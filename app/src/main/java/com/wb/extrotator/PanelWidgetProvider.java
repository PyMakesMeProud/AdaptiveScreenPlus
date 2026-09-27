package com.wb.extrotator;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * 「双屏快捷面板」小组件，内外屏各一份布局：
 * <ul>
 *   <li>内屏 2×1 横条（{@code widget_panel.xml}）：折叠/展开切换 · 双屏模式；</li>
 *   <li>外屏 2×2 四格铺满（{@code widget_panel_2x2.xml}）：折叠/展开切换 · 自适应旋转
 *       · 双屏模式 · 一键投屏。</li>
 * </ul>
 *
 * <p>「自适应旋转」的状态是现读 {@link Prefs} 画上去的（本地 SharedPreferences，
 * 不碰 Shizuku、不卡桌面），所以设置一改必须调 {@link #updateAll} 重画。
 *
 * <p>点下去的动作交给 {@link WidgetActionActivity}（透明 Activity，能弹 Toast、能走 Shizuku）。
 */
public class PanelWidgetProvider extends AppWidgetProvider {

    private static final int REQ_FOLD = 0x2001;
    private static final int REQ_DUAL = 0x2002;
    private static final int REQ_SYNC = 0x2003;
    private static final int REQ_CAST = 0x2004;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        // getClass() 在系统实例化的那个子类上调用，所以外屏那份会自动拿到 2×2 布局
        RemoteViews v = build(ctx, layoutFor(getClass()));
        for (int id : ids) {
            mgr.updateAppWidget(id, v);
        }
    }

    /** 内屏那份和内屏用的布局不是同一个，这里统一口径 */
    static int layoutFor(Class<?> cls) {
        return PanelCoverWidgetProvider.class.isAssignableFrom(cls)
                ? R.layout.widget_panel_2x2
                : R.layout.widget_panel;
    }

    static RemoteViews build(Context ctx, int layoutId) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layoutId);
        v.setOnClickPendingIntent(R.id.btnWidgetFold,
                click(ctx, WidgetActionActivity.ACT_FOLD, REQ_FOLD));
        v.setOnClickPendingIntent(R.id.btnWidgetDual,
                click(ctx, WidgetActionActivity.ACT_DUAL, REQ_DUAL));

        if (layoutId == R.layout.widget_panel_2x2) {
            // 外屏那份多一格：自适应旋转是个开关式按钮（底色跟状态走），
            // 一键投屏是动作按钮，文字写死在布局里
            Prefs p = new Prefs(ctx);
            v.setOnClickPendingIntent(R.id.btnWidgetSync,
                    click(ctx, WidgetActionActivity.ACT_SYNC, REQ_SYNC));
            v.setOnClickPendingIntent(R.id.btnWidgetCast,
                    click(ctx, WidgetActionActivity.ACT_CAST, REQ_CAST));
            bindToggle(v, R.id.btnWidgetSync, R.drawable.widget_btn_sync,
                    ctx.getString(R.string.widget_btn_sync), p.isEnabled());
        }
        return v;
    }

    /**
     * 把开关式按钮画成「名字 + 开/关」两行，底色区分状态。
     * @param onBg 开启时的底色；关闭时统一用灰底（widget_btn_off）
     */
    private static void bindToggle(RemoteViews v, int id, int onBg, String name, boolean on) {
        v.setTextViewText(id, name + "\n" + (on ? "开" : "关"));
        v.setInt(id, "setBackgroundResource", on ? onBg : R.drawable.widget_btn_off);
    }

    private static PendingIntent click(Context ctx, String action, int reqCode) {
        Intent i = new Intent(ctx, WidgetActionActivity.class)
                .setAction("com.wb.extrotator.WIDGET_" + action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WidgetActionActivity.EXTRA_ACTION, action);
        return PendingIntent.getActivity(ctx, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 需要重画时调一下（换主题 / 刚装好 / 改过按钮文案）—— 内外屏两份都要刷 */
    public static void updateAll(Context ctx) {
        refresh(ctx, PanelWidgetProvider.class);
        refresh(ctx, PanelCoverWidgetProvider.class);
    }

    private static void refresh(Context ctx, Class<?> cls) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, cls));
            if (ids == null || ids.length == 0) {
                return;
            }
            RemoteViews v = build(ctx, layoutFor(cls));
            for (int id : ids) {
                mgr.updateAppWidget(id, v);
            }
        } catch (Throwable ignored) {
        }
    }
}
