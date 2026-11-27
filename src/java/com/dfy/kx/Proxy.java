package com.dfy.kx;

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * m3u8 改写 + 分片代理。
 *
 * 为什么必须有这一层：
 * 采集站的 m3u8 和分片都查 Referer，播放器（hls.js）直接去取一律 403。
 * 所以把播放清单里每个 URI 都改写成 /proxy?u=<真实地址>&r=<Referer>，
 * 播放器只会来请求 127.0.0.1，由这里带上正确的 Referer 去源站取，再原样吐回。
 */
public class Proxy {

    /** 分片缓存：key=url，value=完整字节。按总字节数封顶，超了从最旧的删。 */
    static final LinkedHashMap<String, byte[]> CACHE =
        new LinkedHashMap<String, byte[]>(64, 0.75f, true);

    static final AtomicLong cacheBytes = new AtomicLong(0);
    static final long CACHE_MAX = 32L * 1024 * 1024;   // 内存只放热分片；全量落盘交给 DiskCache
    static final java.util.Set<String> INFLIGHT =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static final ExecutorService PRE = Executors.newFixedThreadPool(10);

    static void putCache(String url, byte[] b) {
        if (b == null || b.length == 0) return;
        if (b.length > 8 * 1024 * 1024) return;   // 单片超 8MB 不缓存
        synchronized (CACHE) {
            if (CACHE.containsKey(url)) return;
            CACHE.put(url, b);
            cacheBytes.addAndGet(b.length);
            evictLocked();
        }
        // 冷层落盘：进程被杀、切集回来都不用重新拉
        DiskCache.put(url, b);
    }

    /** 内存命中 -> 直接返回；否则查磁盘，命中就回填内存（下次零等待） */
    static byte[] getCache(String url) {
        synchronized (CACHE) {
            byte[] b = CACHE.get(url);
            if (b != null) return b;
        }
        byte[] d = DiskCache.get(url);
        if (d != null && d.length > 0) {
            // 磁盘回填也要走淘汰。以前这里只加不减 —— 每命中一次磁盘就往内存里塞一份，
            // 上限形同虚设（实测涨到 69MB 还在涨，长时间看片就是一路吃内存）。
            if (d.length <= 8 * 1024 * 1024) {
                synchronized (CACHE) {
                    if (!CACHE.containsKey(url)) {
                        CACHE.put(url, d);
                        cacheBytes.addAndGet(d.length);
                        evictLocked();
                    }
                }
            }
            return d;
        }
        return null;
    }

    /** 按 LRU 淘汰到上限之下。调用方必须已持有 CACHE 锁。 */
    static void evictLocked() {
        Iterator<Map.Entry<String, byte[]>> it = CACHE.entrySet().iterator();
        while (cacheBytes.get() > CACHE_MAX && it.hasNext()) {
            Map.Entry<String, byte[]> e = it.next();
            cacheBytes.addAndGet(-e.getValue().length);
            it.remove();
        }
    }

    /** 相对地址按 base 补全 */
    public static String abs(String base, String rel) {
        if (rel == null) return "";
        rel = rel.trim();
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        try {
            URL b = new URL(base);
            if (rel.startsWith("//")) return b.getProtocol() + ":" + rel;
            if (rel.startsWith("/")) {
                int p = base.indexOf("//") + 2;
                int s = base.indexOf('/', p);
                String host = (s < 0) ? base : base.substring(0, s);
                return host + rel;
            }
            String dir = base.substring(0, base.lastIndexOf('/') + 1);
            return dir + rel;
        } catch (Throwable t) {
            return rel;
        }
    }

    static String p(String u, String referer) {
        return "/proxy?u=" + Sources.enc(u) + "&r=" + Sources.enc(referer == null ? "" : referer);
    }

