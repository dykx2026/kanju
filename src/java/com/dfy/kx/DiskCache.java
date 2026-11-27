package com.dfy.kx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 分片磁盘缓存。
 *
 * 为什么要有它：一集 1080p 往往 300~800MB，全塞内存必 OOM。
 * 但预取又不能没有——源站对单连接限速（实测 110~140KB/s），
 * 播放器顺序取片时每片要等 5 秒以上，必须提前把后面几片拉到本地。
 *
 * 所以：热数据留在内存（最近几十片，读的时候零拷贝），
 *       全部落盘（上限 1.2GB，按最后访问时间淘汰）。
 * 落盘用 hash 分两级目录，避免单目录几万个文件。
 */
public class DiskCache {

    static File root;
    static final long MAX_BYTES = 1200L * 1024 * 1024;   // 1.2GB
    static long used = 0;
    static final Object LOCK = new Object();

    public static void init(File dir) {
        root = new File(dir, "seg");
        if (!root.exists()) root.mkdirs();
        used = calcUsed();
        Log.i("DiskCache " + root + " used=" + (used / 1048576) + "MB");
    }

    static long calcUsed() {
        long n = 0;
        File[] ds = root.listFiles();
        if (ds == null) return 0;
        for (File d : ds) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) n += f.length();
        }
        return n;
    }

    static String hash(String u) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(u.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte x : b) {
                int v = x & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(u.hashCode());
        }
    }

    static File fileOf(String url) {
        String h = hash(url);
        File d = new File(root, h.substring(0, 2));
        if (!d.exists()) d.mkdirs();
        return new File(d, h.substring(2) + ".bin");
    }

    /** 读缓存；没有返回 null */
    public static byte[] get(String url) {
        File f = fileOf(url);
        if (!f.exists() || f.length() == 0) return null;
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                long n = raf.length();
                if (n > 64L * 1024 * 1024) return null;   // 超大不要，肯定不是分片
                byte[] b = new byte[(int) n];
                raf.readFully(b);
                f.setLastModified(System.currentTimeMillis());   // 触摸，LRU 用
                return b;
            } finally { raf.close(); }
        } catch (Throwable t) {
            Log.w("DiskCache.get " + t);
            return null;
        }
    }

    public static boolean has(String url) {
        File f = fileOf(url);
        return f.exists() && f.length() > 0;
    }

    /** 写缓存（异步落盘，调用方不必等） */
    public static void put(String url, byte[] data) {
        if (data == null || data.length == 0) return;
        if (data.length > 64L * 1024 * 1024) return;
        File f = fileOf(url);
        try {
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(data); } finally { out.close(); }
            synchronized (LOCK) {
                used += data.length;
                if (used > MAX_BYTES) trim();
            }
        } catch (Throwable t) {
            Log.w("DiskCache.put " + t);
        }
    }

    /** 超上限就按「最久没访问」删掉一批 */
    static void trim() {
        try {
            ArrayList<File> all = new ArrayList<File>();
            File[] ds = root.listFiles();
            if (ds == null) return;
            for (File d : ds) {
                File[] fs = d.listFiles();
                if (fs == null) continue;
                all.addAll(Arrays.asList(fs));
            }
            final File[] arr = all.toArray(new File[0]);
            Arrays.sort(arr, new Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            long goal = (long) (MAX_BYTES * 0.75);
            long cur = used;
            for (File f : arr) {
                if (cur <= goal) break;
                long n = f.length();
                if (f.delete()) cur -= n;
            }
            used = cur;
            Log.i("DiskCache trim -> " + (used / 1048576) + "MB");
        } catch (Throwable t) {
            Log.w("DiskCache.trim " + t);
        }
    }

    /** 清空（离线下载成功后、或设置里点清理时用） */
    public static void clear() {
        try {
            File[] ds = root.listFiles();
            if (ds != null) for (File d : ds) {
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) f.delete();
            }
            used = 0;
        } catch (Throwable t) {}
    }

    public static long used() { return used; }
}
