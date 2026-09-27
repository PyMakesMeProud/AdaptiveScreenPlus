package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「外屏启动器」自己的设置。
 *
 * <p>单独一个 SharedPreferences 文件（跟 {@link QuickPrefs} 一个路子）：这块功能将来要摘掉时
 * 删一个文件就干净，不用去主设置里翻哪些 key 是它的。
 *
 * <p>存五类东西：勾了哪些应用与什么顺序（分号分隔的包名串）、外观（列数 / 图标大小 / 形状 /
 * 行距 / 背景浓度 / 显不显示名字）、收藏夹、启动次数（给「按常用」排序用，点一次加一）。
 * 不存图标和名字 —— 每次从 {@link AppRepo} 现读，应用改名 / 换图标 / 卸载后就不会留下过期缓存。
 *
 * <p><b>顺序有两份，各管各的</b>：本类的 {@code launcher_apps} 是<b>本启动器</b>的顺序
 * （小组件与配置页都认它，即 {@link #SORT_MANUAL}），{@link HomePrefs#KEY_APPS} 是<b>副屏桌面</b>
 * 的顺序。两份互不干扰，只有用户手动执行配置页那个「把当前顺序写入副屏桌面」时才同步一次。
 * ⚠ 别在 {@link #setApps} 末尾挂自动同步 —— 那等于手动顺序一改就把副屏桌面覆盖掉，两份就分不出来了。
 *
 * <p>{@link #SORT_HOME} 是「直接读副屏桌面那份来排」，跟着副屏桌面走，不跟手动顺序走。
 *
 * <p>外观档位都写在这里当<b>单一出处</b>：配置页外观条、小组件、缩位图那段读的是同一批常量；
 * 各写一套的话加一档要改三处，漏一处就是「这边五档那边四档」。
 */
public final class LauncherPrefs {

    private static final String FILE = "extrot_launcher";

    private static final String KEY_APPS = "launcher_apps";
    private static final String KEY_SHOW_LABEL = "launcher_show_label";
    private static final String KEY_SORT = "launcher_sort";
    private static final String KEY_COLUMNS = "launcher_columns";
    private static final String KEY_ICON = "launcher_icon_size";
    private static final String KEY_GAP = "launcher_row_gap";
    private static final String KEY_SHAPE = "launcher_icon_shape";
    private static final String KEY_ALPHA = "launcher_bg_alpha";
    private static final String KEY_USAGE = "launcher_usage";
    private static final String KEY_FAV = "launcher_fav";
    private static final String KEY_FAV_ONLY = "launcher_fav_only";

    /** 包名里不会出现分号，选它当分隔符 */
    private static final String SEP = ";";

    // ------------------------------------------------------------ 排序方式

    /** 手动：按用户在配置页里排出来的顺序（可以拖） */
    public static final String SORT_MANUAL = "manual";
    /** 名称：按中文拼音升序 */
    public static final String SORT_NAME = "name";
    /** 常用：按启动次数降序，没点过的排在后面并保持原顺序 */
    public static final String SORT_USAGE = "usage";
    /** 副屏桌面顺序：直接跟着 {@link HomePrefs} 那份排（没设过就退回名称序） */
    public static final String SORT_HOME = "home";

    /**
     * 排序方式的全部候选 —— <b>单一出处</b>。
     *
     * <p>配置页的排序弹窗、小组件的 ⇅ 图标、工具条上那行"排序：xxx"全都读这一份，
     * 所以两边不可能出现"这边三种、那边四种"的不对等。
     */
    public static final String[] SORT_ALL = {SORT_MANUAL, SORT_NAME, SORT_USAGE, SORT_HOME};

    // ------------------------------------------------------------ 外观档位

    public static final int SIZE_SMALL = 0;
    public static final int SIZE_MID = 1;
    public static final int SIZE_LARGE = 2;

    public static final int GAP_TIGHT = 0;
    public static final int GAP_MID = 1;
    public static final int GAP_LOOSE = 2;

    /**
     * 列数候选。每个都对应一份布局文件（见 {@link CoverLauncherProvider#layoutFor}）——
     * {@code GridView.setNumColumns} 没有 {@code @RemotableViewMethod} 注解，小组件里<b>不能</b>
     * 运行时改列数，只能换布局。
     *
     * <p>不再往上加：外屏就那么宽，8 列时每格不到 45dp，图标和名字都得挤没了。
     */
    public static final int[] COLUMN_CHOICES = {4, 5, 6, 7};

    public static final int COLUMNS_MIN = 4;
    public static final int COLUMNS_MAX = 7;

    /** 原样：应用自己给什么形状就画什么 */
    public static final int SHAPE_APP = 0;
    /** 圆角方形 */
    public static final int SHAPE_ROUND = 1;
    /** 圆形（默认档，见 {@link #iconShape}） */
    public static final int SHAPE_CIRCLE = 2;

    public static final int[] SHAPE_ALL = {SHAPE_APP, SHAPE_ROUND, SHAPE_CIRCLE};

    /**
     * 圆角方形档的圆角半径 = 图标边长 × 这个比例。
     *
     * <p>24% 是照着三星启动器的观感调的。两处共用：
     * 小组件缩位图（{@link CoverLauncherService#toBitmap}）和配置页的格子。
     */
    public static final float SHAPE_RADIUS_RATIO = 0.24f;

    /**
     * 小组件底板的不透明度候选（%），越大越实。
     *
     * <p>写百分比而不是「淡 / 中 / 浓」：说「透明度高」到底指更透还是更实，问十个人能给两种答案，
     * 一个数字说出来没有歧义。
     *
     * <p>每档对应一份 shape drawable（见 {@link #bgDrawable}）。改档位要连 drawable
     * 一起改，两边对不上就是一块纯色或者一个空档。
     */
    public static final int[] ALPHA_CHOICES = {0, 10, 30, 50};

    /** 默认 30% —— 底板要让位给封面屏自己的背景，太实就把壁纸盖住了 */
    public static final int ALPHA_DEFAULT = 30;

    private LauncherPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 勾选列表

    /** 勾选的应用包名，有序 —— 顺序就是"手动排序"下的显示顺序 */
    public static List<String> apps(Context c) {
        String raw = sp(c).getString(KEY_APPS, "");
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String s : raw.split(SEP)) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static void setApps(Context c, List<String> pkgs) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.trim().isEmpty()) {
                    set.add(p.trim());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(p);
        }
        sp(c).edit().putString(KEY_APPS, sb.toString()).apply();

        /*
         * ⚠ 这里<b>不要</b>去动 {@link HomePrefs}（v3.2 定）。
         *
         * 副屏桌面那份顺序是独立的一份，只在用户手动执行
         * 「把当前顺序写入副屏桌面」时同步一次。写在这里就意味着"手动顺序一改，
         * 副屏桌面被顺带覆盖"，两边的顺序就再也分不出来了。
         */
    }

    public static boolean isSelected(Context c, String pkg) {
        return pkg != null && apps(c).contains(pkg);
    }

    /**
     * 勾上 / 取消，返回操作之后是否处于勾选态。
     *
     * <p>⚠ 勾上的时候是 <b>插到队首</b>，不是追加到末尾 —— 这是用户定的规矩：
     * 配置页的列表里"没勾的都在末尾"，一旦勾上，它就该从末尾跳到最前面去，
     * 而不是接着末尾排队（那样在几十个应用里根本找不到它）。
     */
    public static boolean toggle(Context c, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return false;
        }
        List<String> list = apps(c);
        boolean now;
        if (list.contains(pkg)) {
            list.remove(pkg);
            now = false;
        } else {
            list.add(0, pkg);
            now = true;
        }
        setApps(c, list);
        return now;
    }

    /** 配置过没有（一个都没勾 = 没配过，外屏上显示引导文案） */
    public static boolean isConfigured(Context c) {
        return !apps(c).isEmpty();
    }

    // ------------------------------------------------------------ 排序

    public static String sort(Context c) {
        String v = sp(c).getString(KEY_SORT, SORT_MANUAL);
        for (String m : SORT_ALL) {
            if (m.equals(v)) {
                return m;
            }
        }
        return SORT_MANUAL;
    }

    public static void setSort(Context c, String mode) {
        sp(c).edit().putString(KEY_SORT, mode == null ? SORT_MANUAL : mode).apply();
    }

    /** 排序方式的中文名。配置页的选择器、小组件的工具条、Toast 全用它。 */
    public static String sortLabel(Context c, String mode) {
        if (SORT_NAME.equals(mode)) {
            return c.getString(R.string.launcher_sort_name);
        }
        if (SORT_USAGE.equals(mode)) {
            return c.getString(R.string.launcher_sort_usage);
        }
        if (SORT_HOME.equals(mode)) {
            return c.getString(R.string.launcher_sort_home);
        }
        return c.getString(R.string.launcher_sort_manual);
    }

    public static String sortLabel(Context c) {
        return sortLabel(c, sort(c));
    }

    /**
     * 按当前排序方式排列一批应用（就地改顺序）。
     *
     * <ul>
     *   <li>{@link #SORT_MANUAL} —— 按 {@link #apps} 里的索引（数组里没有的排最后）；</li>
     *   <li>{@link #SORT_NAME} —— 中文拼音升序（同 {@link AppRepo#load}，用 Collator）；</li>
     *   <li>{@link #SORT_USAGE} —— 启动次数从多到少，没点过的算 0；靠 {@code Collections.sort}
     *       的稳定性保持原有相对顺序；</li>
     *   <li>{@link #SORT_HOME} —— 按 {@link HomePrefs#apps} 里的索引；副屏桌面没设过顺序时返回
     *       空表 ⇒ 全部同分 ⇒ 保持传进来的顺序（= 名称序）。</li>
     * </ul>
     */
    public static void sortItems(Context c, List<AppRepo.Item> items) {
        if (items == null || items.size() < 2) {
            return;
        }
        final String mode = sort(c);
        try {
            if (SORT_NAME.equals(mode)) {
                final Collator col = Collator.getInstance(Locale.CHINA);
                Collections.sort(items, (a, b) -> col.compare(a.label, b.label));
            } else if (SORT_USAGE.equals(mode)) {
                final Map<String, Integer> use = usage(c);
                Collections.sort(items, (a, b) -> Integer.compare(count(use, b.pkg), count(use, a.pkg)));
            } else if (SORT_HOME.equals(mode)) {
                final Map<String, Integer> idx = index(HomePrefs.apps(c));
                Collections.sort(items, (a, b) -> Integer.compare(at(idx, a.pkg), at(idx, b.pkg)));
            } else {
                final Map<String, Integer> idx = index(apps(c));
                Collections.sort(items, (a, b) -> Integer.compare(at(idx, a.pkg), at(idx, b.pkg)));
            }
        } catch (Throwable ignored) {
            // 排序失败不算致命：保持原顺序往下走
        }
    }

    private static Map<String, Integer> index(List<String> order) {
        Map<String, Integer> m = new HashMap<>();
        if (order != null) {
            for (int i = 0; i < order.size(); i++) {
                m.put(order.get(i), i);
            }
        }
        return m;
    }

    /** 不在表里的给个大数 —— 稳定排序会把它留在原地（通常是末尾） */
    private static int at(Map<String, Integer> m, String pkg) {
        Integer i = m.get(pkg);
        return i == null ? Integer.MAX_VALUE : i;
    }

    private static int count(Map<String, Integer> m, String pkg) {
        Integer n = m.get(pkg);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ 启动次数

    /** 点过几次，「按常用」排序的依据 */
    public static Map<String, Integer> usage(Context c) {
        Map<String, Integer> m = new HashMap<>();
        String raw = sp(c).getString(KEY_USAGE, "");
        if (TextUtils.isEmpty(raw)) {
            return m;
        }
        for (String part : raw.split(SEP)) {
            int i = part.indexOf('=');
            if (i <= 0) {
                continue;
            }
            try {
                m.put(part.substring(0, i), Integer.parseInt(part.substring(i + 1).trim()));
            } catch (Throwable ignored) {
            }
        }
        return m;
    }

    /**
     * 记一次启动。由 {@link LauncherTapReceiver} 在真正投屏成功之后调。
     * 这点数据不写盘也不会坏（只是排序不准），所以用 apply() 异步落盘。
     */
    public static void bump(Context c, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return;
        }
        Map<String, Integer> m = usage(c);
        m.put(pkg, count(m, pkg) + 1);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sp(c).edit().putString(KEY_USAGE, sb.toString()).apply();
    }

    // ------------------------------------------------------------ 收藏夹

    /**
     * 收藏夹里的包名，有序（收藏的先后）。
     *
     * <p>跟 {@link #apps} 是<b>两份</b>：收藏只是给应用打个记号，不动启动器那份
     * "勾了哪些、怎么排"。唯一的例外在 {@link #toggleFav} —— 收藏时顺手把它加进启动器
     * （不在启动器里的应用，筛"只看收藏"也筛不出来）。
     */
    public static List<String> favs(Context c) {
        String raw = sp(c).getString(KEY_FAV, "");
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String s : raw.split(SEP)) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static void setFavs(Context c, List<String> pkgs) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.trim().isEmpty()) {
                    set.add(p.trim());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(p);
        }
        sp(c).edit().putString(KEY_FAV, sb.toString()).apply();
    }

    public static boolean isFav(Context c, String pkg) {
        return pkg != null && favs(c).contains(pkg);
    }

    /**
     * 收藏 / 取消收藏，返回操作之后还在不在收藏夹里。
     *
     * <p>收藏时<b>顺带加进启动器</b>（插队首，跟 {@link #toggle} 一个规矩）；
     * 取消收藏<b>不动</b>启动器那份 —— 去掉记号不等于把它从启动器踢出去。
     */
    public static boolean toggleFav(Context c, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return false;
        }
        List<String> fav = favs(c);
        boolean now;
        if (fav.contains(pkg)) {
            fav.remove(pkg);
            now = false;
        } else {
            fav.add(pkg);
            now = true;
            if (!isSelected(c, pkg)) {
                List<String> sel = apps(c);
                sel.add(0, pkg);
                setApps(c, sel);
            }
        }
        setFavs(c, fav);
        return now;
    }

    /**
     * 「只看收藏」。配置页与小组件<b>共用这一个开关</b> —— 在哪儿拨的，另一边立刻是这个样子。
     * 默认关（= 全部应用）。
     */
    public static boolean favOnly(Context c) {
        return sp(c).getBoolean(KEY_FAV_ONLY, false);
    }

    public static void setFavOnly(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_FAV_ONLY, v).apply();
    }

    /** 卸掉一个应用之后清记录用：两份名单里都拿掉它 */
    public static void forget(Context c, String pkg) {
        if (pkg == null) {
            return;
        }
        List<String> sel = apps(c);
        if (sel.remove(pkg)) {
            setApps(c, sel);
        }
        List<String> fav = favs(c);
        if (fav.remove(pkg)) {
            setFavs(c, fav);
        }
    }

    // ------------------------------------------------------------ 外观

    /** 外屏上要不要显示应用名。图标小、格子密时关掉更清爽。 */
    public static boolean showLabel(Context c) {
        return sp(c).getBoolean(KEY_SHOW_LABEL, true);
    }

    public static void setShowLabel(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SHOW_LABEL, v).apply();
    }

    /** 一排放几个 */
    public static int columns(Context c) {
        int n = sp(c).getInt(KEY_COLUMNS, 4);
        return Math.max(COLUMNS_MIN, Math.min(COLUMNS_MAX, n));
    }

    public static void setColumns(Context c, int n) {
        sp(c).edit().putInt(KEY_COLUMNS, Math.max(COLUMNS_MIN, Math.min(COLUMNS_MAX, n))).apply();
    }

    public static int iconSize(Context c) {
        int v = sp(c).getInt(KEY_ICON, SIZE_MID);
        return (v < 0 || v > 2) ? SIZE_MID : v;
    }

    public static void setIconSize(Context c, int v) {
        sp(c).edit().putInt(KEY_ICON, Math.max(0, Math.min(2, v))).apply();
    }

    /** 图标相对基准尺寸的缩放比（小组件那边按它缩位图，配置页按它改 LayoutParams） */
    public static float iconScale(Context c) {
        switch (iconSize(c)) {
            case SIZE_SMALL:
                return 0.78f;
            case SIZE_LARGE:
                return 1.22f;
            default:
                return 1f;
        }
    }

    /**
     * 图标形状。默认<b>圆形</b>。
     *
     * <p>为什么默认就动手裁：外屏上这些图标本来就形状不齐（有的应用给方形，
     * 有的给圆角，有的给圆形），混在一屏里看着乱。圆形是唯一能真正"统一"的形状 ——
     * 它能把任何比它小的形状整个包住；反过来裁圆角时，本来画成圆的图标还是圆的。
     * 想要各归各的就切到「原样」。
     */
    public static int iconShape(Context c) {
        int v = sp(c).getInt(KEY_SHAPE, SHAPE_CIRCLE);
        return (v < 0 || v > SHAPE_CIRCLE) ? SHAPE_CIRCLE : v;
    }

    public static void setIconShape(Context c, int v) {
        sp(c).edit().putInt(KEY_SHAPE,
                Math.max(SHAPE_APP, Math.min(SHAPE_CIRCLE, v))).apply();
    }

    public static int rowGap(Context c) {
        int v = sp(c).getInt(KEY_GAP, GAP_MID);
        return (v < 0 || v > 2) ? GAP_MID : v;
    }

    public static void setRowGap(Context c, int v) {
        sp(c).edit().putInt(KEY_GAP, Math.max(0, Math.min(2, v))).apply();
    }

    /**
     * 行距换算成每一格上下各留多少 dp。
     *
     * <p>GridView 的 {@code verticalSpacing} 在 RemoteViews 里改不了（没有
     * {@code @RemotableViewMethod}），所以行距是靠给每格的根节点撑上下内边距实现的
     * —— 见 {@code CoverLauncherService.AppFactory.getViewAt}。
     *
     * <p>⚠ 这三个数是 <b>dp</b>，由 AppFactory 用<b>本进程</b>的 density 折成 px（不是外屏的），
     * 所以实际像素 ≈ 数值 × 3.0。
     */
    public static int rowPadDp(Context c) {
        switch (rowGap(c)) {
            case GAP_TIGHT:
                return 1;
            case GAP_LOOSE:
                return 9;
            default:
                return 5;
        }
    }

    /** 底板不透明度（%）。不在候选表里的值（比如手改过 pref）一律当默认档。 */
    public static int bgAlpha(Context c) {
        int v = sp(c).getInt(KEY_ALPHA, ALPHA_DEFAULT);
        for (int a : ALPHA_CHOICES) {
            if (a == v) {
                return a;
            }
        }
        return ALPHA_DEFAULT;
    }

    public static void setBgAlpha(Context c, int v) {
        int pick = ALPHA_DEFAULT;
        for (int a : ALPHA_CHOICES) {
            if (a == v) {
                pick = a;
            }
        }
        sp(c).edit().putInt(KEY_ALPHA, pick).apply();
    }

    /**
     * 当前档位对应的底板 drawable。
     *
     * <p>换资源而不是改 alpha 的原因：RemoteViews 只放行内置的那几个方法，改颜色
     * （setColorFilter）和反射调 setter 都不行；换资源走的 {@code setImageViewResource}
     * 是内置的，稳。见 {@link CoverLauncherProvider#build}。
     */
    public static int bgDrawable(Context c) {
        switch (bgAlpha(c)) {
            case 0:
                return R.drawable.launcher_widget_bg_0;
            case 10:
                return R.drawable.launcher_widget_bg_10;
            case 50:
                return R.drawable.launcher_widget_bg_50;
            default:
                return R.drawable.launcher_widget_bg_30;
        }
    }
}
