package com.dfy.kx;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线下载任务的内存表 + 磁盘持久化。
 *
 * 为什么要落盘：App 被系统杀掉、手机重启之后，界面重新打开要能看到
 * 「下到 68% 的任务」并接着下，而不是全部清零。
 */
public class DlStore {

    public static class Task {
        public String id = "";          // 唯一 id（时间戳）
        public String src = "";         // 源 key
        public String vodId = "";       // 片子 id
        public String vodName = "";     // 片子名
        public String pic = "";         // 封面
        public int epIdx = 0;           // 第几集（0 起）
        public String epName = "";      // 集名
        public String referer = "";     // 防盗链 Referer
        public String playlist = "";    // 原始 m3u8 地址
        public String baseUrl = "";     // 清单地址（补全相对路径用）
        public int total = 0;           // 总片数
        public int done = 0;            // 已完成片数
        public long bytes = 0;          // 已下字节
        public int state = 0;           // 0排队 1下载中 2完成 3失败 4已取消
        public String err = "";
        public long ts = 0;

        public JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("src", src); o.put("vodId", vodId);
                o.put("vodName", vodName); o.put("pic", pic);
                o.put("epIdx", epIdx); o.put("epName", epName);
                o.put("referer", referer); o.put("playlist", playlist);
                o.put("baseUrl", baseUrl);
                o.put("total", total); o.put("done", done); o.put("bytes", bytes);
                o.put("state", state); o.put("err", err); o.put("ts", ts);
            } catch (Throwable t) {}
            return o;
        }

        public static Task from(JSONObject o) {
            Task t = new Task();
            t.id = o.optString("id");
            t.src = o.optString("src");
            t.vodId = o.optString("vodId");
            t.vodName = o.optString("vodName");
            t.pic = o.optString("pic");
            t.epIdx = o.optInt("epIdx");
            t.epName = o.optString("epName");
            t.referer = o.optString("referer");
            t.playlist = o.optString("playlist");
            t.baseUrl = o.optString("baseUrl");
            t.total = o.optInt("total");
            t.done = o.optInt("done");
            t.bytes = o.optLong("bytes");
            t.state = o.optInt("state");
            t.err = o.optString("err");
            t.ts = o.optLong("ts");
            return t;
        }

        public int pct() {
            if (total <= 0) return 0;
            return (int) (done * 100L / total);
        }
    }

    static final List<Task> LIST = new ArrayList<Task>();
    static final Object LOCK = new Object();
    static File storeFile;

    /**
     * 只初始化一次。
     *
     * 这里踩过一个坑：原来每次 init 都 load()，而 CoreServer 每个
     * /api/dl/list 请求都会 init 一遍——结果把内存里正在下载、
     * done 已经涨到 80 的那个 Task 对象整个换成磁盘上的旧副本，
     * 界面上的进度永远停在 0。内存是权威，磁盘只在启动时读一次。
     */
    public static void init(Context c) {
        if (storeFile != null) return;
        synchronized (LOCK) {
            if (storeFile != null) return;
            storeFile = new File(c.getFilesDir(), "dl_tasks.json");
            load();
        }
    }

    static void load() {
        synchronized (LOCK) {
            LIST.clear();
            if (storeFile == null || !storeFile.exists()) return;
            try {
                FileInputStream in = new FileInputStream(storeFile);
                StringBuilder sb = new StringBuilder();
                InputStreamReader r = new InputStreamReader(in, "UTF-8");
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                r.close();
                JSONArray a = new JSONArray(sb.toString());
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) LIST.add(Task.from(o));
                }
                // 上次被杀时正在下的任务，重启后标成排队，可以手动续
                for (Task t : LIST) if (t.state == 1) t.state = 0;
            } catch (Throwable t) {
                Log.w("DlStore.load " + t);
            }
        }
    }

    public static void save() {
        synchronized (LOCK) {
            try {
                JSONArray a = new JSONArray();
                for (Task t : LIST) a.put(t.json());
                File tmp = new File(storeFile.getAbsolutePath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
                w.write(a.toString());
                w.close();
                if (storeFile.exists()) storeFile.delete();
                tmp.renameTo(storeFile);
            } catch (Throwable t) {
                Log.w("DlStore.save " + t);
            }
        }
    }

    public static void add(Task t) {
        synchronized (LOCK) {
            for (int i = 0; i < LIST.size(); i++) {
                Task x = LIST.get(i);
                // 同片子同集不重复下载
                if (x.src.equals(t.src) && x.vodId.equals(t.vodId) && x.epIdx == t.epIdx) {
                    LIST.remove(i);
                    break;
                }
            }
            LIST.add(0, t);
        }
        save();
    }

    public static Task get(String id) {
        synchronized (LOCK) {
            for (Task t : LIST) if (t.id.equals(id)) return t;
        }
        return null;
    }

    public static void remove(String id) {
        synchronized (LOCK) {
            for (int i = 0; i < LIST.size(); i++) {
                if (LIST.get(i).id.equals(id)) { LIST.remove(i); break; }
            }
        }
        save();
    }

    public static List<Task> all() {
        synchronized (LOCK) { return new ArrayList<Task>(LIST); }
    }

    /** 一个任务的文件目录 */
    public static File dirOf(Context c, String id) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "dl/" + id);
        if (!d.exists()) d.mkdirs();
        return d;
    }
}
