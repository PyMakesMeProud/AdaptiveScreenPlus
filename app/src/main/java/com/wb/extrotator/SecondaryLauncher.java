package com.wb.extrotator;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 把 Activity 投到指定的显示屏上。
 *
 * <p>三条路，按"稳"排下来：
 *
 * <ol>
 *   <li><b>shell（Shizuku）</b> —— {@code am start --display <id> -n <pkg>/<cls>}。shell 持有
 *       START_ANY_ACTIVITY，能启动任何组件（包括未导出的），{@code --display} 在 Android 14
 *       上稳定可用，实测能把本应用与第三方应用投到封面屏 displayId=1。</li>
 *   <li><b>三星封面屏通道</b> —— {@link #coverLaunch}。三星给 Good Lock（MultiStar）的封面屏
 *       启动器留的系统接口，反射就能调、<b>什么权限都不要</b>，跨应用也成。第 1 条走不了
 *       （没装 Shizuku / 没授权）时它是主力。</li>
 *   <li><b>直接 startActivity + {@code setLaunchDisplayId}</b> —— 不需要权限，但只对自己进程的
 *       组件可靠；跨应用投屏会被「Permission Denial: ... not allowed on display」拒掉，只当兜底。</li>
 * </ol>
 */
public final class SecondaryLauncher {

    private static final String TAG = "SecondaryLauncher";

    private SecondaryLauncher() {
    }

    public static String shellCommand(int displayId, ComponentName cn) {
        return "am start --display " + displayId + " -n " + cn.flattenToShortString();
    }

    // ------------------------------------------------------------------ 三星封面屏通道

    /** 三星那个接口认的启动器名字（MultiStar 的封面屏启动器就叫这个） */
    private static final String COVER_LAUNCHER_NAME = "AppsLauncherWidgetProvider";

    /** 三星自己的标记：让目标应用知道「这次是从封面屏启动器起来的」 */
    private static final String EXTRA_FROM_COVER_LAUNCHER =
            "com.sec.intent.extra.IS_LAUNCHED_FROM_MULTISTAR_COVER_LAUNCHER";

    /**
     * 把 Intent 交给三星的封面屏启动通道 —— <b>不需要 Shizuku，也不需要 root</b>。
     *
     * <p>三星在 {@code IActivityTaskManager} 上加了一个
     * {@code startActivityForCoverLauncherAsUser(Intent, String launcherName, int userId)}，
     * 是给 Good Lock（MultiStar）的封面屏启动器用的。第三方反射拿到那个 Binder 代理直接就能调，
     * 系统自己把目标摆到封面屏上，也不查 display 权限 —— 所以「装好应用、还没配 Shizuku」
     * 的情况下外屏启动器照样点得开。「阿田自用」走的正是这条路。
     *
     * <p>⚠ 只对<b>封面屏</b>有意义：目标是别的屏时别走这条（见 {@link #isCover}）。
     *
     * @return true = 已经交给系统；false = 这台机器没这个接口，调用方接着走兜底
     */
    public static boolean coverLaunch(Context ctx, Intent intent) {
        if (ctx == null || intent == null) {
            return false;
        }
        try {
            Intent i = new Intent(intent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.putExtra(EXTRA_FROM_COVER_LAUNCHER, true);
            // 先把目标包注册进封面屏「允许启动」列表 —— 「阿田自用」每次启动前都做
            // 这一下（SimpleLauncherStarter.launchPackageOnCover 的第一句）。
            // 少了它，白名单之外的包会被系统拦成「打开手机以继续」。
            String pkg = i.getComponent() != null
                    ? i.getComponent().getPackageName() : i.getPackage();
            if (pkg != null) {
                CoverPolicy.ensure(ctx, pkg);
            }
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object svc = atm.getDeclaredMethod("getService").invoke(null);
            Method m = svc.getClass().getDeclaredMethod(
                    "startActivityForCoverLauncherAsUser", Intent.class, String.class, int.class);
            // ⚠ userId 不能用 {@code ctx.getUserId()} —— 它不在公开 API 里，编译不过。
            //   口径就是 UserHandle.getUserId(uid)：每 10 万一个用户（本应用跑在自己那个用户里）。
            m.invoke(svc, i, COVER_LAUNCHER_NAME, android.os.Process.myUid() / 100000);
            Log.i(TAG, "coverLaunch -> " + intent.getComponent());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "三星封面屏通道不可用: " + t);
            return false;
        }
    }

    /** 目标屏是不是封面屏（三星封面屏通道只对这一块屏有意义） */
    public static boolean isCover(Context ctx, int displayId) {
        return displayId > 0 && displayId == CoverDisplay.id(ctx);
    }

    /** 启动本应用自己的 Activity 到指定屏 */
    public static boolean launchOwn(Context ctx, int displayId, Class<?> cls) {
        return launchOwn(ctx, displayId, cls, null);
    }

    /**
     * 同上，但带一组参数过去。
     *
     * <p>为什么要这个重载：走 shell 时命令是一行字符串，参数得拼成
     * {@code am start … --es key value}，{@code Intent.putExtra} 那套在这儿用不上。目前只有
     * 字符串 / 整数两种类型要传，够用就行，不为它写反射那种重家伙。
     *
     * <p>⚠ 字符串值进 shell 前要加引号 —— 值里有空格或引号会被拆成两个参数。调用方传的都是
     * 我们自己定的短标识（比如 {@code open_sort=1}），不会有什么花样。
     */
    public static boolean launchOwn(Context ctx, int displayId, Class<?> cls, Bundle args) {
        ComponentName cn = new ComponentName(ctx, cls);
        if (ShellRunner.isReady() && displayId > 0) {
            StringBuilder sb = new StringBuilder(shellCommand(displayId, cn));
            appendArgs(sb, args);
            ShellRunner.run(sb.toString());
            Log.i(TAG, "launchOwn(shell) display=" + displayId + " -> " + cn.flattenToShortString());
            return true;
        }
        Intent i = new Intent(ctx, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (args != null) {
            i.putExtras(args);
        }
        if (isCover(ctx, displayId) && coverLaunch(ctx, i)) {
            Log.i(TAG, "launchOwn(cover) -> " + cls.getName());
            return true;
        }
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(Math.max(0, displayId));
            ctx.startActivity(i, opts.toBundle());
            Log.i(TAG, "launchOwn(direct) display=" + displayId + " -> " + cls.getName());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launchOwn failed", t);
            return false;
        }
    }

    /** 把 Bundle 里的参数拼成 am 的命令行开关 */
    private static void appendArgs(StringBuilder sb, Bundle args) {
        if (args == null) {
            return;
        }
        for (String k : args.keySet()) {
            Object v = args.get(k);
            if (v instanceof String) {
                sb.append(" --es ").append(k).append(' ').append(quote((String) v));
            } else if (v instanceof Integer) {
                sb.append(" --ei ").append(k).append(' ').append(v);
            } else if (v instanceof Boolean) {
                sb.append(" --ez ").append(k).append(' ').append(v);
            }
        }
    }

    /** 单引号包一层；值里万一有单引号就按 shell 的写法断开再拼 */
    private static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 启动任意已安装应用到指定屏（<b>跨应用也能成</b> —— 没 Shizuku 时走三星封面屏通道）。
     *
     * @param launchIntent {@code PackageManager.getLaunchIntentForPackage(pkg)} 的结果
     * @return false 表示三条路都走不通（通常是 launchIntent 没带组件名）
     */
    public static boolean launch(Context ctx, int displayId, Intent launchIntent) {
        if (launchIntent == null) {
            return false;
        }
        ComponentName cn = launchIntent.getComponent();
        if (cn == null) {
            return false;
        }
        if (ShellRunner.isReady() && displayId > 0) {
            ShellRunner.run(shellCommand(displayId, cn));
            Log.i(TAG, "launch(shell) display=" + displayId + " -> " + cn.flattenToShortString());
            return true;
        }
        if (isCover(ctx, displayId) && coverLaunch(ctx, launchIntent)) {
            Log.i(TAG, "launch(cover) -> " + cn.flattenToShortString());
            return true;
        }
        try {
            Intent i = new Intent(launchIntent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(Math.max(0, displayId));
            ctx.startActivity(i, opts.toBundle());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launch failed", t);
            return false;
        }
    }

    /** 让某块屏回到系统桌面（清掉我们投上去的内容） */
    public static boolean goHome(Context ctx, int displayId) {
        if (!ShellRunner.isReady()) {
            return false;
        }
        ShellRunner.run("am start --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.HOME");
        return true;
    }
}
