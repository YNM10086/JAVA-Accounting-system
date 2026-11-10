import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 WebServer 的页面模板渲染成静态 HTML，供 headless 浏览器截图。
 *
 * 用法：
 *   java PagePreview main   out.html      # 真实主菜单（不连数据库）
 *   java PagePreview query  out.html      # 查询页样式样例（假数据）
 *   java PagePreview form   out.html      # 表单页样式样例（假数据）
 *   java PagePreview body.html out.html   # 自定义 body 片段
 *
 * 输出会做三件事，保证截图拿到「稳定帧」：
 *   1. /static/ascii-ocean.js 改成 file:// 绝对路径（本地预览不经过服务器）
 *   2. 关掉 CSS 动画/过渡
 *   3. 海洋引擎 fadeIn 关闭（直接以目标透明度出图）
 */
public class PagePreview {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PagePreview <main|query|form|body.html> <out.html>");
            System.exit(2);
        }
        String page = args[0];
        Path out = Path.of(args[1]);

        String html;
        switch (page) {
            case "main":
                html = invoke("mainPage");
                break;
            case "query":
                html = render("消费查询（样例）", QUERY_BODY);
                break;
            case "form":
                html = render("存入消费（样例）", FORM_BODY);
                break;
            default:
                String body = Files.readString(Path.of(page), StandardCharsets.UTF_8);
                html = render("预览", body);
        }

        String oceanUrl = Path.of(System.getProperty("project.root", "."), "static", "ascii-ocean.js")
            .toAbsolutePath().toUri().toString();

        html = html.replace("/static/ascii-ocean.js", oceanUrl)
                   .replace("fadeIn:true", "fadeIn:false")
                   .replace("</head>", "<style>*{animation:none!important;transition:none!important}</style></head>");

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        System.out.println(out.toAbsolutePath());
    }

    private static String invoke(String method) throws Exception {
        Method m = Class.forName("function.WebServer").getDeclaredMethod(method);
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    private static String render(String title, String body) throws Exception {
        Method m = Class.forName("function.WebServer").getDeclaredMethod("pageRaw", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, title, body);
    }

    private static final String QUERY_BODY =
        "<header><div class='top-bar'>" +
        "<a href='/main' class='back-btn'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='18' height='18'><path d='M19 12H5M12 19l-7-7 7-7'/></svg><span>首页</span></a>" +
        "<div class='top-bar-spacer'></div></div></header>" +
        "<div class='page-header-row'><h2>消费查询</h2><button class='btn btn-sm btn-export'>导出 Excel</button></div>" +
        "<div class='filter-bar'><a href='#' class='filter-pill'>1月</a><a href='#' class='filter-pill active'>11月</a><a href='#' class='filter-pill'>12月</a></div>" +
        "<section class='query-section'><h3>月度统计</h3><div class='stat-row'>" +
        "<div class='stat'><span>总消费</span><strong>¥1,286.40</strong></div>" +
        "<div class='stat'><span>日均消费</span><strong>¥42.88</strong></div>" +
        "<div class='stat'><span>统计天数</span><strong>30 天</strong></div>" +
        "<div class='stat'><span>总记录数</span><strong>87 条</strong></div></div></section>" +
        "<section class='query-section'><h3>日消费明细</h3>" +
        "<table><tr><th>商品</th><th>价格</th><th>类型</th><th>操作</th></tr>" +
        "<tr><td>早餐</td><td>¥8.00</td><td>日常</td><td><button class='btn btn-sm btn-danger'>删除</button></td></tr>" +
        "<tr><td>地铁</td><td>¥4.00</td><td>日常</td><td><button class='btn btn-sm btn-danger'>删除</button></td></tr>" +
        "<tr><td>耳机</td><td>¥499.00</td><td>特殊</td><td><button class='btn btn-sm btn-danger'>删除</button></td></tr></table></section>";

    private static final String FORM_BODY =
        "<header><div class='top-bar'>" +
        "<a href='/main' class='back-btn'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' width='18' height='18'><path d='M19 12H5M12 19l-7-7 7-7'/></svg><span>首页</span></a>" +
        "<div class='top-bar-spacer'></div></div></header>" +
        "<div class='form-card'><h2>存入消费</h2>" +
        "<div class='input-group'><label>商品名</label><div class='input-wrap'><input value='咖啡'></div></div>" +
        "<div class='input-group'><label>价格</label><div class='input-wrap'><input value='18.00'></div></div>" +
        "<div class='radio-pill-group'><label class='radio-pill'><input type='radio' checked><span class='pill-text'>日常</span></label>" +
        "<label class='radio-pill'><input type='radio'><span class='pill-text'>特殊</span></label></div>" +
        "<button class='btn btn-block'>保存</button></div>";
}
