package function;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地 Ollama 记账助手。
 * 只依赖 JDK 自带 HttpClient，不引入第三方库；Ollama 不在时所有方法抛异常，由上层降级。
 */
public class AiAssistant {

    private static final String BASE  = env("LOH_AI_URL", "http://127.0.0.1:11434");
    private static final String MODEL = env("LOH_AI_MODEL", "qwen2.5:7b");
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 一条待入库的消费 */
    public static class Item {
        public String goods;
        public double price;
        public int type;   // 1=日常 2=特殊
        public String cat; // 细类，见 Cat.ALL
        public int day;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    // ==================== 状态 ====================

    /** Ollama 是否在跑、默认模型是否存在 */
    public static String statusJson() {
        List<String> models = tags();
        boolean up = models != null;
        boolean has = up && hasModel(models, MODEL);
        StringBuilder b = new StringBuilder();
        b.append("{\"ok\":").append(up && has)
         .append(",\"ollama\":").append(up)
         .append(",\"model\":\"").append(Json.esc(MODEL)).append("\"")
         .append(",\"models\":[");
        if (models != null) {
            for (int i = 0; i < models.size(); i++) {
                if (i > 0) b.append(',');
                b.append('"').append(Json.esc(models.get(i))).append('"');
            }
        }
        b.append("]}");
        return b.toString();
    }

    private static List<String> tags() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) return null;
            List<String> names = new ArrayList<>();
            for (Object o : Json.list(Json.map(Json.parse(res.body())).get("models"))) {
                String name = Json.str(Json.map(o).get("name"));
                if (name != null) names.add(name);
            }
            return names;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasModel(List<String> names, String want) {
        String base = want.contains(":") ? want.substring(0, want.indexOf(':')) : want;
        for (String n : names) {
            if (n.equals(want)) return true;
            if (n.equals(base + ":latest")) return true;
            if (n.startsWith(base + ":")) return true;
        }
        return false;
    }

    // ==================== 解析 ====================

