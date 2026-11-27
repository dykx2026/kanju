package com.dfy.kx;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DLNA 投屏（不是 Miracast）。
 *
 * 区别很关键：Miracast 是把手机屏幕编码成视频流推过去，受 1080p30 限制；
 * DLNA 只是把「一个 URL」告诉电视，电视自己去拉、自己解码，所以画质
 * 只受源站码率和电视解码能力限制，没有协议层分辨率上限。
 *
 * 代价：电视必须能访问那个 URL，所以必须是手机的局域网地址，
 * 而且手机上的代理进程得一直活着（源站查 Referer，电视直连会被 403）。
 */
public class Cast {

    public static class Device {
        public String name = "";
        public String location = "";
        public String control = "";
        public String type = "";
    }

    static final String ST_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";

    /** 拿本机在局域网里的 IPv4 */
    public static String lanIp(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Throwable t) {}
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String h = a.getHostAddress();
                    if (h != null && h.indexOf('.') > 0 && !h.startsWith("169.254")) return h;
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    /** SSDP 搜索局域网里的 MediaRenderer */
    public static List<Device> discover(int timeoutMs) {
        List<Device> out = new ArrayList<Device>();
        List<String> locs = new ArrayList<String>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(Math.max(400, timeoutMs / 4));
            /* 兼容性：不同电视回应的搜索目标不一样。
               有的只认 MediaRenderer:1，有的只认 AVTransport:1，老盒子/部分国产品牌
               只认 ssdp:all。以前只发 MediaRenderer 一种，那些电视直接搜不到，
               用户看到的就是「搜不到设备」，还以为这台电视不支持投屏。
               现在四种都发，发两轮（UDP 会丢包，单轮经常漏）。
               ssdp:all 会招来路由器/NAS，但 describe() 里找不到 AVTransport 或
               RenderingControl 的会被丢掉，不会污染设备列表。 */
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] sts = new String[]{
                    ST_RENDERER,
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "ssdp:all"
            };
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < sts.length; i++) {
                    String msg = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 2\r\n"
                            + "ST: " + sts[i] + "\r\n\r\n";
                    byte[] data = msg.getBytes("UTF-8");
                    try { sock.send(new DatagramPacket(data, data.length, group, 1900)); }
                    catch (Throwable t) {}
                }
                try { Thread.sleep(220); } catch (Throwable t) {}
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() >= end) break;
                    continue;
                }
                String text = new String(p.getData(), 0, p.getLength(), "UTF-8");
                String loc = headerOf(text, "LOCATION");
                if (loc == null || loc.length() == 0) continue;
                if (locs.contains(loc)) continue;
                locs.add(loc);
            }
        } catch (Throwable t) {
            Log.w("ssdp " + t);
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable t) {}
        }

        // 并发拉设备描述。串行的话，只要有一台设备的 desc.xml 拉不动，
        // 整张列表就得陪着等一次 4 秒超时，实测能把「搜设备」拖到十几秒。
        if (locs.isEmpty()) {
            Log.i("cast found 0");
            return out;
        }
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, locs.size()));
        List<java.util.concurrent.Future<Device>> futs =
                new ArrayList<java.util.concurrent.Future<Device>>();
        for (final String loc : locs) {
            futs.add(pool.submit(new java.util.concurrent.Callable<Device>() {
                public Device call() { return describe(loc); }
            }));
        }
        long hard = System.currentTimeMillis() + 6000;
        for (java.util.concurrent.Future<Device> f : futs) {
            try {
                long left = Math.max(300, hard - System.currentTimeMillis());
                Device d = f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (d != null) out.add(d);
            } catch (Throwable t) {
                // 这台拉不到描述就算了，别人的照样返回
            }
        }
        pool.shutdownNow();
        Log.i("cast found " + out.size());
        return out;
    }

    static String headerOf(String resp, String key) {
        for (String ln : resp.split("\r\n")) {
            int c = ln.indexOf(':');
            if (c < 0) continue;
            if (ln.substring(0, c).trim().equalsIgnoreCase(key)) return ln.substring(c + 1).trim();
        }
        return null;
    }

    /** 拉设备描述，找 AVTransport 控制地址 */
    static Device describe(String location) {
        try {
            String xml = httpGet(location, 2500);
            if (xml == null) return null;
            Device d = new Device();
            d.location = location;
            d.name = firstTag(xml, "friendlyName");
            if (d.name == null || d.name.length() == 0) d.name = "电视";
            d.type = firstTag(xml, "deviceType");

            // 找 AVTransport 服务的 controlURL
            Pattern svc = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL);
            Matcher m = svc.matcher(xml);
            String baseUrl = origin(location);
            String fallback = null;
            while (m.find()) {
                String block = m.group(1);
                String st = firstTag(block, "serviceType");
                String cu = firstTag(block, "controlURL");
                if (st == null || cu == null) continue;
                String abs = absolutize(baseUrl, cu);
                /* 大小写容错：实测有设备写成 avtransport1 / AVTRANSPORT。
                   以前这里区分大小写，那类设备就被判成「没有 AVTransport」。 */
                String low = st.toLowerCase();
                if (low.indexOf("avtransport") >= 0) { d.control = abs; break; }
                if (fallback == null && low.indexOf("renderingcontrol") >= 0) fallback = abs;
            }
            if (d.control == null) d.control = fallback;
            if (d.control == null) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    static String origin(String u) {
        try {
            URL url = new URL(u);
            int port = url.getPort();
            return url.getProtocol() + "://" + url.getHost() + (port > 0 ? (":" + port) : "");
        } catch (Throwable t) { return u; }
    }

    static String absolutize(String base, String rel) {
        rel = rel.trim();
        if (rel.startsWith("http")) return rel;
        if (rel.startsWith("/")) return base + rel;
        return base + "/" + rel;
    }

    static String firstTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        if (m.find()) {
            String v = m.group(1).trim();
            if (v.startsWith("<![CDATA[")) v = v.substring(9);
            if (v.endsWith("]]>")) v = v.substring(0, v.length() - 3);
            return unescape(v);
        }
        return null;
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String httpGet(String u, int timeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", "KX-Cast/1.0");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) { return null; }
    }

    /** 发一条 SOAP 动作 */
    static boolean soap(String control, String service, String action, String inner) {
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + inner
                + "</u:" + action + "></s:Body></s:Envelope>";
            HttpURLConnection c = (HttpURLConnection) new URL(control).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setRequestProperty("User-Agent", "KX-Cast/1.0");
            java.io.OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            int code = c.getResponseCode();
            Log.i("soap " + action + " -> " + code);
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            Log.w("soap " + action + " " + t);
            return false;
        }
    }

    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";

    /** 投一个地址到电视上 */
    public static boolean play(Device d, String mediaUrl, String title) {
        if (d == null || d.control == null) return false;
        String meta = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"1\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\">"
                + esc(mediaUrl) + "</res></item></DIDL-Lite>";
        boolean ok1 = soap(d.control, AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + esc(mediaUrl) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + esc(meta) + "</CurrentURIMetaData>");
        if (!ok1) return false;
        try { Thread.sleep(400); } catch (Throwable t) {}
        return soap(d.control, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    /** 暂停/继续 */
    public static boolean pause(Device d) {
        return d != null && soap(d.control, AVT, "Pause", "<InstanceID>0</InstanceID>");
    }

    public static boolean resume(Device d) {
        return d != null && soap(d.control, AVT, "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static boolean stop(Device d) {
        return d != null && soap(d.control, AVT, "Stop", "<InstanceID>0</InstanceID>");
    }

    /** 电视音量（0~100） */
    public static boolean volume(Device d, int v) {
        if (d == null) return false;
        // RenderingControl 的地址不一定拿到，试着用同一个
        return soap(d.control, RCS, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + v + "</DesiredVolume>");
    }

    /**
     * 发一条 SOAP 动作并拿回响应正文。
     * soap() 只返回 true/false，遥控要读电视回话（当前位置、时长、音量），
     * 所以需要一个带正文的版本。失败返回 null。
     */
    static String soapRaw(String control, String service, String action, String inner, int timeout) {
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + inner
                + "</u:" + action + "></s:Body></s:Envelope>";
            HttpURLConnection c = (HttpURLConnection) new URL(control).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setRequestProperty("User-Agent", "KX-Cast/1.0");
            java.io.OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.w("soapRaw " + action + " " + t);
            return null;
        }
    }

    /** 把 01:23:45 / 23:45 解析成秒。解析不了返回 -1。 */
    static int parseTime(String t) {
        if (t == null) return -1;
        t = t.trim();
        if (t.length() == 0) return -1;
        String[] a = t.split(":");
        try {
            if (a.length == 3) {
                return Integer.parseInt(a[0]) * 3600 + Integer.parseInt(a[1]) * 60
                        + (int) Double.parseDouble(a[2]);
            }
            if (a.length == 2) {
                return Integer.parseInt(a[0]) * 60 + (int) Double.parseDouble(a[1]);
            }
        } catch (Throwable e) { return -1; }
        return -1;
    }

    /** 拖进度：按绝对秒数 seek（REL_TIME 模式，电视普遍支持） */
    public static boolean seek(Device d, int sec) {
        if (d == null || sec < 0) return false;
        int h = sec / 3600, m = (sec % 3600) / 60, ss = sec % 60;
        String t = String.format("%02d:%02d:%02d", h, m, ss);
        return soap(d.control, AVT, "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + t + "</Target>");
    }

    /**
     * 读电视上的播放位置。返回 {当前秒, 总时长秒}，取不到的项为 -1。
     * 失败返回 null。
     */
    public static int[] position(Device d) {
        if (d == null) return null;
        String r = soapRaw(d.control, AVT, "GetPositionInfo",
                "<InstanceID>0</InstanceID>", 4000);
        if (r == null) return null;
        int rel = parseTime(firstTag(r, "RelTime"));
        int dur = parseTime(firstTag(r, "TrackDuration"));
        String st = firstTag(r, "TransportState");
        int state = -1;
        if (st != null) {
            if (st.indexOf("PLAYING") >= 0) state = 1;
            else if (st.indexOf("PAUSED") >= 0) state = 0;
            else if (st.indexOf("STOPPED") >= 0 || st.indexOf("NO_MEDIA") >= 0) state = 2;
        }
        return new int[]{rel, dur, state};
    }

    /**
     * 读媒体总时长（GetMediaInfo 的 MediaDuration）。
     *
     * 为什么需要这个：部分电视的 GetPositionInfo 里 TrackDuration 一直是 00:00:00，
     * 于是遥控界面拿不到总时长，进度条拖不了、快进快退也没法算边界。
     * GetMediaInfo 是另一条标准动作，这类电视往往能给对。
     */
    public static int mediaDuration(Device d) {
        if (d == null) return -1;
        String r = soapRaw(d.control, AVT, "GetMediaInfo",
                "<InstanceID>0</InstanceID>", 3500);
        if (r == null) return -1;
        return parseTime(firstTag(r, "MediaDuration"));
    }

    /** 读电视音量（0~100）。失败返回 -1。 */
    public static int getVolume(Device d) {
        if (d == null) return -1;
        String r = soapRaw(d.control, RCS, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>", 4000);
        if (r == null) return -1;
        String v = firstTag(r, "CurrentVolume");
        if (v == null) return -1;
        try { return Integer.parseInt(v.trim()); } catch (Throwable t) { return -1; }
    }
}
