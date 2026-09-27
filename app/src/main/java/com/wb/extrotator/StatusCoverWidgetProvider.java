package com.wb.extrotator;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.RemoteViews;

/**
 * 「手机状态」小组件（外屏 2×2）—— 电量 / CPU 温度 / 内存占用 / 存储占用，全是只读。
 *
 * <p><b>为什么整个组件就一个 ImageView</b>：四个指标的"图形化"（进度条 + 固定配色）
 * 在 RemoteViews 那十来种控件里拼不出来，所以由 {@link WidgetStatus#draw} 画成一张位图塞进去。
 * 布局里那个 ImageView 用 {@code fitXY}，位图按组件实际 dp 尺寸算，不会变形。
 *
 * <p><b>为什么要自己定时刷</b>：{@code updatePeriodMillis} 系统最小只认 30 分钟
 * （填更小也是 30 分钟），这一页要看电量、温度的实时值，所以改用 {@link AlarmManager}
 * 一分钟一次。⚠ 重复闹钟在 Doze 下会被推迟，不在意的——这不是秒表。
 *
 * <p>⚠ 用户点名这个组件<b>只提供信息观看、不提供点击</b>，所以这里刻意<b>不</b>给任何
 * {@code setOnClickPendingIntent}。
 */
public class StatusCoverWidgetProvider extends AppWidgetProvider {

    /** 自己给自己发的"该刷了" */
    public static final String ACTION_TICK = "com.wb.extrotator.widget.STATUS_TICK";

    /** 刷新间隔；系统会对重复闹钟做批处理，实际可能晚几秒到几分钟 */
    private static final long PERIOD_MS = 60_000L;

    private static final int REQ_TICK = 0x3300;

    /** 设计基准尺寸（dp）：三星外屏 2×2 那一格 */
    private static final int BASE_W_DP = 150;
    private static final int BASE_H_DP = 133;

    /**
     * 位图缩放的上限。
     *
     * <p>⚠ 它的作用是别让位图顶到 binder 的 1MB 上限：2×2（150×133dp）全密度（340dpi ≈ 2.125）
     * 是 319×283 的 ARGB ≈ 361KB，放得下；就算设备密度再高，2.5 倍也只有 375×333 ≈ 499KB。
     * （上一版是 4×2，312×133dp 全密度 663×283 ≈ 750KB，太顶，那时才把上限定成 1.5 倍。）
     */
    private static final float MAX_SCALE = 2.5f;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            mgr.updateAppWidget(id, build(ctx, mgr, id));
        }
        schedule(ctx);
    }

    @Override
    public void onEnabled(Context ctx) {
        super.onEnabled(ctx);
        schedule(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        super.onDisabled(ctx);
        cancel(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_TICK.equals(intent.getAction())) {
            refreshAll(ctx);
        }
    }

    /** 现采一次样、画一张图。采样只读几个文件，很快，不必开线程。 */
    static RemoteViews build(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_status_2x2);
        int wDp = BASE_W_DP;
        int hDp = BASE_H_DP;
        try {
            Bundle o = mgr.getAppWidgetOptions(id);
            if (o != null) {
                int mw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
                int mh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                if (mw > 0) {
                    wDp = mw;
                }
                if (mh > 0) {
                    hDp = mh;
                }
            }
        } catch (Throwable ignored) {
        }
        float scale = Math.min(ctx.getResources().getDisplayMetrics().density, MAX_SCALE);
        int wPx = Math.max(64, Math.round(wDp * scale));
        int hPx = Math.max(32, Math.round(hDp * scale));
        v.setImageViewBitmap(R.id.statusImage, WidgetStatus.draw(wPx, hPx, WidgetStatus.sample(ctx)));
        return v;
    }

    /** 刷一遍本组件（定时器到点、或刚装好） */
    public static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, StatusCoverWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            for (int id : ids) {
                mgr.updateAppWidget(id, build(ctx, mgr, id));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + PERIOD_MS, PERIOD_MS, tick(ctx));
        } catch (Throwable ignored) {
        }
    }

    private static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(tick(ctx));
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent tick(Context ctx) {
        Intent i = new Intent(ctx, StatusCoverWidgetProvider.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(ctx, REQ_TICK, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
