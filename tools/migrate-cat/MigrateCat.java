import function.Cat;
import function.One;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性回填：给 table_01~12 的 cat 列按关键词补分类。
 *
 * 用法：
 *   java MigrateCat --dry-run     只看会改多少，不写库
 *   java MigrateCat --apply       先备份成 table_XX_bak_<日期>，再 UPDATE
 *
 * 规则与 function.Cat 同源（页面录入 / AI 入库用的也是它），不另写一套。
 * 只填空值/默认值（cat 为空或 '其他' 且能猜出别的类），已有分类一律不覆盖。
 */
public class MigrateCat {

    public static void main(String[] args) throws Exception {
        boolean apply = false;
        for (String a : args) if ("--apply".equals(a)) apply = true;
        boolean dry = !apply;

        Connection conn = One.getConn();
        if (conn == null) {
            System.out.println("数据库连接失败，退出。");
            return;
        }

        String stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int totalChanged = 0, totalRows = 0;

        for (int m = 1; m <= 12; m++) {
            String t = String.format("table_%02d", m);
            if (!tableExists(conn, t)) continue;
            if (!columnExists(conn, t, "cat")) {
                System.out.println(t + " 缺 cat 列 —— 先启动一次 WebServer 让表结构自愈");
                continue;
            }

            Map<String, Integer> before = tally(conn, t);
            if (before.isEmpty()) continue;
            totalRows += before.values().stream().mapToInt(Integer::intValue).sum();

            // 月表没有主键，按 (date, goods, price, num_1) 定位；同元组的值本来也一样
            List<Object[]> fixes = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT date, goods, price, num_1, cat FROM " + t)) {
                while (rs.next()) {
                    String goods = rs.getString("goods");
                    String cat = rs.getString("cat");
                    if (cat != null && !cat.isEmpty() && !"其他".equals(cat)) continue;
                    String guess = Cat.guess(goods);
                    if (guess.equals(cat == null ? "" : cat)) continue;
                    fixes.add(new Object[]{ rs.getInt("date"), goods, rs.getDouble("price"),
                            rs.getInt("num_1"), guess });
                }
            }

            System.out.println(t + " 行数 " + before.values().stream().mapToInt(Integer::intValue).sum()
                    + " 现有分类 " + before + " 待回填 " + fixes.size());
            if (fixes.isEmpty()) continue;

            if (dry) { totalChanged += fixes.size(); continue; }

            String bak = t + "_bak_" + stamp;
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + bak + " AS SELECT * FROM " + t);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + t + " SET cat=? WHERE date=? AND goods=? AND price=? AND num_1=?")) {
                for (Object[] f : fixes) {
                    ps.setString(1, (String) f[4]);
                    ps.setInt(2, (Integer) f[0]);
                    ps.setString(3, (String) f[1]);
                    ps.setDouble(4, (Double) f[2]);
                    ps.setInt(5, (Integer) f[3]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            totalChanged += fixes.size();
            System.out.println("  已回填 " + fixes.size() + " 行（备份 " + bak + "）→ " + tally(conn, t));
        }

        System.out.println((dry ? "[dry-run] 将回填 " : "共回填 ") + totalChanged + " 行 / 总计 " + totalRows + " 行");
        if (dry) System.out.println("加 --apply 真正执行（会先建 table_XX_bak_<日期> 备份表）");
        conn.close();
    }

    private static boolean tableExists(Connection c, String t) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        }
    }

    private static boolean columnExists(Connection c, String t, String col) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
            ps.setString(1, t);
            ps.setString(2, col);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        }
    }

    private static Map<String, Integer> tally(Connection c, String t) throws Exception {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT cat, COUNT(*) FROM " + t + " GROUP BY cat")) {
            while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
        }
        return m;
    }
}
