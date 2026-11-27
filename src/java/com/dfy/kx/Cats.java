package com.dfy.kx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 分类归一层。
 *
 * 为什么需要这一层（2026-11-27 实测结论）：
 * 苹果CMS 的 type_id 是**每个源自己编的**，跨源完全不对应。实测同一个 id 在各源的含义：
 *
 *   id=1  →  modu:国产动漫   ffzy:电影片   hn/bdzy/wujin/zuid:电影
 *   id=2  →  modu:日韩动漫   ffzy/hn/wujin:连续剧   bdzy/zuid:电视剧
 *   id=13 →  modu:科幻片     ffzy/wujin/zuid:国产剧   hn:港澳剧   bdzy:大陆剧
 *
 * 旧代码把 type_id 当全局常量（"2=电视剧"）写死，于是魔都源的"电视剧"实际拿的是
 * 日韩动漫，红牛源的"国产剧"实际拿的是港澳剧 —— 这就是"分类不准"的真正原因，
 * 不是源数据脏，是我们没按源解析。
 *
 * 现在的做法：启动后拉每个源的 ?ac=list，得到它自己的 class 表，把分类名做**别名归一**
 * （电影/电影片 → 电影；连续剧/电视剧 → 电视剧；记录片 → 纪录片；大陆剧 → 国产剧 …），
 * 再建「归一 key → 该源 type_id」的映射。查某个分类时每个源用自己的 id。
 * 某个源压根没有这个分类，就跳过它（而不是拿个错 id 去请求）。
 */
public class Cats {

    /** 一个归一分类。alias 是「可能出现在源站里的名字」，全部会被归一成 name。 */
    public static class Def {
        public final String key, name;
        public final String[] alias;
        public Def(String k, String n, String... a) { key = k; name = n; alias = a; }
    }

    /**
     * 导航分类。顺序就是界面上的顺序。
     * 别名表按实测的 6 个源整理：同一概念各家叫法不同，都收进来。
     */
    public static final Def[] DEFS = new Def[]{
        // 总分类
        new Def("movie",   "电影",     "电影", "电影片", "古装片", "历史片", "灾难片",
                                        "短片", "家庭片", "西部片", "4K电影", "邵氏电影",
                                        "Netflix电影", "枪战片"),
        new Def("tv",      "电视剧",   "电视剧", "连续剧", "Netflix自制剧"),
        new Def("variety", "综艺",     "综艺", "综艺片"),
        new Def("anime",   "动漫",     "动漫", "动漫片", "动画片", "动画"),
        new Def("cn_tv",   "国产剧",   "国产剧", "大陆剧", "国产电视剧"),
        new Def("hk_tv",   "港剧",     "香港剧", "港澳剧", "港剧"),
        new Def("kr_tv",   "韩剧",     "韩国剧", "韩剧"),
        new Def("jp_tv",   "日剧",     "日本剧", "日剧"),
        new Def("us_tv",   "欧美剧",   "欧美剧", "美国剧", "欧美电视剧", "美剧"),
        new Def("tw_tv",   "台剧",     "台湾剧", "台剧"),
        new Def("th_tv",   "泰剧",     "泰国剧", "泰剧"),
        new Def("ovs_tv",  "海外剧",   "海外剧", "其他剧"),
        new Def("doc",     "纪录片",   "纪录片", "记录片"),
        // 短剧。2026-11-27 实测：红牛/蘑菇/如意 把短剧拆成十几个子类
        //（古装仙侠 / 重生民国 / 都市脑洞 …），不归一的话「短剧」这一格几乎全空。
        new Def("shorts",  "短剧",     "短剧", "爽文短剧", "反转爽剧", "都市脑洞",
                                        "古装仙侠", "现代都市", "穿越年代", "年代穿越",
                                        "言情总裁", "女恋总裁", "女频恋爱", "闪婚离婚",
                                        "现代言情", "重生民国", "脑洞悬疑", "成长逆袭",
                                        "擦边短剧", "漫剧", "ai短剧", "AI短剧", "反转爽文",
                                        "神豪", "逆袭", "复仇"),

        // 电影子类
        new Def("action",  "动作片",   "动作片"),
        new Def("comedy",  "喜剧片",   "喜剧片"),
        new Def("romance", "爱情片",   "爱情片"),
        new Def("scifi",   "科幻片",   "科幻片"),
        new Def("horror",  "恐怖片",   "恐怖片"),
        new Def("drama",   "剧情片",   "剧情片"),
        new Def("war",     "战争片",   "战争片"),
        new Def("susp",    "悬疑片",   "悬疑片"),
        new Def("crime",   "犯罪片",   "犯罪片"),
        new Def("fantasy", "奇幻片",   "奇幻片"),
        new Def("thrill",  "惊悚片",   "惊悚片"),

        // 动漫子类
        new Def("cn_anime",  "国产动漫", "国产动漫", "中国动漫"),
        new Def("jpkr_anime","日韩动漫", "日韩动漫", "日本动漫", "有声动漫", "里番动漫"),
        new Def("us_anime",  "欧美动漫", "欧美动漫", "海外动漫"),
        new Def("hktw_anime","港台动漫", "港台动漫", "港台动画"),
        new Def("mov_anime", "动画电影", "动画电影", "动漫电影"),

        // 综艺子类
        new Def("cn_var",    "大陆综艺", "大陆综艺"),
        new Def("jpkr_var",  "日韩综艺", "日韩综艺"),
        new Def("hktw_var",  "港台综艺", "港台综艺"),
        new Def("us_var",    "欧美综艺", "欧美综艺"),
    };

