package com.wb.extrotator;

/**
 * 手机内屏（内部 display 0）的分辨率覆盖控制。
 *
 * <p>实证结论（Galaxy Z Flip5 / Android 14，用 adb 逐条验证过）：
 * <pre>
 *   wm size -d 0              → 只是【读取】display 0 的尺寸
 *   wm size -d 0 1080x2160    → 带值时 -d 被忽略，命令退化成读取，写不进去
 *   wm size 1080x1920         → 真正写入成功（默认作用对象就是 display 0，即内屏）
 *   wm size reset             → 清除覆盖，回到面板原生分辨率
 * </pre>
 * 所以这里一律不带 {@code -d}。⚠ {@code wm user-rotation} 与它不同，那条命令的 {@code -d}
 * 是生效的（要写外接屏必须带）。
 */
public final class ScreenSizeUtil {

    private ScreenSizeUtil() {
    }

    /**
     * 把任意一组宽高规范化成「竖屏」方向，即保证 高 ≥ 宽。
     *
     * <p>为什么需要它：便携屏的面板大多是横向的（例如 1920×1080），而手机内屏
     * 镜像给它是竖着看的，内屏必须用 1080×1920 才能和转 90° 后的面板对上，
     * 否则会出现黑边或拉伸。所以 1920×1080 → 1080×1920。
     *
     * @return 新的 {宽, 高}；入参非法时原样返回
     */
    public static int[] portrait(int w, int h) {
        if (w <= 0 || h <= 0) {
            return new int[]{w, h};
        }
        return w <= h ? new int[]{w, h} : new int[]{h, w};
    }

    /** 这组宽高是不是横向的（宽 > 高） */
    public static boolean isLandscape(int w, int h) {
        return w > 0 && h > 0 && w > h;
    }

    /**
     * 给内屏加上分辨率覆盖；density &le; 0 表示不动 DPI。
     * <p>宽高在这里统一过一道 {@link #portrait} —— 这是唯一真正写盘的地方，
     * 放在这里可以保证无论谁调用都不会把横屏分辨率写进内屏。
     */
    public static String apply(int w, int h, int density) {
        int[] p = portrait(w, h);
        StringBuilder sb = new StringBuilder();
        sb.append(ShellRunner.run("wm size " + p[0] + "x" + p[1]));
        if (density > 0) {
            sb.append(" | dpi: ").append(ShellRunner.run("wm density " + density));
        }
        return sb.toString().trim();
    }

    /**
     * 写入一套覆盖，<b>不做</b>「竖屏强转」。
     *
     * <p>{@link #apply} 那条路一律把宽高摆成竖的（高 ≥ 宽），那是给「接便携屏」用的
     * （便携屏面板天生横放，内屏得配竖的数值才对得上）。
     *
     * <p>但投屏页那个「内屏分辨率重设」不行：内屏自己可能正被转成横的，而 {@code wm size}
     * 写的是「没转过」的基准值 —— 要让「看到的」是竖的，写进去的必须是横的，强转一下正好把它弄反。
     * 所以单独留这个口子，调用方负责把方向算对（见 CastActivity.applyCastResize）。
     */
    public static String write(int w, int h, int density) {
        if (w <= 0 || h <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ShellRunner.run("wm size " + w + "x" + h));
        if (density > 0) {
            sb.append(" | dpi: ").append(ShellRunner.run("wm density " + density));
        }
        return sb.toString().trim();
    }

    /** 清除内屏的分辨率覆盖，回到面板原生分辨率 */
    public static String reset(boolean alsoDensity) {
        StringBuilder sb = new StringBuilder();
        sb.append(ShellRunner.run("wm size reset"));
        if (alsoDensity) {
            sb.append(" | dpi: ").append(ShellRunner.run("wm density reset"));
        }
        return sb.toString().trim();
    }

