package com.wb.extrotator;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 外屏通知面板顶上那排「快捷开关」的数据与执行（v4.22 起）。
 *
 * <p>就是系统下拉那排里的常用几项：WiFi / 移动数据 / 蓝牙 / 自动旋转 / 手电筒 / 勿扰 / 飞行模式，
 * 外加一条「外屏自动旋转」（{@link CoverAutoRot}，只管封面屏那块）。
 *
 * <p><b>这一排是用户自己排的</b>（v4.23 起）：默认给 {@link #ORDER}，面板上点「＋」可以
 * 增、删、上下调序，落盘在 {@link #list}/{@link #save} 里。
 *
 * <p>每一项只有三件事：叫什么、图标是哪张、现在是开是关、按一下切过去。
 *
 * <p><b>为什么执行方式各不相同</b>：本应用是普通应用，这几个开关分别归不同的系统服务管 ——
 * 自动旋转只是一个设置项（我们有 WRITE_SECURE_SETTINGS，自己就能写）；手电筒走 CameraManager
 * （相机权限）；剩下五个（WiFi / 数据 / 蓝牙 / 飞行模式 / 勿扰）都得借 Shizuku 发一条 shell，
 * 系统没给普通应用这种口子。
 *
 * <p>⚠ 参数里带 {@code Context} 的都不是纯读 —— {@link #toggle} 会<b>阻塞</b>
 * （shell 那条几百毫秒，航空模式那条还要回读一次），所以界面必须扔到后台线程去跑，
 * 跑完再回主线程刷状态。
 *
 * <p>⚠ 这条经验来自 v4.21 那次输入法：老教程里的命令在新系统上会被悄悄收掉。
 * 所以这里对每个开关都<b>执行完回读一次</b>，没变就是没成，界面把那一格闪红 ——
 * 而不是"按了没反应也不知道为什么"。
 */
public final class QuickSettings {

    private static final String TAG = "QuickSettings";

    public static final int WIFI = 0;
    public static final int DATA = 1;
    public static final int BLUETOOTH = 2;
    public static final int AUTOROTATE = 3;
    public static final int FLASH = 4;
    public static final int DND = 5;
    public static final int AIRPLANE = 6;
    /**
     * 外屏自动旋转：封面屏跟着手机横竖转。
     *
     * <p>跟 {@link #AUTOROTATE} 是<b>两件事</b>：那个是系统的自动旋转，只管默认屏（内屏）；
     * 这个是封面屏的，靠加速度计 + shell 旋转命令（见 {@link CoverAutoRot}）。
     */
    public static final int COVER_ROT = 7;

    /**
     * 下面三个是<b>动作型</b>：点一下执行一次，没有一个"现在是开是关"可言，
     * 所以 {@link #isOn} 对它们恒返回 false（界面画成灰底），另见 {@link #isAction}。
     */
    /** 切换展开模式（折叠 / 展开，见 {@link DualScreen#toggleFold}） */
    public static final int ACTION_FOLD = 8;
    /** 一键投屏（{@link CastService}） */
    public static final int ACTION_CAST = 9;
    /** 后台应用 —— 跟内屏底部上拉悬停那一下同一个去处 */
    public static final int ACTION_RECENTS = 10;

    /**
     * 默认那一排（用户没动过时按这个摆）。
     *
     * <p>⚠ 这里是<b>外屏</b>的通知面板，所以摆的是 {@link #COVER_ROT}（封面屏跟着手机横竖转），
     * 不是 {@link #AUTOROTATE}（系统那个只管默认屏 = 内屏）—— 后者已经从可选清单里摘掉了，
     * 用户原话：「外屏通知为什么要控制内屏自动旋转啊」。
     */
    public static final int[] ORDER = {
            WIFI, DATA, BLUETOOTH, COVER_ROT, FLASH, DND, AIRPLANE
    };

    /** 所有能摆到那一排上的条目 —— 面板上点「＋」时列的就是这些 */
    public static final int[] ALL_IDS = {
            WIFI, DATA, BLUETOOTH, COVER_ROT, FLASH, DND, AIRPLANE,
            ACTION_FOLD, ACTION_CAST, ACTION_RECENTS
    };

    private static final String SP = "cover_qs";
    private static final String K_LIST = "qs_list";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /**
     * 现在那一排上有哪些、什么次序。
     *
     * <p>⚠ 得区分「从来没存过」和「用户全删了」：前者给默认那一排，后者就该是空的 ——
     * 所以用 {@code contains} 判有没有这个键，而不是看读出来的串空不空。
     *
     * <p>认不出来的 id（以后删掉的开关）解析时直接跳过，不会在面板上摆一个空白格子。
     */
    public static List<Integer> list(Context c) {
        List<Integer> out = new ArrayList<>();
        String s = null;
        try {
            SharedPreferences p = sp(c);
            s = p.contains(K_LIST) ? p.getString(K_LIST, "") : null;
        } catch (Throwable ignored) {
        }
        if (s == null) {
            for (int id : ORDER) {
                out.add(id);
            }
            return out;
        }
        for (String one : s.split(",")) {
            try {
                int id = Integer.parseInt(one.trim());
                if (known(id) && !out.contains(id)) {
                    out.add(id);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static boolean has(Context c, int id) {
        return list(c).contains(id);
    }

    private static void save(Context c, List<Integer> v) {
        StringBuilder sb = new StringBuilder();
        for (int id : v) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        try {
            sp(c).edit().putString(K_LIST, sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 加到那一排的末尾；已经在上面就当成功（幂等） */
    public static void add(Context c, int id) {
        List<Integer> v = list(c);
        if (!v.contains(id)) {
            v.add(id);
            save(c, v);
        }
    }

    public static void remove(Context c, int id) {
        List<Integer> v = list(c);
        if (v.remove(Integer.valueOf(id))) {
            save(c, v);
        }
    }

    /** 上下挪一格；已经到顶/到底就不动 */
    public static void move(Context c, int id, int dir) {
        List<Integer> v = list(c);
        int i = v.indexOf(id);
        int j = i + (dir < 0 ? -1 : 1);
        if (i < 0 || j < 0 || j >= v.size()) {
            return;
        }
        v.set(i, v.get(j));
        v.set(j, id);
        save(c, v);
    }

    /** 恢复成默认那一排 */
    public static void reset(Context c) {
        save(c, new ArrayList<Integer>() {
            {
                for (int id : ORDER) {
                    add(id);
                }
            }
        });
    }

    private static boolean known(int id) {
        for (int k : ALL_IDS) {
            if (k == id) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 名称与图标

    public static String name(int id) {
        switch (id) {
            case WIFI:
                return "WiFi";
            case DATA:
                return "移动数据";
            case BLUETOOTH:
                return "蓝牙";
            case AUTOROTATE:
                return "自动旋转";
            case FLASH:
                return "手电筒";
            case DND:
                return "勿扰";
            case AIRPLANE:
                return "飞行模式";
            case COVER_ROT:
                return "外屏自动旋转";
            case ACTION_FOLD:
                return "展开模式";
            case ACTION_CAST:
                return "一键投屏";
            case ACTION_RECENTS:
                return "后台应用";
            default:
                return "";
        }
    }

    public static int icon(int id) {
        switch (id) {
            case WIFI:
                return R.drawable.ic_qs_wifi;
            case DATA:
                return R.drawable.ic_qs_data;
            case BLUETOOTH:
                return R.drawable.ic_qs_bt;
            case AUTOROTATE:
                return R.drawable.ic_qs_rotate;
            case FLASH:
                return R.drawable.ic_qs_flash;
            case DND:
                return R.drawable.ic_qs_dnd;
            case AIRPLANE:
                return R.drawable.ic_qs_airplane;
            case COVER_ROT:
                return R.drawable.ic_qs_screen_rotate;
            case ACTION_FOLD:
                return R.drawable.ic_qs_fold;
            case ACTION_CAST:
                return R.drawable.ic_qs_cast;
            case ACTION_RECENTS:
                return R.drawable.ic_qs_recents;
            default:
                return R.drawable.ic_qs_wifi;
        }
    }

    // ------------------------------------------------------------------ 读状态

    /** 现在是开着吗。读不到一律当"关"，不抛。 */
    public static boolean isOn(Context c, int id) {
        try {
            switch (id) {
                case AUTOROTATE:
                    return Settings.System.getInt(c.getContentResolver(),
                            S_ACCEL, 0) == 1;
                case WIFI:
                    return Settings.Global.getInt(c.getContentResolver(), S_WIFI_ON, 0) != 0;
                case DATA:
                    return Settings.Global.getInt(c.getContentResolver(), S_MOBILE_DATA, 0) != 0;
                case BLUETOOTH:
                    return Settings.Global.getInt(c.getContentResolver(), S_BT_ON, 0) != 0;
                case AIRPLANE:
                    return Settings.Global.getInt(c.getContentResolver(), S_AIRPLANE, 0) != 0;
                case FLASH:
                    // 顺手先把状态回调挂上 —— 挂在回调里的才是"现在到底开着没"，
                    // 不然用户用系统那排开的手电筒，我们这边会一直显示成关。
                    torchReady(c);
                    return torchOn;
                case DND:
                    return dndOn(c);
                case COVER_ROT:
                    return CoverAutoRot.on(c);
                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读状态失败 " + name(id), t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 切换

    /**
     * 切一下。
     *
     * @return null = 成了；否则是给用户看的一句话（界面把它显示在那一格上）
     */
    public static String toggle(Context c, int id) {
        try {
            switch (id) {
                case AUTOROTATE:
                    return toggleAccel(c);
                case WIFI:
                    return onOff(c, id, "svc wifi ");
                case DATA:
                    return onOff(c, id, "svc data ");
                case BLUETOOTH:
                    // 两条都试一遍：不同系统版本上留着的命令不一样
                    return onOff(c, id, "svc bluetooth ", "cmd bluetooth_manager ");
                case AIRPLANE:
                    return toggleAirplane(c);
                case FLASH:
                    return toggleTorch(c);
                case DND:
                    return toggleDnd(c);
                case COVER_ROT:
                    // 它自己会去关/开整个自动旋转（含还原角度、起停传感器）
                    return CoverAutoRot.toggle(c);
                case ACTION_FOLD:
                    return QuickActions.run(c, QuickActions.KEY_TOGGLE_FOLD);
                case ACTION_CAST:
                    return QuickActions.run(c, QuickActions.KEY_CAST);
                case ACTION_RECENTS:
                    return openRecents(c);
                default:
                    return "未知开关";
            }
        } catch (Throwable t) {
            Log.w(TAG, "切换失败 " + name(id), t);
            return "切不动";
        }
    }

    /**
     * 这一项是不是「点一下执行一次」的<b>动作</b>（不是开关）。
     *
     * <p>界面拿它把这一格画成中性色 —— 动作型没有"开关状态"好显示，
     * 跟着 {@link #isOn} 走会永远是灰的，用户会以为按了没反应。
     */
    public static boolean isAction(int id) {
        return id == ACTION_FOLD || id == ACTION_CAST || id == ACTION_RECENTS;
    }

    /**
     * 动作型：打开「后台应用」。
     *
     * <p>跟外屏侧边栏里那一档、内屏底部上拉悬停走的是同一条
     * （{@link CoverRecentsGesture#openRecentsNow}）—— 封面屏的最近任务是系统目标，
     * 应用身份发不出去，所以这条<b>要 Shizuku</b>（没有时它自己会退到全局动作）。
     */
    private static String openRecents(Context c) {
        int did = CoverDisplay.id(c);
        if (did <= 0) {
            return "找不到外屏";
        }
        CoverRecentsGesture.openRecentsNow(did);
        return null;
    }

    // ------------------------------------------------------------------ 各条实现

    private static final String S_ACCEL = "accelerometer_rotation";
    private static final String S_WIFI_ON = "wifi_on";
    private static final String S_MOBILE_DATA = "mobile_data";
    private static final String S_BT_ON = "bluetooth_on";
    private static final String S_AIRPLANE = "airplane_mode_on";

    /**
     * 自动旋转 —— 唯一一条不用 Shizuku 的：它只是个 Settings.System 项，
     * 而本应用装着 WRITE_SECURE_SETTINGS（「授予安全设置权限」那个按钮给的），能自己写。
     * 写不动（权限掉了）再退到 shell。
     */
    private static String toggleAccel(Context c) {
        final int v = isOn(c, AUTOROTATE) ? 0 : 1;
        try {
            Settings.System.putInt(c.getContentResolver(), S_ACCEL, v);
        } catch (Throwable t) {
            Log.w(TAG, "写 accelerometer_rotation 失败，改走 shell：" + t);
        }
        // 回读判成没成：写不下去（没权限）时 putInt 也是"不抛异常但不生效"的情况有过，
        // 所以不靠"没抛异常"当成功。
        if (isOn(c, AUTOROTATE) == (v == 1)) {
            return null;
        }
        String err = shell("settings put system " + S_ACCEL + " " + v);
        return err != null ? err : (isOn(c, AUTOROTATE) == (v == 1) ? null : "没切成");
    }

    /**
     * WiFi / 移动数据 / 蓝牙 三条长得一样：发一条 {@code enable|disable} 的命令 + 回读确认。
     *
     * <p>给多串前缀是为了留退路 —— 同一件事在不同 One UI 版本上留着的 shell 命令不一样
     * （蓝牙就有 {@code svc bluetooth} 与 {@code cmd bluetooth_manager} 两套），
     * 前一条没把状态改过来就接着试下一条。
     */
    private static String onOff(Context c, int id, String... prefixes) {
        final boolean cur = isOn(c, id);
        String last = "没切成";
        for (String base : prefixes) {
            String err = shell(base + (cur ? "disable" : "enable"));
            if (isOn(c, id) != cur) {
                return null;
            }
            last = err != null ? err : "没切成";
        }
        return last;
    }

    /**
     * 飞行模式。
     *
     * <p>优先用 {@code cmd connectivity airplane-mode}（Android 11 起有）；它不成再退到
     * 老办法 —— 直接写 {@code Settings.Global} 再发那条广播（老教程都这么写，
     * 新系统上广播这一半可能已经没人听了，所以它只是兜底）。
     */
    private static String toggleAirplane(Context c) {
        final boolean cur = isOn(c, AIRPLANE);
        final boolean want = !cur;
        String err = shell("cmd connectivity airplane-mode " + (want ? "enable" : "disable"));
        if (err == null && isOn(c, AIRPLANE) == want) {
            return null;
        }
        try {
            Settings.Global.putInt(c.getContentResolver(), S_AIRPLANE, want ? 1 : 0);
        } catch (Throwable t) {
            Log.w(TAG, "写 airplane_mode_on 失败：" + t);
        }
        shell("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + want);
        if (isOn(c, AIRPLANE) == want) {
            return null;
        }
        return err != null ? err : "没切成";
    }

    /** 勿扰：读不需要权限，切需要「通知策略」权限 —— 没给就用 shell 现补一次 */
    private static boolean dndOn(Context c) {
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return false;
        }
        return nm.getCurrentInterruptionFilter()
                != NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    private static String toggleDnd(Context c) {
        NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return "没有通知服务";
        }
        final boolean want = !dndOn(c);
        final int f = want ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL;
        try {
            nm.setInterruptionFilter(f);
            return null;
        } catch (Throwable t) {
            Log.i(TAG, "勿扰没权限，用 shell 补一次：" + t);
        }
        String err = shell("cmd notification allow_dnd " + c.getPackageName());
        if (err != null) {
            return err;
        }
        try {
            nm.setInterruptionFilter(f);
            return null;
        } catch (Throwable t) {
            return "勿扰没权限";
        }
    }

    // ----- 手电筒

    private static CameraManager cam;
    private static boolean torchHooked;
    private static volatile boolean torchOn;
    private static String torchId;

    /** 挂一次手电筒状态回调（挂了之后系统改了状态这边也知道），幂等 */
    private static boolean torchReady(Context c) {
        if (cam == null) {
            try {
                cam = (CameraManager)
                        c.getApplicationContext().getSystemService(Context.CAMERA_SERVICE);
            } catch (Throwable t) {
                cam = null;
            }
        }
        if (cam == null) {
            return false;
        }
        if (!torchHooked) {
            torchHooked = true;
            try {
                cam.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        torchId = cameraId;
                        torchOn = enabled;
                    }

                    @Override
                    public void onTorchModeUnavailable(String cameraId) {
                        torchOn = false;
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (Throwable t) {
                Log.w(TAG, "挂手电筒回调失败：" + t);
            }
        }
        return true;
    }

    /** 找出带闪光灯的那个后摄 id */
    private static String findTorch(Context c) {
        try {
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics ch = cam.getCameraCharacteristics(id);
                Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer face = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(fl) && face != null
                        && face == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "找闪光灯失败：" + t);
        }
        return null;
    }

    private static String toggleTorch(Context c) {
        if (!torchReady(c)) {
            return "没有相机服务";
        }
        String id = torchId != null ? torchId : findTorch(c);
        if (id == null) {
            return "没有闪光灯";
        }
        final boolean want = !torchOn;
        try {
            cam.setTorchMode(id, want);
            return null;
        } catch (Throwable t) {
            Log.i(TAG, "开手电筒被拒（多半是缺相机权限），用 shell 补一次：" + t);
        }
        // 相机权限是运行期权限，系统对话框会弹在**内屏**上 —— 那块屏用户看不了，
        // 所以不弹框，直接用 Shizuku 补（`pm grant` 属于 development 级，shell 干得了）。
        String err = shell("pm grant " + c.getPackageName() + " android.permission.CAMERA");
        if (err != null) {
            return err;
        }
        try {
            cam.setTorchMode(id, want);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "补了权限还是打不开手电筒", t);
            return "手电筒打不开";
        }
    }

    // ------------------------------------------------------------------ shell

    /**
     * 发一条 shell，把"没跑成"翻译成一句人话。
     *
     * <p>{@link ShellRunner#run} 把 stdout 与 stderr 合在一起返回（stderr 每行前面带 {@code !}），
     * 命令不存在 / 被 SELinux 拒时看到的是 {@code inaccessible or not found} 之类，
     * 这里统一认成失败 —— 不然界面会显示"切成功"而实际什么都没发生。
     */
    private static String shell(String cmd) {
        if (!ShellRunner.isReady()) {
            return "要 Shizuku";
        }
        String out = ShellRunner.run(cmd, 10);
        if (out == null || out.isEmpty()) {
            return null;
        }
        if (out.startsWith("ERR:")) {
            return "命令没跑成";
        }
        String low = out.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("exception") || low.contains("denied")
                || low.contains("inaccessible") || low.contains("not found")
                || low.contains("unknown command") || low.contains("usage:")) {
            Log.w(TAG, cmd + " => " + out);
            return "系统拒了";
        }
        Log.d(TAG, cmd + " => " + out);
        return null;
    }
}
