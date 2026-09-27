package com.wb.extrotator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「图标库」—— 把本机**有启动图标的应用**的图标抄一份存进本应用的私有目录，
 * 之后摇奖只读自己这份。
 *
 * <p><b>为什么要抄一份，而不是每次现取</b>：读别的应用的图标要跨进程拉那个 APK 的资源，
 * 单次几毫秒到几十毫秒不等，连读 8 个就是一次肉眼可见的卡顿；而且那是在**摇之前**做的，
 * 用户按下摇杆就在等这一下。抄成自己的 PNG 之后，摇奖只需要解码几张本地小图。
 *
 * <p><b>什么时候同步</b>：
 * <ol>
 *   <li>{@link AppIconReceiver} 收到「装了 / 卸了 / 更新了应用」的广播 —— 这是主路径；</li>
 *   <li>{@link #ensure} 兜底：库是空的、库版本变了、或者距上次检查超过 {@link #RECHECK_MS}
 *       时补一次。第二条是必要的 —— 广播在有些系统版本上不一定送得到，光靠它库会一直旧下去。</li>
 * </ol>
 * 同步本身是**增量**的：包名没变就不重画那张图，只有新装的应用会真去读图标。
 *
 * <p>⚠ 所有方法都不抛异常：这条链路连的是小组件，任何一次抛出去都会让桌面把整块换成
 * 「无法显示微件」，比"图标库是空的"严重得多。
 *
 * <p>⚠ 目录里只放**我们自己写出来**的 PNG。删文件那一步只删「包名已经不在本机了」的那几个，
 * 不做任何通配删除 —— 这个目录是本应用私有的，但依然只删自己认得的东西。
 */
public final class AppIconLib {

    private static final String TAG = "AppIconLib";

    /** 落盘尺寸（px）。跟布局里图标的显示尺寸同量级 —— 存大了只是白占内存和传输量。 */
    static final int PX = 72;

    private static final String DIR = "app_icons";
    private static final String SP = "app_icon_lib";
    private static final String K_INDEX = "index";
    private static final String K_VER = "ver";
    private static final String K_AT = "checked_at";

    /** 库的版本。落盘尺寸或画法一变就 +1，进来自动全部重建（老 PNG 尺寸对不上）。 */
    private static final int LIB_VER = 1;

    /** 库的容量上限。一台手机上万个应用也够，纯粹是给"某个桌面应用装了 500 个启动项"兜底。 */
    private static final int MAX = 200;

    /** 兜底复查的间隔。装应用那条广播没收到时，最多旧这么久。 */
    private static final long RECHECK_MS = 6 * 3600_000L;

    private static final String[] EMPTY = new String[0];

    /** 上一次读出来的清单，省掉每次都 split —— 组件会连着读好几次 */
    private static String[] cache;

    private AppIconLib() {
    }

    // ── 对外 ────────────────────────────────────────────────────────────

    /** 需要时补一次同步。**会读写磁盘，只能在后台线程调用。** */
    public static void ensure(Context ctx) {
        try {
            SharedPreferences sp = sp(ctx);
            boolean fresh = sp.getInt(K_VER, 0) == LIB_VER
                    && list(ctx).length > 0
                    && System.currentTimeMillis() - sp.getLong(K_AT, 0L) < RECHECK_MS;
            if (!fresh) {
                sync(ctx);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensure 失败", t);
        }
    }

    /** 收到"装了/卸了/更新了应用"之后在后台线程调一次 */
    public static void syncAsync(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                sync(app);
            }
        }, "app-icon-sync").start();
    }

    /**
     * 全量对齐一次：本机现在有哪些能启动的应用，库里就该有哪几张图。
     * <p>**会在后台线程调用**，也可以重复调用（幂等）。
     */
    public static synchronized void sync(Context ctx) {
        long t0 = System.currentTimeMillis();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> ris = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);

            // 按包名排序 + 一个应用只留一个入口（有的应用声明了好几个 LAUNCHER Activity）
            TreeMap<String, ResolveInfo> want = new TreeMap<>();
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri == null || ri.activityInfo == null) {
                        continue;
                    }
                    String p = ri.activityInfo.packageName;
                    if (p == null || p.isEmpty() || p.equals(ctx.getPackageName())) {
                        continue;   // 自己不算 —— 摇奖盘上出现本应用的图标只会让人以为出错了
                    }
                    if (!want.containsKey(p)) {
                        want.put(p, ri);
                    }
                }
            }

            String[] old = list(ctx);
            HashSet<String> oldSet = new HashSet<>(Arrays.asList(old));
            ArrayList<String> kept = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> e : want.entrySet()) {
                if (kept.size() >= MAX) {
                    break;
                }
                File f = png(ctx, e.getKey());
                if (!oldSet.contains(e.getKey()) || !f.isFile()) {
                    if (!write(f, e.getValue().loadIcon(pm))) {
                        continue;   // 画不出来就当它没图标，跳过，别拖垮整次同步
                    }
                }
                kept.add(e.getKey());
            }

            // 卸掉的应用：把它的图删了。只删清单里那些"以前有、现在没有"的，绝不扫目录
            HashSet<String> keepSet = new HashSet<>(kept);
            for (String p : old) {
                if (!keepSet.contains(p)) {
                    png(ctx, p).delete();
                }
            }

            cache = kept.toArray(new String[0]);
            sp(ctx).edit()
                    .putString(K_INDEX, join(kept))
                    .putInt(K_VER, LIB_VER)
                    .putLong(K_AT, System.currentTimeMillis())
                    .apply();
            Log.i(TAG, "图标库同步完成：" + kept.size() + " 个（本机 " + want.size()
                    + " 个可启动应用），" + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "图标库同步失败", t);
        }
    }

    /** 库里有哪些应用（包名）。**只读内存/SharedPreferences，可以随时调。** */
    public static String[] list(Context ctx) {
        if (cache != null) {
            return cache;
        }
        try {
            String s = sp(ctx).getString(K_INDEX, "");
            if (s == null || s.isEmpty()) {
                return EMPTY;
            }
            cache = s.split("\n", -1);
            return cache;
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /**
     * 取某个应用的图标位图（带透明通道，{@link #PX} 见方）。
     * <p>库里还没有它就现补一张 —— 同步可能正好没跑到（比如刚装完应用就被点了摇杆）。
     */
    public static Bitmap icon(Context ctx, String pkg) {
        try {
            File f = png(ctx, pkg);
            if (!f.isFile()) {
                if (!write(f, ctx.getPackageManager().getApplicationIcon(pkg))) {
                    return null;
                }
            }
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 把一张 drawable 按 {@link #PX} 画进 PNG 文件 */
    private static boolean write(File out, Drawable d) {
        if (d == null) {
            return false;
        }
        Bitmap bm = null;
        try {
            bm = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, PX, PX);
            d.draw(c);
            FileOutputStream fos = new FileOutputStream(out);
            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (bm != null) {
                bm.recycle();
            }
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    /** 包名只用得到 [a-z0-9._]，直接当文件名是安全的（不经过用户输入） */
    private static File png(Context ctx, String pkg) {
        return new File(dir(ctx), pkg + ".png");
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
