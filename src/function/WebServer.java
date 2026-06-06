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
    private static final String COOKIE_NAME = "session";
    private static final String VALID_TOKEN = "tok_274823137";

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
        boolean loggedIn = VALID_TOKEN.equals(cookie(ex, COOKIE_NAME));

        // 未登录只允许访问 /login 和 /
        if (!loggedIn && !path.equals("/login") && !path.equals("/")) {
            redirect(ex, "/"); return;
        }
        // 已登录访问 / 跳到主页
        if (loggedIn && path.equals("/")) {
            redirect(ex, "/main"); return;
        }

        try {
            switch (path) {
                case "/"          -> html(ex, loginPage(null));
                case "/login"     -> handleLogin(ex);
                case "/main"      -> html(ex, mainPage());
                case "/save"      -> { if ("POST".equals(method)) handleSavePost(ex); else html(ex, savePage(null)); }
                case "/query"     -> { if ("POST".equals(method)) handleDetailPost(ex); else html(ex, queryPage(ex)); }
                case "/chart-img" -> serveChart(ex);
                default           -> redirect(ex, "/main");
            }
        } catch (Exception e) {
            html(ex, errorPage(e.getMessage()));
        }
    }

    // ==================== 登录 ====================

    private static void handleLogin(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(ex);
        if ("274823137".equals(form.get("password"))) {
            ex.getResponseHeaders().add("Set-Cookie", COOKIE_NAME + "=" + VALID_TOKEN + "; Path=/");
            redirect(ex, "/main");
        } else {
            html(ex, loginPage("密码错误"));
        }
    }

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
        sb.append("<h2>消费查询</h2>");

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
        sb.append("<section class='query-section'><h3>特殊消费 (固定消费)</h3>");
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

    private static String loginPage(String error) {
        String err = error != null ? "<p class='error'>" + error + "</p>" : "";
        return pageRaw("登录 - 消费记录系统",
            "<div class='top-right-toggle'>" + themeToggle() + "</div>" +
            "<div class='login-box'><h1 class='hero-title'>消费记录系统</h1>" +
	            "<p class='hero-subtitle'>认真记录每一笔花销</p>" + err +
            "<form method='post' action='/login'>" +
            "<input type='password' name='password' placeholder='请输入密码' required autofocus>" +
            "<button type='submit' class='btn' style='width:100%'>登 录</button>" +
            "</form></div>");
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
        sb.append("<div class='hero-body'><h2>存入消费</h2><p>记录日常消费或固定消费，按日期存入当月表中</p></div>");
        sb.append("<div class='hero-arrow'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='24' height='24'><path d='M9 18l6-6-6-6'/></svg></div>");
        sb.append("</div></a>");
        // 卡片二：消费查询
        sb.append("<a href='/query' class='hero-link' style='--i:1'>");
        sb.append("<div class='hero-card hero-card-query'>");
        sb.append("<div class='hero-icon'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' width='36' height='36'><path d='M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z'/></svg></div>");
        sb.append("<div class='hero-body'><h2>消费查询</h2><p>月度统计 · 日均消费 · 折线图 · 日明细 · 特殊消费</p></div>");
        sb.append("<div class='hero-arrow'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='24' height='24'><path d='M9 18l6-6-6-6'/></svg></div>");
        sb.append("</div></a>");
        sb.append("</div>");
        return page("主菜单", sb.toString());
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
            "<label class='radio-pill'><input type='radio' name='type' value='2'><span class='pill-text'>固定消费</span></label>" +
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
            "</head><body><main class='container'>" + body + "</main></body></html>";
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
"animation:cardIn .5s var(--ease-out) both;animation-delay:calc(var(--i,0)*120ms)}" +
".hero-link:hover{text-decoration:none}" +
            ".hero-card{display:flex;align-items:center;gap:var(--space-lg);" +
            "background:var(--surface);padding:var(--space-xl) var(--space-xl);" +
            "border-radius:var(--radius-lg);border:1px solid var(--border);" +
            "box-shadow:var(--shadow);cursor:pointer;" +
            "transition:all .35s var(--ease-in-out);position:relative;overflow:hidden}" +
            ".hero-card::before{content:'';position:absolute;top:0;left:0;right:0;" +
            "height:3px;border-radius:3px 3px 0 0;transition:height .35s var(--ease-in-out)}" +
            ".hero-card-save::before{background:linear-gradient(90deg,var(--accent),#c4b5fd)}" +
            "[data-theme='dark'] .hero-card-save::before{background:linear-gradient(90deg,#a5b4fc,var(--accent))}" +
            ".hero-card-query::before{background:linear-gradient(90deg,#e8920a,#ea580c)}" +
            "[data-theme='dark'] .hero-card-query::before{background:linear-gradient(90deg,#fbbf24,#f59e0b)}" +
            ".hero-card:hover{transform:translateY(-4px) scale(1.015);" +
            "box-shadow:0 12px 40px rgba(0,0,0,.12);border-color:transparent}" +
            ".hero-card:hover::before{height:5px}" +
            ".hero-card:active{transform:translateY(-2px) scale(1.005)}" +
            ".hero-icon{flex-shrink:0;width:64px;height:64px;display:flex;align-items:center;" +
            "justify-content:center;border-radius:16px;transition:transform .35s var(--ease-in-out)}" +
            ".hero-card-save .hero-icon{background:rgba(79,110,247,.1);color:var(--accent)}" +
            ".hero-card-query .hero-icon{background:rgba(245,158,11,.1);color:#f59e0b}" +
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
            "@keyframes cardIn{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:translateY(0)}}" +
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
