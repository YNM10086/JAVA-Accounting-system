package function;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总览的数据层：一次连库把一屏要用的东西全算出来。
 *
 * 页面只负责排版，不算数；分类与性质的合计必须能对上（两边都是 SUM(price)）。
 * 预算口径 1500，与 budgetPage 一致（后续做设置页时改成可配置）。
 */
public class Overview {

    /** 没有单独设置过预算时用的默认值；每月可在预算段改 */
    public static final double DEFAULT_BUDGET = 1500;

    public int month, days, count;
    public double income, expense, prevExpense, daily, special, budget = DEFAULT_BUDGET;
    public double[] perDay = new double[32];              // 1..31 每日支出
    public double[] perMonth = new double[13];            // 1..12 每月总支出（全年）
    public double[] perDayPrev = new double[32];
    public LinkedHashMap<String, Double> cats = new LinkedHashMap<>(); // 按金额降序
    public List<Row> recent = new ArrayList<>();
    public List<Row> all = new ArrayList<>();      // 当月全部记录，前端筛选用

    public static class Row {
        public int date, num1;
        public String goods, cat;
        public double price;
    }

    public static Overview load() { return load(Date_time.getMonth()); }

    public static Overview load(int month) {
        Overview o = new Overview();
        o.month = (month >= 1 && month <= 12) ? month : Date_time.getMonth();
        o.days = java.time.LocalDate.of(Date_time.getYear(), o.month, 1).lengthOfMonth();
        String table = String.format("table_%02d", o.month);
        String prevTable = String.format("table_%02d", o.month == 1 ? 12 : o.month - 1);

        Connection conn = One.getConn();
        if (conn == null) return o;
        try {
            WebServer.ensureCatColumn(conn);
            o.income = income(conn, o.month);
            o.budget = budgetOf(conn, o.month);

            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT COALESCE(SUM(price),0), COUNT(*), " +
                        "COALESCE(SUM(CASE WHEN num_1=1 THEN price ELSE 0 END),0), " +
                        "COALESCE(SUM(CASE WHEN num_1=2 THEN price ELSE 0 END),0) FROM " + table)) {
                    if (rs.next()) {
                        o.expense = rs.getDouble(1);
                        o.count = rs.getInt(2);
                        o.daily = rs.getDouble(3);
                        o.special = rs.getDouble(4);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT date, SUM(price) FROM " + table + " GROUP BY date")) {
                    while (rs.next()) {
                        int d = rs.getInt(1);
                        if (d >= 1 && d <= 31) o.perDay[d] = rs.getDouble(2);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT cat, SUM(price) FROM " + table + " GROUP BY cat ORDER BY 2 DESC")) {
                    while (rs.next()) o.cats.put(rs.getString(1) == null ? "其他" : rs.getString(1), rs.getDouble(2));
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT date, goods, price, num_1, cat FROM " + table + " ORDER BY date DESC LIMIT 500")) {
                    while (rs.next()) {
                        Row r = new Row();
                        r.date = rs.getInt(1);
                        r.goods = rs.getString(2);
                        r.price = rs.getDouble(3);
                        r.num1 = rs.getInt(4);
                        r.cat = rs.getString(5) == null ? "其他" : rs.getString(5);
                        o.all.add(r);
                        if (o.recent.size() < 6) o.recent.add(r);
                    }
                }
            }

            // 全年 12 个月的总额（缺表就是 0）
            for (int m = 1; m <= 12; m++) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(price),0) FROM " + String.format("table_%02d", m))) {
                    if (rs.next()) o.perMonth[m] = rs.getDouble(1);
                } catch (Exception ignore) { /* 该月表不存在 */ }
            }

            // 环比：上月总收入支出（表不存在就算了，不影响本月）
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(price),0) FROM " + prevTable)) {
                if (rs.next()) o.prevExpense = rs.getDouble(1);
            } catch (Exception ignore) { /* 上月没有表 */ }
        } catch (Exception e) {
            System.out.println("总览取数失败：" + e.getMessage());
        } finally {
            try { conn.close(); } catch (Exception ignored) {}
        }
        return o;
    }

    /** 当月预算：没设过就回默认 1500 */
    private static double budgetOf(Connection conn, int month) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS budgets (month INT PRIMARY KEY, amount DECIMAL(10,2) NOT NULL)");
        } catch (Exception ignore) { /* 建表失败不影响读 */ }
        try (PreparedStatement ps = conn.prepareStatement("SELECT amount FROM budgets WHERE month = ?")) {
            ps.setInt(1, month);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (Exception ignore) { /* 表还没建 */ }
        return DEFAULT_BUDGET;
    }

    private static double income(Connection conn, int month) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(amount),0) FROM income_records WHERE month = ?")) {
            ps.setInt(1, month);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (Exception ignore) { /* 表还没建 */ }
        return 0;
    }

    /** 换月联动用：只要结构图和表需要的那几项 */
    public String monthJson() {
        return "{\"month\":" + month
            + ",\"expense\":" + Math.round(expense * 100) / 100.0
            + ",\"count\":" + count
            + ",\"cats\":[" + catsJson() + "]"
            + ",\"types\":[{\"n\":\"日常\",\"v\":" + Math.round(daily * 100) / 100.0
            + "},{\"n\":\"特殊\",\"v\":" + Math.round(special * 100) / 100.0 + "}]"
            + ",\"rows\":" + rowsJson()
            + ",\"daily\":[" + dailyJson() + "]"
            + ",\"today\":" + (all.isEmpty() ? 1 : all.get(0).date)
            + ",\"days\":" + days + "}";
    }

    private String dailyJson() {
        StringBuilder b = new StringBuilder();
        for (int d = 1; d <= days; d++) {
            if (d > 1) b.append(',');
            b.append(Math.round(perDay[d] * 100) / 100.0);
        }
        return b.toString();
    }

    private String rowsJson() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < all.size(); i++) {
            Row r = all.get(i);
            if (i > 0) b.append(',');
            b.append("{\"d\":").append(r.date)
             .append(",\"g\":\"").append(Json.esc(r.goods)).append("\"")
             .append(",\"p\":").append(r.price)
             .append(",\"n\":").append(r.num1)
             .append(",\"c\":\"").append(Json.esc(r.cat)).append("\"}");
        }
        return b.append(']').toString();
    }

    private String catsJson() {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Double> e : cats.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"n\":\"").append(Json.esc(e.getKey())).append("\",\"v\":")
             .append(Math.round(e.getValue() * 100) / 100.0).append('}');
        }
        return b.toString();
    }

    /** 供前端画图：{daily:[...], cats:[{n,v}], types:[{n,v}], budget, expense, income} */
    public String json() {
        StringBuilder b = new StringBuilder("{\"daily\":[");
        for (int d = 1; d <= days; d++) {
            if (d > 1) b.append(',');
            b.append(Math.round(perDay[d] * 100) / 100.0);
        }
        b.append("],\"months\":[");
        for (int m = 1; m <= 12; m++) {
            if (m > 1) b.append(',');
            b.append(Math.round(perMonth[m] * 100) / 100.0);
        }
        b.append("],\"month\":").append(month)
         .append(",\"today\":").append(Date_time.getDay())
         .append(",\"daysInMonth\":").append(days)
         .append(",\"cats\":[");
        boolean first = true;
        for (Map.Entry<String, Double> e : cats.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"n\":\"").append(Json.esc(e.getKey())).append("\",\"v\":")
             .append(Math.round(e.getValue() * 100) / 100.0).append('}');
        }
        b.append("],\"types\":[{\"n\":\"日常\",\"v\":").append(Math.round(daily * 100) / 100.0)
         .append("},{\"n\":\"特殊\",\"v\":").append(Math.round(special * 100) / 100.0).append("}]")
         .append(",\"budget\":").append(Math.round(budget * 100) / 100.0)
         .append(",\"expense\":").append(Math.round(expense * 100) / 100.0)
         .append(",\"income\":").append(Math.round(income * 100) / 100.0)
         .append(",\"rows\":").append(rowsJson())
         .append('}');
        return b.toString();
    }
}
