package com.wb.extrotator;

import android.content.Context;

/**
 * 「隐藏三键导航」：把系统那条导航条整条压进沉浸态（桌面也藏）。
 *
 * <p><b>做法只有一条</b> —— 往 {@code Settings.Global.policy_control} 写策略串
 * {@code immersive.navigation=*}。这是系统自带的沉浸模式开关，不是我们的私有通道：
 * Good Lock 里的 NavStar 干的也是同一件事（反编译取证的全文见 process/navbar/EVIDENCE.md §7）。
 *
 * <p><b>为什么不是改「导航模式」</b>：系统的导航模式只有「按钮 / 双键 / 手势」三档，
 * <b>没有"无导航条"这一档</b>；三档的条高又都写在 framework 资源里，无 root 改不动
 * （RRO 不许覆盖 android 包，`cmd overlay fabricate` 从 2022-01 起也要 root）。
 * 沉浸模式是唯一一条不动资源就能把条藏掉的路。
 *
 * <p><b>为什么开启前要备份</b>：这一项是全局的，别人也在用（NavStar、某些沉浸式应用）。
 * 我们只是暂时占着它，关掉开关时要还回去 —— 一律写空会把用户原来的策略一起抹掉。
 *
 * <p>⚠ <b>NavStar 会打架</b>：它常驻监听这一项、按自己的开关重写。所以每次写完都必须
 * 回读校验（见 {@link #apply}），不校验就会变成"界面显示开了、条还在"。
 */
public final class NavBarImmersive {

    /** 要写的策略串。`immersive.navigation=` 后面跟包名列表，`*` = 全部包（含桌面） */
    public static final String STRATEGY = "immersive.navigation=*";

    private NavBarImmersive() {
    }

    /**
     * 读回系统当前的策略串。
     *
     * @return 没设置过返回<b>空串</b>；压根读不到（Shizuku 没就绪 / 命令超时）返回 <b>null</b>
     *         —— 这两种必须分开：前者是"确实没开"，后者是"不知道"，界面上的话不一样。
     */
    public static String read() {
        String s = ShellRunner.run("settings get global policy_control", 10);
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty() || s.startsWith("ERR") || s.contains("TIMEOUT")) {
            return null;
        }
        if ("null".equals(s)) {
            return "";
        }
        return s;
    }

    /**
     * 这串策略算不算"已经全局藏掉了"。
     *
     * <p>判据是 <b>恰好</b> {@code *} 一项：NavStar 的「固定在主屏幕上」写的是
     * {@code *,-com.sec.android.app.launcher}（桌面除外），那种<u>不算</u> ——
     * 用户要的就是桌面也一起藏。
     */
    public static boolean isOn(String policy) {
        if (policy == null || policy.isEmpty()) {
            return false;
        }
        // 语法：一整个设置项由 ':' 分成若干段，每段是 key=value，冒号后面还能跟 flags
        for (String item : policy.split(":")) {
            String v = item.trim();
            if (!v.startsWith("immersive.navigation=")) {
                continue;
            }
            String pkgs = v.substring("immersive.navigation=".length()).trim();
            String[] parts = pkgs.split(",");
            return parts.length == 1 && "*".equals(parts[0].trim());
        }
        return false;
    }

    /**
     * 拨开关。
     *
     * @param on true = 藏掉，false = 还原
     * @return null 表示成功；否则是一句能直接显示给用户的话
     */
    public static String apply(Context c, boolean on) {
        if (!ShellRunner.isReady()) {
            return "Shizuku 未就绪，请先在主界面授权";
        }
        final String before = read();
        if (before == null) {
            return "读不到系统当前的沉浸策略，先确认 Shizuku 在线";
        }

        final String target;
        if (on) {
            // 只在第一次开的时候备份 —— 反复开关不能把我们自己写的串覆盖成"原值"
            if (ExtPrefs.navImmBackup(c) == null) {
                ExtPrefs.setNavImmBackup(c, before);
            }
            target = STRATEGY;
        } else {
            String saved = clean(ExtPrefs.navImmBackup(c));
            if (saved.isEmpty() || isOn(saved)) {
                // 没备份过（换机 / 清了数据），或备份的本来就是这串策略 —— 两种情况都只能写空
                target = "";
            } else {
                target = saved;
            }
        }

        ShellRunner.run("settings put global policy_control "
                + (target.isEmpty() ? "null" : "'" + target + "'"), 10);

        // 回读校验：NavStar 在旁时会立刻把值改回它自己的偏好
        final String after = read();
        final boolean ok = on ? isOn(after) : !isOn(after);
        if (!ok) {
            return "写下去了但没生效（现在读到的是 "
                    + (after == null || after.isEmpty() ? "空" : after) + "）"
                    + "。装过 Good Lock 的话，先把 NavStar 里的同名开关关掉再试。";
        }

        ExtPrefs.setNavImmOn(c, on);
        return null;
    }

    /** 单引号会把 shell 的引号配对打断。备份值理论上干净，仍然清一道再拼命令 */
    private static String clean(String s) {
        return s == null ? "" : s.replace("'", "").trim();
    }
}
