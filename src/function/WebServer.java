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

        // 启动时自愈一次表结构（缺 cat 列就补，缺 id 主键就补），失败不影响服务
        try (Connection c = One.getConn()) {
            ensureCatColumn(c);
            ensureIdColumn(c);
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
                    if (splashPending) serveSplash(ex);
                    else redirect(ex, "/main");
                }
                case "/splash-done"  -> { splashPending = false; redirect(ex, "/main"); }
                case "/main"         -> html(ex, mainPage(ex.getRequestURI().getQuery()));
                case "/save"         -> { if ("POST".equals(method)) handleSavePost(ex, parseForm(ex)); else redirect(ex, "/main#entry"); }
                case "/del"          -> { if ("POST".equals(method)) handleDel(ex, parseForm(ex)); else redirect(ex, "/main#details"); }
                case "/income"       -> { if ("POST".equals(method)) handleIncomePost(ex, parseForm(ex)); else redirect(ex, "/main#income"); }
                case "/query"        -> { if ("POST".equals(method)) handleDetailPost(ex); else redirect(ex, "/main#details"); }
                case "/budget"       -> { if ("POST".equals(method)) handleBudgetPost(ex, parseForm(ex)); else redirect(ex, "/main#budget"); }
                case "/settings"     -> redirect(ex, "/main#settings");
                case "/api/month"    -> {
                    Map<String, String> qp = parseQuery(ex.getRequestURI().getQuery());
                    int mm = Date_time.getMonth();
                    try { if (qp.get("m") != null) mm = Integer.parseInt(qp.get("m")); } catch (Exception ignore) {}
                    json(ex, Overview.load(mm).monthJson());
                }
                // 静默刷新用：整页同款数据（含 charts 需要的全年每月）
                case "/api/overview" -> json(ex, Overview.load().json());
                case "/export"       -> handleExportData(ex);
                case "/ai/status"    -> json(ex, AiAssistant.statusJson());
                case "/ai/parse"     -> { if ("POST".equals(method)) handleAiParse(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/save"      -> { if ("POST".equals(method)) handleAiSave(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/advice"    -> { if ("POST".equals(method)) handleAiAdvice(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/classify"  -> { if ("POST".equals(method)) handleAiClassify(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                case "/ai/model"     -> { if ("POST".equals(method)) handleAiModel(ex); else json(ex, "{\"ok\":false,\"error\":\"仅支持 POST\"}"); }
                default              -> {
                    if (path.startsWith("/static/")) serveStatic(ex, path);
                    else redirect(ex, "/main");
                }
            }
        } catch (Exception e) {
            html(ex, errorPage(e.getMessage()));
        }
    }

    /** 保存当月预算（每月可以不一样） */
    private static void handleBudgetPost(HttpExchange ex, Map<String, String> f) throws IOException {
        Connection conn = null;
        try {
            double amount = Double.parseDouble(f.get("amount"));
            if (amount < 0 || amount > 10000000) throw new NumberFormatException("预算超出范围");
            conn = One.getConn();
            if (conn == null) { backTo(ex, "budget", "数据库连接失败"); return; }
            initTable(conn);
            PreparedStatement ps = One.getPreparedStmt(conn,
                "INSERT INTO budgets (month, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)");
            ps.setInt(1, Date_time.getMonth());
            ps.setDouble(2, amount);
            ps.executeUpdate();
            ps.close();
            backTo(ex, "budget", "");
        } catch (NumberFormatException e) {
            backTo(ex, "budget", "预算要填一个 0 ~ 10000000 的数字");
        } catch (Exception e) {
            backTo(ex, "budget", "预算保存失败：" + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
    }

    /** 写完数据回到单页的对应锚点；msg 为空表示成功，否则是错误文案 */
    private static void backTo(HttpExchange ex, String section, String msg) throws IOException {
        String q = (msg == null || msg.isEmpty())
            ? "ok=1"
            : "err=" + java.net.URLEncoder.encode(msg, StandardCharsets.UTF_8);
        redirect(ex, "/main?" + q + "#" + section);
    }

    /** 成功回跳 + 具体提示（顶部闪一条，非错误色） */
    private static void backToOk(HttpExchange ex, String section, String text) throws IOException {
        redirect(ex, "/main?ok=" + java.net.URLEncoder.encode(text, StandardCharsets.UTF_8) + "#" + section);
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

        // 带 id = 修改已有记录（月份在表单里锁死，不跨月搬家）
        int editId = 0;
        String idStr = f.get("id");
        if (idStr != null && !idStr.trim().isEmpty()) {
            try { editId = Integer.parseInt(idStr.trim()); } catch (NumberFormatException ignored) { editId = 0; }
        }

        String table = String.format("table_%02d", month);
        Connection conn = One.getConn();
        if (conn == null) { backTo(ex, "entry", "数据库连接失败"); return; }
        try {
            ensureCatColumn(conn);
            ensureIdColumn(conn);
            if (editId > 0) {
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "UPDATE " + table + " SET date = ?, goods = ?, price = ?, num_1 = ?, cat = ? WHERE id = ?");
                ps.setInt(1, day);
                ps.setString(2, goods.trim());
                ps.setDouble(3, price);
                ps.setInt(4, num1);
                ps.setString(5, cat);
                ps.setInt(6, editId);
                int n = ps.executeUpdate();
                ps.close();
                conn.close();
                if (n > 0) backToOk(ex, "details", "已修改 1 笔");
                else backTo(ex, "details", "没找到这条记录，可能已经被删了");
                return;
            }
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
            backToOk(ex, "entry", "已记一笔");
        } catch (Exception e) {
            conn.close();
            backTo(ex, "entry", "存入失败：" + e.getMessage());
        }
    }

    /** 删除明细里的一条（按 id 精确定位，不会误删同天同名的其他记录） */
    private static void handleDel(HttpExchange ex, Map<String, String> f) throws IOException {
        int id, month;
        try {
            id = Integer.parseInt(f.get("id"));
            month = Integer.parseInt(f.get("month"));
        } catch (Exception e) {
            backTo(ex, "details", "删除参数不对");
            return;
        }
        if (month < 1 || month > 12) { backTo(ex, "details", "月份不对"); return; }

        Connection conn = One.getConn();
        if (conn == null) { backTo(ex, "details", "数据库连接失败"); return; }
        int n = 0;
        try {
            ensureIdColumn(conn);
            PreparedStatement ps = One.getPreparedStmt(conn,
                "DELETE FROM " + String.format("table_%02d", month) + " WHERE id = ?");
            ps.setInt(1, id);
            n = ps.executeUpdate();
            ps.close();
        } catch (Exception ignored) {
            n = 0;
        } finally {
            try { conn.close(); } catch (Exception ignored) { }
        }
        if (n > 0) backToOk(ex, "details", "已删除 1 笔");
        else backTo(ex, "details", "没找到这条记录，可能已经删过");
    }

    private static void handleIncomePost(HttpExchange ex, Map<String, String> f) throws Exception {
        Archiver.ensureRollover(); // 写入前检查跨年
        int month = Date_time.getMonth();
        Connection conn = One.getConn();
        if (conn == null) { backTo(ex, "income", "数据库连接失败"); return; }

        String action = f.get("_action");
        int id = 0;
        if (f.get("id") != null && !f.get("id").trim().isEmpty()) {
            try { id = Integer.parseInt(f.get("id").trim()); } catch (NumberFormatException ignored) { id = 0; }
        }
        try {
            initTable(conn);
            // 删除
            if ("delete".equals(action)) {
                if (id <= 0) { conn.close(); backTo(ex, "income", "记录编号不对"); return; }
                PreparedStatement ps = One.getPreparedStmt(conn, "DELETE FROM income_records WHERE id = ?");
                ps.setInt(1, id); ps.executeUpdate(); ps.close();
                conn.close();
                backToOk(ex, "income", "已删除 1 笔收入");
                return;
            }
            // 修改
            else if ("update".equals(action)) {
                if (id <= 0) { conn.close(); backTo(ex, "income", "记录编号不对"); return; }
                int day;
                try { day = Integer.parseInt(f.get("date")); } catch (Exception e) { day = Date_time.getDay(); }
                double amount;
                try { amount = Double.parseDouble(f.get("amount")); } catch (Exception e) { conn.close(); backTo(ex, "income", "金额格式错误"); return; }
                String desc = f.get("description");
                if (desc == null) desc = "";
                PreparedStatement ps = One.getPreparedStmt(conn,
                    "UPDATE income_records SET date = ?, amount = ?, description = ? WHERE id = ? AND month = ?");
                ps.setInt(1, day); ps.setDouble(2, amount); ps.setString(3, desc.trim());
                ps.setInt(4, id); ps.setInt(5, month);
                int n = ps.executeUpdate(); ps.close();
                conn.close();
                if (n > 0) backToOk(ex, "income", "已修改 1 笔收入");
                else backTo(ex, "income", "没找到这笔收入，可能已经删了");
                return;
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
        backToOk(ex, "income", "已记一笔收入");
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

    private static void handleAiModel(HttpExchange ex) throws IOException {
        try {
            String raw = readBody(ex);
            Map<String, Object> body = Json.map(Json.parse(raw == null || raw.isEmpty() ? "{}" : raw));
            String want = Json.str(body.get("model"));
            AiAssistant.setModel(want);
            json(ex, "{\"ok\":true,\"model\":\"" + Json.esc(AiAssistant.model()) + "\"}");
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
            st.execute("CREATE TABLE IF NOT EXISTS budgets (month INT PRIMARY KEY, amount DECIMAL(10,2) NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS income_records (id INT AUTO_INCREMENT PRIMARY KEY, month INT NOT NULL, date INT NOT NULL, amount DECIMAL(10,2) NOT NULL, description VARCHAR(200) DEFAULT '')");
            st.execute("CREATE TABLE IF NOT EXISTS app_settings (k VARCHAR(48) PRIMARY KEY, v VARCHAR(255) NOT NULL)");
            st.close();
        } catch (Exception e) { /* 表已存在则忽略 */ }
        ensureCatColumn(conn);
        ensureIdColumn(conn);
    }

    /**
     * 月度表补一列自增主键 id。
     * 老表没有主键，删除只能按 (日期, 商品名) 猜，同一天买两次同样的东西会一次删掉两条。
     */
    static void ensureIdColumn(Connection conn) {
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
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='id'")) {
                    ps.setString(1, t);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) continue;
                    }
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE " + t + " ADD COLUMN id INT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST");
                    System.out.println("已补主键 " + t + ".id");
                }
            } catch (Exception ignore) {
                /* 加不上（比如已经有别的主键）就跳过，不影响其他表 */
            }
        }
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

    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        File file = new File(path.substring(1));
        if (!file.exists()) { ex.sendResponseHeaders(404, -1); return; }
        byte[] bytes = Files.readAllBytes(file.toPath());
        String lower = path.toLowerCase();
        String ct = lower.endsWith(".js") ? "application/javascript; charset=utf-8" :
            lower.endsWith(".css") ? "text/css; charset=utf-8" :
            lower.endsWith(".html") ? "text/html; charset=utf-8" :
            lower.endsWith(".json") ? "application/json; charset=utf-8" :
            lower.endsWith(".png") ? "image/png" :
            lower.endsWith(".ico") ? "image/x-icon" :
            lower.endsWith(".svg") ? "image/svg+xml" :
            "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ==================== Excel 导出 ====================

    private static void handleExportData(HttpExchange ex) throws Exception {
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        boolean all = "1".equals(q.get("all"));
        int month = Date_time.getMonth();
        try { if (q.get("month") != null) month = Integer.parseInt(q.get("month")); } catch (Exception ignore) {}
        if (month < 1 || month > 12) month = Date_time.getMonth();

        Connection conn = One.getConn();
        if (conn == null) { html(ex, errorPage("数据库连接失败")); return; }
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            if (all) {
                for (int m = 1; m <= 12; m++) addSheet(wb, conn, m, false);
            } else {
                addSheet(wb, conn, month, true);
            }
            if (wb.getNumberOfSheets() == 0) {
                org.apache.poi.ss.usermodel.Sheet s0 = wb.createSheet("空");
                s0.createRow(0).createCell(0).setCellValue("这个月还没有记录");
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            byte[] bytes = bos.toByteArray();

            String name = "消费记录-" + (all ? Date_time.getYear() + "年" : Date_time.getYear() + "-" + String.format("%02d", month)) + ".xlsx";
            ex.getResponseHeaders().set("Content-Type",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"bookkeeping.xlsx\"; filename*=UTF-8''" +
                java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } finally {
            try { conn.close(); } catch (Exception ignored) {}
        }
    }

    /** 把某个月的记录写成一个 sheet（all=true 时即使为空也建，当月导出为空则跳过） */
    private static void addSheet(org.apache.poi.xssf.usermodel.XSSFWorkbook wb, Connection conn, int month, boolean forceEmpty) throws Exception {
        String table = String.format("table_%02d", month);
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT date, cat, num_1, goods, price FROM " + table + " ORDER BY date")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{ rs.getInt(1), rs.getString(2) == null ? "其他" : rs.getString(2),
                            rs.getInt(3) == 1 ? "日常" : "特殊", rs.getString(4), rs.getDouble(5) });
                }
            }
        } catch (Exception e) { return; }        // 该月表不存在
        if (rows.isEmpty() && !forceEmpty) return;

        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(String.format("%02d 月", month));
        String[] headers = { "日期（日）", "分类", "性质", "商品", "金额（元）" };
        org.apache.poi.ss.usermodel.CellStyle headStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font bold = wb.createFont();
        bold.setBold(true);
        headStyle.setFont(bold);

        org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell c = h.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headStyle);
        }
        double sum = 0;
        for (int r = 0; r < rows.size(); r++) {
            Object[] v = rows.get(r);
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            row.createCell(0).setCellValue((Integer) v[0]);
            row.createCell(1).setCellValue((String) v[1]);
            row.createCell(2).setCellValue((String) v[2]);
            row.createCell(3).setCellValue((String) v[3]);
            row.createCell(4).setCellValue((Double) v[4]);
            sum += (Double) v[4];
        }
        org.apache.poi.ss.usermodel.Row total = sheet.createRow(rows.size() + 1);
        total.createCell(3).setCellValue("合计");
        total.createCell(4).setCellValue(Math.round(sum * 100) / 100.0);
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
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

    private static void serveSplash(HttpExchange ex) throws IOException {
        java.io.File f = new java.io.File("static/splash.html");
        if (f.exists()) {
            byte[] bytes = Files.readAllBytes(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            return;
        }
        html(ex, splashPage());
    }

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

    /** 取一条待修改的记录：{id, month, day, goods, price, num1, cat}；找不到返回 null */
    private static String[] loadRecord(String idStr, String monthStr) {
        int id, month;
        try {
            id = Integer.parseInt(idStr.trim());
            month = Integer.parseInt(monthStr == null ? "" : monthStr.trim());
        } catch (Exception e) {
            return null;
        }
        if (month < 1 || month > 12) return null;

        Connection conn = One.getConn();
        if (conn == null) return null;
        try {
            ensureIdColumn(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT date, goods, price, num_1, cat FROM " + String.format("table_%02d", month) + " WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new String[]{ String.valueOf(id), String.valueOf(month),
                            String.valueOf(rs.getInt(1)), rs.getString(2),
                            String.format("%.2f", rs.getDouble(3)), String.valueOf(rs.getInt(4)),
                            rs.getString(5) == null ? "其他" : rs.getString(5) };
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            try { conn.close(); } catch (Exception ignored) { }
        }
    }

    /** 取一条待修改的收入：{id, date, amount, description}；找不到返回 null */
    private static String[] loadIncome(String idStr) {
        int id;
        try {
            id = Integer.parseInt(idStr.trim());
        } catch (Exception e) {
            return null;
        }

        Connection conn = One.getConn();
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT date, amount, description FROM income_records WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{ String.valueOf(id), String.valueOf(rs.getInt(1)),
                        String.format("%.2f", rs.getDouble(2)),
                        rs.getString(3) == null ? "" : rs.getString(3) };
            }
        } catch (Exception e) {
            return null;
        } finally {
            try { conn.close(); } catch (Exception ignored) { }
        }
    }

    private static String mainPage(String rawQuery) {
        Overview ov = Overview.load();
        Map<String, String> q = rawQuery == null || rawQuery.isEmpty()
            ? new HashMap<>() : parseQuery(rawQuery);
        String flash = "";
        if (q.get("err") != null) {
            flash = "<script>window.__flash=[" + jsStr(q.get("err")) + ",true];</script>";
        } else if (q.get("ok") != null) {
            String ok = q.get("ok");
            String text = "1".equals(ok) ? "✓ 已保存" : ok;
            flash = "<script>window.__flash=[" + jsStr(text) + ",false];</script>";
        }
        String[] editRec = q.get("edit") == null ? null : loadRecord(q.get("edit"), q.get("m"));
        String[] incEdit = q.get("iedit") == null ? null : loadIncome(q.get("iedit"));
        String ymd = Date_time.getYear() + "-" + String.format("%02d", ov.month);
        String table = String.format("table_%02d", ov.month);
        double balance = ov.income - ov.expense;
        double used = ov.budget <= 0 ? 0 : ov.expense / ov.budget;
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
          .append("<p class='num'><span class='cur'>¥</span>").append(money(ov.budget)).append("</p>")
          .append("<div class='bTrack'><i style='width:").append(String.format("%.1f", Math.min(used, 1) * 100))
          .append("%'></i></div>")
          .append("<p class='delta'>已用 ").append(String.format("%.1f", used * 100)).append("% · 剩余 ¥")
          .append(money(Math.max(0, ov.budget - ov.expense))).append("</p></section>");
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
          .append("<th style='width:110px'>分类 / TAG</th><th style='width:76px'>性质 / TYPE</th><th>备注 / NOTE</th>")
          .append("<th style='width:140px;text-align:right'>金额 / AMOUNT</th></tr></thead><tbody id='recentBody'>");
        int no = ov.count;
        for (Overview.Row r : ov.recent) {
            sb.append("<tr><td class='id'>").append(String.format("%06d", Math.max(1, no--))).append("</td>")
              .append("<td class='dt'>").append(ymd).append("-").append(String.format("%02d", r.date)).append("</td>")
              .append("<td class='tag' data-cat='").append(esc(r.cat)).append("'><span class='tick'></span>")
              .append(esc(r.cat)).append("</td>")
              .append(typeCell(r.num1))
              .append("<td class='note'>").append(esc(r.goods)).append("</td>")
              .append("<td class='amt out'>-¥").append(String.format("%,.2f", r.price)).append("</td></tr>");
        }
        if (ov.recent.isEmpty()) {
            sb.append("<tr><td colspan='6' style='text-align:center;color:var(--faint);padding:22px 0'>本月还没有记录</td></tr>");
        }
        sb.append("</tbody><tfoot><tr><td colspan='5'>共 ").append(ov.count).append(" 条记录 · 表 ").append(table).append("</td>")
          .append("<td class='sum'>合计 <b>¥").append(String.format("%,.2f", ov.expense)).append("</b></td></tr></tfoot></table></section>");

        sb.append("</section>");
        sb.append(Sections.entry(editRec));
        sb.append(Sections.income(ov, incEdit));
        sb.append(Sections.details(ov));
        sb.append(Sections.budget(ov));
        sb.append(Sections.settings(ov));
        sb.append(Sections.export(ov));
        sb.append("<script>window.OV=").append(ov.json()).append(";</script>");
        sb.append(flash);
        return shell("总览", "overview", sb.toString());
    }

    private static String money(double v) {
        return String.format("%,.0f", v);
    }

    /** 日常 / 特殊 的小标签，和环图内环同一套色 */
    static String typeCell(int num1) {
        return num1 == 1
            ? "<td class='type'><span class='t1'>日常</span></td>"
            : "<td class='type'><span class='t2'>特殊</span></td>";
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
    private static String probEval(int prob) {
        if (prob < 30) return "赤字率较低,继续保持当前消费习惯";
        if (prob < 60) return "赤字率上升,需留意日常开支";
        return "赤字率较高,请减少非必要消费";
    }

    private static final String[][] NAV = {
        {"overview", "01", "总览", "OVERVIEW", "#overview", ""},
        {"entry",    "02", "记一笔", "ENTRY", "#entry", ""},
        {"income",   "03", "收入", "INCOME", "#income", ""},
        {"details",  "04", "明细", "DETAILS", "#details", ""},
        {"budget",   "05", "预算", "BUDGET", "#budget", ""},
        {"settings", "06", "设置", "SETTINGS", "#settings", ""},
        {"export",   "07", "导出", "EXPORT", "#export", ""},
    };

    /**
     * 整页骨架：顶栏 / 左栏 / 视图 / 状态栏 / AI 小球。
     * 页面方法只负责 body 片段，壳只有这一份。
     */
    private static String shell(String title, String navKey, String body) {
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>" + esc(title) + " · 消费记录系统</title>" +
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
            "<div><div class='zh'>消费记录系统</div><div class='en'>BOOKKEEPING SYSTEM</div></div>" +
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
          .append("<div class='stat'><span>BUILD</span><span>v3.0.3</span></div>")
          .append("</div></aside>");
        return sb.toString();
    }

    private static String shellStatus() {
        return "<footer class='statusbar'>" +
            "<span class='live'><i></i>账本已同步</span><span>LEDGER SYNCHRONISED</span>" +
            "<div class='mid'><span><kbd>F2</kbd> 记一笔</span><span><kbd>K</kbd> 唤 AI</span><span><kbd>ESC</kbd> 返回</span></div>" +
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

    private static String errorPage(String msg) {
        return shell("出错了", "overview",
            "<section class='sec'><div class='pagehead'><div><h1>出错了</h1></div></div>" +
            "<div class='panel pad'><p class='hint'>" + esc(msg) + "</p>" +
            "<p style='margin-top:14px'><a class='btn' href='/main'>返回总览</a></p></div></section>");
    }

    // ==================== CSS 主题系统 ====================

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
