package com.dfy.kx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 豆瓣榜单（排行榜的数据源）。
 *
 * 为什么去豆瓣拿：采集站的 vod_douban_score 字段实测全是 0.0（魔都源有字段没数据），
 * 靠它们做不出排行榜。豆瓣自己的 rexxar 接口能直接给出「Top250」「实时热门」这类
 * 榜单，每条都带评分，正好当排行榜用。
 *
 * 注意：这里只拿「片名 / 评分 / 年份 / 海报」，不拿播放地址。播放地址还得靠采集站，
 * 由 /api/rank/play 按片名去搜。
 */
public class Douban {

    public static class Coll {
        public final String id;
        public final String name;
        public Coll(String id, String name) { this.id = id; this.name = name; }
    }

    /** 实测全部 200 的榜单。顺序就是界面上的顺序。 */
    public static final Coll[] COLLS = new Coll[]{
            new Coll("movie_top250", "豆瓣电影Top250"),
            new Coll("movie_showing", "正在上映"),
            new Coll("movie_real_time_hotest", "实时热门电影"),
            new Coll("movie_weekly_best", "一周口碑电影"),
            new Coll("tv_real_time_hotest", "实时热门剧集"),
            new Coll("tv_chinese_best_weekly", "华语口碑剧集"),
            new Coll("tv_global_best_weekly", "全球口碑剧集"),
            new Coll("show_chinese_best_weekly", "华语口碑综艺")
    };

    public static class Item {
        public String name = "";
        public String year = "";
        public String pic = "";
        public String dbId = "";
        public String url = "";
        public String type = "";
        public String card = "";
        public double score = -1;
        public int rank = 0;
    }

    static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";
    static final String REF = "https://m.douban.com/subject_collection/";

    static class Cache {
        long ts;
        List<Item> list;
    }

    static final Map<String, Cache> MEM = new HashMap<String, Cache>();
    /** 10 分钟。榜单本身一天才动几次，刷太勤只会被豆瓣掐。 */
    static final long TTL = 10 * 60 * 1000L;

    static Object lock() { return MEM; }

    public static String base(String coll) {
        for (Coll c : COLLS) if (c.id.equals(coll)) return c.name;
        return coll;
    }

    public static boolean valid(String coll) {
        for (Coll c : COLLS) if (c.id.equals(coll)) return true;
        return false;
    }

    /** 取某个榜单的前 n 条。失败返回空表（不抛异常，界面显示「取不到」就行）。 */
    public static List<Item> items(String coll, int n) {
        if (!valid(coll)) coll = COLLS[0].id;
        if (n < 1) n = 20;
        if (n > 250) n = 250;
        String key = coll + ":" + n;
        synchronized (lock()) {
            Cache c = MEM.get(key);
            if (c != null && System.currentTimeMillis() - c.ts < TTL) return c.list;
        }
        List<Item> out = fetch(coll, n);
        if (out != null && out.size() > 0) {
            synchronized (lock()) {
                Cache c = new Cache();
                c.ts = System.currentTimeMillis();
                c.list = out;
                MEM.put(key, c);
            }
            return out;
        }
        // 拉失败时把旧缓存拿出来顶着，总比空手强
        synchronized (lock()) {
            Cache c = MEM.get(key);
            if (c != null) return c.list;
        }
        return new ArrayList<Item>();
    }

