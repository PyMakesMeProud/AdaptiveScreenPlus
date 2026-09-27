package com.wb.extrotator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * 把「启动器背景图」那个私有文件，暴露成一个只读的 {@code content://} 地址。
 *
 * <p><b>为什么需要它</b>：小组件是 RemoteViews，跑在桌面进程里。
 * 图片递过去只有两条路 —— 塞位图，或者塞地址。塞位图不行：
 * 748×720 的 ARGB 位图有 2MB，而 RemoteViews 过 Binder 的事务上限是 1MB，直接顶穿。
 * 剩下的就是地址，而地址必须是 {@code content://}（{@code file://} 从 Android 7 起
 * 不许跨进程给），图片又躺在应用的私有目录里谁也读不到 —— 所以要这么一层出口。
 * 小组件那层 ImageView 走的 {@code setImageViewUri}，见 {@link CoverLauncherProvider#build}。
 *
 * <p><b>为什么不用 androidx 的 FileProvider</b>：本工程不带 androidx（见 build.gradle）。
 * 这里只需要"读一个固定文件"，自己写比拉一整个依赖划算。
 *
 * <p><b>⚠ 两个踩过的坑（v4.14 修的）</b>
 * <ol>
 *   <li>清单里的 {@code exported} <b>必须为 true</b>。原本写 false，以为"更新小组件时
 *       system_server 会把 RemoteViews 里出现过的 URI 授权给宿主" —— 实测<b>没有这回事</b>，
 *       宿主（systemui，uid 10049）照样被 {@code not exported from UID ...} 拒掉。</li>
 *   <li>{@link #openFile} <b>绝不能抛异常</b>。小组件那边是在
 *       {@code RemoteViews$BaseReflectionAction.apply} 里反射调 {@code setImageURI}，
 *       抛出去的任何异常都会被包成 {@code ActionException} 冒到宿主，
 *       宿主就把<b>整块小组件</b>换成「无法显示微件」—— 不是图空着，是全挂。</li>
 * </ol>
 * 于是策略定成：{@code exported=true} 让 exported 那道检查不再拦，
 * 访问控制自己拿（{@link #callerTrusted}）；
 * 而不管什么情况，{@link #openFile} 都递出<b>一个能解码的文件</b>
 * （真图读不到就递 {@link #blank}）。
 */
public class PhotoProvider extends ContentProvider {

    /** 清单里注册的 authority，两处必须一致 */
    public static final String AUTHORITY = "com.wb.extrotator.photo";

    /** 路径段，只是个名字，不参与寻址（就一个文件） */
    private static final String NAME = "bg";

    /** 递不出真图时用的那张 1×1 空图（私有目录里的文件名） */
    private static final String BLANK = "photo_blank.png";

    /** 递给 {@code setImageViewUri} 的地址 */
    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = callerTrusted() ? LauncherPhoto.fileWidget(getContext()) : null;
        if (f == null || !f.exists() || f.length() <= 0L) {
            /*
             * 三种情况都落到这里：还没设过背景图 / 图刚被撤掉、小组件还没跟上 /
             * 调用方不在白名单。**一律递空图，绝不抛异常**（理由见类注释第二条）。
             */
            f = blank();
        }
        if (f == null) {
            // 连空图都写不出来（磁盘满之类），这时除了抛没有别的选择
            throw new FileNotFoundException("连兜底图都写不出来");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * 调用方值不值得给真图。名单是手工的：外屏宿主 systemui、内屏桌面（三星 launcher）、
     * 以及我们自己。
     *
     * <p>不在名单里<b>不算错</b>，只是递空图 —— 挡错人的代价是"背景图不显示"，
     * 而不是"小组件整块挂掉"（v4.14 就是被后一种代价坑了）。
     */
    private boolean callerTrusted() {
        final int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) {
            return true;
        }
        final String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
        if (pkgs == null) {
            return false;
        }
        for (String p : pkgs) {
            if ("com.android.systemui".equals(p) || "com.sec.android.app.launcher".equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一张 1×1 的透明 PNG，落在私有目录里，第一次用到时才生成。
     * 存在的唯一目的：让宿主永远拿得到一个能解码的文件。
     */
    private File blank() {
        File f = new File(getContext().getFilesDir(), BLANK);
        if (f.exists() && f.length() > 0L) {
            return f;
        }
        try {
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            FileOutputStream out = new FileOutputStream(f);
            try {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
                b.recycle();
            }
        } catch (Throwable t) {
            Log.w("PhotoProvider", "空图写不出来", t);
        }
        return f.exists() && f.length() > 0L ? f : null;
    }

    // 只出不进，下面这些按只读 provider 的惯例留空壳

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
