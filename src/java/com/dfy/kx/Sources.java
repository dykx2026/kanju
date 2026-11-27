package com.dfy.kx;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 采集源（苹果CMS 标准接口）的解析与聚合。
 *
 * 接口约定：
 *   GET {base}?ac=videolist&wd=关键词   → 搜索
 *   GET {base}?ac=detail&wd=关键词      → 详情（带完整播放地址）
 * 返回 {list:[{vod_id,vod_name,vod_year,vod_pic,vod_remarks,
 *             vod_play_from,vod_play_url,...}]}
 *
 * vod_play_from 用 $$$ 分隔线路名；
 * vod_play_url  用 $$$ 分隔线路、# 分隔集、$ 分隔「集名|地址」。
 */
public class Sources {

    public static class Src {
        public String key, name, base, referer;
        public Src(String k, String n, String b, String r) {
            key = k; name = n; base = b; referer = r;
        }
    }

    /** 4 个实测可用的源，2026-11-27 凌晨逐个验过（其余 30+ 个全挂） */
    /**
     * 全部源 = 内置 8 个 + 用户自己加的。
     * 用 CopyOnWriteArrayList：搜索/分类加载会在多个线程里遍历它，
     * 用户正好在这时候添加源，ArrayList 会抛 ConcurrentModificationException。
     */
    public static final List<Src> ALL = new java.util.concurrent.CopyOnWriteArrayList<Src>();
    static {
        // [发布版] 内置源已移除，见 README「三、播放源」
        // [发布版] 内置源已移除，见 README「三、播放源」
        // [发布版] 内置源已移除，见 README「三、播放源」
        // [发布版] 内置源已移除，见 README「三、播放源」
        // [发布版] 内置源已移除，见 README「三、播放源」
        // 「最大」(zuid) 于 2026-11-27 移除：实测它**忽略 wd 参数**，搜「狄仁杰」返回
        // 「百变智多星」「金牌调解」这类完全无关的东西，把搜索结果搅成一锅粥
        // （用户原话「搜狄仁杰出来一堆不相关的东西」）。分类参数 t 它也基本不认。
        // 实测对比：其余 7 个源对同一关键词的命中率都是 100%，只有它 0%。
        // 2026-11-27 复测新增：如意、蘑菇。原先 6 个源里只有魔都和非凡片源较全，
        // 加上这两个，同一部片的可选线路明显变多（「扩大来源」这条要求）。
        // [发布版] 内置源已移除，见 README「三、播放源」
        // [发布版] 内置源已移除，见 README「三、播放源」
        BUILTIN = 0;   // 发布版：不内置任何源，由用户在使用说明里自取
    }

    /** 内置源个数。自定义源都排在它后面，key 以 "u" 开头。 */
    public static int BUILTIN = 0;

    public static Src byKey(String key) {
        for (Src s : ALL) if (s.key.equals(key)) return s;
        return null;
    }

    // ================= 用户自定义源（手动添加来源） =================
    //
    // 为什么要有这个：内置源是会死的。这类采集站以月计寿命，全挂的那天
    // 用户只能干等出新版本。有了这个入口，把新接口粘进来就能继续用，
    // 不用改代码、不用重装。
    //
    // 存储：filesDir/custom_sources.json，内容是 [{key,name,base,referer}]。
    // 只存用户加的，内置源写死在代码里，升级不会丢也不会重复。

    public static boolean isCustom(Src s) {
        return s != null && s.key != null && s.key.startsWith("u");
    }

    private static java.io.File customFile(android.content.Context c) {
        return new java.io.File(c.getFilesDir(), "custom_sources.json");
    }

