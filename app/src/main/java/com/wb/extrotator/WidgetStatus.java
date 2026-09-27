package com.wb.extrotator;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 「手机状态」小组件（外屏 2×2）要的那几个读数，以及把它们画成一张图。
 *
 * <p><b>四个指标</b>：电量、CPU 温度、内存占用、存储占用。
 *
 * <p><b>为什么不做蓝牙</b>：读蓝牙设备电量要「附近的设备」权限，没给授权时那一格只能写
 * 「未授权」—— 用户看到的就是一句没用的提示（他的原话："蓝牙显示未授权，不行就不要做蓝牙"）。
 * 四个格子宁可都放**不依赖任何权限**的东西，所以第四格换成内部存储占用。
 *
 * <p><b>为什么不用 {@link StatusMonitor}</b>：那个是给「状态展示柜」页面用的 —— 绑着
 * Activity、每秒采一次、还带 60 格历史。桌面小组件是另一回事：进程随时可能被系统回收，
 * 所以这里刻意做得很轻，**一次采完就交给桌面**，不留任何常驻状态。
 *
 * <p><b>为什么整个组件就是一张 Bitmap</b>：RemoteViews 里能用的控件就那么十来种，
 * 想画进度条、想要固定配色，只有一条路 —— 自己画好位图塞进一个 ImageView。
 * （另一条路 ProgressBar 会套上宿主桌面的主题色，外屏上深浅不可控。）
 */
public final class WidgetStatus {

    /** 一次采样的全部读数。字段用 -1 / NaN 表示"没读到"，不抛异常。 */
    public static final class Data {
        /** 电量 0~100；-1 = 没读到 */
        public int battPct = -1;
        public boolean charging;
        /** CPU 相关传感器里最高的那个，摄氏度；NaN = 没读到 */
        public float tempC = Float.NaN;
        /** 内存占用 0~100；-1 = 没读到 */
        public int memPct = -1;
        public long memUsedMb = -1, memTotalMb = -1;
        /** 内部存储占用 0~100；-1 = 没读到 */
        public int storePct = -1;
        public long storeUsedGb = -1, storeTotalGb = -1;
    }

    private WidgetStatus() {
    }

    // ============================================================ 采样

    public static Data sample(Context c) {
        Data d = new Data();
        try {
            sampleBattery(c, d);
        } catch (Throwable ignored) {
        }
        try {
            d.tempC = readTempC();
        } catch (Throwable ignored) {
        }
        try {
            sampleMem(c, d);
        } catch (Throwable ignored) {
        }
        try {
            sampleStore(d);
        } catch (Throwable ignored) {
        }
        return d;
    }