    public static List<Item> parse(String text) throws Exception {
        LocalDate today = LocalDate.now();
        int day = today.getDayOfMonth();
        int month = today.getMonthValue();
        int days = today.lengthOfMonth();
        int yesterday = Math.max(1, day - 1);

        String sys =
            "你是一个记账解析器。把用户的一句话拆成若干条消费记录。\n" +
            "今天是 " + today.getYear() + " 年 " + month + " 月 " + day + " 日，本月共 " + days + " 天。\n" +
            "只输出 JSON，不要解释，格式：\n" +
            "{\"items\":[{\"goods\":\"商品名，20字以内\",\"price\":数字,\"type\":1,\"cat\":\"类别\",\"day\":数字}]}\n" +
            "规则：\n" +
            "- 先判断是否餐饮：早餐/午餐/晚餐/夜宵/饮料（可乐、奶茶、咖啡、果汁、水等）/零食/水果/外卖 → type=1（日常消费）\n" +
            "- 不属于餐饮的一律 type=2（特殊消费），例如交通、日用品、话费、房租、数码、旅游、娱乐、医美、大件等\n" +
            "- 示例：午餐25→type=1，可乐3→type=1，奶茶12→type=1，打车30→type=2，买键盘399→type=2\n" +
            "- cat 只能从这些里选一个：" + Cat.LIST + "；拿不准就选\"其他\"，不要自创类别\n" +
            "- day 是 1-" + days + " 的日期；说\"今天\"填 " + day + "，说\"昨天\"填 " + yesterday + "，没提日期就填 " + day + "\n" +
            "- 金额必须为正数；一句话里有多笔就拆成多条\n" +
            "- goods 用简短中文概括，例如\"牛肉面\"\"打车\"\"奶茶\"\n" +
            "- 如果完全不是消费描述，返回 {\"items\":[]}";

        String content = chat(sys, text);
        Map<String, Object> root = Json.map(Json.parse(extractJson(content)));
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(root.get("items"))) {
            Map<String, Object> m = Json.map(o);
            String goods = Json.str(m.get("goods"));
            if (goods == null) continue;
            goods = goods.trim();
            if (goods.isEmpty()) continue;
            if (goods.length() > 40) goods = goods.substring(0, 40);

            double price = Json.num(m.get("price"), 0);
            if (price <= 0) continue;

            int type = Json.i(m.get("type"), 2);
            if (type != 2) type = 1;

            String cat = Cat.clean(Json.str(m.get("cat")), goods);
            // 性质与项目口径一致：餐饮归日常，其余一律特殊（模型这条经常给错，代码兜住）
            if (!"餐饮".equals(cat)) type = 2;

            int d = Json.i(m.get("day"), day);
            if (d < 1) d = 1;
            if (d > days) d = days;

            Item it = new Item();
            it.goods = goods;
            it.price = Math.round(price * 100) / 100.0;
            it.type = type;
            it.cat = cat;
            it.day = d;
            items.add(it);
        }
        if (items.isEmpty()) throw new IllegalArgumentException("没听懂要记什么，换个说法试试");
        return items;
    }

    public static String itemsJson(List<Item> items) {
        StringBuilder b = new StringBuilder("{\"ok\":true,\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) b.append(',');
            b.append("{\"goods\":\"").append(Json.esc(it.goods)).append("\"")
             .append(",\"price\":").append(it.price)
             .append(",\"type\":").append(it.type)
             .append(",\"cat\":\"").append(Json.esc(it.cat == null ? Cat.guess(it.goods) : it.cat)).append("\"")
             .append(",\"day\":").append(it.day).append('}');
        }
        b.append("]}");
        return b.toString();
    }

    // ==================== 入库 ====================

    /** 把解析结果写入当月表，返回写入条数 */
    public static int save(List<Item> items) throws Exception {
        Archiver.ensureRollover();
        int month = Date_time.getMonth();
        String table = String.format("table_%02d", month);
        Connection conn = One.getConn();
        if (conn == null) throw new IOException("数据库连接失败");
        int n = 0;
        try {
            WebServer.ensureCatColumn(conn);
            PreparedStatement ps = One.getPreparedStmt(conn,
                    "INSERT INTO " + table + " (date, goods, price, num_1, cat) VALUES (?, ?, ?, ?, ?)");
            for (Item it : items) {
                ps.setInt(1, it.day);
                ps.setString(2, it.goods);
                ps.setDouble(3, it.price);
                ps.setInt(4, it.type);
                ps.setString(5, it.cat == null || it.cat.isEmpty() ? Cat.clean(null, it.goods) : it.cat);
                ps.executeUpdate();
                n++;
            }
            ps.close();
        } finally {
            try { conn.close(); } catch (Exception ignored) {}
        }
        return n;
    }


    // ==================== 批量重新分类 ====================

    /**
     * 把某月记录按 40 条一批喂给模型重新分类，写回 cat。
     * 只覆盖模型给出且在白名单里的值；离线/解析失败时抛异常，由上层提示。
     * 返回实际改动的行数。
     */
    public static int reclassify(int month) throws Exception {
        if (month < 1 || month > 12) month = Date_time.getMonth();
        String table = String.format("table_%02d", month);

        Connection conn = One.getConn();
        if (conn == null) throw new IOException("数据库连接失败");
        int changed = 0;
        try {
            WebServer.ensureCatColumn(conn);

            List<Object[]> rows = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT date, goods, price, num_1, cat FROM " + table + " ORDER BY date")) {
                while (rs.next()) {
                    rows.add(new Object[]{ rs.getInt(1), rs.getString(2), rs.getDouble(3), rs.getInt(4), rs.getString(5) });
                }
            }
            if (rows.isEmpty()) return 0;

            String sys = "你是个人记账的分类器。给每一条消费从下面的清单里选且只选一个类别：\n" +
                Cat.LIST + "\n" +
                "规则：餐饮=吃饭喝饮料；交通=地铁公交打车加油停车；居住=房租水电燃气宽带物业话费；" +
                "娱乐=电影游戏演出旅游会员；购物=衣物数码日用；教育=课程书籍考试；" +
                "AI API=模型/接口/token 订阅；医疗=看病买药体检；人情=红包礼物请客随礼；其余归其他。\n" +
                "只输出 JSON，不要解释：{\"items\":[{\"i\":序号,\"cat\":\"类别\"}]}";

            PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + table + " SET cat=? WHERE date=? AND goods=? AND price=? AND num_1=?");

            for (int from = 0; from < rows.size(); from += 40) {
                int to = Math.min(from + 40, rows.size());
                StringBuilder payload = new StringBuilder("[");
                for (int i = from; i < to; i++) {
                    Object[] r = rows.get(i);
                    if (i > from) payload.append(',');
                    payload.append("{\"i\":").append(i - from)
                           .append(",\"goods\":\"").append(Json.esc((String) r[1])).append("\"}");
                }
                payload.append(']');

                Map<String, Object> root = Json.map(Json.parse(extractJson(chat(sys, payload.toString()))));
                for (Object o : Json.list(root.get("items"))) {
                    Map<String, Object> m = Json.map(o);
                    int idx = Json.i(m.get("i"), -1);
                    if (idx < 0 || from + idx >= to) continue;
                    String cat = Cat.clean(Json.str(m.get("cat")), (String) rows.get(from + idx)[1]);
                    Object[] r = rows.get(from + idx);
                    if (cat.equals(r[4])) continue;
                    ps.setString(1, cat);
                    ps.setInt(2, (Integer) r[0]);
                    ps.setString(3, (String) r[1]);
                    ps.setDouble(4, (Double) r[2]);
                    ps.setInt(5, (Integer) r[3]);
                    ps.executeUpdate();
                    r[4] = cat;
                    changed++;
                }
            }
            ps.close();
        } finally {
            try { conn.close(); } catch (Exception ignored) {}
        }
        return changed;
    }

    // ==================== 建议 ====================

    public static String advice() throws Exception {
        String sys = "你是一个语气轻松、不说教的个人记账助手。根据用户本月数据，用中文给 2-3 句消费建议，" +
                "直接说重点，不要客套，不要罗列数据。只输出 JSON：{\"advice\":\"...\"}";
        String content = chat(sys, monthSummary());
        Map<String, Object> root = Json.map(Json.parse(extractJson(content)));
        String a = Json.str(root.get("advice"));
        if (a == null || a.trim().isEmpty()) a = content.trim();
        return a.trim();
    }

    /** 汇总当月数据，喂给模型；尽量短，省 token */
    public static String monthSummary() {
        int month = Date_time.getMonth();
        int today = Date_time.getDay();
        String table = String.format("table_%02d", month);

        double total = 0, daily = 0, special = 0, income = 0;
        int recordDays = 0;
        List<String> top = new ArrayList<>();

        Connection conn = One.getConn();
        if (conn != null) {
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery(
                        "SELECT COALESCE(SUM(price),0), " +
                        "COALESCE(SUM(CASE WHEN num_1=1 THEN price END),0), " +
                        "COALESCE(SUM(CASE WHEN num_1<>1 THEN price END),0), " +
                        "COUNT(DISTINCT date) FROM " + table);
                if (rs.next()) {
                    total = rs.getDouble(1);
                    daily = rs.getDouble(2);
                    special = rs.getDouble(3);
                    recordDays = rs.getInt(4);
                }
                rs.close();

                rs = st.executeQuery("SELECT goods, price FROM " + table + " ORDER BY price DESC LIMIT 5");
                while (rs.next()) {
                    top.add(rs.getString(1) + " ¥" + String.format("%.0f", rs.getDouble(2)));
                }
                rs.close();
            } catch (Exception e) {
                // 表不存在或查询失败：返回一个安全的最小摘要
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        }

        try (Connection c2 = One.getConn()) {
            if (c2 != null) {
                PreparedStatement ps = c2.prepareStatement(
                        "SELECT COALESCE(SUM(amount),0) FROM income_records WHERE month = ?");
                ps.setInt(1, month);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) income = rs.getDouble(1);
                rs.close();
                ps.close();
            }
        } catch (Exception ignored) {}

        if (income <= 0) income = 1500;

        if (total <= 0) {
            return "本月（" + month + " 月）截至 " + today + " 日还没有任何消费记录。";
        }

        StringBuilder b = new StringBuilder();
        b.append("本月 ").append(month).append(" 月，截至 ").append(today).append(" 日：")
         .append("日常消费 ¥").append(String.format("%.0f", daily))
         .append("，特殊消费 ¥").append(String.format("%.0f", special))
         .append("，合计 ¥").append(String.format("%.0f", total))
         .append("；本月收入（预算）¥").append(String.format("%.0f", income))
         .append("，已记录 ").append(recordDays).append(" 天");
        if (!top.isEmpty()) {
            b.append("；金额前五：").append(String.join("、", top));
        }
        b.append("。");
        return b.toString();
    }

    // ==================== Ollama 调用 ====================

    private static String chat(String system, String user) throws Exception {
        String body = "{\"model\":\"" + Json.esc(MODEL) + "\",\"stream\":false,\"format\":\"json\"," +
                "\"options\":{\"temperature\":0.2}," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"" + Json.esc(system) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + Json.esc(user) + "\"}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/chat"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("Ollama 返回 HTTP " + res.statusCode());
        }
        Map<String, Object> root = Json.map(Json.parse(res.body()));
        String content = Json.str(Json.map(root.get("message")).get("content"));
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("AI 没有返回内容");
        }
        if ("1".equals(System.getenv("LOH_AI_DEBUG"))) {
            System.out.println("[AI RAW] " + content);
        }
        return content;
    }

    /** 模型有时会包 ```json 或加解释，截取第一个 { 到最后一个 } */
    private static String extractJson(String s) {
        if (s == null) throw new IllegalArgumentException("AI 返回为空");
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("AI 返回的不是 JSON");
        return s.substring(a, b + 1);
    }
}
