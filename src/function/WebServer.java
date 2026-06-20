package function;

import com.sun.net.httpserver.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class WebServer {

    private static final int[] PORTS = {8080, 8081, 8082, 9090};
    private static boolean splashPending = true;

    public static void start() throws Exception {
        HttpServer srv = null;
        int usedPort = 0;
        for (int port : PORTS) {
            try {
                srv = HttpServer.create(new InetSocketAddress(port), 0);
                usedPort = port;
                break;
            } catch (BindException e) {
                System.out.println("端口 " + port + " 被占用，尝试下一个...");
            }
        }
        if (srv == null) {
            System.out.println("所有端口均被占用，请检查是否有已运行的实例！");
            return;
        }

        srv.createContext("/", WebServer::handle);
        srv.setExecutor(null);
        srv.start();

        String url = "http://localhost:" + usedPort;
        System.out.println("====================================");
        System.out.println("Web服务器已启动: " + url);
        System.out.println("如果浏览器未自动打开，请手动访问上述地址");
        System.out.println("====================================");

        try { Desktop.getDesktop().browse(new URI(url)); } catch (Exception ignored) {}
    }

    // ==================== 路由 ====================

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        try {
            switch (path) {
                case "/"             -> {
                    if (splashPending) html(ex, splashPage());
                    else redirect(ex, "/main");
                }
                case "/splash-done"  -> { splashPending = false; redirect(ex, "/main"); }
                case "/main"         -> html(ex, mainPage());
                case "/save"         -> { if ("POST".equals(method)) handleSavePost(ex); else html(ex, savePage(null)); }
                case "/query"        -> { if ("POST".equals(method)) handleDetailPost(ex); else html(ex, queryPage(ex)); }
                case "/budget"       -> html(ex, budgetPage());
                case "/export"       -> handleExport(ex);
                case "/chart-img"    -> serveChart(ex);
                default              -> {
                    if (path.startsWith("/static/")) serveStatic(ex, path);
                    else redirect(ex, "/main");
                }
            }
        } catch (Exception e) {
            html(ex, errorPage(e.getMessage()));
        }
    }

    // ==================== 登录 ====================

    // ==================== 存入消费 ====================

    private static void handleSavePost(HttpExchange ex) throws Exception {
        Map<String, String> f = parseForm(ex);
        String goods = f.get("goods");
        String priceStr = f.get("price");
        String typeStr = f.get("type");

        if (goods == null || goods.trim().isEmpty() || priceStr == null || typeStr == null) {
            html(ex, savePage("商品名和价格不能为空")); return;
        }
        double price;
        try { price = Double.parseDouble(priceStr); } catch (NumberFormatException e) {
            html(ex, savePage("价格格式错误")); return;
        }
        int num1 = "1".equals(typeStr) ? 1 : 2;

        String dayStr = f.get("day");
        int day;
        try {
            day = (dayStr != null && !dayStr.trim().isEmpty()) ? Integer.parseInt(dayStr) : Date_time.getDay();
        } catch (NumberFormatException e) {
            day = Date_time.getDay();
        }

        String table = getMonthTable();
        Connection conn = One.getConn();
        if (conn == null) { html(ex, savePage("数据库连接失败")); return; }
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "INSERT INTO " + table + " (date, goods, price, num_1) VALUES (?, ?, ?, ?)");
            ps.setInt(1, day);
            ps.setString(2, goods.trim());
            ps.setDouble(3, price);
            ps.setInt(4, num1);
            ps.executeUpdate();
            ps.close();
            conn.close();
            html(ex, savePage("存入成功！"));
        } catch (Exception e) {
            conn.close();
            html(ex, savePage("存入失败：" + e.getMessage()));
        }
    }

    // ==================== 消费查询（融合总消费+折线图+明细+特殊） ====================

    private static String queryPage(HttpExchange ex) throws Exception {
        // 解析月份参数，默认当前月
        int curMonth = Date_time.getMonth();
        int selMonth = curMonth;
        int selDay = Date_time.getDay();
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            Map<String, String> params = parseQuery(q);
            try { selMonth = Integer.parseInt(params.get("month")); } catch (Exception ignored) {}
            try { selDay = Integer.parseInt(params.get("day")); } catch (Exception ignored) {}
        }
        if (selMonth < 1 || selMonth > 12) selMonth = curMonth;
        String table = String.format("table_%02d", selMonth);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='page-header-row'>");
        sb.append("<h2>消费查询</h2>");
        sb.append("<a href='/export?month=").append(selMonth).append("' class='btn btn-sm btn-export'>📥 导出Excel</a>");
        sb.append("</div>");

        sb.append("<div class='filter-bar'>");
        for (int m = 1; m <= 12; m++) {
            sb.append("<a href='?month=").append(m).append("' class='filter-pill").append(m == selMonth ? " active" : "").append("'>").append(m).append("月</a>");
        }
        sb.append("</div>");
        sb.append("<p class='hint'>当前查询: ").append(selMonth).append("月").append(selMonth == curMonth ? " (本月)" : "").append("</p>");

        // === 一、统计面板 ===
        sb.append("<section class='query-section'><h3>月度统计</h3>");
        Connection conn = One.getConn();
        if (conn == null) return errorPage("数据库连接失败");
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT SUM(price), COUNT(DISTINCT date), COUNT(*), " +
                "COALESCE(SUM(CASE WHEN num_1=1 THEN price END)/NULLIF(COUNT(DISTINCT CASE WHEN num_1=1 THEN date END),0),0) FROM " + table);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(3) > 0) {
                sb.append("<div class='stat-row'>");
                sb.append("<div class='stat'><span>总消费</span><strong>¥").append(String.format("%.2f", rs.getDouble(1))).append("</strong></div>");
                sb.append("<div class='stat'><span>日均消费</span><strong>¥").append(String.format("%.2f", rs.getDouble(4))).append("</strong></div>");
                sb.append("<div class='stat'><span>消费天数</span><strong>").append(rs.getInt(2)).append(" 天</strong></div>");
                sb.append("<div class='stat'><span>总记录数</span><strong>").append(rs.getInt(3)).append(" 条</strong></div>");
                sb.append("</div>");
                sb.append("<p class='hint'>日均消费仅统计日常消费</p>");
            } else {
                sb.append("<p>暂无消费记录。</p>");
            }
            rs.close(); ps.close();
        } finally { conn.close(); }
        sb.append("</section>");

        // === 二、折线图 ===
        sb.append("<section class='query-section'><h3>每日趋势</h3>");
        Connection conn2 = One.getConn();
        if (conn2 != null) {
            try {
                PreparedStatement ps2 = One.getPreparedStmt(conn2,
                    "SELECT date, SUM(price) FROM " + table + " WHERE num_1=1 GROUP BY date ORDER BY date");
                ResultSet rs2 = ps2.executeQuery();
                List<Integer> days = new ArrayList<>();
                List<Double> totals = new ArrayList<>();
                while (rs2.next()) { days.add(rs2.getInt(1)); totals.add(rs2.getDouble(2)); }
                rs2.close(); ps2.close();
                if (!days.isEmpty()) {
                    Line.drawLineChart(days, totals, table);
                    sb.append("<img src='/chart-img?f=").append(table).append("_chart.png' class='chart-img' alt='").append(selMonth).append("月每日消费折线图' onerror=\"this.outerHTML='<p>图片加载失败</p>'\">");
                } else {
                    sb.append("<p>暂无数据。</p>");
                }
            } finally { conn2.close(); }
        }
        sb.append("</section>");

        // === 三、日消费明细 ===
        sb.append("<section class='query-section'><h3>日消费明细</h3>");
        sb.append("<form class='inline-form' action='/query' method='get'>");
        sb.append("<input type='hidden' name='month' value='").append(selMonth).append("'>");
        sb.append("<div class='input-wrap' style='flex:1'><svg class='input-icon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5' width='18' height='18'><rect x='3' y='4' width='18' height='18' rx='2'/><path d='M16 2v4M8 2v4M3 10h18'/></svg>");
        sb.append("<input type='number' name='day' min='1' max='31' placeholder='输入日期(日)' value='").append(selDay > 0 ? selDay : Date_time.getDay()).append("' required></div>");
        sb.append("<button type='submit' class='btn'>查询</button>");
        sb.append("</form>");

        if (selDay > 0) {
            Connection conn3 = One.getConn();
            if (conn3 != null) {
                try {
                    PreparedStatement ps3 = One.getPreparedStmt(conn3,
                        "SELECT goods, price, num_1 FROM " + table + " WHERE date=?");
                    ps3.setInt(1, selDay);
                    ResultSet rs3 = ps3.executeQuery();
                    if (!rs3.isBeforeFirst()) {
                        sb.append("<p>该日暂无消费记录。</p>");
                    } else {
                        sb.append("<table><tr><th>商品</th><th>价格</th><th>类型</th><th>操作</th></tr>");
                        while (rs3.next()) {
                            String g = rs3.getString(1);
                            double p = rs3.getDouble(2);
                            int n1 = rs3.getInt(3);
                            sb.append("<tr><td>").append(esc(g)).append("</td><td>¥").append(String.format("%.2f", p))
                                .append("</td><td>").append(n1 == 1 ? "日常" : "固定").append("</td><td>");
                            sb.append("<form method='post' action='/query?month=").append(selMonth).append("&day=").append(selDay).append("' style='display:inline' onsubmit=\"return confirm('确认删除 ").append(esc(g)).append("？')\">");
                            sb.append("<input type='hidden' name='action' value='delete'>");
                            sb.append("<input type='hidden' name='day' value='").append(selDay).append("'>");
                            sb.append("<input type='hidden' name='goods' value='").append(esc(g)).append("'>");
                            sb.append("<button class='btn btn-sm btn-danger'>删除</button></form> ");
                            sb.append("<button class='btn btn-sm' onclick=\"editRow('").append(escJS(g)).append("',").append(p).append(")\">编辑</button>");
                            sb.append("</td></tr>");
                        }
                        sb.append("</table>");
                        // 编辑表单
                        sb.append("<div id='editBox' class='edit-box' style='display:none'>");
                        sb.append("<h4>编辑记录</h4>");
                        sb.append("<form method='post' action='/query?month=").append(selMonth).append("&day=").append(selDay).append("'>");
                        sb.append("<input type='hidden' name='action' value='edit'>");
                        sb.append("<input type='hidden' name='day' value='").append(selDay).append("'>");
                        sb.append("<input type='hidden' name='oldGoods' id='eOld'>");
                        sb.append("<div class='input-group'><label>商品名</label><div class='input-wrap'><input name='newGoods' id='eName' required></div></div>");
                        sb.append("<div class='input-group'><label>价格</label><div class='input-wrap'><input name='newPrice' id='ePrice' type='number' step='0.01' required></div></div>");
                        sb.append("<div style='display:flex;gap:8px;margin-top:12px'><button class='btn'>保存</button> ");
                        sb.append("<button type='button' class='btn' style='background:var(--text2)' onclick=\"document.getElementById('editBox').style.display='none'\">取消</button>");
                        sb.append("</div></form></div>");
                        sb.append("<script>function editRow(g,p){var b=document.getElementById('editBox');b.style.display='block';document.getElementById('eOld').value=g;document.getElementById('eName').value=g;document.getElementById('ePrice').value=p;b.scrollIntoView()}</script>");
                    }
                    rs3.close(); ps3.close();
                } finally { conn3.close(); }
            }
        }
        sb.append("</section>");

        // === 四、特殊消费 ===
        sb.append("<section class='query-section'><h3>特殊消费</h3>");
        Connection conn4 = One.getConn();
        if (conn4 != null) {
            try {
                PreparedStatement ps4 = One.getPreparedStmt(conn4,
                    "SELECT date, goods, price FROM " + table + " WHERE num_1=2 ORDER BY date");
                ResultSet rs4 = ps4.executeQuery();
                if (!rs4.isBeforeFirst()) {
                    sb.append("<p>暂无特殊消费记录。</p>");
                } else {
                    sb.append("<table><tr><th>日期</th><th>商品</th><th>价格</th></tr>");
                    while (rs4.next()) {
                        sb.append("<tr><td>").append(rs4.getInt(1)).append("日</td>");
                        sb.append("<td>").append(esc(rs4.getString(2))).append("</td>");
                        sb.append("<td>¥").append(String.format("%.2f", rs4.getDouble(3))).append("</td></tr>");
                    }
                    sb.append("</table>");
                }
                rs4.close(); ps4.close();
            } finally { conn4.close(); }
        }
        sb.append("</section>");

        return page("消费查询", sb.toString());
    }

    // ==================== 折线图 ====================

    private static String chartPage() throws Exception {
        String table = getMonthTable();
        Connection conn = One.getConn();
        if (conn == null) return errorPage("数据库连接失败");

        PreparedStatement ps = One.getPreparedStmt(conn,
            "SELECT date, SUM(price) FROM " + table + " WHERE num_1 = 1 GROUP BY date ORDER BY date");
        ResultSet rs = ps.executeQuery();
        List<Integer> days = new ArrayList<>();
        List<Double> totals = new ArrayList<>();
        while (rs.next()) { days.add(rs.getInt(1)); totals.add(rs.getDouble(2)); }
        rs.close(); ps.close(); conn.close();

        if (days.isEmpty())
            return page("消费折线图", "<h2>每日消费折线图</h2><p>暂无消费记录。</p>");

        Line.drawLineChart(days, totals, table);
        String img = table + "_chart.png";
        return page("消费折线图",
            "<h2>每日消费折线图</h2><p class='hint'>仅统计日常消费(num_1=1)</p>" +
            "<img src='/chart-img?f=" + img + "' class='chart-img' onerror=\"this.outerHTML='<p>图片加载失败</p>'\">");
    }

    private static void serveChart(HttpExchange ex) throws IOException {
        String f = parseQuery(ex.getRequestURI().getQuery()).get("f");
        if (f == null) { ex.sendResponseHeaders(404, -1); return; }
        File file = new File("The_Pic/" + f);
        if (!file.exists()) { ex.sendResponseHeaders(404, -1); return; }
        byte[] bytes = Files.readAllBytes(file.toPath());
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        File file = new File(path.substring(1));
        if (!file.exists()) { ex.sendResponseHeaders(404, -1); return; }
        byte[] bytes = Files.readAllBytes(file.toPath());
        String ct = path.endsWith(".js") ? "application/javascript" :
            path.endsWith(".css") ? "text/css" : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ==================== Excel 导出 ====================

    private static void handleExport(HttpExchange ex) {
        int month = Date_time.getMonth();
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            try { month = Integer.parseInt(parseQuery(q).get("month")); } catch (Exception ignored) {}
        }
        String table = String.format("table_%02d", month);

        Connection conn = One.getConn();
        if (conn == null) {
            try { html(ex, errorPage("数据库连接失败")); } catch (Exception ignored) {}
            return;
        }

        try {
            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset='UTF-8'><style>");
            html.append("table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 10px;font-size:12px}");
            html.append("th{background:#e2e2ec;font-weight:bold;text-align:center}");
            html.append(".price{text-align:right}.date{text-align:center}.gap{width:40px;border:none}");
            html.append("h2{font-size:14px;margin:0 0 8px}");
            html.append("</style></head><body>");
            html.append("<h2>日常消费</h2>");
            html.append("<table><tr><th>日期</th><th>商品名</th><th>价格</th></tr>");

            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT date, goods, price FROM " + table + " WHERE num_1=1 ORDER BY date");
            ResultSet rs = ps.executeQuery();
            double dailyTotal = 0;
            while (rs.next()) {
                double p = rs.getDouble("price");
                dailyTotal += p;
                html.append("<tr><td class='date'>").append(rs.getInt("date")).append("日</td>");
                html.append("<td>").append(esc(rs.getString("goods"))).append("</td>");
                html.append("<td class='price'>¥").append(String.format("%.2f", p)).append("</td></tr>");
            }
            rs.close(); ps.close();
            html.append("<tr style='font-weight:bold;background:#f0f0f0'><td colspan='2' style='text-align:right'>合计</td>");
            html.append("<td class='price'>¥").append(String.format("%.2f", dailyTotal)).append("</td></tr>");

            html.append("</table>");
            html.append("<br><h2>特殊消费</h2>");
            html.append("<table><tr><th>日期</th><th>商品名</th><th>价格</th></tr>");

            ps = One.getPreparedStmt(conn,
                "SELECT date, goods, price FROM " + table + " WHERE num_1=2 ORDER BY date");
            rs = ps.executeQuery();
            double fixedTotal = 0;
            while (rs.next()) {
                double p = rs.getDouble("price");
                fixedTotal += p;
                html.append("<tr><td class='date'>").append(rs.getInt("date")).append("日</td>");
                html.append("<td>").append(esc(rs.getString("goods"))).append("</td>");
                html.append("<td class='price'>¥").append(String.format("%.2f", p)).append("</td></tr>");
            }
            rs.close(); ps.close();
            html.append("<tr style='font-weight:bold;background:#f0f0f0'><td colspan='2' style='text-align:right'>合计</td>");
            html.append("<td class='price'>¥").append(String.format("%.2f", fixedTotal)).append("</td></tr>");
            conn.close();

            html.append("</table></body></html>");

            byte[] bytes = html.toString().getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/vnd.ms-excel; charset=UTF-8");
            String fn = new String((month + "月消费数据.xls").getBytes("UTF-8"), "ISO-8859-1");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fn + "\"");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }

        } catch (Exception e) {
            try { conn.close(); } catch (Exception ignored) {}
            e.printStackTrace();
        }
    }

    // ==================== 日消费明细 ====================

    private static String detailPage(HttpExchange ex) throws Exception {
        int selDay = -1;
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            try { selDay = Integer.parseInt(parseQuery(q).get("day")); } catch (Exception ignored) {}
        }

        StringBuilder body = new StringBuilder();
        body.append("<h2>查询指定日消费明细</h2>");
        body.append("<form class='inline-form' action='/detail' method='get'>");
        body.append("<input type='number' name='day' min='1' max='31' placeholder='输入日期(日)' value='").append(selDay > 0 ? selDay : "").append("' required>");
        body.append("<button type='submit' class='btn'>查询</button>");
        body.append("</form>");

        if (selDay > 0) {
            String table = getMonthTable();
            Connection conn = One.getConn();
            if (conn == null) return errorPage("数据库连接失败");
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT goods, price, num_1 FROM " + table + " WHERE date = ?");
            ps.setInt(1, selDay);
            ResultSet rs = ps.executeQuery();
            if (!rs.isBeforeFirst()) {
                body.append("<p>该日暂无消费记录。</p>");
            } else {
                body.append("<table><tr><th>商品</th><th>价格</th><th>类型</th><th>操作</th></tr>");
                while (rs.next()) {
                    String g = rs.getString(1);
                    double p = rs.getDouble(2);
                    int n1 = rs.getInt(3);
                    body.append("<tr><td>").append(esc(g)).append("</td><td>¥").append(String.format("%.2f", p))
                        .append("</td><td>").append(n1 == 1 ? "日常" : "固定").append("</td><td>");
                    // 删除表单
                    body.append("<form method='post' action='/detail' style='display:inline' onsubmit=\"return confirm('确认删除 ").append(esc(g)).append("？')\">");
                    body.append("<input type='hidden' name='action' value='delete'>");
                    body.append("<input type='hidden' name='day' value='").append(selDay).append("'>");
                    body.append("<input type='hidden' name='goods' value='").append(esc(g)).append("'>");
                    body.append("<button class='btn btn-sm btn-danger'>删除</button></form> ");
                    // 编辑按钮
                    body.append("<button class='btn btn-sm' onclick=\"editRow('").append(escJS(g)).append("',").append(p).append(")\">编辑</button>");
                    body.append("</td></tr>");
                }
                body.append("</table>");

                // 隐藏编辑表单
                body.append("<div id='editBox' style='display:none;margin-top:16px;background:#fff;padding:20px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.08)'>");
                body.append("<h3>编辑记录</h3>");
                body.append("<form method='post' action='/detail'>");
                body.append("<input type='hidden' name='action' value='edit'>");
                body.append("<input type='hidden' name='day' value='").append(selDay).append("'>");
                body.append("<input type='hidden' name='oldGoods' id='eOld'>");
                body.append("<label>商品名</label><input name='newGoods' id='eName' required>");
                body.append("<label>价格</label><input name='newPrice' id='ePrice' type='number' step='0.01' required>");
                body.append("<button class='btn'>保存</button> ");
                body.append("<button type='button' class='btn' style='background:#999' onclick=\"document.getElementById('editBox').style.display='none'\">取消</button>");
                body.append("</form></div>");

                body.append("<script>function editRow(g,p){var b=document.getElementById('editBox');b.style.display='block';document.getElementById('eOld').value=g;document.getElementById('eName').value=g;document.getElementById('ePrice').value=p;b.scrollIntoView()}</script>");
            }
            rs.close(); ps.close(); conn.close();
        }
        return page("日消费明细", body.toString());
    }

    private static void handleDetailPost(HttpExchange ex) throws Exception {
        Map<String, String> f = parseForm(ex);
        String action = f.get("action");
        int day = Integer.parseInt(f.get("day"));

        // 从query string取月份
        int month = Date_time.getMonth();
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            try { month = Integer.parseInt(parseQuery(q).get("month")); } catch (Exception ignored) {}
        }
        String table = String.format("table_%02d", month);

        Connection conn = One.getConn();
        if (conn == null) { redirect(ex, "/query?month=" + month + "&day=" + day); return; }
        try {
            if ("delete".equals(action)) {
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "DELETE FROM " + table + " WHERE date = ? AND goods = ?");
                ps.setInt(1, day);
                ps.setString(2, f.get("goods"));
                ps.executeUpdate();
                ps.close();
            } else if ("edit".equals(action)) {
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "UPDATE " + table + " SET goods = ?, price = ? WHERE date = ? AND goods = ?");
                ps.setString(1, f.get("newGoods"));
                ps.setDouble(2, Double.parseDouble(f.get("newPrice")));
                ps.setInt(3, day);
                ps.setString(4, f.get("oldGoods"));
                ps.executeUpdate();
                ps.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        conn.close();
        redirect(ex, "/query?month=" + month + "&day=" + day);
    }

    // ==================== 特殊消费 ====================

    private static String specialPage() throws Exception {
        String table = getMonthTable();
        Connection conn = One.getConn();
        if (conn == null) return errorPage("数据库连接失败");

        PreparedStatement ps = One.getPreparedStmt(conn,
            "SELECT date, goods, price FROM " + table + " WHERE num_1 = 2 ORDER BY date");
        ResultSet rs = ps.executeQuery();
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>特殊消费记录 (num_1=2)</h2>");
        if (!rs.isBeforeFirst()) {
            sb.append("<p>暂无特殊消费记录。</p>");
        } else {
            sb.append("<table><tr><th>日期</th><th>商品</th><th>价格</th></tr>");
            while (rs.next()) {
                sb.append("<tr><td>").append(rs.getInt(1)).append("日</td>");
                sb.append("<td>").append(esc(rs.getString(2))).append("</td>");
                sb.append("<td>¥").append(String.format("%.2f", rs.getDouble(3))).append("</td></tr>");
            }
            sb.append("</table>");
        }
        rs.close(); ps.close(); conn.close();
        return page("特殊消费", sb.toString());
    }

    // ==================== HTML 页面组装 ====================

    private static String splashPage() {
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>消费记录系统</title><style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{width:100vw;height:100vh;overflow:hidden;display:flex;align-items:center;justify-content:center;" +
            "font-family:'Microsoft YaHei','PingFang SC',sans-serif;cursor:pointer;" +
            "transition:opacity .6s ease-out;flex-direction:column}" +
            ".splash-bg{position:fixed;top:0;left:0;width:100%;height:100%;z-index:-2;transition:opacity 1.5s ease-in}" +
            ".greeting{font-size:clamp(42px,15vw,120px);font-weight:800;letter-spacing:normal;" +
            "opacity:0;transform:scale(.8);animation:inAnim 1.2s cubic-bezier(.22,1,.36,1) forwards;" +
            "text-shadow:0 2px 12px rgba(0,0,0,.12),0 6px 24px rgba(0,0,0,.08),0 12px 48px rgba(0,0,0,.05);" +
            "filter:drop-shadow(0 2px 8px rgba(0,0,0,.1))}" +
            "@keyframes inAnim{0%{opacity:0;transform:scale(.7) translateY(30px)}" +
            "100%{opacity:1;transform:scale(1) translateY(0)}}" +
            ".sub{font-size:clamp(16px,3.5vw,26px);margin-top:clamp(12px,3vh,25px);opacity:0;" +
            "font-weight:600;letter-spacing:.35em;" +
            "background:linear-gradient(135deg,#d4a017,#f5d76e,#8B6914,#f5d76e,#d4a017);" +
            "-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;" +
            "animation:subIn 1s .6s ease-out forwards}" +
            "@keyframes subIn{0%{opacity:0;transform:translateY(10px)}100%{opacity:1;transform:translateY(0)}}" +
            ".tl-wrap{width:720px;max-width:92vw;margin-top:clamp(28px,6vh,60px);opacity:0;margin-left:auto;margin-right:auto;" +
            "animation:subIn 1s 1s ease-out forwards}" +
            ".tl-bar{display:flex;height:14px;border-radius:7px;overflow:hidden;gap:2px;background:rgba(255,255,255,.12);padding:2px}" +
            ".tl-seg{flex:1;border-radius:3px;transition:all .3s;position:relative}" +
            ".tl-seg.active{transform:scaleY(1.5);border-radius:3px;z-index:1}" +
            ".tl-seg.past{opacity:.55}" +
            ".tl-labels{display:flex;margin-top:8px;font-size:11px;color:rgba(255,255,255,.65);gap:1px;font-weight:500}" +
            ".tl-labels span{flex:1;text-align:center}" +
            ".tl-labels span.active{color:#fff;font-weight:700;font-size:14px;text-shadow:0 0 8px rgba(255,255,255,.4)}" +
            ".tl-periods{display:flex;margin-top:6px;gap:2px;flex-wrap:nowrap}" +
            ".tl-p{flex:1;text-align:center;font-size:10px;letter-spacing:.5px;color:rgba(255,255,255,.5);" +
            "padding:3px 0;border-radius:4px;font-weight:500}" +
            ".tl-p.active{color:#fff;font-weight:700;font-size:12px;text-shadow:0 0 6px rgba(255,255,255,.3)}" +
            ".hint{position:fixed;bottom:clamp(20px,5vh,50px);left:50%;transform:translateX(-50%);" +
            "color:rgba(255,255,255,.5);font-size:13px;letter-spacing:.1em;opacity:0;" +
            "animation:subIn 1s 1.4s ease-out forwards}" +
            ".wipe-overlay{position:fixed;top:50%;left:50%;width:200vmax;height:200vmax;" +
            "border-radius:50%;transform:translate(-50%,-50%) scale(0);z-index:100;pointer-events:none;}" +
            ".wipe-overlay.active{animation:wipeOut .7s cubic-bezier(.4,0,.2,1) forwards}" +
            "@keyframes wipeOut{0%{transform:translate(-50%,-50%) scale(0)}" +
            "50%{opacity:1}100%{transform:translate(-50%,-50%) scale(1);opacity:.95}}" +
            "</style></head><body>" +
            "<div class='splash-bg' id='splashBg'></div>" +
            "<div class='wipe-overlay' id='wipeOverlay'></div>" +
            "<div style='text-align:center;z-index:1;padding:20px 20px 0;width:100%;max-width:100vw;display:flex;flex-direction:column;align-items:center'>" +
            "<div class='greeting' id='greeting'></div>" +
            "<div class='sub' id='subLine'>做自己的财务管家</div>" +
            "<div class='tl-wrap' id='tlWrap'></div></div>" +
            "<div class='hint'>点击任意处进入</div>" +
            "<script>" +
            "(function(){var params=new URLSearchParams(location.search);var h=parseInt(params.get('h'));if(isNaN(h)||h<0||h>23)h=new Date().getHours();var m=new Date().getMinutes();" +
            "var periods=[" +
            "{t:'凌晨',m:'凌晨了，早点休息',h0:0,h1:5,bg:'linear-gradient(135deg,#0a0a1a,#1a1040,#0d0d2b)',gt:'linear-gradient(135deg,#818cf8,#c4b5fd)'}," +
            "{t:'早上',m:'早上好',h0:6,h1:8,bg:'linear-gradient(135deg,#ffecd2,#fcb69f,#a1c4fd)',gt:'linear-gradient(135deg,#f97316,#ec4899)'}," +
            "{t:'上午',m:'上午好',h0:9,h1:11,bg:'linear-gradient(135deg,#e0eafc,#cfdef3,#fffde7)',gt:'linear-gradient(135deg,#3b82f6,#06b6d4)'}," +
            "{t:'中午',m:'中午好',h0:12,h1:13,bg:'linear-gradient(135deg,#e0f7fa,#b2ebf2,#fff9c4)',gt:'linear-gradient(135deg,#00bcd4,#ff9800)'}," +
            "{t:'下午',m:'下午好',h0:14,h1:17,bg:'linear-gradient(135deg,#fef3c7,#fde68a,#fecaca)',gt:'linear-gradient(135deg,#f59e0b,#ef4444)'}," +
            "{t:'晚上',m:'晚上好',h0:18,h1:20,bg:'linear-gradient(135deg,#1e1b4b,#312e81,#5b21b6)',gt:'linear-gradient(135deg,#a78bfa,#fbbf24)'}," +
            "{t:'深夜',m:'深夜了，注意休息',h0:21,h1:23,bg:'linear-gradient(135deg,#020024,#090979,#1a0040)',gt:'linear-gradient(135deg,#c084fc,#6366f1,#ec4899)'}];" +
            "function getPeriod(h){for(var i=0;i<periods.length;i++){var p=periods[i];if(h>=p.h0&&h<=p.h1)return p}return periods[0]}" +
            "var p=getPeriod(h);document.getElementById('splashBg').style.background=p.bg;" +
            "var g=document.getElementById('greeting');g.textContent=p.m;" +
            "g.style.background=p.gt;g.style.webkitBackgroundClip='text';g.style.webkitTextFillColor='transparent';g.style.backgroundClip='text';" +
            "var tl=document.getElementById('tlWrap');var segs=[],labels=[];" +
            "var colors=['#4f46e5','#f97316','#3b82f6','#06b6d4','#f59e0b','#7c3aed','#312e81'];" +
            "var plabels=['凌晨','早上','上午','中午','下午','晚上','深夜'];" +
            "var bar='';for(var i=0;i<24;i++){var segClass='tl-seg'+(i===h?' active':'');" +
            "var inP=0;for(var j=0;j<periods.length;j++){if(i>=periods[j].h0&&i<=periods[j].h1){inP=j;break}}" +
            "bar+='<div class=\"'+segClass+'\" style=\"background:'+colors[inP]+'\"></div>'}" +
            "var lbls='';for(var i=0;i<24;i++){lbls+='<span'+(i===h?' class=\"active\"':'')+'>'+(i%3===0?i:'')+'</span>'}" +
            "var prds='';for(var j=0;j<periods.length;j++){prds+='<div'+(p===periods[j]?' class=\"tl-p active\"':' class=\"tl-p\"')+'>'+plabels[j]+'</div>'}" +
            "tl.innerHTML='<div class=\"tl-bar\">'+bar+'</div><div class=\"tl-labels\">'+lbls+'</div><div class=\"tl-periods\">'+prds+'</div>';" +
            "document.addEventListener('click',function(){var overlay=document.getElementById('wipeOverlay');" +
            "overlay.style.background=p.bg;overlay.classList.add('active');" +
            "setTimeout(function(){window.location.href='/splash-done'},700)" +
            "})})();" +
            "</script></body></html>";
    }


    private static String mainPage() {
        String t = getMonthTable();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='hero-title-wrap'>");
        sb.append("<h1 class='hero-title'>消费记录系统</h1>");
        sb.append("<p class='hero-subtitle'>认真记录每一笔花销</p>");
        sb.append("</div>");
        sb.append("<p class='hint'>当前月份表: ").append(t).append(" | 日期: ").append(Date_time.getDay()).append("日</p>");
        sb.append("<div class='hero-cards'>");
        // 卡片一：存入消费
        sb.append("<a href='/save' class='hero-link' style='--i:0'>");
        sb.append("<div class='hero-card hero-card-save'>");
        sb.append("<div class='hero-icon'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' width='36' height='36'><circle cx='12' cy='12' r='10'/><path d='M12 8v8M8 12h8'/></svg></div>");
        sb.append("<div class='hero-body'><h2>存入消费</h2><p>记录日常消费或特殊消费，按日期存入当月表中</p></div>");
        sb.append("<div class='hero-arrow'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='24' height='24'><path d='M9 18l6-6-6-6'/></svg></div>");
        sb.append("</div></a>");
        // 卡片二：消费查询
        sb.append("<a href='/query' class='hero-link' style='--i:1'>");
        sb.append("<div class='hero-card hero-card-query'>");
        sb.append("<div class='hero-icon'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' width='36' height='36'><path d='M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z'/></svg></div>");
        sb.append("<div class='hero-body'><h2>消费查询</h2><p>月度统计 · 日均消费 · 折线图 · 日明细 · 特殊消费</p></div>");
        sb.append("<div class='hero-arrow'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='24' height='24'><path d='M9 18l6-6-6-6'/></svg></div>");
        sb.append("</div></a>");
        // 卡片三：消费管控
        sb.append("<a href='/budget' class='hero-link' style='--i:2'>");
        sb.append("<div class='hero-card hero-card-budget'>");
        sb.append("<div class='hero-icon'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' width='36' height='36'><path d='M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2'/><circle cx='12' cy='12' r='7'/><path d='M12 9v3l2 2'/></svg></div>");
        sb.append("<div class='hero-body'><h2>消费管控</h2><p>预算监控，智慧理财每一笔支出</p></div>");
        sb.append("<div class='hero-arrow'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='24' height='24'><path d='M9 18l6-6-6-6'/></svg></div>");
        sb.append("</div></a>");
        sb.append("</div>");
        return page("主菜单", sb.toString());
    }

    // ==================== 消费管控 ====================

    /** 预算：每月固定预算 1500 元 */
    private static final double MONTHLY_BUDGET = 1500;

    private static String budgetPage() {
        String table = getMonthTable();
        int today = Date_time.getDay();
        int remainingDays = Date_time.getRemainingDays();

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>消费管控</h2>");
        sb.append("<p class='hint'>月预算 ¥").append(String.format("%.0f", MONTHLY_BUDGET))
            .append(" | 今日: ").append(today).append("日 | 本月剩余: ").append(remainingDays).append("天</p>");

        // === 数据查询 ===
        double totalSpent = 0;
        double dailyAvg = 0;
        Connection conn = One.getConn();
        if (conn != null) {
            try {
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "SELECT SUM(price) FROM " + table);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getObject(1) != null) totalSpent = rs.getDouble(1);
                rs.close();

                ps = One.getPreparedStmt(conn,
                    "SELECT COALESCE(SUM(CASE WHEN num_1=1 THEN price END)/" +
                    "NULLIF(COUNT(DISTINCT CASE WHEN num_1=1 THEN date END),0),0) FROM " + table);
                rs = ps.executeQuery();
                if (rs.next()) dailyAvg = rs.getDouble(1);
                rs.close(); ps.close();
            } catch (Exception e) {
                e.printStackTrace();
            } finally { try { conn.close(); } catch (Exception ignored) {} }
        }

        // === 计算 ===
        double balance = MONTHLY_BUDGET - totalSpent;
        double dailyRemaining = balance > 0 ? balance / remainingDays : 0;
        double deficit = totalSpent > MONTHLY_BUDGET ? totalSpent - MONTHLY_BUDGET : 0;

        // === 统计面板 ===
        sb.append("<section class='query-section'><h3>本月概览</h3>");
        sb.append("<div class='stat-row'>");
        sb.append("<div class='stat'><span>已消费</span><strong>¥").append(String.format("%.2f", totalSpent)).append("</strong></div>");
        sb.append("<div class='stat'><span>日均消费</span><strong>¥").append(String.format("%.2f", dailyAvg)).append("</strong></div>");
        sb.append("</div></section>");

        // === 预算建议 ===
        sb.append("<section class='query-section'><h3>预算建议</h3>");
        sb.append("<div class='stat-row'>");

        // 当前余额
        sb.append("<div class='stat stat-budget");
        if (balance < 0) sb.append(" stat-danger");
        sb.append("'><span>当前余额</span><strong>");
        if (balance < 0) {
            sb.append("⚠ 已超出").append(String.format("¥%.2f", -balance));
        } else {
            sb.append("¥").append(String.format("%.2f", balance));
        }
        sb.append("</strong></div>");

        // 赤字
        sb.append("<div class='stat");
        if (deficit > 0) sb.append(" stat-danger");
        sb.append("'><span>赤字</span><strong>");
        if (deficit > 0) {
            sb.append("¥").append(String.format("%.2f", deficit));
        } else {
            sb.append("暂未赤字");
        }
        sb.append("</strong></div>");

        // 建议每日消费
        sb.append("<div class='stat'><span>建议每日消费</span><strong>");
        if (balance > 0) {
            sb.append("¥").append(String.format("%.2f", dailyRemaining));
        } else {
            sb.append("⚠ 已超出预算");
        }
        sb.append("</strong></div>");

        // 预计超额
        double projectedOvershoot = dailyAvg * remainingDays - balance;
        sb.append("<div class='stat");
        if (projectedOvershoot > 0) sb.append(" stat-danger");
        sb.append("'><span>预计超额</span><strong>");
        if (projectedOvershoot > 0) {
            sb.append("¥").append(String.format("%.2f", projectedOvershoot));
        } else {
            sb.append("暂未超额");
        }
        sb.append("</strong></div>");

        sb.append("</div></section>");

        // === 赤字概率 ===
        sb.append("<section class='query-section'><h3>当月赤字概率</h3>");
        double deficitProb = 0;
        if (balance <= 0) {
            deficitProb = 100;
        } else if (dailyAvg > 0 && remainingDays > 0) {
            double ratio = dailyAvg / (balance / remainingDays);
            deficitProb = Math.min(99, Math.max(0, ratio * 50));
        }
        int probInt = (int) Math.round(deficitProb);
        sb.append("<div class='prob-card'>");
        sb.append("<div class='prob-header'><span>基于当前消费趋势</span><strong class='prob-num' data-target='").append(probInt).append("'>0%</strong></div>");
        sb.append("<div class='prob-bar'><div class='prob-fill' data-target='").append(probInt).append("' style='width:0%'></div></div>");
        sb.append("<div class='prob-detail'>").append(probEval(probInt)).append("</div></section>");
        // 赤字概率动画
        sb.append("<script>");
        sb.append("(function(){var b=document.querySelector('.prob-fill');");
        sb.append("var n=document.querySelector('.prob-num');");
        sb.append("var t=parseInt(b.getAttribute('data-target'));");
        sb.append("var c=0;function a(){if(c>=t)return;c++;");
        sb.append("var cl=c<30?'#10b981':c<60?'#f59e0b':'#ef4444';");
        sb.append("b.style.width=c+'%';b.style.background=cl;");
        sb.append("n.textContent=c+'%';n.style.color=cl;");
        sb.append("var d=c<t*.7?12:28;setTimeout(a,d);}a();})();");
        sb.append("</script>");

        return page("消费管控", sb.toString());
    }

    private static String probEval(int prob) {
        if (prob < 30) return "赤字率较低，继续保持当前消费习惯";
        if (prob < 60) return "赤字率上升，需留意日常开支";
        return "赤字率较高，请减少非必要消费";
    }

    private static String savePage(String msg) {
        String msgHtml = msg != null ? "<p class='" + (msg.contains("成功") ? "success" : "error") + "'>" + msg + "</p>" : "";
        int today = Date_time.getDay();
        return page("存入消费",
            "<h2>存入消费信息</h2>" + msgHtml +
            "<div class='form-card'>" +
            "<form method='post' action='/save'>" +
            "<div class='radio-pill-group'>" +
            "<label class='radio-pill'><input type='radio' name='type' value='1' checked><span class='pill-text'>日常消费</span></label>" +
            "<label class='radio-pill'><input type='radio' name='type' value='2'><span class='pill-text'>特殊消费</span></label>" +
            "</div>" +
            "<div class='input-group'><label>商品名称</label>" +
            "<div class='input-wrap'><svg class='input-icon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5' width='18' height='18'><path d='M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2'/><rect x='9' y='3' width='6' height='4' rx='1'/></svg>" +
            "<input name='goods' required placeholder='例如：午餐'></div></div>" +
            "<div class='input-group'><label>价格（元）</label>" +
            "<div class='input-wrap'><svg class='input-icon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5' width='18' height='18'><circle cx='12' cy='12' r='10'/><path d='M16 8h-4.5a2 2 0 100 4h1a2 2 0 010 4H8'/><path d='M12 6v2M12 16v2'/></svg>" +
            "<input name='price' type='number' step='0.01' required placeholder='例如：25.5'></div></div>" +
            "<div class='input-group'><label>日期（日）</label>" +
            "<div class='input-wrap'><svg class='input-icon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.5' width='18' height='18'><rect x='3' y='4' width='18' height='18' rx='2'/><path d='M16 2v4M8 2v4M3 10h18'/></svg>" +
            "<input name='day' type='number' min='1' max='31' value='" + today + "' placeholder='" + today + "'></div></div>" +
            "<button type='submit' class='btn btn-lg btn-block'>确认存入</button>" +
            "</form></div>");
    }

    // ==================== HTML 模板 ====================

    private static String page(String title, String body) {
        return pageRaw(title,
            "<header><div class='top-bar'>" +
            "<a href='/main' class='back-btn' title='返回首页'>" +
            "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='18' height='18'>" +
            "<path d='M19 12H5M12 19l-7-7 7-7'/></svg>" +
            "<span>首页</span></a>" +
            "<div class='top-bar-spacer'></div>" +
            themeToggle() +
            "</div></header>" + body);
    }

    private static String pageRaw(String title, String body) {
        return "<!DOCTYPE html><html lang='zh' data-theme='light'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>" + title + "</title><style>" + css() + "</style>" +
            "<script>(function(){var t=localStorage.getItem('theme')||'light';document.documentElement.setAttribute('data-theme',t);})();" +
            "function toggleTheme(){var e=document.documentElement;var t=e.getAttribute('data-theme')==='dark'?'light':'dark';e.setAttribute('data-theme',t);localStorage.setItem('theme',t);}</script>" +
            "<style>.bg-canvas{position:fixed;top:0;left:0;width:100%;height:100%;z-index:-1;pointer-events:none}</style>" +
            "</head><body><canvas class='bg-canvas' id='bgCanvas'></canvas><main class='container'>" + body + "</main>" +
            "<script>!function(){var c=document.getElementById('bgCanvas'),ctx=c.getContext('2d');" +
            "var w,h;function resize(){w=c.width=window.innerWidth;h=c.height=window.innerHeight}" +
            "window.addEventListener('resize',resize);resize();" +
            "var sq=[];var n=Math.min(28,w>800?40:16);" +
            "var dpal=[['rgba(255,255,255,','rgba(200,210,255,']," +
            "['rgba(200,180,255,','rgba(160,140,230,']," +
            "['rgba(180,220,255,','rgba(140,180,230,']," +
            "['rgba(255,180,200,','rgba(220,130,170,']," +
            "['rgba(180,255,220,','rgba(130,210,180,']];" +
            "function r(a,b){return Math.random()*(b-a)+a}" +
            "for(var i=0;i<n;i++){var side=i%2===0?r(10,w*0.3):r(w*0.7,w-10);" +
            "sq.push({x:side+r(-20,20),y:r(0,h)," +
            "s:r(20,50)+r(.4,1)*80,sp:r(.08,.15)+r(.2,.6),op:r(.08,.12)+r(.4,1)*.1," +
            "ci:Math.floor(r(0,5)),vx:r(-.03,.03)})}" +
            "var lb=[];var lm=Math.min(24,w>800?36:14);" +
            "var lpal=[['rgba(0,188,212,','rgba(0,140,170,']," +
            "['rgba(0,200,170,','rgba(0,155,130,']," +
            "['rgba(139,195,74,','rgba(100,160,40,']," +
            "['rgba(205,190,50,','rgba(165,150,30,']," +
            "['rgba(255,183,77,','rgba(210,145,40,']];" +
            "for(var i=0;i<lm;i++){var side=i%2===0?r(10,w*0.3):r(w*0.7,w-10);" +
            "var depth=r(.4,1);lb.push({x:side+r(-20,20),y:r(0,h)," +
            "s:r(20,50)+depth*90,sp:r(.08,.15)+depth*.35,op:r(.08,.12)+depth*.12," +
            "ci:Math.floor(r(0,5)),vx:r(-.03,.03),depth:depth})}" +
            "function draw(){ctx.clearRect(0,0,w,h);var isLight=document.documentElement.getAttribute('data-theme')!=='dark';" +
            "if(isLight){for(var i=0;i<lb.length;i++){var b=lb[i];b.y-=b.sp;b.x+=b.vx;" +
            "var p=Math.min(1,Math.max(0,b.y/h));var op=b.op*p;var sz=b.s*p;" +
            "if(b.y<-b.s*2){b.y=h+r(0,h*.3);var side=i%2===0?r(10,w*0.3):r(w*0.7,w-10);b.x=side+r(-20,20);" +
            "b.s=r(20,50)+r(.4,1)*90;b.sp=r(.08,.15)+r(.2,.7);b.op=r(.08,.12)+r(.4,1)*.12;b.ci=Math.floor(r(0,5))}" +
            "if(sz>6){var grd=ctx.createRadialGradient(b.x-sz*.15,b.y-sz*.15,0,b.x,b.y,sz/2);" +
            "var c1=b.ci;var c2=(b.ci+1)%5;" +
            "grd.addColorStop(0,'rgba(255,255,255,'+(op*.5).toFixed(3)+')');" +
            "grd.addColorStop(.5,lpal[c1][0]+(op*.8).toFixed(3)+')');" +
            "grd.addColorStop(1,lpal[c2][1]+(op*.6).toFixed(3)+')');" +
            "ctx.fillStyle=grd;ctx.beginPath();ctx.arc(b.x,b.y,sz/2,0,Math.PI*2);ctx.fill()}}}" +
            "else{for(var i=0;i<sq.length;i++){var q=sq[i];q.y-=q.sp;q.x+=q.vx;" +
            "var p=Math.min(1,Math.max(0,q.y/h));var op=q.op*p;var sz=q.s*p;" +
            "if(q.y<-q.s*2){var side=q.x<w/2?r(10,w*0.3):r(w*0.7,w-10);q.x=side+r(-20,20);q.y=h+r(0,h*.3);" +
            "q.s=r(20,50)+r(.4,1)*80;q.sp=r(.08,.15)+r(.2,.6);q.op=r(.08,.12)+r(.4,1)*.1;q.ci=Math.floor(r(0,5))}" +
            "if(sz>4){var grd=ctx.createRadialGradient(q.x-sz*.15,q.y-sz*.15,0,q.x,q.y,sz/2);" +
            "grd.addColorStop(0,'rgba(255,255,255,'+(op*.6).toFixed(3)+')');" +
            "grd.addColorStop(.5,dpal[q.ci][0]+(op*.7).toFixed(3)+')');" +
            "grd.addColorStop(1,dpal[q.ci][1]+(op*.5).toFixed(3)+')');" +
            "ctx.fillStyle=grd;ctx.beginPath();ctx.arc(q.x,q.y,sz/2,0,Math.PI*2);ctx.fill()}}}" +
            "requestAnimationFrame(draw)}draw()}();</script>" +
            "</body></html>";
    }

    private static String themeToggle() {
        return "<div class='theme-switch' onclick='toggleTheme()' title='切换浅色/深色模式'>" +
            "<svg class='icon-sun' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2'><circle cx='12' cy='12' r='5'/><path d='M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4'/></svg>" +
            "<span class='toggle-track'><span class='toggle-thumb'></span></span>" +
            "<svg class='icon-moon' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2'><path d='M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z'/></svg>" +
            "</div>";
    }

    private static String card(String href, String title, String desc, int i) {
        return "<a href='" + href + "' class='card-link' style='--i:" + i + "'><div class='card'><h3>" + title + "</h3><p>" + desc + "</p></div></a>";
    }

    private static String errorPage(String msg) {
        return page("错误", "<div class='card'><h2>出错了</h2><p class='error'>" + msg + "</p><a href='/main'>返回首页</a></div>");
    }

    // ==================== CSS 主题系统 ====================

    private static String css() {
        return
            // === CSS变量：浅色 ===
            ":root,[data-theme='light']{" +
            "--space-xs:4px;--space-sm:8px;--space-md:16px;--space-lg:24px;--space-xl:32px;--space-2xl:48px;--space-3xl:64px;" +
            "--bg:#f2f3f8;--surface:#fff;--surface2:#f8f9fc;--elevated:#fff;" +
            "--text:#1e1e2e;--text2:#52526b;--border:#e2e2ec;" +
            "--accent:#4f6ef7;--accent-h:#3b54d4;--accent-light:rgba(79,110,247,.08);" +
            "--danger:#e74c3c;--danger-h:#c0392b;--danger-light:rgba(231,76,60,.08);" +
            "--success:#27ae60;--success-light:rgba(39,174,96,.08);" +
            "--shadow-sm:0 1px 2px rgba(0,0,0,.04);" +
            "--shadow:0 1px 3px rgba(0,0,0,.06),0 1px 2px rgba(0,0,0,.04);" +
            "--shadow-lg:0 4px 16px rgba(0,0,0,.08),0 2px 4px rgba(0,0,0,.04);" +
            "--input-bg:#fff;--input-bd:#d4d4e0;--toggle-bg:#c8cad8;" +
            "--hover:rgba(79,110,247,.04);--nav-hover:rgba(79,110,247,.06);" +
            "--glow-top:rgba(79,110,247,.04);--glow-bottom:rgba(245,158,11,.03);" +
            "--title-g1:#4f6ef7;--title-g2:#7c3aed;--title-g3:#d97706;" +
            "--sub-g1:#6366f1;--sub-g2:#f59e0b;" +
            "--radius-sm:6px;--radius:10px;--radius-lg:14px;" +
            "--ease-out:cubic-bezier(.16,1,.3,1);--ease-in-out:cubic-bezier(.4,0,.2,1)}" +

            // === CSS变量：深色 ===
            "[data-theme='dark']{" +
            "--bg:#0d0d1a;--surface:#16162b;--surface2:#1c1c34;--elevated:#222242;" +
            "--text:#e2e2ee;--text2:#8e8eaa;--border:#282850;" +
            "--accent:#7b9bff;--accent-h:#9bb5ff;--accent-light:rgba(123,155,255,.1);" +
            "--danger:#ff6b6b;--danger-h:#e05555;--danger-light:rgba(255,107,107,.1);" +
            "--success:#5ddb6e;--success-light:rgba(93,219,110,.1);" +
            "--shadow-sm:0 1px 2px rgba(0,0,0,.25);" +
            "--shadow:0 1px 4px rgba(0,0,0,.3);" +
            "--shadow-lg:0 4px 16px rgba(0,0,0,.3),0 2px 4px rgba(0,0,0,.2);" +
            "--input-bg:#1a1a35;--input-bd:#32325a;--toggle-bg:#4f6ef7;" +
            "--hover:rgba(123,155,255,.06);--nav-hover:rgba(123,155,255,.08);" +
            "--glow-top:rgba(123,155,255,.06);--glow-bottom:rgba(139,92,246,.04);" +
            "--title-g1:#a5b4fc;--title-g2:#c4b5fd;--title-g3:#fcd34d;" +
            "--sub-g1:#93c5fd;--sub-g2:#c4b5fd}" +

            // === 基础 ===
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'Microsoft YaHei','PingFang SC','Noto Sans SC',sans-serif;" +
            "background:var(--bg);color:var(--text);min-height:100vh;" +
            "font-weight:350;line-height:1.6;position:relative;" +
            "transition:background .4s var(--ease-out),color .4s var(--ease-out)}" +
            "body::before{content:'';position:fixed;inset:0;pointer-events:none;z-index:0;" +
            "background:radial-gradient(ellipse 80% 60% at 50% 0%,var(--glow-top),transparent)," +
            "radial-gradient(ellipse 60% 50% at 80% 100%,var(--glow-bottom),transparent)}" +
            "body::after{content:'';position:fixed;inset:0;pointer-events:none;z-index:0;" +
            "opacity:.035;background-image:url(\"data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\");background-size:200px 200px}" +
            "main.container{position:relative;z-index:1}" +
            ".container{max-width:860px;margin:0 auto;padding:var(--space-lg)}" +

            // === 顶部栏 ===
            ".top-bar{display:flex;align-items:center;gap:var(--space-sm);" +
            "padding:var(--space-sm) 0 var(--space-lg)}" +
            ".top-bar-spacer{flex:1}" +
            ".back-btn{display:inline-flex;align-items:center;gap:6px;color:var(--text2);" +
            "text-decoration:none;font-size:13px;font-weight:450;padding:6px 10px;" +
            "border-radius:var(--radius-sm);transition:all .2s var(--ease-out)}" +
            ".back-btn:hover{color:var(--accent);background:var(--nav-hover);text-decoration:none}" +
            ".back-btn:active{transform:scale(.95)}" +

            // === 首页大卡片 ===
            ".hero-cards{display:flex;flex-direction:column;gap:var(--space-lg);margin-top:var(--space-xl)}" +
            ".hero-link{text-decoration:none;display:block;" +
"animation:cardIn .7s ease-out both;animation-delay:calc(var(--i,0)*100ms)}" +
".hero-link:hover{text-decoration:none}" +
            ".hero-card{display:flex;align-items:center;gap:var(--space-lg);" +
            "background:var(--surface);padding:var(--space-xl) var(--space-xl);" +
            "border-radius:var(--radius-lg);border:1px solid var(--border);" +
            "box-shadow:var(--shadow);cursor:pointer;min-height:120px;width:100%;box-sizing:border-box;" +
            "transition:all .35s var(--ease-in-out);position:relative;overflow:hidden}" +
            ".hero-card::before{content:'';position:absolute;top:0;left:0;right:0;" +
            "height:3px;border-radius:3px 3px 0 0;transition:height .35s var(--ease-in-out)}" +
            ".hero-card-save::before{background:linear-gradient(90deg,var(--accent),#c4b5fd)}" +
            "[data-theme='dark'] .hero-card-save::before{background:linear-gradient(90deg,#a5b4fc,var(--accent))}" +
            ".hero-card-query::before{background:linear-gradient(90deg,#e8920a,#ea580c)}" +
            "[data-theme='dark'] .hero-card-query::before{background:linear-gradient(90deg,#fbbf24,#f59e0b)}" +
            ".hero-card-budget::before{background:linear-gradient(90deg,#10b981,#34d399)}" +
            "[data-theme='dark'] .hero-card-budget::before{background:linear-gradient(90deg,#6ee7b7,#34d399)}" +
            ".hero-card:hover{transform:translateY(-4px) scale(1.015);" +
            "box-shadow:0 12px 40px rgba(0,0,0,.12);border-color:transparent}" +
            ".hero-card:hover::before{height:5px}" +
            ".hero-card:active{transform:translateY(-2px) scale(1.005)}" +
            ".hero-icon{flex-shrink:0;width:64px;height:64px;display:flex;align-items:center;" +
            "justify-content:center;border-radius:16px;transition:transform .35s var(--ease-in-out)}" +
            ".hero-card-save .hero-icon{background:rgba(79,110,247,.1);color:var(--accent)}" +
            ".hero-card-query .hero-icon{background:rgba(245,158,11,.1);color:#f59e0b}" +
".hero-card-budget .hero-icon{background:rgba(16,185,129,.1);color:#10b981}" +
            ".hero-card:hover .hero-icon{transform:scale(1.1)}" +
            ".hero-body{flex:1;min-width:0}" +
            ".hero-body h2{font-size:clamp(18px,2.5vw,22px);font-weight:700;color:var(--text);border-left:none;padding-left:0;" +
            "margin-bottom:4px;letter-spacing:-.01em}" +
            ".hero-body p{color:var(--text2);font-size:14px;line-height:1.5}" +
            ".hero-arrow{flex-shrink:0;color:var(--text2);transition:all .35s var(--ease-in-out)}" +
            ".hero-card:hover .hero-arrow{color:var(--accent);transform:translateX(4px)}" +

            // === 查询分区 ===
            ".query-section{margin-bottom:var(--space-2xl)}" +
            ".query-section h3{font-size:16px;font-weight:650;color:var(--text);" +
            "margin-bottom:var(--space-md);padding-bottom:var(--space-sm);" +
            "border-bottom:2px solid var(--accent)}" +
            ".stat-row{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:var(--space-sm)}" +

            // === 主题开关 ===
            ".theme-switch{display:flex;align-items:center;gap:var(--space-sm);" +
            "margin-left:auto;cursor:pointer;user-select:none;padding:var(--space-xs)}" +
            ".theme-switch:active .toggle-thumb{transform:scale(.9)}" +
            ".toggle-track{width:44px;height:26px;background:var(--toggle-bg);" +
            "border-radius:13px;position:relative;transition:background .3s var(--ease-out)}" +
            ".toggle-thumb{width:22px;height:22px;background:#fff;border-radius:50%;" +
            "position:absolute;top:2px;left:2px;transition:transform .3s var(--ease-out);" +
            "box-shadow:0 1px 3px rgba(0,0,0,.2)}" +
            "[data-theme='dark'] .toggle-thumb{transform:translateX(18px)}" +
            ".icon-sun,.icon-moon{width:18px;height:18px;color:var(--text2);transition:color .3s}" +
            "[data-theme='dark'] .icon-moon{color:#f0c040}" +
            "[data-theme='light'] .icon-sun{color:#f59e0b}" +
            ".top-right-toggle{display:flex;justify-content:flex-end;padding:var(--space-lg) var(--space-lg) 0}" +

            // === 卡片 ===
            ".card{background:var(--surface);padding:var(--space-xl) var(--space-lg);" +
            "border-radius:var(--radius-lg);box-shadow:var(--shadow);" +
            "transition:all .35s var(--ease-in-out);border:1px solid var(--border);" +
            "cursor:pointer;position:relative}" +
            ".card:hover{box-shadow:var(--shadow-lg);transform:translateY(-4px) scale(1.025);" +
            "border-color:var(--accent)}" +
            ".card:active{transform:translateY(-2px) scale(1.01)}" +
            ".card h3{margin-bottom:var(--space-sm);font-size:clamp(16px,2vw,18px);" +
            "color:var(--text);font-weight:600;letter-spacing:-.01em}" +
            ".card p{color:var(--text2);font-size:13px;line-height:1.5}" +
            ".card-link{text-decoration:none;display:block}" +
            ".card-link:focus-visible .card{outline:2px solid var(--accent);outline-offset:2px}" +
            "@keyframes cardIn{0%{opacity:0;transform:translateY(40px) scale(.9)}" +
"55%{opacity:1;transform:translateY(-8px) scale(1.025)}" +
"75%{transform:translateY(2px) scale(.98)}" +
"100%{opacity:1;transform:translateY(0) scale(1)}}" +
            ".card{animation:cardIn .5s var(--ease-out) both;animation-delay:calc(var(--i,0)*70ms)}" +

            // === 登录 ===
            ".login-box{max-width:400px;margin:80px auto;background:var(--surface);" +
            "padding:var(--space-2xl) var(--space-xl);border-radius:var(--radius-lg);" +
            "box-shadow:var(--shadow-lg);text-align:center;border:1px solid var(--border)}" +
            ".login-box h1{margin-bottom:var(--space-xl)}" +
            "color:var(--text);font-weight:700;letter-spacing:-.02em}" +

            // === 表单 ===
            "input,select{width:100%;padding:11px 14px;border:1px solid var(--input-bd);" +
            "border-radius:var(--radius);font-size:14px;margin-bottom:var(--space-md);" +
            "outline:none;transition:all .2s var(--ease-out);" +
            "background:var(--input-bg);color:var(--text);font-family:inherit}" +
            "input:focus,select:focus{border-color:var(--accent);" +
            "box-shadow:0 0 0 3px var(--accent-light);transform:translateY(-1px)}" +
            "label{display:block;margin-bottom:var(--space-xs);color:var(--text);" +
            "font-size:14px;font-weight:500;letter-spacing:-.01em}" +

            // === 按钮 ===
            ".btn{display:inline-block;background:var(--accent);color:#fff;border:none;" +
            "padding:10px 26px;border-radius:var(--radius);font-size:14px;font-weight:550;" +
            "cursor:pointer;text-decoration:none;" +
            "transition:all .2s var(--ease-out);position:relative;overflow:hidden}" +
            ".btn:hover{background:var(--accent-h);transform:translateY(-1px);" +
            "box-shadow:0 4px 14px rgba(79,110,247,.35)}" +
            ".btn:active{transform:translateY(0) scale(.97)}" +
            ".btn-danger{background:var(--danger)}" +
            ".btn-danger:hover{background:var(--danger-h);box-shadow:0 4px 14px rgba(231,76,60,.35)}" +
            ".btn-danger:active{transform:translateY(0) scale(.97)}" +
            ".btn-sm{padding:5px 14px;font-size:12px;border-radius:var(--radius-sm)}" +

            // === 表格 ===
            "table{width:100%;border-collapse:collapse;margin-top:var(--space-md);" +
            "background:var(--surface);border-radius:var(--radius);overflow:hidden;" +
            "box-shadow:var(--shadow-sm);border:1px solid var(--border)}" +
            "th,td{padding:12px 16px;text-align:left;border-bottom:1px solid var(--border);font-size:14px}" +
            "th{background:var(--surface2);font-weight:600;color:var(--text2);font-size:12px;" +
            "letter-spacing:.04em}" +
            "tr:last-child td{border-bottom:none}" +
            "tr{transition:background .15s var(--ease-out)}" +
            "tr:hover td{background:var(--hover)}" +

            // === 统计卡片 ===
            ".stat{background:var(--surface);padding:var(--space-md) var(--space-lg);" +
            "border-radius:var(--radius);margin-bottom:var(--space-sm);" +
            "display:flex;justify-content:space-between;align-items:center;" +
            "box-shadow:var(--shadow-sm);border:1px solid var(--border);" +
            "transition:all .25s var(--ease-out)}" +
            ".stat:hover{border-color:var(--accent);transform:translateX(4px)}" +
            ".stat span{color:var(--text2);font-size:13px;font-weight:450}" +
            ".stat strong{font-size:clamp(16px,2.5vw,20px);color:var(--accent);font-weight:650}" +
            ".stat-danger{border-color:var(--danger)!important}" +
            ".stat-danger strong{color:var(--danger)!important}" +

            // === 赤字概率 ===
            ".prob-card{background:var(--surface);padding:var(--space-lg);border-radius:var(--radius);" +
            "border:1px solid var(--border);box-shadow:var(--shadow-sm)}" +
            ".prob-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:var(--space-md)}" +
            ".prob-header span{color:var(--text2);font-size:14px}.prob-header strong{font-size:28px;font-weight:700}" +
            ".prob-bar{height:10px;background:var(--bg);border-radius:5px;overflow:hidden;margin-bottom:var(--space-sm)}" +
            ".prob-fill{height:100%;border-radius:5px;transition:width .6s var(--ease-out)}" +
            ".prob-detail{color:var(--text);font-size:14px;font-weight:450;line-height:1.5;margin-top:var(--space-sm)}" +

            // === 导航网格 ===
            ".nav-grid{display:grid;" +
            "grid-template-columns:repeat(auto-fill,minmax(200px,1fr));" +
            "gap:var(--space-lg);margin-top:var(--space-xl)}" +

            // === 工具 ===
            ".error{color:var(--danger);margin-bottom:var(--space-sm);font-size:14px;font-weight:500}" +
            ".success{color:var(--success);margin-bottom:var(--space-sm);font-size:14px;font-weight:500}" +
            ".hint{color:var(--text2);font-size:13px;margin:var(--space-sm) 0 var(--space-md);line-height:1.5}" +
            ".inline-form{display:flex;gap:var(--space-sm);margin-bottom:var(--space-md)}" +
            ".inline-form input{flex:1;margin-bottom:0}" +
            ".chart-img{max-width:100%;border-radius:var(--radius-lg);" +
            "box-shadow:var(--shadow-lg);margin-top:var(--space-md)}" +
            ".radio-group{margin-bottom:var(--space-md);display:flex;gap:var(--space-xl)}" +
            ".radio-label{display:inline-flex;align-items:center;gap:var(--space-sm);" +
            "font-weight:450;cursor:pointer;color:var(--text);font-size:14px;" +
            "transition:color .2s var(--ease-out)}" +
            ".radio-label:hover{color:var(--accent)}" +
            ".radio-label input[type=radio]{width:auto;margin-bottom:0;accent-color:var(--accent)}" +
            // === 表单卡片 ===
            ".form-card{max-width:560px;margin:var(--space-lg) auto;background:var(--surface);" +
            "padding:var(--space-2xl) var(--space-xl);border-radius:var(--radius-lg);" +
            "border:1px solid var(--border);box-shadow:var(--shadow-lg)}" +
            ".form-card form{display:flex;flex-direction:column;gap:var(--space-lg)}" +
            // === 药丸单选 ===
            ".radio-pill-group{display:flex;gap:var(--space-sm);margin-bottom:var(--space-sm)}" +
            ".radio-pill{flex:1;position:relative;cursor:pointer}" +
            ".radio-pill input{position:absolute;opacity:0;pointer-events:none}" +
            ".radio-pill .pill-text{display:block;text-align:center;padding:12px 20px;" +
            "border-radius:var(--radius);border:2px solid var(--border);background:var(--surface2);" +
            "color:var(--text2);font-weight:500;font-size:14px;transition:all .25s var(--ease-out)}" +
            ".radio-pill input:checked+.pill-text{border-color:var(--accent);" +
            "background:var(--accent-light);color:var(--accent);font-weight:600;" +
            "box-shadow:0 0 0 3px var(--accent-light)}" +
            ".radio-pill:hover .pill-text{border-color:var(--accent)}" +
            // === 输入组 ===
            ".input-group{display:flex;flex-direction:column;gap:var(--space-xs)}" +
            ".input-group label{font-size:13px;font-weight:550;color:var(--text2);" +
            "margin-bottom:0;text-transform:uppercase;letter-spacing:.06em}" +
            ".input-wrap{position:relative;display:flex;align-items:center}" +
            ".input-wrap .input-icon{position:absolute;left:14px;color:var(--text2);" +
            "pointer-events:none;transition:color .2s var(--ease-out)}" +
            ".input-wrap input{padding:13px 14px 13px 42px;font-size:15px;width:100%;border:1.5px solid var(--input-bd);" +
            "border-radius:var(--radius);background:var(--input-bg);color:var(--text);" +
            "font-family:inherit;outline:none;transition:all .25s var(--ease-out);margin-bottom:0}" +
            ".input-wrap input:focus{border-color:var(--accent);box-shadow:0 0 0 4px var(--accent-light)}" +
            "" +
            ".input-wrap:focus-within .input-icon{color:var(--accent)}" +
            // === 大按钮 ===
            ".btn-lg{padding:14px 32px;font-size:16px;border-radius:var(--radius);font-weight:600}" +
            ".page-header-row{display:flex;justify-content:space-between;align-items:center;margin-bottom:var(--space-md);flex-wrap:wrap;gap:var(--space-sm)}" +
            ".btn-export{background:var(--surface);color:var(--accent);border:1px solid var(--border);font-size:13px}" +
            ".btn-export:hover{background:var(--accent);color:#fff;border-color:var(--accent)}" +
            ".btn-block{width:100%;text-align:center}" +
            // === 月份筛选条 ===
            ".filter-bar{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:var(--space-md)}" +
            ".filter-pill{display:inline-block;padding:8px 16px;border-radius:20px;" +
            "border:1.5px solid var(--border);color:var(--text2);text-decoration:none;" +
            "font-size:13px;font-weight:500;transition:all .2s var(--ease-out);" +
            "background:var(--surface)}" +
            ".filter-pill:hover{border-color:var(--accent);color:var(--accent);" +
            "background:var(--accent-light);text-decoration:none}" +
            ".filter-pill.active{background:var(--accent);color:#fff;" +
            "border-color:var(--accent);font-weight:600;box-shadow:0 2px 8px rgba(79,110,247,.3)}" +
            // === 编辑弹窗 ===
            ".edit-box{margin-top:var(--space-lg);background:var(--surface);" +
            "padding:var(--space-xl);border-radius:var(--radius-lg);" +
            "border:1px solid var(--border);box-shadow:var(--shadow-lg)}" +
            ".edit-box h4{font-size:15px;font-weight:650;margin-bottom:var(--space-lg);" +
            "color:var(--text)}" +
            // === 艺术标题 ===
            ".hero-title-wrap{text-align:center;padding:var(--space-2xl) 0 var(--space-lg)}" +
            ".hero-title{font-size:clamp(32px,5vw,52px);font-weight:800;letter-spacing:-.03em;" +
            "line-height:1.15;margin-bottom:var(--space-sm);text-align:center;" +
            "background:linear-gradient(135deg,var(--title-g1),var(--title-g2),var(--title-g3));" +
            "-webkit-background-clip:text;-webkit-text-fill-color:transparent;" +
            "background-clip:text;animation:titleShimmer 6s ease-in-out infinite;background-size:200% 200%}" +
            ".hero-subtitle{font-size:clamp(14px,1.8vw,17px);font-weight:400;text-align:center;" +
            "letter-spacing:.08em;margin-bottom:var(--space-sm);" +
            "background:linear-gradient(90deg,var(--sub-g1),var(--sub-g2));" +
            "-webkit-background-clip:text;-webkit-text-fill-color:transparent;" +
            "background-clip:text}" +
            "@keyframes titleShimmer{0%,100%{background-position:0% 50%}50%{background-position:100% 50%}}" +
            "h1{font-size:clamp(22px,3vw,28px);margin-bottom:var(--space-sm);" +
            "font-weight:700;letter-spacing:-.02em}" +
            "h2{font-size:clamp(17px,2.5vw,21px);margin-bottom:var(--space-md);" +
            "font-weight:650;letter-spacing:-.01em;position:relative;padding-left:14px;" +
            "border-left:3px solid var(--accent)}" +
            "a{color:var(--accent);text-decoration:none;transition:color .2s var(--ease-out)}" +
            "a:hover{text-decoration:underline}" +

            // === 减少动画 ===
            "@media(prefers-reduced-motion:reduce){*,::before,::after{" +
            "animation-duration:.01ms!important;animation-iteration-count:1!important;" +
            "transition-duration:.01ms!important}}" +

            // === 滚动条 ===
            "::-webkit-scrollbar{width:6px}::-webkit-scrollbar-track{background:var(--bg)}" +
            "::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}" +
            "::-webkit-scrollbar-thumb:hover{background:var(--text2)}" +

            // === 响应式 ===
            "@media(max-width:768px){" +
            ".container{padding:var(--space-md)}" +
            ".stat-row{grid-template-columns:1fr 1fr}" +
            ".hero-card{padding:var(--space-lg);gap:var(--space-md)}" +
            ".hero-icon{width:48px;height:48px}" +
            ".hero-icon svg{width:28px;height:28px}" +
            ".hero-body h2{font-size:16px}" +
            ".top-bar{padding:var(--space-sm) 0 var(--space-md)}}" +
            "@media(max-width:480px){" +
            ".container{padding:var(--space-sm)}" +
            ".stat-row{grid-template-columns:1fr}" +
            ".hero-card{flex-direction:column;text-align:center}" +
            ".hero-arrow{display:none}" +
            ".inline-form{flex-direction:column}" +
            ".query-section{margin-bottom:var(--space-xl)}" +
            ".form-card{padding:var(--space-lg) var(--space-md);margin:var(--space-md) 0}" +
            ".radio-pill-group{flex-direction:column}" +
            "table{font-size:13px}th,td{padding:8px 10px}}";
    }

    // ==================== 工具方法 ====================

    private static void html(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void redirect(HttpExchange ex, String url) throws IOException {
        ex.getResponseHeaders().set("Location", url);
        ex.sendResponseHeaders(302, -1);
    }

    private static String cookie(HttpExchange ex, String name) {
        String h = ex.getRequestHeaders().getFirst("Cookie");
        if (h == null) return null;
        for (String c : h.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        Map<String, String> map = new HashMap<>();
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return map;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q != null) {
            for (String pair : q.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    try { map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8")); } catch (Exception ignored) {}
                }
            }
        }
        return map;
    }

    private static String getMonthTable() {
        return String.format("table_%02d", Date_time.getMonth());
    }

    /** HTML 转义 */
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** JS字符串转义 */
    private static String escJS(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
