package com.dfy.kx;

import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把页面里的 DOM 结构取出来（调试用）。
 *
 * 为什么不用无障碍树
 * ------------------
 * WebView 对无障碍只暴露一个空壳节点（实测 Android 16 上 getChildCount()=0），
 * 页面里几百个按钮一个都读不到。而接手维护的人最需要知道的就是"页面上有什么、
 * 在哪个位置"。所以让页面自己导一份：window.__domDump() 返回 JSON 字符串。
 *
 * 关于坐标（踩过的坑）
 * -------------------
 * 页面里的 getBoundingClientRect() 给的是 **WebView 视口坐标**，而 WebView 从
 * 状态栏下面开始（这台机器偏 122px）。拿视口坐标去 dispatchGesture 点击，
 * 会整个偏掉 122px——表现出来就是"点下载 tab 结果打开了详情页"。
 * 所以这里统一换算成**屏幕物理坐标**再输出，字段是 pb：
 *
 *     pb = [x, y, w, h]   // 直接可以喂给 /api/a11y/tap?x=&y=
 *
 * 原来的 b（CSS 像素）保留，用来对照页面样式。
 */
public class DomDump {

    /** 页面导出的 JSON。失败时返回带 ok=false 的 JSON。 */
    public static String run(long timeoutMs) {
        WebView w = MainActivity.WEB;
        if (w == null) return "{\"ok\":false,\"err\":\"no_webview\"}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<String>("");
        try {
            w.post(new Runnable() {
                public void run() {
                    try {
                        MainActivity.WEB.evaluateJavascript(
                                "(window.__domDump?__domDump():'')",
                                new ValueCallback<String>() {
                                    public void onReceiveValue(String v) {
                                        ref.set(v);
                                        latch.countDown();
                                    }
                                });
                    } catch (Throwable t) {
                        latch.countDown();
                    }
                }
            });
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(t)) + "\"}";
        }

        String raw = ref.get();
        if (raw == null || raw.length() == 0) return "{\"ok\":false,\"err\":\"timeout\"}";
        String s = unquote(raw);
        if (s.length() == 0) return "{\"ok\":false,\"err\":\"empty\"}";

        // 把视口坐标换算成屏幕坐标
        try {
            JSONObject j = new JSONObject(s);
            double dpr = j.optDouble("dpr", 1);
            int[] xy = offsetOf(w);
            j.put("ox", xy[0]);
            j.put("oy", xy[1]);
            if (j.optJSONObject("tree") != null) {
                addPb(j.getJSONObject("tree"), xy[0], xy[1], dpr);
            }
            return j.toString();
        } catch (Throwable t) {
            return s; // 换算失败就原样返回，至少还能用 b
        }
    }

    /** WebView 在屏幕上的左上角（物理像素）。 */
    private static int[] offsetOf(WebView w) {
        try {
            int[] loc = new int[2];
            w.getLocationOnScreen(loc);
            return loc;
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    /** 递归给每个节点补 pb（屏幕物理坐标）。 */
    private static void addPb(JSONObject n, int ox, int oy, double dpr) {
        if (n == null) return;
        try {
            JSONArray b = n.optJSONArray("b");
            if (b != null && b.length() == 4) {
                JSONArray pb = new JSONArray();
                pb.put(Math.round(ox + b.getDouble(0) * dpr));
                pb.put(Math.round(oy + b.getDouble(1) * dpr));
                pb.put(Math.round(b.getDouble(2) * dpr));
                pb.put(Math.round(b.getDouble(3) * dpr));
                n.put("pb", pb);
            }
        } catch (Throwable t) { }
        JSONArray kids = n.optJSONArray("kids");
        if (kids == null) return;
        for (int i = 0; i < kids.length(); i++) {
            addPb(kids.optJSONObject(i), ox, oy, dpr);
        }
    }

    /**
     * evaluateJavascript 的回调给的是 JS 值的 JSON 表示：
     * 字符串会被包成 "\"...\"" 并转义。这里还原成原始字符串。
     */
    static String unquote(String v) {
        if (v == null) return "";
        if (v.equals("null")) return "";
        if (v.length() < 2 || v.charAt(0) != '"') return v;
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c != '\\') { b.append(c); continue; }
            i++;
            if (i >= v.length() - 1) break;
            char e = v.charAt(i);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 < v.length()) {
                        try {
                            b.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable t) { }
                    }
                    break;
                default: b.append(e);
            }
        }
        return b.toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
