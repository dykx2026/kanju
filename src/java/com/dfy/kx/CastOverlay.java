package com.dfy.kx;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 投屏悬浮遥控窗。
 *
 * 为什么做成系统悬浮窗（而不是页面里的二级面板）：投屏之后用户在刷别的 App，
 * 这时候要暂停/快进，如果必须切回来看剧 App 才能操作，投屏就没什么意义了。
 * WindowManager 的 overlay 独立于 Activity，别的 App 在前台时一样能点。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限（设置里「显示在其他应用上层」）。没授权就
 * 安静地不显示，并提示一次；绝不能因为没权限把投屏本身搞挂。
 *
 * 状态不自己存：全部读写 CastCtl，和网页那边共用同一份真值。
 */
public class CastOverlay {

    static WindowManager wm = null;
    static View box = null;       // 展开的面板
    static View pill = null;      // 收起后的胶囊
    static boolean shown = false;
    static Context ctx = null;
    static final Handler UI = new Handler(Looper.getMainLooper());

    static TextView tPos, tTitle, btnPlay, tVol;
    static SeekBar skPos, skVol;
    static boolean dragging = false, dragMoved = false;
    static int dragX, dragY;

    public static boolean canOverlay(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(c);
        } catch (Throwable t) {}
        return true;
    }

    /** 申请权限（跳到系统设置页）。没权限时悬浮窗不可用，但投屏照常。 */
    public static void askPermission(Context c) {
        if (canOverlay(c)) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.w("overlay ask " + t);
        }
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable roundStroke(int color, int strokeCol, int radiusDp, Context c) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, 1), strokeCol);
        return g;
    }

    static WindowManager.LayoutParams lp(int w, int h) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // 必须 NOT_FOCUSABLE：悬浮窗如果可获得焦点，返回键会被它吃掉，
                        // 投屏时按返回就什么都不发生（实测的表现就是「返回键全废」）。
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = dp(ctx, 16);
        p.y = dp(ctx, 220);
        return p;
    }

    public static void show(Context c) {
        if (c == null) return;
        ctx = c.getApplicationContext();
        if (!canOverlay(ctx)) {
            CastOverlay.toastIn("开「显示在其他应用上层」后可用悬浮遥控");
            return;
        }
        UI.post(new Runnable() {
            public void run() { doShow(); }
        });
    }

    static boolean hooked = false;

    static void doShow() {
        // 已经挂了一个就别再挂第二个。
        // 以前页面反复调投屏（失败自动换源时会重投），每次都会 addView 一个新胶囊，
        // 结果屏幕上叠了好几个「遥控中」，而且 listener 也越积越多。
        if (shown && wm != null && pill != null && pill.getParent() != null) {
            refresh();
            return;
        }
        try {
            if (wm == null || !shown) {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            }
            teardown();                       // 清掉任何残留
            box = buildPanel();
            pill = buildPill();
            wm.addView(pill, lp(dp(ctx, 96), dp(ctx, 40)));
            shown = true;
            if (!hooked) {
                hooked = true;
                CastCtl.addListener(new CastCtl.L() {
                    public void onCastState(final String json) {
                        UI.post(new Runnable() { public void run() { refresh(); } });
                    }
                });
            }
            Log.i("overlay shown");
        } catch (Throwable t) {
            Log.w("overlay show " + t);
            shown = false;
        }
    }

    /** 只把视图摘掉，不改 shown 状态语义 */
    static void teardown() {
        try {
            if (wm != null) {
                if (box != null && box.getParent() != null) wm.removeView(box);
                if (pill != null && pill.getParent() != null) wm.removeView(pill);
            }
        } catch (Throwable t) {}
        box = null;
        pill = null;
    }

    public static void hide(Context c) {
        UI.post(new Runnable() {
            public void run() {
                teardown();
                shown = false;
            }
        });
    }

    static void toastIn(final String s) {
        UI.post(new Runnable() {
            public void run() {
                try { if (ctx != null) android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show(); }
                catch (Throwable t) {}
            }
        });
    }

    // ---------------- 收起态胶囊 ----------------

    static View buildPill() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        l.setBackground(round(Color.argb(0xE6, 0x1B, 0x28, 0x38), 20, ctx));

        TextView t = new TextView(ctx);
        t.setText("遥控中");
        t.setTextColor(0xFF66C0F4);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        l.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        l.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { expand(); }
        });
        l.setOnTouchListener(new DragTouch());
        return l;
    }

    // ---------------- 展开态面板 ----------------

    static void expand() {
        try {
            WindowManager.LayoutParams pp = null;
            if (pill != null && pill.getParent() != null) {
                pp = (WindowManager.LayoutParams) pill.getLayoutParams();
                wm.removeView(pill);
            }
            WindowManager.LayoutParams p = lp(dp(ctx, 300), WindowManager.LayoutParams.WRAP_CONTENT);
            if (pp != null) { p.x = pp.x; p.y = pp.y; }
            wm.addView(box, p);
            refresh();
        } catch (Throwable t) { Log.w("overlay expand " + t); }
    }

    static void collapse() {
        try {
            WindowManager.LayoutParams pp = null;
            if (box != null && box.getParent() != null) {
                pp = (WindowManager.LayoutParams) box.getLayoutParams();
                wm.removeView(box);
            }
            WindowManager.LayoutParams p = lp(dp(ctx, 96), dp(ctx, 40));
            if (pp != null) { p.x = pp.x; p.y = pp.y; }
            wm.addView(pill, p);
        } catch (Throwable t) { Log.w("overlay collapse " + t); }
    }

    static TextView tb(String s, int size, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    static View mkBtn(String s, int wDp, final String op, final String arg) {
        TextView t = tb(s, 13, 0xFFC7D5E0);
        t.setBackground(round(0xFF22344A, 9, ctx));
        t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                CastCtl.ctl(op, arg);
                if ("stop".equals(op)) hide(ctx);
            }
        });
        LinearLayout.LayoutParams lp;
        if (wDp > 0) lp = new LinearLayout.LayoutParams(dp(ctx, wDp), dp(ctx, 36));
        else lp = new LinearLayout.LayoutParams(0, dp(ctx, 36), 1f);
        lp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
        t.setLayoutParams(lp);
        return t;
    }

    static View buildPanel() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundStroke(0xFF1B2838, 0xFF2A475E, 14, ctx));
        int pad = dp(ctx, 12);
        root.setPadding(pad, dp(ctx, 8), pad, pad);

        // 标题行：设备名 + 收起
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        tTitle = tb("投屏遥控", 13, 0xFFFFFFFF);
        tTitle.setGravity(Gravity.LEFT);
        head.addView(tTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView min = tb("收起", 12, 0xFF8F98A0);
        min.setPadding(dp(ctx, 8), dp(ctx, 4), 0, dp(ctx, 4));
        min.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { collapse(); }
        });
        head.addView(min);
        root.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.setOnTouchListener(new DragTouch());

        // 进度条
        tPos = tb("00:00 / 00:00", 12, 0xFF8F98A0);
        tPos.setGravity(Gravity.LEFT);
        root.addView(tPos, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        skPos = new SeekBar(ctx);
        skPos.setMax(1000);
        skPos.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar sb) { skDragging = true; }
            public void onStopTrackingTouch(SeekBar sb) {
                skDragging = false;
                int d = CastCtl.dur;
                if (d > 0) CastCtl.ctl("seek", String.valueOf(sb.getProgress() * d / 1000));
            }
        });
        root.addView(skPos, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 30)));

        // 快退 / 播放 / 快进
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(mkBtn("-30", 0, "back", "30"));
        row1.addView(mkBtn("-10", 0, "back", "10"));
        btnPlay = tb("暂停", 14, 0xFF0B1220);
        btnPlay.setBackground(round(0xFF66C0F4, 9, ctx));
        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { CastCtl.ctl("toggle", "0"); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(ctx, 38), 1.4f);
        blp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
        btnPlay.setLayoutParams(blp);
        row1.addView(btnPlay);
        row1.addView(mkBtn("+10", 0, "fwd", "10"));
        row1.addView(mkBtn("+30", 0, "fwd", "30"));
        root.addView(row1);

        // 音量
        LinearLayout rowV = new LinearLayout(ctx);
        rowV.setOrientation(LinearLayout.HORIZONTAL);
        rowV.setGravity(Gravity.CENTER_VERTICAL);
        tVol = tb("音量", 12, 0xFF8F98A0);
        rowV.addView(tVol, new LinearLayout.LayoutParams(dp(ctx, 48),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        skVol = new SeekBar(ctx);
        skVol.setMax(100);
        skVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar sb) { volDragging = true; }
            public void onStopTrackingTouch(SeekBar sb) {
                volDragging = false;
                CastCtl.ctl("volume", String.valueOf(sb.getProgress()));
            }
        });
        rowV.addView(skVol, new LinearLayout.LayoutParams(0, dp(ctx, 30), 1f));
        root.addView(rowV);

        // 选集 + 停止
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(mkBtn("上一集", 0, "prevep", "0"));
        row2.addView(mkBtn("下一集", 0, "nextep", "0"));
        View stop = mkBtn("停止投屏", 0, "stop", "0");
        ((TextView) stop).setTextColor(0xFFFF8A80);
        row2.addView(stop);
        root.addView(row2);

        return root;
    }

    static volatile boolean skDragging = false, volDragging = false;

    static String hms(int s) {
        if (s < 0) s = 0;
        int h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, x);
        return String.format("%02d:%02d", m, x);
    }

    /** 把 CastCtl 的真值刷到控件上。正在拖的时候不覆盖，免得手感被抢。 */
    static void refresh() {
        if (!shown || box == null) return;
        try {
            if (!CastCtl.active()) { hide(ctx); return; }
            tTitle.setText(CastCtl.devName.length() > 0 ? CastCtl.devName : "投屏遥控");
            btnPlay.setText(CastCtl.playing ? "暂停" : "播放");
            if (!skDragging && CastCtl.dur > 0 && CastCtl.pos >= 0) {
                skPos.setProgress(CastCtl.pos * 1000 / CastCtl.dur);
            } else if (!skDragging && CastCtl.pos >= 0 && CastCtl.dur <= 0) {
                skPos.setProgress(0);
            }
            tPos.setText(hms(CastCtl.pos) + " / " + hms(CastCtl.dur));
            if (!volDragging && CastCtl.vol >= 0) skVol.setProgress(CastCtl.vol);
            tVol.setText(CastCtl.vol >= 0 ? ("音量 " + CastCtl.vol) : "音量");
        } catch (Throwable t) {}
    }

    // ---------------- 拖动 ----------------

    static class DragTouch implements View.OnTouchListener {
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    dragMoved = false;
                    dragX = (int) e.getRawX() - p.x;
                    dragY = (int) e.getRawY() - p.y;
                    return false;   // 不吞 DOWN，否则子控件点不到
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    int nx = (int) e.getRawX() - dragX;
                    int ny = (int) e.getRawY() - dragY;
                    if (Math.abs(nx - p.x) > dp(ctx, 4) || Math.abs(ny - p.y) > dp(ctx, 4)) dragMoved = true;
                    p.x = nx;
                    p.y = ny;
                    try { wm.updateViewLayout(v, p); } catch (Throwable t) {}
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return false;
            }
            return false;
        }
    }
}