    /**
     * 明确**不要**的分类。
     *
     * 为什么需要：后面的模糊兜底规则有「以『片』结尾 → 电影」这一条，
     * 如果不先挡一下，伦理片 / 预告片 / 影视解说会全被当成电影塞进「电影」栏，
     * 那就成了用户最担心的「格式看着对、内容全不对」。
     * 这里列出的名字，canon() 直接返回空，即：该分类在导航里不出现。
     */
    public static final String[] EXCLUDE = new String[]{
        "预告片", "资讯", "公告", "头条", "未分类", "体育", "体育赛事", "足球", "篮球",
        "NBA", "网球", "台球", "斯诺克", "其他赛事", "赛事", "演唱会", "影视解说",
        "电影解说", "伦理", "伦理片", "港台三级", "韩国伦理", "日本伦理", "西方伦理",
        "两性课堂", "写真热舞", "AI漫剧", "儿童儿歌", "家庭篇", "专题", "福利",
    };

    /**
     * 「总分类」的子分类。
     *
     * 实测（2026-11-27）：这几个源里 id 对应"电影""连续剧"的那一档基本是空壳
     * （ffzy t=1 返回 0 条、modu t=7 只有 2 条），而子分类（动作片/喜剧片/国产剧…）
     * 数据是满的。所以点「电影」时先把子分类各拉一页拼起来，别让用户看到空白。
     */
    static final java.util.Map<String, String[]> CHILD = new java.util.HashMap<String, String[]>();
    static {
        CHILD.put("movie", new String[]{"action", "comedy", "romance", "scifi",
                "horror", "drama", "war", "susp", "crime", "fantasy", "thrill"});
        CHILD.put("tv", new String[]{"cn_tv", "hk_tv", "kr_tv", "jp_tv", "us_tv",
                "tw_tv", "th_tv", "ovs_tv", "shorts"});
        CHILD.put("variety", new String[]{"cn_var", "jpkr_var", "hktw_var", "us_var"});
        CHILD.put("anime", new String[]{"cn_anime", "jpkr_anime", "us_anime",
                "hktw_anime", "mov_anime"});
    }

    public static String[] children(String key) {
        return CHILD.get(key);
    }

    // ---------------- 缓存 ----------------

    /** src.key -> (归一 key -> 该源 type_id) */
    static final Map<String, Map<String, String>> MAP = new HashMap<String, Map<String, String>>();
    /** src.key -> 刷新时刻 */
    static final Map<String, Long> TS = new HashMap<String, Long>();
    /** 6 小时。源站分类表基本不变，一天刷两次足够。 */
    static final long TTL = 6 * 60 * 60 * 1000L;
    static final Object LOCK = new Object();

    public static Def def(String key) {
        for (Def d : DEFS) if (d.key.equals(key)) return d;
        return null;
    }

    /** 把源站给的分类名归一（别名 → 去掉"片/剧"后缀 → 模糊兜底）。 */
    static String canon(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        // 0) 先看排除表：宁可不出现在导航里，也不能归错
        for (String x : EXCLUDE) if (x.equalsIgnoreCase(s)) return "";
        // 1) 精确别名
        for (Def d : DEFS) {
            for (String a : d.alias) {
                if (a.equals(s)) return d.key;
            }
        }
        // 2) 去掉尾部"片"再比一次（"动画片"→"动画"，"恐怖片"→"恐怖"）
        if (s.endsWith("片") && s.length() > 2) {
            String t = s.substring(0, s.length() - 1);
            for (Def d : DEFS) {
                for (String a : d.alias) if (a.equals(t)) return d.key;
            }
        }
        // 3) 模糊兜底。用户自己加的源很可能用没见过的叫法，
        //    这一步保证「大体认得出来」的分类不会因为名字不同而整条丢掉。
        if (s.contains("动漫") || s.contains("动画")) {
            if (s.contains("港台") || s.contains("台湾") || s.contains("香港")) return "hktw_anime";
            if (s.contains("欧美") || s.contains("海外")) return "us_anime";
            if (s.contains("日韩") || s.contains("日本")) return "jpkr_anime";
            if (s.contains("国产") || s.contains("中国") || s.contains("大陆")) return "cn_anime";
            return "anime";
        }
        if (s.contains("综艺")) return "variety";
        if (s.contains("纪录") || s.contains("记录")) return "doc";
        if (s.contains("短剧") || s.contains("爽文") || s.contains("爽剧")) return "shorts";
        if (s.endsWith("剧")) {
            if (s.contains("国产") || s.contains("大陆")) return "cn_tv";
            if (s.contains("港") || s.contains("台")) return "hk_tv";
            if (s.contains("韩")) return "kr_tv";
            if (s.contains("日")) return "jp_tv";
            if (s.contains("美") || s.contains("欧美")) return "us_tv";
            if (s.contains("泰")) return "th_tv";
            return "tv";
        }
        if (s.endsWith("片")) return "movie";
        return "";
    }

