package com.wb.extrotator;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 外屏启动器上所有「点一下」的落点。
 *
 * <p>小组件自己既不能启动一个别的应用、也不能 {@code startActivity}，只能发一条 PendingIntent
 * —— 这里就是那些 PendingIntent 的终点：点某一格图标 → 把那个应用投到<b>封面屏</b>上打开；
 * 工具条 🔍 → 把搜索页投到封面屏；⇅ → 把配置页投到封面屏<b>并直接弹出排序选择器</b>；
 * ⚙ → 把配置页投到封面屏；★（收藏夹）→ 切「只看收藏」，网格当场重取一遍。
 * 「有哪几种排序」只有 {@link LauncherPrefs#SORT_ALL} 一处定义。
 *
 * <p>⚠ 两条必须注意的事：{@link ShellRunner#run} 是<b>阻塞</b>的（内部 {@code waitFor}），
 * 而广播接收器的 {@code onReceive} 有 10 秒 ANR 红线 —— 所以先 {@code goAsync()} 再去后台线程跑，
 * 跑完 finish；投屏交给 {@link SecondaryLauncher} 的三条路 —— 没装 Shizuku 时它会走
 * 三星的封面屏通道，所以这里<b>不再</b>因为"没有 Shizuku"就放弃（见那边的注释）。
 */
public class LauncherTapReceiver extends BroadcastReceiver {

    private static final String TAG = "CoverLauncherTap";

    /** 点了工具条的搜索图标：打开搜索页（投到封面屏） */
    public static final String ACTION_OPEN_SEARCH = "com.wb.extrotator.action.LAUNCHER_SEARCH";
    /** 点了工具条的排序图标：打开配置页并直接弹排序选择器（投到封面屏） */
    public static final String ACTION_SORT = "com.wb.extrotator.action.LAUNCHER_SORT";
    /** 点了工具条的设置图标：打开配置页（投到封面屏） */
    public static final String ACTION_OPEN_CONFIG = "com.wb.extrotator.action.LAUNCHER_CONFIG";
    /** 点了工具条的收藏夹图标：切「只看收藏」 */
    public static final String ACTION_FAV_FILTER = "com.wb.extrotator.action.LAUNCHER_FAV";

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_CLS = "cls";
    public static final String EXTRA_LABEL = "label";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        final Context ctx = context.getApplicationContext();
        final String action = intent.getAction();

        if (ACTION_OPEN_SEARCH.equals(action)) {
            runAsync(ctx, "extrot-launcher-search",
                    () -> openOwn(ctx, CoverSearchActivity.class, null));
            return;
        }
        if (ACTION_SORT.equals(action)) {
            Bundle args = new Bundle();
            args.putString(CoverLauncherActivity.EXTRA_OPEN_SORT, "1");
            runAsync(ctx, "extrot-launcher-sort",
                    () -> openOwn(ctx, CoverLauncherActivity.class, args));
            return;
        }
        if (ACTION_OPEN_CONFIG.equals(action)) {
            runAsync(ctx, "extrot-launcher-cfg",
                    () -> openOwn(ctx, CoverLauncherActivity.class, null));
            return;
        }

        /*
         * 收藏夹：只拨一个开关，不碰 Shizuku，所以**同步做完**就走 ——
         * 不必像上面几条那样绕 goAsync()。反馈就在小组件自己身上：
         * 左边那行字变成「只看收藏」，那个星星同时点亮。
         */
        if (ACTION_FAV_FILTER.equals(action)) {
            boolean on = !LauncherPrefs.favOnly(ctx);
            LauncherPrefs.setFavOnly(ctx, on);
            Log.i(TAG, "收藏夹筛选 -> " + (on ? "只看收藏" : "全部应用"));
            CoverLauncherProvider.refreshAll(ctx);
            return;
        }

        if (!CoverLauncherProvider.ACTION_TAP.equals(action)) {
            return;
        }

        final String pkg = intent.getStringExtra(EXTRA_PKG);
        final String cls = intent.getStringExtra(EXTRA_CLS);
        if (pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "点了但没有包名，忽略");
            return;
        }
        runAsync(ctx, "extrot-launcher-tap", () -> launch(ctx, pkg, cls));
    }

    /**
     * 把活儿挪到后台线程再跑。
     *
     * <p>广播接收器的 onReceive 有 10 秒 ANR 红线，而 {@link ShellRunner#run} 是阻塞的
     * （内部 waitFor 等命令跑完），直接在 onReceive 里调会踩线。
     * 用 {@code goAsync()} 领一张"我还没干完"的票，干完再 finish。
     */
    private void runAsync(Context ctx, String name, Runnable job) {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                job.run();
            } catch (Throwable t) {
                Log.w(TAG, name + " failed", t);
            } finally {
                try {
                    pr.finish();
                } catch (Throwable ignored) {
                }
            }
        }, name).start();
    }

    // ------------------------------------------------------------------ 投屏

    /**
     * 打开本应用自己的某个页面，优先投到封面屏 ——
     * 这样在合着盖的情况下也能把事办完，不用翻开手机。
     * 投屏交给 {@link SecondaryLauncher#launchOwn}（它自己有三条路）；
     * 都走不通才退回默认屏，至少还能用，只是得在内屏看。
     */
    private static void openOwn(Context ctx, Class<?> cls, Bundle args) {
        int display = CoverDisplay.id(ctx);
        if (display > 0 && SecondaryLauncher.launchOwn(ctx, display, cls, args)) {
            Log.i(TAG, cls.getSimpleName() + " 已打开在第 " + display + " 屏");
            return;
        }
        try {
            Intent i = new Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (args != null) {
                i.putExtras(args);
            }
            ctx.startActivity(i);
            Log.i(TAG, cls.getSimpleName() + " 退回默认屏打开");
        } catch (Throwable t) {
            Log.w(TAG, "openOwn failed", t);
        }
    }

    // ------------------------------------------------------------------ 格子

    private static void launch(Context ctx, String pkg, String cls) {
        int display = CoverDisplay.id(ctx);
        Intent li = null;
        try {
            if (cls != null && !cls.isEmpty()) {
                li = new Intent(Intent.ACTION_MAIN);
                li.addCategory(Intent.CATEGORY_LAUNCHER);
                li.setComponent(new ComponentName(pkg, cls));
            }
        } catch (Throwable ignored) {
        }
        if (li == null) {
            try {
                li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            } catch (Throwable ignored) {
            }
        }
        if (li == null) {
            Log.w(TAG, "没有可用的启动入口: " + pkg);
            return;
        }

        boolean ok = SecondaryLauncher.launch(ctx, display, li);
        Log.i(TAG, "启动 " + pkg + " 到第 " + display + " 屏 => " + ok);
        if (ok) {
            /*
             * 记一笔启动次数，给"按常用"排序用。
             * 刻意**不**在这里刷新小组件 —— 否则"按常用"模式下刚点过的图标
             * 会当场跳到第一格，网格自己动起来会让人以为点错了。
             * 顺序留到下次重画（切排序、改外观、重进配置页）时再更新。
             */
            LauncherPrefs.bump(ctx, pkg);
        }
    }
}