    private static void sampleBattery(Context c, Data d) {
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (p > 0) {
                d.battPct = Math.min(100, p);
            }
        }
        // 充电状态只有粘性广播里有（getIntProperty 那套没有对应项）
        Intent it = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (it != null) {
            int st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            d.charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
            if (d.battPct < 0) {
                int lv = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (lv >= 0 && sc > 0) {
                    d.battPct = lv * 100 / sc;
                }
            }
        }
    }

    private static void sampleMem(Context c, Data d) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem;
        long avail = mi.availMem;
        if (total <= 0) {
            return;
        }
        d.memTotalMb = total / 1048576L;
        d.memUsedMb = Math.max(0, total - avail) / 1048576L;
        d.memPct = (int) Math.round((total - avail) * 100.0 / total);
    }

    /**
     * 内部存储占用。
     *
     * <p>跟「分类里显示的那个数字」同一个口径：{@code Environment.getDataDirectory()} 那块分区
     * 的总量与可用量。⚠ 不用 {@code StatFs(path)} 的其余重载 —— 这里不需要 SDK 30 那套 API。
     */
    private static void sampleStore(Data d) {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes();
        long avail = fs.getAvailableBytes();
        if (total <= 0) {
            return;
        }
        d.storeTotalGb = total / 1073741824L;
        d.storeUsedGb = Math.max(0, total - avail) / 1073741824L;
        d.storePct = (int) Math.round((total - avail) * 100.0 / total);
    }

    /**
     * CPU 相关传感器里最高的那个读数（摄氏度）。
     *
     * <p>跟 {@link StatusMonitor} 一个口径 —— 它读的也是这批 thermal zone 文件；
     * 这里重写一份精简版，是因为那边的方法都是 private、且绑着采样循环。
     */
    private static float readTempC() {
        float best = Float.NaN;
        for (int i = 0; i < 40; i++) {
            File z = new File("/sys/class/thermal/thermal_zone" + i);
            String type = readLine(new File(z, "type"));
            if (type == null) {
                continue;
            }
            String t = type.toLowerCase();
            if (!(t.contains("cpu") || t.contains("soc") || t.contains("tsens")
                    || t.contains("ap") || t.contains("core"))) {
                continue;
            }
            long v = readLong(new File(z, "temp"));
            if (v <= 0) {
                continue;
            }
            float cc = v >= 1000 ? v / 1000f : v;   // 单位是毫摄氏度
            if (Float.isNaN(best) || cc > best) {
                best = cc;
            }
        }
        return best;
    }

    private static String readLine(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static long readLong(File f) {
        String s = readLine(f);
        if (s == null) {
            return -1;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ============================================================ 画

    /**
     * 把四个指标画成一张位图（透明底，圆角与底色由布局那边给）。
     *
     * <p>排法是 2×2：左上电量 · 右上 CPU 温度 · 左下内存 · 右下存储。
     *
     * <p><b>为什么每格不用环形</b>：2×2 的格子只有 4×2 的一半宽（75dp 对 156dp），
     * 环占掉左边一半之后右边就只剩三十来 dp 写字 —— 读数会顶到隔壁格上。
     * 现在每格是「上标签 / 中读数 / 下细条」三行居中排，宽度全给文字用，
     * 「图形化」落在底下那条进度条上。
     *
     * @param w 位图宽（像素）
     * @param h 位图高（像素）；设计基准是 150×133dp，所以 1dp ≈ h/133 像素
     */
    public static Bitmap draw(int w, int h, Data d) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(bmp);
        float k = h / 133f;                     // dp → 像素的等效缩放

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);

        float cw = w / 2f, ch = h / 2f;

        // 电量：绿；充电时改用更亮的青（颜色本身就是"在充电"的信号）
        cell(g, p, k, 0, 0, cw, ch, "电量",
                d.battPct < 0 ? "—" : d.battPct + "%",
                d.battPct < 0 ? -1f : d.battPct / 100f,
                d.charging ? 0xFF26C6DA : 0xFF43A047);

        // CPU 温度：20~70℃ 映射到 0~100%
        cell(g, p, k, 1, 0, cw, ch, "CPU",
                Float.isNaN(d.tempC) ? "—" : (Math.round(d.tempC) + "°"),
                Float.isNaN(d.tempC) ? -1f : (d.tempC - 20f) / 50f,
                tempColor(d.tempC));

        cell(g, p, k, 0, 1, cw, ch, "内存",
                d.memPct < 0 ? "—" : d.memPct + "%",
                d.memPct < 0 ? -1f : d.memPct / 100f, 0xFF7E57C2);

        cell(g, p, k, 1, 1, cw, ch, "存储",
                d.storePct < 0 ? "—" : d.storePct + "%",
                d.storePct < 0 ? -1f : d.storePct / 100f, 0xFF29B6F6);

        return bmp;
    }

    private static int tempColor(float c) {
        if (Float.isNaN(c)) {
            return 0xFF9E9E9E;
        }
        if (c >= 55f) {
            return 0xFFE53935;
        }
        if (c >= 45f) {
            return 0xFFFB8C00;
        }
        return 0xFF43A047;
    }

    /**
     * 画一格：上面标签、中间大读数、底下一条细进度条，三行都居中。
     *
     * @param frac 进度条的填充比例；小于 0 表示"没有数值"（只剩底槽）
     */
    private static void cell(Canvas g, Paint p, float k, int col, int row,
                             float cw, float ch, String label, String value,
                             float frac, int color) {
        float left = col * cw, top = row * ch;
        float cx = left + cw / 2f;

        p.setStyle(Paint.Style.FILL);

        p.setColor(0xB3FFFFFF);
        p.setTextSize(10.5f * k);
        g.drawText(label, cx, top + ch * 0.30f, p);

        p.setColor(color);
        p.setTextSize(17f * k);
        g.drawText(value, cx, top + ch * 0.66f, p);

        float bw = cw * 0.72f;
        float bh = Math.max(1.5f, 3.4f * k);
        float bx = cx - bw / 2f;
        float by = top + ch * 0.80f;
        p.setColor(0x2EFFFFFF);
        g.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), bh / 2f, bh / 2f, p);
        if (frac > 0f) {
            float fw = bw * Math.min(1f, Math.max(0f, frac));
            p.setColor(color);
            g.drawRoundRect(new RectF(bx, by, bx + fw, by + bh), bh / 2f, bh / 2f, p);
        }
    }
}
