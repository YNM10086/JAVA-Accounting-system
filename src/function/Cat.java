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
        "餐饮", "交通", "居住", "娱乐", "购物", "教育", "AI API", "医疗", "人情", "其他"
    };

    /** 给模型看的候选清单字符串 */
    public static final String LIST = String.join(" / ", ALL);

    private static final String[][] RULES = {
        { "(饭|面|餐|奶茶|咖啡|外卖|早点|食堂|火锅|烧烤|夜宵|零食|水果|饮料)", "餐饮" },
        { "(地铁|公交|打车|滴滴|高铁|火车|机票|飞机|加油|停车|单车|车费|过路)", "交通" },
        { "(房租|租金|电费|水费|燃气|宽带|物业|话费|家政|维修)", "居住" },
        { "(电影|游戏|演出|门票|旅游|KTV|唱歌|桌游|剧本杀|会员)", "娱乐" },
        { "(课|网课|书|教程|考试|学费|培训|训练营|极客时间|得到)", "教育" },
        { "(?i)(api|claude|gpt|openai|token|模型|订阅|ollama|算力)", "AI API" },
        { "(药|医院|挂号|体检|门诊|牙医|看病|疫苗)", "医疗" },
        { "(红包|礼物|随礼|份子|请客|送礼|人情)", "人情" },
        { "(买|京东|淘宝|天猫|超市|商场|衣服|鞋|裤|外套|键盘|鼠标|耳机|数码|显示器)", "购物" },
    };

    private Cat() {}

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
