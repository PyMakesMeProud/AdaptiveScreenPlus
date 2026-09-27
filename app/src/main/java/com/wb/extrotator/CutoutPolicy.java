package com.wb.extrotator;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「全局给应用去掉黑边」。
 *
 * <p><b>这东西到底在干什么</b>：内屏顶部有挖孔摄像头，系统给屏幕划了一块「挖孔安全区」。
 * 应用如果在清单里没有声明 {@code layoutInDisplayCutoutMode}，系统就把它当成「不想碰挖孔」，
 * 把内容压到安全区下面，那一圈就留成黑边（横屏时跑到左边，就是用户说的「左侧黑边」）。
 * 系统里有一个没公开的开关能给每个包单独指定策略：
 * {@code IActivityTaskManager.setCutoutPolicy(pkg, value)}，走 {@code service call} 打进去。
 *
 * <p><b>取值（拿 {@code com.android.settings} 做可逆 A/B 实测：依次下发 0/1/2/3 后 dump 窗口
 * 自己的 {@code layoutInDisplayCutoutMode}，得到 0→always、1→always、2→never、3→always）</b>：
 * <ul>
 *   <li>{@link #POL_OFF} = <b>1</b> —— 让窗口铺到挖孔里 ⇒ <b>黑边没了</b>；</li>
 *   <li>{@link #POL_DEFAULT} = <b>0</b> —— 枚举里的「默认」档，各家应用按自己的清单决定
 *       （API 35 以上它本身就等价于铺进挖孔，所以常常「看不出差别」）；</li>
 *   <li>2 = 强制不铺（一定留黑边）；3 = 也是铺（窗口属性仍报 {@code always}）。</li>
 * </ul>
 * 回读用的是紧接着的下一个编号（get = set + 1），能读到就说明命令是活的。
 *
 * <p>⚠ <b>关于「还原」</b>：原来只做了「下发 1」、没做还原，我们关掉开关时下发
 * {@link #POL_DEFAULT}（0）。实测「从未设过」的包回读是 1，所以 0 严格来说不等于出厂态 ——
 * 这一档属于「摸着看」的部分。
 *
 * <p>⚠ 发布前这套「全量下发」<b>从没在真机上真跑过</b>（只验证了回读通道与开关联动），
 * 全机状态是干净的，第一次由用户自己按下开关时才会真改。
 *
 * <p>命令走 {@link ShellRunner}（Shizuku → shell 身份），没有 Shizuku 就用不了。
 */
public final class CutoutPolicy {

    private static final String TAG = "CutoutPolicy";

    /** 去黑边：窗口铺进挖孔区 */
    public static final int POL_OFF = 1;
    /** 还原成"默认"档 */
    public static final int POL_DEFAULT = 0;

    /**
     * 阿田跳过的两个包：系统设置（改了以后设置页本身可能变得不好用）与它自己。
     * <p>我们照抄，并且再跳过我们自己 —— 本页面就在我们自己身上，
     * 不让它中途变形。
     */
    private static final String SKIP_SETTINGS = "com.android.settings";

    private static final Pattern LAST_WORD =
            Pattern.compile("([0-9a-fA-F]{8})\\s*'");

    private CutoutPolicy() {
    }

    /**
     * 这台机器上 {@code setCutoutPolicy} 的事务号 —— 按 SDK 写死的映射
     * （34→107、35→115、36→126），另有一套按 release 的兜底（14/15/16）。
     *
     * @return 事务号；本机既不认识 SDK 也不认识版本时返回 -1
     */
    public static int serviceCode() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 36) {
            return 126;
        }
        if (sdk == 35) {
            return 115;
        }
        if (sdk == 34) {
            return 107;
        }
        String rel = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
        if (rel.startsWith("16")) {
            return 126;
        }
        if (rel.startsWith("15")) {
            return 115;
        }
        if (rel.startsWith("14")) {
            return 107;
        }
        return -1;
    }

    public static boolean supported() {
        return serviceCode() > 0;
    }

    /**
     * 读某个包现在的策略值。
     *
     * @return 读到的值；命令不通 / 解析不出来返回 -1
     */
    public static int read(Context c, String pkg) {
        int code = serviceCode();
        if (code < 0) {
            return -1;
        }
        String out = ShellRunner.run(
                "service call activity_task " + (code + 1) + " i32 0 s16 " + pkg, 12);
        return parseValue(out);
    }

    /**
     * 从 {@code Result: Parcel(  00000000 00000001   '........')} 里抠出最后一个 8 位十六进制。
     * <p>取"最后一个"是因为回包第一个字永远是 0（异常码），值在第二个字上。
     */
    static int parseValue(String out) {
        if (out == null) {
            return -1;
        }
        int v = -1;
        try {
            Matcher m = LAST_WORD.matcher(out);
            while (m.find()) {
                v = (int) Long.parseLong(m.group(1), 16);
            }
        } catch (Throwable ignored) {
        }
        return v;
    }

    /**
     * 对<b>所有已装应用</b>下发同一个策略值（跳过 {@code com.android.settings} 与自身）。
     *
     * <p><b>阻塞</b>：几百个包挨个 {@code service call}，实测一条 40~60ms，整轮十几二十秒 ——
     * 调用方必须放在后台线程。
     *
     * @param policy {@link #POL_OFF} 或 {@link #POL_DEFAULT}
     * @return 一行给人看的结果（形如 {@code OK=… FAIL=… SKIP=…}），出错时返回带 ERR 的说明
     */
    public static String applyAll(Context c, int policy) {
        int code = serviceCode();
        if (code < 0) {
            return "ERR: 这台机器的 Android 版本没有对应的功能号（sdk="
                    + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE + "）";
        }
        if (!ShellRunner.isReady()) {
            return "ERR: Shizuku 未就绪";
        }
        final String self = c.getPackageName();
        String cmd =
                "TMP=/data/local/tmp/extrot_cutout_$$.txt; "
                        + "pm list packages > \"$TMP\" 2>&1 || { echo ERR:pmlist; exit 3; }; "
                        + "OK=0; FAIL=0; SKIP=0; FIRSTFAIL=''; "
                        + "while IFS= read -r LINE; do "
                        + "PKG=${LINE#package:}; PKG=$(echo \"$PKG\" | tr -d '\\r'); "
                        + "if [ -z \"$PKG\" ] || [ \"$PKG\" = \"" + SKIP_SETTINGS + "\" ] "
                        + "|| [ \"$PKG\" = \"" + self + "\" ]; then SKIP=$((SKIP+1)); continue; fi; "
                        + "OUT=$(service call activity_task " + code
                        + " i32 0 s16 \"$PKG\" i32 " + policy + " 2>&1); "
                        + "if [ $? = 0 ]; then OK=$((OK+1)); "
                        + "else FAIL=$((FAIL+1)); "
                        + "[ -z \"$FIRSTFAIL\" ] && FIRSTFAIL=\"$PKG:$OUT\"; fi; "
                        + "done < \"$TMP\"; rm -f \"$TMP\"; "
                        + "echo \"OK=$OK FAIL=$FAIL SKIP=$SKIP $FIRSTFAIL\"";
        String out = ShellRunner.run(cmd, 300);
        Log.i(TAG, "applyAll policy=" + policy + " => " + out);
        return out;
    }
}
