package com.wb.extrotator;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「有哪些应用可以启动」这件事的唯一出处。
 *
 * <p>唯一的消费者是副屏桌面（{@link SecondaryHomeActivity}）—— 它在封面屏 / 便携屏上列出
 * 应用，点一下就投到它自己所在的那块屏。
 *
 * <p>⚠ Android 11（API 30）起有「软件包可见性」限制，普通应用查不到别的应用。解决办法不是
 * 申请 {@code QUERY_ALL_PACKAGES}，而是在清单里声明一条 {@code <queries>} 的 MAIN/LAUNCHER
 * intent —— 这是官方给启动器的正规口子，本应用的清单里已经加了。
 *
 * <p><b>伪应用</b>：{@link Item#pseudoItems} 造出来的「看着像应用、其实是我们自己的页面」
 * （副屏触控板 / 投屏控制 / 状态展示柜 / 副屏播放器）。
 * 它们<b>不在 {@link #load} 里</b> ——
 * 只有副屏桌面会往列表头上插一份，所以外屏启动器（小组件 / 配置页 / 搜索页）里不会冒出来，
 * 系统那边更不会有（它们本来就不是真的应用）；包名是编的（{@link #PSEUDO_PREFIX} 开头），
 * 组件名指向本应用的 Activity，投屏那条路不用为它们改一行；用
 * {@code getLaunchIntentForPackage} 永远查不到（没这个包），所以调用方拿到 null 之后会自然走
 * {@link Item#toIntent()}，正好是对的。每个都可以单独关掉，开关在 Beta 实验室 →「副屏管理」，
 * 存 {@link ExtPrefs#pseudoOn}。
 */
public final class AppRepo {

    /** 伪应用的"包名"前缀。刻意用一个不会与真实包名相撞的写法。 */
    public static final String PSEUDO_PREFIX = "com.wb.extrotator.pseudo.";

    public static final String PSEUDO_TOUCHPAD = PSEUDO_PREFIX + "touchpad";
    /** 投屏控制—— 把内屏画面投到副屏上、在副屏上直接操控 */
    public static final String PSEUDO_CAST = PSEUDO_PREFIX + "cast";
    /** 状态展示柜 —— 副屏上的实时状态面板（CPU / 温度 / 内存 / 刷新率） */
    public static final String PSEUDO_STATUS = PSEUDO_PREFIX + "status";
    /** 副屏播放器 —— 外屏上的播放器：当前歌曲的封面 / 歌词 / 进度 / 上一曲下一曲 */
    public static final String PSEUDO_PLAYER = PSEUDO_PREFIX + "player";

    /** 一个可启动的应用（或一个伪应用） */
    public static final class Item {
        public final String label;
        public final String pkg;
        public final ComponentName component;
        public final Drawable icon;
        /**
         * 伪应用专用的"真实组件类"；普通应用为 null。
         *
         * <p>它只是给 {@link #isPseudo()} 当标记用 —— 投屏走的是
         * {@link #component}，两条路都能用，所以调用方不必为伪应用写分支。
         */
        public final Class<?> ownActivity;

        /**
         * 伪应用<b>默认</b>在副屏桌面上露不露脸（普通应用恒 false，用不到）。
         *
         * <p>只是"从没设置过"时的缺省值，真正的开关存在 {@link ExtPrefs#pseudoOn}。
         * 副屏触控板 false（用户要"打开副屏时别看到它们"）；投屏控制与状态展示柜 true
         * —— 这两个是拿来「用」的，一进去就该能看见。
         */
        public final boolean pseudoDefaultOn;

        Item(String label, String pkg, ComponentName component, Drawable icon) {
            this(label, pkg, component, icon, null, false);
        }

        Item(String label, String pkg, ComponentName component, Drawable icon,
             Class<?> ownActivity, boolean pseudoDefaultOn) {
            this.label = label;
            this.pkg = pkg;
            this.component = component;
            this.icon = icon;
            this.ownActivity = ownActivity;
            this.pseudoDefaultOn = pseudoDefaultOn;
        }

        /** 是真的应用还是我们自己的页面 */
        public boolean isPseudo() {
            return ownActivity != null;
        }

        /** 造一个可以用来启动它的 Intent（带 NEW_TASK，可直接丢给 startActivity） */
        public Intent toIntent() {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
    }

    /**
     * 伪应用清单，按要展示的先后顺序给出来（副屏桌面会原样插到列表最前面）。
     *
     * <p>图标是自带的矢量图，不是从 PackageManager 读的 —— 挑色和形状都按「落进启动器那个
     * 圆形裁切区」设计过（见 ic_pseudo_*.xml 的注释）；名字走 strings.xml，跟别的文案一样。
     *
     * <p>⚠ <b>开关也在这儿过滤</b>：关掉的不进列表，副屏桌面上就看不到它。过滤只做在这一处
     * —— 副屏桌面是唯一的消费者，别在别处再判一次，不然「关掉了还冒出来」这种毛病以后很难查
     * （外屏启动器 / 组件 / 搜索页本来就不走这里，见类注释）。
     */
    public static List<Item> pseudoItems(Context c) {
        List<Item> out = new ArrayList<>();
        addVisible(out, c, pseudo(c, PSEUDO_TOUCHPAD, R.string.ext_pad_title,
                R.drawable.ic_pseudo_touchpad, TouchpadActivity.class, false));
        addVisible(out, c, pseudo(c, PSEUDO_CAST, R.string.ext_cast_title,
                R.drawable.ic_pseudo_cast, CastActivity.class, true));
        addVisible(out, c, pseudo(c, PSEUDO_STATUS, R.string.ext_status_title,
                R.drawable.ic_pseudo_status, StatusActivity.class, true));
        addVisible(out, c, pseudo(c, PSEUDO_PLAYER, R.string.ext_player_title,
                R.drawable.ic_pseudo_player, PlayerActivity.class, true));
        return out;
    }

    /** 开关开着才收进列表（缺省值跟着 {@link Item#pseudoDefaultOn} 走） */
    private static void addVisible(List<Item> out, Context c, Item it) {
        if (ExtPrefs.pseudoOn(c, it.pkg, it.pseudoDefaultOn)) {
            out.add(it);
        }
    }

    private static Item pseudo(Context c, String pkg, int labelRes, int iconRes,
                               Class<?> cls, boolean defaultOn) {
        Drawable icon = null;
        try {
            icon = c.getDrawable(iconRes);
        } catch (Throwable ignored) {
        }
        return new Item(c.getString(labelRes), pkg,
                new ComponentName(c.getPackageName(), cls.getName()), icon, cls, defaultOn);
    }

    private AppRepo() {
    }

    /**
     * 列出所有「有启动图标」的应用，按中文习惯排序。
     *
     * <p>会读图标（IO），**必须在后台线程调用**。
     * 同一个包只保留第一个入口 —— 常见的是 Settings 之类有多个 launcher 别名。
     */
    public static List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        PackageManager pm;
        try {
            pm = ctx.getPackageManager();
        } catch (Throwable t) {
            return out;
        }
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            return out;
        }
        if (ris == null) {
            return out;
        }

        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : ris) {
            if (ri == null || ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || !seen.add(pkg)) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(ri.loadLabel(pm));
            } catch (Throwable t) {
                label = pkg;
            }
            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            out.add(new Item(label, pkg,
                    new ComponentName(pkg, ri.activityInfo.name), icon));
        }

        final Collator col = Collator.getInstance(Locale.CHINA);
        try {
            Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 按关键字过滤（名字或包名包含即命中，忽略大小写） */
    public static List<Item> filter(List<Item> src, String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(src);
        }
        String k = q.trim().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        for (Item it : src) {
            if (it.label.toLowerCase(Locale.ROOT).contains(k)
                    || it.pkg.toLowerCase(Locale.ROOT).contains(k)) {
                out.add(it);
            }
        }
        return out;
    }
}
