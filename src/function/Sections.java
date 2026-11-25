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
        return entry(null);
    }

    /**
     * 记一笔。rec 为 null 时是新增；否则是 /main?edit=ID 带过来的
     * {id, month, day, goods, price, num1, cat}，表单预填成「修改」模式。
     */
    public static String entry(String[] rec) {
        boolean editing = rec != null;
        int today = Date_time.getDay();
        int month = Date_time.getMonth();
        int selMonth = editing ? Integer.parseInt(rec[1]) : month;
        int selDay = editing ? Integer.parseInt(rec[2]) : today;
        int selType = editing ? Integer.parseInt(rec[5]) : 2;

        StringBuilder m = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            m.append("<option value='").append(i).append("'").append(i == selMonth ? " selected" : "")
             .append(">").append(i).append(" 月").append(i == selMonth ? "（本月）" : "").append("</option>");
        }
        StringBuilder cats = new StringBuilder();
        for (String c : Cat.ALL) {
            cats.append("<option").append(editing && c.equals(rec[6]) ? " selected" : "").append(">")
                .append(c).append("</option>");
        }

        return "<section class='sec' id='entry'>" +
            head("02", editing ? "改一笔" : "记一笔",
                 editing ? "ENTRY / 修改第 " + rec[0] + " 条记录" : "ENTRY / 存进当月表", "") +
            (editing
                ? "<div class='editBar'>正在修改：<b>" + esc(rec[3]) + "</b> ¥" + esc(rec[4]) +
                  "（" + selDay + " 日 · " + esc(rec[6]) + "）<a class='link' href='/main#details'>取消修改</a></div>"
                : "") +
            "<div class='panel pad'><form class='formGrid' method='post' action='/save'>" +
            (editing
                ? "<input type='hidden' name='id' value='" + rec[0] + "'>" +
                  "<input type='hidden' name='month' value='" + selMonth + "'>"
                : "") +
            "<label class='fld'><span>商品名称</span><input name='goods' required placeholder='例如：牛肉面' autocomplete='off' value='" +
                (editing ? esc(rec[3]) : "") + "'></label>" +
            "<label class='fld'><span>价格（元）</span><input name='price' type='number' step='0.01' min='0.01' required placeholder='25.5' value='" +
                (editing ? esc(rec[4]) : "") + "'></label>" +
            "<label class='fld'><span>类别</span><select name='cat'>" + cats + "</select></label>" +
            "<label class='fld'><span>性质</span><select name='type'>" +
                "<option value='1'" + (selType == 1 ? " selected" : "") + ">日常</option>" +
                "<option value='2'" + (selType == 2 ? " selected" : "") + ">特殊</option></select></label>" +
            (editing
                ? "<label class='fld'><span>月份</span><input value='" + selMonth + " 月（修改时不能换月）' disabled></label>"
                : "<label class='fld'><span>月份</span><select name='month'>" + m + "</select></label>") +
            "<label class='fld'><span>日期（日）</span><input name='day' type='number' min='1' max='31' value='" + selDay + "'></label>" +
            "<div class='fld fldBtn'><button class='btn solid' type='submit'>" +
                (editing ? "保存修改" : "确认存入") + "</button>" +
                (editing ? "<a class='btn' href='/main#details'>取消</a>" : "") + "</div>" +
            "</form></div></section>";
    }

    /* ---------------- 收入 ---------------- */
    public static String income(Overview ov) {
        return income(ov, null);
    }

    /** rec 为 null 时是新增；否则是 /main?iedit=ID 带过来的 {id, date, amount, description} */
    public static String income(Overview ov, String[] rec) {
        boolean editing = rec != null;
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
          .append(head("03", editing ? "改一笔收入" : "收入",
                  editing ? "INCOME / 修改第 " + rec[0] + " 条收入" : "INCOME / 本月进账 " + String.format("%,.2f", sum),
                  "<span class='act'>" + rows.size() + " 笔</span>"));
        if (editing) {
            sb.append("<div class='editBar'>正在修改收入：<b>")
              .append(esc(rec[3].isEmpty() ? "（无备注）" : rec[3])).append("</b> ¥").append(esc(rec[2]))
              .append("（").append(rec[1]).append(" 日）<a class='link' href='/main#income'>取消修改</a></div>");
        }
        sb.append("<div class='panel pad'><form class='formGrid formGrid4' method='post' action='/income'>");
        if (editing) {
            sb.append("<input type='hidden' name='_action' value='update'>")
              .append("<input type='hidden' name='id' value='").append(rec[0]).append("'>");
        }
        sb.append("<label class='fld'><span>日期（日）</span><input name='date' type='number' min='1' max='31' value='")
          .append(editing ? esc(rec[1]) : String.valueOf(today)).append("'></label>")
          .append("<label class='fld'><span>金额（元）</span><input name='amount' type='number' step='0.01' required placeholder='5000' value='")
          .append(editing ? esc(rec[2]) : "").append("'></label>")
          .append("<label class='fld'><span>备注</span><input name='description' placeholder='工资 / 兼职 / 红包' value='")
          .append(editing ? esc(rec[3]) : "").append("'></label>")
          .append("<div class='fld fldBtn'><button class='btn solid' type='submit'>")
          .append(editing ? "保存修改" : "记一笔收入").append("</button>")
          .append(editing ? "<a class='btn' href='/main#income'>取消</a>" : "")
          .append("</div></form></div>");

        if (!rows.isEmpty()) {
            sb.append("<div class='panel'><table><thead><tr><th style='width:80px'>日期</th>")
              .append("<th>备注</th><th style='width:140px;text-align:right'>金额</th>")
              .append("<th style='width:96px;text-align:right'>操作</th></tr></thead><tbody>");
            for (String[] r : rows) {
                sb.append("<tr><td class='dt'>").append(String.format("%02d", Integer.parseInt(r[1]))).append(" 日</td>")
                  .append("<td class='note'>").append(esc(r[3])).append("</td>")
                  .append("<td class='amt in'>+¥").append(r[2]).append("</td>")
                  .append("<td class='rowAct'>")
                  .append("<a class='link' href='/main?iedit=").append(r[0]).append("#income' title='修改这笔收入'>改</a>")
                  .append("<form method='post' action='/income' onsubmit=\"return confirm('删除这笔收入？')\">")
                  .append("<input type='hidden' name='_action' value='delete'>")
                  .append("<input type='hidden' name='id' value='").append(r[0]).append("'>")
                  .append("<button class='link danger' type='submit' title='删除这笔收入'>删</button></form></td></tr>");
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
                     "SELECT id, date, goods, price, num_1, cat FROM " + table + " ORDER BY date DESC, id DESC LIMIT 400")) {
                while (rs.next()) {
                    rows.add(new String[]{ String.valueOf(rs.getInt(1)), String.valueOf(rs.getInt(2)),
                            rs.getString(3), String.format("%.2f", rs.getDouble(4)),
                            String.valueOf(rs.getInt(5)),
                            rs.getString(6) == null ? "其他" : rs.getString(6) });
                }
            } catch (Exception ignore) { /* 表不存在 */ }
            try { conn.close(); } catch (Exception ignored) {}
        }
        double avg = ov.expense / Math.max(1, Date_time.getDay());

        StringBuilder sb = new StringBuilder();
        sb.append("<section class='sec' id='details'>")
          .append(head("04", "明细", "DETAILS / 最近 " + Math.min(20, ov.count) + " 条 · 共 " + ov.count + " 条",
                  "<a class='act' href='/export'>导出本月 →</a>"))
          .append("<div class='statGrid'>")
          .append(stat("本月支出", "¥" + money(ov.expense)))
          .append(stat("日均", "¥" + money(avg)))
          .append(stat("记录数", ov.count + " 条"))
          .append(stat("日常 / 特殊", money(ov.daily) + " / " + money(ov.special)))
          .append("</div>");

        sb.append("<div class='panel'><table><thead><tr><th style='width:92px'>日期</th>")
          .append("<th style='width:110px'>分类</th><th style='width:72px'>性质</th><th>备注</th>")
          .append("<th style='width:120px;text-align:right'>金额</th><th style='width:96px;text-align:right'>操作</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            sb.append("<tr><td colspan='6' class='empty'>本月还没有记录</td></tr>");
        }
        int shown = 0;
        for (String[] r : rows) {
            sb.append(shown >= 20 ? "<tr class='more' hidden>" : "<tr>");
            shown++;
            sb.append("<td class='dt'>").append(String.format("%02d", Integer.parseInt(r[1]))).append(" 日</td>")
              .append("<td class='tag' data-cat='").append(esc(r[5])).append("'><span class='tick'></span>")
              .append(esc(r[5])).append("</td>")
              .append(WebServer.typeCell(Integer.parseInt(r[4])))
              .append("<td class='note'>").append(esc(r[2])).append("</td>")
              .append("<td class='amt out'>-¥").append(r[3]).append("</td>")
              .append("<td class='rowAct'>")
              .append("<a class='link' href='/main?edit=").append(r[0]).append("&amp;m=").append(ov.month)
              .append("#entry' title='修改这一条'>改</a>")
              .append("<form method='post' action='/del' onsubmit=\"return confirm('删除这条记录？')\">")
              .append("<input type='hidden' name='id' value='").append(r[0]).append("'>")
              .append("<input type='hidden' name='month' value='").append(ov.month).append("'>")
              .append("<button class='link danger' type='submit' title='删除这一条'>删</button></form></td></tr>");
        }
        sb.append("</tbody></table>");
        if (rows.size() > 20) {
            sb.append("<div class='tableMore'><button class='link' type='button' id='detMore'>显示全部 ")
              .append(rows.size()).append(" 条</button></div>");
        }
        sb.append("</div></section>");
        return sb.toString();
    }

    private static String stat(String label, String value) {
        return "<div class='panel statCard'><span class='k'>" + label + "</span><b class='v'>" + value + "</b></div>";
    }

    /* ---------------- 预算 ---------------- */
    public static String budget(Overview ov) {
        double budget = ov.budget;
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
          .append("<form class='formGrid formGrid3 budgetForm' method='post' action='/budget'>")
          .append("<label class='fld'><span>本月预算（元）</span><input name='amount' type='number' step='1' min='0' value='")
          .append(String.format("%.0f", budget)).append("'></label>")
          .append("<div class='fld fldBtn'><button class='btn solid' type='submit'>保存预算</button></div>")
          .append("</form>")
          .append("<div class='bTrack big'><i class='").append(state).append("' style='width:")
          .append(String.format("%.1f", Math.min(used, 100))).append("%'></i></div>")
          .append("<p class='hint'>本月还剩 ").append(remainDays).append(" 天，")
          .append(left >= 0
              ? "按每天 ¥" + String.format("%.2f", perDay) + " 花，能撑到月底。"
              : "已经超支 ¥" + String.format("%.2f", -left) + "，后面每笔都是挤出来的。")
          .append("</p></div></section>");
        return sb.toString();
    }

    /* ---------------- 导出 ---------------- */
    public static String export(Overview ov) {
        return "<section class='sec' id='export'>" +
            head("07", "导出", "EXPORT / 交给 Excel", "") +
            "<div class='panel pad'>" +
            "<div class='exportRow'>" +
            "<a class='btn solid' href='/export'>导出本月明细 .xlsx</a>" +
            "<a class='btn' href='/export?all=1'>导出全年 12 个月 .xlsx</a>" +
            "<span class='hint'>列：日期 / 分类 / 性质 / 商品 / 金额；每月一个工作表，末尾带合计。</span>" +
            "</div></div></section>";
    }

    /* ---------------- 设置 ---------------- */
    public static String settings(Overview ov) {
        StringBuilder cats = new StringBuilder();
        for (String c : Cat.ALL) cats.append("<span class='pill'>").append(c).append("</span>");
        return "<section class='sec' id='settings'>" +
            head("06", "设置", "SETTINGS / 偏好", "") +
            "<div class='panel pad'><dl class='kv'>" +
            "<dt>每月预算</dt><dd id='settingsBudget'>¥" + money(ov.budget) + "（在「预算」段可改，每月可不同）</dd>" +
            "<dt>开屏声音</dt><dd class='sndPref'>" +
                "<label class='sndToggle'><input type='checkbox' id='splashSnd'> <span>开机时播放</span></label>" +
                "<div class='sndVol'><input type='range' id='splashVol' min='0' max='100' step='1' aria-label='开屏音量'>" +
                "<b id='splashVolVal'>60%</b></div>" +
                "<span class='hint'>开屏动画的音效（现场合成，无音频文件）；拖音量条会试听一声，改完下次启动生效。开屏页左下角也能临时开关。</span>" +
            "</dd>" +
            "<dt>分类清单</dt><dd class='pills'>" + cats + "</dd>" +
            "<dt>数据库</dt><dd>MySQL · Cost_system · 当月表 " + String.format("table_%02d", ov.month) + "</dd>" +
            "<dt>AI 引擎</dt><dd id='settingsAi'>读取中…</dd>" +
            "<dt>AI 模型</dt><dd><select id='modelSel' aria-label='选择 AI 模型'></select>" +
            "<span class='hint' id='modelHint'>读取中…</span></dd>" +
            "<dt>分类数</dt><dd>" + Cat.ALL.length + " 类（AI 只能从这里选）</dd>" +
            "<dt>版本</dt><dd>v3.0.9 · 补丁 09（2026-11-25）</dd>" +
            "</dl><p class='hint'>预算按月份存在库里（`budgets` 表，没设过的月份默认 ¥1,500）；分类清单暂时写死在 `Cat.ALL`。</p></div>" +
            "</section>";
    }
}
