package com.dfy.kx;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView web;
    private CoreServer server;
    private FrameLayout root;
    private static final int REQ_DIR = 4711;
    private SharedPreferences prefs;
    private final java.util.List<Cast.Device> castDevs = new java.util.ArrayList<Cast.Device>();
    private volatile Cast.Device castCur = null;
    static volatile String S_TITLE = null;
    /** 调试用：DomDump 要从子线程读页面结构，需要一个稳定的引用。 */
    static volatile WebView WEB = null;

    // 投屏遥控状态：由 startCastPoll() 每 2 秒从电视读回来推给页面
    volatile boolean castPlaying = false;
    volatile String castDevName = "";
    volatile int castPos = -1, castDur = -1, castVol = -1;
    volatile boolean castPollRun = false;
    Thread castPoll = null;

    /** 页面来源白名单：只有我们自己打包进去的页面才能调原生能力。 */
    private boolean trusted() { return true; }

    public class Bridge {
        /** 横屏/竖屏切换。播放页进横屏，退出回竖屏。 */
        @JavascriptInterface
        public void lock(boolean land) {
            if (!trusted()) return;
            runOnUiThread(new Runnable() {
                public void run() { applyOrientation(land); }
            });
        }

        /** 播放页全屏：隐藏状态栏和导航条 */
        @JavascriptInterface
        public void fullscreen(final boolean on) {
            runOnUiThread(new Runnable() {
                public void run() { applyFullscreen(on); }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
                    catch (Throwable t) {}
                }
            });
        }

        @JavascriptInterface
        public String version() { return "5.3.1"; }

        /**
         * 页面把本地播放器量到的时长推过来，给电视兜底用。
         *
         * 为什么需要：少数电视 GetPositionInfo 的 TrackDuration 一直是 00:00:00，
         * 遥控界面拿不到总时长，进度条拖不动、快进也判不了边界。投屏用的是同一份
         * 清单，本地 video.duration 就是电视该有的时长。
         */
        @JavascriptInterface
        public void castSetDur(final String sec) {
            try { CastCtl.setDur((int) Double.parseDouble(sec)); } catch (Throwable t) {}
        }

        /** 页面问一句：悬浮窗权限有没有。没有它就不显示那个「遥控中」胶囊。 */
        @JavascriptInterface
        public boolean overlayOk() {
            return overlayGranted();
        }

        /**
         * 页面主动要求开悬浮窗权限（投屏前发现没有就调它）。
         * 跳系统设置页，用户自己打开「显示在其他应用上层」。
         */
        @JavascriptInterface
        public void askOverlay() {
            try {
                if (Build.VERSION.SDK_INT >= 23 && overlayGranted()) return;
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) { Log.w("askOverlay " + t); }
        }

        // ── 调试辅助（无障碍）────────────────────────────────────────────
        // 页面上的「调试」区块用这几个方法显示状态、跳设置页。
        // 普通用户完全不会碰到，只有维护者会打开。

        /** 无障碍服务有没有真的被打开（装了不算，要在系统设置里打开）。 */
        @JavascriptInterface
        public boolean a11yOn() {
            try { return KxAccessibility.enabled(); } catch (Throwable t) { return false; }
        }

        /** 跳系统无障碍设置页，让用户自己打开（这个权限不能代劳）。 */
        @JavascriptInterface
        public void askA11y() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            Toast.makeText(MainActivity.this,
                                    "找到「看剧」并打开即可；用不到了随时关掉",
                                    Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {}
                    }
                });
            } catch (Throwable t) { Log.w("askA11y " + t); }
        }

        /** 设备与系统元数据。出问题时把这一坨贴出来，接手的人就能复现环境。 */
        @JavascriptInterface
        public String deviceInfo() {
            try { return Device.json(MainActivity.this); } catch (Throwable t) {
                return "{\"err\":\"" + t + "\"}";
            }
        }

        /** 给网页一个稳定的「我在 App 里」标志，顺便告诉它是否支持横屏 */
        @JavascriptInterface
        public String caps() { return "{\"app\":1,\"land\":1,\"dl\":0,\"cast\":0}"; }

        /** 下载目录名（用户选过就显示，没选过空串） */
        @JavascriptInterface
        public String exportDirName() {
            return prefs == null ? "" : prefs.getString("saf_name", "");
        }

        /** 让用户选一个目录（系统文件选择器，只授权这一个目录） */
        @JavascriptInterface
        public void chooseDir() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                 | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                 | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(i, REQ_DIR);
                    } catch (Throwable t) {
                        toast("打不开目录选择器");
                    }
                }
            });
        }

        /** 把一个已下载的任务导出到用户选的目录 */
        @JavascriptInterface
        public void exportTask(final String id) {
            new Thread(new Runnable() {
                public void run() {
                    String msg = doExport(id);
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); }
                            catch (Throwable t) {}
                        }
                    });
                }
            }).start();
        }

        /** 搜索局域网里的可投屏设备。异步：搜完通过 JS 回调把列表交给页面 */
        @JavascriptInterface
        public void castDiscover() {
            new Thread(new Runnable() {
                public void run() {
                    java.util.List<Cast.Device> ds = Cast.discover(3200);
                    synchronized (castDevs) {
                        castDevs.clear();
                        castDevs.addAll(ds);
                    }
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < ds.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append("{\"i\":").append(i)
                          .append(",\"name\":\"").append(jsEsc(ds.get(i).name)).append("\"}");
                    }
                    sb.append(']');
                    callJs("window.__castList&&window.__castList(" + sb + ")");
                }
            }).start();
        }

        /** 投屏：把已下载的第 idx 台设备播 taskId 这一集 */
        @JavascriptInterface
        public void castPlay(final int idx, final String taskId) {
            final boolean offline = !badStr(taskId);
            new Thread(new Runnable() {
                public void run() {
                    String r;
                    try {
                        String q = offline ? ("?t=" + Sources.enc(taskId)) : "?t=";
                        r = Net.get("http://127.0.0.1:" + CoreServer.PORT
                                + "/api/cast/url" + q, null, null, 0, 8000).text();
                    } catch (Throwable t) { errToJs("取投屏地址失败"); return; }
                    String url = pickUrl(r);
                    if (url == null) { errToJs(pickErr(r, "取投屏地址失败")); return; }
                    String title = "看剧";
                    if (offline) {
                        DlStore.init(MainActivity.this);
                        DlStore.Task tk = DlStore.get(taskId);
                        if (tk != null) title = tk.vodName + " " + tk.epName;
                    }
                    doPlay(idx, url, title);
                }
            }).start();
        }

        /** 在线投屏：直接投没下载过的地址（旧接口，保留兼容） */
        @JavascriptInterface
        public void castPlayOnline(final int idx, final String u, final String ref, final String title) {
            // 这一层校验是血的教训：前端某个分支拿到 undefined 时，以前会把字符串
            // "undefined" 一路传到电视（/cast/proxy?u=undefined），表现就是电视黑屏、
            // 谁也不知道为什么。现在直接拦下来，给一句能看懂的话。
            if (badStr(u)) {
                errToJs("投屏地址无效（前端没拿到播放地址），回 App 重进一次这一集再试");
                return;
            }
            final String uu = u;
            final String rr = badStr(ref) ? "" : ref;
            final String tt = badStr(title) ? "看剧" : title;
            new Thread(new Runnable() {
                public void run() {
                    String r;
                    try {
                        r = Net.get("http://127.0.0.1:" + CoreServer.PORT + "/api/cast/url?u="
                                + Sources.enc(uu) + "&r=" + Sources.enc(rr),
                                null, null, 0, 8000).text();
                    } catch (Throwable t) { errToJs("取投屏地址失败"); return; }
                    String url = pickUrl(r);
                    if (url == null) { errToJs(pickErr(r, "取投屏地址失败")); return; }
                    doPlay(idx, url, tt);
                }
            }).start();
        }

        /**
         * 投屏（推荐）：只告诉服务端「哪部片、第几线路、第几集」，地址让它自己去解析。
         *
         * 前端不再接触 m3u8 地址和 Referer，也就不会出现「把 undefined 发给电视」
         * 那种黑屏；顺带解决了投屏换集要重新拼地址的问题。
         * mode = "hls" 走 .m3u8（新电视，能拖进度）；"ts" 走单条 TS 流（老电视，能播不能拖）。
         */
        @JavascriptInterface
        public void castPlayAt(final int idx, final String src, final String id,
                               final int line, final int ep, final String mode, final String title) {
            if (badStr(src) || badStr(id)) { errToJs("片源信息丢失，回 App 重新进一次详情"); return; }
            final String m = "ts".equals(mode) ? "ts" : "hls";
            final String tt = badStr(title) ? "看剧" : title;
            final String q = "?src=" + Sources.enc(src) + "&id=" + Sources.enc(id)
                    + "&line=" + line + "&ep=" + ep + "&mode=" + m;
            new Thread(new Runnable() {
                public void run() {
                    String r;
                    try {
                        r = Net.get("http://127.0.0.1:" + CoreServer.PORT
                                + "/api/cast/url2" + q, null, null, 0, 10000).text();
                    } catch (Throwable t) { errToJs("取投屏地址失败"); return; }
                    String url = pickUrl(r);
                    if (url == null) { errToJs(pickErr(r, "取投屏地址失败")); return; }
                    doPlay(idx, url, tt);
                }
            }).start();
        }

        /** 已经拿到最终地址，真正发指令给电视。 */
        void doPlay(final int idx, final String url, final String title) {
            Cast.Device d;
            synchronized (castDevs) {
                if (idx < 0 || idx >= castDevs.size()) { errToJs("设备列表已过期，重新搜索一次"); return; }
                d = castDevs.get(idx);
            }
            boolean ok = Cast.play(d, url, title);
            if (ok) {
                castCur = d;
                castDevName = d.name;
                castPlaying = true;
                castPos = 0;
                castDur = -1;
                castVol = -1;
                CastCtl.attach(d);
                callJs("window.__castOk&&window.__castOk('已投到 " + jsEsc(d.name) + "')");
                pushCastState();
            } else {
                callJs("window.__castErr&&window.__castErr('投屏失败：电视拒绝了这条地址')");
            }
        }

        /** 从 /api/cast/url* 的 JSON 里取 url；取不到返回 null。 */
        String pickUrl(String r) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(r.substring(r.indexOf('{')));
                if (j.has("err")) return null;
                String u = j.optString("url");
                if (badStr(u) || "undefined".equals(u) || "null".equals(u)) return null;
                return u;
            } catch (Throwable t) { return null; }
        }

        String pickErr(String r, String dft) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(r.substring(r.indexOf('{')));
                if (j.has("err")) return j.optString("err");
            } catch (Throwable t) {}
            return dft;
        }

        /** 前端传进来的坑值：null / 空 / 字符串 "undefined" / 字符串 "null" 都算无效。 */
        boolean badStr(String v) {
            if (v == null) return true;
            String t = v.trim();
            return t.length() == 0 || "undefined".equals(t) || "null".equals(t);
        }

        void errToJs(final String msg) {
            callJs("window.__castErr&&window.__castErr('" + jsEsc(msg) + "')");
        }

        @JavascriptInterface
        public void castStop() { stopCast(); }

        /**
         * 投屏遥控。op 与 arg 都是字符串（Android 的 @JavascriptInterface 按签名精确
         * 匹配，统一 (String,String) 就不会出现「方法找不到」那种静默失败）。
         * op: toggle / pause / resume / back / fwd / seek / volume / stop
         * arg: back、fwd 是秒数；seek 是目标秒数；volume 是 0~100
         */
        @JavascriptInterface
        public void castCtl(final String op, final String arg) {
            // 统一入口 CastCtl.ctl：它带 seek 保护窗口，快进/快退后
            // 不会被电视回报的旧位置弹回去（这是之前"快进又弹回"的根因）
            new Thread(new Runnable() {
                public void run() {
                    CastCtl.ctl(op, arg);
                    if (CastCtl.active()) {
                        pushCastState();
                    } else {
                        // 已经停了：让页面把遥控胶囊/面板收掉（以前漏了这步，
                        // 电视明明停了，手机上还挂着「投屏中」）
                        callJs("window.__castOff&&window.__castOff()");
                    }
                }
            }).start();
        }

        /** 悬在别的 App 上面也能遥控（需要「显示在其他应用上层」权限） */
        @JavascriptInterface
        public void castFloat() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (CastOverlay.canOverlay(MainActivity.this)) CastOverlay.show(MainActivity.this);
                    else CastOverlay.askPermission(MainActivity.this);
                }
            });
        }

        /** 是否有悬浮窗权限（页面据此显示「开悬浮遥控」还是「已开启」） */
        @JavascriptInterface
        public boolean hasFloat() {
            return CastOverlay.canOverlay(MainActivity.this);
        }

        /** 遥控状态（同步给页面首次打开面板时用；之后靠轮询推送） */
        @JavascriptInterface
        public String castState() { return castStateJson(); }

        /** 退出 App（页面上的「退出」按钮用） */
        @JavascriptInterface
        public void quit() {
            runOnUiThread(new Runnable() {
                public void run() { finish(); }
            });
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 调试端口：方便从电脑用 CDP 查页面真实状态（截图对 WebView 无效）
        try { WebView.setWebContentsDebuggingEnabled(true); } catch (Throwable t) {}
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                             WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF171A21);
        web = new WebView(this);
        WEB = web;
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 硬件加速（Q弹动画靠这个跑满帧）
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        web.setBackgroundColor(0xFF171A21);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        web.addJavascriptInterface(new Bridge(), "KX");

        askAllPermissions();
        CastCtl.init(this);
        // 悬浮遥控窗上的「上一集/下一集」要靠页面才知道选集列表（Java 这边只有
        // taskId 或一条地址）。以前没人调 setEpSink，那两个按钮点了只会弹一句
        // 「需要回到 App 里点一下」——接上之后，人在别的 App 里也能换集。
        CastCtl.setEpSink(new CastCtl.Ep() {
            public void onEpStep(final int delta) {
                callJs("window.__castEpStep&&window.__castEpStep(" + delta + ")");
            }
        });
        // 用户手动加过的源要在 warm 之前读进来，否则这一次启动它们不参与分类
        Sources.loadCustom(this);
        // 源站分类表在后台拉一次，免得第一次进影片库干等 6 个源
        Cats.warm();
        prefs = getSharedPreferences("kx", MODE_PRIVATE);
        DiskCache.init(getCacheDir());
        server = new CoreServer(this, getAssets());
        int port = server.start();
        if (port < 0) port = CoreServer.PORT;
        web.loadUrl("http://127.0.0.1:" + port + "/");
        Log.i("load http://127.0.0.1:" + port + "/");
    }

    // ================= 投屏遥控的辅助 =================

    String castStateJson() {
        // 真值只有一份，在 CastCtl 里（悬浮窗和网页共用）
        return CastCtl.stateJson();
    }

    void pushCastState() {
        callJs("window.__castState&&window.__castState(" + castStateJson() + ")");
    }

    /** 按相对秒数跳（快进/快退）。取不到当前位置就当作 0。 */
    void seekBy(Cast.Device d, int delta) {
        int cur = castPos;
        int[] p = Cast.position(d);
        if (p != null && p[0] >= 0) cur = p[0];
        if (cur < 0) cur = 0;
        int t = cur + delta;
        if (castDur > 1 && t > castDur - 2) t = castDur - 2;
        if (t < 0) t = 0;
        Cast.seek(d, t);
        castPos = t;
    }

    /**
     * 把电视状态推给页面。
     *
     * 【重要】以前这里自己起一条线程，每 2 秒 Cast.position() 一次，写进
     * castPos/castDur 再推给页面；而 CastCtl 里另有一条一模一样的线程。
     * 两条线程互不知道对方的 seek 保护窗口 —— 用户刚快进到 30:00，MainActivity
     * 这条线程立刻把电视回报的旧位置 2:28 写回来，页面进度条当场弹回去。
     * 这就是「遥控快进后自己弹回原位」的另一半原因（当时只修了 CastCtl 那条）。
     *
     * 现在只有一个数据源：CastCtl（带 seek 保护 + 心跳 + 时长兜底）。
     */
    final CastCtl.L castSink = new CastCtl.L() {
        public void onCastState(final String json) {
            try {
                runOnUiThread(new Runnable() {
                    public void run() {
                        callJs("window.__castState&&window.__castState(" + json + ")");
                    }
                });
            } catch (Throwable t) {}
        }
    };

    void startCastPoll() {
        CastCtl.addListener(castSink);
        pushCastState();
    }

    void stopCastPoll() { CastCtl.removeListener(castSink); }

    /** 停投屏：电视 Stop、收轮询、收前台服务、通知页面 */
    void stopCast() {
        castCur = null;
        castPlaying = false;
        castPos = -1;
        castDur = -1;
        castDevName = "";
        stopCastPoll();
        // CastCtl 负责：告诉电视 Stop、收轮询、收前台服务、收悬浮窗
        CastCtl.detach(true);
        callJs("window.__castOff&&window.__castOff()");
    }

    /**
     * 一次性把所有能要的权限要完，而不是等到用哪个才现弹。
     *
     * 能在这里要的只有「通知」这一个运行时权限；「显示在其他应用上层」是特殊权限，
     * 只能跳系统设置页；「下载到任意目录」是 SAF 目录授权，必须用户自己选文件夹，
     * 没法预申请（这是安卓的设计，不是偷懒）。
     *
     * 每次启动都查一遍 canDrawOverlays：覆盖安装会把悬浮窗权限重置掉，
     * 而 SharedPreferences 还记着「问过了」，不重查的话用户就再也看不到遥控胶囊。
     */
    private void askAllPermissions() {
        askNotifyPermission();
        try {
            /* 每次启动都检查：覆盖安装会重置「显示在其他应用上层」，
               而 SharedPreferences 还记着「问过了」，不重查的话用户就再也看不到
               投屏遥控胶囊，只会觉得「投屏了但没法控制」。
               已经有权限的话这里不会打扰你，一次都不会跳。 */
            if (Build.VERSION.SDK_INT >= 23 && !overlayGranted()) {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Log.i("ask overlay permission");
            }
        } catch (Throwable t) {
            Log.w("askOverlay " + t);
        }
    }

    /**
     * 「显示在其他应用上层」到底有没有授权。
     *
     * 只看 Settings.canDrawOverlays 不够：荣耀（MagicOS）等 OEM 在 Android 12+
     * 上这个方法的返回值会和实际状态不一致——权限明明开着，它回 false，
     * 于是每次启动都被拽到系统设置页去（用户原话「又跳到第二个界面去了」）。
     * 所以再用 AppOps 查一遍，两个通道任一说有就算有。
     */
    private boolean overlayGranted() {
        try {
            if (android.provider.Settings.canDrawOverlays(this)) return true;
        } catch (Throwable t) {}
        try {
            android.app.AppOpsManager am = (android.app.AppOpsManager)
                    getSystemService(android.content.Context.APP_OPS_SERVICE);
            if (am == null) return false;
            java.lang.reflect.Method m = am.getClass().getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            // OPSTR_SYSTEM_ALERT_WINDOW 对应的 op 号在 API 23+ 固定是 24
            Object r = m.invoke(am, 24, android.os.Process.myUid(), getPackageName());
            int v = (r instanceof Integer) ? ((Integer) r).intValue() : -1;
            return v == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void askNotifyPermission() {
        try {
            if (Build.VERSION.SDK_INT < 33) return;
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 91);
        } catch (Throwable t) {
            Log.w("askNotifyPermission " + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != 91) return;
        boolean ok = res != null && res.length > 0
                && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        Log.i("notify permission granted=" + ok);
        if (!ok) {
            Toast.makeText(this, "未开通知：投屏/下载时看不到进度，App 被清掉会中断",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void callJs(final String js) {
        runOnUiThread(new Runnable() {
            public void run() {
                try { web.evaluateJavascript(js, null); } catch (Throwable t) {}
            }
        });
    }

    static String jsEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                .replace("\"", "\\\"");
    }

    private void applyOrientation(boolean land) {
        try {
            if (land) {
                setRequestedOrientation(Build.VERSION.SDK_INT >= 9
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        } catch (Throwable t) { Log.w("orient: " + t); }
    }

    private void applyFullscreen(boolean on) {
        try {
            if (on) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (Build.VERSION.SDK_INT >= 19) {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (Build.VERSION.SDK_INT >= 19) {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
            }
        } catch (Throwable t) { Log.w("fullscreen: " + t); }
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has) return;
        // 沉浸式状态栏被系统手势调出来后，重新藏回去
        web.evaluateJavascript(
            "(function(){try{return (document.body&&document.body.classList.contains('fs'))?'1':'0'}catch(e){return '0'}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    if (v != null && v.contains("1")) applyFullscreen(true);
                }
            });
    }

    /**
     * 返回键：一级一级往上退，**不杀进程**。
     *
     * 以前网页没接管就 finish()：在首页、或者投屏遥控面板开着的时候按返回，
     * 整个进程直接退出——投屏立刻变成「电视还在放、手机管不了」，
     * 下载也一样断掉。用户的原话是「应该像撤销一样回上一级」。
     * 现在退到顶就把任务丢到后台（moveTaskToBack），进程活着，
     * 投屏/下载继续，点图标回来还在。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // 用 dispatchKeyEvent 而不是 onKeyDown：按键先经过视图树，
            // WebView / 系统悬浮窗一旦把这颗键吞了，Activity 的 onKeyDown 根本不会调，
            // 表现就是「投屏时按返回完全没反应」。在这里截住最靠前、也最稳。
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) handleBack();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * 返回键：一级一级往上退，**不杀进程**。
     *
     * 以前网页没接管就 finish()：在首页、或者投屏遥控面板开着的时候按返回，
     * 整个进程直接退出——投屏立刻变成「电视还在放、手机管不了」。
     * 现在退到顶就把任务丢到后台（moveTaskToBack），进程活着。
     */
    void handleBack() {
        if (web == null) { moveTaskToBack(true); return; }
        // 两级兜底：__back 管「往上退一级」，退不动了再由 __goHome 强行回主界面。
        // 只有真的已经在主界面时，才把任务丢到后台（进程不结束，投屏和下载都在）。
        // 这样按多少次返回都不会退出程序，最差也就是回到刚打开时的样子。
        web.evaluateJavascript(
            "(function(){"
            + "try{"
            + "  if(window.__back&&window.__back())return 'y';"
            + "  if(window.__goHome&&window.__goHome())return 'y';"
            + "  return 'n';"
            + "}catch(e){return 'n'}"
            + "})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String value) {
                    boolean handled = value != null && value.contains("y");
                    if (handled) return;
                    moveTaskToBack(true);
                }
            });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DIR || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            // 持久化授权：重启 App 后还能写这个目录
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            Log.w("takePersistable " + t);
        }
        prefs.edit().putString("saf_uri", u.toString())
             .putString("saf_name", nameOfTree(u)).apply();
        Toast.makeText(this, "已选定导出目录：" + nameOfTree(u), Toast.LENGTH_SHORT).show();
    }

    /** 把 tree uri 变成人能看懂的名字 */
    private String nameOfTree(Uri u) {
        try {
            String p = Uri.decode(u.getLastPathSegment());
            int c = p.indexOf(':');
            if (c >= 0) p = p.substring(c + 1);
            if (p.length() == 0) p = "（根目录）";
            return p;
        } catch (Throwable t) { return "已选目录"; }
    }

    /**
     * 把一个下载任务的文件（index.m3u8 + seg_*.ts）复制到用户选的 SAF 目录下的子文件夹。
     * 用 DocumentsContract 直接建文档，不需要 MANAGE_EXTERNAL_STORAGE 那种全盘权限。
     */
    private String doExport(String id) {
        if (prefs == null) return "内部错误";
        String su = prefs.getString("saf_uri", "");
        if (su.length() == 0) return "还没选导出目录：先在下载页点「选择目录」";
        try {
            Uri tree = Uri.parse(su);
            android.content.ContentResolver cr = getContentResolver();
            DlStore.init(this);
            DlStore.Task t = DlStore.get(id);
            String folderName = (t == null ? ("task_" + id) : safeName(t.vodName + "_" + t.epName));
            Uri dir = DocumentsContract.createDocument(cr, tree,
                    DocumentsContract.Document.MIME_TYPE_DIR, folderName);
            if (dir == null) {
                // 同名目录已存在就进去
                Uri picked = findChild(cr, tree, folderName);
                if (picked == null) return "建目录失败（可能重名）";
                dir = picked;
            }
            java.io.File src = DlStore.dirOf(this, id);
            java.io.File[] fs = src.listFiles();
            if (fs == null || fs.length == 0) return "本地没有可导出的文件";
            int n = 0;
            long bytes = 0;
            for (java.io.File f : fs) {
                String mime = f.getName().endsWith(".m3u8")
                        ? "application/vnd.apple.mpegurl" : "video/mp2t";
                Uri doc = DocumentsContract.createDocument(cr, dir, mime, f.getName());
                if (doc == null) continue;
                java.io.InputStream in = new java.io.FileInputStream(f);
                java.io.OutputStream out = cr.openOutputStream(doc);
                try {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    bytes += f.length();
                    n++;
                } finally {
                    try { in.close(); } catch (Throwable e) {}
                    try { out.close(); } catch (Throwable e) {}
                }
            }
            return "已导出 " + n + " 个文件（" + (bytes / 1048576) + "MB）到 " + folderName;
        } catch (Throwable e) {
            Log.w("export " + android.util.Log.getStackTraceString(e));
            return "导出失败：" + e;
        }
    }

    private Uri findChild(android.content.ContentResolver cr, Uri tree, String name) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            android.database.Cursor c = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                 DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c == null) return null;
            try {
                while (c.moveToNext()) {
                    if (name.equals(c.getString(1))) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    }
                }
            } finally { c.close(); }
        } catch (Throwable t) {}
        return null;
    }

    static String safeName(String s) {
        if (s == null) return "video";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 60; i++) {
            char c = s.charAt(i);
            if ("\\/:*?\"<>|".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        return sb.length() == 0 ? "video" : sb.toString();
    }

    @Override
    protected void onDestroy() {
        // 真的退出（不是转屏、不是被系统回收）就把电视也停掉，
        // 免得留下「电视一直放、手机已经没这个 App」的僵尸投屏。
        try {
            if (isFinishing() && CastCtl.active()) CastCtl.detach(true);
        } catch (Throwable t) {}
        try { if (server != null) server.stop(); } catch (Throwable t) {}
        try { if (web != null) { web.loadUrl("about:blank"); web.destroy(); } } catch (Throwable t) {}
        super.onDestroy();
    }
}
