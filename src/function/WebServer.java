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
                case "/query"     -> html(ex, queryPage());
                case "/chart"     -> html(ex, chartPage());
                case "/chart-img" -> serveChart(ex);
                case "/detail"    -> { if ("POST".equals(method)) handleDetailPost(ex); else html(ex, detailPage(ex)); }
                case "/special"   -> html(ex, specialPage());
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

    // ==================== 总消费查询 ====================

    private static String queryPage() throws Exception {
        String table = getMonthTable();
        Connection conn = One.getConn();
        if (conn == null) return errorPage("数据库连接失败");

        PreparedStatement ps = One.getPreparedStmt(conn,
            "SELECT SUM(price), COUNT(DISTINCT date), COUNT(*), " +
            "COALESCE(SUM(CASE WHEN num_1 = 1 THEN price END) / NULLIF(COUNT(DISTINCT CASE WHEN num_1 = 1 THEN date END), 0), 0) FROM " + table);
        ResultSet rs = ps.executeQuery();
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>查询总消费与日均消费</h2>");
        if (rs.next() && rs.getInt(3) > 0) {
            sb.append("<div class='stat'><span>总消费</span><strong>¥").append(String.format("%.2f", rs.getDouble(1))).append("</strong></div>");
            sb.append("<div class='stat'><span>日均消费（仅日常）</span><strong>¥").append(String.format("%.2f", rs.getDouble(4))).append("</strong></div>");
            sb.append("<div class='stat'><span>消费天数</span><strong>").append(rs.getInt(2)).append(" 天</strong></div>");
            sb.append("<div class='stat'><span>总记录数</span><strong>").append(rs.getInt(3)).append(" 条</strong></div>");
        } else {
            sb.append("<p>暂无消费记录。</p>");
        }
        rs.close(); ps.close(); conn.close();
        return page("总消费查询", sb.toString());
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
        String table = getMonthTable();
        int day = Integer.parseInt(f.get("day"));

        Connection conn = One.getConn();
        if (conn == null) { redirect(ex, "/detail?day=" + day); return; }
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
        redirect(ex, "/detail?day=" + day);
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
            "<div class='login-box'><h1>消费记录系统</h1>" + err +
            "<form method='post' action='/login'>" +
            "<input type='password' name='password' placeholder='请输入密码' required autofocus>" +
            "<button type='submit' class='btn' style='width:100%'>登 录</button>" +
            "</form></div>");
    }

    private static String mainPage() {
        String t = getMonthTable();
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>消费记录系统</h1>");
        sb.append("<p class='hint'>当前月份表: ").append(t).append(" | 日期: ").append(Date_time.getDay()).append("日</p>");
        sb.append("<div class='nav-grid'>");
        sb.append(card("/save", "存入消费", "记录日常或固定消费", 0));
        sb.append(card("/query", "总消费查询", "查看总消费与日均消费", 1));
        sb.append(card("/chart", "消费折线图", "生成每日消费趋势图", 2));
        sb.append(card("/detail", "日消费明细", "查询/编辑/删除指定日记录", 3));
        sb.append(card("/special", "特殊消费", "查看固定消费记录", 4));
        sb.append("</div>");
        return page("主菜单", sb.toString());
    }

    private static String savePage(String msg) {
        String msgHtml = msg != null ? "<p class='" + (msg.contains("成功") ? "success" : "error") + "'>" + msg + "</p>" : "";
        int today = Date_time.getDay();
        return page("存入消费",
            "<h2>存入消费信息</h2>" + msgHtml +
            "<form method='post' action='/save'>" +
            "<div class='radio-group'>" +
            "<label class='radio-label'><input type='radio' name='type' value='1' checked> 日常消费</label>" +
            "<label class='radio-label'><input type='radio' name='type' value='2'> 固定消费</label>" +
            "</div>" +
            "<label>商品名称</label><input name='goods' required placeholder='例如：午餐'>" +
            "<label>价格（元）</label><input name='price' type='number' step='0.01' required placeholder='例如：25.5'>" +
            "<label>日期（日）</label><input name='day' type='number' min='1' max='31' value='" + today + "' placeholder='" + today + "'>" +
            "<button type='submit' class='btn'>确认存入</button>" +
            "</form>");
    }

    // ==================== HTML 模板 ====================

    private static String page(String title, String body) {
        return pageRaw(title, nav() + body);
    }

    private static String pageRaw(String title, String body) {
        return "<!DOCTYPE html><html lang='zh' data-theme='light'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>" + title + "</title><style>" + css() + "</style>" +
            "<script>(function(){var t=localStorage.getItem('theme')||'light';document.documentElement.setAttribute('data-theme',t);})();" +
            "function toggleTheme(){var e=document.documentElement;var t=e.getAttribute('data-theme')==='dark'?'light':'dark';e.setAttribute('data-theme',t);localStorage.setItem('theme',t);}</script>" +
            "</head><body><div class='container'>" + body + "</div></body></html>";
    }

    private static String nav() {
        return "<nav><div class='nav-links'><a href='/main'>首页</a><a href='/save'>存入</a><a href='/query'>总消费</a><a href='/chart'>折线图</a><a href='/detail'>明细</a><a href='/special'>特殊</a></div>" + themeToggle() + "</nav>";
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
            "--text:#1e1e2e;--text2:#6b6b80;--border:#e2e2ec;" +
            "--accent:#4f6ef7;--accent-h:#3b54d4;--accent-light:rgba(79,110,247,.08);" +
            "--danger:#e74c3c;--danger-h:#c0392b;--danger-light:rgba(231,76,60,.08);" +
            "--success:#27ae60;--success-light:rgba(39,174,96,.08);" +
            "--shadow-sm:0 1px 2px rgba(0,0,0,.04);" +
            "--shadow:0 1px 3px rgba(0,0,0,.06),0 1px 2px rgba(0,0,0,.04);" +
            "--shadow-lg:0 4px 16px rgba(0,0,0,.08),0 2px 4px rgba(0,0,0,.04);" +
            "--input-bg:#fff;--input-bd:#d4d4e0;--toggle-bg:#c8cad8;" +
            "--hover:rgba(79,110,247,.04);--nav-hover:rgba(79,110,247,.06);" +
            "--radius-sm:6px;--radius:10px;--radius-lg:14px;" +
            "--ease-out:cubic-bezier(.16,1,.3,1);--ease-in-out:cubic-bezier(.4,0,.2,1)}" +

            // === CSS变量：深色 ===
            "[data-theme='dark']{" +
            "--bg:#0d0d1a;--surface:#16162b;--surface2:#1c1c34;--elevated:#222242;" +
            "--text:#e2e2ee;--text2:#8e8eaa;--border:#282850;" +
            "--accent:#7b9bff;--accent-h:#9bb5ff;--accent-light:rgba(123,155,255,.1);" +
            "--danger:#ff6b6b;--danger-h:#e05555;--danger-light:rgba(255,107,107,.1);" +
            "--success:#5ddb6e;--success-light:rgba(93,219,110,.1);" +
            "--shadow-sm:0 1px 2px rgba(0,0,0,.3);" +
            "--shadow:0 1px 3px rgba(0,0,0,.4);" +
            "--shadow-lg:0 4px 20px rgba(0,0,0,.5);" +
            "--input-bg:#1a1a35;--input-bd:#32325a;--toggle-bg:#4f6ef7;" +
            "--hover:rgba(123,155,255,.06);--nav-hover:rgba(123,155,255,.08)}" +

            // === 基础 ===
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'Microsoft YaHei','PingFang SC','Noto Sans SC',sans-serif;" +
            "background:var(--bg);color:var(--text);min-height:100vh;" +
            "font-weight:350;line-height:1.6;" +
            "transition:background .4s var(--ease-out),color .4s var(--ease-out)}" +
            ".container{max-width:860px;margin:0 auto;padding:var(--space-lg)}" +

            // === 导航栏 ===
            "nav{background:var(--surface);padding:0 var(--space-lg);border-radius:var(--radius-lg);" +
            "margin-bottom:var(--space-xl);box-shadow:var(--shadow);display:flex;align-items:center;" +
            "height:52px;transition:background .4s var(--ease-out),box-shadow .4s var(--ease-out);" +
            "border:1px solid var(--border)}" +
            ".nav-links{display:flex;gap:var(--space-xs)}" +
            "nav a{color:var(--text2);text-decoration:none;padding:var(--space-sm) 14px;" +
            "border-radius:var(--radius-sm);font-size:14px;font-weight:450;" +
            "transition:all .2s var(--ease-out);position:relative}" +
            "nav a:hover{background:var(--nav-hover);color:var(--accent)}" +
            "nav a:active{transform:scale(.96)}" +

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
            ".login-box h1{font-size:clamp(20px,3vw,24px);margin-bottom:var(--space-xl);" +
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
            "h1{font-size:clamp(22px,3vw,28px);margin-bottom:var(--space-sm);" +
            "font-weight:700;letter-spacing:-.02em}" +
            "h2{font-size:clamp(17px,2.5vw,21px);margin-bottom:var(--space-md);" +
            "font-weight:650;letter-spacing:-.01em}" +
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

            // === 容器查询 ===
            "@container(inline-size){.nav-grid{grid-template-columns:repeat(auto-fill,minmax(180px,1fr))}}";
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
