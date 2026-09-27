package com.wb.extrotator;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「外屏侧边栏」的通知来源 —— 一个 {@link NotificationListenerService}：系统把通知「抄送」一份
 * 给我们，我们在内存里留最近 {@link #MAX} 条，侧边栏拉出来时直接读这份快照。
 *
 * <p><b>为什么要单独一个服务</b>：读别人的通知是「通知使用权」，跟无障碍服务是两码事情，
 * 得单独授权 —— 界面上那个「授予通知使用权」按钮就是走 Shizuku 发 {@link #GRANT_CMD} 这条。
 *
 * <p>刻意<b>不落盘</b>：通知内容属于隐私，进程一死这份快照就没了，比「存到 prefs 里下次
 * 还能看」要干净；用户没接侧边栏时也不会有任何东西留在机器上。
 */
public class CoverNotifListener extends NotificationListenerService {

    private static final String TAG = "CoverNotif";

    /** 快照上限。外屏那个面板一屏也就看几条，多了没意义还费内存 */
    public static final int MAX = 12;

    /** 授权用的 shell 命令（界面上的按钮发它）。组件名 = 包名/类全名 */
    public static String grantCmd(Context c) {
        return "cmd notification allow_listener "
                + c.getPackageName() + "/" + CoverNotifListener.class.getName();
    }

    private static final List<Entry> SNAP = new ArrayList<>();
    /** 连接状态由系统回调维护，界面只读 */
    private static volatile boolean connected;

    /** 一条通知的精简副本（只留面板要显示与要用得上的那几个字段） */
    public static final class Entry {
        public final String key;
        public final String pkg;
        public final String title;
        public final String text;
        public final long when;
        public final PendingIntent contentIntent;
        /** 应用名（通知中心要按应用分栏、要显示图标） */
        public final String label;
        /** 应用图标；读不到就是 null，界面那边用首字母圆顶着 */
        public final Drawable icon;

        Entry(String key, String pkg, String title, String text, long when,
              PendingIntent contentIntent, String label, Drawable icon) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.when = when;
            this.contentIntent = contentIntent;
            this.label = label;
            this.icon = icon;
        }
    }

    // ------------------------------------------------------------------ 系统回调

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        Log.i(TAG, "通知使用权已连上");
        refreshAll();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        synchronized (SNAP) {
            SNAP.clear();
        }
        Log.w(TAG, "通知使用权断开");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // ⚠ 这是**系统叫的**回调：这里抛出去就等于进程 FATAL（无障碍服务也在同一个进程里），
        //    没有第二次机会 —— 所以整段包一层，出什么事都只记日志。
        try {
            if (sbn == null || !isShowable(sbn)) {
                return;
            }
            Entry e = toEntry(sbn);
            if (e == null) {
                return;
            }
            synchronized (SNAP) {
                removeKey(e.key);
                SNAP.add(0, e);
                while (SNAP.size() > MAX) {
                    SNAP.remove(SNAP.size() - 1);
                }
            }
            // 面板开着的话让它重建一次列表
            CoverSidebar.onNotificationsChanged();
        } catch (Throwable t) {
            Log.w(TAG, "收到通知时出错（已吞掉，不能让进程死）", t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            if (sbn == null) {
                return;
            }
            synchronized (SNAP) {
                removeKey(keyOf(sbn));
            }
            CoverSidebar.onNotificationsChanged();
        } catch (Throwable t) {
            Log.w(TAG, "通知消失时出错（已吞掉，不能让进程死）", t);
        }
    }

    /** 把系统当前挂着的通知整个重新读一遍（刚连上、或者面板每次拉开时调） */
    public static void refreshAll() {
        try {
            CoverNotifListener self = Holder.self;
            if (self == null) {
                return;
            }
            StatusBarNotification[] all = self.getActiveNotifications();
            List<Entry> fresh = new ArrayList<>();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    if (!isShowable(sbn)) {
                        continue;
                    }
                    Entry e = toEntry(sbn);
                    if (e != null) {
                        fresh.add(e);
                    }
                }
            }
            // 新的在前
            Collections.sort(fresh, (a, b) -> Long.compare(b.when, a.when));
            synchronized (SNAP) {
                SNAP.clear();
                SNAP.addAll(fresh.subList(0, Math.min(fresh.size(), MAX)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "重读通知失败", t);
        }
    }

    /** 面板要显示的快照副本 */
    public static List<Entry> snapshot() {
        synchronized (SNAP) {
            return new ArrayList<>(SNAP);
        }
    }

    /** 丢掉某一条（面板上那个 × ） */
    public static void dismiss(String key) {
        try {
            CoverNotifListener self = Holder.self;
            if (self != null && key != null) {
                self.cancelNotification(key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "清除通知失败", t);
        }
        synchronized (SNAP) {
            removeKey(key);
        }
    }

    /**
     * 使用权到手了没。
     *
     * <p>⚠ 别用 {@code NotificationManager.getEnabledListenerPackages(Context)} ——
     * 那是 <b>AndroidX</b> 的 {@code NotificationManagerCompat} 才有的，平台类上没有，
     * 直接编译不过。这里读系统设置里那串白名单：{@code enabled_notification_listeners}，
     * 里面是「包名/类名」用冒号连起来的列表。
     */
    public static boolean granted(Context c) {
        try {
            Context app = c.getApplicationContext();
            String flat = android.provider.Settings.Secure.getString(
                    app.getContentResolver(), "enabled_notification_listeners");
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            ComponentName me = new ComponentName(app, CoverNotifListener.class);
            return flat.contains(me.flattenToString())
                    || flat.contains(me.flattenToShortString());
        } catch (Throwable t) {
            Log.w(TAG, "查通知使用权失败", t);
        }
        return false;
    }

    public static boolean alive() {
        return connected;
    }

    // ------------------------------------------------------------------ 内部

    /** 实例的弱引用存放处（静态内部类，避免在服务里放 static 字段被 lint 骂） */
    private static final class Holder {
        static CoverNotifListener self;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Holder.self = this;
    }

    @Override
    public void onDestroy() {
        if (Holder.self == this) {
            Holder.self = null;
        }
        connected = false;
        super.onDestroy();
    }

    /**
     * 按 key 丢掉一条。
     *
     * <p>⚠⚠ 必须自己判下标：{@code indexOf} 找不到时返回 <b>-1</b>，而
     * {@code List.remove(int)} 遇到 -1 是<b>抛 {@code IndexOutOfBoundsException}</b>、
     * 不是「什么都不做」。只要来一条「不在快照里的通知消失」事件（常驻通知、分组摘要都被
     * {@link #isShowable} 挡在快照外，可它们照样回调），写成
     * {@code SNAP.remove(indexOf(...))} 就会当场 FATAL —— 实测崩溃：
     * {@code Index -1 out of bounds for length 0}。
     */
    private static void removeKey(String key) {
        int i = indexOf(key);
        if (i >= 0) {
            SNAP.remove(i);
        }
    }

    private static int indexOf(String key) {
        for (int i = 0; i < SNAP.size(); i++) {
            if (SNAP.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static String keyOf(StatusBarNotification sbn) {
        try {
            return sbn.getKey();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 值不值得显示：常驻的、系统自留的、以及本应用自己的都不要 */
    private static boolean isShowable(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) {
                return false;
            }
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                return false;
            }
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                return false;
            }
            String p = sbn.getPackageName();
            return !TextUtils.isEmpty(p) && !p.equals("com.wb.extrotator")
                    && !p.equals("android");
        } catch (Throwable t) {
            return false;
        }
    }

    private static Entry toEntry(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            Bundle x = n.extras;
            CharSequence t = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence c = x == null ? null : x.getCharSequence(Notification.EXTRA_TEXT);
            if (TextUtils.isEmpty(t) && TextUtils.isEmpty(c)) {
                return null;
            }
            String title = TextUtils.isEmpty(t) ? sbn.getPackageName() : t.toString();
            String text = TextUtils.isEmpty(c) ? "" : c.toString();
            String pkg = sbn.getPackageName();
            return new Entry(sbn.getKey(), pkg, title, text,
                    sbn.getPostTime(), n.contentIntent, appLabel(pkg), appIcon(pkg));
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 应用名与图标

    /** 名字与图标都按包名缓存一份：通知一来就查一次，别每次都读盘 */
    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, Drawable> ICONS = new HashMap<>();

    private static Context appCtx() {
        CoverNotifListener self = Holder.self;
        return self == null ? null : self.getApplicationContext();
    }

    /**
     * 应用名。读不到（包可见性拦着、或者已经卸载）就退成包名末段，
     * 界面那边也还有一层兜底 —— 这里绝不返回 null。
     */
    private static String appLabel(String pkg) {
        if (pkg == null) {
            return null;
        }
        synchronized (LABELS) {
            String hit = LABELS.get(pkg);
            if (hit != null) {
                return hit;
            }
        }
        String out = null;
        try {
            Context c = appCtx();
            PackageManager pm = c == null ? null : c.getPackageManager();
            if (pm != null) {
                out = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            }
        } catch (Throwable t) {
            out = null;
        }
        if (TextUtils.isEmpty(out)) {
            int i = pkg.lastIndexOf('.');
            out = i >= 0 && i + 1 < pkg.length() ? pkg.substring(i + 1) : pkg;
        }
        synchronized (LABELS) {
            LABELS.put(pkg, out);
        }
        return out;
    }

    /** 应用图标。拿不到就返回 null（界面画首字母圆）—— 缓存里 null 也算缓存过 */
    private static Drawable appIcon(String pkg) {
        if (pkg == null) {
            return null;
        }
        synchronized (ICONS) {
            if (ICONS.containsKey(pkg)) {
                return ICONS.get(pkg);
            }
        }
        Drawable out = null;
        try {
            Context c = appCtx();
            PackageManager pm = c == null ? null : c.getPackageManager();
            if (pm != null) {
                out = pm.getApplicationIcon(pkg);
            }
        } catch (Throwable t) {
            out = null;
        }
        synchronized (ICONS) {
            ICONS.put(pkg, out);
        }
        return out;
    }

    /** 给界面用的一句状态话 */
    public static String statusText(Context c) {
        if (!granted(c)) {
            return "未授予通知使用权 —— 面板里就看不到通知";
        }
        return alive() ? "通知使用权已就绪" : "已授权，但服务还没挂上（切一次开关或重启应用即可）";
    }
}
