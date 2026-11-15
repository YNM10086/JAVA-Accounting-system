package function;

import com.sun.net.httpserver.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
                System.out.println("端口 " + port + " 被占用,尝试下一个...");
            }
        }
        if (srv == null) {
            System.out.println("所有端口均被占用,请检查是否有已运行的实例！");
            return;
        }

        srv.createContext("/", WebServer::handle);
        // AI 请求可能跑几秒，用线程池避免把整站请求串行堵住
        srv.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        srv.start();

        // 启动时自愈一次表结构（缺 cat 列就补），失败不影响服务
        try (Connection c = One.getConn()) {
            ensureCatColumn(c);
        } catch (Exception e) {
            System.out.println("表结构检查跳过：" + e.getMessage());
        }

        String url = "http://localhost:" + usedPort;
        System.out.println("====================================");
        System.out.println("Web服务器已启动: " + url);
        System.out.println("如果浏览器未自动打开,请手动访问上述地址");
        System.out.println("====================================");
        // 给外部外壳（如 Electron）用的机器可读地址，勿改格式
        System.out.println("LOH_URL=" + url);
        System.out.flush();

        // 被桌面外壳接管时不要再唤起系统浏览器
        if (!"1".equals(System.getenv("LOH_NO_BROWSER"))) {
            try { Desktop.getDesktop().browse(new URI(url)); } catch (Exception ignored) {}
        }
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
                case "/main"         -> html(ex, mainPage(ex.getRequestURI().getQuery()));
                case "/save"         -> { if ("POST".equals(method)) handleSavePost(ex, parseForm(ex)); else redirect(ex, "/main#entry"); }
                case "/income"       -> { if ("POST".equals(method)) handleIncomePost(ex, parseForm(ex)); else redirect(ex, "/main#income"); }
                case "/query"        -> { if ("POST".equals(method)) handleDetailPost(ex); else redirect(ex, "/main#details"); }
                case "/budget"       -> redirect(ex, "/main#budget");
                case "/settings"     -> redirect(ex, "/main#settings");
                case "/api/month"    -> {
                    Map<String, String> qp = parseQuery(ex.getRequestURI().getQuery());
                    int mm = Date_time.getMonth();
                    try { if (qp.get("m") != null) mm = Integer.parseInt(qp.get("m")); } catch (Exception ignore) {}
                    json(ex, Overview.load(mm).monthJson());
                }
                case "/export"       -> handleExportData(ex);
                case "/chart-img"    -> serveChart(ex);
                case "/ai/status"    -> json(ex, AiAssistant.statusJson());
                case "/ai/parse"     -> { if ("POST".equals(method)) handleAiParse(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/save"      -> { if ("POST".equals(method)) handleAiSave(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/advice"    -> { if ("POST".equals(method)) handleAiAdvice(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/classify"  -> { if ("POST".equals(method)) handleAiClassify(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                default              -> {
                    if (path.startsWith("/static/")) serveStatic(ex, path);
                    else redirect(ex, "/main");
                }
            }
        } catch (Exception e) {
            html(ex, errorPage(e.getMessage()));
        }
    }

    /** 写完数据回到单页的对应锚点；msg 为空表示成功，否则是错误文案 */
    private static void backTo(HttpExchange ex, String section, String msg) throws IOException {
        String q = (msg == null || msg.isEmpty())
            ? "ok=1"
            : "err=" + java.net.URLEncoder.encode(msg, StandardCharsets.UTF_8);
        redirect(ex, "/main?" + q + "#" + section);
    }

    // ==================== 登录 ====================

    // ==================== 存入消费 ====================

    private static void handleSavePost(HttpExchange ex, Map<String, String> f) throws Exception {
        Archiver.ensureRollover(); // 写入前检查跨年
        // 先解析月份与日期,便于出错时回显用户所选
        String monthStr = f.get("month");
        int month;
        try {
            month = (monthStr != null && !monthStr.trim().isEmpty()) ? Integer.parseInt(monthStr) : Date_time.getMonth();
        } catch (NumberFormatException e) {
            month = Date_time.getMonth();
        }
        if (month < 1 || month > 12) month = Date_time.getMonth();

        String dayStr = f.get("day");
        int day;
        try {
            day = (dayStr != null && !dayStr.trim().isEmpty()) ? Integer.parseInt(dayStr) : Date_time.getDay();
        } catch (NumberFormatException e) {
            day = Date_time.getDay();
        }
        if (day < 1 || day > 31) day = Date_time.getDay();

        String goods = f.get("goods");
        String priceStr = f.get("price");
        String typeStr = f.get("type");

        if (goods == null || goods.trim().isEmpty() || priceStr == null || typeStr == null) {
            backTo(ex, "entry", "商品名和价格不能为空"); return;
        }
        double price;
        try { price = Double.parseDouble(priceStr); } catch (NumberFormatException e) {
            backTo(ex, "entry", "价格格式错误"); return;
        }
        int num1 = "1".equals(typeStr) ? 1 : 2;
        String cat = Cat.clean(f.get("cat"), goods);

        String table = String.format("table_%02d", month);
        Connection conn = One.getConn();
        if (conn == null) { backTo(ex, "entry", "数据库连接失败"); return; }
        try {
            ensureCatColumn(conn);
            PreparedStatement ps = One.getPreparedStmt(conn,
                "INSERT INTO " + table + " (date, goods, price, num_1, cat) VALUES (?, ?, ?, ?, ?)");
            ps.setInt(1, day);
            ps.setString(2, goods.trim());
            ps.setDouble(3, price);
            ps.setInt(4, num1);
            ps.setString(5, cat);
            ps.executeUpdate();
            ps.close();
            conn.close();
            backTo(ex, "entry", "");
        } catch (Exception e) {
            conn.close();
            backTo(ex, "entry", "存入失败：" + e.getMessage());
        }
    }

    private static void handleIncomePost(HttpExchange ex, Map<String, String> f) throws Exception {
        Archiver.ensureRollover(); // 写入前检查跨年
        int month = Date_time.getMonth();
        Connection conn = One.getConn();
        if (conn == null) { backTo(ex, "income", "数据库连接失败"); return; }
        try {
            initTable(conn);
            // 删除
            if ("delete".equals(f.get("_action"))) {
                int id = Integer.parseInt(f.get("id"));
                PreparedStatement ps = One.getPreparedStmt(conn, "DELETE FROM income_records WHERE id = ?");
                ps.setInt(1, id); ps.executeUpdate(); ps.close();
            }
            // 新增
            else {
                int day;
                try { day = Integer.parseInt(f.get("date")); } catch (Exception e) { day = Date_time.getDay(); }
                double amount;
                try { amount = Double.parseDouble(f.get("amount")); } catch (Exception e) { conn.close(); backTo(ex, "income", "金额格式错误"); return; }
                String desc = f.get("description");
                if (desc == null) desc = "";
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "INSERT INTO income_records (month, date, amount, description) VALUES (?, ?, ?, ?)");
                ps.setInt(1, month); ps.setInt(2, day); ps.setDouble(3, amount); ps.setString(4, desc.trim());
                ps.executeUpdate(); ps.close();
            }
            conn.close();
        } catch (Exception e) { try { conn.close(); } catch (Exception ignored) {} backTo(ex, "income", e.getMessage()); return; }
        backTo(ex, "income", "");
    }

    // ==================== AI 记账（本地 Ollama） ====================

    private static void handleAiParse(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> body = Json.map(Json.parse(readBody(ex)));
            String text = Json.str(body.get("text"));
            if (text == null || text.trim().isEmpty()) {
                json(ex, "{\"ok\":false,\"error\":\"先说说今天买了什么\"}");
                return;
            }
            List<AiAssistant.Item> items = AiAssistant.parse(text.trim());
            json(ex, AiAssistant.itemsJson(items));
        } catch (Exception e) {
            json(ex, "{\"ok\":false,\"error\":\"" + Json.esc(errMsg(e)) + "\"}");
        }
    }

    private static void handleAiSave(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> body = Json.map(Json.parse(readBody(ex)));
            List<AiAssistant.Item> items = new ArrayList<>();
            for (Object o : Json.list(body.get("items"))) {
                Map<String, Object> m = Json.map(o);
                String goods = Json.str(m.get("goods"));
                double price = Json.num(m.get("price"), 0);
                if (goods == null || goods.trim().isEmpty() || price <= 0) continue;
                String g = goods.trim();
                if (g.length() > 40) g = g.substring(0, 40);
                AiAssistant.Item it = new AiAssistant.Item();
                it.goods = g;
                it.price = Math.round(price * 100) / 100.0;
                it.type = Json.i(m.get("type"), 1) == 2 ? 2 : 1;
                it.day = Math.min(31, Math.max(1, Json.i(m.get("day"), Date_time.getDay())));
                items.add(it);
            }
            if (items.isEmpty()) {
                json(ex, "{\"ok\":false,\"error\":\"没有可保存的条目\"}");
                return;
            }
            int n = AiAssistant.save(items);
            String advice = "";
            String adviceErr = null;
            try {
                advice = AiAssistant.advice();
            } catch (Exception e) {
                adviceErr = errMsg(e);
            }
            StringBuilder b = new StringBuilder();
            b.append("{\"ok\":true,\"saved\":").append(n)
             .append(",\"advice\":\"").append(Json.esc(advice)).append('"');
            if (adviceErr != null) {
                b.append(",\"adviceError\":\"").append(Json.esc(adviceErr)).append('"');
            }
            b.append('}');
            json(ex, b.toString());
        } catch (Exception e) {
            json(ex, "{\"ok\":false,\"error\":\"" + Json.esc(errMsg(e)) + "\"}");
        }
    }

    /** 让 AI 按批重新分类某月（默认本月）。失败时返回 ok:false，页面上给提示。 */
    private static void handleAiClassify(HttpExchange ex) throws IOException {
        try {
            String raw = readBody(ex);
            Map<String, Object> body = Json.map(Json.parse(raw == null || raw.isEmpty() ? "{}" : raw));
            int month = Json.i(body.get("month"), Date_time.getMonth());
            int changed = AiAssistant.reclassify(month);
            json(ex, "{\"ok\":true,\"changed\":" + changed + "}");
        } catch (Exception e) {
            json(ex, "{\"ok\":false,\"error\":\"" + Json.esc(errMsg(e)) + "\"}");
        }
    }

    private static void handleAiAdvice(HttpExchange ex) throws IOException {
        try {
            json(ex, "{\"ok\":true,\"advice\":\"" + Json.esc(AiAssistant.advice()) + "\"}");
        } catch (Exception e) {
            json(ex, "{\"ok\":false,\"error\":\"" + Json.esc(errMsg(e)) + "\"}");
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 给用户看的短错误，避免把整段异常直接甩到气泡里 */
    private static String errMsg(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.ConnectException
                || (m != null && (m.contains("Connection refused") || m.contains("connect timed out")))) {
            return "连不上 Ollama，先确认它正在运行";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "AI 响应超时，稍后再试";
        }
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void initTable(Connection conn) {
        try {
            Statement st = conn.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS income_records (id INT AUTO_INCREMENT PRIMARY KEY, month INT NOT NULL, date INT NOT NULL, amount DECIMAL(10,2) NOT NULL, description VARCHAR(200) DEFAULT '')");
            st.close();
        } catch (Exception e) { /* 表已存在则忽略 */ }
        ensureCatColumn(conn);
    }

    /** 拼成 JS 字符串字面量（带引号），用于把后端的提示交给前端弹 */
    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'";
    }

    /** Cat.ALL 的 JS 数组字面量，前端下拉与后端白名单同源 */
    private static String catJs() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < Cat.ALL.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(Cat.ALL[i]).append('"');
        }
        return b.append(']').toString();
    }

    /** 分类下拉选项（与 Cat.ALL 同源） */
    private static String catOptions() {
        StringBuilder b = new StringBuilder();
        for (String c : Cat.ALL) b.append("<option>").append(c).append("</option>");
        return b.toString();
    }

    /**
     * 表结构自愈：给 table_01~12 补一列 cat（幂等；表不存在就跳过）。
     * 月表是手工建的，不能假定 cat 一定在。
     */
    static void ensureCatColumn(Connection conn) {
        if (conn == null) return;
        for (int m = 1; m <= 12; m++) {
            String t = String.format("table_%02d", m);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
                    ps.setString(1, t);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) == 0) continue;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='cat'")) {
                    ps.setString(1, t);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) continue;
                    }
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE " + t + " ADD COLUMN cat VARCHAR(16) NOT NULL DEFAULT '其他'");
                    System.out.println("已补列 " + t + ".cat");
                }
            } catch (Exception e) {
                System.out.println("补列失败（" + t + "）：" + e.getMessage());
            }
        }
    }

    /** 读取当月总收入,无记录返回 1500 */
    private static double getMonthlyIncome() {
        int month = Date_time.getMonth();
        Connection conn = One.getConn();
        if (conn == null) return 1500;
        try {
            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT COALESCE(SUM(amount), 0) FROM income_records WHERE month = ?");
            ps.setInt(1, month);
            ResultSet rs = ps.executeQuery();
            double income = rs.next() ? rs.getDouble(1) : 0;
            rs.close(); ps.close(); conn.close();
            return income > 0 ? income : 1500;
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ignored) {}
            return 1500;
        }
    }

    // ==================== 消费查询（融合总消费+折线图+明细+特殊） ====================

    private static String queryPage(HttpExchange ex) throws Exception {
        // 解析月份参数,默认当前月
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
        sb.append("<button onclick='exportExcel(").append(selMonth).append(")' class='btn btn-sm btn-export'>📥 导出Excel</button>");
        sb.append("<script src='/static/xlsx.full.min.js'></script>");
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
                "SELECT SUM(price), SUM(CASE WHEN num_1=1 THEN price END), COUNT(*) FROM " + table);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(3) > 0) {
                int elapsedDays = Date_time.getElapsedDays(selMonth);
                double dailySum = rs.getDouble(2);
                double dailyAvg = elapsedDays > 0 ? dailySum / elapsedDays : 0;
                sb.append("<div class='stat-row'>");
                sb.append("<div class='stat'><span>总消费</span><strong>¥").append(String.format("%.2f", rs.getDouble(1))).append("</strong></div>");
                sb.append("<div class='stat'><span>日均消费</span><strong>¥").append(String.format("%.2f", dailyAvg)).append("</strong></div>");
                sb.append("<div class='stat'><span>统计天数</span><strong>").append(elapsedDays).append(" 天</strong></div>");
                sb.append("<div class='stat'><span>总记录数</span><strong>").append(rs.getInt(3)).append(" 条</strong></div>");
                sb.append("</div>");
                sb.append("<p class='hint'>日均消费 = 日常消费总额 ÷ 已过天数（").append(selMonth == curMonth ? "本月截至今天" : "整月").append("）</p>");
            } else {
                sb.append("<p>暂无消费记录。</p>");
            }
            rs.close(); ps.close();
        } finally { conn.close(); }
        sb.append("</section>");

        // === 二、日常 vs 特殊 对比折线图 ===
        sb.append("<section class='query-section'><h3>日常 vs 特殊趋势</h3>");
        sb.append("<p class='hint'>红线 = 日常消费（num_1=1），蓝线 = 特殊消费（num_1=2）</p>");
        Connection connCmp = One.getConn();
        if (connCmp != null) {
            try {
                PreparedStatement psCmp = One.getPreparedStmt(connCmp,
                    "SELECT date, SUM(CASE WHEN num_1=1 THEN price ELSE 0 END), " +
                    "SUM(CASE WHEN num_1=2 THEN price ELSE 0 END) FROM " + table +
                    " GROUP BY date ORDER BY date");
                ResultSet rsCmp = psCmp.executeQuery();
                List<Integer> cmpDays = new ArrayList<>();
                List<Double> cmpDaily = new ArrayList<>();
                List<Double> cmpSpecial = new ArrayList<>();
                while (rsCmp.next()) {
                    cmpDays.add(rsCmp.getInt(1));
                    cmpDaily.add(rsCmp.getDouble(2));
                    cmpSpecial.add(rsCmp.getDouble(3));
                }
                rsCmp.close(); psCmp.close();
                if (!cmpDays.isEmpty()) {
                    Line.drawCompareChart(cmpDays, cmpDaily, cmpSpecial,
                        table + " 日常 / 特殊消费对比", table + "_chart_cmp.png");
                    sb.append("<img src='/chart-img?f=").append(table).append("_chart_cmp.png' class='chart-img' alt='")
                      .append(selMonth).append("月日常与特殊消费对比折线图' onerror=\"this.outerHTML='<p>图片加载失败</p>'\">");
                } else {
                    sb.append("<p>暂无数据。</p>");
                }
            } finally { connCmp.close(); }
        }
        sb.append("</section>");

        // === 三、日消费明细 ===
        sb.append("<section class='query-section'><h3>日消费明细</h3>");

        // 今日消费（当月表内今天这一天的全部记录，含日常与特殊）
        int todayDay = Date_time.getDay();
        String todayTable = String.format("table_%02d", curMonth);
        Connection connToday = One.getConn();
        if (connToday != null) {
            try {
                PreparedStatement psToday = One.getPreparedStmt(connToday,
                    "SELECT COALESCE(SUM(price),0), COUNT(*) FROM " + todayTable + " WHERE date=?");
                psToday.setInt(1, todayDay);
                ResultSet rsToday = psToday.executeQuery();
                if (rsToday.next()) {
                    double todaySum = rsToday.getDouble(1);
                    int todayCnt = rsToday.getInt(2);
                    sb.append("<div class='stat-row'>");
                    sb.append("<div class='stat'><span>今日消费（").append(curMonth).append("月").append(todayDay).append("日）</span><strong>¥")
                        .append(String.format("%.2f", todaySum)).append("</strong></div>");
                    sb.append("<div class='stat'><span>今日笔数</span><strong>").append(todayCnt).append(" 笔</strong></div>");
                    sb.append("</div>");
                    if (todayCnt == 0) sb.append("<p class='hint'>今天还没有消费记录。</p>");
                    if (selMonth != curMonth) sb.append("<p class='hint'>「今日消费」始终统计当前月份（").append(curMonth).append("月）的今天，与上方所选月份无关。</p>");
                }
                rsToday.close(); psToday.close();
            } finally { connToday.close(); }
        }

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
                                .append("</td><td>").append(n1 == 1 ? "日常" : "特殊").append("</td><td>");
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

        sb.append("<script>");
        sb.append("function exportExcel(m){");
        sb.append("fetch('/export?month='+m).then(function(r){return r.json()}).then(function(d){");
        sb.append("var wb=XLSX.utils.book_new();");
        sb.append("var a=[['日常消费','','','','特殊消费','','']];");
        sb.append("a.push(['日期','商品名','金额','','日期','商品名','金额']);");
        sb.append("var mx=Math.max(d.daily.length,d.special.length);");
        sb.append("for(var i=0;i<mx;i++){var r=['','','','','','',''];");
        sb.append("if(i<d.daily.length){r[0]=d.daily[i].d+'日';r[1]=d.daily[i].g;r[2]=d.daily[i].p}");
        sb.append("if(i<d.special.length){r[4]=d.special[i].d+'日';r[5]=d.special[i].g;r[6]=d.special[i].p}");
        sb.append("a.push(r)}");
        sb.append("var dd=0;d.daily.forEach(function(x){dd+=x.p});");
        sb.append("var ds=0;d.special.forEach(function(x){ds+=x.p});");
        sb.append("a.push(['','合计',dd,'','','合计',ds]);");
        sb.append("var ws=XLSX.utils.aoa_to_sheet(a);");
        sb.append("ws['!cols']=[{wch:6},{wch:24},{wch:10},{wch:3},{wch:6},{wch:24},{wch:10}];");
        sb.append("XLSX.utils.book_append_sheet(wb,ws,'"+ "'+m+'月" + "');");
        sb.append("XLSX.writeFile(wb,m+'月消费数据.xlsx')})}");
        sb.append("</script>");
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

    private static void handleExportData(HttpExchange ex) throws Exception {
        int month = Date_time.getMonth();
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            try { month = Integer.parseInt(parseQuery(q).get("month")); } catch (Exception ignored) {}
        }
        String table = String.format("table_%02d", month);

        Connection conn = One.getConn();
        if (conn == null) { try { html(ex, errorPage("数据库连接失败")); } catch (Exception ignored) {} return; }

        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"month\":").append(month).append(",\"daily\":[");

            PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT date, goods, price FROM " + table + " WHERE num_1=1 ORDER BY date");
            ResultSet rs = ps.executeQuery();
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                json.append("{\"d\":").append(rs.getInt("date"))
                    .append(",\"g\":\"").append(escJson(rs.getString("goods")))
                    .append("\",\"p\":").append(rs.getDouble("price")).append("}");
                first = false;
            }
            rs.close();

            json.append("],\"special\":[");
            ps = One.getPreparedStmt(conn,
                "SELECT date, goods, price FROM " + table + " WHERE num_1=2 ORDER BY date");
            rs = ps.executeQuery();
            first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                json.append("{\"d\":").append(rs.getInt("date"))
                    .append(",\"g\":\"").append(escJson(rs.getString("goods")))
                    .append("\",\"p\":").append(rs.getDouble("price")).append("}");
                first = false;
            }
            rs.close(); ps.close(); conn.close();
            json.append("]}");

            byte[] bytes = json.toString().getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ignored) {}
            throw e;
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
                        .append("</td><td>").append(n1 == 1 ? "日常" : "特殊").append("</td><td>");
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
        backTo(ex, "details", "");
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
            "{t:'凌晨',m:'凌晨了,早点休息',h0:0,h1:5,bg:'linear-gradient(135deg,#0a0a1a,#1a1040,#0d0d2b)',gt:'linear-gradient(135deg,#818cf8,#c4b5fd)'}," +
            "{t:'早上',m:'早上好',h0:6,h1:8,bg:'linear-gradient(135deg,#ffecd2,#fcb69f,#a1c4fd)',gt:'linear-gradient(135deg,#f97316,#ec4899)'}," +
            "{t:'上午',m:'上午好',h0:9,h1:11,bg:'linear-gradient(135deg,#e0eafc,#cfdef3,#fffde7)',gt:'linear-gradient(135deg,#3b82f6,#06b6d4)'}," +
            "{t:'中午',m:'中午好',h0:12,h1:13,bg:'linear-gradient(135deg,#e0f7fa,#b2ebf2,#fff9c4)',gt:'linear-gradient(135deg,#00bcd4,#ff9800)'}," +
            "{t:'下午',m:'下午好',h0:14,h1:17,bg:'linear-gradient(135deg,#fef3c7,#fde68a,#fecaca)',gt:'linear-gradient(135deg,#f59e0b,#ef4444)'}," +
            "{t:'晚上',m:'晚上好',h0:18,h1:20,bg:'linear-gradient(135deg,#1e1b4b,#312e81,#5b21b6)',gt:'linear-gradient(135deg,#a78bfa,#fbbf24)'}," +
            "{t:'深夜',m:'深夜了,注意休息',h0:21,h1:23,bg:'linear-gradient(135deg,#020024,#090979,#1a0040)',gt:'linear-gradient(135deg,#c084fc,#6366f1,#ec4899)'}];" +
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


    private static String mainPage() { return mainPage(null); }

    private static String mainPage(String rawQuery) {
        Overview ov = Overview.load();
        Map<String, String> q = rawQuery == null || rawQuery.isEmpty()
            ? new HashMap<>() : parseQuery(rawQuery);
        String flash = "";
        if (q.get("err") != null) flash = "<script>window.__flash=[" + jsStr(q.get("err")) + ",true];</script>";
        else if (q.get("ok") != null) flash = "<script>window.__flash=['✓ 已保存',false];</script>";
        String ymd = Date_time.getYear() + "-" + String.format("%02d", ov.month);
        String table = String.format("table_%02d", ov.month);
        double balance = ov.income - ov.expense;
        double used = Overview.BUDGET <= 0 ? 0 : ov.expense / Overview.BUDGET;
        double avg = ov.count > 0 ? ov.expense / Math.max(1, Date_time.getDay()) : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("<section class='sec' id='overview'>");
        sb.append("<div class='pagehead'><div><h1>总览</h1><div class='sub'><span class='bar'></span>")
          .append("<span class='label'>OVERVIEW / ").append(ymd).append(" / 本月累计</span></div></div>")
          .append("<div class='headtool'><span class='chip'>").append(Date_time.getYear())
          .append(" 年 ").append(ov.month).append(" 月</span>")
          .append("<a class='btn' href='/export'>导出</a>")
          .append("<a class='btn solid' href='/save'>＋ 记一笔</a></div></div>");

        // ---- KPI ----
        sb.append("<div class='kpis'>");
        double dayAvg = ov.expense / Math.max(1, Date_time.getDay());
        sb.append(kpiCard("收", "本月收入 / INCOME", ov.income, "",
                ov.income > 0 ? "本月累计" : "本月还没有收入记录", false));

        String deltaTxt; String deltaCls;
        if (ov.prevExpense > 0) {
            double pct = (ov.expense - ov.prevExpense) / ov.prevExpense * 100;
            deltaTxt = (pct >= 0 ? "▲ " : "▼ ") + String.format("%.1f", Math.abs(pct)) + "% 环比上月";
            deltaCls = pct >= 0 ? "down" : "up";
        } else {
            deltaTxt = "日均 ¥" + money(dayAvg);
            deltaCls = "";
        }
        sb.append(kpiCard("支", "本月支出 / EXPENSE", ov.expense, deltaCls, deltaTxt, true));

        sb.append(kpiCard("余", "本月结余 / BALANCE", balance, "",
                ov.income > 0
                    ? "结余率 " + String.format("%.1f", balance / ov.income * 100) + "% · 共 " + ov.count + " 笔支出"
                    : "共 " + ov.count + " 笔支出 · 尚无收入", false));
        sb.append("<section class='panel kpi budget'><i class='ghost' aria-hidden='true'>算</i>")
          .append("<p class='label'>本月预算 / BUDGET</p>")
          .append("<p class='num'><span class='cur'>¥</span>").append(money(Overview.BUDGET)).append("</p>")
          .append("<div class='bTrack'><i style='width:").append(String.format("%.1f", Math.min(used, 1) * 100))
          .append("%'></i></div>")
          .append("<p class='delta'>已用 ").append(String.format("%.1f", used * 100)).append("% · 剩余 ¥")
          .append(money(Math.max(0, Overview.BUDGET - ov.expense))).append("</p></section>");
        sb.append("</div>");

        // ---- 图表 ----
        sb.append("<div class='row2'>");
        sb.append("<section class='panel'><header class='ph'><span class='label' id='flowLabel'>本月每日支出 / DAILY</span>")
          .append("<span class='phTools'><span class='act' id='flowMeta'>—</span>")
          .append("<span class='seg' id='flowSeg' role='tablist'>")
          .append("<button type='button' class='on' data-mode='day'>本月每日</button>")
          .append("<button type='button' data-mode='month'>每月总额</button>")
          .append("</span></span></header>")
          .append("<div class='chartBox'><svg id='flow' viewBox='0 0 1000 190' role='img' aria-label='30 天每日支出柱状图'></svg>")
          .append("<div class='legend'><span id='legDaily'><i style='background:var(--accent);height:7px'></i><em id='legDailyTxt'>每日支出 DAILY</em></span>")
          .append("<span id='legAvg'><i style='background:transparent;border-top:1px dashed rgba(200,210,230,.62)'></i>7 日均线 AVG7</span></div>")
          .append("</div></section>");

        sb.append("<section class='panel'><header class='ph'><span class='label'>分类结构 / BREAKDOWN</span>")
          .append("<span class='act' id='ringNote'>").append(ov.month).append(" 月 · ").append(ov.cats.size()).append(" 类</span></header>")
          .append("<div class='share'><div class='ringWrap'>")
          .append("<svg id='ring' width='150' height='150' viewBox='0 0 150 150' role='img' aria-label='支出结构：内环日常/特殊，外环细分类'></svg>")
          .append("<div class='center'><span class='v' id='ringVal'>¥").append(money(ov.expense)).append("</span>")
          .append("<span class='k' id='ringKey'>EXPENSE</span></div></div>")
          .append("<ul id='legend'></ul></div></section>");
        sb.append("</div>");

        // ---- 最近流水 ----
        sb.append("<section class='panel'><header class='ph'><span class='label'>最近流水 / RECENT</span>")
          .append("<span class='phTools'><span class='act' id='recentNote'>本月 · 共 ").append(ov.count).append(" 条</span>")
          .append("<button class='link' id='recentReset' type='button' hidden>显示本月全部</button></span></header>")
          .append("<table><thead><tr><th style='width:100px'>编号 / NO.</th><th style='width:104px'>日期 / DATE</th>")
          .append("<th style='width:110px'>分类 / TAG</th><th>备注 / NOTE</th>")
          .append("<th style='width:140px;text-align:right'>金额 / AMOUNT</th></tr></thead><tbody id='recentBody'>");
        int no = ov.count;
        for (Overview.Row r : ov.recent) {
            sb.append("<tr><td class='id'>").append(String.format("%06d", Math.max(1, no--))).append("</td>")
              .append("<td class='dt'>").append(ymd).append("-").append(String.format("%02d", r.date)).append("</td>")
              .append("<td class='tag' data-cat='").append(esc(r.cat)).append("'><span class='tick'></span>")
              .append(esc(r.cat)).append("</td>")
              .append("<td class='note'>").append(esc(r.goods)).append("</td>")
              .append("<td class='amt out'>-¥").append(String.format("%,.2f", r.price)).append("</td></tr>");
        }
        if (ov.recent.isEmpty()) {
            sb.append("<tr><td colspan='5' style='text-align:center;color:var(--faint);padding:22px 0'>本月还没有记录</td></tr>");
        }
        sb.append("</tbody><tfoot><tr><td colspan='4'>共 ").append(ov.count).append(" 条记录 · 表 ").append(table).append("</td>")
          .append("<td class='sum'>合计 <b>¥").append(String.format("%,.2f", ov.expense)).append("</b></td></tr></tfoot></table></section>");

        sb.append("</section>");
        sb.append(Sections.entry());
        sb.append(Sections.income(ov));
        sb.append(Sections.details(ov));
        sb.append(Sections.budget(ov));
        sb.append(Sections.settings(ov));
        sb.append("<script>window.OV=").append(ov.json()).append(";</script>");
        sb.append(flash);
        return shell("总览", "overview", sb.toString());
    }

    private static String money(double v) {
        return String.format("%,.0f", v);
    }

    private static String kpiCard(String ghost, String label, double value, String cls, String note, boolean accent) {
        StringBuilder b = new StringBuilder();
        b.append("<section class='panel kpi").append(accent ? " accentLine" : "").append("'>")
         .append("<i class='ghost' aria-hidden='true'>").append(ghost).append("</i>")
         .append("<p class='label'>").append(label).append("</p>")
         .append("<p class='num'><span class='cur'>¥</span>").append(money(value)).append("</p>")
         .append("<p class='delta ").append(cls).append("'>").append(note).append("</p></section>");
        return b.toString();
    }


    // ==================== 消费管控 ====================

    /** 预算：每月固定预算 1500 元 */
    private static String budgetPage() {
        double monthlyIncome = getMonthlyIncome();
        String table = getMonthTable();
        int today = Date_time.getDay();
        int remainingDays = Date_time.getRemainingDays();

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>消费管控</h2>");
        sb.append("<p class='hint'>月收入 ¥").append(String.format("%.0f", monthlyIncome))
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
                    "SELECT COALESCE(SUM(CASE WHEN num_1=1 THEN price END),0) FROM " + table);
                rs = ps.executeQuery();
                if (rs.next()) {
                    int elapsedDays = Date_time.getElapsedDays(Date_time.getMonth());
                    dailyAvg = elapsedDays > 0 ? rs.getDouble(1) / elapsedDays : 0;
                }
                rs.close(); ps.close();
            } catch (Exception e) {
                e.printStackTrace();
            } finally { try { conn.close(); } catch (Exception ignored) {} }
        }

        // === 计算 ===
        double balance = monthlyIncome - totalSpent;
        double dailyRemaining = balance > 0 ? balance / remainingDays : 0;
        double deficit = totalSpent > monthlyIncome ? totalSpent - monthlyIncome : 0;

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
        if (prob < 30) return "赤字率较低,继续保持当前消费习惯";
        if (prob < 60) return "赤字率上升,需留意日常开支";
        return "赤字率较高,请减少非必要消费";
    }

    private static String savePage(String msg) {
        return savePage(msg, Date_time.getMonth(), Date_time.getDay());
    }

    private static String savePage(String msg, int month, int day) {
        String msgHtml = msg != null ? "<p class='" + (msg.contains("成功") ? "success" : "error") + "'>" + msg + "</p>" : "";
        if (month < 1 || month > 12) month = Date_time.getMonth();
        int curMonth = Date_time.getMonth();
        if (day < 1 || day > 31) day = Date_time.getDay();
        StringBuilder monthOptions = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            monthOptions.append("<option value='").append(m).append("'").append(m == month ? " selected" : "")
                .append(">").append(m).append("月").append(m == curMonth ? " (本月)" : "").append("</option>");
        }
        return page("存入消费",
            "<h2>存入消费信息</h2>" + msgHtml +
            "<div class='form-card'>" +
            "<form method='post' action='/save'>" +
            "<div class='radio-pill-group'>" +
            "<label class='radio-pill'><input type='radio' name='type' value='1' checked><span class='pill-text'>日常消费</span></label>" +
            "<label class='radio-pill'><input type='radio' name='type' value='2'><span class='pill-text'>特殊消费</span></label>" +
            "</div>" +
            "<div class='input-group'><label>商品名称</label>" +
            "<div class='input-wrap'><input name='goods' required placeholder='例如：午餐'></div></div>" +
            "<div class='input-group'><label>价格（元）</label>" +
            "<div class='input-wrap'><input name='price' type='number' step='0.01' required placeholder='例如：25.5'></div></div>" +
            "<div class='input-group'><label>消费细类</label>" +
            "<div class='input-wrap'><select name='cat'>" + catOptions() + "</select></div></div>" +
            "<div class='input-group'><label>月份</label>" +
            "<div class='input-wrap'><select name='month'>" + monthOptions + "</select></div></div>" +
            "<div class='input-group'><label>日期（日）</label>" +
            "<div class='input-wrap'><input name='day' type='number' min='1' max='31' value='" + day + "' placeholder='" + day + "'></div></div>" +
            "<button type='submit' class='btn btn-lg btn-block'>确认存入</button>" +
            "</form></div>");
    }

    private static String incomePage() {
        int month = Date_time.getMonth();
        int today = Date_time.getDay();
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>进账记录</h2>");
        sb.append("<p class='hint'>逐笔记录当月收入,消费管控将以总额计算</p>");

        // 录入表单
        sb.append("<div class='form-card'>");
        sb.append("<form method='post' action='/income'>");
        sb.append("<div class='income-form-row'>");
        sb.append("<div class='input-group'><label>日期</label><div class='input-wrap'><input name='date' type='number' min='1' max='31' value='" + today + "'></div></div>");
        sb.append("<div class='input-group'><label>金额 (元)</label><div class='input-wrap'><input name='amount' type='number' step='0.01' required placeholder='5000'></div></div>");
        sb.append("<div class='input-group'><label>备注</label><div class='input-wrap'><input name='description' placeholder='工资/兼职等'></div></div>");
        sb.append("</div>");
        sb.append("<button type='submit' class='btn'>添加进账</button>");
        sb.append("</form></div>");

        // 记录表格
        double total = 0;
        Connection conn = One.getConn();
        if (conn != null) {
            try {
                initTable(conn);
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "SELECT id, date, amount, description FROM income_records WHERE month = ? ORDER BY date, id");
                ps.setInt(1, month);
                ResultSet rs = ps.executeQuery();
                if (rs.isBeforeFirst()) {
                    sb.append("<table><tr><th>日期</th><th>金额</th><th>备注</th><th>操作</th></tr>");
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        double amt = rs.getDouble("amount");
                        total += amt;
                        sb.append("<tr><td>").append(rs.getInt("date")).append("日</td>");
                        sb.append("<td>+¥").append(String.format("%.2f", amt)).append("</td>");
                        sb.append("<td>").append(esc(rs.getString("description") != null ? rs.getString("description") : "")).append("</td>");
                        sb.append("<td><form method='post' action='/income' style='display:inline' onsubmit=\"return confirm('确认删除？')\">");
                        sb.append("<input type='hidden' name='_action' value='delete'>");
                        sb.append("<input type='hidden' name='id' value='").append(id).append("'>");
                        sb.append("<button class='btn btn-sm btn-danger'>删除</button></form></td></tr>");
                    }
                    sb.append("</table>");
                } else {
                    sb.append("<p class='hint' style='margin-top:16px'>暂无收入记录,默认可支配金额 ¥1500</p>");
                }
                rs.close(); ps.close();
            } catch (Exception e) {
                sb.append("<p class='error'>读取失败</p>");
            } finally { try { conn.close(); } catch (Exception ignored) {} }
        }

        // 合计行
        if (total > 0) {
            sb.append("<div class='stat-row' style='margin-top:16px'>");
            sb.append("<div class='stat'><span>本月总收入</span><strong>¥").append(String.format("%.2f", total)).append("</strong></div>");
            sb.append("</div>");
        }
        return page("进账设置", sb.toString());
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
            "</div></header>" + body);
    }

    private static String pageRaw(String title, String body) {
        return "<!DOCTYPE html><html lang='zh' data-theme='dark'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>" + title + "</title><style>" + css() + "</style>" +
            "<style>html{background:#05080f}body{background:transparent}" +
            ".ocean-bg{position:fixed;inset:0;width:100%;height:100%;z-index:-1;display:block;pointer-events:none}" +
            "#lohNavBar{position:fixed;top:0;left:0;height:2px;width:0;opacity:0;z-index:9999;pointer-events:none;" +
            "background:linear-gradient(90deg,#6f9bff,#a9c2ff);box-shadow:0 0 8px rgba(111,155,255,.6)}</style>" +
            "</head><body>" +
            "<canvas class='ocean-bg' id='oceanBg' aria-hidden='true'></canvas>" +
            "<main class='container'>" + body + "</main>" +
            "<script src='/static/ascii-ocean.js'></script>" +
            "<script>!function(){var c=document.getElementById('oceanBg');" +
            "if(!c||!window.AsciiOcean)return;" +
            "var eng=new window.AsciiOcean.OceanEngine(c,{fadeIn:true});" +
            "eng.setSettings({heroOpacity:.7,idleOpacity:.7,background:'#05080f',grid:.03,dotDensity:.1});" +
            "eng.start();window.addEventListener('beforeunload',function(){eng.destroy()});" +
            "}();</script>" +
            "<script src='/static/soft-nav.js'></script>" +
            "<script src='/static/ai-widget.js'></script>" +
            "</body></html>";
    }

    // ==================== 新 UI 的壳 ====================

    /** 左栏导航：key / 序号 / 中文 / 英文 / 路由。设置页 P2 才上线，先置灰。 */
    private static final String[][] NAV = {
        {"overview", "01", "总览", "OVERVIEW", "#overview", ""},
        {"entry",    "02", "记一笔", "ENTRY", "#entry", ""},
        {"income",   "03", "收入", "INCOME", "#income", ""},
        {"details",  "04", "明细", "DETAILS", "#details", ""},
        {"budget",   "05", "预算", "BUDGET", "#budget", ""},
        {"settings", "06", "设置", "SETTINGS", "#settings", ""},
        {"export",   "07", "导出", "EXPORT", "/export", ""},
    };

    /**
     * 整页骨架：顶栏 / 左栏 / 视图 / 状态栏 / AI 小球。
     * 页面方法只负责 body 片段，壳只有这一份。
     */
    private static String shell(String title, String navKey, String body) {
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>" + esc(title) + " · 鲨鱼账本</title>" +
            "<link rel='stylesheet' href='/static/app.css'>" +
            "</head><body>" +
            "<canvas id='oceanBg' aria-hidden='true'></canvas>" +
            "<div class='scrim' aria-hidden='true'></div>" +
            "<div class='frame'>" +
            shellTop(navKey) +
            "<div class='stage'>" +
            shellRail(navKey) +
            "<main class='view'>" + body + "</main>" +
            "</div>" +
            shellStatus() +
            "</div>" +
            shellAi() +
            "<script>window.CAT_LIST=" + catJs() + ";</script>" +
            "<script src='/static/ascii-ocean.js'></script>" +
            "<script src='/static/shell.js'></script>" +
            "<script src='/static/overview.js'></script>" +
            "</body></html>";
    }

    private static String shellTop(String navKey) {
        String crumb = "OVERVIEW", idx = "[ 01 ]";
        for (String[] n : NAV) {
            if (n[0].equals(navKey)) { crumb = n[3]; idx = "[ " + n[1] + " ]"; }
        }
        return "<header class='top'>" +
            "<div class='brand'>" +
            "<svg width='24' height='24' viewBox='0 0 24 24' aria-hidden='true'>" +
            "<circle cx='12' cy='12' r='9.6' fill='none' stroke='#EDEDED' stroke-width='2.1'/>" +
            "<path d='M12 7.4 L16.2 14.6 L7.8 14.6 Z' fill='#ff8a1e'/></svg>" +
            "<div><div class='zh'>鲨鱼账本</div><div class='en'>SHARK LEDGER</div></div>" +
            "</div>" +
            "<div class='sep' aria-hidden='true'></div>" +
            "<div class='crumbs'><span class='k'>" + idx + "</span><span class='v'>" + crumb +
            "</span><span class='k'>/ " + Date_time.getYear() + "-" + String.format("%02d", Date_time.getMonth()) + "</span></div>" +
            "<div class='topRight'>" +
            "<button class='aiOrb' id='aiOrb' type='button' aria-label='AI 记账' aria-expanded='false' title='AI 记账 · 点开说一句'>" +
            "<svg viewBox='0 0 36 36' aria-hidden='true'><defs><radialGradient id='orbGlow'>" +
            "<stop offset='0' stop-color='#ff8a1e' stop-opacity='.40'/>" +
            "<stop offset='58%' stop-color='#ff8a1e' stop-opacity='.10'/>" +
            "<stop offset='100%' stop-color='#ff8a1e' stop-opacity='0'/>" +
            "</radialGradient></defs>" +
            "<circle class='orbRing' cx='18' cy='18' r='13.6' fill='none' stroke='rgba(155,180,225,.34)' stroke-width='1'/>" +
            "<circle class='orbSweep' cx='18' cy='18' r='13.6' fill='none' stroke='#ff8a1e' stroke-width='1.4' stroke-dasharray='16 69.5' opacity='.85'/>" +
            "<circle class='orbHalo' cx='18' cy='18' r='12' fill='url(#orbGlow)'/>" +
            "<circle class='orbCore' cx='18' cy='18' r='4' fill='#ff8a1e'/>" +
            "</svg></button>" +
            "<div class='sep' aria-hidden='true'></div>" +
            "<span class='clock' id='clock'>--</span>" +
            "<div class='winbtns' aria-hidden='true'><i></i><i></i><i></i></div>" +
            "</div></header>";
    }

    private static String shellRail(String navKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("<aside class='rail'><nav class='nav'><div class='group'>LEDGER</div>");
        for (String[] n : NAV) {
            if ("settings".equals(n[0])) sb.append("<div class='group'>SYSTEM</div>");
            boolean on = n[0].equals(navKey);
            sb.append("<a href='").append(n[4]).append("'")
              .append(on ? " class='on'" : "off".equals(n[5]) ? " class='off' title='P2 上线'" : "")
              .append("><span class='idx'>").append(n[1]).append("</span>")
              .append("<span class='txt'>").append(n[2]).append("</span>")
              .append("<span class='en'>").append(n[3]).append("</span></a>");
        }
        sb.append("</nav><div class='railBot'>")
          .append("<div class='stat'><span>DATABASE</span><span class='dot'>已连接</span></div>")
          .append("<div class='stat'><span>AI ENGINE</span><span class='dot' id='aiEngine'>就绪</span></div>")
          .append("<div class='stat'><span>BUILD</span><span>v3.0.0</span></div>")
          .append("</div></aside>");
        return sb.toString();
    }

    private static String shellStatus() {
        return "<footer class='statusbar'>" +
            "<span class='live'><i></i>账本已同步</span><span>LEDGER SYNCHRONISED</span>" +
            "<div class='mid'><span><kbd>F2</kbd> 记一笔</span><span><kbd>K</kbd> 搜索</span><span><kbd>ESC</kbd> 返回</span></div>" +
            "<div class='barcode' id='barcode' aria-hidden='true'></div>" +
            "</footer>";
    }

    /** AI 小球带出的面板与气泡：位置、文案都在壳里，行为在 static/shell.js。 */
    private static String shellAi() {
        return "<section class='panel aiPanel' id='aiPanel' aria-hidden='true'>" +
            "<header class='ph'><span class='label'>AI / 记一笔</span>" +
            "<span class='act' role='button' tabindex='0' id='aiReclass' title='让 AI 按批重新分类本月'>重分本月</span>" +
            "<span class='st'><i></i><span id='aiModel'>AI · 就绪</span></span></header>" +
            "<div class='aiBody'>" +
            "<textarea class='aiInput' id='aiInput' rows='3' placeholder='说一句就行，例如：中午牛肉面 28，晚上打车 35'></textarea>" +
            "<div class='aiRow'><span class='aiHint'>ENTER 解析 · SHIFT+ENTER 换行</span>" +
            "<div class='aiRowBtns'><button class='btn' id='aiAdvise' type='button'>建议</button>" +
            "<button class='btn solid' id='aiParse' type='button'>解析</button></div></div>" +
            "<div class='aiStat' id='aiStat'></div>" +
            "<div class='aiPrev' id='aiPrev' hidden></div>" +
            "<div class='aiAdvice' id='aiAdvice' hidden></div>" +
            "</div></section>" +
            "<div class='panel aiBubble' id='aiBubble' role='status'>" +
            "<div class='bk'><span>AI</span><span class='t'>CLICK TO REPLY</span></div>" +
            "<p class='txt'></p></div>";
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
            // === CSS变量：深海暗色（单一主题，配合 ASCII ocean 背景） ===
            ":root{color-scheme:dark;" +
            "--space-xs:4px;--space-sm:8px;--space-md:16px;--space-lg:24px;--space-xl:32px;--space-2xl:48px;--space-3xl:64px;" +
            "--bg:#05080f;--surface:rgba(13,17,29,.88);--surface2:rgba(20,25,40,.86);--elevated:rgba(22,27,44,.92);" +
            "--text:#e6ebf7;--text2:#93a0bb;--border:rgba(120,150,210,.20);" +
            "--accent:#6f9bff;--accent-h:#8fb3ff;--accent-light:rgba(111,155,255,.14);" +
            "--danger:#ff6b6b;--danger-h:#e05555;--danger-light:rgba(255,107,107,.12);" +
            "--success:#4ade80;--success-light:rgba(74,222,128,.12);" +
            "--shadow-sm:0 1px 2px rgba(0,0,0,.35);" +
            "--shadow:0 2px 10px rgba(0,0,0,.35);" +
            "--shadow-lg:0 10px 30px rgba(0,0,0,.45),0 2px 6px rgba(0,0,0,.25);" +
            "--input-bg:rgba(9,12,21,.66);--input-bd:rgba(120,150,210,.26);--toggle-bg:#4f6ef7;" +
"--chevron:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%2393a0bb' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E\");" +
            "--hover:rgba(111,155,255,.08);--nav-hover:rgba(111,155,255,.12);" +
            "--glow-top:rgba(111,155,255,.10);--glow-bottom:rgba(51,200,204,.06);" +
            "--title-g1:#a5b4fc;--title-g2:#c4b5fd;--title-g3:#fcd34d;" +
            "--sub-g1:#93c5fd;--sub-g2:#c4b5fd;" +
            "--radius-sm:6px;--radius:10px;--radius-lg:14px;" +
            "--ease-out:cubic-bezier(.16,1,.3,1);--ease-in-out:cubic-bezier(.4,0,.2,1)}" +

            // === 基础 ===
            "*{margin:0;padding:0;box-sizing:border-box}" +

            "body{font-family:'Microsoft YaHei','PingFang SC','Noto Sans SC',sans-serif;" +
            "background:transparent;color:var(--text);min-height:100vh;" +
            "font-weight:350;line-height:1.6;position:relative}" +
            "body::before{content:'';position:fixed;inset:0;pointer-events:none;z-index:0;" +
            "background:linear-gradient(180deg,rgba(5,8,15,.12) 0%,rgba(5,8,15,.34) 45%,rgba(5,8,15,.55) 100%)}" +
            "body::after{content:'';position:fixed;inset:0;pointer-events:none;z-index:0;" +
            "opacity:.03;background-image:url(\"data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\");background-size:200px 200px}" +
            "main.container{position:relative;z-index:1}" +
            ".container{max-width:860px;margin:0 auto;padding:var(--space-lg)}" +
            ".hero-cards,.card,.stat,.form-card,.edit-box,.login-box,.prob-card,.query-section," +
            "table,.input-wrap input,.input-wrap select,.btn,.filter-pill,.radio-pill .pill-text" +
            "{transition:background .4s var(--ease-out),color .4s var(--ease-out)," +
            "border-color .4s var(--ease-out),box-shadow .4s var(--ease-out)}" +
            // 玻璃质感：让背景的海面透出来一点
            ".hero-card,.card,.stat,.form-card,.edit-box,.login-box,.prob-card,table,.filter-pill," +
            ".query-section{backdrop-filter:blur(14px) saturate(120%);-webkit-backdrop-filter:blur(14px) saturate(120%)}" +

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
            ".hero-card-income::before{background:linear-gradient(90deg,#f59e0b,#fbbf24)}" +
            "[data-theme='dark'] .hero-card-income::before{background:linear-gradient(90deg,#fbbf24,#fcd34d)}" +
            ".hero-card:hover{transform:translateY(-4px) scale(1.015);" +
            "box-shadow:0 12px 40px rgba(0,0,0,.12);border-color:transparent}" +
            ".hero-card:hover::before{height:5px}" +
            ".hero-card:active{transform:translateY(-2px) scale(1.005)}" +
            ".hero-icon{flex-shrink:0;width:64px;height:64px;display:flex;align-items:center;" +
            "justify-content:center;border-radius:16px;transition:transform .35s var(--ease-in-out)}" +
            ".hero-card-save .hero-icon{background:rgba(79,110,247,.1);color:var(--accent)}" +
            ".hero-card-query .hero-icon{background:rgba(245,158,11,.1);color:#f59e0b}" +
".hero-card-budget .hero-icon{background:rgba(16,185,129,.1);color:#10b981}" +
".hero-card-income .hero-icon{background:rgba(245,158,11,.1);color:#f59e0b}" +
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
            "select{appearance:none;-webkit-appearance:none;cursor:pointer;" +
            "padding:13px 40px 13px 16px;font-size:15px;font-weight:450;" +
            "background-image:var(--chevron);background-repeat:no-repeat;" +
            "background-position:right 15px center;background-size:13px}" +
            "select:hover{border-color:var(--accent)}" +
            "select option{background:var(--input-bg);color:var(--text)}" +
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
            ".income-form-row{display:flex;gap:var(--space-md);flex-wrap:wrap}" +
            ".income-form-row .input-group{flex:1;min-width:120px}" +
            ".input-wrap{position:relative;display:flex;align-items:center}" +
            ".input-wrap .input-icon{position:absolute;left:14px;color:var(--text2);" +
            "pointer-events:none;transition:color .2s var(--ease-out)}" +
            ".input-wrap input,.input-wrap select{font-size:15px;width:100%;border:1.5px solid var(--input-bd);" +
            "border-radius:var(--radius);background:var(--input-bg);color:var(--text);" +
            "font-family:inherit;outline:none;transition:all .25s var(--ease-out);margin-bottom:0}" +
            ".input-wrap input{padding:13px 14px 13px 42px}" +
            ".input-wrap select{padding:13px 40px 13px 16px;appearance:none;-webkit-appearance:none;" +
            "cursor:pointer;background-image:var(--chevron);background-repeat:no-repeat;" +
            "background-position:right 15px center;background-size:13px}" +
            ".input-wrap input:focus,.input-wrap select:focus{border-color:var(--accent);box-shadow:0 0 0 4px var(--accent-light)}" +
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

    /** JSON 字符串转义 */
    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
