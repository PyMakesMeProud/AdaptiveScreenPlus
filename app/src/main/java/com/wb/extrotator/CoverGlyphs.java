package com.wb.extrotator;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * 侧边栏几套动画共用的一小撮画笔工具：一档指令（返回 / 后台 / 通知 / 桌面）的<b>名字</b>，
 * 以及那个手画的小图标。
 *
 * <p><b>为什么单独拎出来</b>：几套动画挂的是同一组指令，图标与叫法必须一模一样 ——
 * 各画各的，改一处忘一处，几套动画就会走形。以后新增第三套动画直接调这里。
 *
 * <p>⚠ {@link CoverWheel} 里还留着自己的一份同源代码 —— 那一套的观感用户已经验收过，
 * 本版不碰它，等哪次真要动圆环时再一并换过来。
 */
final class CoverGlyphs {

    private CoverGlyphs() {
    }

    /** 这一档指令在屏上的叫法 */
    static String label(int act) {
        switch (act) {
            case ExtPrefs.RING_BACK:
                return "返回";
            case ExtPrefs.RING_RECENTS:
                return "后台";
            case ExtPrefs.RING_NOTIFY:
                return "通知";
            case ExtPrefs.RING_HOME:
                return "桌面";
            default:
                return "空";
        }
    }

    /**
     * 画一档指令的小图标。
     *
     * @param glyph 描边笔（STROKE + 圆头圆角）
     * @param dot   实心笔：画通知下面那个小圆点用，函数里会把它改成 FILL
     * @param path  借用的路径，进来会 reset
     * @param rect  借用的矩形，进来会被改写
     * @param dir   朝屏内的方向：+1 = 朝右，-1 = 朝左（决定返回箭头指哪边）
     * @param s     图标边长（px）
     */
    static void draw(Canvas cv, Paint glyph, Paint dot, Path path, RectF rect,
                     int act, float x, float y, float s, float dir) {
        switch (act) {
            case ExtPrefs.RING_BACK:
                // 箭头朝着"划出去"的方向，一看就知道往哪划
                path.reset();
                path.moveTo(x - dir * s * 0.5f, y - s * 0.55f);
                path.lineTo(x + dir * s * 0.42f, y);
                path.lineTo(x - dir * s * 0.5f, y + s * 0.55f);
                cv.drawPath(path, glyph);
                break;
            case ExtPrefs.RING_RECENTS: {
                float w = s * 0.6f;
                float h = s * 1.0f;
                for (int k = 0; k < 3; k++) {
                    float ox = x + (k - 1) * s * 0.56f;
                    rect.set(ox - w / 2f, y - h / 2f, ox + w / 2f, y + h / 2f);
                    cv.drawRoundRect(rect, s * 0.22f, s * 0.22f, glyph);
                }
                break;
            }
            case ExtPrefs.RING_NOTIFY: {
                rect.set(x - s * 0.5f, y - s * 0.62f, x + s * 0.5f, y + s * 0.2f);
                path.reset();
                path.addArc(rect, 180f, 180f);
                path.moveTo(x - s * 0.5f, rect.centerY());
                path.lineTo(x - s * 0.5f, y + s * 0.38f);
                path.lineTo(x + s * 0.5f, y + s * 0.38f);
                path.lineTo(x + s * 0.5f, rect.centerY());
                cv.drawPath(path, glyph);
                dot.setStyle(Paint.Style.FILL);
                dot.setShader(null);
                dot.setColor(glyph.getColor());
                cv.drawCircle(x, y + s * 0.66f, s * 0.14f, dot);
                break;
            }
            case ExtPrefs.RING_HOME:
                path.reset();
                path.moveTo(x - s * 0.55f, y + s * 0.05f);
                path.lineTo(x, y - s * 0.6f);
                path.lineTo(x + s * 0.55f, y + s * 0.05f);
                cv.drawPath(path, glyph);
                rect.set(x - s * 0.34f, y + s * 0.05f, x + s * 0.34f, y + s * 0.6f);
                cv.drawRoundRect(rect, s * 0.14f, s * 0.14f, glyph);
                break;
            default:
                // 这一档没配指令：画个空心圈占位
                cv.drawCircle(x, y, s * 0.5f, glyph);
                break;
        }
    }
}
