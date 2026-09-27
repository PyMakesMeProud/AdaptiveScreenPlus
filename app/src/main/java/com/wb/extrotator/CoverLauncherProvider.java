package com.wb.extrotator;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * 「外屏启动器」小组件 —— 封面屏上的整页启动器。
 *
 * <p><b>凭什么能出现在外屏</b>：清单里那两条声明缺一不可（依据见 {@code widget_launcher_cover.xml}
 * 与 {@code widget_panel_samsung.xml} 的注释）—— 配置 xml 里 {@code widgetCategory} 要写成
 * {@code keyguard}、尺寸给 <b>352×339dp / targetCell 4×4</b>；以及一条私有 meta-data
 * {@code com.samsung.android.appwidget.provider}，指向一个只有一行、声明了 {@code sub_screen}
 * 的 xml。这套配方是从「阿田自用」的 APK 里反查出来的，也是外屏整页启动器仅有的现成样板。
 *
 * <p><b>界面</b>：恒定 54dp 的工具条（左边一行字显示<b>当前排序方式</b>，右边四个图标 ——
 * 收藏夹 / 搜索 / 排序 / 设置），下面是图标网格。收藏夹是个开关：拨一下只显示收藏过的应用，
 * 左边那行字跟着变成「只看收藏」。
 *
 * <p><b>为什么用 GridView + RemoteViewsService</b>：小组件里的网格不能自己 setAdapter
 * （视图跑在桌面进程里，我们只能递 RemoteViews 过去），格子内容由 {@link CoverLauncherService}
 * 按 position 一格格地造。点击的活儿也不在这里：{@code setPendingIntentTemplate} 给的是一条模板，
 * 每格用 {@code setOnClickFillInIntent} 填自己的包名，最终落到 {@link LauncherTapReceiver}
 * 手里去执行投屏启动。
 *
 * <p><b>外观设置怎么落到 RemoteViews 上</b>：RemoteViews 只放行内置 API，改布局参数一律没门，
 * 于是四条外观各自绕 —— 一排几个换布局文件（{@link #layoutFor}）；图标大小 / 图标形状 / 名称字号
 * 都在 {@link CoverLauncherService} 里现画位图、现设字号；底板浓度换一张 shape 资源塞给背景
 * ImageView（见 {@link #build}）。
 */
public class CoverLauncherProvider extends AppWidgetProvider {

    private static final String TAG = "CoverLauncher";

    /** 点了网格里的某一格 */
    public static final String ACTION_TAP = "com.wb.extrotator.action.LAUNCHER_TAP";
    /** 数据变了，让所有实例重新取数 */
    public static final String ACTION_REFRESH = "com.wb.extrotator.action.LAUNCHER_REFRESH";

    private static final int REQ_TEMPLATE = 0x3101;
    private static final int REQ_CONFIG = 0x3102;
    private static final int REQ_SEARCH = 0x3103;
    private static final int REQ_SORT = 0x3104;
    private static final int REQ_FAV = 0x3105;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        RemoteViews v = build(ctx);
        for (int id : ids) {
            mgr.updateAppWidget(id, v);
        }
        notifyData(mgr, ids);
        Log.i(TAG, "onUpdate 刷新了 " + ids.length + " 个实例");
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            refreshAll(ctx);
        }
    }

    /**
     * 按「一排几个」挑一份布局 —— 四份结构一样，只有 GridView 的 numColumns 不同
     * （为什么要换文件而不是运行时设 numColumns，见 {@code widget_launcher.xml} 顶部注释）。
     *
     * <p>⚠ 加档位时这里和 {@link LauncherPrefs#COLUMN_CHOICES} 要一起加，
     * 并用 {@code scripts/gen_launcher_column_layouts.py} 生成新的那份布局。
     */
    static int layoutFor(Context ctx) {
        switch (LauncherPrefs.columns(ctx)) {
            case 7:
                return R.layout.widget_launcher_c7;
            case 6:
                return R.layout.widget_launcher_c6;
            case 5:
                return R.layout.widget_launcher_c5;
            default:
                return R.layout.widget_launcher;
        }
    }

    /**
     * 当前排序方式的中文名 —— 工具条上那行字用它。
     *
     * <p>真正的判断在 {@link LauncherPrefs#sortLabel(Context, String)}：
     * 配置页的排序器、这里、还有 Toast 全走同一份 {@link LauncherPrefs#SORT_ALL}，
     * 才不会出现"小组件显示的名字配置页里没有"这种不对等。
     */
    public static String sortLabel(Context ctx) {
        return LauncherPrefs.sortLabel(ctx);
    }

    /** 造一份小组件视图（所有实例长得一样，共用一份） */
    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layoutFor(ctx));

        /*
         * 底板浓度：换资源，不是改颜色 —— RemoteViews 里能改颜色的那几条路全堵着
         * （setColorFilter 要反射调 setter，不在放行名单里）。所以四种浓度各做了一份 shape
         * （launcher_widget_bg_0/10/30/50），按设置挑一份塞进背景 ImageView。
         * （为什么背景是 ImageView 而不是根布局的 background，见布局里的注释。）
         */
        v.setImageViewResource(R.id.launcherBg, LauncherPrefs.bgDrawable(ctx));

        /*
         * 背景图：设过才铺，铺在底板下面一层（见布局里的注释）。
         *
         * 递的是**地址**不是位图：748×720 的 ARGB 位图有 2MB，而 RemoteViews 过 Binder
         * 的事务上限是 1MB，setImageViewBitmap 会直接顶穿。地址走 setImageViewUri，
         * 那边自己去读 —— 出口是 PhotoProvider。
         * 没设过就什么都不做，那层 ImageView 空着，跟老版本一模一样。
         */
        if (LauncherPhoto.has(ctx)) {
            v.setImageViewUri(R.id.launcherPhoto, PhotoProvider.uri());
        }

        // 网格的数据来源：一个 RemoteViewsService
        v.setRemoteAdapter(R.id.launcherGrid, new Intent(ctx, CoverLauncherService.class));
        // 一格子都没有时显示那块引导文案
        v.setEmptyView(R.id.launcherGrid, R.id.launcherEmpty);

        // 工具条左边：现在按什么排
        v.setTextViewText(R.id.launcherBarTitle, LauncherPrefs.favOnly(ctx)
                ? ctx.getString(R.string.launcher_fav_bar)
                : ctx.getString(R.string.launcher_sort_now, sortLabel(ctx)));

        // 排序图标：不在"手动顺序"时点亮成绿色，一眼能看出排法被改过
        v.setImageViewResource(R.id.launcherSort,
                LauncherPrefs.SORT_MANUAL.equals(LauncherPrefs.sort(ctx))
                        ? R.drawable.ic_launcher_sort : R.drawable.ic_launcher_sort_on);

        // 收藏夹图标：开着「只看收藏」时也点亮成绿色
        v.setImageViewResource(R.id.launcherFav,
                LauncherPrefs.favOnly(ctx)
                        ? R.drawable.ic_launcher_star_on : R.drawable.ic_launcher_star);

        /*
         * 工具条上三个图标走的都是广播，而不是 getActivity —— 因为搜索页和配置页都需要
         * **投到封面屏**才能在外屏上显示（普通 startActivity 永远落在默认屏），所以统一交给
         * LauncherTapReceiver 用 shell 的 am start --display 去开。
         */
        v.setOnClickPendingIntent(R.id.launcherSearch,
                broadcast(ctx, REQ_SEARCH, LauncherTapReceiver.ACTION_OPEN_SEARCH));
        v.setOnClickPendingIntent(R.id.launcherSort,
                broadcast(ctx, REQ_SORT, LauncherTapReceiver.ACTION_SORT));
        v.setOnClickPendingIntent(R.id.launcherConfig,
                broadcast(ctx, REQ_CONFIG, LauncherTapReceiver.ACTION_OPEN_CONFIG));
        // 收藏夹：切「只看收藏」。不发 Activity —— 原地把网格重取一遍就行
        v.setOnClickPendingIntent(R.id.launcherFav,
                broadcast(ctx, REQ_FAV, LauncherTapReceiver.ACTION_FAV_FILTER));

        // 格子点击的"模板"。必须是 MUTABLE —— 系统要把它和每格的
        // fillInIntent（带着包名）合并，IMMUTABLE 的话合并不进去，点击会丢参数。
        Intent tap = new Intent(ctx, LauncherTapReceiver.class).setAction(ACTION_TAP);
        v.setPendingIntentTemplate(R.id.launcherGrid, PendingIntent.getBroadcast(
                ctx, REQ_TEMPLATE, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        return v;
    }

    /** 工具条按钮统一用 IMMUTABLE：动作写在 action 里，不需要接收方回填参数。 */
    private static PendingIntent broadcast(Context ctx, int req, String action) {
        Intent i = new Intent(ctx, LauncherTapReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 勾选 / 排序 / 外观变了之后调一下：重画视图 + 让网格重新取数 */
    public static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, CoverLauncherProvider.class));
            if (ids == null || ids.length == 0) {
                Log.i(TAG, "没有已放置的实例，跳过刷新");
                return;
            }
            RemoteViews v = build(ctx);
            for (int id : ids) {
                mgr.updateAppWidget(id, v);
            }
            notifyData(mgr, ids);
            Log.i(TAG, "refreshAll 刷新了 " + ids.length + " 个实例");
        } catch (Throwable t) {
            Log.w(TAG, "refreshAll failed", t);
        }
    }

    private static void notifyData(AppWidgetManager mgr, int[] ids) {
        try {
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.launcherGrid);
        } catch (Throwable t) {
            Log.w(TAG, "notifyAppWidgetViewDataChanged failed", t);
        }
    }
}
