package com.wb.extrotator;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * 「自定义操作」小组件（外屏 2×2）—— 四个空格子，各挂一个动作，挂什么由用户自己挑。
 *
 * <p><b>为什么不配 {@code android:configure}</b>：用户要的是「加到外屏时先是空白的，
 * 第一次点它才进去设置」。系统自带的"添加时先弹配置页"（{@code android:configure}）
 * 恰恰相反 —— 它是添加流程的一部分，取消就没法加。所以这里不声明它，改成
 * 「空格子点一下就进去配那一格」。
 *
 * <p><b>点下去的两种去向</b>：
 * <ul>
 *   <li>这一格已经配好了 → 交给 {@link WidgetActionActivity}（带 {@code EXTRA_KEY}）执行；</li>
 *   <li>这一格还是空的 → 进 {@link CustomCfgActivity} 配这一格。</li>
 * </ul>
 *
 * <p>⚠ 每一个 {@code android:id} 都必须被 {@link #build} 画到；少一个就是一次 RemoteViews
 * 异常、整块变成「无法显示微件」。
 *
 * <p>⚠ 只做外屏一份（本页只有 {@code keyguard} + 三星私有 meta-data）。用户点名要的是
 * 「外屏小组件」，不像双屏面板那样还有个内屏版本要照顾，所以不再另开一个内屏 receiver。
 */
public class CustomCoverWidgetProvider extends AppWidgetProvider {

    /** 四个格子的 view id，下标即格子号 */
    private static final int[] BTN = {
            R.id.btnCustom0, R.id.btnCustom1, R.id.btnCustom2, R.id.btnCustom3,
    };

    /** PendingIntent 的 requestCode 段（别跟别的组件撞） */
    private static final int REQ_RUN = 0x3100;
    private static final int REQ_CFG = 0x3200;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = build(ctx);
        for (int id : ids) {
            mgr.updateAppWidget(id, v);
        }
    }

    /** 现读设置画一块（读的是本地 SharedPreferences，不碰 Shizuku、不卡桌面） */
    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_custom_2x2);
        for (int i = 0; i < BTN.length; i++) {
            if (CustomWidgetCfg.slotAlive(ctx, i)) {
                v.setTextViewText(BTN[i], CustomWidgetCfg.label(ctx, i));
                v.setOnClickPendingIntent(BTN[i],
                        runClick(ctx, CustomWidgetCfg.slot(ctx, i), i));
            } else {
                v.setTextViewText(BTN[i], ctx.getString(R.string.widget_custom_empty));
                v.setOnClickPendingIntent(BTN[i], cfgClick(ctx, i));
            }
        }
        return v;
    }

    /** 已配好的格子：把动作 key 交给透明 Activity 去执行 */
    private static PendingIntent runClick(Context ctx, String key, int slot) {
        Intent i = new Intent(ctx, WidgetActionActivity.class)
                .setAction("com.wb.extrotator.WIDGET_CUSTOM_" + slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WidgetActionActivity.EXTRA_KEY, key);
        return PendingIntent.getActivity(ctx, REQ_RUN + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 空着的格子：进配置页配这一格 */
    private static PendingIntent cfgClick(Context ctx, int slot) {
        Intent i = new Intent(ctx, CustomCfgActivity.class)
                .setAction("com.wb.extrotator.WIDGET_CUSTOM_CFG_" + slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CustomCfgActivity.EXTRA_SLOT, slot);
        return PendingIntent.getActivity(ctx, REQ_CFG + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 需要重画时调一下（配好一格的当下、刚装好） */
    public static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, CustomCoverWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            RemoteViews v = build(ctx);
            for (int id : ids) {
                mgr.updateAppWidget(id, v);
            }
        } catch (Throwable ignored) {
        }
    }
}