    /**
     * 把一份 m3u8 里所有地址改写成走本地代理。
     * - #EXT-X-STREAM-INF 下一行的子清单地址
     * - 普通分片行
     * - #EXT-X-KEY / #EXT-X-MAP 里的 URI="..."
     */
    public static String rewrite(String text, String baseUrl, String referer) {
        StringBuilder out = new StringBuilder(text.length() + 2048);
        String[] lines = text.replace("\r\n", "\n").split("\n");
        boolean nextIsUri = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String ln = raw.trim();
            if (ln.length() == 0) { out.append(raw).append('\n'); continue; }

            if (ln.startsWith("#")) {
                String l2 = ln;
                // KEY / MAP 里藏着 URI="..."
                if (l2.indexOf("URI=\"") >= 0) {
                    StringBuilder sb = new StringBuilder();
                    int p = 0;
                    while (true) {
                        int a = l2.indexOf("URI=\"", p);
                        if (a < 0) { sb.append(l2.substring(p)); break; }
                        int b = l2.indexOf('"', a + 5);
                        if (b < 0) { sb.append(l2.substring(p)); break; }
                        String uri = l2.substring(a + 5, b);
                        sb.append(l2.substring(p, a)).append("URI=\"")
                          .append(p(abs(baseUrl, uri), referer)).append('"');
                        p = b + 1;
                    }
                    out.append(sb).append('\n');
                } else {
                    out.append(l2).append('\n');
                }
                if (l2.startsWith("#EXT-X-STREAM-INF")) nextIsUri = true;
                continue;
            }
            // 非注释行 = URI
            out.append(p(abs(baseUrl, ln), referer)).append('\n');
            nextIsUri = false;
        }
        return out.toString();
    }

    /** 拉清单并改写。若拿到 HTML（老式 play 页），自动试 /index.m3u8。 */
    /**
     * 有些源（非凡、红牛）给的「播放地址」其实是一个 HTML 播放页，
     * 真 m3u8 写在里面的 <script> 里，两种写法：
     *   const url = "/20261009/xx/index.m3u8?sign=...";   相对
     *   const vid = 'https://hn.bfvvs.com/play/xx/index.m3u8'; 绝对
     * 这里把两种都试一遍，相对路径按当前页面补全。
     */
    public static String extractM3u8(String html, String baseUrl) {
        if (html == null) return null;
        java.util.regex.Pattern[] ps = new java.util.regex.Pattern[] {
            java.util.regex.Pattern.compile("(?:const|var|let)\\s+(?:url|vid|videoUrl|src)\\s*=\\s*[\"']([^\"']+)[\"']"),
            java.util.regex.Pattern.compile("[\"']([^\"'\\s]*?\\.m3u8[^\"'\\s]*)[\"']"),
        };
        for (java.util.regex.Pattern p : ps) {
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                String cand = m.group(1);
                if (cand == null) continue;
                cand = cand.trim();
                if (cand.length() < 5) continue;
                if (cand.startsWith("data:") || cand.startsWith("blob:")) continue;
                if (cand.indexOf(".m3u8") < 0 && !cand.startsWith("/")) continue;
                if (cand.indexOf(".m3u8") < 0) continue;
                return abs(baseUrl, cand);
            }
        }
        return null;
    }

    public static String fetchPlaylist(String url, String referer) {
        Net.Resp r = Net.get(url, referer, null, 0, 14000);
        if (r.ok() && r.body != null) {
            String t = r.text().trim();
            if (t.startsWith("#EXTM3U")) return rewrite(t, url, referer);
        }
        // 有些源给的播放页不是 m3u8，而是 /play/xxx 这种页面，真清单在 /index.m3u8
        if (url.indexOf(".m3u8") < 0) {
            String alt = url.endsWith("/") ? (url + "index.m3u8") : (url + "/index.m3u8");
            Net.Resp r2 = Net.get(alt, referer, null, 0, 14000);
            if (r2.ok() && r2.body != null) {
                String t2 = r2.text().trim();
                if (t2.startsWith("#EXTM3U")) return rewrite(t2, alt, referer);
            }
        }
        Log.w("playlist fail " + url + " code=" + r.code);
        return null;
    }

    /** 从清单里抽分片地址（用于预取） */
    public static List<String> segUrls(String playlist, String baseUrl) {
        List<String> out = new ArrayList<String>();
        String[] lines = playlist.split("\n");
        String lastInf = null;
        for (String raw : lines) {
            String ln = raw.trim();
            if (ln.length() == 0) continue;
            if (ln.startsWith("#EXTINF")) { lastInf = ln; continue; }
            if (ln.startsWith("#")) continue;
            if (ln.startsWith("/proxy?")) continue;   // 已改写过的不算
            out.add(abs(baseUrl, ln));
        }
        return out;
    }

    /** 主清单（#EXT-X-STREAM-INF）里第一条变体流的绝对地址；不是主清单就返回 null。 */
    public static String firstVariant(String playlist, String baseUrl) {
        if (playlist == null) return null;
        String[] lines = playlist.replace("\r\n", "\n").split("\n");
        for (String raw : lines) {
            String ln = raw.trim();
            if (ln.length() == 0 || ln.startsWith("#")) continue;
            return abs(baseUrl, ln);
        }
        return null;
    }

    /**
     * 后台预取：清单下发后立刻把前 N 片拉进缓存，播放器请求时就能立刻响应。
     * 这是「点开不用干等」的关键——不预取的话每片都要现等一次源站往返。
     */
    public static void prefetch(final List<String> urls, final String referer, int n) {
        final int cnt = Math.min(n, urls.size());
        for (int i = 0; i < cnt; i++) {
            final String u = urls.get(i);
            if (DiskCache.has(u)) continue;
            if (!INFLIGHT.add(u)) continue;
            PRE.submit(new Runnable() {
                public void run() {
                    try {
                        Net.Resp r = Net.get(u, referer, null, 0, 12000);
                        if (r.ok() && r.body != null && r.body.length > 0) {
                            putCache(u, r.body);
                        }
                    } catch (Throwable t) {
                    } finally {
                        INFLIGHT.remove(u);
                    }
                }
            });
        }
    }

    /** 取分片：先查缓存，没有就现拉。 */
    public static byte[] segment(String url, String referer) {
        byte[] c = getCache(url);
        if (c != null) return c;
        Net.Resp r = Net.get(url, referer, null, 0, 16000);
        if (r.ok() && r.body != null && r.body.length > 0) {
            putCache(url, r.body);
            return r.body;
        }
        Log.w("seg fail code=" + r.code + " " + url);
        return null;
    }

    public static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); cacheBytes.set(0); }
    }

    public static long used() { return cacheBytes.get(); }
}
