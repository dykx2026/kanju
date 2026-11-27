package com.dfy.kx;

import android.content.res.AssetManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本地 HTTP 服务器（127.0.0.1:8686）。
 *
 * 存在的理由：网页版要绕过采集站防盗链，必须有个能自带 Referer 的中转；
 * 同时把网页、接口、分片代理放在同一个源上，hls.js 就不会撞跨域。
 * 安卓上 App 自监听回环不需要任何权限，也不触发防火墙。
 */
public class CoreServer {

    public static final int PORT = 8686;

    private final AssetManager assets;
    private ServerSocket ss;
    private volatile boolean running = false;
    /**
     * 请求处理池。
     *
     * 原来是 newFixedThreadPool(24)：投屏时电视同时拉十几条分片，每条分片又要从
     * 源站慢慢读（实测单连接就一百多 KB/s），24 个线程很快全卡在 read 上。
     * 结果是新的请求（包括页面自己刷新）全部排队，整个 App 看起来就像死了。
     * 换成可伸缩池：平时才几个线程，被分片占满时自动开新的，最多 96 条。
     */
    private final ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(
            8, 128, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(512),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private final android.content.Context ctx;

    /**
     * 投屏 token。
     *
     * 服务本身监听 0.0.0.0（不是只绑回环）——因为电视要能访问到手机的
     * 局域网地址。为了不让同一 WiFi 下别人乱翻，投屏路径必须带这串
     * 随机 token，没带一律 403。
     */
    static volatile String CAST_TOKEN = null;

    public CoreServer(android.content.Context c, AssetManager am) {
        this.ctx = c;
        this.assets = am;
    }

    public int start() {
        try {
            // 绑所有网卡：投屏时电视要能访问手机的这个端口。
                // 安全靠下面的「非本机只放行 /cast/」+ token，而不是靠绑回环。
                ss = new ServerSocket(PORT, 80);
            running = true;
            Thread t = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "core-accept");
            t.setDaemon(true);
            t.start();
            Log.i("server up on " + PORT);
            return PORT;
        } catch (Throwable e) {
            Log.e("server start fail: " + e);
            return -1;
        }
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Throwable t) {}
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = ss.accept();
                s.setSoTimeout(30000);
                s.setTcpNoDelay(true);
                try {
                    pool.submit(new Runnable() {
                        public void run() { handle(s); }
                    });
                } catch (Throwable rej) {
                    // 线程和队列都满了：明确拒绝这一条，绝不能让 accept 线程被拖住
                    //（旧的 CallerRunsPolicy 就是在这里把整个服务器拖死的）
                    Log.w("pool busy, reject: " + rej);
                    try { s.close(); } catch (Throwable t2) {}
                }
            } catch (Throwable t) {
                if (running) Log.w("accept: " + t);
            }
        }
    }

    // ---------- 请求处理 ----------

    static class Req {
        boolean fromLocal = true;
        String method = "GET";
        String path = "/";
        String query = "";
        String range = "";
        Map<String, String> headers = new HashMap<String, String>();
        Map<String, String> q = new HashMap<String, String>();
    }

    static Req parse(String head) {
        Req r = new Req();
        try {
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length >= 2) {
                r.method = first[0];
                String t = first[1];
                int qi = t.indexOf('?');
                if (qi >= 0) { r.path = t.substring(0, qi); r.query = t.substring(qi + 1); }
                else r.path = t;
            }
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    String k = lines[i].substring(0, c).trim().toLowerCase();
                    String v = lines[i].substring(c + 1).trim();
                    r.headers.put(k, v);
                }
            }
            r.range = r.headers.containsKey("range") ? r.headers.get("range") : "";
            if (r.query.length() > 0) {
                for (String kv : r.query.split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) r.q.put(kv.substring(0, e), dec(kv.substring(e + 1)));
                    else if (e < 0) r.q.put(kv, "");
                }
            }
        } catch (Throwable t) {
            Log.w("parse: " + t);
        }
        return r;
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Throwable t) { return s; }
    }

    private void handle(Socket s) {
        OutputStream out = null;
        try {
            InputStream in = s.getInputStream();
            // 只读请求头（我们只处理 GET，没有 body）
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int b, nl = 0;
            while ((b = in.read()) >= 0) {
                hb.write(b);
                if (b == '\n') { nl++; if (nl == 2) break; } else if (b != '\r') nl = 0;
                if (hb.size() > 16384) break;
            }
            Req r = parse(new String(hb.toByteArray(), "UTF-8"));
            try { r.fromLocal = s.getInetAddress().isLoopbackAddress(); }
            catch (Throwable t3) { r.fromLocal = false; }
            out = new BufferedOutputStream(s.getOutputStream(), 65536);
            route(r, out);
            out.flush();
        } catch (Throwable t) {
            Log.w("STACKLOG " + android.util.Log.getStackTraceString(t));
        } finally {
            try { if (out != null) out.close(); } catch (Throwable t) {}
            try { s.close(); } catch (Throwable t) {}
        }
    }

    private void route(Req r, OutputStream out) throws Exception {
        String p = r.path;
        // 来自局域网（不是本机）的请求只允许投屏相关路径，别的一律 403。
        // 否则同一 WiFi 下任何人都能调 /api/dl/add、/api/search 这些接口。
        if (!r.fromLocal) {
            if (!p.startsWith("/cast/")) {
                send(out, 403, "text/plain", "forbidden".getBytes("UTF-8"), null);
                return;
            }
        }
        if (p.equals("/") || p.equals("/index.html")) { asset("index.html", "text/html; charset=utf-8", out); return; }
        if (p.equals("/hls.min.js")) { asset("hls.min.js", "application/javascript; charset=utf-8", out); return; }
        // 播放前的等待页图片（用户指定）。这里漏注册过，页面里就是个空 img。
        if (p.equals("/loading.jpg")) { asset("loading.jpg", "image/jpeg", out); return; }
        if (p.equals("/api/sources")) { apiSources(out); return; }
        // 自定义源（用户手动添加的来源）
        if (p.equals("/api/src/add")) { apiSrcAdd(r, out); return; }
        if (p.equals("/api/src/del")) { apiSrcDel(r, out); return; }
        if (p.equals("/api/src/check")) { apiSrcCheck(r, out); return; }
        if (p.equals("/api/search")) { apiSearch(r, out); return; }
        if (p.equals("/api/cats")) { apiCats(out); return; }
        if (p.equals("/api/list")) { apiList(r, out); return; }
        if (p.equals("/api/suggest")) { apiSuggest(r, out); return; }
        if (p.equals("/api/health")) { sendJson(out, "{\"sources\":" + Sources.healthJson() + "}"); return; }
        if (p.equals("/api/dl/add")) { apiDlAdd(r, out); return; }
        if (p.equals("/api/dl/list")) { apiDlList(out); return; }
        if (p.equals("/api/dl/del")) { apiDlDel(r, out); return; }
        if (p.equals("/api/dl/retry")) { apiDlRetry(r, out); return; }
        if (p.equals("/local")) { localPlaylist(r, out); return; }
        if (p.equals("/localseg")) { localSeg(r, out); return; }
        if (p.equals("/cast/local")) { castLocal(r, out); return; }
        if (p.equals("/cast/seg")) { castSeg(r, out); return; }
        if (p.equals("/cast/proxy")) { castProxy(r, out); return; }
        if (p.equals("/api/rank/colls")) { sendJson(out, Douban.collsJson()); return; }
        if (p.equals("/api/rank")) { apiRank(r, out); return; }
        if (p.equals("/api/random")) { apiRandom(r, out); return; }
        if (p.equals("/api/cast/state")) { apiCastState(out); return; }
        if (p.equals("/api/cast/ctl")) { apiCastCtl(r, out); return; }
        if (p.equals("/api/cast/url")) { apiCastUrl(r, out); return; }
        if (p.equals("/api/play")) { apiPlay(r, out); return; }
        if (p.equals("/api/stat")) { apiStat(out); return; }
        // ── 调试用：无障碍节点树 / 设备元数据（给接手维护的人）──
        if (p.equals("/api/device")) { sendJson(out, Device.json(ctx)); return; }
        if (p.equals("/api/a11y/state")) { apiA11yState(out); return; }
        if (p.equals("/api/a11y/dump")) { apiA11yDump(r, out); return; }
        if (p.equals("/api/a11y/tap")) { apiA11yTap(r, out); return; }
        if (p.equals("/api/a11y/scroll")) { apiA11yScroll(r, out); return; }
        if (p.equals("/api/dom")) { apiDom(r, out); return; }
        if (p.equals("/img")) { img(r, out); return; }
        if (p.equals("/api/cast/url2")) { apiCastUrl2(r, out); return; }
        if (p.equals("/cast/single")) { castSingle(r, out); return; }
        if (p.equals("/proxy")) { proxy(r, out); return; }
        send(out, 404, "text/plain; charset=utf-8", "not found".getBytes("UTF-8"), null);
    }

    /**
     * 图片代理 /img?u=<图片地址>&r=<Referer>
     *
     * 为什么需要它：封面图挂的是采集站的域名，直连有三个毛病——
     *   1. 有的站校验 Referer，直连返回 403，界面上就是一片灰块（「封面老失败」）；
     *   2. 没有本地缓存，每次进列表都重新下几十张，弱网下要等很久（「封面特别慢」）；
     *   3. 几十张图同时请求会把手机的连接数占满，连页面自己的接口都排不上队。
     * 走这里：带 Referer 取一次、落 DiskCache、之后本地毫秒级命中。
     */
    /**
     * 目标地址是不是本机/内网。这类地址对「抓源站图片、代理分片」没有任何正当用途，
     * 但可以被本机其它 App 借 /proxy、/img 当跳板去摸内网和本机接口（SSRF）。
     * 一律拒掉，代价是这类源站（极少数）取不到图。
     */
    static boolean isPrivateUrl(String u) {
        try {
            java.net.URL x = new java.net.URL(u);
            String h = x.getHost();
            if (h == null) return true;
            h = h.trim().toLowerCase();
            if (h.equals("localhost") || h.equals("::1") || h.endsWith(".localhost")) return true;
            if (h.startsWith("[") ) h = h.substring(1, h.length() - 1);
            if (h.indexOf(':') >= 0) return true;              // 裸 IPv6：不做解析，直接拒
            String[] p = h.split("\\.");
            if (p.length == 4) {
                int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
                if (a == 127 || a == 0 || a == 10) return true;              // 回环 / 本网 / 私有 A
                if (a == 169 && b == 254) return true;                        // 链路本地（云元数据）
                if (a == 192 && b == 168) return true;                        // 私有 C
                if (a == 172 && b >= 16 && b <= 31) return true;              // 私有 B
                if (a == 100 && b >= 64 && b <= 127) return true;             // CGNAT
            }
            return false;
        } catch (Throwable t) {
            return true;   // 解析不了就当危险
        }
    }

    /** 人类可读的字节数 */
    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    private void img(Req r, OutputStream out) throws Exception {
        String u = r.q.containsKey("u") ? r.q.get("u") : "";
        String ref = r.q.containsKey("r") ? r.q.get("r") : "";
        if (u.length() == 0 || !(u.startsWith("http://") || u.startsWith("https://"))) {
            send(out, 400, "text/plain", "bad u".getBytes("UTF-8"), null);
            return;
        }
        if (isPrivateUrl(u)) {
            // 本机/内网地址不给代理（SSRF）。400 而不是 200，前端会显示灰块而不是拿去当图。
            send(out, 400, "text/plain", "private host blocked".getBytes("UTF-8"), null);
            return;
        }
        String key = "img:" + u;
        byte[] hit = Proxy.getCache(key);
        if (hit != null && hit.length > 0) {
            sendImg(out, hit, "max-age=604800");
            return;
        }
        Map<String, String> hd = new HashMap<String, String>();
        hd.put("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
        Net.Resp resp = Net.get(u, ref.length() > 0 ? ref : null, hd, 0, 9000);
        if (!resp.ok() || resp.body == null || resp.body.length == 0) {
            send(out, 404, "text/plain", "img fail".getBytes("UTF-8"), null);
            return;
        }
        Proxy.putCache(key, resp.body);
        sendImg(out, resp.body, "max-age=604800");
    }

    private void sendImg(OutputStream out, byte[] b, String cc) throws Exception {
        String ct = "image/jpeg";
        if (b.length > 8) {
            int a = b[0] & 0xff, c = b[1] & 0xff;
            if (a == 0x89 && c == 0x50) ct = "image/png";
            else if (a == 0x47 && c == 0x49) ct = "image/gif";
            else if (a == 0x52 && c == 0x49) ct = "image/webp";
        }
        // ACAO 由 send() 统一加，这里再写一份就会出现重复头
        send(out, 200, ct, b, -1, 0, null, "Cache-Control: " + cc + "\r\n");
    }

    // ---------- 静态资源 ----------

    private void asset(String name, String ct, OutputStream out) {
        try {
            InputStream in = assets.open(name);
            byte[] b = Net.readAll(in);
            send(out, 200, ct, b, null);
        } catch (Throwable t) {
            try { send(out, 404, "text/plain", ("missing " + name).getBytes("UTF-8"), null); }
            catch (Throwable t2) {}
        }
    }

    // ---------- 接口 ----------

    private void apiSources(OutputStream out) throws Exception {
        JSONArray a = new JSONArray();
        for (Sources.Src s : Sources.ALL) {
            JSONObject o = new JSONObject();
            o.put("key", s.key);
            o.put("name", s.name);
            o.put("custom", Sources.isCustom(s));
            o.put("base", s.base);
            o.put("alive", Sources.healthOf(s.key));
            a.put(o);
        }
        JSONObject j = new JSONObject();
        j.put("sources", a);
        sendJson(out, j.toString());
    }

    /**
     * 添加自定义源。/api/src/add?name=xxx&base=https://xxx.com
     *
     * 会顺手探一次（3 秒超时）。探不通**也照样加**，只是把失败原因回给界面——
     * 格式对但站点暂时挂了是很常见的情况，用户往往就想先存着，回头再用。
     */
    private void apiSrcAdd(Req r, OutputStream out) throws Exception {
        String name = r.q.containsKey("name") ? r.q.get("name") : "";
        String base = r.q.containsKey("base") ? r.q.get("base") : "";
        JSONObject o = new JSONObject();
        String nb = Sources.normBase(base);
        if (nb == null) {
            o.put("ok", false);
            o.put("err", "地址不合法，要像 https://xxx.com 这样");
            sendJson(out, o.toString());
            return;
        }
        Sources.Src s = Sources.addCustom(ctx, name, nb);
        if (s == null) {
            o.put("ok", false);
            o.put("err", "这个地址加不进来");
            sendJson(out, o.toString());
            return;
        }
        o.put("ok", true);
        o.put("key", s.key);
        o.put("name", s.name);
        o.put("base", s.base);
        // 深探：不光报条数，还告诉用户这个源「哪些栏目会有内容、哪些不会有」。
        // 用户自己加的源里，接口通但分类参数被忽略的相当常见——那时列表照常返回，
        // 点任何分类看到的都是同一批「最新更新」，正是「格式看着对、内容全不对」。
        org.json.JSONObject pf = Sources.probeFull(s, 5000);
        int n = pf.optInt("list", -1);
        o.put("found", n);
        o.put("probeOk", n >= 0);
        o.put("check", pf);
        if (n < 0) o.put("warn", "已添加，但没连上：" + pf.optString("note", ""));
        else o.put("warn", "添加成功。" + pf.optString("note", ""));
        // 新源要参与分类导航，得后台把它的分类表拉一遍
        try { Cats.warmOne(s); } catch (Throwable t) {}
        Log.i("src add " + s.key + " " + s.base + " n=" + n);
        sendJson(out, o.toString());
    }

    private void apiSrcDel(Req r, OutputStream out) throws Exception {
        String key = r.q.containsKey("key") ? r.q.get("key") : "";
        boolean ok = Sources.delCustom(ctx, key);
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        if (!ok) o.put("err", "没有这个自定义源（内置源不能删）");
        sendJson(out, o.toString());
    }

    /** 单独探一个源，界面上「测一下」按钮用。 */
    private void apiSrcCheck(Req r, OutputStream out) throws Exception {
        String key = r.q.containsKey("key") ? r.q.get("key") : "";
        Sources.Src s = Sources.byKey(key);
        JSONObject o = new JSONObject();
        if (s == null) {
            o.put("ok", false);
            o.put("err", "没有这个源");
        } else {
            org.json.JSONObject pf = Sources.probeFull(s, 5000);
            int n = pf.optInt("list", -1);
            o.put("ok", n >= 0);
            o.put("found", n);
            o.put("check", pf);
            o.put("alive", Sources.healthOf(key));
        }
        sendJson(out, o.toString());
    }

    /**
     * 分类列表。返回的是**归一分类**（key 如 movie/tv/cn_tv），不是源的 type_id。
     * 只列出至少有一个源真的支持的分类，避免点进去空白。
     */
    private void apiCats(OutputStream out) throws Exception {
        sendJson(out, Cats.json());
    }

    /** 影片库分类列表：/api/list?t=1&pg=1&n=60 */
    private void apiList(Req r, OutputStream out) throws Exception {
        // t 是归一分类 key（movie/tv/cn_tv…），不是源的 type_id
        String t = r.q.containsKey("t") ? r.q.get("t") : "movie";
        if (Cats.def(t) == null) t = "movie";
        int pg = 1, n = 60;
        try { pg = Integer.parseInt(r.q.get("pg")); } catch (Throwable th) {}
        try { n = Integer.parseInt(r.q.get("n")); } catch (Throwable th) {}
        if (pg < 1) pg = 1;
        if (n < 1 || n > 120) n = 60;
        long t0 = System.currentTimeMillis();
        List<Sources.Vod> vs = Sources.listCats(t, pg, n);
        JSONArray a = new JSONArray();
        for (Sources.Vod v : vs) a.put(vodJson(v));
        JSONObject j = new JSONObject();
        j.put("list", a);
        j.put("pg", pg);
        j.put("ms", System.currentTimeMillis() - t0);
        sendJson(out, j.toString());
    }

    /** 搜索联想：/api/suggest?kw=xxx */
    private void apiSuggest(Req r, OutputStream out) throws Exception {
        String kw = r.q.containsKey("kw") ? r.q.get("kw") : "";
        JSONArray a = new JSONArray();
        for (String s : Sources.suggest(kw, 8)) a.put(s);
        JSONObject j = new JSONObject();
        j.put("list", a);
        sendJson(out, j.toString());
    }

    /** 一部片子的 JSON（搜索/列表/详情共用） */
    static JSONObject vodJson(Sources.Vod v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", v.id);
        o.put("src", v.src);
        // 封面图要走本地 /img 代理，代理那边需要带 Referer 才不会被源站 403
        Sources.Src _ss = Sources.byKey(v.src);
        o.put("ref", _ss == null ? "" : _ss.referer);
        o.put("name", v.name);
        o.put("year", v.year);
        o.put("pic", v.pic);
        o.put("remarks", v.remarks);
        o.put("type", v.type);
        o.put("area", v.area);
        o.put("actor", v.actor);
        o.put("director", v.director);
        o.put("desc", v.desc);
        o.put("eps", v.epCount());
        JSONArray ls = new JSONArray();
        for (Sources.Line l : v.lines) {
            JSONObject lo = new JSONObject();
            lo.put("from", l.from);
            lo.put("n", l.eps.size());
            ls.put(lo);
        }
        o.put("lines", ls);
        return o;
    }

    /** 排行榜：/api/rank?coll=movie_top250&n=30 */
    private void apiRank(Req r, OutputStream out) throws Exception {
        String coll = r.q.containsKey("coll") ? r.q.get("coll") : "movie_top250";
        int n = 30;
        try { n = Integer.parseInt(r.q.get("n")); } catch (Throwable th) {}
        if (n < 1 || n > 100) n = 30;
        long t0 = System.currentTimeMillis();
        sendJson(out, Douban.json(coll, n, System.currentTimeMillis() - t0));
    }

    /**
     * 随机选一部：/api/random?min=4.0&type=movie|tv|any
     * min 已由前端严格校验（一位整数 + 可选一位小数），这里再兜一层。
     * 只从豆瓣榜单池里挑 —— 榜单里的片子有评分，采集站也基本都收录。
     */
    private void apiRandom(Req r, OutputStream out) throws Exception {
        String ms = r.q.containsKey("min") ? r.q.get("min") : "4.0";
        String ty = r.q.containsKey("type") ? r.q.get("type") : "any";
        double min = 4.0;
        try { min = Double.parseDouble(ms); } catch (Throwable th) { min = 4.0; }
        if (min < 0) min = 0;
        if (min > 9.9) min = 9.9;
        long t0 = System.currentTimeMillis();
        java.util.List<Douban.Item> pool = Douban.pool();
        java.util.List<Douban.Item> cand = new java.util.ArrayList<Douban.Item>();
        for (Douban.Item it : pool) {
            // score < 0 表示豆瓣没给评分（未知）。以前写成 it.score >= 0 && it.score < min，
            // 未知分就被留下了 —— 用户勾了「屏蔽低于 4.0」，结果抽到一部 -1 分的，
            // 界面显示「-1 分」，等于筛选没生效。未知分一律当不合格处理。
            if (min > 0 && (it.score < 0 || it.score < min)) continue;
            if ("movie".equals(ty) && !"movie".equals(it.type)) continue;
            if ("tv".equals(ty) && !"tv".equals(it.type)) continue;
            cand.add(it);
        }
        JSONObject j = new JSONObject();
        j.put("ms", System.currentTimeMillis() - t0);
        j.put("pool", pool.size());
        j.put("cand", cand.size());
        if (cand.isEmpty()) {
            j.put("list", new JSONArray());
            sendJson(out, j.toString());
            return;
        }
        java.util.Random rnd = new java.util.Random();
        JSONArray a = new JSONArray();
        // 给 3 个候选，界面上做成"抽卡"滚动，最后一个停住
        java.util.List<Integer> idx = new java.util.ArrayList<Integer>();
        for (int i = 0; i < cand.size(); i++) idx.add(i);
        java.util.Collections.shuffle(idx, rnd);
        for (int k = 0; k < Math.min(3, idx.size()); k++) {
            Douban.Item it = cand.get(idx.get(k));
            JSONObject o = new JSONObject();
            o.put("name", it.name);
            o.put("year", it.year);
            o.put("pic", it.pic);
            o.put("score", it.score);
            o.put("type", it.type);
            o.put("card", it.card);
            a.put(o);
        }
        j.put("list", a);
        sendJson(out, j.toString());
    }

    /** 投屏状态（页面轮询用，也方便悬浮窗跟主界面共用一份真值） */
    private void apiCastState(OutputStream out) throws Exception {
        sendJson(out, CastCtl.stateJson());
    }

    /** 投屏遥控：/api/cast/ctl?op=toggle&arg=0 */
    private void apiCastCtl(Req r, OutputStream out) throws Exception {
        // 参数名兼容：do/pos/v 是调试脚本和文档里用的写法，op/arg 是内部写法。
        // 以前只认 op/arg —— 用 do=pause 调用时 op 为空串，CastCtl.ctl 直接返回 false，
        // 表现是「接口返回 200 但遥控没反应」，非常难查。
        String op = r.q.containsKey("op") ? r.q.get("op")
                  : (r.q.containsKey("do") ? r.q.get("do") : "");
        String arg = r.q.containsKey("arg") ? r.q.get("arg")
                   : (r.q.containsKey("pos") ? r.q.get("pos")
                   : (r.q.containsKey("v") ? r.q.get("v") : "0"));
        if ("next".equals(op)) op = "nextep";
        if ("prev".equals(op)) op = "prevep";
        boolean ok = CastCtl.ctl(op, arg);
        sendJson(out, "{\"ok\":" + ok + ",\"state\":" + CastCtl.stateJson() + "}");
    }

    private void apiSearch(Req r, OutputStream out) throws Exception {
        String kw = r.q.containsKey("kw") ? r.q.get("kw") : "";
        if (kw.trim().length() == 0) { sendJson(out, "{\"list\":[]}"); return; }
        long t0 = System.currentTimeMillis();
        List<Sources.Vod> vs = Sources.searchAll(kw.trim());
        JSONArray a = new JSONArray();
        for (Sources.Vod v : vs) a.put(vodJson(v));
        JSONObject j = new JSONObject();
        j.put("list", a);
        j.put("ms", System.currentTimeMillis() - t0);
        sendJson(out, j.toString());
    }

    /**
     * 播放：src + id + line（线路下标）。
     * 返回该线路下所有集的「代播放地址」= /proxy?u=...&r=...
     * 这样前端拿到的就是可以直接喂给 hls.js 的地址，不需要再拼。
     */
    private void apiPlay(Req r, OutputStream out) throws Exception {
        String src = r.q.containsKey("src") ? r.q.get("src") : "";
        String id = r.q.containsKey("id") ? r.q.get("id") : "";
        int line = 0;
        try { line = Integer.parseInt(r.q.containsKey("line") ? r.q.get("line") : "0"); }
        catch (Throwable t) {}

        Sources.Vod v = Sources.cached(src, id);
        if (v == null) {
            // 缓存没有（比如进程重启后直接进详情页）：按 id 反查一次
            Sources.Src s = Sources.byKey(src);
            if (s != null) {
                List<Sources.Vod> one = Sources.searchOne(s, id, 8000);
                for (Sources.Vod x : one) if (id.equals(x.id)) { v = x; break; }
            }
        }
        if (v == null) { sendJson(out, "{\"err\":\"not found\"}"); return; }
        if (line < 0 || line >= v.lines.size()) line = 0;

        Sources.Line l = v.lines.get(line);
        Sources.Src s = Sources.byKey(src);
        String referer = (s == null) ? "" : s.referer;
        JSONArray eps = new JSONArray();
        for (Sources.Ep e : l.eps) {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            String full = Proxy.abs(playBase(v, e.url), e.url);
            o.put("u", full);
            o.put("r", referer);
            o.put("proxy", "/proxy?u=" + Sources.enc(full) + "&r=" + Sources.enc(referer));
            eps.put(o);
        }
        JSONObject j = new JSONObject();
        j.put("name", v.name);
        j.put("lineName", l.from);
        j.put("lines", v.lines.size());
        j.put("line", line);
        j.put("eps", eps);
        sendJson(out, j.toString());
    }

    /** 相对路径的集按源站域名补全（跨源合并后 base 需取原源，这里用源 base 兜底） */
    static String playBase(Sources.Vod v, String url) {
        if (url.startsWith("http")) return url;
        Sources.Src s = Sources.byKey(v.src);
        return (s == null) ? url : s.base;
    }

    /**
     * 添加离线任务：/api/dl/add?src=&id=&line=&ep=
     * 服务端自己回头查一遍选集，拿到真实 m3u8 地址和 Referer 再落任务，
     * 前端不用把这些东西传回来（省得参数对不上）。
     */
    private void apiDlAdd(Req r, OutputStream out) throws Exception {
        String src = r.q.containsKey("src") ? r.q.get("src") : "";
        String id = r.q.containsKey("id") ? r.q.get("id") : "";
        int line = 0, ep = 0;
        try { line = Integer.parseInt(r.q.get("line")); } catch (Throwable t) {}
        try { ep = Integer.parseInt(r.q.get("ep")); } catch (Throwable t) {}

        Sources.Vod v = Sources.cached(src, id);
        if (v == null) { sendJson(out, "{\"err\":\"片源信息已过期，重新搜索一次\"}"); return; }
        if (line < 0 || line >= v.lines.size()) line = 0;
        Sources.Line l = v.lines.get(line);
        if (ep < 0 || ep >= l.eps.size()) { sendJson(out, "{\"err\":\"集号越界\"}"); return; }
        Sources.Src s = Sources.byKey(src);
        String referer = (s == null) ? "" : s.referer;
        Sources.Ep e = l.eps.get(ep);

        DlStore.Task t = new DlStore.Task();
        t.id = String.valueOf(System.currentTimeMillis());
        t.src = src;
        t.vodId = id;
        t.vodName = v.name;
        t.pic = v.pic;
        t.epIdx = ep;
        t.epName = e.name;
        t.referer = referer;
        t.playlist = Proxy.abs(playBase(v, e.url), e.url);
        t.baseUrl = t.playlist;
        t.state = 0;
        t.ts = System.currentTimeMillis();
        DlStore.init(ctx);
        DlStore.add(t);
        DlService.start(ctx);

        JSONObject j = new JSONObject();
        j.put("ok", 1);
        j.put("id", t.id);
        j.put("name", t.vodName + " " + t.epName);
        sendJson(out, j.toString());
    }

    private void apiDlList(OutputStream out) throws Exception {
        DlStore.init(ctx);
        JSONArray a = new JSONArray();
        for (DlStore.Task t : DlStore.all()) {
            JSONObject o = t.json();
            o.put("pct", t.pct());
            o.put("local", new java.io.File(DlStore.dirOf(ctx, t.id), "index.m3u8").exists());
            a.put(o);
        }
        JSONObject j = new JSONObject();
        j.put("list", a);
        sendJson(out, j.toString());
    }

    private void apiDlDel(Req r, OutputStream out) throws Exception {
        String id = r.q.containsKey("id") ? r.q.get("id") : "";
        DlStore.init(ctx);
        DlStore.remove(id);
        // 顺手删文件
        try {
            java.io.File d = new java.io.File(DlStore.dirOf(ctx, id).getParentFile(), id);
            if (d.exists()) {
                java.io.File[] fs = d.listFiles();
                if (fs != null) for (java.io.File f : fs) f.delete();
                d.delete();
            }
        } catch (Throwable t) {}
        sendJson(out, "{\"ok\":1}");
    }

    private void apiDlRetry(Req r, OutputStream out) throws Exception {
        String id = r.q.containsKey("id") ? r.q.get("id") : "";
        DlStore.init(ctx);
        DlStore.Task t = DlStore.get(id);
        if (t != null) { t.state = 0; t.err = ""; DlStore.save(); DlService.start(ctx); }
        sendJson(out, "{\"ok\":1}");
    }

    /** 离线任务的本地清单：分片地址改写成 /localseg */
    private void localPlaylist(Req r, OutputStream out) throws Exception {
        String id = r.q.containsKey("t") ? r.q.get("t") : "";
        DlStore.init(ctx);
        java.io.File f = new java.io.File(DlStore.dirOf(ctx, id), "index.m3u8");
        if (!f.exists()) { send(out, 404, "text/plain", "no local".getBytes("UTF-8"), null); return; }
        String text = new String(Net.readAll(new java.io.FileInputStream(f)), "UTF-8");
        StringBuilder sb = new StringBuilder();
        for (String ln : text.split("\n")) {
            String s = ln.trim();
            if (s.length() == 0) { sb.append('\n'); continue; }
            if (s.startsWith("#")) { sb.append(s).append('\n'); continue; }
            if (s.endsWith(".ts")) {
                sb.append("/localseg?t=").append(Sources.enc(id)).append("&n=")
                  .append(Sources.enc(s)).append('\n');
            } else sb.append(s).append('\n');
        }
        send(out, 200, "application/vnd.apple.mpegURL", sb.toString().getBytes("UTF-8"),
                -1, 0, null, DLNA_HDR);
    }

    private void localSeg(Req r, OutputStream out) throws Exception {
        String id = r.q.containsKey("t") ? r.q.get("t") : "";
        String n = r.q.containsKey("n") ? r.q.get("n") : "";
        if (n.indexOf("..") >= 0 || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) {
            send(out, 400, "text/plain", "bad".getBytes("UTF-8"), null); return;
        }
        DlStore.init(ctx);
        java.io.File f = new java.io.File(DlStore.dirOf(ctx, id), n);
        if (!f.exists()) { send(out, 404, "text/plain", "no seg".getBytes("UTF-8"), null); return; }
        byte[] b = Net.readAll(new java.io.FileInputStream(f));
        send(out, 200, "video/mp2t", b, null);
    }

    static boolean castOk(Req r) {
        String k = r.q.containsKey("k") ? r.q.get("k") : "";
        String t = CAST_TOKEN;
        return t != null && t.length() > 0 && t.equals(k);
    }

    /**
     * 投屏响应的 DLNA 头。
     *
     * 少了这几行，不少电视（尤其老款）会认为「这不是一个可播放的媒体」，
     * 明明拿到 200 也直接黑屏 —— 这是 DLNA 的经典坑，不是代码逻辑问题。
     * 电视收到 transferMode=Streaming 才知道要边下边播；
     * contentFeatures 里的 OP=01 表示支持 Range，seek 才有意义。
     */
    static final String DLNA_HDR =
            "transferMode.dlna.org: Streaming\r\n"
          + "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;"
          + "DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n"
          + "Accept-Ranges: bytes\r\n";
    /* 这里以前还有一行 Access-Control-Allow-Origin，而 send() 自己也会加一行，
       结果响应里出现两个同名头。浏览器（WebView / Chromium 内核的接收端）
       遇到重复的 ACAO 直接判 CORS 失败 —— 电视端表现就是「地址拿到了但黑屏」。
       所以 ACAO 只在 send() 里加一次。 */

    /**
     * /api/cast/url2?src=&id=&line=&ep=&mode=hls|ts
     *
     * 和 /api/cast/url 的区别：**只收「哪部片、第几线路、第几集」，地址由这里自己解析**。
     * 旧接口要前端把 m3u8 地址和 Referer 传过来，一旦前端某个分支拿到 undefined，
     * 电视就会收到 /cast/proxy?u=undefined&r=undefined，然后黑屏（实测发生过）。
     * 现在地址不让前端碰，这类错误从根上没了。
     */
    private void apiCastUrl2(Req r, OutputStream out) throws Exception {
        String src = r.q.containsKey("src") ? r.q.get("src") : "";
        String id = r.q.containsKey("id") ? r.q.get("id") : "";
        int line = 0, ep = 0;
        try { line = Integer.parseInt(r.q.get("line")); } catch (Throwable t) {}
        try { ep = Integer.parseInt(r.q.get("ep")); } catch (Throwable t) {}
        String mode = r.q.containsKey("mode") ? r.q.get("mode") : "hls";

        JSONObject j = new JSONObject();
        if (CAST_TOKEN == null) CAST_TOKEN = newToken();
        String ip = Cast.lanIp(ctx);
        if (ip == null) { j.put("err", "没连上 WiFi"); sendJson(out, j.toString()); return; }

        Sources.Vod v = Sources.cached(src, id);
        if (v == null) { j.put("err", "片源信息已过期，回 App 重新进一次详情"); sendJson(out, j.toString()); return; }
        if (line < 0 || line >= v.lines.size()) line = 0;
        Sources.Line l = v.lines.get(line);
        if (l.eps == null || l.eps.isEmpty()) { j.put("err", "这条线路没有可播的集"); sendJson(out, j.toString()); return; }
        if (ep < 0 || ep >= l.eps.size()) ep = 0;
        Sources.Ep e = l.eps.get(ep);
        Sources.Src s = Sources.byKey(src);
        String referer = (s == null) ? "" : s.referer;
        String full = Proxy.abs(playBase(v, e.url), e.url);

        String base = "http://" + ip + ":" + PORT;
        String url;
        if ("ts".equals(mode)) {
            url = base + "/cast/single?u=" + Sources.enc(full)
                    + "&r=" + Sources.enc(referer) + "&k=" + CAST_TOKEN;
        } else {
            url = base + "/cast/proxy?u=" + Sources.enc(full)
                    + "&r=" + Sources.enc(referer) + "&k=" + CAST_TOKEN;
        }
        j.put("url", url);
        j.put("ip", ip);
        j.put("mode", mode);
        j.put("epName", e.name == null ? "" : e.name);
        j.put("eps", l.eps.size());
        j.put("line", line);
        sendJson(out, j.toString());
    }

    static String newToken() {
        StringBuilder sb = new StringBuilder();
        java.util.Random rd = new java.util.Random();
        for (int i = 0; i < 20; i++) sb.append("0123456789abcdef".charAt(rd.nextInt(16)));
        return sb.toString();
    }

    /**
     * /cast/single?u=<m3u8>&r=<Referer>&k=<token>  ——「转封装」模式
     *
     * 给不认 HLS(.m3u8) 的老电视用：把整集的 TS 分片按顺序拼成**一条 MPEG-TS 流**，
     * 直接以 video/mp2t 推给电视。不做转码，只是把本来就是 MPEG-TS 的分片首尾相接，
     * 所以画面质量一点不掉、CPU 几乎不占（解码还是电视自己干）。
     *
     * 代价：这是「一条流」，没法按字节 Range 定位，所以**拖进度条会回到开头**。
     * 能播 vs 能拖，老电视只能二选一，界面上让用户自己选。
     */
    private void castSingle(Req r, OutputStream out) throws Exception {
        if (!castOk(r)) { send(out, 403, "text/plain", "forbidden".getBytes("UTF-8"), null); return; }
        String u = r.q.containsKey("u") ? r.q.get("u") : "";
        String ref = r.q.containsKey("r") ? r.q.get("r") : "";
        if (u.length() == 0 || "undefined".equals(u) || "null".equals(u)) {
            send(out, 400, "text/plain", "no u".getBytes("UTF-8"), null);
            return;
        }
        // 先解析出真正的分片清单（主清单 -> 子清单 -> 分片数组）
        List<String> segs = null;
        String ref2 = ref;
        Net.Resp resp = Net.get(u, ref, null, 0, 15000);
        if (!resp.ok() || resp.body == null) { send(out, 502, "text/plain", "upstream".getBytes("UTF-8"), null); return; }
        String t1 = resp.text().trim();
        if (t1.startsWith("<")) {
            String inner = Proxy.extractM3u8(t1, u);
            if (inner != null && !inner.equals(u)) {
                try { java.net.URL pu = new java.net.URL(u);
                    ref2 = pu.getProtocol() + "://" + pu.getHost() + "/"; } catch (Throwable th) {}
                u = inner;
                resp = Net.get(u, ref2, null, 0, 15000);
                if (resp.ok() && resp.body != null) t1 = resp.text().trim();
            }
        }
        if (t1.startsWith("#EXTM3U") && t1.indexOf("#EXTINF") < 0) {
            // 还是主清单：取第一条变体
            String inner = Proxy.firstVariant(t1, u);
            if (inner != null) {
                resp = Net.get(inner, ref2, null, 0, 15000);
                if (resp.ok() && resp.body != null) { u = inner; t1 = resp.text().trim(); }
            }
        }
        if (!t1.startsWith("#EXTM3U") || t1.indexOf("#EXTINF") < 0) {
            send(out, 502, "text/plain", "not a segment playlist".getBytes("UTF-8"), null);
            return;
        }
        segs = Proxy.segUrls(t1, u);
        Log.i("castSingle segs=" + segs.size() + " u=" + u);

        // 拼流：不写 Content-Length（长度要先把全部分片 heads 一遍，太贵），
        // 用 chunked 传给电视。DLNA 头照给。
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: video/mp2t\r\n");
        h.append("Transfer-Encoding: chunked\r\n");
        // 这条路径是手写响应头、不走 send()，所以 CORS 头要在这里补（只补一次）
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append(DLNA_HDR);
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        int sent = 0;
        long t0 = System.currentTimeMillis();
        for (String seg : segs) {
            // 整条流最多推 20 分钟，防止某个源站卡住时这条连接一直挂着占线程
            if (System.currentTimeMillis() - t0 > 20 * 60 * 1000L) {
                Log.w("castSingle timeout");
                break;
            }
            byte[] b = Proxy.getCache(seg);
            if (b == null || b.length == 0) {
                Net.Resp rs = Net.get(seg, ref2, null, 0, 15000);
                if (!rs.ok() || rs.body == null || rs.body.length == 0) continue;
                b = rs.body;
                Proxy.putCache(seg, b);
            }
            try {
                out.write((Integer.toHexString(b.length) + "\r\n").getBytes("UTF-8"));
                out.write(b);
                out.write("\r\n".getBytes("UTF-8"));
                out.flush();
            } catch (Throwable w) {
                // 电视那头断开了（换台、关机、用户取消），立刻收工别再拉后面的分片
                Log.i("castSingle client gone at seg " + sent);
                break;
            }
            sent++;
        }
        out.write("0\r\n\r\n".getBytes("UTF-8"));
        out.flush();
        Log.i("castSingle done segs=" + sent);
    }

    /** 给前端一个可以直接丢给电视的地址：http://<局域网IP>:8686/cast/local?t=xx&k=yy */
    private void apiCastUrl(Req r, OutputStream out) throws Exception {
        String id = r.q.containsKey("t") ? r.q.get("t") : "";
        String onUrl = r.q.containsKey("u") ? r.q.get("u") : "";
        String onRef = r.q.containsKey("r") ? r.q.get("r") : "";
        if (CAST_TOKEN == null) {
            StringBuilder sb = new StringBuilder();
            java.util.Random rd = new java.util.Random();
            for (int i = 0; i < 20; i++) sb.append("0123456789abcdef".charAt(rd.nextInt(16)));
            CAST_TOKEN = sb.toString();
        }
        String ip = Cast.lanIp(ctx);
        JSONObject j = new JSONObject();
        if (ip == null) { j.put("err", "没连上 WiFi"); sendJson(out, j.toString()); return; }
        if (onUrl.length() > 0) {
            j.put("url", "http://" + ip + ":" + PORT + "/cast/proxy?u=" + Sources.enc(onUrl)
                    + "&r=" + Sources.enc(onRef) + "&k=" + CAST_TOKEN);
        } else {
            j.put("url", "http://" + ip + ":" + PORT + "/cast/local?t=" + Sources.enc(id) + "&k=" + CAST_TOKEN);
        }
        j.put("ip", ip);
        sendJson(out, j.toString());
    }

    private void castLocal(Req r, OutputStream out) throws Exception {
        if (!castOk(r)) { send(out, 403, "text/plain", "forbidden".getBytes("UTF-8"), null); return; }
        String id = r.q.containsKey("t") ? r.q.get("t") : "";
        String k = r.q.get("k");
        DlStore.init(ctx);
        java.io.File f = new java.io.File(DlStore.dirOf(ctx, id), "index.m3u8");
        if (!f.exists()) { send(out, 404, "text/plain", "no local".getBytes("UTF-8"), null); return; }
        String text = new String(Net.readAll(new java.io.FileInputStream(f)), "UTF-8");
        StringBuilder sb = new StringBuilder();
        for (String ln : text.split("\n")) {
            String s = ln.trim();
            if (s.length() == 0) { sb.append('\n'); continue; }
            if (s.startsWith("#")) { sb.append(s).append('\n'); continue; }
            if (s.endsWith(".ts")) {
                sb.append("/cast/seg?t=").append(Sources.enc(id))
                  .append("&n=").append(Sources.enc(s))
                  .append("&k=").append(k).append('\n');
            } else sb.append(s).append('\n');
        }
        send(out, 200, "application/vnd.apple.mpegURL", sb.toString().getBytes("UTF-8"),
                -1, 0, null, DLNA_HDR);
    }

    /**
     * 在线投屏代理。和 /proxy 一样要带 Referer 去源站取，
     * 区别是清单里的分片地址必须改写成 /cast/proxy（同样带 token），
     * 否则电视去请求 /proxy 会被 403。
     */
    /** 投屏清单里给电视的地址前缀。相对路径（/cast/proxy?...）部分电视解析不对，
     *  统一补成绝对地址，电视拿到就能直接请求。 */
    private String castBase() {
        return "http://" + Cast.lanIp(ctx) + ":" + PORT;
    }

    private void castProxy(Req r, OutputStream out) throws Exception {
        if (!castOk(r)) { send(out, 403, "text/plain", "forbidden".getBytes("UTF-8"), null); return; }
        String u = r.q.containsKey("u") ? r.q.get("u") : "";
        String ref = r.q.containsKey("r") ? r.q.get("r") : "";
        String k = r.q.get("k");
        if (u.length() == 0) { send(out, 400, "text/plain", "no u".getBytes("UTF-8"), null); return; }
        // 只有带投屏令牌的请求才可能指向内网（自己的分片地址），其余一律拒。
        if (isPrivateUrl(u) && !r.q.containsKey("k")) {
            send(out, 403, "text/plain", "private host blocked".getBytes("UTF-8"), null);
            return;
        }

        // 分片先吃手机侧缓存。投屏和本机播放最大的差别就在这里：
        // 电视没有分片下载器、不会并发预取，它只会有一下没一下地发单连接请求。
        // 如果这里无脑回源，那前面 prefetch 攒的 22 片等于白攒，电视端必然卡。
        String low = u.toLowerCase();
        boolean maybeList = low.indexOf(".m3u8") >= 0 || low.indexOf("m3u8?") >= 0;
        if (!maybeList) {
            byte[] hit = Proxy.getCache(u);
            if (hit != null && hit.length > 0) {
                String ct0 = u.endsWith(".ts") ? "video/mp2t" : "application/octet-stream";
                long st0 = parseRange(r.range);
                if (st0 > 0 && st0 < hit.length) {
                    int n0 = (int) (hit.length - st0);
                    byte[] cut0 = new byte[n0];
                    System.arraycopy(hit, (int) st0, cut0, 0, n0);
                    send(out, 206, ct0, cut0, hit.length, st0);
                } else {
                    send(out, 200, ct0, hit, null);
                }
                return;
            }
        }

        Net.Resp resp = Net.get(u, ref, null, 0, 20000);
        if (!resp.ok() || resp.body == null || resp.body.length == 0) {
            send(out, 502, "text/plain", "upstream fail".getBytes("UTF-8"), null);
            return;
        }
        String body = resp.text();
        String bt = body.trim();
        if (bt.startsWith("#EXTM3U")) {
            String rw = Proxy.rewrite(bt, u, ref);
            rw = rw.replace("/proxy?u=", castBase() + "/cast/proxy?k=" + k + "&u=");
            if (bt.indexOf("#EXTINF") >= 0) {
                List<String> segs = Proxy.segUrls(bt, u);
                Proxy.prefetch(segs, ref, 22);
            }
            send(out, 200, "application/vnd.apple.mpegURL", rw.getBytes("UTF-8"),
                    -1, 0, null, DLNA_HDR);
            return;
        }
        // HTML 播放页：挖出真 m3u8 再来一次
        if (bt.startsWith("<") || resp.ctype.indexOf("html") >= 0) {
            String inner = Proxy.extractM3u8(bt, u);
            if (inner != null && !inner.equals(u)) {
                String ref2 = ref;
                try {
                    java.net.URL pu = new java.net.URL(u);
                    ref2 = pu.getProtocol() + "://" + pu.getHost() + "/";
                } catch (Throwable t2) {}
                Net.Resp r2 = Net.get(inner, ref2, null, 0, 20000);
                if (r2.ok() && r2.body != null) {
                    String t2 = r2.text().trim();
                    if (t2.startsWith("#EXTM3U")) {
                        String rw = Proxy.rewrite(t2, inner, ref2);
                        rw = rw.replace("/proxy?u=", castBase() + "/cast/proxy?k=" + k + "&u=");
                        if (t2.indexOf("#EXTINF") >= 0) {
                            List<String> segs = Proxy.segUrls(t2, inner);
                            Proxy.prefetch(segs, ref2, 22);
                        }
                        send(out, 200, "application/vnd.apple.mpegURL", rw.getBytes("UTF-8"),
                                -1, 0, null, DLNA_HDR);
                        return;
                    }
                }
            }
        }
        // 分片：原样吐
        String ct = resp.ctype.length() > 0 ? resp.ctype : "application/octet-stream";
        if (u.endsWith(".ts")) ct = "video/mp2t";
        Proxy.putCache(u, resp.body);
        send(out, 200, ct, resp.body, null);
    }

    private void castSeg(Req r, OutputStream out) throws Exception {
        if (!castOk(r)) { send(out, 403, "text/plain", "forbidden".getBytes("UTF-8"), null); return; }
        String id = r.q.containsKey("t") ? r.q.get("t") : "";
        String n = r.q.containsKey("n") ? r.q.get("n") : "";
        if (n.indexOf("..") >= 0 || n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) {
            send(out, 400, "text/plain", "bad".getBytes("UTF-8"), null); return;
        }
        DlStore.init(ctx);
        java.io.File f = new java.io.File(DlStore.dirOf(ctx, id), n);
        if (!f.exists()) { send(out, 404, "text/plain", "no seg".getBytes("UTF-8"), null); return; }
        byte[] b = Net.readAll(new java.io.FileInputStream(f));
        send(out, 200, "video/mp2t", b, null);
    }

    // ── 无障碍调试接口 ──────────────────────────────────────────────────────
    //
    // 这几个接口存在的唯一理由：这个 App 的界面是 WebView，uiautomator / adb 看不到
    // 里面任何一个按钮。换人接手时想复现"点这里会怎样"，只能靠节点树。
    // 它们跟播放、下载、投屏完全无关，删掉也不影响任何用户功能。

    private void apiA11yState(OutputStream out) throws Exception {
        JSONObject j = new JSONObject();
        boolean on = KxAccessibility.enabled();
        j.put("on", on);
        j.put("lastPkg", KxAccessibility.lastPkg);
        long t = KxAccessibility.lastEventAt;
        j.put("lastEventAgo", t <= 0 ? -1 : (System.currentTimeMillis() - t));
        // 把"怎么打开"直接写进返回里，省得接手的人去翻文档
        j.put("howto", on ? "" : "设置 → 辅助功能 → 无障碍 → 下载服务 → 找到「看剧」并打开");
        sendJson(out, j.toString());
    }

    /**
     * /api/a11y/dump?clickable=1&max=1500&sleep=0
     * 导出当前界面的无障碍节点树。sleep>0 时先等一会儿再读（界面刚切完页用得上）。
     */
    private void apiA11yDump(Req r, OutputStream out) throws Exception {
        if (!KxAccessibility.enabled()) {
            sendJson(out, "{\"ok\":false,\"err\":\"a11y_off\",\"howto\":"
                    + "\"设置 → 辅助功能 → 无障碍 → 下载服务 → 找到「看剧」并打开\"}");
            return;
        }
        try {
            int sl = Integer.parseInt(r.q.get("sleep"));
            if (sl > 0 && sl < 5000) Thread.sleep(sl);
        } catch (Throwable t) { }
        boolean clk = "1".equals(r.q.get("clickable")) || "true".equals(r.q.get("clickable"));
        int max = 0;
        try { max = Integer.parseInt(r.q.get("max")); } catch (Throwable t) { }
        String s = KxAccessibility.I.dump(clk, max);
        sendJson(out, s);
    }

    /** /api/a11y/tap?text=播放   或   /api/a11y/tap?x=300&y=800 */
    private void apiA11yTap(Req r, OutputStream out) throws Exception {
        if (!KxAccessibility.enabled()) { sendJson(out, "{\"ok\":false,\"err\":\"a11y_off\"}"); return; }
        String text = r.q.get("text");
        if (text != null && text.length() > 0) {
            sendJson(out, KxAccessibility.I.tapText(text));
            return;
        }
        float x, y;
        try {
            x = Float.parseFloat(r.q.get("x"));
            y = Float.parseFloat(r.q.get("y"));
        } catch (Throwable t) {
            sendJson(out, "{\"ok\":false,\"err\":\"need_text_or_xy\"}");
            return;
        }
        boolean ok = KxAccessibility.I.tapXY(x, y);
        sendJson(out, "{\"ok\":" + ok + ",\"how\":\"gesture\"}");
    }

    /**
     * /api/dom?max=600&depth=14
     * 导出页面 DOM 结构（WebView 里的元素在无障碍树上读不到，只能问页面自己）。
     * 返回里的坐标是 CSS 像素，乘 dpr 就是屏幕物理像素。
     */
    private void apiDom(Req r, OutputStream out) throws Exception {
        sendJson(out, DomDump.run(4000));
    }

    /** /api/a11y/scroll?dir=down|up */
    private void apiA11yScroll(Req r, OutputStream out) throws Exception {
        if (!KxAccessibility.enabled()) { sendJson(out, "{\"ok\":false,\"err\":\"a11y_off\"}"); return; }
        boolean down = !"up".equals(r.q.get("dir"));
        boolean ok = KxAccessibility.I.scroll(down);
        sendJson(out, "{\"ok\":" + ok + ",\"dir\":\"" + (down ? "down" : "up") + "\"}");
    }

    private void apiStat(OutputStream out) throws Exception {
        JSONObject j = new JSONObject();
        j.put("cache", Proxy.used());
        j.put("disk", DiskCache.used());
        j.put("diskUsed", human(DiskCache.used()));
        sendJson(out, j.toString());
    }

    // ---------- 分片/清单代理 ----------

    private void proxy(Req r, OutputStream out) throws Exception {
        String u = r.q.containsKey("u") ? r.q.get("u") : "";
        String ref = r.q.containsKey("r") ? r.q.get("r") : "";
        if (u.length() == 0) { send(out, 400, "text/plain", "no u".getBytes("UTF-8"), null); return; }
        // 本机/内网地址不代理：正常源站都是公网域名，这条不影响播放，
        // 但能挡住本机其它 App 借这个接口当跳板摸内网（SSRF）。
        if (isPrivateUrl(u)) {
            send(out, 403, "text/plain", "private host blocked".getBytes("UTF-8"), null);
            return;
        }

        long start = parseRange(r.range);

        // Range 请求（拖进度条）：优先从完整缓存里切，切不到就带 Range 回源
        if (start > 0) {
            byte[] full = Proxy.getCache(u);
            if (full != null && start < full.length) {
                int n = (int) (full.length - start);
                byte[] cut = new byte[n];
                System.arraycopy(full, (int) start, cut, 0, n);
                send(out, 206, "application/octet-stream", cut, full.length, start);
                return;
            }
            Net.Resp rr = Net.get(u, ref, null, start, 20000);
            if (rr.ok() && rr.body != null) {
                send(out, 206, "application/octet-stream", rr.body, -1, start, rr.contentRange);
                return;
            }
            send(out, 502, "text/plain", "range fail".getBytes("UTF-8"), null);
            return;
        }

        // 清单还是分片？看内容判断，比猜后缀靠谱
        Net.Resp resp = Net.get(u, ref, null, 0, 20000);
        if (!resp.ok() || resp.body == null || resp.body.length == 0) {
            // 有些老源播放页不是 m3u8，真清单在 /index.m3u8
            if (u.indexOf(".m3u8") < 0) {
                String alt = u.endsWith("/") ? (u + "index.m3u8") : (u + "/index.m3u8");
                Net.Resp r2 = Net.get(alt, ref, null, 0, 20000);
                if (r2.ok() && r2.body != null && r2.body.length > 0) {
                    String t = r2.text().trim();
                    if (t.startsWith("#EXTM3U")) { servePlaylist(t, alt, ref, out, r); return; }
                }
            }
            send(out, 502, "text/plain", "upstream fail".getBytes("UTF-8"), null);
            return;
        }

        String body = resp.text();
        String btrim = body.trim();
        if (btrim.startsWith("#EXTM3U")) {
            servePlaylist(btrim, u, ref, out, r);
            return;
        }
        // HTML 播放页：真 m3u8 藏在页面里，挖出来再取一次
        if (btrim.startsWith("<") || resp.ctype.indexOf("html") >= 0) {
            String inner = Proxy.extractM3u8(btrim, u);
            if (inner != null && !inner.equals(u)) {
                // 播放页换域名时，Referer 跟着换成播放页的 origin，防盗链才放行
                String ref2 = ref;
                try {
                    java.net.URL pu = new java.net.URL(u);
                    ref2 = pu.getProtocol() + "://" + pu.getHost() + "/";
                } catch (Throwable t2) {}
                Net.Resp r2 = Net.get(inner, ref2, null, 0, 20000);
                if (r2.ok() && r2.body != null) {
                    String t2 = r2.text().trim();
                    if (t2.startsWith("#EXTM3U")) {
                        servePlaylist(t2, inner, ref2, out, r);
                        return;
                    }
                }
                // 挖出来的地址本身还是播放页（套娃），再挖一层
                if (r2.body != null) {
                    String t3 = r2.text();
                    String inner2 = Proxy.extractM3u8(t3, inner);
                    if (inner2 != null && !inner2.equals(inner)) {
                        Net.Resp r3 = Net.get(inner2, ref2, null, 0, 20000);
                        if (r3.ok() && r3.body != null && r3.text().trim().startsWith("#EXTM3U")) {
                            servePlaylist(r3.text().trim(), inner2, ref2, out, r);
                            return;
                        }
                    }
                }
            }
        }
        // 不是清单 = 分片或图片，原样吐
        String ct = resp.ctype.length() > 0 ? resp.ctype : "application/octet-stream";
        if (u.endsWith(".ts")) ct = "video/mp2t";
        else if (u.endsWith(".m4s") || u.endsWith(".mp4")) ct = "video/mp4";
        else if (u.endsWith(".jpg") || u.endsWith(".png")) ct = "image/jpeg";
        Proxy.putCache(u, resp.body);
        send(out, 200, ct, resp.body, null);
    }

    /** 清单：改写成走本地代理；如果是媒体清单，顺手预取前 8 片。 */
    private void servePlaylist(String text, String baseUrl, String ref,
                               OutputStream out, Req r) throws Exception {
        String rw = Proxy.rewrite(text, baseUrl, ref);
        boolean mediaList = (text.indexOf("#EXTINF") >= 0);
        if (mediaList) {
            List<String> segs = Proxy.segUrls(text, baseUrl);
            Proxy.prefetch(segs, ref, 22);
        }
        send(out, 200, "application/vnd.apple.mpegURL", rw.getBytes("UTF-8"), null);
    }

    static long parseRange(String h) {
        if (h == null || h.length() == 0) return 0;
        try {
            int i = h.indexOf("bytes=");
            if (i < 0) return 0;
            String t = h.substring(i + 6);
            int d = t.indexOf('-');
            if (d <= 0) return 0;
            return Long.parseLong(t.substring(0, d).trim());
        } catch (Throwable t) { return 0; }
    }

    // ---------- 响应 ----------

    static void sendJson(OutputStream out, String s) throws Exception {
        send(out, 200, "application/json; charset=utf-8", s.getBytes("UTF-8"), null);
    }

    static void send(OutputStream out, int code, String ct, byte[] body, String extra) throws Exception {
        send(out, code, ct, body, -1, 0, null);
    }

    static void send(OutputStream out, int code, String ct, byte[] body,
                     long total, long start) throws Exception {
        send(out, code, ct, body, total, start, null);
    }

    static void send(OutputStream out, int code, String ct, byte[] body,
                     long total, long start, String contentRange) throws Exception {
        send(out, code, ct, body, total, start, contentRange, null);
    }

    /** extra 是额外响应头（每行以 \r\n 结尾，含结尾换行），给 DLNA / 缓存头用。 */
    static void send(OutputStream out, int code, String ct, byte[] body,
                     long total, long start, String contentRange, String extra) throws Exception {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(ct).append("\r\n");
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        if (total > 0) {
            long end = start + (body == null ? 0 : body.length) - 1;
            h.append("Content-Range: bytes ").append(start).append('-').append(end)
             .append('/').append(total).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
        } else if (contentRange != null && contentRange.length() > 0) {
            h.append("Content-Range: ").append(contentRange).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
        }
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Cache-Control: no-store\r\n");
        if (extra != null && extra.length() > 0) h.append(extra);
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        if (body != null && body.length > 0) out.write(body);
    }

    static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            default: return "OK";
        }
    }
}
