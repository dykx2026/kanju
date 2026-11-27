package com.dfy.kx;

import android.app.Activity;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 投屏会话的持有者。
 *
 * 为什么要把状态从 MainActivity 搬出来：悬浮遥控窗要在「别的 App 前台」时也能操控电视，
 * 那时候 Activity 可能已经不在前台甚至被回收了。状态挂在 Activity 上就会跟着一起没。
 *
 * 另一个重点是 seekGuard：以前快进/快退完，界面上进度条会自己弹回原处。
 * 原因是轮询每 2 秒把电视回报的位置写回本地，而电视收到 Seek 之后要缓冲一阵
 * 才真的跳过去，这期间它回报的还是旧位置 —— 于是本地刚乐观更新的值立刻被旧值覆盖。
 * 现在发过 seek 之后开一个保护窗口，窗口内只有「电视确实跳到了目标附近」才认它的值。
 */
public class CastCtl {

    /** 谁想知道投屏状态变化（主界面推给网页、悬浮窗刷自己）。 */
    public interface L {
        void onCastState(String json);
    }

    /** 悬浮窗上的「上一集/下一集」需要界面那边配合（只有页面知道选集列表）。 */
    public interface Ep {
        void onEpStep(int delta);
    }

    public static volatile Cast.Device dev = null;
    public static volatile String devName = "";
    public static volatile boolean playing = false;
    public static volatile int pos = -1, dur = -1, vol = -1;

    /** 心跳：连续几次读不到电视状态就当掉线，界面能提示，而不是傻等。 */
    public static volatile boolean online = true;
    static volatile int hbFail = 0;

    /**
     * 兜底时长（秒）。由页面从本地播放器量出来推过来 —— 投屏用的是同一份清单，
     * 本地 video.duration 和电视拿到的是同一个值。电视自己报不出来时用这个，
     * 免得遥控进度条永远是 0。
     */
    static volatile int durFallback = 0;

    public static void setDur(int sec) {
        if (sec > 0) durFallback = sec;
    }

    /** seek 保护窗口 */
    static volatile long seekGuardUntil = 0;
    static volatile int seekTarget = -1;
    static volatile int seekTries = 0;      // 已重发次数（电视不认 Seek 时兜底）

    static volatile boolean pollRun = false;
    static Thread poll = null;

    /**
     * 所有 Cast.* 都是真的网络请求（SOAP over HTTP）。悬浮窗的按钮点击是在主线程
     * 回调的，直接在那边调会抛 NetworkOnMainThreadException —— 而且异常被 catch 掉，
     * 表现就是「点按钮完全没反应」。所以统一丢到这个单线程池里排队执行。
     */
    static java.util.concurrent.ExecutorService NET =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    static void bg(Runnable r) {
        try { NET.execute(r); } catch (Throwable t) { Log.w("castCtl bg " + t); }
    }

    static final List<L> ls = new ArrayList<L>();
    static volatile Ep epSink = null;
    static volatile Context appCtx = null;

