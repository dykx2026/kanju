package com.dfy.kx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 HTTP 客户端。这里刻意不用 OkHttp：
 * 一是省 APK 体积（本项目整个才几 MB），二是采集站大量自签证书/证书过期，
 * 默认校验根本连不上，必须放开。所以手写比引库更省事。
 */
public class Net {

    public static final String UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 每个源站都会带的 Referer 兜底，防止 403 */
    public static final Map<String, String> NO_HEADERS = new HashMap<String, String>();

    static {
        // 全放行：采集站证书五花八门，严格校验等于一个都连不上
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String h, SSLSession s) { return true; }
            });
        } catch (Throwable t) {
            Log.w("SSL init fail: " + t);
        }
    }

    /** 一个响应。body 为 null 表示只取了头部（HEAD 或 Range 探测）。 */
    public static class Resp {
        public int code;
        public String ctype = "";
        public long len = -1;
        public byte[] body;
        public String location = "";
        public String contentRange = "";
        public Map<String, String> headers = new HashMap<String, String>();
        public String text() { return body == null ? "" : decode(body, ctype); }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    /** 按 Content-Type 里的 charset 解码，没写就 UTF-8，再不行回 GBK。 */
    public static String decode(byte[] b, String ctype) {
        String cs = "utf-8";
        if (ctype != null) {
            int i = ctype.toLowerCase().indexOf("charset=");
            if (i >= 0) {
                cs = ctype.substring(i + 8).trim();
                int s = cs.indexOf(';');
                if (s > 0) cs = cs.substring(0, s);
                cs = cs.replace("\"", "").trim();
            }
        }
        try {
            return new String(b, cs);
        } catch (Throwable t) {
            try { return new String(b, "utf-8"); }
            catch (Throwable t2) { return new String(b); }
        }
    }

    public static Resp get(String url) { return get(url, null, null, 0, 12000); }

    public static Resp get(String url, String referer) { return get(url, referer, null, 0, 12000); }

    /**
     * @param rangeStart >0 时带 Range: bytes=start-  （分片代理拖进度要用）
     */
    public static Resp get(String url, String referer, Map<String, String> extra,
                           long rangeStart, int timeoutMs) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            // 采集站会返回 http 的 m3u8 地址，URL 构造时若含中文要编码
            URL u = new URL(url.replace(" ", "%20"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && referer.length() > 0) {
                c.setRequestProperty("Referer", referer);
            }
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (rangeStart > 0) {
                c.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            r.code = c.getResponseCode();
            r.ctype = nz(c.getContentType());
            r.len = c.getContentLength();
            r.location = nz(c.getHeaderField("Location"));
            r.contentRange = nz(c.getHeaderField("Content-Range"));
            for (Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    r.headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
                }
            }
            InputStream in = null;
            try {
                in = (r.code >= 400) ? c.getErrorStream() : c.getInputStream();
            } catch (Throwable t) {
                in = c.getErrorStream();
            }
            if (in != null) {
                String enc = nz(c.getContentEncoding()).toLowerCase();
                if (enc.contains("gzip")) {
                    try { in = new GZIPInputStream(in); } catch (Throwable t) { /* 不是真 gzip，按原样读 */ }
                }
                r.body = readAll(in);
            } else {
                r.body = new byte[0];
            }
            return r;
        } catch (Throwable t) {
            r.code = -1;
            r.ctype = "ERR:" + t.getClass().getSimpleName();
            r.body = new byte[0];
            return r;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable t) {}
        }
    }

    static String nz(String s) { return s == null ? "" : s; }

    public static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        } finally {
            try { in.close(); } catch (Throwable t) {}
        }
    }

    /** 把一段字节按需截断后写给输出流（分片 Range 用） */
    public static void copy(InputStream in, java.io.OutputStream out) {
        byte[] buf = new byte[32768];
        try {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable t) {
            Log.w("copy: " + t);
        } finally {
            try { in.close(); } catch (Throwable t) {}
        }
    }
}