    /**
     * 现在这套覆盖长什么样（直接从设置里抄原文）。
     *
     * <p>「内屏分辨率重设」是<b>临时</b>改一下、收工要还回去的；还的时候直接
     * {@code wm size reset} 就坏了事：用户自己在主界面设过覆盖的话，reset 会把它一并抹掉
     * —— 等于「投个屏顺手帮你改了设置」。所以改之前先抄下来，收工照着抄回去。
     *
     * <p>⚠ 出口统一成 {@code 1080x1920} 这种 x 格式，调用方拿它去 {@link #restore}
     * 时不用再转换。
     *
     * @return 形如 {@code 1080x1920} 的值；从没设过覆盖时返回 {@code null}
     */
    public static String forced() {
        String s = ShellRunner.run("settings get global display_size_forced");
        if (s == null) {
            return null;
        }
        // 没设过覆盖时它返回字面量 "null"（有的机型给空串），两种都当"没有"
        String t = s.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) {
            return null;
        }
        /*
         * ⚠ v3.20 在这儿栽过：设置里存的是**逗号**（"1080,1920"），
         * 而 `wm size` 只认 x（"1080x1920"）。把原文直接喂给 restore() 会
         * 静默失败（命令格式不对），投屏结束后分辨率还回去失败。
         * 出口就换成 x，让"读到的"和"能写回去的"是同一种写法。
         */
        return t.replace(',', 'x');
    }

    /**
     * 把覆盖还原成当初抄下来的样子。
     *
     * @param prev {@link #forced()} 当初抄到的原文；{@code null} = 当初就没有覆盖
     */
    public static String restore(String prev) {
        if (prev == null || prev.isEmpty()) {
            return ShellRunner.run("wm size reset");
        }
        // 兜一道：万一拿到的是设置里那种逗号写法，换成 wm size 认的 x（见 forced）
        return ShellRunner.run("wm size " + prev.replace(',', 'x'));
    }

    /**
     * 给内屏算一套「让<b>看到的</b>画面跟某个框一样宽高比」的尺寸 —— 投屏页铺满用。
     *
     * <p>投到的那个框在封面屏上是扁的，内屏等比缩放之后画面只占中间一条、两边一片黑；
     * 把内屏的<b>宽高比</b>改成跟框一样，缩放就正好铺满 —— 这是「看起来能满」的唯一办法
     * （框那边不能变形，变了触点就偏）。
     *
     * <p>⚠ <b>返回的是「期望看到的」尺寸，不是「要写进去的」</b>：内屏被转 90° 时
     * {@code wm size} 写的是没转过的那套基准值，看到的宽高正好对调 —— 所以写什么由调用方决定
     * （见 {@code CastActivity.applyCastResize}），这里只负责把比例算对。
     *
     * <p>⚠ <b>不能把比例封顶成 1:1</b>：那句「竖屏拿不出横比例」只在<b>内屏没被转</b>时成立，
     * 转成横的之后封顶等于硬把画面对成正方形，上下照样留黑边
     * （实测：框 1199×1000，写进去的是 1080×1080）。
     *
     * @param boxW 框宽（像素）
     * @param boxH 框高（像素）
     * @param base 短边取多少像素（一般给内屏当前短边，缩放倍率才不会被改掉）
     * @return {宽, 高}（期望看到的）；入参非法时返回 null
     */
    public static int[] fitToBox(int boxW, int boxH, int base) {
        if (boxW <= 0 || boxH <= 0 || base <= 0) {
            return null;
        }
        double r = (double) boxW / (double) boxH;   // 框的宽高比
        int vw, vh;
        if (r >= 1.0) {
            // 框是横的（或正方形）：看到的就摆成横的，长边按比例出来
            vh = base;
            vw = (int) Math.round(base * r);
        } else {
            vw = base;
            vh = (int) Math.round(base / r);
        }
        // 取整到 4 的倍数：不要求系统去凑奇数像素，避免缩放滤波出现一条暗边
        vw = Math.max(4, (vw + 2) / 4 * 4);
        vh = Math.max(4, (vh + 2) / 4 * 4);
        return new int[]{vw, vh};
    }
}
