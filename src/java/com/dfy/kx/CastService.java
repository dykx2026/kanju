package com.dfy.kx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 投屏期间的前台服务。
 *
 * 为什么必须有：投屏时视频字节要经手机里的代理转发（源站有防盗链，电视自己连不上），
 * App 一被系统冻结，电视那边立刻就断。挂一条常驻通知把进程升到前台，
 * 系统才不会掐它。
 */
public class CastService extends Service {

    public static final String CH = "kx_cast";
    public static final int NID = 7712;
    static volatile String DEV = "";

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        if (it != null && it.getStringExtra("dev") != null) DEV = it.getStringExtra("dev");
        try { startForeground(NID, build()); }
        catch (Throwable t) { Log.w("cast startForeground " + t); }
        return START_STICKY;
    }

    public static void start(Context c, String dev) {
        try {
            Intent i = new Intent(c, CastService.class);
            i.putExtra("dev", dev == null ? "" : dev);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) { Log.w("CastService.start " + t); }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, CastService.class)); } catch (Throwable t) {}
    }

    void ensureChannel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(CH) == null) {
                    NotificationChannel ch = new NotificationChannel(CH, "投屏",
                            NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("投屏进行中（保持转发不断开）");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
        } catch (Throwable t) {}
    }

    Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pi = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent p = PendingIntent.getActivity(this, 0, open, pi);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH);
        else b = new Notification.Builder(this);
        b.setContentTitle("看剧 · 正在投屏")
         .setContentText("投到 " + (DEV.length() == 0 ? "电视" : DEV)
                         + "（App 被系统清掉电视会断，别划掉这条通知）")
         .setSmallIcon(android.R.drawable.ic_menu_slideshow)
         .setContentIntent(p)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
