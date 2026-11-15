package function;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 单页里的各个 section（总览以外）。
 *
 * 全部功能都挂在 /main 这一页上：左栏是锚点，表单 POST 完回到对应锚点。
 * 这里只生成 section 的 HTML，壳由 WebServer.shell() 包。
 */
public final class Sections {

    private Sections() {}

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String money(double v) {
        return String.format("%,.0f", v);
    }

    /** 段落头：序号 + 标题 + 英文副标 + 右侧工具 */
    public static String head(String no, String title, String en, String right) {
        return "<div class='pagehead'><div><h1><span class='no'>" + no + "</span>" + title + "</h1>" +
            "<div class='sub'><span class='bar'></span><span class='label'>" + en + "</span></div></div>" +
            (right == null || right.isEmpty() ? "" : "<div class='headtool'>" + right + "</div>") + "</div>";
    }

    /* ---------------- 记一笔 ---------------- */
    public static String entry() {
        int today = Date_time.getDay();
        int month = Date_time.getMonth();
        StringBuilder m = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            m.append("<option value='").append(i).append("'").append(i == month ? " selected" : "")
             .append(">").append(i).append(" 月").append(i == month ? "（本月）" : "").append("</option>");
        }
        StringBuilder cats = new StringBuilder();
        for (String c : Cat.ALL) cats.append("<option>").append(c).append("</option>");

