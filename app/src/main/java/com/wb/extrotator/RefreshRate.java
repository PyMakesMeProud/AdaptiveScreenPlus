package com.wb.extrotator;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「内屏管理 → 刷新率」的落盘层：写系统 peak_refresh_rate / min_refresh_rate 上下界，取代自适应。
 * ⚠ 超过 60Hz 的档位要识别到内屏才有效（合盖时在用的是封面屏，只有 60Hz）。
 */
public final class RefreshRate {

    private static final String TAG = "RefreshRate";

    private static final String KEY_PEAK = "peak_refresh_rate";
    private static final String KEY_MIN = "min_refresh_rate";

    /** 档位读不到时的兜底表 */
    private static final float[] FALLBACK = {120f, 96f, 60f, 48f, 30f, 24f, 10f};

    private RefreshRate() {
    }

    /** 内屏支持的全部档位，从高到低、去重 */
    public static float[] supported(Context c) {
        List<Float> out = new ArrayList<>();
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = DisplayUtil.defaultDisplay(dm);
            if (d != null) {
                for (Display.Mode m : d.getSupportedModes()) {
                    float hz = m.getRefreshRate();
                    if (!has(out, hz)) {
                        out.add(hz);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读内屏支持的档位失败：" + t);
        }
        if (out.size() < 2) {
            return FALLBACK.clone();
        }
        Collections.sort(out, Collections.reverseOrder());
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    /** 系统当前的最高刷新率；没设过、读不到时返回 -1。 */
    public static float readPeak(Context c) {
        return read(c, KEY_PEAK);
    }

    /** 系统当前的最低刷新率；没设过、读不到时返回 -1。 */
    public static float readMin(Context c) {
        return read(c, KEY_MIN);
    }

    /** 下发。返回 null = 成功，否则是给人看的原因 */
    public static String apply(Context c, float a, float b) {
        float lo = Math.min(a, b), hi = Math.max(a, b);
        ContentResolver cr = c.getContentResolver();
        try {
            Settings.System.putFloat(cr, KEY_PEAK, hi);
            Settings.System.putFloat(cr, KEY_MIN, lo);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "写刷新率失败：" + t);
            return "写不进去（" + t.getClass().getSimpleName() + "）";
        }
    }

    /** 交回系统：删掉这两个值，重新归系统管 */
    public static String reset(Context c) {
        ContentResolver cr = c.getContentResolver();
        try {
            Settings.System.putString(cr, KEY_PEAK, null);
            Settings.System.putString(cr, KEY_MIN, null);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "还原刷新率失败：" + t);
            return "还原失败（" + t.getClass().getSimpleName() + "）";
        }
    }

    /** 内屏此刻在不在工作 */
    public static boolean innerActive(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            return DisplayUtil.isOn(DisplayUtil.defaultDisplay(dm));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean has(List<Float> l, float v) {
        for (float f : l) {
            if (Math.abs(f - v) < 0.5f) {
                return true;
            }
        }
        return false;
    }

    private static float read(Context c, String key) {
        try {
            String s = Settings.System.getString(c.getContentResolver(), key);
            return s == null || s.isEmpty() ? -1f : Float.parseFloat(s);
        } catch (Throwable t) {
            return -1f;
        }
    }
}
