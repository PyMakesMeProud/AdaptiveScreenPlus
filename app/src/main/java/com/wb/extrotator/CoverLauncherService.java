package com.wb.extrotator;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/**
 * 给「外屏启动器」小组件供数。
 *
 * <p>RemoteViews 的网格没法自己 setAdapter，得由一个 RemoteViewsService 按 position
 * 一格一格地造视图，真正干活的是里面的 {@link AppFactory}。
 *
 * <p>⚠ 图标要在 {@code onDataSetChanged()} 里**一次性全部**读好并缩成小位图 ——
 * 那个回调允许耗时，而 {@code getViewAt()} 会被反复调用，在里面读图标会卡住外屏。
 *
 * <p><b>四处动态外观</b>（小组件里改不了布局参数，只能换着法子达到同样效果）：
 * 图标大小 —— itemIcon 在布局里是 wrap_content，显示多大由这里塞进去的位图尺寸决定，
 * 按 {@link LauncherPrefs#iconScale} 缩；图标形状 —— 按设置把画布裁成统一形状再画上去，
 * 见 {@link #toBitmap}；行距 —— GridView 的 verticalSpacing 改不了（没有 @RemotableViewMethod），
 * 改成给每一格的根节点 {@code setViewPadding} 撑上下边距；名称字号 —— 跟着图标大小一起走。
 * 底板浓度不在这里 —— 那是整块组件一层的事，由 {@link CoverLauncherProvider} 换背景资源。
 */