        return "<section class='sec' id='entry'>" +
            head("02", "记一笔", "ENTRY / 存进当月表", "") +
            "<div class='panel pad'><form class='formGrid' method='post' action='/save'>" +
            "<label class='fld'><span>商品名称</span><input name='goods' required placeholder='例如：牛肉面' autocomplete='off'></label>" +
            "<label class='fld'><span>价格（元）</span><input name='price' type='number' step='0.01' min='0.01' required placeholder='25.5'></label>" +
            "<label class='fld'><span>类别</span><select name='cat'>" + cats + "</select></label>" +
            "<label class='fld'><span>性质</span><select name='type'><option value='1'>日常</option><option value='2'>特殊</option></select></label>" +
            "<label class='fld'><span>月份</span><select name='month'>" + m + "</select></label>" +
            "<label class='fld'><span>日期（日）</span><input name='day' type='number' min='1' max='31' value='" + today + "'></label>" +
            "<div class='fld fldBtn'><button class='btn solid' type='submit'>确认存入</button></div>" +
            "</form></div></section>";
    }

    /* ---------------- 收入 ---------------- */
    public static String income(Overview ov) {
        int today = Date_time.getDay();
        List<String[]> rows = new ArrayList<>();
        double sum = 0;
        Connection conn = One.getConn();
        if (conn != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, date, amount, description FROM income_records WHERE month = ? ORDER BY date DESC, id DESC")) {
                ps.setInt(1, ov.month);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[]{ String.valueOf(rs.getInt(1)), String.valueOf(rs.getInt(2)),
                                String.format("%.2f", rs.getDouble(3)), rs.getString(4) == null ? "" : rs.getString(4) });
                        sum += rs.getDouble(3);
                    }
                }
            } catch (Exception ignore) { /* 表还没建 */ }
            try { conn.close(); } catch (Exception ignored) {}
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<section class='sec' id='income'>")
          .append(head("03", "收入", "INCOME / 本月进账 " + String.format("%,.2f", sum),
                  "<span class='act'>" + rows.size() + " 笔</span>"))
          .append("<div class='panel pad'><form class='formGrid formGrid4' method='post' action='/income'>")
          .append("<label class='fld'><span>日期（日）</span><input name='date' type='number' min='1' max='31' value='")
          .append(today).append("'></label>")
          .append("<label class='fld'><span>金额（元）</span><input name='amount' type='number' step='0.01' required placeholder='5000'></label>")
          .append("<label class='fld'><span>备注</span><input name='description' placeholder='工资 / 兼职 / 红包'></label>")
          .append("<div class='fld fldBtn'><button class='btn solid' type='submit'>记一笔收入</button></div>")
          .append("</form></div>");

        if (!rows.isEmpty()) {
            sb.append("<div class='panel'><table><thead><tr><th style='width:80px'>日期</th>")
              .append("<th>备注</th><th style='width:140px;text-align:right'>金额</th><th style='width:80px'></th></tr></thead><tbody>");
            for (String[] r : rows) {
                sb.append("<tr><td class='dt'>").append(String.format("%02d", Integer.parseInt(r[1]))).append(" 日</td>")
                  .append("<td class='note'>").append(esc(r[3])).append("</td>")
                  .append("<td class='amt in'>+¥").append(r[2]).append("</td>")
                  .append("<td><form method='post' action='/income'>")
                  .append("<input type='hidden' name='_action' value='delete'>")
                  .append("<input type='hidden' name='id' value='").append(r[0]).append("'>")
                  .append("<button class='link danger' type='submit'>删除</button></form></td></tr>");
            }
            sb.append("</tbody></table></div>");
        }
        return sb.append("</section>").toString();
    }

    /* ---------------- 明细 ---------------- */
    public static String details(Overview ov) {
        String table = String.format("table_%02d", ov.month);
        List<String[]> rows = new ArrayList<>();
        Connection conn = One.getConn();
        if (conn != null) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT date, goods, price, num_1, cat FROM " + table + " ORDER BY date DESC LIMIT 20")) {
                while (rs.next()) {
                    rows.add(new String[]{ String.valueOf(rs.getInt(1)), rs.getString(2),
                            String.format("%.2f", rs.getDouble(3)), String.valueOf(rs.getInt(4)),
                            rs.getString(5) == null ? "其他" : rs.getString(5) });
                }
            } catch (Exception ignore) { /* 表不存在 */ }
            try { conn.close(); } catch (Exception ignored) {}
        }
        double avg = ov.expense / Math.max(1, Date_time.getDay());

        StringBuilder sb = new StringBuilder();
        sb.append("<section class='sec' id='details'>")
          .append(head("04", "明细", "DETAILS / 最近 20 条 · 共 " + ov.count + " 条",
                  "<a class='act' href='/export'>导出本月 →</a>"))
          .append("<div class='statGrid'>")
          .append(stat("本月支出", "¥" + money(ov.expense)))
          .append(stat("日均", "¥" + money(avg)))
          .append(stat("记录数", ov.count + " 条"))
          .append(stat("日常 / 特殊", money(ov.daily) + " / " + money(ov.special)))
          .append("</div>");

        sb.append("<div class='panel'><table><thead><tr><th style='width:92px'>日期</th>")
          .append("<th style='width:110px'>分类</th><th style='width:72px'>性质</th><th>备注</th>")
          .append("<th style='width:120px;text-align:right'>金额</th><th style='width:64px'></th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            sb.append("<tr><td colspan='6' class='empty'>本月还没有记录</td></tr>");
        }
        for (String[] r : rows) {
            sb.append("<tr><td class='dt'>").append(String.format("%02d", Integer.parseInt(r[0]))).append(" 日</td>")
              .append("<td class='tag' data-cat='").append(esc(r[4])).append("'><span class='tick'></span>")
              .append(esc(r[4])).append("</td>")
              .append(WebServer.typeCell(Integer.parseInt(r[3])))
              .append("<td class='note'>").append(esc(r[1])).append("</td>")
              .append("<td class='amt out'>-¥").append(r[2]).append("</td>")
              .append("<td><form method='post' action='/query'>")
              .append("<input type='hidden' name='action' value='delete'>")
              .append("<input type='hidden' name='month' value='").append(ov.month).append("'>")
              .append("<input type='hidden' name='day' value='").append(r[0]).append("'>")
              .append("<input type='hidden' name='goods' value='").append(esc(r[1])).append("'>")
              .append("<button class='link danger' type='submit'>删除</button></form></td></tr>");
        }
        sb.append("</tbody></table></div></section>");
        return sb.toString();
    }

    private static String stat(String label, String value) {
        return "<div class='panel statCard'><span class='k'>" + label + "</span><b class='v'>" + value + "</b></div>";
    }

    /* ---------------- 预算 ---------------- */
    public static String budget(Overview ov) {
        double budget = Overview.BUDGET;
        double left = budget - ov.expense;
        int remainDays = Date_time.getRemainingDays();
        double perDay = left > 0 ? left / Math.max(1, remainDays) : 0;
        double used = budget > 0 ? ov.expense / budget * 100 : 0;
        String state = left >= 0 ? (used > 90 ? "warn" : "ok") : "over";

        StringBuilder sb = new StringBuilder();
        sb.append("<section class='sec' id='budget'>")
          .append(head("05", "预算", "BUDGET / 每月 " + money(budget),
                  "<span class='act " + ("over".equals(state) ? "danger" : "") + "'>"
                      + ("ok".equals(state) ? "稳得住" : "over".equals(state) ? "已超支" : "接近上限") + "</span>"))
          .append("<div class='statGrid'>")
          .append(stat("本月预算", "¥" + money(budget)))
          .append(stat("已用", "¥" + money(ov.expense) + " · " + String.format("%.1f", used) + "%"))
          .append(stat("剩余", (left >= 0 ? "¥" : "-¥") + money(Math.abs(left))))
          .append(stat("每日可用", left > 0 ? "¥" + String.format("%.2f", perDay) + " × " + remainDays + " 天" : "已用完"))
          .append("</div>")
          .append("<div class='panel pad'>")
          .append("<div class='bTrack big'><i class='").append(state).append("' style='width:")
          .append(String.format("%.1f", Math.min(used, 100))).append("%'></i></div>")
          .append("<p class='hint'>本月还剩 ").append(remainDays).append(" 天，")
          .append(left >= 0
              ? "按每天 ¥" + String.format("%.2f", perDay) + " 花，能撑到月底。"
              : "已经超支 ¥" + String.format("%.2f", -left) + "，后面每笔都是挤出来的。")
          .append("</p></div></section>");
        return sb.toString();
    }

    /* ---------------- 设置 ---------------- */
    public static String settings(Overview ov) {
        StringBuilder cats = new StringBuilder();
        for (String c : Cat.ALL) cats.append("<span class='pill'>").append(c).append("</span>");
        return "<section class='sec' id='settings'>" +
            head("06", "设置", "SETTINGS / 只读", "") +
            "<div class='panel pad'><dl class='kv'>" +
            "<dt>每月预算</dt><dd>¥" + money(Overview.BUDGET) + "</dd>" +
            "<dt>分类清单</dt><dd class='pills'>" + cats + "</dd>" +
            "<dt>数据库</dt><dd>MySQL · Cost_system · 当月表 " + String.format("table_%02d", ov.month) + "</dd>" +
            "<dt>AI 引擎</dt><dd id='settingsAi'>读取中…</dd>" +
            "</dl><p class='hint'>预算与分类暂时写死在后端（`Overview.BUDGET` / `Cat.ALL`），改这里属于下一步。</p></div>" +
            "</section>";
    }
}
