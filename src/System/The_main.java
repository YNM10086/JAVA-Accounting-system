package System;

import function.Date_time;
import function.Line;
import function.Menu;
import function.One;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class The_main {

    // 读取整数，输入quit返回null
    private static Integer readInt(Scanner in, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = in.nextLine();
            if ("quit".equalsIgnoreCase(s)) return null;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("格式错误，请输入整数！");
            }
        }
    }

    // 读取小数，输入quit返回null
    private static Double readDouble(Scanner in, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = in.nextLine();
            if ("quit".equalsIgnoreCase(s)) return null;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                System.out.println("格式错误，请输入数字！");
            }
        }
    }

    // ==================== 表名工具 ====================

    // 根据当前月份返回表名：1月→table_01
    private static String getTableName() {
        int m = Date_time.getMonth();
        return String.format("table_%02d", m);
    }

    // ==================== 功能一：存入消费 ====================

    private static void saveConsumption() {
        Scanner in = new Scanner(System.in);
        int day = Date_time.getDay();
        String table = getTableName();
        System.out.printf("当前表: %s | 日期(日): %02d\n", table, day);

        // 消费类型子菜单
        int num1;
        while (true) {
            int ch = Menu.consumeTypeMenu();
            if (ch == -2) return;
            if (ch == 1) { num1 = 1; break; }
            if (ch == 2) { num1 = 2; break; }
            System.out.println("请输入1或2！");
        }

        System.out.println("（输入quit可随时退出）");
        while (true) {
            System.out.print("请输入商品名称：");
            String goods = in.nextLine();
            if ("quit".equalsIgnoreCase(goods)) break;
            if (goods.trim().isEmpty()) {
                System.out.println("商品名不能为空！");
                continue;
            }

            Double price = readDouble(in, "请输入价格：");
            if (price == null) break;

            int saveDay = day;
            if (num1 == 1) {
                System.out.printf("确认日期（默认%02d日，直接回车确认，输入quit退出）：", day);
                String s = in.nextLine();
                if ("quit".equalsIgnoreCase(s)) break;
                if (!s.trim().isEmpty()) {
                    try {
                        saveDay = Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        System.out.println("日期格式错误，使用默认日期。");
                    }
                }
            }

            Connection conn = One.getConn();
            if (conn == null) continue;
            try {
                String sql = "INSERT INTO " + table + " (date, goods, price, num_1) VALUES (?, ?, ?, ?)";
                PreparedStatement ps = One.getPreparedStmt(conn, sql);
                ps.setInt(1, saveDay);
                ps.setString(2, goods);
                ps.setDouble(3, price);
                ps.setInt(4, num1);
                int rows = ps.executeUpdate();
                System.out.println(rows > 0 ? "存入成功！" : "存入失败！");
                ps.close();
                conn.close();
            } catch (Exception e) {
                System.out.println("存入异常！");
                e.printStackTrace();
            }
        }
    }

    // ==================== 功能二：查询消费 ====================

    // 进入功能二时先调用子菜单
    private static void queryConsumption() {
        Menu.runQuery(The_main::queryTotal, The_main::queryDaily, The_main::queryDayDetail, The_main::querySpecial);
    }

    // 子菜单1：查询总消费与日均消费
    private static void queryTotal() {
        String table = getTableName();
        Connection conn = One.getConn();
        if (conn == null) return;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT SUM(price), COUNT(DISTINCT date), COUNT(*), " +
                "COALESCE(SUM(CASE WHEN num_1 = 1 THEN price END) / NULLIF(COUNT(DISTINCT CASE WHEN num_1 = 1 THEN date END), 0), 0) FROM " + table);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                double total = rs.getDouble(1);
                int days = rs.getInt(2);
                int count = rs.getInt(3);
                double dailyAvg = rs.getDouble(4);
                System.out.printf("总消费: %.2f | 日均消费: %.2f | 天数: %d | 记录数: %d\n",
                    total, dailyAvg, days, count);
            } else {
                System.out.println("暂无消费记录。");
            }
            rs.close();
            ps.close();
            conn.close();
        } catch (Exception e) {
            System.out.println("查询异常！");
            e.printStackTrace();
        }
    }

    // 确认操作
    private static boolean confirm(Scanner in, String msg) {
        System.out.print(msg + " (y/n): ");
        return "y".equalsIgnoreCase(in.nextLine());
    }

    // 子菜单3：查询指定日消费明细（含删除/修改子菜单）
    private static void queryDayDetail() {
        String table = getTableName();
        Scanner in = new Scanner(System.in);
        Integer day = readInt(in, "请输入要查询的日数：");
        if (day == null) return;

        while (true) {
            if (!showDailyDetail(table, day)) return; // 无记录则返回
            System.out.println("1. 删除记录  2. 更改内容  3. 返回");
            System.out.print("请选择: ");
            String ch = in.nextLine();
            switch (ch) {
                case "1" -> deleteRecord(in, table, day);
                case "2" -> editRecord(in, table, day);
                case "3" -> { return; }
                default -> System.out.println("输入无效！");
            }
        }
    }

    // 显示指定日消费清单，返回是否有记录
    private static boolean showDailyDetail(String table, int day) {
        Connection conn = One.getConn();
        if (conn == null) return false;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT goods, price FROM " + table + " WHERE date = ?");
            ps.setInt(1, day);
            ResultSet rs = ps.executeQuery();
            if (!rs.isBeforeFirst()) {
                System.out.println("该日暂无消费记录。");
                rs.close();
                ps.close();
                conn.close();
                return false;
            }
            System.out.printf("\n--- %02d日消费明细 ---\n", day);
            while (rs.next()) {
                System.out.printf("  %s | %.2f\n", rs.getString(1), rs.getDouble(2));
            }
            rs.close();
            ps.close();
            conn.close();
            return true;
        } catch (Exception e) {
            System.out.println("查询异常！");
            e.printStackTrace();
            return false;
        }
    }

    // 删除指定日中的某条记录
    private static void deleteRecord(Scanner in, String table, int day) {
        System.out.print("请输入要删除的商品名：");
        String goods = in.nextLine();
        if (!confirm(in, "确认删除 " + goods + "？")) return;

        Connection conn = One.getConn();
        if (conn == null) return;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "DELETE FROM " + table + " WHERE date = ? AND goods = ?");
            ps.setInt(1, day);
            ps.setString(2, goods);
            int rows = ps.executeUpdate();
            System.out.println(rows > 0 ? "删除成功！" : "未找到该商品。");
            ps.close();
            conn.close();
        } catch (Exception e) {
            System.out.println("删除异常！");
            e.printStackTrace();
        }
    }

    // 更改指定日中的某条记录
    private static void editRecord(Scanner in, String table, int day) {
        System.out.print("请输入要更改的商品名：");
        String oldGoods = in.nextLine();

        System.out.print("请输入新商品名：");
        String newGoods = in.nextLine();
        if (newGoods.trim().isEmpty()) { System.out.println("商品名不能为空！"); return; }

        Double newPrice = readDouble(in, "请输入新价格：");
        if (newPrice == null) return;

        if (!confirm(in, "确认将 " + oldGoods + " 改为 " + newGoods + " / " + newPrice + "？")) return;

        Connection conn = One.getConn();
        if (conn == null) return;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "UPDATE " + table + " SET goods = ?, price = ? WHERE date = ? AND goods = ?");
            ps.setString(1, newGoods);
            ps.setDouble(2, newPrice);
            ps.setInt(3, day);
            ps.setString(4, oldGoods);
            int rows = ps.executeUpdate();
            System.out.println(rows > 0 ? "更改成功！" : "未找到该商品。");
            ps.close();
            conn.close();
        } catch (Exception e) {
            System.out.println("更改异常！");
            e.printStackTrace();
        }
    }

    // 子菜单2：生成每日消费折线图
    private static void queryDaily() {
        String table = getTableName();
        Connection conn = One.getConn();
        if (conn == null) return;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT date, SUM(price) FROM " + table + " WHERE num_1 = 1 GROUP BY date ORDER BY date");
            ResultSet rs = ps.executeQuery();
            List<Integer> days = new ArrayList<>();
            List<Double> totals = new ArrayList<>();
            while (rs.next()) {
                days.add(rs.getInt(1));
                totals.add(rs.getDouble(2));
            }
            rs.close();
            ps.close();
            conn.close();

            if (days.isEmpty()) {
                System.out.println("暂无消费记录。");
                return;
            }
            Line.drawLineChart(days, totals, table);
            System.out.println("折线图已生成：The_Pic/" + table + "_chart.png");
        } catch (Exception e) {
            System.out.println("查询异常！");
            e.printStackTrace();
        }
    }

    // 子菜单4：查询特殊消费（num_1=2）
    private static void querySpecial() {
        String table = getTableName();
        Connection conn = One.getConn();
        if (conn == null) return;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT date, goods, price FROM " + table + " WHERE num_1 = 2 ORDER BY date");
            ResultSet rs = ps.executeQuery();
            if (!rs.isBeforeFirst()) {
                System.out.println("暂无特殊消费记录。");
            } else {
                System.out.println("\n--- 特殊消费记录 ---");
                while (rs.next()) {
                    System.out.printf("%02d日 | %s | %.2f\n", rs.getInt(1), rs.getString(2), rs.getDouble(3));
                }
            }
            rs.close();
            ps.close();
            conn.close();
        } catch (Exception e) {
            System.out.println("查询异常！");
            e.printStackTrace();
        }
    }

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        try {
            function.WebServer.start();
        } catch (Exception e) {
            System.out.println("Web服务器启动失败！");
            e.printStackTrace();
        }
    }
}
