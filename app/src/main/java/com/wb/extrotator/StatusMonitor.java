package com.wb.extrotator;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 「状态展示柜」的数据源：每秒采一轮 CPU / 内存 / 温度 / 刷新率 / 电量。
 * ⚠ 读文件都走后台线程，{@link Cb} 回调已切回主线程。
 */
public final class StatusMonitor {

    private static final String TAG = "ExtStatus";

    /** 采样周期。占用率靠两次采样作差，再快没意义 */
    private static final long PERIOD_MS = 1000L;

    /** 曲线保留多少个点（1 点 = 1 秒） */
    public static final int HIST = 60;

    /** 有效温度区间（℃）。thermal 里普遍有 -273 这类占位值，落到区间外就当没读到 */
    private static final float TEMP_MIN = -20f, TEMP_MAX = 200f;

    /** 一次采样拿到的全部数据；界面只读它，不再自己碰文件 */
    public static final class Snap {
        /** CPU 总占用率 0~100；-1 = 还测不出来（第一轮只建基线） */
        public int cpuPct = -1;
        public int memPct = -1;
        public long memUsedMb = -1, memTotalMb = -1;
        /** 所有 CPU 相关传感器里最高的那个；NaN = 没读到 */
        public float tempC = Float.NaN;
        /** 主屏（内屏）当前刷新率；NaN = 没读到 */
        public float refreshHz = Float.NaN;
        /** 内屏物理分辨率，底栏用 */
        public int panelW = 0, panelH = 0;
        public int battPct = -1;
        public boolean charging;

        /** 每个簇（policy）的当前 / 最高频率（KHz）与核数；读不到就是空数组 */
        public int[] curKhz = new int[0];
        public int[] maxKhz = new int[0];
        public int[] nrCores = new int[0];

        /** CPU 占用历史，末尾是最新；未填满时前面是 -1 */
        public int[] hist = new int[HIST];
    }

    /** 采样回调，<b>已在主线程</b> */
    public interface Cb {
        void onSample(Snap s);
    }

    private final Context app;
    private Activity host;
    private final Cb cb;

    /** 占用历史，跨采样累积 */
    private final int[] hist = new int[HIST];

    private ScheduledExecutorService exec;

    // ---- 下面几项都是"探一次就记住"的路径缓存，省掉每秒几十次 listFiles ----
    /** 每个核的空闲计时文件：idlePaths[cpu] = {state0/time, state1/time, ...} */
    private String[][] idlePaths;
    /** CPU 相关温度传感器的 temp 路径 */
    private String[] tempPaths;
    /** 各簇的 cpufreq 目录 */
    private File[] policyDirs;
    /** 各簇的最高频率（KHz），跟 policyDirs 一一对应 */
    private int[] policyMaxKhz;

    // ---- CPU 占用的差分基线 ----
    private long lastIdleUs = -1, lastWallUs = -1;
    private List<Integer> lastCpus;

    public StatusMonitor(Activity host, Cb cb) {
        this.host = host;
        this.cb = cb;
        this.app = host.getApplicationContext();
        java.util.Arrays.fill(hist, -1);
    }