    static List<Item> fetch(String coll, int n) {
        List<Item> out = new ArrayList<Item>();
        String body = http("https://m.douban.com/rexxar/api/v2/subject_collection/"
                + coll + "/items?start=0&count=" + n);
        if (body == null) {
            Log.w("douban " + coll + " fetch fail");
            return out;
        }
        try {
            JSONObject j = new JSONObject(body);
            JSONArray a = j.optJSONArray("subject_collection_items");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.name = nz(o.optString("title"));
                it.card = nz(o.optString("card_subtitle"));
                it.pic = pickPic(o);
                it.dbId = nz(o.optString("id"));
                it.url = nz(o.optString("url"));
                it.type = nz(o.optString("type"));
                it.rank = o.optInt("rank", i + 1);
                JSONObject rt = o.optJSONObject("rating");
                if (rt != null) it.score = rt.optDouble("value", -1);
                it.year = yearOf(it.card);
                if (it.name.length() > 0) out.add(it);
            }
        } catch (Throwable t) {
            Log.w("douban parse " + t);
        }
        Log.i("douban " + coll + " n=" + out.size());
        return out;
    }

    /**
     * 豆瓣同一套接口，不同榜单的海报字段长得不一样（实测）：
     *   Top250 / 一周口碑        -> cover_url: "https://img3.doubanio.com/..."
     *   正在上映 / 实时热门      -> cover: {url, width, height, shape}
     *   实时热门（另有）         -> pic: {large, normal}
     * 只认 cover_url 会让「正在上映 / 实时热门电影 / 实时热门剧集」三个榜
     * 整排没有海报，所以这里按优先级挨个试。
     */
    static String pickPic(JSONObject o) {
        String s = nz(o.optString("cover_url")).trim();
        if (s.length() == 0) {
            JSONObject cv = o.optJSONObject("cover");
            if (cv != null) s = nz(cv.optString("url")).trim();
        }
        if (s.length() == 0) {
            JSONObject p = o.optJSONObject("pic");
            if (p != null) {
                s = nz(p.optString("large")).trim();
                if (s.length() == 0) s = nz(p.optString("normal")).trim();
            }
        }
        if (s.length() == 0) {
            String p2 = nz(o.optString("pic")).trim();
            if (p2.startsWith("http")) s = p2;
        }
        return smallPic(s);
    }

    /**
     * 把豆瓣的大海报换成小海报。m_ratio 是 4000x6000 那种原图（实测单张 83KB 起，
     * 榜单卡片才 60x90），换 s_ratio 只要 19KB，一屏 30 张能省 2MB。
     * 换不到就原样返回，不影响功能。
     */
    static String smallPic(String u) {
        if (u == null) return "";
        if (u.indexOf("/m_ratio_poster/") > 0) return u.replace("/m_ratio_poster/", "/s_ratio_poster/");
        if (u.indexOf("/l_ratio_poster/") > 0) return u.replace("/l_ratio_poster/", "/s_ratio_poster/");
        return u;
    }

    /** card_subtitle 形如 "1994 / 美国 / 剧情 犯罪 / 导演 / 主演"，第一个字段是年份 */
    static String yearOf(String card) {
        if (card == null) return "";
        int i = card.indexOf('/');
        String y = (i > 0 ? card.substring(0, i) : card).trim();
        if (y.length() == 4) {
            boolean digits = true;
            for (int k = 0; k < 4; k++) if (!Character.isDigit(y.charAt(k))) digits = false;
            if (digits) return y;
        }
        return "";
    }

    static String nz(String s) { return s == null ? "" : s; }

    static String http(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REF);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w("douban http " + code);
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.w("douban http " + t);
            return null;
        }
    }

    /** 榜单 JSON（给 /api/rank 用） */
    public static String json(String coll, int n, long ms) {
        List<Item> list = items(coll, n);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"coll\":\"").append(esc(coll)).append("\",\"title\":\"")
          .append(esc(base(coll))).append("\",\"ms\":").append(ms).append(",\"list\":[");
        for (int i = 0; i < list.size(); i++) {
            Item it = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(esc(it.name)).append('"')
              .append(",\"year\":\"").append(esc(it.year)).append('"')
              .append(",\"pic\":\"").append(esc(it.pic)).append('"')
              .append(",\"score\":").append(it.score)
              .append(",\"dbId\":\"").append(esc(it.dbId)).append('"')
              .append(",\"card\":\"").append(esc(it.card)).append('"')
              .append(",\"rank\":").append(it.rank)
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String collsJson() {
        StringBuilder sb = new StringBuilder("{\"colls\":[");
        for (int i = 0; i < COLLS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(esc(COLLS[i].id)).append("\",\"name\":\"")
              .append(esc(COLLS[i].name)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append(' '); break;
                default:
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 随机池：把几个榜单混在一起，去重。给「不知道看什么」用。
     * 只取前 60 条，够随机了，也不至于把豆瓣刷爆。
     */
    public static List<Item> pool() {
        List<Item> all = new ArrayList<Item>();
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        String[] use = new String[]{"movie_top250", "movie_showing", "movie_real_time_hotest",
                "tv_real_time_hotest", "tv_chinese_best_weekly", "tv_global_best_weekly"};
        for (String c : use) {
            for (Item it : items(c, 40)) {
                String k = norm(it.name);
                if (k.length() == 0 || seen.containsKey(k)) continue;
                seen.put(k, Boolean.TRUE);
                all.add(it);
            }
        }
        return all;
    }

    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) sb.append(Character.toLowerCase(c));
            else if (c >= 0x4e00 && c <= 0x9fa5) sb.append(c);
        }
        return sb.toString();
    }
}
