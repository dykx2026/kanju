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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线下载前台服务。
 *
 * 为什么必须是前台服务：安卓在 App 退到后台几分钟后就会掐掉网络，
 * 下载必断。挂一条常驻通知把进程升到前台，系统才允许一直下。
 *
 * 下载策略：任务串行（一次下一集，避免抢带宽），单集内分片并发 4 路。
 * 实测源站对单连接限速 110~140KB/s，4 路并发能把一集 300MB 从
 * 40 分钟压到 10 分钟出头。
 */
public class DlService extends Service {

    public static final String CH = "kx_dl";

    /**
     * 单集内分片并发路数。
     *
     * 为什么是 8：实测源站对**单连接**限速 110~140KB/s，一路下 300MB 要 40 分钟。
     * 4 路实测能到 8 分钟左右，8 路再快一档（并发上限取决于源站，多数能承受 8~16）。
     * 再往上收益就平了，反而容易被源站拒连，所以定在 8。
     */
    static final int CONC = 8;
    public static final int NID = 7711;

    static DlService INST;

    ExecutorService single = Executors.newSingleThreadExecutor();
    final Object lock = new Object();
    volatile boolean running = false;
    volatile String curId = null;
    volatile String curText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        INST = this;
        DlStore.init(this);
        ensureChannel();
        Log.i("DlService.onCreate tasks=" + DlStore.all().size());
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        Log.i("DlService.onStartCommand startId=" + startId + " running=" + running);
        try { startForeground(NID, build("准备中…", 0, true)); }
        catch (Throwable e) { Log.w("startForeground FAILED " + e); }
        if (it != null && "add".equals(it.getAction())) {
            String id = it.getStringExtra("id");
            if (id != null) kick();
        } else {
            kick();
        }
        return START_STICKY;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, DlService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) { Log.w("DlService.start " + t); }
    }

    public static void poke(Context c) {
        start(c);
        try {
            Intent i = new Intent(c, DlService.class);
            c.startService(i);
        } catch (Throwable t) {}
    }

    void ensureChannel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(CH) == null) {
                    NotificationChannel ch = new NotificationChannel(CH, "离线下载",
                            NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("下载进度");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
        } catch (Throwable t) {}
    }

    Notification build(String text, int pct, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pi = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent p = PendingIntent.getActivity(this, 0, open, pi);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH);
        else b = new Notification.Builder(this);
        b.setContentTitle("看剧 · 离线下载")
         .setContentText(text)
         .setSmallIcon(android.R.drawable.stat_sys_download)
         .setContentIntent(p)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        if (indeterminate) b.setProgress(0, 0, true);
        else if (pct > 0) b.setProgress(100, pct, false);
        return b.build();
    }

    void notify(String text, int pct, boolean ind) {
        curText = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NID, build(text, pct, ind));
        } catch (Throwable t) {}
    }

    /** 挑下一个排队任务开工 */
    void kick() {
        synchronized (lock) {
            if (running) { Log.i("kick: already running"); return; }
            DlStore.Task next = null;
            java.util.List<DlStore.Task> all = DlStore.all();
            StringBuilder sb = new StringBuilder();
            for (DlStore.Task t : all) {
                sb.append(t.id).append(':').append(t.state).append(' ');
                if (t.state == 0 && next == null) next = t;
            }
            Log.i("kick: tasks=" + all.size() + " [" + sb + "]");
            if (next == null) {
                // 没活了：停前台，避免常驻通知一直挂着
                try { stopForeground(true); } catch (Throwable e) {}
                try { stopSelf(); } catch (Throwable e) {}
                return;
            }
            running = true;
            curId = next.id;
            final DlStore.Task task = next;
            task.state = 1;
            DlStore.save();
            single.submit(new Runnable() {
                public void run() {
                    try { doTask(task); }
                    catch (Throwable e) {
                        task.state = 3; task.err = String.valueOf(e);
                        Log.w("dl fail " + android.util.Log.getStackTraceString(e));
                    } finally {
                        DlStore.save();
                        synchronized (lock) { running = false; curId = null; }
                        kick();
                    }
                }
            });
        }
    }

    void doTask(DlStore.Task t) throws Exception {
        Log.i("doTask start " + t.id + " " + t.playlist);
        notify("读取清单：" + t.vodName + " " + t.epName, 0, true);
        Net.Resp r = Net.get(t.playlist, t.referer, null, 0, 20000);
        if (!r.ok() || r.body == null) throw new Exception("清单拉取失败 code=" + r.code);
        String text = r.text().trim();
        String base = t.playlist;

        // 播放页 -> 真清单
        if (!text.startsWith("#EXTM3U")) {
            String inner = Proxy.extractM3u8(text, base);
            if (inner == null) throw new Exception("播放页里没找到 m3u8");
            Net.Resp r2 = Net.get(inner, t.referer, null, 0, 20000);
            if (!r2.ok() || r2.body == null) throw new Exception("m3u8 拉取失败");
            text = r2.text().trim();
            base = inner;
        }
        // 主清单（多码率）-> 直接选第一条子清单
        if (text.indexOf("#EXTINF") < 0) {
            String sub = null;
            for (String ln : text.split("\n")) {
                String s = ln.trim();
                if (s.length() == 0 || s.startsWith("#")) continue;
                sub = Proxy.abs(base, s);
                break;
            }
            if (sub == null) throw new Exception("清单里没有分片");
            Net.Resp r3 = Net.get(sub, t.referer, null, 0, 20000);
            if (!r3.ok() || r3.body == null) throw new Exception("子清单拉取失败");
            text = r3.text().trim();
            base = sub;
        }

        // 解析分片
        List<String> segs = new ArrayList<String>();
        List<String> lines = new ArrayList<String>();
        for (String ln : text.replace("\r\n", "\n").split("\n")) {
            String s = ln.trim();
            if (s.length() == 0) continue;
            if (s.startsWith("#")) { lines.add(s); continue; }
            String u = Proxy.abs(base, s);
            lines.add("__SEG__" + segs.size());
            segs.add(u);
        }
        if (segs.isEmpty()) throw new Exception("分片数为 0");
        t.total = segs.size();
        t.done = 0;
        DlStore.save();

        File dir = DlStore.dirOf(this, t.id);
        final AtomicInteger ok = new AtomicInteger(0);
        final AtomicInteger bytes = new AtomicInteger(0);

        // 分片并发（见 CONC 注释）
        int workers = CONC;
        final List<String> segList = segs;
        final String ref = t.referer;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        final Object plock = new Object();
        List<java.util.concurrent.Future<?>> futs = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < segList.size(); i++) {
            final int idx = i;
            futs.add(pool.submit(new Runnable() {
                public void run() {
                    File f = new File(dir, String.format("seg_%05d.ts", idx));
                    if (f.exists() && f.length() > 0) {
                        ok.incrementAndGet();
                        bytes.addAndGet((int) f.length());
                        bump(t, ok.get(), bytes.get());
                        return;
                    }
                    for (int tryn = 0; tryn < 3; tryn++) {
                        try {
                            Net.Resp rr = Net.get(segList.get(idx), ref, null, 0, 30000);
                            if (rr.ok() && rr.body != null && rr.body.length > 0) {
                                FileOutputStream o = new FileOutputStream(f);
                                try { o.write(rr.body); } finally { o.close(); }
                                ok.incrementAndGet();
                                bytes.addAndGet(rr.body.length);
                                bump(t, ok.get(), bytes.get());
                                return;
                            }
                        } catch (Throwable e) {
                            try { Thread.sleep(600L * (tryn + 1)); } catch (Throwable e2) {}
                        }
                    }
                    Log.w("seg fail " + idx);
                }
            }));
        }
        for (java.util.concurrent.Future<?> fu : futs) {
            try { fu.get(); } catch (Throwable e) {}
        }
        pool.shutdown();

        int got = ok.get();
        if (got < segList.size() * 0.98) {
            throw new Exception("分片缺失 " + got + "/" + segList.size());
        }

        // 写本地清单
        StringBuilder out = new StringBuilder();
        out.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        out.append("#EXT-X-TARGETDURATION:12\n#EXT-X-MEDIA-SEQUENCE:0\n");
        // 简单复刻：保留 EXTINF，分片指向本地
        int segi = 0;
        String lastInf = "#EXTINF:8.0,";
        for (String ln : lines) {
            if (ln.startsWith("#EXTINF")) { lastInf = ln; continue; }
            if (ln.startsWith("__SEG__")) {
                out.append(lastInf).append('\n');
                out.append(String.format("seg_%05d.ts", segi)).append('\n');
                segi++;
            }
        }
        out.append("#EXT-X-ENDLIST\n");
        File ml = new File(dir, "index.m3u8");
        FileOutputStream mo = new FileOutputStream(ml);
        try { mo.write(out.toString().getBytes("UTF-8")); } finally { mo.close(); }

        t.done = segList.size();
        t.bytes = bytes.get();
        t.state = 2;
        t.err = "";
        DlStore.save();
        notify("已完成：" + t.vodName + " " + t.epName, 100, false);
        Log.i("dl done " + t.id + " segs=" + segList.size() + " bytes=" + t.bytes);
    }

    /** 进度上报（节流，别每片都刷通知） */
    long lastBump = 0;
    void bump(DlStore.Task t, int done, int bytes) {
        t.done = done;
        t.bytes = bytes;
        long now = System.currentTimeMillis();
        if (now - lastBump < 900 && done < t.total) return;
        lastBump = now;
        DlStore.save();   // 进度也落盘，进程被杀不至于全丢
        int pct = t.total > 0 ? (int) (done * 100L / t.total) : 0;
        notify(t.vodName + " " + t.epName + " · " + pct + "% (" + (bytes / 1048576) + "MB)", pct, false);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        INST = null;
        super.onDestroy();
    }
}