    public static void init(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    public static void addListener(L l) {
        synchronized (ls) { if (!ls.contains(l)) ls.add(l); }
        fire();
    }

    public static void removeListener(L l) {
        synchronized (ls) { ls.remove(l); }
    }

    public static void setEpSink(Ep e) { epSink = e; }

    public static boolean active() { return dev != null; }

    public static String stateJson() {
        boolean guard = System.currentTimeMillis() < seekGuardUntil;
        return "{\"on\":" + (dev != null)
             + ",\"name\":\"" + MainActivity.jsEsc(devName) + "\""
             + ",\"pos\":" + pos
             + ",\"dur\":" + dur
             + ",\"vol\":" + vol
             + ",\"playing\":" + playing
             + ",\"seeking\":" + guard
             + ",\"online\":" + online
             + "}";
    }

    static void fire() {
        String j = stateJson();
        List<L> c;
        synchronized (ls) { c = new ArrayList<L>(ls); }
        for (L l : c) {
            try { l.onCastState(j); } catch (Throwable t) {}
        }
    }

    /** 投屏成功后调用：接管状态、起轮询、起前台服务、弹悬浮遥控 */
    public static void attach(Cast.Device d) {
        // 重新投屏时先把上一轮的轮询/前台服务/悬浮窗收干净，
        // 否则会同时跑两条轮询、叠两个悬浮窗。
        if (dev != null) {
            try { stopPoll(); } catch (Throwable t) {}
            Context old = appCtx;
            if (old != null) {
                try { CastService.stop(old); } catch (Throwable t) {}
                try { CastOverlay.hide(old); } catch (Throwable t) {}
            }
        }
        dev = d;
        devName = d == null ? "" : d.name;
        playing = true;
        pos = 0;
        dur = -1;
        vol = -1;
        seekGuardUntil = 0;
        seekTarget = -1;
        hbFail = 0;
        online = true;
        startPoll();
        Context c = appCtx;
        if (c != null) {
            CastService.start(c, devName);
            CastOverlay.show(c);
        }
        fire();
    }

    /**
     * 停投屏。
     *
     * 老写法有个致命顺序问题：把 Stop 丢进线程池之后，**立刻**收前台服务和悬浮窗。
     * 前台服务一收，进程就变成 cached 状态，可能被系统就地冻结，
     * 那条还在路上的 Stop 再也发不出去 —— 实测表现就是「点了结束投屏，电视照放不误」。
     *
     * 现在改成：Stop 真的发完（或 4 秒超时）之后才收服务；收尾放在同一个后台线程里，
     * 界面立刻清空，电视那边也一定收到。另外 Stop 失败时补一发 Pause，
     * 有些电视的 Stop 实现是空的，只认 Pause。
     */
    public static void detach(boolean tellTv) {
        final Cast.Device d = dev;
        final Context c = appCtx;

        // 先把状态清掉：页面/悬浮窗立刻有反馈，不用等网络
        dev = null;
        devName = "";
        playing = false;
        pos = -1;
        dur = -1;
        vol = -1;
        seekGuardUntil = 0;
        seekTarget = -1;
        seekTries = 0;
        stopPoll();
        fire();

        if (!tellTv || d == null) { teardown(c); return; }

        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                try { ok = Cast.stop(d); } catch (Throwable x) {}
                if (!ok) {
                    // 有的电视 Stop 返回 200 但没动作，有的直接不认，补一发 Pause
                    try { Cast.pause(d); } catch (Throwable x) {}
                }
                if (done.compareAndSet(false, true)) teardown(c);
            }
        });
        t.setDaemon(true);
        t.start();

        // 看门狗：电视不在线时 Sop 最坏要等十几秒，不能把前台服务吊在那儿
        Thread w = new Thread(new Runnable() {
            public void run() {
                try { t.join(4000); } catch (Throwable x) {}
                if (done.compareAndSet(false, true)) teardown(c);
            }
        });
        w.setDaemon(true);
        w.start();
    }

    static void teardown(Context c) {
        if (c == null) return;
        try { CastService.stop(c); } catch (Throwable t) {}
        try { CastOverlay.hide(c); } catch (Throwable t) {}
    }

    // ---------------- 轮询 ----------------

    static void startPoll() {
        stopPoll();
        pollRun = true;
        poll = new Thread(new Runnable() {
            public void run() {
                while (pollRun) {
                    try {
                        Cast.Device d = dev;
                        if (d == null) break;
                        int[] p = Cast.position(d);
                        long now = System.currentTimeMillis();
                        /* 心跳：这一秒读到了 = 电视活着；连续 3 秒读不到 = 掉线。
                           以前这里不管成败都静默继续，界面看不出「电视已经不理我了」，
                           用户只会觉得遥控坏了。 */
                        if (p == null) {
                            if (++hbFail >= 3 && online) {
                                online = false;
                                Log.w("cast heartbeat lost");
                                fire();
                            }
                        } else if (hbFail != 0 || !online) {
                            hbFail = 0;
                            online = true;
                        }
                        boolean guard = now < seekGuardUntil && seekTarget >= 0;
                        if (p != null) {
                            if (p[1] > 0) {
                                dur = p[1];
                            } else if (dur <= 0) {
                                /* TrackDuration 拿不到：先试 GetMediaInfo，再试页面量到的值。
                                   两条都没有就保持 -1，界面会提示「电视还没告诉总时长」。 */
                                int md = Cast.mediaDuration(d);
                                if (md > 0) { dur = md; durFallback = md; }
                                else if (durFallback > 0) dur = durFallback;
                            }
                            if (p[0] >= 0) {
                                if (guard) {
                                    // 只有电视真的到了目标附近才认，否则保持本地乐观值
                                    if (Math.abs(p[0] - seekTarget) <= 6) {
                                        pos = p[0];
                                        seekGuardUntil = 0;
                                        seekTarget = -1;
                                        seekTries = 0;
                                    } else if (now > seekGuardUntil - 2000 && seekTries < 2) {
                                        // 保护窗口快到期还没到位：再发一次 Seek。
                                        // 有些渲染器第一次 Seek 会丢掉（正在重新加载清单），
                                        // 不重发就只能眼睁睁看着位置被旧值盖回去。
                                        final int again = seekTarget;
                                        if (d != null) {
                                            seekTries++;
                                            seekGuardUntil = now + 8000;
                                            bg(new Runnable() {
                                                public void run() {
                                                    try { Cast.seek(d, again); } catch (Throwable t) {}
                                                }
                                            });
                                        }
                                    }
                                } else {
                                    // 重试用完还没到位，说明这台电视就是不支持跳转：
                                    // 认现实，别让界面一直显示一个假的进度。
                                    pos = p[0];
                                    seekTarget = -1;
                                    seekTries = 0;
                                }
                            }
                            if (p[2] == 1) playing = true;
                            else if (p[2] == 0) playing = false;
                        }
                        int v = Cast.getVolume(d);
                        if (v >= 0) vol = v;
                        fire();
                    } catch (Throwable t) {
                        Log.w("castPoll " + t);
                    }
                    /* 1 秒一次心跳：位置和播放状态跟得上手，遥控才不像「点了没反应」。
                       GetPositionInfo 是个很小的 SOAP 请求，一秒一次对电视没压力。 */
                    try { Thread.sleep(1000); } catch (Throwable t) { break; }
                }
            }
        });
        poll.setDaemon(true);
        poll.start();
    }

    static void stopPoll() {
        pollRun = false;
        poll = null;
    }

    // ---------------- 遥控 ----------------

    public static void toggle() {
        final Cast.Device d = dev;
        if (d == null) return;
        final boolean was = playing;
        // 先乐观翻转，界面立刻有反馈；网络请求在后台补上
        playing = !was;
        fire();
        bg(new Runnable() {
            public void run() {
                try {
                    if (was) Cast.pause(d); else Cast.resume(d);
                } catch (Throwable t) { Log.w("toggle " + t); }
            }
        });
    }

    public static void pause() {
        final Cast.Device d = dev;
        if (d == null) return;
        playing = false;
        fire();
        bg(new Runnable() {
            public void run() {
                try { Cast.pause(d); } catch (Throwable t) { Log.w("pause " + t); }
            }
        });
    }

    public static void resume() {
        final Cast.Device d = dev;
        if (d == null) return;
        playing = true;
        fire();
        bg(new Runnable() {
            public void run() {
                try { Cast.resume(d); } catch (Throwable t) { Log.w("resume " + t); }
            }
        });
    }

    /** 跳转。返回 false 表示电视拒绝（有些只认 ABS_TIME，Cast.seek 里已经试过两种）。 */
    public static boolean seekTo(int t) {
        final Cast.Device d = dev;
        if (d == null) return false;
        if (dur > 1) {
            if (t > dur - 2) t = dur - 2;
        }
        if (t < 0) t = 0;
        // 先乐观更新，界面立刻跟手（这也是「快进后弹回」的修法：
        // 保护窗口内电视回报的旧位置不覆盖这个值）
        pos = t;
        seekTarget = t;
        seekTries = 0;
        // 6 秒不够：有的电视收到 Seek 后要重新拉清单+缓冲，回报的位置还会滞留在旧值上一两秒。
        // 窗口太短就会被旧值覆盖（=「快进后又弹回去」），这里给到 12 秒。
        seekGuardUntil = System.currentTimeMillis() + 12000;
        fire();
        final int target = t;
        bg(new Runnable() {
            public void run() {
                boolean ok;
                try { ok = Cast.seek(d, target); } catch (Throwable e) { ok = false; }
                if (!ok) {
                    // 不要在这里立刻放弃：有些电视/渲染器对 Seek 回 SOAP 错误，
                    // 但实际已经跳过去了（回话格式不合规范）。一放弃就把乐观值丢掉，
                    // 下一轮轮询拿到旧位置写回界面 → 用户看到「快进又弹回去」。
                    // 交给轮询去确认/重试，窗口到期仍不达标才认现实。
                    Log.w("cast seek soap-false, keep guard target=" + target);
                }
            }
        });
        return true;
    }

    /** 快进/快退。基准取当前本地位置（保护窗口内它就是目标值，不会被旧值污染）。 */
    public static void seekBy(int delta) {
        int cur = pos < 0 ? 0 : pos;
        seekTo(cur + delta);
    }

    public static void setVolume(int v) {
        final Cast.Device d = dev;
        if (d == null) return;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        vol = v;
        fire();
        final int nv = v;
        bg(new Runnable() {
            public void run() {
                try { Cast.volume(d, nv); } catch (Throwable t) { Log.w("vol " + t); }
            }
        });
    }

    public static void epStep(int delta) {
        Ep e = epSink;
        if (e == null) {
            CastOverlay.toastIn("切换选集需要回到 App 里点一下");
            return;
        }
        try { e.onEpStep(delta); } catch (Throwable t) { Log.w("epStep " + t); }
    }

    /** 网页/悬浮窗统一入口。op 见注释。返回 true 表示这条指令发下去了。 */
    public static boolean ctl(String op, String arg) {
        if (dev == null) return false;
        String o = op == null ? "" : op;
        int a = 0;
        try { a = Integer.parseInt(arg == null ? "0" : arg.trim()); }
        catch (Throwable t) { a = 0; }
        if ("toggle".equals(o)) { toggle(); return true; }
        if ("pause".equals(o)) { pause(); return true; }
        if ("resume".equals(o)) { resume(); return true; }
        if ("back".equals(o)) { seekBy(-(a == 0 ? 10 : Math.abs(a))); return true; }
        if ("fwd".equals(o)) { seekBy(a == 0 ? 10 : Math.abs(a)); return true; }
        if ("seek".equals(o)) { return seekTo(Math.max(0, a)); }
        if ("volume".equals(o)) { setVolume(a); return true; }
        if ("nextep".equals(o)) { epStep(1); return true; }
        if ("prevep".equals(o)) { epStep(-1); return true; }
        if ("stop".equals(o)) {
            Context c = appCtx;
            if (c instanceof Activity) {
                ((Activity) c).runOnUiThread(new Runnable() {
                    public void run() { detach(true); }
                });
            } else {
                detach(true);
            }
            return true;
        }
        return false;
    }
}
