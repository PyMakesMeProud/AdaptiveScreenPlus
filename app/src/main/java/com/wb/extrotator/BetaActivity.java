package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beta 功能页。
 *
 * <p>从主界面右上角的「Beta 实验室」进入，是一个跟主界面完全分开的界面：整块黑底，
 * 内部分「外屏管理 / 指令操作 / 手势增强」三页。
 *
 * <p>为什么不做成主界面的第五个选项卡：这一页是黑底，颜色体系和主界面那套浅色完全不同，
 * 塞进同一个布局里每个控件都得写两套颜色；而且主界面那排选项卡已经排了四个，
 * 再塞第五个会把这四个压窄一圈。
 *
 * <p>各页的实现分别在 {@link CoverDisplay}（外屏设置）、{@link QuickActions} 与
 * {@link QuickPrefs}（侧键操作）、{@link CustomCmds}（自定义指令）。
 *
 * <p><b>线程约定</b>：这一页里所有会走 binder / sleep 的操作统一丢进 {@link #pool}（单线程）执行，
 * 结果再回主线程刷界面。用单线程而不是线程池，是因为这些命令本身有先后依赖（先设 size 再读状态），
 * 并发跑会互相盖掉。
 */
public class BetaActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    // ---- 外屏设置页的控件
    private EditText etW, etH, etDpi;
    private TextView tvCoverState;
    private LinearLayout listPresets;
    /** 「外屏启动器」区块的状态行（显示已勾选几个应用） */
    private TextView tvLauncherState;
    // ---- 指令操作页的控件
    private TextView tvAssistState, tvVolumeState;
    private LinearLayout listCustomCmds;
    /** 「侧键操作」里当前显示哪一个按键的设置（{@link QuickPrefs#SIDE_ASSIST} 等三选一） */
    private String keyTarget = QuickPrefs.SIDE_ASSIST;
    /** 「去掉应用黑边」的状态行（v4.2 搬去「指令操作」页，v4.17 又搬到「应用管理」页） */
    private TextView tvEdgeState;
    /** 「应用管理」页的状态行：当前名单几个应用 */
    private TextView tvRepairBrief;
    /** 回读时我们自己拨开关的挡板，见 {@link #setNavImmSwitch} */
    private boolean navImmSyncing;
    /** 「外屏自动旋转」那个开关的挡板：下发没成要把开关拨回去，那一下不是用户操作 */
    private boolean coverRotSyncing;

    /** 当前显示的预设列表（与文件里的那份同步） */
    private List<CoverPresets.Preset> presets;
    /** 封面屏面板原生值，用来生成默认预设、也是「恢复默认」的依据 */
    private int physW, physH, physDpi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beta);

        View back = findViewById(R.id.btnBetaBack);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        // 四个面板与四个选项卡 id 一对一，不做下标换算 —— 只有四页，不值得再引一层索引。
        RadioGroup tabs = findViewById(R.id.rgBetaTabs);
        if (tabs != null) {
            tabs.setOnCheckedChangeListener((group, checkedId) -> {
                showPanel(R.id.panelBetaExt, checkedId == R.id.tabBetaExt);
                showPanel(R.id.panelBetaCmd, checkedId == R.id.tabBetaCmd);
                showPanel(R.id.panelBetaEdge, checkedId == R.id.tabBetaEdge);
                showPanel(R.id.panelBetaApp, checkedId == R.id.tabBetaApp);
            });
            tabs.check(R.id.tabBetaExt);
        }

        bindInfo();

        bindCoverPanel();
        bindQuickPanel();
        bindEdgePanel();
        bindRepairPanel();
        bindInnerGesture();
        bindNavImmPanel();

        refreshCoverState();
        refreshQuickState();
        refreshLauncherState();
        refreshEdgeState();
        maybeShowIntro();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页回来时，无障碍的开启状态可能变了
        refreshQuickState();
        // 可能刚在外屏配置页里勾完应用回来
        refreshLauncherState();
        // 名单可能被「修复界面」那页（或阿田自用、MultiStar 本体）改过了
        refreshRepairBrief();
        // 系统里那一项可能被 NavStar 改过，回来重新对一次
        refreshNavImmState();
        // 这个开关在别处（主界面快捷操作 / 老虎机）也能拨，回来对一次
        refreshCoverAutoRotSwitch();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================================================================== 通用小工具

    private void showPanel(int viewId, boolean visible) {
        View v = findViewById(viewId);
        if (v != null) {
            v.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /**
     * 与主界面同一套「ⓘ」写法（见 MainActivity.bindInfo）。
     * 详情正文都放在 strings.xml 的 info_* 里，写法规矩也照旧：只写功能 / 用法 / 注意事项。
     */
    private void bindInfo() {
        bindInfo(R.id.iBetaTop, "Beta 实验室", R.string.info_beta_intro);
        bindInfo(R.id.iBetaExt, "外屏管理", R.string.info_beta_ext);
        bindInfo(R.id.iBetaCoverRes, "分辨率与 DPI", R.string.info_beta_cover_res);
        bindInfo(R.id.iBetaPreset, "分辨率预设", R.string.info_beta_preset);
        bindInfo(R.id.iBetaRotate, "外屏旋转", R.string.info_beta_rotate);
        bindInfo(R.id.iBetaCoverAutoRot, "外屏自动旋转", R.string.info_cover_autorot);
        bindInfo(R.id.iBetaLauncher, "外屏启动器", R.string.info_launcher);
        bindInfo(R.id.iBetaCmd, "指令操作", R.string.info_beta_cmd);
        bindInfo(R.id.iBetaKey, "侧键操作", R.string.info_beta_key);
        bindInfo(R.id.iBetaCustom, "自定义指令", R.string.info_beta_custom);
        bindInfo(R.id.iBetaExternal, "外部调用", R.string.info_beta_external);
        bindInfo(R.id.iBetaEdge, "去掉应用黑边", R.string.info_edge_cutout);
        bindInfo(R.id.iBetaCoverRecents, "手势条", R.string.info_cover_recents);
        bindInfo(R.id.iBetaSidebar, "外屏侧边栏", R.string.info_cover_sidebar);
        bindInfo(R.id.iBetaInner, "内屏手势", R.string.info_inner_gesture);
        bindInfo(R.id.iBetaNavImm, "隐藏三键导航", R.string.info_nav_immersive);
        bindInfo(R.id.iBetaApp, "应用管理", R.string.info_repair_app);
    }

    private void bindInfo(int iconId, String title, int detailRes) {
        View icon = findViewById(iconId);
        if (icon == null) {
            return;
        }
        icon.setOnClickListener(v -> {
            if (isFinishing()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(detailRes)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    /** 统一处理「Shizuku 没就绪」：返回 true 表示已经提示过了，调用方直接 return */
    private boolean needShizuku() {
        if (CoverDisplay.id(this) <= 0) {
            toast("这台机器上没有封面屏");
            return true;
        }
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            return true;
        }
        return false;
    }

    // ================================================================== 手势增强

    /**
     * 「手势增强」页的两个开关。
     *
     * <p>⚠ 先 setChecked 再挂监听，理由见 {@link #bindPseudoSwitch}。
     *
     * <p><b>手势条</b> —— 只是在封面屏上补一层，但要用<b>无障碍服务的上下文</b>才加得上
     * （{@code TYPE_ACCESSIBILITY_OVERLAY} 是无障碍服务专属的窗口类型），所以这里交给
     * {@link VolumeKeyService} 去转一道；<b>侧边栏</b> —— 只有开关。
     */
    // ================================================================== 应用管理

    /**
     * 「应用管理」这一页现在只有一件事：进「修复界面」那张名单。
     *
     * <p>状态行上的数字是<b>回读</b>出来的，不是我们记着的那份 —— 读要过 Shizuku，
     * 所以丢后台线程，回来再贴上去。
     */
    private void bindRepairPanel() {
        tvRepairBrief = findViewById(R.id.tvRepairBrief);

        View open = findViewById(R.id.btnOpenRepair);
        if (open != null) {
            open.setOnClickListener(v -> startActivity(new Intent(this, RepairActivity.class)));
        }

    }

    private void refreshRepairBrief() {
        final TextView tv = tvRepairBrief;
        if (tv == null) {
            return;
        }
        tv.setText("读取中…");
        pool.execute(() -> {
            CoverRepair.Snapshot s = CoverRepair.read();
            final String text;
            if (s == null || !s.valid()) {
                text = "读不回名单 —— Shizuku 没就绪？";
            } else {
                text = "当前名单 " + CoverRepair.packages(s).size() + " 个应用"
                        + (CoverRepair.hasSnapshot(this) ? " · 已存改动前的快照" : "");
            }
            ui.post(() -> tv.setText(text));
        });
    }

    private void bindEdgePanel() {
        tvEdgeState = findViewById(R.id.tvEdgeState);
        View apply = findViewById(R.id.btnCutoutApply);
        if (apply != null) {
            apply.setOnClickListener(v -> doApplyCutout());
        }

        Switch recents = findViewById(R.id.swCoverRecents);
        if (recents != null) {
            recents.setChecked(ExtPrefs.coverRecentsGesture(this));
            recents.setOnCheckedChangeListener((b, on) -> {
                ExtPrefs.setCoverRecentsGesture(this, on);
                if (on && !QuickPrefs.isAccessibilityEnabled(this)) {
                    toast("手势条要无障碍服务在跑，先去「指令操作」页把它打开");
                }
                VolumeKeyService.syncCoverRecents();
                toast(on ? "外屏底部上滑停顿 → 打开后台" : "已关掉手势条");
                refreshEdgeState();
            });
        }
        // 捕获带三档。⚠ 先 setChecked 再挂 listener —— 反了就成"初始化那一发把用户档位覆盖掉"。
        RadioGroup band = findViewById(R.id.rgRecentsBand);
        if (band != null) {
            int cur = ExtPrefs.coverRecentsBand(this);
            band.check(cur == 0 ? R.id.rbBandSmall : cur == 2 ? R.id.rbBandBig : R.id.rbBandMid);
            band.setOnCheckedChangeListener((g, id) -> {
                int pick = id == R.id.rbBandSmall ? 0 : id == R.id.rbBandBig ? 2 : 1;
                if (pick == ExtPrefs.coverRecentsBand(this)) {
                    return;
                }
                ExtPrefs.setCoverRecentsBand(this, pick);
                VolumeKeyService.refreshCoverRecents();
                toast("捕获带改成「" + (pick == 0 ? "小" : pick == 2 ? "大" : "中")
                        + "」—— 手势条已按新高度重装");
            });
        }

        // 触发区宽度四档。同样先 setChecked 再挂 listener。
        RadioGroup zone = findViewById(R.id.rgRecentsZone);
        if (zone != null) {
            int z = ExtPrefs.coverRecentsZone(this);
            zone.check(z == 0 ? R.id.rbZoneQuarter : z == 2 ? R.id.rbZoneHalf
                    : z == 3 ? R.id.rbZoneFull : R.id.rbZoneThird);
            zone.setOnCheckedChangeListener((g, id) -> {
                int pick = id == R.id.rbZoneQuarter ? 0
                        : id == R.id.rbZoneHalf ? 2
                        : id == R.id.rbZoneFull ? 3 : 1;
                if (pick == ExtPrefs.coverRecentsZone(this)) {
                    return;
                }
                ExtPrefs.setCoverRecentsZone(this, pick);
                VolumeKeyService.refreshCoverRecents();
                toast("触发区改成「" + zoneWidthName(pick) + "」—— 手势条已按新宽度重装");
            });
        }

        // 「外屏 0° 时固定在左下角」。0° 下外屏最底下那一条被系统遮罩压着，
        // 手势条留在正中会跟它叠在一起；左下角那一块没有应用内容，放那儿两不耽误。
        // ⚠ 先 setChecked 再挂 listener —— 反了就等于"初始化那一发把用户的选择覆盖掉"。
        CheckBox corner = findViewById(R.id.cbRecentsCorner);
        if (corner != null) {
            corner.setChecked(ExtPrefs.coverRecentsCornerLeft(this));
            corner.setOnCheckedChangeListener((b, on) -> {
                ExtPrefs.setCoverRecentsCornerLeft(this, on);
                VolumeKeyService.refreshCoverRecents();
                toast(on ? "外屏 0° 时手势条挪到左下角" : "手势条回到正中");
            });
        }


        // 系统「导航条」设置的直达入口。手势条能不能安心接管"上滑"，
        // 取决于系统导航模式 —— 全面屏手势会在同一串触摸上再判一次。
        View navMode = findViewById(R.id.btnNavMode);
        if (navMode != null) {
            navMode.setOnClickListener(v -> doOpenNavMode());
        }

        Switch sidebar = findViewById(R.id.swCoverSidebar);
        if (sidebar != null) {
            sidebar.setChecked(ExtPrefs.coverSidebar(this));
            sidebar.setOnCheckedChangeListener((b, on) -> {
                ExtPrefs.setCoverSidebar(this, on);
                if (on && !QuickPrefs.isAccessibilityEnabled(this)) {
                    toast("侧边栏要无障碍服务在跑，先去「指令操作」页把它打开");
                }
                VolumeKeyService.syncSidebar();
                toast(on ? "外屏侧边栏：已开（从选定的那一段往里拉）" : "外屏侧边栏：已关");
                refreshEdgeState();
            });
        }
        bindSidebarPanel();

        View refresh = findViewById(R.id.btnEdgeRefresh);
        if (refresh != null) {
            refresh.setOnClickListener(v -> refreshEdgeState());
        }
    }

    // ---------------------------------------------------------------- 隐藏三键导航

    /**
     * 「隐藏三键导航」的开关。
     *
     * <p>⚠ 先 setChecked 再挂监听 —— 反了就等于"初始化那一发把用户的开关拨回去"（本页通例）。
     *
     * <p>开关的位置以<b>系统里回读到的策略</b>为准；这里先按本地记的那份显示一下，
     * 进页面后 {@link #refreshNavImmState()} 会拿真值纠正。
     */
    private void bindNavImmPanel() {
        Switch sw = findViewById(R.id.swNavImm);
        if (sw != null) {
            sw.setChecked(ExtPrefs.navImmOn(this));
            sw.setOnCheckedChangeListener((b, on) -> {
                // 回读时是我们自己把开关拨回原位的，那一下不是用户操作
                if (navImmSyncing) {
                    return;
                }
                doNavImm(on);
            });
        }
    }

    /**
     * 「内屏手势」的开关。
     *
     * <p>跟「隐藏三键导航」不同，这一条<b>不写系统设置</b>：那三条热区由无障碍服务来贴，
     * 所以这里只要把开关记下来、再让服务对齐一次就行。
     *
     * <p>装不上（无障碍没在跑）时当场说清 —— 不然用户拨了开关、划半天没反应。
     */
    private void bindInnerGesture() {
        Switch sw = findViewById(R.id.swInnerGesture);
        if (sw == null) {
            return;
        }
        sw.setChecked(ExtPrefs.innerGesture(this));
        sw.setOnCheckedChangeListener((b, on) -> {
            ExtPrefs.setInnerGesture(this, on);
            VolumeKeyService.syncInnerGesture();
            if (on && !InnerGesture.installed()) {
                toast("没装上，先确认无障碍服务在跑");
            } else {
                toast(on ? "内屏手势已开" : "内屏手势已关");
            }
        });

        View animNative = findViewById(R.id.btnAnimNative);
        View animGlow = findViewById(R.id.btnAnimGlow);
        if (animNative != null && animGlow != null) {
            animNative.setOnClickListener(v -> pickGestureAnim(GestureAnim.STYLE_NATIVE));
            animGlow.setOnClickListener(v -> pickGestureAnim(GestureAnim.STYLE_GLOW));
            applyGestureAnim();
        }
    }

    /** 换内屏手势的画法。只是怎么画的事，不用重装捕获带 */
    private void pickGestureAnim(int style) {
        ExtPrefs.setGestureAnimStyle(this, style);
        applyGestureAnim();
    }

    /** 两档画成一对分段按钮：选中的那格换成实心绿（跟子选项卡同色） */
    private void applyGestureAnim() {
        int now = ExtPrefs.gestureAnimStyle(this);
        paintSegment(R.id.btnAnimNative, now == GestureAnim.STYLE_NATIVE);
        paintSegment(R.id.btnAnimGlow, now == GestureAnim.STYLE_GLOW);
    }

    private void paintSegment(int id, boolean on) {
        TextView t = findViewById(id);
        if (t == null) {
            return;
        }
        t.setBackgroundResource(on ? R.drawable.beta_btn_primary : R.drawable.beta_btn_ghost);
        t.setTextColor(on ? 0xFFFFFFFF : 0xE6FFFFFF);
    }

    /**
     * 拨「隐藏三键导航」。
     *
     * <p>这是一件<b>动系统设置</b>的事：改的是 {@code Settings.Global.policy_control}，
     * 走 {@link NavBarImmersive#apply}。成没成都得回读校验一次 —— 装了 NavStar 的机器上它会
     * 按自己的偏好把同一项重写回去，不校验就会变成"界面显示开了、条还在"。
     */
    private void doNavImm(final boolean on) {
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            setNavImmSwitch(!on);           // 拨回去，别让开关停在没生效的位置上
            return;
        }
        final Context app = getApplicationContext();
        pool.execute(() -> {
            final String err = NavBarImmersive.apply(app, on);
            ui.post(() -> {
                toast(err != null ? err
                        : (on ? "系统导航条已整条藏起来" : "已还原接管前的设置"));
                refreshNavImmState();
            });
        });
    }

    /**
     * 静默回读系统里那一项，让开关跟着它走（页面上没有状态行了）。
     *
     * <p>⚠ 回读结果要盖掉本地记的那份 —— 用户可能自己改过设置，或者 NavStar 把值换成了
     * 带排除项的版本；开关得跟着系统现状，不能跟着我们记的。
     *
     * <p>读不到（Shizuku 没就绪）就什么都不动：这时候拨开关本来也拨不动，
     * 硬把开关摆成某个位置反而是撒谎。
     */
    private void refreshNavImmState() {
        if (!ShellRunner.isReady()) {
            return;
        }
        pool.execute(() -> {
            final String policy = NavBarImmersive.read();
            if (policy == null) {
                return;
            }
            final boolean on = NavBarImmersive.isOn(policy);
            ui.post(() -> {
                if (isFinishing()) {
                    return;
                }
                ExtPrefs.setNavImmOn(this, on);
                setNavImmSwitch(on);
            });
        });
    }

    /** 把开关拨到指定位置，<b>不算用户操作</b>（不会再去写系统） */
    private void setNavImmSwitch(boolean on) {
        Switch sw = findViewById(R.id.swNavImm);
        if (sw == null || sw.isChecked() == on) {
            return;
        }
        navImmSyncing = true;
        sw.setChecked(on);
        navImmSyncing = false;
    }

    /**
     * 触发区宽度四档的名字（跟布局里那四个单选项一一对应）。
     *
     * <p>⚠ 不能叫 {@code zoneName} —— 侧边栏那边已经有一个同签名的 {@code zoneName(int)}
     * （那是"上/中/下"的段名），重名直接编译不过。
     */
    private static String zoneWidthName(int pick) {
        switch (pick) {
            case 0:
                return "窄 1/4";
            case 2:
                return "宽 1/2";
            case 3:
                return "满宽";
            default:
                return "中 1/3";
        }
    }

    /**
     * 点「一键去掉所有应用黑边」。
     *
     * <p><b>一次性动作，不是开关</b>：给机器上每个已装应用下发 {@link CutoutPolicy#POL_OFF}
     * （让窗口铺进挖孔），跳开系统设置与本应用。
     *
     * <p>几百个包挨个发一条 binder，实测十几到二十秒，所以整段丢到 {@link #pool} 里跑，
     * 期间界面只更新状态行。预检（版本不认 / Shizuku 没就绪）放在最前面，直接退回，不假装成功。
     */
    private void doApplyCutout() {
        if (!CutoutPolicy.supported()) {
            toast("这台机器的 Android 版本没有对应的功能号，这个按钮用不了");
            refreshEdgeState();
            return;
        }
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            refreshEdgeState();
            return;
        }
        // 这个 prefs 现在只当"点过没有"的记录，不再当状态（真状态去系统里回读）
        ExtPrefs.setCutoutGlobal(this, true);
        final Context app = getApplicationContext();
        if (tvEdgeState != null) {
            tvEdgeState.setText("正在给所有应用下发挖孔策略…（几百个包，要十几秒）");
        }
        pool.execute(() -> {
            final String out = CutoutPolicy.applyAll(app, CutoutPolicy.POL_OFF);
            ui.post(() -> {
                toast(out.startsWith("ERR") ? out : ("完成 · " + out));
                refreshEdgeState();
            });
        });
    }

    /**
     * 打开系统的「导航条」设置页。
     *
     * <p>用户提的：底部那条手势条想安心接管"上滑"，前提是系统别在同一串触摸上再判一次。
     * 系统自己的「底部上滑回桌面」在「全面屏手势」模式下会跟它抢（现象是后台刚出来又回桌面），
     * 换成「导航条」之后封面屏底部那条系统手势区就没了。
     * 所以在这儿给个直达入口，别让人自己去设置里一层层翻。
     */
    private void doOpenNavMode() {
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            return;
        }
        toast("正在打开系统的「导航条」设置…");
        pool.execute(() -> {
            // ⚠ $ 必须待在单引号里，否则会被 shell 当变量吃掉，解析成不存在的组件
            final String out = ShellRunner.run(
                    "am start -n 'com.android.settings/.Settings$NavigationBarSettingsActivity'"
                            + " || am start -a android.settings.DISPLAY_SETTINGS"
                            + " || am start -a android.settings.SETTINGS", 15);
            ui.post(() -> toast(out == null || out.contains("Error")
                    ? "没打开，手动去：设置 → 显示 → 导航条"
                    : "已打开，改成「导航条」即可"));
        });
    }

    /** 「去掉应用黑边」的状态行：当场回读一个样本包的策略值 + 点过按钮没有 */
    /**
     * 「外屏侧边栏」的其余设置。
     *
     * <p>动画效果、位置（左/右 × 上/中/下）、面板内容、通知使用权状态与授权按钮。
     *
     * <p>⚠ 位置与内容都必须"拆了重装"才生效 ⇒ 一律走
     * {@link VolumeKeyService#refreshSidebar()}；只改 prefs 是不会动的
     * （感应带的窗口几何是在安装那一刻定死的，跟手势条的捕获带同一条道理）。
     */
    private void bindSidebarPanel() {
        RadioGroup anim = findViewById(R.id.rgSidebarAnim);
        if (anim != null) {
            int cur = ExtPrefs.sidebarStyle(this);
            anim.check(cur == 1 ? R.id.rbAnimGear
                    : (cur == 3 ? R.id.rbAnimWin : R.id.rbAnimRing));
            anim.setOnCheckedChangeListener((g, id) -> {
                int pick = id == R.id.rbAnimGear ? 1 : (id == R.id.rbAnimWin ? 3 : 0);
                if (pick == ExtPrefs.sidebarStyle(this)) {
                    return;
                }
                ExtPrefs.setSidebarStyle(this, pick);
                VolumeKeyService.refreshSidebar();
                toast("侧边栏动画：" + animName(pick));
            });
        }

        RadioGroup side = findViewById(R.id.rgSidebarSide);
        if (side != null) {
            int cur = ExtPrefs.sidebarSide(this);
            side.check(cur == 0 ? R.id.rbSideLeft : R.id.rbSideRight);
            side.setOnCheckedChangeListener((g, id) -> {
                int pick = id == R.id.rbSideLeft ? 0 : 1;
                if (pick == ExtPrefs.sidebarSide(this)) {
                    return;
                }
                ExtPrefs.setSidebarSide(this, pick);
                VolumeKeyService.refreshSidebar();
                toast("侧边栏挪到" + (pick == 0 ? "左" : "右") + "侧");
            });
        }

        RadioGroup zone = findViewById(R.id.rgSidebarZone);
        if (zone != null) {
            int cur = ExtPrefs.sidebarZone(this);
            zone.check(cur == 0 ? R.id.rbZoneTop
                    : (cur == 2 ? R.id.rbZoneBottom : R.id.rbZoneMid));
            zone.setOnCheckedChangeListener((g, id) -> {
                int pick = id == R.id.rbZoneTop ? 0 : (id == R.id.rbZoneBottom ? 2 : 1);
                if (pick == ExtPrefs.sidebarZone(this)) {
                    return;
                }
                ExtPrefs.setSidebarZone(this, pick);
                VolumeKeyService.refreshSidebar();
                toast("感应带挪到" + zoneName(pick) + "段");
            });
        }

        applySbItemsOrder();

        View grant = findViewById(R.id.btnNotifGrant);
        if (grant != null) {
            grant.setOnClickListener(v -> doGrantNotif());
        }
        refreshNotifState();
    }

    private static String zoneName(int z) {
        return z == 0 ? "上" : (z == 2 ? "下" : "中");
    }

    /**
     * 「面板内容」那四个勾：0=通知 1=最近任务 2=返回 3=回到桌面。
     *
     * <p>这四个下标就是 {@link ExtPrefs#SIDEBAR_ITEM_IDS} 的顺序（跟布局里从上到下一致）——
     * 勾几项、面板上就留几档，档位顺序也照这个次序。
     */
    /** 「动画效果」那三个的名字（日志与提示共用） */
    private static String animName(int kind) {
        return kind == 1 ? "调速齿轮" : (kind == 3 ? "抽奖轮盘" : "动力圆环");
    }

    /**
     * 按存下来的次序把「面板内容」那四行填上：第 i 行放
     * {@link ExtPrefs#sidebarOrder(Context)} 里的第 i 个指令。
     *
     * <p>⚠ 四行是<b>按位置</b>读出来的（cbSbItem0 ~ 3 就是从上面数第 1 ~ 4 行），
     * 换次序只是"把内容重写一遍" —— 不搬 View。ViewGroup 里挪孩子有过 parent 没摘干净、
     * 再 addView 直接抛的坑，不值当。
     *
     * <p>每行重填时先摘掉监听再 {@code setChecked}，免得这次程序性改动被当成用户点了一下。
     */
    private void applySbItemsOrder() {
        final int[] cbIds = {R.id.cbSbItem0, R.id.cbSbItem1, R.id.cbSbItem2, R.id.cbSbItem3};
        final int[] upIds = {R.id.upSbItem0, R.id.upSbItem1, R.id.upSbItem2, R.id.upSbItem3};
        final int[] dnIds = {R.id.dnSbItem0, R.id.dnSbItem1, R.id.dnSbItem2, R.id.dnSbItem3};
        final int[] order = ExtPrefs.sidebarOrder(this);
        final boolean[] on = ExtPrefs.sidebarItemsOn(this);
        for (int i = 0; i < order.length && i < cbIds.length; i++) {
            final int act = order[i];
            final int row = i;      // lambda 只能捕获 final，循环变量 i 不算
            final int at = ExtPrefs.itemIndex(act);
            CheckBox cb = findViewById(cbIds[i]);
            if (cb != null && at >= 0) {
                cb.setText(ExtPrefs.ringActionName(this, act));
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(on[at]);
                cb.setOnCheckedChangeListener((b, isOn) -> setSbItem(act, isOn));
            }
            View up = findViewById(upIds[i]);
            if (up != null) {
                up.setOnClickListener(v -> moveSbItemAt(row, -1));
                dimSortArrow(up, i > 0);
            }
            View dn = findViewById(dnIds[i]);
            if (dn != null) {
                dn.setOnClickListener(v -> moveSbItemAt(row, 1));
                dimSortArrow(dn, i < order.length - 1);
            }
        }
    }

    /**
     * 勾上 / 取消某一项。
     *
     * <p>⚠ 参数是<b>指令号</b>，不是第几行 —— 勾选状态那四位是按
     * {@link ExtPrefs#SIDEBAR_ITEM_IDS} 的下标存的，跟界面上的行号无关。
     */
    private void setSbItem(int act, boolean isOn) {
        int at = ExtPrefs.itemIndex(act);
        if (at < 0) {
            return;
        }
        boolean[] cur = ExtPrefs.sidebarItemsOn(this);
        cur[at] = isOn;
        ExtPrefs.setSidebarItemsOn(this, cur);
        VolumeKeyService.refreshSidebar();
        if (ExtPrefs.sidebarSlots(this).length == 0) {
            toast("面板内容一项都没勾，面板上不会留档位");
        }
    }

    /** 把第 at 行跟它上面（dir = -1）/ 下面（dir = +1）那行对调 */
    private void moveSbItemAt(int at, int dir) {
        int[] order = ExtPrefs.sidebarOrder(this);
        int to = at + dir;
        if (at < 0 || at >= order.length || to < 0 || to >= order.length) {
            return;
        }
        int t = order[at];
        order[at] = order[to];
        order[to] = t;
        ExtPrefs.setSidebarOrder(this, order);
        applySbItemsOrder();
        VolumeKeyService.refreshSidebar();
    }

    /** 到头那一侧的箭头压暗、也点不动（顶行没有"上"、底行没有"下"） */
    private static void dimSortArrow(View v, boolean usable) {
        v.setEnabled(usable);
        v.setAlpha(usable ? 1f : 0.22f);
    }

    /** 通知使用权那一行的状态（每次进页面、每次授权完都刷） */
    private void refreshNotifState() {
        TextView tv = findViewById(R.id.tvNotifState);
        if (tv != null) {
            tv.setText("通知使用权：" + CoverNotifListener.statusText(this));
        }
    }

    /**
     * 一键授「通知使用权」。
     *
     * <p>这是跟无障碍**分开**的一把权限，系统设置里在「通知 → 通知使用权」。
     * 走 Shizuku 发 {@code cmd notification allow_listener} 就不用让用户自己去翻菜单。
     */
    private void doGrantNotif() {
        if (!ShellRunner.isReady()) {
            toast("需要 Shizuku 才能一键授权（也可以去系统设置 → 通知 → 通知使用权里手动开）");
            return;
        }
        toast("正在授予通知使用权…");
        final Context ctx = this;
        pool.execute(() -> {
            String out = ShellRunner.run(CoverNotifListener.grantCmd(ctx), 12);
            android.util.Log.i("BetaActivity", "授予通知使用权 => " + out);
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
            }
            CoverNotifListener.refreshAll();
            final boolean ok = CoverNotifListener.granted(ctx);
            runOnUiThread(() -> {
                refreshNotifState();
                toast(ok ? "通知使用权已授予" : "授权好像没成，去系统设置里手动开一下");
            });
        });
    }

    private void refreshEdgeState() {
        final TextView tv = tvEdgeState;
        if (tv == null) {
            return;
        }
        if (!CutoutPolicy.supported()) {
            tv.setText("这台机器没有对应的功能号（sdk=" + Build.VERSION.SDK_INT
                    + "，release=" + Build.VERSION.RELEASE + "），这个按钮用不了。");
            return;
        }
        tv.setText("读取中…");
        final String probe = pickProbePackage();
        pool.execute(() -> {
            final int v = CutoutPolicy.read(this, probe);
            ui.post(() -> {
                if (isFinishing() || tvEdgeState == null) {
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("样本　").append(probe).append(" 策略=");
                if (v < 0) {
                    sb.append("读不到（Shizuku 没就绪？）");
                } else {
                    sb.append(v);
                    if (v == CutoutPolicy.POL_OFF) {
                        sb.append("（铺进挖孔 ⇒ 没有黑边）");
                    } else if (v == CutoutPolicy.POL_DEFAULT) {
                        sb.append("（默认档）");
                    }
                }
                sb.append(ExtPrefs.cutoutGlobal(this)
                        ? "\n上次　点过按钮" : "\n上次　没点过按钮");
                sb.append("\n改动要对已经打开的应用生效，得把它重新拉起来一次。");
                tvEdgeState.setText(sb.toString());
            });
        });
    }

    /**
     * 回读用哪个包当样本。
     *
     * <p>挑一个<b>一定装在机器上的第三方应用</b>：系统设置与我们自己都在下发时被跳过，
     * 拿它们当样本说明不了问题。挑不到就退回设置 —— 它至少保证读得到。
     */
    private String pickProbePackage() {
        for (String p : new String[]{"com.tencent.mobileqq", "tv.danmaku.bili",
                "com.autonavi.minimap", "com.ss.android.ugc.aweme"}) {
            try {
                getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (Throwable ignored) {
            }
        }
        return "com.android.settings";
    }

    // ================================================================== 首次进入的说明

    /** 「Beta 实验室」的说明弹窗要不要再弹，存这儿 */
    private static final String FILE_INTRO = "extrot_beta";
    private static final String KEY_INTRO_OFF = "intro_off";
    /** 上次把这个"不再弹出"勾下去时是哪个 versionCode */
    private static final String KEY_INTRO_VER = "intro_off_ver";

    /**
     * 第一次进这一页时把说明弹出来。
     *
     * <p>弹窗里带一个「默认不再弹出」勾选框：勾上之后再进就不弹了，想再看随时点标题右边那个 ⓘ
     * （两处用的是同一段正文）。
     *
     * <p>⚠ 这个「不再弹出」只在同一版里算数。用户要的是「别人装了我的新包，一进 Beta 实验室就能
     * 看到我的说明和注意事项」—— 换了一版就是新东西，之前那次「不再提醒」作废，重新弹一次。
     * 所以勾的时候连版本号一起记，进来时对一下：对不上就照弹。
     */
    private void maybeShowIntro() {
        SharedPreferences sp = getSharedPreferences(FILE_INTRO, MODE_PRIVATE);
        boolean off = sp.getBoolean(KEY_INTRO_OFF, false)
                && sp.getInt(KEY_INTRO_VER, -1) == myVersionCode();
        if (off || isFinishing()) {
            return;
        }
        // 用自定义布局而不是 setMessage + setMultiChoiceItems：后者把勾选框挂在
        // 正文下面，正文一长就先被裁掉（实测在封面屏上连正文都截断了）。
        View body = getLayoutInflater().inflate(R.layout.dialog_beta_intro, null);
        final android.widget.CheckBox cb = body.findViewById(R.id.cbBetaIntroOff);
        new AlertDialog.Builder(this)
                .setTitle("Beta 实验室")
                .setView(body)
                .setPositiveButton("知道了", (d, w) -> {
                    if (cb != null && cb.isChecked()) {
                        getSharedPreferences(FILE_INTRO, MODE_PRIVATE).edit()
                                .putBoolean(KEY_INTRO_OFF, true)
                                .putInt(KEY_INTRO_VER, myVersionCode())
                                .apply();
                    }
                })
                .show();
    }

    /** 本机的 versionCode，用来判"这个不再弹出是不是当前这一版勾的" */
    private int myVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ================================================================== 外屏设置

    private void bindCoverPanel() {
        etW = findViewById(R.id.etCoverW);
        etH = findViewById(R.id.etCoverH);
        etDpi = findViewById(R.id.etCoverDpi);
        tvCoverState = findViewById(R.id.tvCoverState);
        listPresets = findViewById(R.id.listCoverPresets);

        View apply = findViewById(R.id.btnCoverApply);
        if (apply != null) {
            apply.setOnClickListener(v -> applyCoverSize());
        }
        View reset = findViewById(R.id.btnCoverReset);
        if (reset != null) {
            reset.setOnClickListener(v -> confirmResetCoverSize());
        }

        View addPreset = findViewById(R.id.btnPresetAdd);
        if (addPreset != null) {
            addPreset.setOnClickListener(v -> showPresetEditor(-1));
        }
        View resetPreset = findViewById(R.id.btnPresetReset);
        if (resetPreset != null) {
            resetPreset.setOnClickListener(v -> {
                presets = CoverPresets.resetToDefault(this, physW, physH, physDpi);
                renderPresets();
                toast("已恢复默认预设");
            });
        }

        View rotate = findViewById(R.id.btnCoverRotate);
        if (rotate != null) {
            rotate.setOnClickListener(v -> doCoverRotate());
        }
        View rotateReset = findViewById(R.id.btnCoverRotateReset);
        if (rotateReset != null) {
            rotateReset.setOnClickListener(v -> doCoverRotateReset());
        }

        // ---- 外屏自动旋转（v4.23）。跟上面那颗「旋转 90°」是同一件事的两半：那颗手动一下
        //      一下点，这个让外屏自己跟着手机的横竖转（外屏原生不跟手机转）。下发要发 shell，
        //      丢后台；回来再刷一次状态行，好让上面那行度数马上变成新值。
        //      ⚠ 先 setChecked 再挂监听 —— 反了就等于"初始化那一发把用户的开关拨回去"（本页通例）。
        Switch coverAutoRot = findViewById(R.id.swCoverAutoRot);
        if (coverAutoRot != null) {
            coverAutoRot.setChecked(CoverAutoRot.on(this));
            coverAutoRot.setOnCheckedChangeListener((b, on) -> {
                if (coverRotSyncing) {
                    return;
                }
                pool.execute(() -> {
                    String err = CoverAutoRot.set(this, on);
                    ui.post(() -> {
                        if (err != null) {
                            toast(err);
                            // 没成 → 拨回去，别让开关停在没生效的位置上
                            coverRotSyncing = true;
                            coverAutoRot.setChecked(!on);
                            coverRotSyncing = false;
                        } else {
                            toast(on ? "外屏自动旋转：开" : "外屏自动旋转：关");
                        }
                        refreshCoverState();
                    });
                });
            });
        }

        // ---- 外屏启动器
        tvLauncherState = findViewById(R.id.tvLauncherState);

        View openLauncher = findViewById(R.id.btnLauncherOpen);
        if (openLauncher != null) {
            openLauncher.setOnClickListener(v -> openLauncherConfig());
        }

        View pinHelp = findViewById(R.id.btnLauncherPin);
        if (pinHelp != null) {
            pinHelp.setOnClickListener(v -> showPinHelp());
        }
    }

    /**
     * 「打开配置页」。
     *
     * <p>刻意做成跟「点外屏上那个齿轮」走同一条路（{@link SecondaryLauncher#launchOwn}），
     * 而不是本机直接 {@code startActivity}：配置页是个 GridView + 搜索框的重界面，
     * 放在外屏上打开才能顺手看见「改完外屏长什么样」。
     *
     * <p>投屏不再要求 Shizuku —— 没有授权时 {@code launchOwn} 会走三星的封面屏通道；
     * 两条都走不通才退化成普通启动（落在手机内屏），功能不残废，只是位置不对。
     */
    private void openLauncherConfig() {
        int display = CoverDisplay.id(this);
        if (display > 0) {
            pool.execute(() -> {
                boolean ok = SecondaryLauncher.launchOwn(this, display, CoverLauncherActivity.class);
                if (!ok) {
                    ui.post(() -> startActivity(new Intent(this, CoverLauncherActivity.class)));
                }
            });
        } else {
            startActivity(new Intent(this, CoverLauncherActivity.class));
        }
    }

    /**
     * 「怎么钉到外屏」。
     *
     * <p>这块只能在系统设置里手动完成，adb 代劳不了 —— 外屏托盘是三星桌面自己管的，
     * 没有对外接口能往里塞组件。所以这里只把路径说清楚，不让用户自己找。
     */
    private void showPinHelp() {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("把启动器放到外屏")
                .setMessage(R.string.info_launcher_pin)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 「外屏自动旋转」那个开关跟着实况走（在主界面快捷操作、老虎机里拨过也跟得上） */
    private void refreshCoverAutoRotSwitch() {
        Switch sw = findViewById(R.id.swCoverAutoRot);
        if (sw == null) {
            return;
        }
        boolean on = CoverAutoRot.on(this);
        if (sw.isChecked() != on) {
            coverRotSyncing = true;
            sw.setChecked(on);
            coverRotSyncing = false;
        }
    }

    /** 刷新「外屏启动器」区块的状态行 */
    private void refreshLauncherState() {
        if (tvLauncherState == null) {
            return;
        }
        int n = LauncherPrefs.apps(this).size();
        if (n == 0) {
            tvLauncherState.setText("还没勾选应用。点「打开配置页」，在外屏上勾选要显示的应用。");
        } else {
            tvLauncherState.setText("已勾选 " + n + " 个应用。外屏上的启动器会自动跟着变，不用重新添加组件。");
        }
    }

    /** 读一次封面屏状态并刷新界面（后台读、主线程刷） */
    private void refreshCoverState() {
        pool.execute(() -> {
            CoverDisplay.State st = CoverDisplay.read(this);
            ui.post(() -> renderCoverState(st));
        });
    }

    private void renderCoverState(CoverDisplay.State st) {
        if (isFinishing() || tvCoverState == null) {
            return;
        }
        if (st.displayId <= 0) {
            tvCoverState.setText("找不到封面屏。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("面板原生   ").append(nz(st.physSize, "?")).append("   ").append(st.physDpi).append("dpi\n");
        sb.append("当前覆盖   ");
        if (st.overridden()) {
            sb.append(st.hasSizeOverride() ? st.overrideSize : "（未改分辨率）");
            sb.append("   ");
            sb.append(st.hasDpiOverride() ? st.overrideDpi + "dpi" : "（未改 DPI）");
        } else {
            sb.append("无");
        }
        sb.append('\n');
        sb.append("旋转       ").append(CoverDisplay.degreeText(st.rotation));
        if (st.rotation != CoverDisplay.ROT_FREE) {
            sb.append("（已锁定）");
        }
        tvCoverState.setText(sb.toString());

        // 面板原生值留一份，给默认预设和输入框用
        if (st.physSize != null && st.physSize.contains("x")) {
            String[] p = st.physSize.split("x");
            try {
                physW = Integer.parseInt(p[0].trim());
                physH = Integer.parseInt(p[1].trim());
                physDpi = st.physDpi;
            } catch (Throwable ignored) {
            }
        }

        // 输入框只在为空时填 —— 避免把用户正在敲的内容冲掉
        if (etW != null && TextUtils.isEmpty(etW.getText())) {
            String w = st.hasSizeOverride() ? st.overrideSize : st.physSize;
            if (w != null && w.contains("x")) {
                String[] p = w.split("x");
                etW.setText(p[0].trim());
                if (etH != null) {
                    etH.setText(p[1].trim());
                }
            }
        }
        if (etDpi != null && TextUtils.isEmpty(etDpi.getText())) {
            int d = st.hasDpiOverride() ? st.overrideDpi : st.physDpi;
            etDpi.setText(String.valueOf(d));
        }

        if (presets == null) {
            presets = CoverPresets.load(this, physW, physH, physDpi);
            renderPresets();
        }
    }

    private static String nz(String s, String fallback) {
        return s == null ? fallback : s;
    }

    private void applyCoverSize() {
        if (needShizuku()) {
            return;
        }
        int w = parse(etW);
        int h = parse(etH);
        int dpi = parse(etDpi);
        if (w <= 0 || h <= 0) {
            toast("分辨率要填成「宽」和「高」两个数字");
            return;
        }
        pool.execute(() -> {
            String err = CoverDisplay.applySize(this, w, h, dpi);
            ui.post(() -> {
                toast(err == null ? ("已覆盖为 " + w + "×" + h + (dpi > 0 ? " / " + dpi + "dpi" : "")) : err);
                refreshCoverState();
            });
        });
    }

    private void confirmResetCoverSize() {
        if (needShizuku()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("把封面屏的分辨率和 DPI 恢复成面板原生值？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("恢复", (d, w) -> pool.execute(() -> {
                    String err = CoverDisplay.resetSize(this, true);
                    ui.post(() -> {
                        toast(err == null ? "已恢复默认" : err);
                        if (etW != null) {
                            etW.setText("");
                        }
                        if (etH != null) {
                            etH.setText("");
                        }
                        if (etDpi != null) {
                            etDpi.setText("");
                        }
                        refreshCoverState();
                    });
                }))
                .show();
    }

    private int parse(EditText e) {
        if (e == null) {
            return 0;
        }
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 预设列表

    private void renderPresets() {
        if (listPresets == null) {
            return;
        }
        listPresets.removeAllViews();
        if (presets == null) {
            return;
        }
        for (int i = 0; i < presets.size(); i++) {
            listPresets.addView(buildPresetRow(presets.get(i), i));
        }
        if (presets.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("还没有预设，点右上角「＋ 新增」加一条。");
            tv.setTextColor(0x8CFFFFFF);
            tv.setTextSize(13);
            tv.setPadding(0, dp(6), 0, 0);
            listPresets.addView(tv);
        }
    }

    private View buildPresetRow(final CoverPresets.Preset p, final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.beta_row);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(p.name);
        name.setTextColor(0xFFF0F0F0);
        name.setTextSize(14);
        col.addView(name);

        TextView sum = new TextView(this);
        sum.setText(p.summary());
        sum.setTextColor(0x8CFFFFFF);
        sum.setTextSize(12);
        col.addView(sum);

        row.addView(col);

        TextView hint = new TextView(this);
        hint.setText("点应用");
        hint.setTextColor(0xFF7FC77F);
        hint.setTextSize(12);
        row.addView(hint);

        row.setOnClickListener(v -> applyPreset(p));
        row.setOnLongClickListener(v -> {
            confirmDeletePreset(index);
            return true;
        });
        return row;
    }

    private void applyPreset(CoverPresets.Preset p) {
        if (needShizuku()) {
            return;
        }
        if (etW != null) {
            etW.setText(String.valueOf(p.w));
        }
        if (etH != null) {
            etH.setText(String.valueOf(p.h));
        }
        if (etDpi != null) {
            etDpi.setText(String.valueOf(p.dpi));
        }
        pool.execute(() -> {
            String err = CoverDisplay.applySize(this, p.w, p.h, p.dpi);
            ui.post(() -> {
                toast(err == null ? ("已应用「" + p.name + "」") : err);
                refreshCoverState();
            });
        });
    }

    private void confirmDeletePreset(final int index) {
        if (presets == null || index < 0 || index >= presets.size()) {
            return;
        }
        final CoverPresets.Preset p = presets.get(index);
        new AlertDialog.Builder(this)
                .setTitle("删除预设")
                .setMessage("删掉「" + p.name + "」（" + p.summary() + "）？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (d, w) -> {
                    CoverPresets.removeAt(this, presets, index);
                    renderPresets();
                })
                .show();
    }

    /**
     * 新增 / 编辑预设。
     *
     * <p>没有引入额外的布局文件：这个弹窗只有三个输入框，用代码拼比再多一个 xml 更好维护
     * （那些只有一处用到的布局文件散在 res/layout 里，回头很难判断能不能删）。
     *
     * @param editIndex 传 -1 表示新增
     */
    private void showPresetEditor(final int editIndex) {
        // 状态还没读回来时 presets 是 null（首次读状态是异步的），这里补一次，
        // 免得用户手快先点了「＋ 新增」就崩
        if (presets == null) {
            presets = CoverPresets.load(this, physW, physH, physDpi);
            renderPresets();
        }
        if (editIndex < 0 && presets.size() >= CoverPresets.MAX) {
            toast("最多 " + CoverPresets.MAX + " 组预设");
            return;
        }
        final CoverPresets.Preset editing =
                (editIndex >= 0 && presets != null && editIndex < presets.size())
                        ? presets.get(editIndex) : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText name = new EditText(this);
        name.setHint("名称，例如 小窗模式");
        if (editing != null) {
            name.setText(editing.name);
        }
        box.addView(name);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setPadding(0, dp(10), 0, 0);

        final EditText w = new EditText(this);
        w.setHint("宽");
        w.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        w.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeRow.addView(w);

        final EditText h = new EditText(this);
        h.setHint("高");
        h.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        h.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeRow.addView(h);

        final EditText dpi = new EditText(this);
        dpi.setHint("DPI，可空");
        dpi.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dpi.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeRow.addView(dpi);
        box.addView(sizeRow);

        if (editing != null) {
            w.setText(String.valueOf(editing.w));
            h.setText(String.valueOf(editing.h));
            dpi.setText(editing.dpi > 0 ? String.valueOf(editing.dpi) : "");
        } else {
            // 新增时给个起点：当前面板原生尺寸
            w.setText(String.valueOf(physW > 0 ? physW : 748));
            h.setText(String.valueOf(physH > 0 ? physH : 720));
            dpi.setText(String.valueOf(physDpi > 0 ? physDpi : 340));
        }

        new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? "编辑预设" : "新增预设")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (d, which) -> {
                    String nm = name.getText().toString().trim();
                    int ww = parseIntText(w);
                    int hh = parseIntText(h);
                    int dd = parseIntText(dpi);
                    if (TextUtils.isEmpty(nm)) {
                        toast("给预设起个名字");
                        return;
                    }
                    if (ww <= 0 || hh <= 0) {
                        toast("分辨率要填成两个数字");
                        return;
                    }
                    if (editing != null) {
                        editing.name = nm;
                        editing.w = ww;
                        editing.h = hh;
                        editing.dpi = dd;
                        CoverPresets.save(this, presets);
                        renderPresets();
                    } else {
                        CoverPresets.add(this, presets, new CoverPresets.Preset(nm, ww, hh, dd));
                        renderPresets();
                    }
                })
                .show();
    }

    private int parseIntText(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 旋转

    private void doCoverRotate() {
        if (needShizuku()) {
            return;
        }
        pool.execute(() -> {
            int deg = CoverDisplay.rotate(this);
            ui.post(() -> {
                toast(deg < 0 ? "旋转失败" : ("外屏已转到 " + CoverDisplay.degreeText(deg)));
                refreshCoverState();
            });
        });
    }

    private void doCoverRotateReset() {
        if (needShizuku()) {
            return;
        }
        pool.execute(() -> {
            boolean ok = CoverDisplay.resetRotation(this);
            ui.post(() -> {
                toast(ok ? "外屏已恢复自动旋转" : "恢复失败");
                refreshCoverState();
            });
        });
    }

    // ================================================================== 指令操作

    private void bindQuickPanel() {
        tvAssistState = findViewById(R.id.tvAssistState);
        tvVolumeState = findViewById(R.id.tvVolumeState);

        // 「侧键操作」：先选按键，下面只显示那一个按键的设置块
        keyTarget = QuickPrefs.sideKey(this);
        View keyAssist = findViewById(R.id.btnKeyAssist);
        if (keyAssist != null) {
            keyAssist.setOnClickListener(v -> selectKey(QuickPrefs.SIDE_ASSIST));
        }
        View keyVolume = findViewById(R.id.btnKeyVolume);
        if (keyVolume != null) {
            keyVolume.setOnClickListener(v -> selectKey(QuickPrefs.SIDE_VOL_UP));
        }
        View keyVolumeDown = findViewById(R.id.btnKeyVolumeDown);
        if (keyVolumeDown != null) {
            keyVolumeDown.setOnClickListener(v -> selectKey(QuickPrefs.SIDE_VOL_DOWN));
        }
        tvExternalAction = findViewById(R.id.tvExternalAction);

        View assistPick = findViewById(R.id.btnAssistPick);
        if (assistPick != null) {
            assistPick.setOnClickListener(v -> pickAction(QuickPrefs.SIDE_ASSIST));
        }
        View assistSet = findViewById(R.id.btnAssistSet);
        if (assistSet != null) {
            assistSet.setOnClickListener(v -> confirmSetAssistant());
        }
        View assistTest = findViewById(R.id.btnAssistTest);
        if (assistTest != null) {
            assistTest.setOnClickListener(v -> runAction(QuickPrefs.assistAction(this)));
        }

        View volPick = findViewById(R.id.btnVolumePick);
        if (volPick != null) {
            volPick.setOnClickListener(v -> pickAction(keyTarget));
        }
        View volA11y = findViewById(R.id.btnVolumeA11y);
        if (volA11y != null) {
            volA11y.setOnClickListener(v -> openAccessibilitySettings());
        }
        View volTest = findViewById(R.id.btnVolumeTest);
        if (volTest != null) {
            volTest.setOnClickListener(v -> runAction(currentVolumeAction()));
        }

        View copy = findViewById(R.id.btnCopyAction);
        if (copy != null) {
            copy.setOnClickListener(v -> copyActionToClipboard());
        }

        // 自定义指令
        listCustomCmds = findViewById(R.id.listCustomCmds);
        View allowRestricted = findViewById(R.id.btnAllowRestricted);
        if (allowRestricted != null) {
            allowRestricted.setOnClickListener(v -> confirmAllowRestricted(false));
        }
        View grantSecure = findViewById(R.id.btnGrantSecure);
        if (grantSecure != null) {
            grantSecure.setOnClickListener(v -> doGrantSecure());
        }
        View addCmd = findViewById(R.id.btnCustomAdd);
        if (addCmd != null) {
            addCmd.setOnClickListener(v -> showCmdEditor(null));
        }
        applyKeySelection();
        renderCustomCmds();
    }

    // ------------------------------------------------------------ 侧键操作

    /** 在「侧键操作」里换按键；换完只显示那一套设置 */
    private void selectKey(String target) {
        if (keyTarget.equals(target)) {
            return;
        }
        keyTarget = target;
        QuickPrefs.setSideKey(this, target);
        applyKeySelection();
        refreshQuickState();
    }

    /** 当前选中的音量键对应的动作（「测试」按钮用）；选的是电源键时按上键算 */
    private String currentVolumeAction() {
        return QuickPrefs.SIDE_VOL_DOWN.equals(keyTarget)
                ? QuickPrefs.volumeDownAction(this)
                : QuickPrefs.volumeUpAction(this);
    }

    /**
     * 按当前选中的按键决定露出哪一块设置，并给三个 chip 上选中态。
     *
     * <p>两块设置一直都在布局里（id 沿用旧版），只是轮着显示 ——
     * 这样 refreshQuickState 那套逻辑一个字都不用改。
     *
     * <p>上键与下键<b>共用</b>同一个音量块：一次只配一个键（跟芯片的语义一致），
     * 块里那两个按钮的文案跟着当前 chip 走。
     */
    private void applyKeySelection() {
        boolean isAssist = QuickPrefs.SIDE_ASSIST.equals(keyTarget);
        showPanel(R.id.keyAssistBlock, isAssist);
        showPanel(R.id.keyVolumeBlock, !isAssist);
        chipSelected(findViewById(R.id.btnKeyAssist), isAssist);
        chipSelected(findViewById(R.id.btnKeyVolume), QuickPrefs.SIDE_VOL_UP.equals(keyTarget));
        chipSelected(findViewById(R.id.btnKeyVolumeDown), QuickPrefs.SIDE_VOL_DOWN.equals(keyTarget));

        boolean down = QuickPrefs.SIDE_VOL_DOWN.equals(keyTarget);
        View pick = findViewById(R.id.btnVolumePick);
        if (pick instanceof TextView) {
            ((TextView) pick).setText(down ? "选择下键动作" : "选择上键动作");
        }
        View test = findViewById(R.id.btnVolumeTest);
        if (test instanceof TextView) {
            ((TextView) test).setText(down ? "测试下键" : "测试上键");
        }
    }

    /** 选中的 chip 用主按钮那套绿底，未选中的用选项卡未选中那套深灰 */
    private void chipSelected(View v, boolean on) {
        if (v == null) {
            return;
        }
        v.setBackgroundResource(on ? R.drawable.beta_btn_primary : R.drawable.beta_tab_bg);
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
        }
    }

    /** 刷新这两块卡片的状态文字 */
    private void refreshQuickState() {
        boolean a11y = QuickPrefs.isAccessibilityEnabled(this);
        if (tvVolumeState != null) {
            tvVolumeState.setText(
                    "无障碍服务：" + (a11y ? "已开启" : "未开启（必须开启才能拦到音量键）")
                            + "\n长按音量上键：" + QuickActions.label(this, QuickPrefs.volumeUpAction(this))
                            + "\n长按音量下键：" + QuickActions.label(this, QuickPrefs.volumeDownAction(this))
                            + (QuickPrefs.isVolumeKeyEnabled(this)
                                    ? "" : "\n（总开关已关，两个键都不拦）"));
        }
        if (tvAssistState != null) {
            tvAssistState.setText("动作：" + QuickActions.label(this, QuickPrefs.assistAction(this))
                    + "\n当前数字助手：读取中…");
        }
        if (tvExternalAction != null) {
            tvExternalAction.setText(QuickActions.ACTION_RUN
                    + "\n  " + QuickActions.EXTRA_ACTION + " = toggle_fold | cover_home |"
                    + " cover_rotate | cover_rotate_reset | custom:<id>");
        }

        // 当前助手是谁，需要走 shell 读
        if (ShellRunner.isReady()) {
            pool.execute(() -> {
                final String cur = ShellRunner.run("settings get secure assistant").trim();
                ui.post(() -> {
                    if (tvAssistState != null && !isFinishing()) {
                        tvAssistState.setText("动作：" + QuickActions.label(this, QuickPrefs.assistAction(this))
                                + "\n当前数字助手：" + (TextUtils.isEmpty(cur) || "null".equals(cur) ? "（未设置）" : cur));
                    }
                });
            });
        }
    }

    private TextView tvExternalAction;

    /**
     * 弹一个单选列表让用户挑动作。
     *
     * @param target {@link QuickPrefs#SIDE_ASSIST} / {@link QuickPrefs#SIDE_VOL_UP}
     *               / {@link QuickPrefs#SIDE_VOL_DOWN}
     */
    private void pickAction(final String target) {
        final boolean forAssist = QuickPrefs.SIDE_ASSIST.equals(target);
        final boolean forDown = QuickPrefs.SIDE_VOL_DOWN.equals(target);

        // 白名单 = 内置四条 + 自定义指令，所以要从 options 取、不能再用 ALL
        final String[] base = QuickActions.options(this);
        // 音量键多挂一项「无」= 这个键不绑动作，长按交回系统调音量。
        // 电源键不给这一项：它靠系统"数字助手"位接入，选「无」并不能把助手位还回去，
        // 那得去系统侧键设置里改 —— 给了反而误导。
        final String[] keys;
        if (forAssist) {
            keys = base;
        } else {
            keys = new String[base.length + 1];
            keys[0] = QuickActions.KEY_NONE;
            System.arraycopy(base, 0, keys, 1, base.length);
        }

        final String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            labels[i] = QuickActions.label(this, keys[i]);
        }

        final String current = forAssist ? QuickPrefs.assistAction(this)
                : (forDown ? QuickPrefs.volumeDownAction(this) : QuickPrefs.volumeUpAction(this));
        int checked = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(current)) {
                checked = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(forAssist ? "长按电源键时执行"
                        : (forDown ? "长按音量下键时执行" : "长按音量上键时执行"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    String key = keys[which];
                    if (forAssist) {
                        QuickPrefs.setAssistAction(this, key);
                    } else if (forDown) {
                        QuickPrefs.setVolumeDownAction(this, key);
                    } else {
                        QuickPrefs.setVolumeUpAction(this, key);
                    }
                    d.dismiss();
                    refreshQuickState();
                })
                .show();
    }

    /**
     * 把本应用的快捷入口设成系统「数字助手」。
     *
     * <p>这是「长按电源键」唯一走得通的接法：三星侧键设置里长按的选项只有
     * 「唤醒数字助手 / 电源菜单 / 无」，应用没有别的办法占用它。
     *
     * <p>⚠️ 系统的助手位只有一个，设成本应用就会把原来的顶掉（这台机器上原本是
     * 「阿田自用」），所以必须先弹窗把现状摆出来再确认。
     */
    private void confirmSetAssistant() {
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            return;
        }
        final String target = getPackageName() + "/" + QuickActionActivity.class.getName();
        pool.execute(() -> {
            final String cur = ShellRunner.run("settings get secure assistant").trim();
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("设为数字助手")
                    .setMessage("当前数字助手：\n" + (TextUtils.isEmpty(cur) ? "（未设置）" : cur)
                            + "\n\n将改为：\n" + target
                            + "\n\n系统的助手位只有一个，原来的会被顶掉。"
                            + "设置完成后再去「设置 → 高级功能 → 侧键」把长按设为「唤醒数字助手」，"
                            + "长按电源键就会执行上面选的动作。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("设为助手", (d, w) -> pool.execute(() -> {
                        ShellRunner.run("settings put secure assistant " + target);
                        final String after = ShellRunner.run("settings get secure assistant").trim();
                        ui.post(() -> {
                            toast(target.equals(after) ? "已设为数字助手" : "设置未生效，结果：" + after);
                            refreshQuickState();
                        });
                    }))
                    .show());
        });
    }

    /**
     * 「开启无障碍服务」的入口。
     *
     * <p>⚠️ Android 13 起，targetSdk ≥ 33 且非商店安装的应用，系统会以「受限设置」拦下无障碍授权
     * （提示「系统已拒绝向此应用授予权限」）。One UI 8 又把设置里的「允许限制性设置」入口砍了
     * （应用信息页没有溢出菜单），UI 上无从解禁 —— 所以跳无障碍设置前先读一次
     * {@code appops get … ACCESS_RESTRICTED_SETTINGS}，没允许就先弹窗执行允许指令，再跳。
     */
    private void openAccessibilitySettings() {
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            return;
        }
        toast("正在检查限制性设置…");
        pool.execute(() -> {
            final String st = ShellRunner.run(
                    "appops get " + getPackageName() + " ACCESS_RESTRICTED_SETTINGS").trim();
            final boolean allowed = st.contains(": allow");
            ui.post(() -> {
                if (allowed) {
                    jumpA11y();
                } else {
                    confirmAllowRestricted(true);
                }
            });
        });
    }

    /** 直接跳系统无障碍设置 */
    private void jumpA11y() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            // 名字直接取服务 label，保证跟系统列表里显示的一模一样，改名不用两头改
            toast("在列表里找到「" + getString(R.string.a11y_service_label) + "」并开启");
        } catch (Throwable t) {
            toast("打不开无障碍设置，请手动进入");
        }
    }

    /**
     * 弹窗确认并执行「允许限制性设置」。
     *
     * @param jumpAfter 执行成功后是否接着跳无障碍设置
     *                  （从「开启无障碍服务」进来是 true，从指令卡按钮进来是 false）
     */
    private void confirmAllowRestricted(final boolean jumpAfter) {
        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，请先在主界面授权");
            return;
        }
        final String cmd = "appops set " + getPackageName() + " ACCESS_RESTRICTED_SETTINGS allow";
        new AlertDialog.Builder(this)
                .setTitle("允许限制性设置")
                .setMessage("本应用尚未被允许「限制性设置」，系统会因此拒绝授予无障碍权限。"
                        + "\n\n将执行指令（以 shell 身份）：\n" + cmd
                        + "\n\n" + (jumpAfter
                        ? "执行成功后会自动跳到无障碍设置。"
                        : "执行成功后即可到无障碍设置里开启服务。"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("执行", (d, w) -> pool.execute(() -> {
                    ShellRunner.run(cmd);
                    final String after = ShellRunner.run(
                            "appops get " + getPackageName() + " ACCESS_RESTRICTED_SETTINGS").trim();
                    final boolean ok = after.contains(": allow");
                    ui.post(() -> {
                        if (ok) {
                            toast("已允许限制性设置");
                            if (jumpAfter) {
                                jumpA11y();
                            }
                        } else {
                            toast("执行未生效，当前状态：" + after);
                        }
                    });
                }))
                .show();
    }

    /**
     * 一键拿「写入安全设置」权限（{@code WRITE_SECURE_SETTINGS}）。
     *
     * <p>它是一条 development 级权限，普通安装拿不到，只能借 shell 身份 {@code pm grant} 授一次。
     * 授过就一直有效，重启、覆盖安装都不用再授。
     *
     * <p>这只是让应用能写系统设置项（无线调试开关等），不会改手机现在的状态。
     */
    private void doGrantSecure() {
        final String perm = android.Manifest.permission.WRITE_SECURE_SETTINGS;
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("已经授过了");
            return;
        }
        if (!ShellRunner.isReady()) {
            toast("需要 Shizuku 才能一键授权");
            return;
        }
        final String cmd = "pm grant " + getPackageName() + " " + perm;
        toast("正在授权…");
        pool.execute(() -> {
            String out = ShellRunner.run(cmd, 12);
            android.util.Log.i("BetaActivity", "授予安全设置权限 => " + out);
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            final boolean ok = checkSelfPermission(perm)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            ui.post(() -> toast(ok ? "安全设置权限已授予"
                    : ("授权没成" + (out.isEmpty() ? "" : "：" + out))));
        });
    }

    private void runAction(final String key) {
        // ★ 无线调试这条动作免 Shizuku —— 它存在的理由就是"Shizuku 起不来时打开它"，
        //   卡在这里等于把死循环续上（见 QuickActions.run 里同一处判据）。
        if (!ShellRunner.isReady() && !QuickActions.KEY_ADB_WIFI.equals(key)) {
            toast("Shizuku 未就绪，请先在主界面授权");
            return;
        }
        toast("执行中…");
        pool.execute(() -> {
            String err = QuickActions.run(this, key);
            ui.post(() -> {
                toast(err == null
                        ? (QuickActions.label(this, key) + " 已完成")
                        : (QuickActions.label(this, key) + "：" + err));
                refreshCoverState();
            });
        });
    }

    // ------------------------------------------------------------ 自定义指令

    /** 重画自定义指令列表 */
    private void renderCustomCmds() {
        if (listCustomCmds == null) {
            return;
        }
        listCustomCmds.removeAllViews();
        List<CustomCmds.Cmd> all = CustomCmds.list(this);
        if (all.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("还没有自定义指令，点上面「＋ 新增指令」加一条。");
            tv.setTextColor(0x8CFFFFFF);
            tv.setTextSize(13);
            tv.setPadding(0, dp(6), 0, 0);
            listCustomCmds.addView(tv);
            return;
        }
        for (CustomCmds.Cmd c : all) {
            listCustomCmds.addView(buildCmdRow(c));
        }
    }

    private View buildCmdRow(final CustomCmds.Cmd c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.beta_row);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        // 第一行：名字 + 右边那串 key（外部调用要用到它）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextColor(0xFFF0F0F0);
        name.setTextSize(14);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);

        TextView key = new TextView(this);
        key.setText(CustomCmds.KEY_PREFIX + c.id);
        key.setTextColor(0x59FFFFFF);
        key.setTextSize(11);
        head.addView(key);
        row.addView(head);

        TextView cmd = new TextView(this);
        cmd.setText(c.cmd);
        cmd.setTextColor(0x8CFFFFFF);
        cmd.setTextSize(12);
        cmd.setTypeface(Typeface.MONOSPACE);
        row.addView(cmd);

        // 三颗按钮并排，等宽
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        btns.setLayoutParams(blp);

        TextView run = cmdBtn("执行", v -> runAction(CustomCmds.KEY_PREFIX + c.id));
        TextView edit = cmdBtn("编辑", v -> showCmdEditor(c));
        TextView del = cmdBtn("删除", v -> confirmDeleteCmd(c));
        ((LinearLayout.LayoutParams) run.getLayoutParams()).rightMargin = dp(8);
        ((LinearLayout.LayoutParams) edit.getLayoutParams()).rightMargin = dp(8);
        btns.addView(run);
        btns.addView(edit);
        btns.addView(del);
        row.addView(btns);
        return row;
    }

    /** 列表行里的一颗小按钮（三颗等宽并排） */
    private TextView cmdBtn(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(13);
        b.setTextColor(0xE6FFFFFF);
        b.setBackgroundResource(R.drawable.beta_btn_ghost);
        b.setPadding(0, dp(9), 0, dp(9));
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setOnClickListener(l);
        return b;
    }

    /** 新增（editing = null）或编辑一条自定义指令 */
    private void showCmdEditor(final CustomCmds.Cmd editing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText name = new EditText(this);
        name.setHint("名称，例如 关掉蓝牙");
        if (editing != null) {
            name.setText(editing.name);
        }
        box.addView(name);

        final EditText cmd = new EditText(this);
        cmd.setHint("shell 命令，例如 svc bluetooth disable");
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (editing != null) {
            cmd.setText(editing.cmd);
        }
        box.addView(cmd);

        new AlertDialog.Builder(this)
                .setTitle(editing == null ? "新增指令" : "编辑指令")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (d, w) -> {
                    String c1 = cmd.getText().toString().trim();
                    if (c1.isEmpty()) {
                        toast("命令不能为空");
                        return;
                    }
                    CustomCmds.Cmd item = (editing == null) ? new CustomCmds.Cmd() : editing;
                    item.name = name.getText().toString().trim();
                    item.cmd = c1;
                    CustomCmds.put(this, item);
                    renderCustomCmds();
                    refreshQuickState();
                })
                .show();
    }

    private void confirmDeleteCmd(final CustomCmds.Cmd c) {
        new AlertDialog.Builder(this)
                .setTitle("删除指令")
                .setMessage("删除「" + c.name + "」？\n\n侧键上如果正挂着它，会变成「自定义指令（已删除）」。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (d, w) -> {
                    CustomCmds.remove(this, c.id);
                    renderCustomCmds();
                    refreshQuickState();
                })
                .show();
    }

    private void copyActionToClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("action", QuickActions.ACTION_RUN));
                toast("已复制：" + QuickActions.ACTION_RUN);
            }
        } catch (Throwable t) {
            toast("复制失败");
        }
    }
}