    /** 拉一个源的分类表并建映射。失败返回 null，调用方跳过该源。 */
    static Map<String, String> load(Sources.Src s) {
        Map<String, String> m = new HashMap<String, String>();
        try {
            Net.Resp r = Net.get(s.base + "?ac=list", s.referer, null, 0, 9000);
            if (!r.ok() || r.body == null || r.body.length == 0) return null;
            String tx = r.text();
            int b0 = tx.indexOf('{');
            if (b0 < 0) return null;
            JSONObject j = new JSONObject(tx.substring(b0));
            JSONArray a = j.optJSONArray("class");
            if (a == null || a.length() == 0) return null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("type_id", "");
                String nm = o.optString("type_name", "");
                String k = canon(nm);
                if (id.length() == 0 || k.length() == 0) continue;
                // 同一归一名有多个 id 时，保留先出现的（通常是主分类，靠前的更全）
                if (!m.containsKey(k)) m.put(k, id);
            }
        } catch (Throwable t) {
            Log.w("cats load " + s.key + " " + t);
            return null;
        }
        return m.size() > 0 ? m : null;
    }

    /** 保证该源分类已加载（必要时刷新）。返回它的映射，失败返回 null。 */
    public static Map<String, String> of(Sources.Src s) {
        if (s == null) return null;
        synchronized (LOCK) {
            Long t = TS.get(s.key);
            Map<String, String> m = MAP.get(s.key);
            if (m != null && t != null && System.currentTimeMillis() - t < TTL) return m;
        }
        Map<String, String> m = load(s);
        if (m == null) return null;
        synchronized (LOCK) {
            MAP.put(s.key, m);
            TS.put(s.key, System.currentTimeMillis());
            return m;
        }
    }

    /** 只看缓存，不触发加载。 */
    public static Map<String, String> peek(Sources.Src s) {
        if (s == null) return null;
        synchronized (LOCK) { return MAP.get(s.key); }
    }

    /** 某源里这个归一分类对应的 type_id。没有就返回 null（表示该源不参与）。 */
    public static String idOf(Sources.Src s, String key) {
        Map<String, String> m = of(s);
        if (m == null) return null;
        return m.get(key);
    }

    /** 只为某一个源预热分类表。用户新加了自定义源时调，不然它进不了分类导航。 */
    public static void warmOne(final Sources.Src s) {
        if (s == null) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { of(s); } catch (Throwable th) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 全部源预加载（App 启动时后台跑一次，避免第一次进影片库卡 6 次等待）。 */
    public static void warm() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (Sources.Src s : Sources.ALL) {
                    try { of(s); } catch (Throwable th) {}
                }
                Log.i("cats warm done");
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * 给界面的分类列表。只返回**至少有一个源真的支持**的分类，
     * 免得点了之后一片空白（旧代码那 14 个分类里有一半在某几个源上是错的）。
     */
    public static String json() {
        // 只用「已经加载过的」源的分类表。绝不在这里逐个去拉：
        // 6 个源首次要 5~10 秒，会把影片库首页一起拖住（实测启动后十几秒白屏）。
        // 没就绪就回 warming=1，前端过一会儿再来问。
        List<Sources.Src> ok = new ArrayList<Sources.Src>();
        for (Sources.Src s : Sources.ALL) {
            Map<String, String> m = peek(s);
            if (m != null) ok.add(s);
        }
        if (ok.isEmpty()) {
            warm();   // 顺带把后台加载踢起来（本身不阻塞）
            return "{\"cats\":[],\"srcs\":0,\"warming\":1}";
        }
        StringBuilder sb = new StringBuilder("{\"cats\":[");
        boolean first = true;
        for (Def d : DEFS) {
            List<String> who = new ArrayList<String>();
            for (Sources.Src s : ok) {
                Map<String, String> m = MAP.get(s.key);
                if (m != null && m.containsKey(d.key)) who.add(s.key);
            }
            if (who.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"key\":\"").append(d.key).append("\",\"id\":\"").append(d.key).append("\",\"name\":\"").append(d.name)
              .append("\",\"n\":").append(who.size()).append(",\"srcs\":[");
            for (int i = 0; i < who.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(who.get(i)).append('"');
            }
            sb.append("]}");
        }
        sb.append("],\"srcs\":").append(ok.size()).append('}');
        return sb.toString();
    }
}
