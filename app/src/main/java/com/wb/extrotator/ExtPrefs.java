package com.wb.extrotator;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 副屏页自己的设置。
 *
 * <p>现在在用的是副屏触控板（遥控板 v4.18 删除、键盘 v4.18 起不再上桌），都朝<b>内屏</b>发指令
 * （外接屏只是内屏的镜像，见 {@link ExtScreen}），所以设置共用一份。
 *
 * <p>跟 {@link LauncherPrefs} / {@link QuickPrefs} 一个路子：单独一个 SharedPreferences 文件，
 * 这块功能将来要摘掉时删一个文件就干净。
 *
 * <p>存三样东西：触控灵敏度（三档，见 {@link #SENS_ALL}）、键盘怎么把字送过去（直接输入 /
 * 剪贴板粘贴，见 {@link #KB_*}）、伪应用在副屏桌面上露不露脸（每个伪应用一个开关，见
 * {@link #pseudoOn}）。别再往回加「要不要镜像画面」「画面方向修正」—— 触控板改成不看画面之后
 * 就没用了。
 */
public final class ExtPrefs {

    private static final String FILE = "extrot_ext";

    private static final String KEY_SENS = "ext_sens";
    private static final String KEY_KB = "ext_kb_mode";
    /** 伪应用开关的键前缀，后面拼伪包名 —— 见 {@link #pseudoOn} */
    private static final String KEY_PSEUDO = "pseudo_on_";
    /** 投屏页的两个勾选项 */
    private static final String KEY_CAST_RESIZE = "cast_resize";
    private static final String KEY_CAST_ZOOM = "cast_zoom";
    /** 手势增强那一页的开关 + 去黑边按钮（v4.1 起；v4.2 去黑边搬去指令操作页） */
    private static final String KEY_EDGE_CUTOUT = "edge_cutout_global";
    /** 「隐藏三键导航」：我们拨过没有，以及接管前 policy_control 的原值 */
    private static final String KEY_NAV_IMM = "nav_immersive";
    private static final String KEY_NAV_IMM_BACKUP = "nav_immersive_backup";
    /** 「内屏手势」：在内屏边缘自己认返回 / 回主界面 / 后台 */
    private static final String KEY_INNER_GESTURE = "inner_gesture";
    /** 内屏手势的画法：0 = 原生，1 = 炫彩（见 {@link GestureAnim}） */
    private static final String KEY_GESTURE_ANIM_STYLE = "gesture_anim_style";
    private static final String KEY_COVER_RECENTS = "cover_recents_gesture";
    private static final String KEY_COVER_SIDEBAR = "cover_sidebar";
    /** 「外屏手势后台」捕获带高度档位：0=小 11dp / 1=中 16dp / 2=大 24dp */
    private static final String KEY_COVER_RECENTS_BAND = "cover_recents_band";
    /** 触发区宽度档位：0=窄 1/4 / 1=中 1/3 / 2=宽 1/2 / 3=满宽 */
    private static final String KEY_COVER_RECENTS_ZONE = "cover_recents_zone";
    /** 「外屏 0° 时手势条固定在左下角」勾没勾 */
    private static final String KEY_COVER_RECENTS_CORNER = "cover_recents_corner_left";
    /** 侧边栏贴哪一边：0 = 左，1 = 右 */
    private static final String KEY_SIDEBAR_SIDE = "cover_sidebar_side";
    /** 感应带落在这一边的哪一段：0 = 上，1 = 中，2 = 下 */
    private static final String KEY_SIDEBAR_ZONE = "cover_sidebar_zone";
    /**
     * 「面板内容」四项里哪几项<b>勾了</b>：四位 "1110"，下标同 {@link #SIDEBAR_ITEM_IDS}
     * （依次是 通知 / 最近任务 / 返回 / 回到桌面）。
     *
     * <p>它只管"在不在"，不管"排第几" —— 先后由 {@link #KEY_SIDEBAR_ORDER} 单独存。
     */
    private static final String KEY_SIDEBAR_ITEMS = "cover_sidebar_items";
    /**
     * 「面板内容」四项<b>排第几</b>：四个指令编号按面板从上到下写成 "3,2,1,4"。
     *
     * <p>跟 {@link #KEY_SIDEBAR_ITEMS} <b>合起来</b>才是面板的全貌：先按这个次序过一遍，
     * 勾过的那几个依次成为面板上的档位。拆成两份是为了"取消勾选"不丢位置 ——
     * 取消一项再勾回来，它还在原来那一行。
     *
     * <p>认不出来的值（长度不对、重复、混进别的编号）一律回落成 {@link #SIDEBAR_ITEM_IDS}
     * 那个出场次序。
     */
    private static final String KEY_SIDEBAR_ORDER = "cover_sidebar_order";
    /**
     * 侧边栏动画（「动画效果」那一栏）：0 = 动力圆环（缺省），1 = 调速齿轮，
     * 2 = 旧的抽屉面板（退路，界面不列），3 = 抽奖轮盘。
     *
     * <p>⚠ 值是<b>往后加</b>的：2 一直是旧的抽屉面板，新动画排它后面，别去重排。
     */
    private static final String KEY_SIDEBAR_STYLE = "cover_sidebar_style";


    /**
     * 灵敏度档位对应的倍数。
     *
     * <p>倍数的含义：手指在触控板上走过的距离 × 倍数 = 光标在外接屏上走过的距离。
     * 1.0 是"手指横穿整块触控板 = 光标横穿整块外接屏"，也就是最直观的 1:1；
     * 触控板只有外接屏的四分之一宽不到，所以想少动手指就得往上调。
     */
    public static final float[] SENS_ALL = {0.6f, 1.0f, 1.6f};
    private static final int SENS_DEFAULT = 1;

    /** 键盘：自己挑（纯 ASCII 走直接输入，含中文走剪贴板） */
    public static final int KB_AUTO = 0;
    /** 键盘：一律用 `input text` 直接敲 */
    public static final int KB_TEXT = 1;
    /** 键盘：一律"写进剪贴板 + 发一次粘贴键" */
    public static final int KB_CLIP = 2;

    private ExtPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 灵敏度档位下标（0..2） */
    public static int sensIndex(Context c) {
        int v = sp(c).getInt(KEY_SENS, SENS_DEFAULT);
        return (v < 0 || v >= SENS_ALL.length) ? SENS_DEFAULT : v;
    }

    public static void setSensIndex(Context c, int i) {
        sp(c).edit().putInt(KEY_SENS, Math.max(0, Math.min(SENS_ALL.length - 1, i))).apply();
    }

    /** 灵敏度倍数 */
    public static float sens(Context c) {
        return SENS_ALL[sensIndex(c)];
    }

    /** 键盘发送方式，见 {@link #KB_AUTO} 等 */
    public static int kbMode(Context c) {
        int v = sp(c).getInt(KEY_KB, KB_AUTO);
        return (v < KB_AUTO || v > KB_CLIP) ? KB_AUTO : v;
    }

    public static void setKbMode(Context c, int v) {
        sp(c).edit().putInt(KEY_KB, Math.max(KB_AUTO, Math.min(KB_CLIP, v))).apply();
    }

    // ------------------------------------------------------------------ 投屏页的勾选项

    /**
     * 「内屏分辨率重设」勾没勾。
     *
     * <p>缺省<b>不勾</b>：它会去写系统的 {@code wm size}，属于"动设置"的动作，
     * 得用户自己点头，不能在人家不知情的时候替他改。
     */
    public static boolean castResize(Context c) {
        return sp(c).getBoolean(KEY_CAST_RESIZE, false);
    }

    public static void setCastResize(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_CAST_RESIZE, on).apply();
    }

    /** 「允许放大」勾没勾（缺省不勾 —— 不勾时单指拖动就是纯拖动，行为最好猜） */
    public static boolean castZoom(Context c) {
        return sp(c).getBoolean(KEY_CAST_ZOOM, false);
    }

    public static void setCastZoom(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_CAST_ZOOM, on).apply();
    }

    // ------------------------------------------------------------------ 手势增强 / 去黑边（v4.2 调整）

    /**
     * 「全局给应用去掉黑边」开没开（缺省关 —— 它会一次性改掉机器上所有应用的窗口策略）。
     *
     * <p>这是"记住用户点过没"，不是"现在是不是生效着"：真正的状态在系统那边
     * （见 {@link CutoutPolicy}），界面上的状态行是当场回读出来的。
     */
    public static boolean cutoutGlobal(Context c) {
        return sp(c).getBoolean(KEY_EDGE_CUTOUT, false);
    }

    public static void setCutoutGlobal(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_EDGE_CUTOUT, on).apply();
    }

    /** 「外屏手势后台」开没开（缺省关 —— 它会在封面屏底部吃掉一条触摸） */
    public static boolean coverRecentsGesture(Context c) {
        return sp(c).getBoolean(KEY_COVER_RECENTS, false);
    }

    public static void setCoverRecentsGesture(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_COVER_RECENTS, on).apply();
    }

    /**
     * 手势条「捕获带」高度档位（0=小 11dp / 1=中 16dp / 2=大 24dp，缺省 1）。
     *
     * <p>⚠ 那一条是死区：条内的触摸被我们收走，Android 没法转发给下面的应用。
     * 所以把这个旋钮交给用户 —— <b>越小越不碍事，越大越好划</b>。
     * 改完立刻生效，不用重启（见 {@link #setCoverRecentsBand} 的调用方）。
     */
    public static int coverRecentsBand(Context c) {
        return sp(c).getInt(KEY_COVER_RECENTS_BAND, 1);
    }

    public static void setCoverRecentsBand(Context c, int idx) {
        sp(c).edit().putInt(KEY_COVER_RECENTS_BAND, idx).apply();
    }

    /**
     * 手势条触发区的宽度档位（0=窄 1/4 / 1=中 1/3 / 2=宽 1/2 / 3=满宽，缺省 1）。
     *
     * <p>触发区就是导航条正中那一段。做成可调是因为它同时决定两件事：
     * 好不好划、以及吃掉多少应用的地盘（不过现在不成手势的触摸会重放回去）。
     */
    public static int coverRecentsZone(Context c) {
        return sp(c).getInt(KEY_COVER_RECENTS_ZONE, 1);
    }

    public static void setCoverRecentsZone(Context c, int idx) {
        sp(c).edit().putInt(KEY_COVER_RECENTS_ZONE, idx).apply();
    }

    /**
     * 「外屏 0° 时把手势条固定在左下角」勾没勾（缺省不勾）。
     *
     * <p>为什么要这一条：外屏转到 0° 时，最底下那一条会被系统的遮罩压住，手势条留在正中会跟它
     * 叠在一起；左下角那一块本来就没有应用内容，放那儿两不耽误 —— 既挡不住东西，也不至于被遮罩
     * 盖到按不着。
     *
     * <p>只在<b>外屏真的是 0°</b> 时才按它挪（见 {@link CoverRecentsGesture} 里的
     * {@code wantCornerLeft}）；别的角度照旧放在最下沿正中。
     */
    public static boolean coverRecentsCornerLeft(Context c) {
        return sp(c).getBoolean(KEY_COVER_RECENTS_CORNER, false);
    }

    public static void setCoverRecentsCornerLeft(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_COVER_RECENTS_CORNER, on).apply();
    }

    /**
     * 「隐藏三键导航」上次拨到哪一边（缺省关）。
     *
     * <p>⚠ 这只是<b>让界面先有个样子</b>，不是真状态 —— 真状态在系统那边，
     * 界面显示以回读为准（见 {@link NavBarImmersive#read()}）。
     */
    public static boolean navImmOn(Context c) {
        return sp(c).getBoolean(KEY_NAV_IMM, false);
    }

    public static void setNavImmOn(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_NAV_IMM, on).apply();
    }

    /**
     * 我们接管 {@code policy_control} 之前它是什么，<b>null = 从没备份过</b>。
     *
     * <p>留着它是为了关掉开关时还回去：这一项是全局的，NavStar 和某些沉浸式应用都在用，
     * 一律写空会把用户原来的策略一起抹掉。
     */
    public static String navImmBackup(Context c) {
        return sp(c).getString(KEY_NAV_IMM_BACKUP, null);
    }

    public static void setNavImmBackup(Context c, String v) {
        sp(c).edit().putString(KEY_NAV_IMM_BACKUP, v).apply();
    }

    /**
     * 「内屏手势」开关。
     *
     * <p>跟外屏手势条同一个套路：真正把那一层贴上去的是无障碍服务
     * （见 {@link InnerGesture#sync}），这里只记开关状态。
     */
    public static boolean innerGesture(Context c) {
        return sp(c).getBoolean(KEY_INNER_GESTURE, false);
    }

    public static void setInnerGesture(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_INNER_GESTURE, on).apply();
    }

    /**
     * 内屏手势画成什么样：{@link GestureAnim#STYLE_NATIVE}（0）或
     * {@link GestureAnim#STYLE_GLOW}（1）。
     *
     * <p>只管"看起来像什么"，跟认不认手势无关 —— 所以改它不用重装捕获带：
     * {@link GestureAnim} 在每一手手势开始时现读一次，下一手就生效。
     */
    public static int gestureAnimStyle(Context c) {
        return sp(c).getInt(KEY_GESTURE_ANIM_STYLE, GestureAnim.STYLE_NATIVE);
    }

    public static void setGestureAnimStyle(Context c, int v) {
        sp(c).edit().putInt(KEY_GESTURE_ANIM_STYLE, v).apply();
    }

    // ------------------------------------------------------------------ 外屏侧边栏

    /** 侧边栏贴哪一边：0 = 左，1 = 右（缺省右 —— 右手拇指顺） */
    public static int sidebarSide(Context c) {
        return sp(c).getInt(KEY_SIDEBAR_SIDE, 1);
    }

    public static void setSidebarSide(Context c, int v) {
        sp(c).edit().putInt(KEY_SIDEBAR_SIDE, v).apply();
    }

    /**
     * 感应带落在这一边的哪一段：0 = 上，1 = 中，2 = 下（缺省上）。
     *
     * <p>屏幕那一侧被均分成三段。用户要的是"从右上方拖出来" ——
     * 那就是 side=1 + zone=0，对照组（Z Flip 外屏助手）做不出这个位置。
     */
    public static int sidebarZone(Context c) {
        return sp(c).getInt(KEY_SIDEBAR_ZONE, 0);
    }

    public static void setSidebarZone(Context c, int v) {
        sp(c).edit().putInt(KEY_SIDEBAR_ZONE, v).apply();
    }

    /** 缺省勾选：界面上前三项（通知 / 最近任务 / 返回） */
    private static final boolean[] SIDEBAR_ITEMS_ON_DEF = {true, true, true, false};

    /**
     * 四项各自的<b>勾选状态</b>（下标同 {@link #SIDEBAR_ITEM_IDS}）。
     *
     * <p>它只管"在不在"，不管"排第几" —— 先后由 {@link #sidebarOrder(Context)} 单独存。
     * 认不出的值一律回落缺省：存的是四位 "1110"，长度不对或混进别的字符都不认。
     */
    public static boolean[] sidebarItemsOn(Context c) {
        boolean[] def = SIDEBAR_ITEMS_ON_DEF.clone();
        String s = sp(c).getString(KEY_SIDEBAR_ITEMS, "");
        if (s == null || s.length() != def.length) {
            return def;
        }
        boolean[] out = new boolean[def.length];
        for (int i = 0; i < def.length; i++) {
            char ch = s.charAt(i);
            if (ch != '0' && ch != '1') {
                return def;
            }
            out[i] = ch == '1';
        }
        return out;
    }

    public static void setSidebarItemsOn(Context c, boolean[] on) {
        if (on == null || on.length != SIDEBAR_ITEM_IDS.length) {
            return;
        }
        StringBuilder b = new StringBuilder();
        for (boolean x : on) {
            b.append(x ? '1' : '0');
        }
        sp(c).edit().putString(KEY_SIDEBAR_ITEMS, b.toString()).apply();
    }

    /**
     * 「面板内容」四项<b>从上到下的先后</b>：返回四个指令编号。
     *
     * <p>出厂就是 {@link #SIDEBAR_ITEM_IDS} 那个次序；界面上的上下箭头改的就是它。
     *
     * <p>⚠ 一定要是那四个编号<b>各来一次</b> —— 长度不对、有重复、混进别的编号、少了一个，
     * 一律当脏值回落出厂次序（宁可把顺序丢了，也别让面板上冒出重复或空的档位）。
     */
    public static int[] sidebarOrder(Context c) {
        int[] def = SIDEBAR_ITEM_IDS.clone();
        String s = sp(c).getString(KEY_SIDEBAR_ORDER, "");
        if (s == null || s.isEmpty()) {
            return def;
        }
        String[] parts = s.split(",");
        if (parts.length != def.length) {
            return def;
        }
        int[] out = new int[def.length];
        boolean[] seen = new boolean[RING_HOME + 1];
        for (int i = 0; i < parts.length; i++) {
            int v;
            try {
                v = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return def;
            }
            if (v <= RING_NONE || v > RING_HOME || seen[v]) {
                return def;
            }
            seen[v] = true;
            out[i] = v;
        }
        for (int v : SIDEBAR_ITEM_IDS) {
            if (!seen[v]) {
                return def;
            }
        }
        return out;
    }

    public static void setSidebarOrder(Context c, int[] order) {
        if (order == null || order.length != SIDEBAR_ITEM_IDS.length) {
            return;
        }
        StringBuilder b = new StringBuilder();
        for (int v : order) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(v);
        }
        sp(c).edit().putString(KEY_SIDEBAR_ORDER, b.toString()).apply();
    }

    /** 某个指令号在 {@link #SIDEBAR_ITEM_IDS} 里排第几（勾选状态就是按这个下标存的） */
    public static int itemIndex(int id) {
        for (int i = 0; i < SIDEBAR_ITEM_IDS.length; i++) {
            if (SIDEBAR_ITEM_IDS[i] == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 「外屏侧边栏」开没开（<b>只做了开关，功能还没接</b>）。
     *
     * <p>用户点名要先看清楚「Z Flip 外屏助手」那套是怎么做的再动手，
     * 所以这一版只把开关留下来占位，翻开/关掉都不会有任何副作用。
     */
    public static boolean coverSidebar(Context c) {
        return sp(c).getBoolean(KEY_COVER_SIDEBAR, false);
    }

    public static void setCoverSidebar(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_COVER_SIDEBAR, on).apply();
    }

    // ------------------------------------------------------------------ 侧边栏圆环

    /**
     * 侧边栏动画（「动画效果」那一栏）：0 = 动力圆环（缺省），1 = 调速齿轮，
     * 2 = 旧的抽屉面板（界面不列），3 = 抽奖轮盘。
     *
     * <p>认不出来的值由 {@link CoverSidebar#animKind()} 回落到 0。
     */
    public static int sidebarStyle(Context c) {
        return sp(c).getInt(KEY_SIDEBAR_STYLE, 0);
    }

    public static void setSidebarStyle(Context c, int v) {
        sp(c).edit().putInt(KEY_SIDEBAR_STYLE, v).apply();
    }

    /** 圆环上能挂的指令。存进 prefs 的就是这几个编号 */
    public static final int RING_NONE = 0;
    public static final int RING_BACK = 1;
    public static final int RING_RECENTS = 2;
    public static final int RING_NOTIFY = 3;
    public static final int RING_HOME = 4;

    /**
     * 「面板内容」那四行<b>出厂时</b>的次序（通知 / 最近任务 / 返回 / 回到桌面），
     * 也是勾选状态那四位 "1110" 的下标次序。
     *
     * <p>⚠ 用户能用箭头调次序，所以<b>面板上的实际先后要看 {@link #sidebarOrder(Context)}</b>，
     * 不是这个数组。这个数组保证的只是：布局里那四行、勾选状态的下标、出厂次序，三者对得上。
     *
     * <p>⚠ 只能声明在 RING_* 那几个常量<b>之后</b> —— 静态字段初始化不许前向引用，
     * 放到前面 javac 直接报「非法前向引用」。
     */
    public static final int[] SIDEBAR_ITEM_IDS = {
            RING_NOTIFY, RING_RECENTS, RING_BACK, RING_HOME};

    /**
     * 侧滑栏面板上从上到下（圆环是内 → 外）各挂哪一档指令。
     *
     * <p>长度就是「面板内容」勾了几项（0 ~ 4）：勾几项面板上就留几档；<b>先后按
     * {@link #sidebarOrder(Context)} 排</b>（就是界面上那四行从上到下的次序，箭头可调）。
     * 返回空数组表示一项都没勾 —— 那种情况下几套动画都不会出来。
     *
     * <p>几套动画共用这一份：圆环把它当「内 / 中 / 外」，齿轮把它当「上 / 中 / 下」，
     * 抽奖轮盘把它均分到盘面上。
     */
    public static int[] sidebarSlots(Context c) {
        boolean[] on = sidebarItemsOn(c);
        int[] order = sidebarOrder(c);
        int n = 0;
        for (int id : order) {
            int at = itemIndex(id);
            if (at >= 0 && on[at]) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int id : order) {
            int at = itemIndex(id);
            if (at >= 0 && on[at]) {
                out[k++] = id;
            }
        }
        return out;
    }

    /** 「面板内容」勾了几项（面板上有几档） */
    public static int sidebarSlotCount(Context c) {
        return sidebarSlots(c).length;
    }

    /** 某一档指令叫什么（设置页以后要用） */
    public static String ringActionName(Context c, int id) {
        switch (id) {
            case RING_BACK:
                return "返回";
            case RING_RECENTS:
                return "最近任务";
            case RING_NOTIFY:
                return "通知";
            case RING_HOME:
                return "回到桌面";
            default:
                return "不挂指令";
        }
    }

    // ------------------------------------------------------------------ 伪应用开关

    /**
     * 伪应用在副屏桌面上要不要露脸。
     *
     * <p>键长这样：{@code pseudo_on_com.wb.extrotator.pseudo.remote} —— 把伪包名整个拼进去，
     * 将来加伪应用不用动这里（{@link AppRepo#pseudoItems} 添一行就行），也不会跟别的键撞。
     *
     * <p>缺省值由调用方给（{@link AppRepo.Item#pseudoDefaultOn}）：<b>三件套默认关</b>
     * （用户点明了「打开副屏的时候不要看到它们」），投屏控制默认开（那是要用的东西）。
     * 开关的界面在 Beta 实验室 →「副屏管理」。
     *
     * @param defaultOn 从没设置过时算开还是关
     */
    public static boolean pseudoOn(Context c, String pkg, boolean defaultOn) {
        return sp(c).getBoolean(KEY_PSEUDO + pkg, defaultOn);
    }

    public static void setPseudoOn(Context c, String pkg, boolean on) {
        sp(c).edit().putBoolean(KEY_PSEUDO + pkg, on).apply();
    }

}
