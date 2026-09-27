package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/**
 * 老虎机摇奖盘上的「图案池」。
 *
 * <p>一格图案 = 一张位图。池子固定 {@link #SIZE} 格：从 {@link AppIconLib} 里随机挑
 * {@link #APPS} 个应用，再加一个「七」（{@link #SEVEN}，对应
 * {@code drawable/widget_slot_seven.xml}）当彩蛋 —— 三格同摇到它才算头奖。
 *
 * <p><b>为什么位图要在这里再合成一层</b>：应用图标的 PNG 是带透明通道的，而
 * {@link Bitmap.Config#RGB_565}（体积只有 ARGB 的一半）没有 alpha，透明处会变成黑块；
 * 所以先把格子底色铺满再画图标，出来的是不带透明通道的方图。
 * 方图比格子小一圈（布局里 42dp、居中），四个角落在同色底板上，看不出来。
 *
 * <p>⚠ 池子要**存盘**：组件进程随时会被回收，而静止态、切模式、宿主重画都要照着
 * 上一次的结果把三个格子画出来。存的是一串包名（空串 = 那个「七」）。
 */
final class SlotPool {

    /** 每次开摇抽几个应用 */
    static final int APPS = 7;
    /** 池子总格数：7 个应用 + 一个「七」 */
    static final int SIZE = APPS + 1;
    /** 位图边长（px），跟 AppIconLib.PX / 布局里 42dp 的显示尺寸同量级 */
    static final int TILE_PX = 72;
    /** 格子底色（= drawable/widget_slot_cell 的 solid）。改那支 drawable 时这里要跟着改。 */
    private static final int CELL_BG = 0xFF2C2E33;

    private static final String SP = "slot_pool";
    private static final String K_POOL = "pool";

    private static final Random RND = new Random();

    /** 合成好的图案。键 = 包名 / {@link #SEVEN}。按字节数限容，装得下几百张。 */
    private static final LruCache<String, Bitmap> TILES =
            new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** 「七」在池子里的代号（存盘时写成空串） */
    static final String SEVEN = "@seven";

    private static final String[] EMPTY = new String[0];

    private SlotPool() {
    }

    /** 当前这一轮的池子；没存过就现抽一份并记下 */
    static String[] pool(Context ctx) {
        String[] p = decode(prefs(ctx).getString(K_POOL, ""));
        if (p.length >= 2) {
            return p;
        }
        p = pick(ctx);
        store(ctx, p);
        return p;
    }

    /**
     * 抽一份新池子。**会读图标库清单**（不读盘上的图，很快），可以在主线程调用。
     * 库里一个图标都没有时返回空数组 —— 调用方要判长度，别拿它去索引。
     */
    static String[] pick(Context ctx) {
        String[] all = AppIconLib.list(ctx);
        if (all.length == 0) {
            return EMPTY;
        }
        ArrayList<String> c = new ArrayList<>(Arrays.asList(all));
        Collections.shuffle(c, RND);
        int n = Math.min(APPS, c.size());
        String[] p = new String[n + 1];
        for (int i = 0; i < n; i++) {
            p[i] = c.get(i);
        }
        p[n] = null;                       // 「七」
        // 再把「七」挪到随机位置 —— 永远钉在最右边的话，"三连七"看着像摆拍
        int k = RND.nextInt(p.length);
        String t = p[k];
        p[k] = p[p.length - 1];
        p[p.length - 1] = t;
        return p;
    }

    static void store(Context ctx, String[] pool) {
        try {
            prefs(ctx).edit().putString(K_POOL, encode(pool)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 池子里每一项的图案位图；拿不到就是 null（那一格留空，不影响其它格） */
    static Bitmap[] tiles(Context ctx, String[] pool) {
        Bitmap[] out = new Bitmap[pool.length];
        for (int i = 0; i < pool.length; i++) {
            out[i] = tile(ctx, pool[i]);
        }
        return out;
    }

    /** 一张图案。命中缓存就直接返回 —— 已经合成过的不会重画。 */
    static Bitmap tile(Context ctx, String item) {
        String key = item == null ? SEVEN : item;
        Bitmap hit = TILES.get(key);
        if (hit != null) {
            return hit;
        }
        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(out);
            c.drawColor(CELL_BG);
            if (item == null) {
                Drawable d = ctx.getResources().getDrawable(R.drawable.widget_slot_seven, null);
                if (d != null) {
                    d.setBounds(0, 0, TILE_PX, TILE_PX);
                    d.draw(c);
                }
            } else {
                Bitmap src = AppIconLib.icon(ctx, item);
                if (src == null) {
                    return null;
                }
                // 库里存的就是 TILE_PX 见方，正常是 1:1；万一是老尺寸，这里按比例摆正
                float s = TILE_PX / (float) src.getWidth();
                float dy = (TILE_PX - src.getHeight() * s) / 2f;
                c.save();
                c.scale(s, s);
                c.drawBitmap(src, 0, dy / s, new Paint(Paint.FILTER_BITMAP_FLAG));
                c.restore();
                src.recycle();
            }
        } catch (Throwable t) {
            return null;
        }
        TILES.put(key, out);
        return out;
    }

    // ── 存盘格式 ────────────────────────────────────────────────────────
    // 包名之间用 \n 分隔，「七」写成空串 —— 包名里不可能出现这两个字符。

    private static String encode(String[] p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if (p[i] != null) {
                sb.append(p[i]);
            }
        }
        return sb.toString();
    }

    private static String[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return EMPTY;
        }
        String[] raw = s.split("\n", -1);
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i].isEmpty() ? null : raw[i];
        }
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
