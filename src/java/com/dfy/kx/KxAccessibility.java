package com.dfy.kx;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 调试用无障碍服务。
 *
 * 存在的理由
 * ----------
 * 这个 App 是个 WebView 壳，界面上的东西对 adb / uiautomator 来说是"一坨 WebView"，
 * 拿不到里面的按钮。换个人接手时，想定位"哪个元素在哪个坐标、点了会发生什么"，
 * 没有任何依据。所以这里把无障碍节点树导出成 JSON，让接手的人能：
 *   - 看清当前屏幕上有哪些节点、在什么位置；
 *   - 按文字/描述找到节点并点它（真点击，走 ACTION_CLICK 或 dispatchGesture）；
 *   - 配合 /api/a11y/dump 做回归测试。
 *
 * 为什么只注册一个服务
 * -------------------
 * MagicOS 11（荣耀）限制了单个第三方 App 只能注册 1 项无障碍服务，
 * 旧版本能注册多项。所以这里刻意只放一个服务，用方法区分能力，
 * 绝不拆成多个 —— 拆了在 MagicOS 11 上会静默消失，用户根本看不到开关。
 *
 * 读节点的三条硬规矩（都是踩过的坑）
 * --------------------------------
 * 1. 必须在主线程读。非主线程调 getRootInActiveWindow 有可能拿到 null。
 * 2. ListView / RecyclerView 刚滚完就立刻读，只能拿到旧的那几项；
 *    要等 TYPE_WINDOW_CONTENT_CHANGED 之后再稍微延迟一点读。
 * 3. 用完即弃，不缓存节点对象（节点会失效，缓存住会拿到过期数据）。
 */
public class KxAccessibility extends AccessibilityService {

    /** 全局单例。没开启服务时是 null —— 所有调用方都要判空。 */
    static volatile KxAccessibility I = null;

