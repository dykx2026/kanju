package com.dfy.kx;

/** 日志壳子：Android 上落 logcat，出问题用 adb logcat -s KX 看。 */
public class Log {
    static final String TAG = "KX";
    public static void i(String m) { try { android.util.Log.i(TAG, m); } catch (Throwable t) {} }
    public static void w(String m) { try { android.util.Log.w(TAG, m); } catch (Throwable t) {} }
    public static void e(String m) { try { android.util.Log.e(TAG, m); } catch (Throwable t) {} }
}
