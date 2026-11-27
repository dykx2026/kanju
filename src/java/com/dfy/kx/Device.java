package com.dfy.kx;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * 设备与系统元数据。
 *
 * 为什么单开一个类
 * ----------------
 * 这个 App 要装到各种安卓手机上，而各家 ROM（尤其荣耀 MagicOS / 华为 EMUI）
 * 在窗口、无障碍、悬浮窗上的行为跟原生 AOSP 并不一致。以前排查问题时，
 * 光"设备是什么型号、什么分辨率、什么 ROM"就要来回问好几轮。
 * 所以把能自报的信息一次收集齐，出问题时附上这一坨，接手的人能直接复现环境。
 */
public class Device {

    /** 一次性收齐，成本很低，随便调。 */
    public static String json(Context a) {
        JSONObject j = new JSONObject();
        try {
            j.put("model", Build.MODEL);
            j.put("brand", Build.BRAND);
            j.put("manufacturer", Build.MANUFACTURER);
            j.put("device", Build.DEVICE);
            j.put("board", Build.BOARD);
            j.put("android", Build.VERSION.RELEASE);
            j.put("sdk", Build.VERSION.SDK_INT);
            j.put("patch", Build.VERSION.SECURITY_PATCH == null ? "" : Build.VERSION.SECURITY_PATCH);
            j.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
            j.put("rom", romName());
            j.put("romVer", romVersion());
        } catch (Throwable t) { }

        try {
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            WindowManager wm = (WindowManager) a.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point real = new android.graphics.Point();
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowMetrics wmt = wm.getMaximumWindowMetrics();
                    real.set(wmt.getBounds().width(), wmt.getBounds().height());
                } else {
                    wm.getDefaultDisplay().getRealSize(real);
                }
            }
            // 逻辑尺寸（dp）：WebView 里的 CSS 像素就是按这个算的，调试坐标必须换算
            float dpW = dm.widthPixels / dm.density;
            float dpH = dm.heightPixels / dm.density;
            j.put("pxW", real.x > 0 ? real.x : dm.widthPixels);
            j.put("pxH", real.y > 0 ? real.y : dm.heightPixels);
            j.put("dpW", Math.round(dpW));
            j.put("dpH", Math.round(dpH));
            j.put("density", round2(dm.density));
            j.put("dpi", dm.densityDpi);
            j.put("fontScale", round2(a.getResources().getConfiguration().fontScale));
            j.put("landscape",
                    a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        } catch (Throwable t) { }

        try {
            // 状态栏/导航条高度：页面做全屏和安全区要靠它，各家还不一样
            if (!(a instanceof Activity)) throw new RuntimeException("not_activity");
            View dv = ((Activity) a).getWindow().getDecorView();
            WindowInsets wi = dv.getRootWindowInsets();
            if (wi != null) {
                int top = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets in = wi.getInsets(WindowInsets.Type.statusBars());
                    top = in.top;
                    android.graphics.Insets in2 = wi.getInsets(WindowInsets.Type.navigationBars());
                    bottom = in2.bottom;
                } else {
                    top = wi.getSystemWindowInsetTop();
                    bottom = wi.getSystemWindowInsetBottom();
                }
                j.put("statusBar", top);
                j.put("navBar", bottom);
            }
        } catch (Throwable t) { }

        try {
            PackageManager pm = a.getPackageManager();
            j.put("vulkan", pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION));
            j.put("es3", pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK));
        } catch (Throwable t) { }

        try {
            j.put("a11yOn", KxAccessibility.enabled());
        } catch (Throwable t) { }
        return j.toString();
    }

    // ── ROM 识别 ────────────────────────────────────────────────────────────

    /**
     * 认 ROM。荣耀/华为要单独识别：它们的无障碍、后台保活、权限弹窗
     * 跟原生差别最大，出问题第一时间要确认是不是它。
     */
    public static String romName() {
        String brand = String.valueOf(Build.BRAND).toLowerCase();
        String man = String.valueOf(Build.MANUFACTURER).toLowerCase();
        if (!isEmpty(prop("ro.build.version.magic"))) return "MagicOS";
        if (brand.contains("honor") || man.contains("honor")) {
            return isEmpty(prop("ro.build.version.emui")) ? "MagicOS" : "EMUI";
        }
        if (brand.contains("huawei") || man.contains("huawei")) return "EMUI";
        if (!isEmpty(prop("ro.miui.ui.version.name"))) return "MIUI";
        if (!isEmpty(prop("ro.build.version.opporom"))) return "ColorOS";
        if (!isEmpty(prop("ro.vivo.os.version"))) return "OriginOS";
        if (!isEmpty(prop("ro.oxygen.version"))) return "OxygenOS";
        if (!isEmpty(prop("ro.build.version.oneui"))) return "OneUI";
        return "AOSP";
    }

    public static String romVersion() {
        String[] keys = {
                "ro.build.version.magic", "ro.build.version.emui",
                "ro.miui.ui.version.name", "ro.build.version.opporom",
                "ro.vivo.os.version", "ro.oxygen.version", "ro.build.display.id"
        };
        for (int i = 0; i < keys.length; i++) {
            String v = prop(keys[i]);
            if (!isEmpty(v)) return v;
        }
        return "";
    }

    /** 读系统属性。Build 里没有公开 API，只能反射 android.os.SystemProperties。 */
    static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.length() == 0; }

    private static double round2(float f) { return Math.round(f * 100.0) / 100.0; }
}