    /** 最近一次事件的时间戳 / 包名，用来判断"界面是不是刚动过"。 */
    static volatile long lastEventAt = 0;
    static volatile String lastPkg = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 单次导出最多收多少节点。太大会卡界面，3000 对调试足够。 */
    private static final int MAX_NODES = 3000;
    private static final int MAX_DEPTH = 30;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        I = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            // 要拿到 view id（WebView 里没意义，但原生控件有），也要能取交互窗口
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            // 关键的一行：不设它，WebView 只暴露一个空的 WebView 节点，
            // 页面里的按钮、文字一个都读不到（实测 getChildCount()=0）。
            // 它会让 WebView 构建完整的虚拟节点树，代价是渲染开销略增，
            // 所以只在调试用无障碍服务里开，普通使用不受影响。
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
            // 内容变化事件必须监听，否则滚动后拿到的还是旧树
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 60;
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.w("a11y setInfo " + t);
        }
        Log.i("a11y connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        lastEventAt = System.currentTimeMillis();
        try {
            CharSequence p = e.getPackageName();
            if (p != null) lastPkg = p.toString();
        } catch (Throwable t) {}
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        I = null;
        super.onDestroy();
        Log.i("a11y destroyed");
    }

    // ── 对外能力 ────────────────────────────────────────────────────────────

    /**
     * 导出当前界面的节点树。
     *
     * @param onlyClickable 只要可点击的（调试时通常只看这个，输出小一半）
     * @param maxNodes      上限（0 用默认）
     * @return JSON 字符串；服务没开或读不到返回 {"ok":false,...}
     */
    public String dump(final boolean onlyClickable, final int maxNodes) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>("");
        ui.post(new Runnable() {
            public void run() {
                try {
                    AccessibilityNodeInfo root = rootNow();
                    if (root == null) {
                        out.set("{\"ok\":false,\"err\":\"no_root\"}");
                    } else {
                        Counter c = new Counter();
                        c.limit = maxNodes > 0 ? maxNodes : MAX_NODES;
                        JSONObject j = node(root, 0, onlyClickable, c);
                        j.put("ok", true);
                        j.put("total", c.n);
                        j.put("truncated", c.n >= c.limit);
                        out.set(j.toString());
                    }
                } catch (Throwable t) {
                    out.set("{\"ok\":false,\"err\":\"" + esc(String.valueOf(t)) + "\"}");
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ie) { }
        String s = out.get();
        return (s == null || s.length() == 0) ? "{\"ok\":false,\"err\":\"timeout\"}" : s;
    }

    /**
     * 按文字点一下。依次尝试：节点自己可点 -> 找可点的祖先 -> 按坐标 dispatchGesture。
     *
     * @return JSON：{"ok":true,"how":"node|parent|gesture","bounds":[...]}
     */
    public String tapText(final String text) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>("");
        ui.post(new Runnable() {
            public void run() {
                try {
                    AccessibilityNodeInfo root = rootNow();
                    if (root == null) { out.set("{\"ok\":false,\"err\":\"no_root\"}"); return; }
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(text);
                    if (hits == null || hits.isEmpty()) {
                        out.set("{\"ok\":false,\"err\":\"not_found\"}");
                        return;
                    }
                    // 取第一个既可见又有点击能力的目标
                    for (AccessibilityNodeInfo n : hits) {
                        if (n == null) continue;
                        Rect r = new Rect();
                        n.getBoundsInScreen(r);
                        if (r.width() <= 0 || r.height() <= 0) continue;
                        AccessibilityNodeInfo target = n;
                        for (int up = 0; up < 6 && target != null; up++) {
                            if (target.isClickable()) {
                                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                out.set("{\"ok\":" + ok + ",\"how\":\"" + (up == 0 ? "node" : "parent")
                                        + "\",\"bounds\":[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]}");
                                return;
                            }
                            target = target.getParent();
                        }
                        // 都不点不了，直接按坐标点
                        boolean ok = tapXY(r.centerX(), r.centerY());
                        out.set("{\"ok\":" + ok + ",\"how\":\"gesture\",\"bounds\":["
                                + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]}");
                        return;
                    }
                    out.set("{\"ok\":false,\"err\":\"found_but_invisible\"}");
                } catch (Throwable t) {
                    out.set("{\"ok\":false,\"err\":\"" + esc(String.valueOf(t)) + "\"}");
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ie) { }
        String s = out.get();
        return (s == null || s.length() == 0) ? "{\"ok\":false,\"err\":\"timeout\"}" : s;
    }

    /** 按屏幕坐标点一下（像素坐标）。调试自绘界面时用。 */
    public boolean tapXY(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            Log.w("a11y tap " + t);
            return false;
        }
    }

    /** 往下/往上滚一屏。返回是否发出了动作。 */
    public boolean scroll(final boolean down) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> ok = new AtomicReference<Boolean>(false);
        ui.post(new Runnable() {
            public void run() {
                try {
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    float x = dm.widthPixels / 2f;
                    float y1 = down ? dm.heightPixels * 0.72f : dm.heightPixels * 0.30f;
                    float y2 = down ? dm.heightPixels * 0.30f : dm.heightPixels * 0.72f;
                    if (Build.VERSION.SDK_INT >= 24) {
                        Path p = new Path();
                        p.moveTo(x, y1);
                        p.lineTo(x, y2);
                        GestureDescription g = new GestureDescription.Builder()
                                .addStroke(new GestureDescription.StrokeDescription(p, 0, 260))
                                .build();
                        ok.set(dispatchGesture(g, null, null));
                        return;
                    }
                    AccessibilityNodeInfo root = rootNow();
                    if (root != null) {
                        ok.set(root.performAction(down
                                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));
                    }
                } catch (Throwable t) {
                    Log.w("a11y scroll " + t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ie) { }
        Boolean b = ok.get();
        return b != null && b.booleanValue();
    }

    /** 全局返回 / 主页。调试时比 adb 快。 */
    public boolean globalBack() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean globalHome() { return performGlobalAction(GLOBAL_ACTION_HOME); }

    /** 服务是否已经连上（不是"装了"，是"用户在系统设置里打开了"）。 */
    static boolean enabled() { return I != null; }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private static class Counter { int n = 0; int limit = MAX_NODES; }

    /**
     * 取根节点。ListView / RecyclerView 刚变化完立刻读会拿到旧树，
     * 所以如果距上次屏幕事件不足 80ms，先让主线程喘一下再读。
     */
    private AccessibilityNodeInfo rootNow() {
        long idle = System.currentTimeMillis() - lastEventAt;
        if (idle >= 0 && idle < 80) {
            try { Thread.sleep(80 - idle); } catch (InterruptedException ie) { }
        }
        try { return getRootInActiveWindow(); } catch (Throwable t) { return null; }
    }

    private JSONObject node(AccessibilityNodeInfo n, int depth, boolean onlyClickable, Counter c) {
        JSONObject j = new JSONObject();
        try {
            c.n++;
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            int dot = cls.lastIndexOf('.');
            if (dot >= 0) cls = cls.substring(dot + 1);
            j.put("cls", cls);

            CharSequence t = n.getText();
            if (t != null && t.length() > 0) j.put("text", clip(t.toString(), 120));
            CharSequence d = n.getContentDescription();
            if (d != null && d.length() > 0) j.put("desc", clip(d.toString(), 120));
            if (Build.VERSION.SDK_INT >= 18) {
                String vid = n.getViewIdResourceName();
                if (vid != null && vid.length() > 0) j.put("id", vid);
            }

            Rect r = new Rect();
            n.getBoundsInScreen(r);
            j.put("b", new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom));
            j.put("clk", n.isClickable());
            j.put("en", n.isEnabled());
            if (n.isScrollable()) j.put("scrl", true);
            if (n.isCheckable()) j.put("chk", n.isChecked());
            if (n.isEditable()) j.put("edit", true);
            if (n.isFocusable()) j.put("fc", true);

            int cc = n.getChildCount();
            if (cc > 0 && depth < MAX_DEPTH) {
                JSONArray kids = new JSONArray();
                for (int i = 0; i < cc; i++) {
                    if (c.n >= c.limit) break;
                    AccessibilityNodeInfo ch = null;
                    try { ch = n.getChild(i); } catch (Throwable t2) { }
                    if (ch == null) continue;
                    if (onlyClickable) {
                        // 只有可点击的才输出，但子树还得往下走（可点的常常藏在里面）
                        JSONObject sub = node(ch, depth + 1, true, c);
                        if (sub.has("kids")) kids.put(sub);
                        else if (sub.optBoolean("clk", false)) kids.put(sub);
                    } else {
                        kids.put(node(ch, depth + 1, false, c));
                    }
                }
                if (kids.length() > 0) j.put("kids", kids);
            }
        } catch (Throwable t) {
            // 单个节点出错不能把整棵树带崩
        }
        return j;
    }

    private static String clip(String s, int n) {
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() <= n ? s : s.substring(0, n) + "…";
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
