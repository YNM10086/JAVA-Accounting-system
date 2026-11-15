package function;

import java.util.regex.Pattern;

/**
 * 消费细类：餐饮 / 娱乐 / 购物 / 教育 / AI API / 其他。
 *
 * 与 num_1（1=日常 / 2=特殊）是并列的两个维度：num_1 记"性质"，cat 记"花在哪"。
 * 口径以 design_tmp/prototype/app.html 的双环为准，两边必须一致。
 */
public final class Cat {

    /** 允许的取值，顺序即界面下拉顺序。AI 只能从这里选，不许自由起名（否则跨月统计会碎） */
    public static final String[] ALL = {
        "餐饮", "交通", "居住", "话费", "购物", "日用", "服饰", "数码",
        "娱乐", "教育", "AI API", "医疗", "人情", "其他"
    };

    /** 给模型看的候选清单字符串 */
    public static final String LIST = String.join(" / ", ALL);

    private static final String[][] RULES = {
        { "(饭|面|餐|奶茶|咖啡|外卖|早点|食堂|火锅|烧烤|夜宵|零食|水果|饮料)", "餐饮" },
        { "(地铁|公交|打车|滴滴|高铁|火车|机票|飞机|加油|停车|单车|车费|过路)", "交通" },
        { "(房租|租金|电费|水费|燃气|宽带|物业|家政|维修|电梯|车位)", "居住" },
        { "(话费|流量|套餐|充值|中国移动|中国联通|中国电信|联通|移动|电信|宽带费)", "话费" },
        { "(电影|游戏|演出|门票|旅游|KTV|唱歌|桌游|剧本杀|会员)", "娱乐" },
        { "(课|网课|书|教程|考试|学费|培训|训练营|极客时间|得到)", "教育" },
        { "(?i)(api|claude|gpt|openai|token|模型|订阅|ollama|算力)", "AI API" },
        { "(药|医院|挂号|体检|门诊|牙医|看病|疫苗)", "医疗" },
        { "(红包|礼物|随礼|份子|请客|送礼|人情)", "人情" },
        { "(纸巾|洗衣液|洗发水|沐浴露|牙膏|清洁|垃圾袋|厨卫|日用)", "日用" },
        { "(衣服|裤|裙|外套|鞋|帽|包|内衣|袜子|羽绒)", "服饰" },
        { "(键盘|鼠标|耳机|显示器|充电|数据线|硬盘|U盘|相机|数码)", "数码" },
        { "(买|京东|淘宝|天猫|超市|商场|网购|下单)", "购物" },
    };

    private Cat() {}

    /** 给模型看的释义，避免它把话费归到居住、把衣服归到购物 */
    public static final String HINT =
        "餐饮=吃饭喝饮料；交通=地铁公交打车加油停车；居住=房租水电燃气物业维修；" +
        "话费=手机流量套餐运营商充值；购物=一般网购和大件；日用=纸巾洗护清洁等消耗品；" +
        "服饰=衣服鞋帽包袜子内衣；数码=电子产品与配件；娱乐=电影游戏演出会员；教育=课程书籍考试；" +
        "AI API=模型接口 token 订阅；医疗=看病买药体检；人情=红包礼物请客随礼；其余归其他";

    /** 关键词猜类：猜不出落"其他"（猜得不准可以事后在明细里改） */
    public static String guess(String goods) {
        if (goods == null || goods.trim().isEmpty()) return "其他";
        for (String[] r : RULES) {
            if (Pattern.compile(r[0]).matcher(goods).find()) return r[1];
        }
        return "其他";
    }

    /** 只接受白名单里的值，其余按 guess 兜底 */
    public static String clean(String raw, String goods) {
        if (raw != null) {
            String v = raw.trim();
            for (String c : ALL) if (c.equals(v)) return c;
        }
        return guess(goods);
    }

    /** 性质（num_1）与细类的默认对应：餐饮 → 日常，其余 → 特殊 */
    public static int defaultType(String cat) {
        return "餐饮".equals(cat) ? 1 : 2;
    }
}