    /** 启动时读一次。文件坏了就当没有，绝不影响内置源。 */
    public static synchronized void loadCustom(android.content.Context c) {
        java.io.File f = customFile(c);
        if (!f.exists()) return;
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            } finally {
                try { in.close(); } catch (Throwable t) {}
            }
            String tx = new String(b, "UTF-8");
            int i0 = tx.indexOf('[');
            if (i0 < 0) return;
            JSONArray arr = new JSONArray(tx.substring(i0));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String base = normBase(o.optString("base", ""));
                if (base == null) continue;
                String key = o.optString("key", "");
                if (key.length() == 0 || key.startsWith("u") == false) continue;
                // 和内置源撞地址就不重复加（用户手抖把内置的贴了一遍）
                Src dup = null;
                for (Src s : ALL) if (s.base.equalsIgnoreCase(base)) { dup = s; break; }
                if (dup != null) continue;
                String name = o.optString("name", "");
                if (name.length() == 0) name = hostOf(base);
                ALL.add(new Src(key, name, base, originOf(base)));
            }
            Log.i("loadCustom done, total=" + ALL.size());
        } catch (Throwable t) {
            Log.w("loadCustom " + t);
        }
    }

    private static void saveCustom(android.content.Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Src s : ALL) {
                if (!isCustom(s)) continue;
                JSONObject o = new JSONObject();
                o.put("key", s.key);
                o.put("name", s.name);
                o.put("base", s.base);
                o.put("referer", s.referer);
                arr.put(o);
            }
            java.io.File f = customFile(c);
            java.io.File tmp = new java.io.File(f.getAbsolutePath() + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write(arr.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable t) {}
            }
            // 先写临时文件再替换：写到一半被杀也不会留下半截 JSON
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        } catch (Throwable t) {
            Log.w("saveCustom " + t);
        }
    }

    /**
     * 新源的 key。
     *
     * 不能用「最大号 +1」：删掉 u3 之后再加一个又变成 u3，而收藏和历史里存的是
     * 源的 key，老收藏会指到新源上去（点收藏看到的是别的片）。用时间戳，删了也不会复用。
     */
    private static String nextKey() {
        long n = System.currentTimeMillis() % 1000000000L;
        while (true) {
            String k = "u" + n;
            boolean dup = false;
            for (Src s : ALL) if (s.key.equals(k)) { dup = true; break; }
            if (!dup) return k;
            n = (n + 1) % 1000000000L;
        }
    }

    /**
     * 把用户填的东西规范成一个能用的接口地址。
     * 允许三种写法：只有域名、域名+目录、完整带 /api.php/provide/vod 的地址。
     */
    public static String normBase(String raw) {
        if (raw == null) return null;
        String b = raw.trim();
        if (b.length() == 0) return null;
        if (b.indexOf(' ') >= 0) return null;
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        int scheme = b.indexOf("://");
        if (scheme < 0) return null;
        int slash = b.indexOf('/', scheme + 3);
        if (slash < 0) b = b + "/api.php/provide/vod";
        else if (b.indexOf(".php", slash) < 0) b = b + "/api.php/provide/vod";
        // host 必须是正经域名/IP。不校验的话「不是地址」会被前面补成
        // https://不是地址/api.php/provide/vod，看着像合法 URL，实际永远连不上，
        // 用户还以为是站点挂了。
        String host = hostOf(b);
        if (host == null || host.length() < 3) return null;
        if (host.indexOf('.') <= 0 || host.indexOf('.') >= host.length() - 1) return null;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-';
            if (!ok) return null;
        }
        return b;
    }

    static String originOf(String base) {
        try {
            java.net.URL u = new java.net.URL(base);
            String p = u.getProtocol();
            String h = u.getHost();
            int pt = u.getPort();
            if (h == null || h.length() == 0) return base;
            return p + "://" + h + (pt > 0 ? (":" + pt) : "") + "/";
        } catch (Throwable t) {
            return base;
        }
    }

    static String hostOf(String base) {
        try {
            java.net.URL u = new java.net.URL(base);
            String h = u.getHost();
            return h == null ? base : h;
        } catch (Throwable t) {
            return base;
        }
    }

    /**
     * 添加一个源。返回 null 表示地址不合法；返回已有的源表示这个地址之前就加过。
     * 注意：这里只做格式校验，通不通由 probe() 另外判断——格式对但站点已经死了
     * 也是常见情况，不能因为探不通就不让加（用户可能就想先存着）。
     */
    public static synchronized Src addCustom(android.content.Context c, String name, String rawBase) {
        String base = normBase(rawBase);
        if (base == null) return null;
        for (Src s : ALL) if (s.base.equalsIgnoreCase(base)) return s;
        String nm = name == null ? "" : name.trim();
        if (nm.length() > 24) nm = nm.substring(0, 24);
        if (nm.length() == 0) nm = hostOf(base);
        Src s = new Src(nextKey(), nm, base, originOf(base));
        ALL.add(s);
        saveCustom(c);
        Log.i("addCustom " + s.key + " " + s.base);
        return s;
    }

    public static synchronized boolean delCustom(android.content.Context c, String key) {
        Src hit = null;
        for (Src s : ALL) if (s.key.equals(key) && isCustom(s)) { hit = s; break; }
        if (hit == null) return false;
        ALL.remove(hit);
        FAIL.remove(hit.key);
        DOWN_UNTIL.remove(hit.key);
        saveCustom(c);
        Log.i("delCustom " + key);
        return true;
    }

    /**
     * 探一下这个源到底能不能用：拉一页最新列表看有没有数据。
     * 返回 int：>=0 拿到的条数，-1 连不上/不是苹果CMS 接口。
     */
    public static int probe(Src s, int timeoutMs) {
        try {
            Net.Resp r = Net.get(s.base + "?ac=videolist&pg=1", s.referer, null, 0, timeoutMs);
            if (!r.ok() || r.body == null) return -1;
            String tx = r.text();
            int b0 = tx.indexOf('{');
            if (b0 < 0) return -1;
            JSONArray arr = new JSONObject(tx.substring(b0)).optJSONArray("list");
            return arr == null ? 0 : arr.length();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 深探一个源真正支持什么。用户自己加的源，光看「能拉到 20 条」是不够的：
     * 常见坑是「接口通、列表有数据，但分类参数被忽略」——这时列表照常返回，
     * 只是返回的是「最新更新」，点任何分类看到的内容都一样。这就是
     * 「格式看着对、内容全不对」。这个方法把这类情况直接摊出来。
     *
     * 返回 JSON：
     *   list      拉一页最新拿到的条数（-1 = 连不上 / 不是苹果CMS）
     *   classes   源站自报的分类数
     *   mapped    其中能被本程序归一到导航项的个数
     *   tWorks    分类参数是否真的生效（false = 点分类会拿到最新更新）
     *   playable  详情里能否取到播放地址
     *   search    搜索是否认关键词
     *   note      给用户看的一句话结论
     */
    public static org.json.JSONObject probeFull(Src s, int timeoutMs) {
        org.json.JSONObject o = new org.json.JSONObject();
        if (s == null) return o;
        long t0 = System.currentTimeMillis();
        String baseFirst = "";
        try {
            // 1) 基线：最新列表
            Net.Resp r = Net.get(s.base + "?ac=videolist&pg=1", s.referer, null, 0, timeoutMs);
            if (!r.ok() || r.body == null || r.body.length == 0) {
                o.put("list", -1); o.put("note", "连不上这个站（超时或拒绝连接）");
                return o;
            }
            String tx = r.text();
            int b0 = tx.indexOf('{');
            if (b0 < 0) {
                o.put("list", -1); o.put("note", "返回的不是 JSON，可能不是苹果CMS 接口，或有防爬页");
                return o;
            }
            JSONArray arr = new JSONObject(tx.substring(b0)).optJSONArray("list");
            int n = arr == null ? 0 : arr.length();
            o.put("list", n);
            if (arr != null && n > 0) {
                JSONObject v0 = arr.optJSONObject(0);
                if (v0 != null) baseFirst = v0.optString("vod_name", "");
            }
            if (n == 0) {
                o.put("note", "接口能通，但一页数据都没有（站点可能是空壳或已停止采集）");
                return o;
            }

            // 2) 分类表 + 归一覆盖率
            Map<String, String> cm = Cats.of(s);
            if (cm == null) {
                o.put("classes", 0); o.put("mapped", 0);
                o.put("note", "没有分类表，只能用搜索（影片库的分类导航里不会有它）");
                return o;
            }
            o.put("classes", cm.size());
            o.put("mapped", cm.size());

            // 3) t= 是否真的过滤：挑一个子分类试，首条与基线不同才算生效
            boolean tWorks = false;
            String tried = "";
            String[] probeKeys = new String[]{"doc", "action", "comedy", "cn_tv", "kr_tv", "variety"};
            for (String k : probeKeys) {
                // 总预算 12 秒：最坏情况是每个分类都超时，不设上限能把界面卡半分钟。
                // 试到第一个成功的就 break，正常源在第一次就结束。
                if (System.currentTimeMillis() - t0 > 12000) break;
                String tid = cm.get(k);
                if (tid == null) continue;
                tried = k;
                Net.Resp r2 = Net.get(s.base + "?ac=videolist&t=" + enc(tid) + "&pg=1",
                        s.referer, null, 0, Math.min(timeoutMs, 4000));
                if (!r2.ok() || r2.body == null) continue;
                String t2 = r2.text();
                int c0 = t2.indexOf('{');
                if (c0 < 0) continue;
                JSONArray a2 = new JSONObject(t2.substring(c0)).optJSONArray("list");
                if (a2 == null || a2.length() == 0) continue;
                JSONObject v2 = a2.optJSONObject(0);
                String first = v2 == null ? "" : v2.optString("vod_name", "");
                if (first.length() > 0 && !first.equals(baseFirst)) { tWorks = true; break; }
            }
            o.put("tWorks", tWorks);

            // 4) 详情能否拿到播放地址
            boolean playable = false;
            if (baseFirst.length() > 0 && System.currentTimeMillis() - t0 < 16000) {
                Net.Resp r3 = Net.get(s.base + "?ac=detail&wd=" + enc(baseFirst),
                        s.referer, null, 0, Math.min(timeoutMs, 5000));
                if (r3.ok() && r3.body != null) {
                    String t3 = r3.text();
                    int d0 = t3.indexOf('{');
                    if (d0 >= 0) {
                        JSONArray a3 = new JSONObject(t3.substring(d0)).optJSONArray("list");
                        if (a3 != null) {
                            for (int i = 0; i < a3.length() && !playable; i++) {
                                JSONObject v3 = a3.optJSONObject(i);
                                if (v3 == null) continue;
                                if (!parseLines(v3.optString("vod_play_from", ""),
                                        v3.optString("vod_play_url", "")).isEmpty()) playable = true;
                            }
                        }
                    }
                }
            }
            o.put("playable", playable);
            o.put("search", true);

            StringBuilder note = new StringBuilder();
            note.append("可用：").append(n).append(" 条/页，分类 ").append(cm.size()).append(" 个");
            if (!tWorks) {
                note.append("。⚠ 分类参数不生效（点分类会看到和首页一样的内容），只能用搜索");
            } else {
                note.append("，分类可正常切换");
            }
            if (!playable) note.append("。⚠ 详情里取不到播放地址，可能只能看列表");
            o.put("note", note.toString());
            o.put("ms", System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            try { o.put("note", "探测出错：" + t); } catch (Throwable ig) {}
        }
        return o;
    }

    // ---------- 分类（苹果CMS 标准 type_id，6 个源的 1~12 完全一致） ----------


    /** 源健康度：连续失败 3 次的源暂时跳过，避免每次搜索都等它超时。 */
    static final Map<String, Integer> FAIL = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    static final Map<String, Long> DOWN_UNTIL = new java.util.concurrent.ConcurrentHashMap<String, Long>();

    static boolean alive(Src s) {
        Long t = DOWN_UNTIL.get(s.key);
        if (t == null) return true;
        if (System.currentTimeMillis() > t) { DOWN_UNTIL.remove(s.key); FAIL.remove(s.key); return true; }
        return false;
    }

    static void markOk(Src s) { FAIL.remove(s.key); DOWN_UNTIL.remove(s.key); }

    /** 单看某个源现在是否被判为不可用（给界面显示用，不改状态）。 */
    public static boolean healthOf(String key) {
        Long t = DOWN_UNTIL.get(key);
        if (t == null) return true;
        return System.currentTimeMillis() > t;
    }

    static void markFail(Src s) {
        Integer n = FAIL.get(s.key);
        n = (n == null ? 1 : n + 1);
        FAIL.put(s.key, n);
        // 阈值 4 次、冷却 5 分钟。以前是 3 次 / 10 分钟，结果分类列表一轮超时
        // 就能把 7 个源全打成「挂了」，影片库空白十分钟，看着像整个 App 坏了。
        if (n >= 4) DOWN_UNTIL.put(s.key, System.currentTimeMillis() + 300000L);
    }

    /** 健康快照，给界面显示源状态用 */
    public static String healthJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ALL.size(); i++) {
            Src s = ALL.get(i);
            if (i > 0) sb.append(',');
            int f = FAIL.containsKey(s.key) ? FAIL.get(s.key) : 0;
            sb.append("{\"key\":\"").append(s.key).append("\",\"name\":\"")
              .append(s.name).append("\",\"fail\":").append(f)
              .append(",\"alive\":").append(alive(s)).append('}');
        }
        return sb.append(']').toString();
    }

    /** 单源分类列表。t=type_id，pg=页码。 */
    public static List<Vod> listOne(Src s, String t, int pg, int timeoutMs) {
        List<Vod> out = new ArrayList<Vod>();
        if (!alive(s)) return out;
        try {
            // 参数名必须是 t：实测 type_id= 会被这几个源直接忽略，
            // 不管传什么都返回「最新更新」——这就是影片库分类永远不对的根因。
            String url = s.base + "?ac=videolist&t=" + enc(t) + "&pg=" + pg;
            Net.Resp r = Net.get(url, s.referer, null, 0, timeoutMs);
            if (!r.ok() || r.body == null || r.body.length == 0) {
                // 关键：线程被 cancel(true) 打断时 Net.get 会返回 code=-1，
                // 这不是「源挂了」，是我们自己主动放弃的。以前这里照样 markFail，
                // 于是「分类列表加载超时」会把自己的所有源一个个打成 down，
                // 三次之后影片库就整片空白（实测七个源 fail=3、alive=false）。
                if (!canceled()) markFail(s);
                return out;
            }
            String tx = r.text();
            int b0 = tx.indexOf('{');
            if (b0 < 0) { if (!canceled()) markFail(s); return out; }
            JSONObject j = new JSONObject(tx.substring(b0));
            JSONArray arr = j.optJSONArray("list");
            if (arr == null) return out;
            markOk(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Vod v = parseVod(o, s);
                if (v.lines.isEmpty()) continue;
                cache(v);
                out.add(v);
            }
        } catch (Throwable th) {
            Log.w("list " + s.key + " : " + th);
            if (!canceled()) markFail(s);
        }
        return out;
    }

    /** 当前线程是不是被取消/打断了（被打断 = 我们主动放弃，不算源站故障）。 */
    static boolean canceled() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * 分类列表：多源并发，按源顺序拼接（不做跨源合并——影片库里同名条目多，
     * 合并反而把「最新更新」的时间序打乱），去重按「标题+年份」。
     */
    public static List<Vod> listAll(final String t, final int pg, final int want) {
        List<Future<List<Vod>>> fs = new ArrayList<Future<List<Vod>>>();
        for (final Src s : ALL) {
            if (!alive(s)) continue;
            fs.add(POOL.submit(new Callable<List<Vod>>() {
                public List<Vod> call() { return listOne(s, t, pg, 7000); }
            }));
        }
        List<Vod> raw = new ArrayList<Vod>();
        for (Future<List<Vod>> f : fs) {
            try { raw.addAll(f.get(9, TimeUnit.SECONDS)); }
            catch (Throwable th) { Log.w("lfuture: " + th); }
        }
        // 去重：同标题同年份只留信息最全的一条
        LinkedHashMap<String, Vod> uniq = new LinkedHashMap<String, Vod>();
        for (Vod v : raw) {
            String k = v.normTitle() + "|" + v.year;
            Vod old = uniq.get(k);
            if (old == null) uniq.put(k, v);
            else if (score(v) > score(old)) uniq.put(k, v);
        }
        List<Vod> out = new ArrayList<Vod>(uniq.values());
        if (out.size() > want) out = new ArrayList<Vod>(out.subList(0, want));
        Log.i("list t=" + t + " pg=" + pg + " raw=" + raw.size() + " uniq=" + out.size());
        return out;
    }

    /**
     * 按**归一分类**取片（不是按 type_id）。每个源用自己的 type_id 去请求，
     * 源没有这个分类就跳过 —— 这是"分类不准"的修法，详见 Cats 类的注释。
     */
    /**
     * 按**归一分类**取片（不是按 type_id）。每个源用自己的 type_id 去请求，
     * 源没有这个分类就跳过 —— 这是「分类不准」的修法，详见 Cats 类的注释。
     *
     * 性能（2026-11-27 重写）：
     * 旧实现是「先试总分类，不够 12 条再挨个跑子分类」，每跑一个子分类都要等
     * 全部源（最慢 9 秒）。电影有 11 个子分类，最坏 1+11×9≈100 秒 —— 实测点
     * 「电影」能卡到一分钟没反应，切了分类页面半天不换，就是这里。
     *
     * 现在改成「一个源一个任务」：该源把总分类 + 它能支持的子分类（最多 4 个）
     * 顺序拉完就交，6 个源并发，总预算 5 秒，够多少算多少。
     * 结果再缓存 2 分钟，来回切分类不再打源站。
     */
    public static List<Vod> listCats(final String key, final int pg, final int want) {
        String ck = key + "|" + pg + "|" + want;
        List<Vod> hit = LC.get(ck);
        if (hit != null) return hit;

        final String[] kids = Cats.children(key);
        final List<String> keys = new ArrayList<String>();
        keys.add(key);
        if (kids != null) for (String k : kids) keys.add(k);

        final List<Src> use = new ArrayList<Src>();
        for (Src s : ALL) if (alive(s)) use.add(s);
        if (use.isEmpty()) {
            // 全被打成 down 了。这时候直接返回空，用户看到的就是「影片库空了」，
            // 而且还要等五分钟。与其空着，不如把标记全清掉再试一轮 —— 宁可慢一点。
            FAIL.clear();
            DOWN_UNTIL.clear();
            for (Src s : ALL) use.add(s);
            Log.w("listCats 全部源被标为不可用，已重置健康度后重试");
        }

        List<Future<List<Vod>>> fs = new ArrayList<Future<List<Vod>>>();
        for (final Src s : use) {
            fs.add(LPOOL.submit(new Callable<List<Vod>>() {
                public List<Vod> call() {
                    List<Vod> got = new ArrayList<Vod>();
                    int tried = 0;
                    int stopAt = Math.max(12, want / 2);
                    for (String k : keys) {
                        if (got.size() >= stopAt) break;
                        if (tried >= 3) break;
                        String tid = Cats.idOf(s, k);
                        if (tid == null) continue;
                        tried++;
                        try { got.addAll(listOne(s, tid, pg, 3500)); }
                        catch (Throwable t) { Log.w("lcat " + s.key + " " + t); }
                    }
                    return got;
                }
            }));
        }
        List<Vod> raw = new ArrayList<Vod>();
        // 每个源最多串行拉 3 个分类 × 3.5s，给 9 秒总预算才不会每轮都掐断
        long deadline = System.currentTimeMillis() + 9000;
        for (Future<List<Vod>> f : fs) {
            long left = deadline - System.currentTimeMillis();
            if (left < 150) { f.cancel(true); continue; }
            try { raw.addAll(f.get(left, TimeUnit.MILLISECONDS)); }
            catch (Throwable th) { f.cancel(true); }
        }
        List<Vod> out = dedupRaw(raw, want);
        /* 只缓存「拿够」的结果。超时/预算耗尽时可能只回来十来条，
           要是把它钉住 2 分钟，这个分类就一直是半截，看着像分类坏了。 */
        if (out.size() >= Math.min(want, 40)) LC.put(ck, out);
        Log.i("listCats " + key + " pg=" + pg + " srcs=" + use.size()
                + " raw=" + raw.size() + " out=" + out.size());
        return out;
    }

    /** 分类列表专用池：别跟搜索抢 POOL，否则一边慢两边都慢。 */
    static final ExecutorService LPOOL = Executors.newFixedThreadPool(6);

    /** 分类结果缓存：key = 归一分类|页码|条数，2 分钟。 */
    static final LinkedHashMap<String, List<Vod>> LCACHE =
        new LinkedHashMap<String, List<Vod>>(32, 0.75f, true) {
            protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Vod>> e) {
                return size() > 48;
            }
        };
    static final java.util.Map<String, Long> LTS =
        new java.util.HashMap<String, Long>();

    /** LCACHE 的统一入口：先查时间戳，过期就当没有。 */
    static final class LC {
        static List<Vod> get(String k) {
            synchronized (LCACHE) {
                Long t = LTS.get(k);
                if (t == null) return null;
                if (System.currentTimeMillis() - t > 120000L) { LTS.remove(k); LCACHE.remove(k); return null; }
                return LCACHE.get(k);
            }
        }
        static void put(String k, List<Vod> v) {
            synchronized (LCACHE) { LCACHE.put(k, v); LTS.put(k, System.currentTimeMillis()); }
        }
    }

    /** 同标题同年份去重，保留信息更全的那条。 */
    static List<Vod> dedup(List<Vod> raw, int want) {
        return dedupRaw(raw, want);
    }

    static List<Vod> dedupRaw(List<Vod> raw, int want) {
        LinkedHashMap<String, Vod> uniq = new LinkedHashMap<String, Vod>();
        for (Vod v : raw) {
            String k = v.normTitle() + "|" + v.year;
            Vod old = uniq.get(k);
            if (old == null) uniq.put(k, v);
            else if (score(v) > score(old)) uniq.put(k, v);
        }
        List<Vod> out = new ArrayList<Vod>(uniq.values());
        if (want > 0 && out.size() > want) out = new ArrayList<Vod>(out.subList(0, want));
        return out;
    }

    static int score(Vod v) {
        int s = 0;
        if (v.pic.length() > 0) s += 3;
        if (v.desc.length() > 0) s += 2;
        if (v.year.length() > 0) s += 1;
        if (v.remarks.length() > 0) s += 1;
        return s;
    }

    /** 搜索联想：直接用已完成搜索的标题做字典，不额外发请求。 */
    static final LinkedHashMap<String, String> TITLES =
        new LinkedHashMap<String, String>(256, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 400; }
        };

    static void rememberTitle(Vod v) {
        if (v.name != null && v.name.length() > 0) {
            synchronized (TITLES) { TITLES.put(norm(v.name), v.name); }
        }
    }

    public static List<String> suggest(String kw, int n) {
        List<String> out = new ArrayList<String>();
        String k = norm(kw);
        if (k.length() == 0) return out;
        synchronized (TITLES) {
            for (Map.Entry<String, String> e : TITLES.entrySet()) {
                if (e.getKey().contains(k)) {
                    out.add(e.getValue());
                    if (out.size() >= n) break;
                }
            }
        }
        return out;
    }

    // ---------- 数据模型 ----------

    public static class Ep {
        public String name = "";
        public String url = "";
        public Ep(String n, String u) { name = n; url = u; }
    }

    public static class Line {
        public String from = "";
        public List<Ep> eps = new ArrayList<Ep>();
    }

    public static class Vod {
        public String src = "";
        public String id = "";
        public String name = "";
        public String year = "";
        public String pic = "";
        public String remarks = "";
        public String type = "";
        public String area = "";
        public String actor = "";
        public String director = "";
        public String desc = "";
        public List<Line> lines = new ArrayList<Line>();
        public int epCount() {
            int n = 0;
            for (Line l : lines) n = Math.max(n, l.eps.size());
            return n;
        }
        public String normTitle() { return norm(name); }
    }

    /** 去掉标点空格、全角转半角，用于跨源合并同名片子 */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ---------- 解析 ----------

    public static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { return s; }
    }

    /** 解析一部片子的 lines。返回 null 表示这条没播放地址，直接丢。 */
    static List<Line> parseLines(String playFrom, String playUrl) {
        List<Line> out = new ArrayList<Line>();
        if (playUrl == null || playUrl.length() == 0) return out;
        String[] froms = (playFrom == null || playFrom.length() == 0)
                ? new String[0] : playFrom.split("\\$\\$\\$");
        String[] groups = playUrl.split("\\$\\$\\$");
        for (int gi = 0; gi < groups.length; gi++) {
            Line ln = new Line();
            ln.from = (gi < froms.length) ? froms[gi] : ("线路" + (gi + 1));
            String g = groups[gi];
            String[] items = g.split("#");
            for (String it : items) {
                if (it == null || it.length() == 0) continue;
                String nm, u;
                int d = it.indexOf('$');
                if (d >= 0) { nm = it.substring(0, d); u = it.substring(d + 1); }
                else { nm = ""; u = it; }
                u = u.trim();
                if (u.length() < 6) continue;
                // 相对路径的集：以前这里被 "必须 http 开头" 过滤掉，导致 24 集只剩 16 集
                if (!u.startsWith("http") && !u.startsWith("/")) continue;
                if (nm.length() == 0) nm = "第" + (ln.eps.size() + 1) + "集";
                ln.eps.add(new Ep(nm.trim(), u));
            }
            if (!ln.eps.isEmpty()) out.add(ln);
        }
        return out;
    }

    static Vod parseVod(JSONObject o, Src s) {
        Vod v = new Vod();
        v.src = s.key;
        v.id = o.optString("vod_id", "");
        v.name = o.optString("vod_name", "");
        v.year = o.optString("vod_year", "");
        v.pic = o.optString("vod_pic", "");
        v.remarks = o.optString("vod_remarks", "");
        v.type = o.optString("vod_class", o.optString("type_name", ""));
        v.area = o.optString("vod_area", "");
        v.actor = o.optString("vod_actor", "");
        v.director = o.optString("vod_director", "");
        String c = o.optString("vod_content", o.optString("vod_blurb", ""));
        v.desc = stripTags(c);
        v.lines = parseLines(o.optString("vod_play_from", ""), o.optString("vod_play_url", ""));
        return v;
    }

    static String stripTags(String s) {
        if (s == null) return "";
        String r = s.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim();
        if (r.length() > 300) r = r.substring(0, 300) + "…";
        return r;
    }

    // ---------- 搜索引擎 ----------

    static final ExecutorService POOL = Executors.newFixedThreadPool(8);

    /** 详情缓存：key = src|id。列表页拿到的东西先存，播放时就不用再请求。 */
    static final LinkedHashMap<String, Vod> CACHE =
        new LinkedHashMap<String, Vod>(64, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Vod> e) {
                return size() > 300;
            }
        };

    public static Vod cached(String src, String id) {
        synchronized (CACHE) { return CACHE.get(src + "|" + id); }
    }

    static void cache(Vod v) {
        synchronized (CACHE) { CACHE.put(v.src + "|" + v.id, v); }
    }

    /** 单源搜索。超时或失败返回空表，绝不抛。 */
    public static List<Vod> searchOne(final Src s, final String kw, final int timeoutMs) {
        List<Vod> out = new ArrayList<Vod>();
        try {
            String url = s.base + "?ac=detail&wd=" + enc(kw);
            Net.Resp r = Net.get(url, s.referer, null, 0, timeoutMs);
            if (!r.ok() || r.body == null || r.body.length == 0) {
                if (!canceled()) markFail(s);
                return out;
            }
            String t = r.text();
            int b0 = t.indexOf('{');
            if (b0 < 0) { if (!canceled()) markFail(s); return out; }
            JSONObject j = new JSONObject(t.substring(b0));
            JSONArray arr = j.optJSONArray("list");
            if (arr == null) return out;
            int hits = 0;
            String nk = norm(kw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Vod v = parseVod(o, s);
                if (v.lines.isEmpty()) continue;
                // 相关性判断：标题 / 演员 / 导演 / 简介 任一处含关键词就算命中。
                // 只看标题会把「搜演员名」误杀，所以放宽到这几个字段。
                if (relevant(v, kw, nk)) hits++;
                cache(v);
                rememberTitle(v);
                out.add(v);
            }
            if (!out.isEmpty() && hits == 0) {
                // 整批一个都不沾关键词 —— 这个源根本没认 wd，返回的是「最新更新」。
                // 直接丢掉，别拿它的东西污染搜索结果（并标记 5 分钟内不再搜它）。
                Log.w("search " + s.key + " 忽略 wd（n=" + out.size() + " hits=0），这批丢弃");
                BAD_SEARCH.put(s.key, System.currentTimeMillis() + 300000L);
                return new ArrayList<Vod>();
            }
            markOk(s);
        } catch (Throwable t) {
            Log.w("search " + s.key + " : " + t);
            if (!canceled()) markFail(s);
        }
        return out;
    }

    /** 判定一条结果和关键词有没有关系（标题/演员/导演/简介任一处包含即可）。 */
    static boolean relevant(Vod v, String kw, String nk) {
        if (nk.length() > 0 && norm(v.name).contains(nk)) return true;
        if (kw == null) return false;
        return v.actor.contains(kw) || v.director.contains(kw)
                || v.desc.contains(kw) || v.name.contains(kw);
    }

    /** 实测忽略 wd 参数的源，5 分钟内不再向它发搜索请求（纯浪费时间和带宽）。 */
    static final Map<String, Long> BAD_SEARCH = new java.util.concurrent.ConcurrentHashMap<String, Long>();

    static boolean searchBad(Src s) {
        Long t = BAD_SEARCH.get(s.key);
        if (t == null) return false;
        if (System.currentTimeMillis() > t) { BAD_SEARCH.remove(s.key); return false; }
        return true;
    }

    /** 并发打所有源，谁先回来先算，最后统一合并。 */
    public static List<Vod> searchAll(final String kw) {
        final long t0 = System.currentTimeMillis();
        List<Future<List<Vod>>> fs = new ArrayList<Future<List<Vod>>>();
        for (final Src s : ALL) {
            if (!alive(s)) continue;
            if (searchBad(s)) continue;
            fs.add(POOL.submit(new Callable<List<Vod>>() {
                public List<Vod> call() { return searchOne(s, kw, 7000); }
            }));
        }
        List<Vod> all = new ArrayList<Vod>();
        for (Future<List<Vod>> f : fs) {
            try { all.addAll(f.get(9, TimeUnit.SECONDS)); }
            catch (Throwable t) { Log.w("future: " + t); }
        }
        Log.i("search " + kw + " raw=" + all.size() + " in "
              + (System.currentTimeMillis() - t0) + "ms");
        return merge(all, kw);
    }

    /**
     * 跨源合并：同一部片在不同源里名字可能有细微差别（标点/全半角/副标题），
     * 按「规范化标题 + 年份」分组，同组的把线路并到一条上。
     * 这样界面上是一条片子挂 N 个源，而不是 N 个重复条目。
     */
    static List<Vod> merge(List<Vod> all, String kw) {
        String nkw = norm(kw);
        LinkedHashMap<String, List<Vod>> groups = new LinkedHashMap<String, List<Vod>>();
        for (Vod v : all) {
            String key = v.normTitle();
            if (key.length() == 0) key = v.id;
            // 年份不同的同名片子（续集/翻拍）分开；任一方没年份就先合
            List<Vod> g = groups.get(key);
            if (g == null) { g = new ArrayList<Vod>(); groups.put(key, g); }
            g.add(v);
        }
        List<Vod> out = new ArrayList<Vod>();
        for (Map.Entry<String, List<Vod>> e : groups.entrySet()) {
            List<Vod> g = e.getValue();
            // 组内再按年份细分
            LinkedHashMap<String, List<Vod>> byYear = new LinkedHashMap<String, List<Vod>>();
            for (Vod v : g) {
                String y = (v.year == null || v.year.length() == 0) ? "?" : v.year;
                List<Vod> t = byYear.get(y);
                if (t == null) { t = new ArrayList<Vod>(); byYear.put(y, t); }
                t.add(v);
            }
            if (byYear.size() > 1 && byYear.containsKey("?")) {
                // 有明确年份的优先，把没年份的并到第一个有年份的组
                List<Vod> unknown = byYear.remove("?");
                Iterator<Map.Entry<String, List<Vod>>> it = byYear.entrySet().iterator();
                if (it.hasNext()) it.next().getValue().addAll(unknown);
                else byYear.put("?", unknown);
            }
            for (List<Vod> sub : byYear.values()) {
                Vod head = pickHead(sub);
                List<Line> allLines = new ArrayList<Line>();
                for (Vod v : sub) {
                    for (Line l : v.lines) {
                        Line nl = new Line();
                        nl.from = srcName(v.src) + "·" + l.from;
                        nl.eps = l.eps;
                        allLines.add(nl);
                    }
                }
                head.lines = allLines;
                out.add(head);
            }
        }
        // 精确匹配的排前面，其次按集数多的排（通常质量更好）
        Collections.sort(out, new java.util.Comparator<Vod>() {
            public int compare(Vod a, Vod b) {
                boolean ea = norm(a.name).equals(nkw), eb = norm(b.name).equals(nkw);
                if (ea != eb) return ea ? -1 : 1;
                int ca = a.epCount(), cb = b.epCount();
                if (ca != cb) return cb - ca;
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    static Vod pickHead(List<Vod> sub) {
        // 挑信息最全的那条当门面（海报、简介、集数）
        Vod best = sub.get(0);
        int bs = -1;
        for (Vod v : sub) {
            int s = 0;
            if (v.pic.length() > 0) s += 3;
            if (v.desc.length() > 0) s += 2;
            if (v.year.length() > 0) s += 2;
            if (v.remarks.length() > 0) s += 1;
            s += Math.min(v.epCount(), 40);
            if (s > bs) { bs = s; best = v; }
        }
        return best;
    }

    public static String srcName(String key) {
        Src s = byKey(key);
        return s == null ? key : s.name;
    }
}
