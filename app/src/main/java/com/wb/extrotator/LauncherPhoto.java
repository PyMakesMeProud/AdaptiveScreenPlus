package com.wb.extrotator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 「外屏启动器」的背景图片：选进来之后怎么处理、存在哪、两边（配置页与小组件）各怎么取。
 *
 * <p><b>存两个文件，不是偷懒</b>：
 * <ul>
 *   <li>{@code launcher_bg} —— 等比缩到 {@link #MAX_EDGE} 以内的原图（JPEG），
 *       给配置页铺底。配置页是全屏 Activity，图随便铺。</li>
 *   <li>{@code launcher_bg_widget} —— 封面屏满屏像素、<b>四角已经裁圆</b>的 PNG，
 *       给小组件那层 ImageView。为什么要预裁：那块图跑在桌面进程的 RemoteViews 里，
 *       能用的控件只有那几种，做不了圆角遮罩；整块的圆角是 20dp、跟底板 shape 一致，
 *       所以干脆把圆角画进位图本身，传过去就自带。</li>
 * </ul>
 *
 * <p><b>图片是"底"不是"皮"</b>：设过图之后，原来那层半透明黑（{@link LauncherPrefs#bgDrawable}）
 * 仍然压在图片上面，只是变成了"图片上盖多厚的黑"。所以「背景图片」和「不透明度」
 * 是叠在一起的两层，互不排斥 —— 配置页与小组件两边的叠法完全一致，看着才是一套。
 *
 * <p>⚠ {@link #WIDGET_W} / {@link #WIDGET_H} 是<b>封面屏的像素尺寸</b>（748×720，见 RULES 屏参表），
 * 不是 dp。换机器或换分辨率要跟着改，否则小组件那张图的圆角会跟底板对不上。
 *
 * <p>⚠ {@link #save} 里有解码和两次压缩，只能在后台线程调。
 */
public final class LauncherPhoto {

    private static final String TAG = "LauncherPhoto";

    /** 配置页那份的文件名（JPEG） */
    private static final String NAME_FULL = "launcher_bg";
    /** 小组件那份的文件名（PNG，四角已裁圆） */
    private static final String NAME_WIDGET = "launcher_bg_widget";

    /**
     * 存盘时最长边的上限。封面屏才 748px 宽，1520 足够配置页全屏铺开还不糊，
     * 又不至于把一张 1200 万像素的照片原样塞进私有目录。
     */
    private static final int MAX_EDGE = 1520;

    /** 小组件那张图的像素尺寸 = 封面屏满屏（748×720） */
    private static final int WIDGET_W = 748;
    private static final int WIDGET_H = 720;

    /**
     * 小组件那张图的圆角半径（像素）。
     * 要跟 {@code launcher_widget_bg_*.xml} 里的 {@code corners radius="20dp"} 对上：
     * 20dp × (340/160) ≈ 43px（封面屏密度 340dpi）。
     */
    private static final float WIDGET_RADIUS = 43f;

    /** 配置页那份的解码缓存 —— 切档位、改列数都会重取一次，别每次都读盘 */
    private static Bitmap sCache;
    private static long sCacheStamp = -1L;

    private LauncherPhoto() {
    }

    // ------------------------------------------------------------------ 文件与状态

    /** 配置页那份（原图） */
    static File fileFull(Context c) {
        return new File(c.getFilesDir(), NAME_FULL);
    }

    /** 小组件那份（圆角 PNG） */
    static File fileWidget(Context c) {
        return new File(c.getFilesDir(), NAME_WIDGET);
    }

    /**
     * 设过背景图没有 —— <b>认文件</b>，不另存一个开关。
     * 这样即使半路写盘失败、或者用户清了应用数据，状态也不会说一套做一套。
     */
    public static boolean has(Context c) {
        File f = fileWidget(c);
        return f.exists() && f.length() > 0L;
    }

    /** 撤掉背景图，回到纯底板。两个文件一起删，别留孤儿。 */
    public static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        fileFull(c).delete();
        //noinspection ResultOfMethodCallIgnored
        fileWidget(c).delete();
        synchronized (LauncherPhoto.class) {
            if (sCache != null && !sCache.isRecycled()) {
                sCache.recycle();
            }
            sCache = null;
            sCacheStamp = -1L;
        }
    }

    // ------------------------------------------------------------------ 存

    /**
     * 把用户选中的那张图处理成上面两个文件。⚠ 后台线程。
     *
     * <p>两张都先写成 {@code .tmp}、都成了再改名顶上。这样中途失败时旧的那对还在 ——
     * 选错一张图不该把已经设好的背景弄丢。
     *
     * @return 成不成
     */
    public static boolean save(Context c, Uri src) {
        File tmpFull = new File(c.getFilesDir(), NAME_FULL + ".tmp");
        File tmpWidget = new File(c.getFilesDir(), NAME_WIDGET + ".tmp");
        Bitmap full = null;
        Bitmap round = null;
        try {
            full = decode(c, src, MAX_EDGE);
            if (full == null) {
                Log.w(TAG, "解不出这张图：" + src);
                return false;
            }

            OutputStream o1 = new FileOutputStream(tmpFull);
            try {
                full.compress(Bitmap.CompressFormat.JPEG, 90, o1);
            } finally {
                o1.close();
            }

            round = rounded(full);
            OutputStream o2 = new FileOutputStream(tmpWidget);
            try {
                round.compress(Bitmap.CompressFormat.PNG, 100, o2);
            } finally {
                o2.close();
            }

            if (!tmpFull.renameTo(fileFull(c)) || !tmpWidget.renameTo(fileWidget(c))) {
                Log.w(TAG, "改名顶上失败，两张图没换成");
                return false;
            }
            Log.i(TAG, "背景图已存：full=" + fileFull(c).length()
                    + "B / widget=" + fileWidget(c).length() + "B");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "存背景图失败", t);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpFull.delete();
            //noinspection ResultOfMethodCallIgnored
            tmpWidget.delete();
            if (round != null) {
                round.recycle();
            }
            if (full != null) {
                full.recycle();
            }
        }
    }

    /**
     * 读一张图并缩到 {@code maxEdge} 以内。
     *
     * <p>走 {@link ImageDecoder} 而不是 {@code BitmapFactory}：<b>它会按 EXIF 把方向摆正</b>
     * （手机竖着拍的片子，BitmapFactory 解出来是躺着的），而且能直接指定目标尺寸，
     * 不用自己算 inSampleSize。
     *
     * <p>要软件位图：默认可能给硬件位图，那种位图的像素读不出来、也压不进文件。
     */
    private static Bitmap decode(Context c, Uri src, int maxEdge) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(c.getContentResolver(), src);
        return ImageDecoder.decodeBitmap(source, (decoder, info, s) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            int longEdge = Math.max(w, h);
            float k = longEdge > maxEdge ? maxEdge / (float) longEdge : 1f;
            decoder.setTargetSize(Math.max(1, Math.round(w * k)),
                    Math.max(1, Math.round(h * k)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    /** 裁成封面屏比例 + 四角切圆 —— 出来就是小组件要的那张贴图 */
    private static Bitmap rounded(Bitmap src) {
        Bitmap cut = crop(src, WIDGET_W, WIDGET_H);
        Bitmap out = Bitmap.createBitmap(WIDGET_W, WIDGET_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(cut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = Math.min(WIDGET_RADIUS, Math.min(WIDGET_W, WIDGET_H) / 2f);
        cv.drawRoundRect(new RectF(0f, 0f, WIDGET_W, WIDGET_H), r, r, p);
        cut.recycle();
        return out;
    }

    /** 居中裁剪 + 缩放，跟 ImageView 的 centerCrop 一个意思 */
    private static Bitmap crop(Bitmap src, int w, int h) {
        float k = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int sw = Math.min(src.getWidth(), Math.max(1, Math.round(w / k)));
        int sh = Math.min(src.getHeight(), Math.max(1, Math.round(h / k)));
        int x = Math.max(0, (src.getWidth() - sw) / 2);
        int y = Math.max(0, (src.getHeight() - sh) / 2);
        Bitmap cut = Bitmap.createBitmap(src, x, y, sw, sh);
        if (sw == w && sh == h) {
            return cut;
        }
        Bitmap out = Bitmap.createScaledBitmap(cut, w, h, true);
        if (out != cut) {
            cut.recycle();
        }
        return out;
    }

    // ------------------------------------------------------------------ 取

    /**
     * 配置页整页的那层背景：图片在下、半透明黑在上 —— 跟小组件那两层的叠法一模一样。
     *
     * <p>没设过图就返回 {@code null}，调用方退回原来的纯色底，行为跟老版本一致。
     *
     * <p>⚠ 第一次会读盘解码，别在主线程调。
     *
     * @param alphaPct 就是「不透明度」那一档（0/10/30/50）
     */
    public static Drawable pageBackdrop(Context c, int alphaPct) {
        File f = fileFull(c);
        if (!f.exists()) {
            return null;
        }
        Bitmap bmp;
        synchronized (LauncherPhoto.class) {
            long stamp = f.lastModified();
            if (sCache == null || sCache.isRecycled() || sCacheStamp != stamp) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b == null) {
                    return null;
                }
                if (sCache != null && !sCache.isRecycled()) {
                    sCache.recycle();
                }
                sCache = b;
                sCacheStamp = stamp;
            }
            bmp = sCache;
        }
        int a = Math.max(0, Math.min(100, alphaPct)) * 255 / 100;
        // 遮罩跟底板同色（#101014），只差 alpha —— 换档位时颜色不会跳
        ColorDrawable veil = new ColorDrawable((a << 24) | 0x00101014);
        return new LayerDrawable(new Drawable[]{new Cover(bmp), veil});
    }

    /**
     * 把图片按 centerCrop 铺满自己的边界。
     *
     * <p>为什么不直接用 BitmapDrawable 配 {@code setGravity(FILL)}：那会把比例一起改掉。
     * 这里只在"多出来的那一边"裁，图不会拉长压扁。
     */
    static final class Cover extends Drawable {

        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bmp;

        Cover(Bitmap bmp) {
            this.bmp = bmp;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
                return;
            }
            float k = Math.max(b.width() / (float) bmp.getWidth(),
                    b.height() / (float) bmp.getHeight());
            float w = bmp.getWidth() * k;
            float h = bmp.getHeight() * k;
            float l = b.left + (b.width() - w) / 2f;
            float t = b.top + (b.height() - h) / 2f;
            canvas.drawBitmap(bmp, null, new RectF(l, t, l + w, t + h), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