public class CoverLauncherService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new AppFactory(getApplicationContext());
    }

    // ------------------------------------------------------------------ 图标位图

    /**
     * Drawable → 定尺寸 Bitmap，顺带按「图标形状」裁一刀。画不出来就返回 null，
     * 调用方会退回默认图标。
     *
     * <p>为什么要自己裁：外屏上这些图标形状不齐（方形 / 圆角 / 圆形混着看很乱），
     * 系统不给应用改别人图标的接口，能做的只有「位图画好之后按统一形状切一刀」。
     *
     * <p>三种档位：原样 —— 不裁，直接画；圆角方形 —— 圆角半径 = 边长 ×
     * {@link LauncherPrefs#SHAPE_RADIUS_RATIO}；圆形 —— 内切圆。
     * ⚠ 裁只减不增：本来画成圆形的图标，选「圆角方形」还是圆的，只有选「圆形」才真正能把
     * 所有图标统一成一种形状（配置页的 ⓘ 里也写了）。
     *
     * <p>⚠ 这里的 Canvas 是画到 Bitmap 上的（软件渲染），clipPath 的抗锯齿才有效；
     * 放到硬件加速的 View 上裁同样的路径反而会糊。
     *
     * @param shape 形状档位；非法值按「原样」处理
     */
    public static Bitmap toBitmap(Drawable d, int px, int shape) {
        if (d == null || px <= 0) {
            return null;
        }
        try {
            Bitmap bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Path clip = maskPath(px, shape);
            if (clip != null) {
                c.clipPath(clip);
            }
            d.setBounds(0, 0, px, px);
            d.draw(c);
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 形状 → 裁剪路径；"原样"返回 null（表示不用裁）。
     *
     * <p>配置页的格子共用这一份 —— 两边形状必须一致，否则"配置页里看到的"
     * 和"外屏上长出来的"不是一个样子。
     */
    public static Path maskPath(int px, int shape) {
        if (px <= 0) {
            return null;
        }
        try {
            if (shape == LauncherPrefs.SHAPE_CIRCLE) {
                Path p = new Path();
                p.addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW);
                return p;
            }
            if (shape == LauncherPrefs.SHAPE_ROUND) {
                float r = px * LauncherPrefs.SHAPE_RADIUS_RATIO;
                Path p = new Path();
                p.addRoundRect(new RectF(0, 0, px, px), r, r, Path.Direction.CW);
                return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ 供数

    private static class AppFactory implements RemoteViewsService.RemoteViewsFactory {

        private static final String TAG = "CoverLauncherFactory";

        /**
         * 图标进 RemoteViews 前缩到这个像素边长（"中"档的基准）。
         * RemoteViews 要走 Binder 传给桌面进程，单格太大直接抛
         * TransactionTooLarge；108px 在外屏(340dpi)上约等于 51dp。
         * 小/大两档按 {@link LauncherPrefs#iconScale} 在这个基准上缩放。
         */
        private static final int ICON_PX = 108;

        /**
         * 名称字号（sp），按图标大小档取 —— 当前 8/9/10。
         *
         * <p>⚠ 改这里<b>必须同步改 {@code widget_launcher_item.xml} 的 itemLabel.minHeight</b>：
         * 那条下限是按「最大档字号的两行高」算的（2.82 × 最大档 sp），字号改了而下限没改
         * ⇒ 格高又分档、行距又不齐。
         */
        private static final float[] LABEL_SP = {8f, 9f, 10f};

        private final Context ctx;
        private final List<AppRepo.Item> items = new ArrayList<>();
        private final List<Bitmap> icons = new ArrayList<>();

        /**
         * 上一次取数时看到的"勾选列表 + 排序方式 + 图标档位 + 形状 + 收藏夹筛选"，
         * 用来判断要不要真读一遍。
         * ⚠ 这几样必须全算进 key —— 少一个，改那项设置时桌面来取数会被当成"没变"而跳过，
         * 网格原地不动（v2.18.9 就在排序上栽过一次；v4.14 加收藏夹筛选时同理）。
         */
        private String lastKey = null;

        AppFactory(Context c) {
            this.ctx = c;
        }

        @Override
        public void onCreate() {
            // 什么都不做，第一次取数交给 onDataSetChanged
        }

        @Override
        public void onDataSetChanged() {
            List<String> picked = LauncherPrefs.apps(ctx);
            final boolean favOnly = LauncherPrefs.favOnly(ctx);
            final List<String> favs = LauncherPrefs.favs(ctx);
            String key = picked.toString()
                    + "|" + LauncherPrefs.sort(ctx)
                    + "|" + LauncherPrefs.iconSize(ctx)
                    + "|" + LauncherPrefs.iconShape(ctx)
                    + "|" + favOnly
                    + "|" + favs;

            if (key.equals(lastKey) && !items.isEmpty()) {
                return;
            }
            lastKey = key;

            items.clear();
            icons.clear();
            if (picked.isEmpty()) {
                return;
            }

            // 1) 先按勾选顺序把 Item 挑出来
            List<AppRepo.Item> all = AppRepo.load(ctx);
            List<AppRepo.Item> pickedItems = new ArrayList<>();
            for (String pkg : picked) {
                // 「只看收藏」开着时，没收藏的直接不进网格
                if (favOnly && !favs.contains(pkg)) {
                    continue;
                }
                for (AppRepo.Item it : all) {
                    if (pkg.equals(it.pkg)) {
                        pickedItems.add(it);
                        break;
                    }
                }
            }

            // 2) 再按当前排序方式排 —— 图标必须在排完之后才生成，
            //    否则位图跟条目会对不上号
            LauncherPrefs.sortItems(ctx, pickedItems);
            items.addAll(pickedItems);

            final int px = iconPx();
            final int shape = LauncherPrefs.iconShape(ctx);
            for (AppRepo.Item it : items) {
                icons.add(toBitmap(it.icon, px, shape));
            }
            Log.i(TAG, "取了 " + items.size() + " 个应用进外屏启动器（图标 " + px + "px，形状 "
                    + shape + "）");
        }

        @Override
        public void onDestroy() {
            items.clear();
            icons.clear();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            AppRepo.Item it = items.get(position);
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_launcher_item);

            Bitmap bm = position < icons.size() ? icons.get(position) : null;
            if (bm != null) {
                rv.setImageViewBitmap(R.id.itemIcon, bm);
            } else {
                rv.setImageViewResource(R.id.itemIcon, R.mipmap.ic_launcher);
            }

            boolean showLabel = LauncherPrefs.showLabel(ctx);
            rv.setViewVisibility(R.id.itemLabel, showLabel ? View.VISIBLE : View.GONE);
            if (showLabel) {
                rv.setTextViewText(R.id.itemLabel, it.label);
                int size = LauncherPrefs.iconSize(ctx);
                float sp = LABEL_SP[Math.max(0, Math.min(LABEL_SP.length - 1, size))];
                rv.setTextViewTextSize(R.id.itemLabel, TypedValue.COMPLEX_UNIT_SP, sp);
            }

            // "行距"：布局里改不了 GridView 的间距，只能给每一格自己撑上下边距
            int pad = dp(LauncherPrefs.rowPadDp(ctx));
            rv.setViewPadding(R.id.itemRoot, 0, pad, 0, showLabel ? pad : pad * 2);

            /*
             * 这里是关键：模板 PendingIntent 由 Provider 给，
             * 每一格再把自己的包名/类名填进去，点下去才有具体目标。
             */
            Intent fill = new Intent();
            fill.putExtra(LauncherTapReceiver.EXTRA_PKG, it.pkg);
            fill.putExtra(LauncherTapReceiver.EXTRA_CLS,
                    it.component == null ? "" : it.component.getClassName());
            fill.putExtra(LauncherTapReceiver.EXTRA_LABEL, it.label);
            rv.setOnClickFillInIntent(R.id.itemRoot, fill);

            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        // -------------------------------------------------------------- 尺寸

        private int iconPx() {
            int px = Math.round(ICON_PX * LauncherPrefs.iconScale(ctx));
            return Math.max(48, Math.min(200, px));
        }

        /**
         * dp → px。用的是本进程的 density（默认屏的），跟外屏的 340dpi 差着两成左右 ——
         * 这里只是给格子撑个边距，差几个像素看不出来，不值得为它去查外屏的 metrics。
         */
        private int dp(int v) {
            float d;
            try {
                d = ctx.getResources().getDisplayMetrics().density;
            } catch (Throwable t) {
                d = 2f;
            }
            return Math.round(v * d);
        }
    }
}