    /** 起采样线程（立刻采一轮，之后每 {@link #PERIOD_MS} 一轮） */
    public void start() {
        stop();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "extrot-status");
            t.setDaemon(true);
            return t;
        });
        // ⚠ 用 scheduleWithFixedDelay：单线程串行，卡顿时不会把任务堆起来
        exec.scheduleWithFixedDelay(this::tick, 0L, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** 停采样。页面离开前台就该停，这页是常驻的，不停就是一直在读文件 */
    public void stop() {
        ScheduledExecutorService e = exec;
        exec = null;
        if (e != null) {
            e.shutdownNow();
        }
    }

    /** 页面销毁时调一下，别让线程持有 Activity */
    public void release() {
        stop();
        host = null;
    }

    // ------------------------------------------------------------------ 采样

    private void tick() {
        Snap s = new Snap();
        try {
            sampleCpuUsage(s);
            sampleFreq(s);
            sampleTemp(s);
            sampleMem(s);
            sampleDisplay(s);
            sampleBattery(s);
        } catch (Throwable t) {
            Log.w(TAG, "采样出错", t);
        }
        if (s.cpuPct >= 0) {
            System.arraycopy(hist, 1, hist, 0, HIST - 1);
            hist[HIST - 1] = s.cpuPct;
        }
        s.hist = hist;

        final Activity a = host;
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            return;
        }
        a.runOnUiThread(() -> {
            if (host != null && !host.isFinishing() && !host.isDestroyed()) {
                cb.onSample(s);
            }
        });
    }

    /** 本轮 CPU 总占用率。只算 online 的核；核数变了或间隔太久就只重建基线，本轮不出数 */
    private void sampleCpuUsage(Snap s) {
        List<Integer> cpus = onlineCpus();
        if (cpus.isEmpty()) {
            return;
        }
        if (idlePaths == null) {
            idlePaths = buildIdlePaths();
        }
        long idle = 0;
        for (int c : cpus) {
            String[] paths = c < idlePaths.length ? idlePaths[c] : null;
            if (paths == null) {
                continue;
            }
            for (String p : paths) {
                long v = readLong(p, -1L);
                if (v > 0) {
                    idle += v;
                }
            }
        }
        long wall = SystemClock.elapsedRealtimeNanos() / 1000L;

        // 间隔太离谱（>10 秒，说明中断过）就只当基线，别拿一段空白窗口算平均
        boolean usable = lastIdleUs > 0 && lastWallUs > 0 && cpus.equals(lastCpus)
                && wall > lastWallUs && (wall - lastWallUs) < 10_000_000L;
        if (usable) {
            double dIdle = idle - lastIdleUs;
            double dWall = (double) (wall - lastWallUs) * cpus.size();
            if (dWall > 0) {
                double used = 1.0 - dIdle / dWall;
                used = Math.max(0.0, Math.min(1.0, used));
                s.cpuPct = (int) Math.round(used * 100.0);
            }
        }
        lastIdleUs = idle;
        lastWallUs = wall;
        lastCpus = cpus;
    }

    /** 各簇频率 */
    private void sampleFreq(Snap s) {
        if (policyDirs == null) {
            policyDirs = policyDirs();
            policyMaxKhz = new int[policyDirs.length];
            for (int i = 0; i < policyDirs.length; i++) {
                policyMaxKhz[i] = (int) readLong(new File(policyDirs[i], "cpuinfo_max_freq")
                        .getAbsolutePath(), 0L);
            }
        }
        int n = policyDirs.length;
        if (n == 0) {
            return;
        }
        int[] cur = new int[n], max = new int[n], cores = new int[n];
        int keep = 0;
        for (int i = 0; i < n; i++) {
            long khz = readLong(new File(policyDirs[i], "scaling_cur_freq").getAbsolutePath(), 0L);
            if (khz <= 0) {
                continue;
            }
            cur[keep] = (int) khz;
            max[keep] = policyMaxKhz[i] > 0 ? policyMaxKhz[i] : (int) khz;
            cores[keep] = coreCountOf(policyDirs[i]);
            keep++;
        }
        if (keep == 0) {
            return;
        }
        s.curKhz = java.util.Arrays.copyOf(cur, keep);
        s.maxKhz = java.util.Arrays.copyOf(max, keep);
        s.nrCores = java.util.Arrays.copyOf(cores, keep);
    }

    /** 温度：把所有 CPU 相关传感器里最高的那个报出来，比挑某一个 zone 更接近"烫不烫" */
    private void sampleTemp(Snap s) {
        if (tempPaths == null) {
            tempPaths = buildTempPaths();
        }
        float best = Float.NaN;
        for (String p : tempPaths) {
            long milli = readLong(p, Long.MIN_VALUE);
            if (milli == Long.MIN_VALUE) {
                continue;
            }
            float c = milli / 1000f;   // 单位是毫摄氏度
            if (c < TEMP_MIN || c > TEMP_MAX) {
                continue;
            }
            if (Float.isNaN(best) || c > best) {
                best = c;
            }
        }
        s.tempC = best;
    }

    private void sampleMem(Snap s) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem <= 0) {
                return;
            }
            long used = mi.totalMem - mi.availMem;
            s.memPct = (int) (used * 100L / mi.totalMem);
            s.memUsedMb = used / 1048576L;
            s.memTotalMb = mi.totalMem / 1048576L;
        } catch (Throwable ignored) {
        }
    }

    private void sampleDisplay(Snap s) {
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) {
                return;
            }
            float hz = d.getRefreshRate();
            if (hz <= 0 && d.getMode() != null) {
                hz = d.getMode().getRefreshRate();
            }
            s.refreshHz = hz;
            Display.Mode m = d.getMode();
            if (m != null) {
                s.panelW = m.getPhysicalWidth();
                s.panelH = m.getPhysicalHeight();
            }
        } catch (Throwable ignored) {
        }
    }

    private void sampleBattery(Snap s) {
        try {
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) {
                return;
            }
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                s.battPct = (int) (level * 100f / scale);
            }
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            s.charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 路径探测

    /** {@code /sys/devices/system/cpu/online} → 在线核号，形如 {@code 0-7} 或 {@code 0-2,4} */
    private static List<Integer> onlineCpus() {
        List<Integer> out = new ArrayList<>();
        String s = readLine("/sys/devices/system/cpu/online");
        if (s == null) {
            return out;
        }
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            try {
                int dash = part.indexOf('-');
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).trim());
                    int b = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int i = a; i <= b && i < 64; i++) {
                        out.add(i);
                    }
                } else {
                    out.add(Integer.parseInt(part));
                }
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    private static String[][] buildIdlePaths() {
        int cores = Runtime.getRuntime().availableProcessors();
        String[][] all = new String[Math.max(cores, 8) + 8][];
        for (int c = 0; c < all.length; c++) {
            File dir = new File("/sys/devices/system/cpu/cpu" + c + "/cpuidle");
            File[] states = dir.listFiles();
            if (states == null) {
                continue;
            }
            List<String> ps = new ArrayList<>();
            for (File st : states) {
                if (!st.getName().startsWith("state")) {
                    continue;
                }
                File t = new File(st, "time");
                if (t.canRead()) {
                    ps.add(t.getAbsolutePath());
                }
            }
            if (!ps.isEmpty()) {
                Collections.sort(ps);
                all[c] = ps.toArray(new String[0]);
            }
        }
        return all;
    }

    /** 挑出 CPU 相关的温度传感器。按名字含 cpu 筛；对不上的机型读不到，温度显示「—」 */
    private static String[] buildTempPaths() {
        List<String> out = new ArrayList<>();
        File dir = new File("/sys/class/thermal");
        File[] zones = dir.listFiles();
        if (zones == null) {
            return new String[0];
        }
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) {
                continue;
            }
            String type = readLine(new File(z, "type").getAbsolutePath());
            if (type == null) {
                continue;
            }
            if (type.toLowerCase(Locale.ROOT).contains("cpu")) {
                out.add(new File(z, "temp").getAbsolutePath());
            }
        }
        Collections.sort(out);
        return out.toArray(new String[0]);
    }

    private static File[] policyDirs() {
        File base = new File("/sys/devices/system/cpu/cpufreq");
        File[] all = base.listFiles();
        if (all == null) {
            return new File[0];
        }
        List<File> out = new ArrayList<>();
        for (File f : all) {
            if (f.getName().startsWith("policy")
                    && new File(f, "scaling_cur_freq").canRead()) {
                out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out.toArray(new File[0]);
    }

    /** 一个簇管几个核：读 {@code related_cpus}（形如 {@code 0-3}），读不到就报 1 */
    private static int coreCountOf(File policyDir) {
        String s = readLine(new File(policyDir, "related_cpus").getAbsolutePath());
        if (s == null) {
            return 1;
        }
        int n = 0;
        for (String part : s.split(",")) {
            part = part.trim();
            int dash = part.indexOf('-');
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).trim());
                    int b = Integer.parseInt(part.substring(dash + 1).trim());
                    n += Math.max(0, b - a + 1);
                } else if (!part.isEmpty()) {
                    n += 1;
                }
            } catch (Throwable ignored) {
            }
        }
        return n > 0 ? n : 1;
    }

    // ------------------------------------------------------------------ 小工具

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l == null ? null : l.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readLong(String path, long fallback) {
        String s = readLine(path);
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        int sp = s.indexOf(' ');
        if (sp > 0) {
            s = s.substring(0, sp);
        }
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
