package com.wb.extrotator;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 4242;
    private static final int REQ_NOTI = 101;

    private static final int[] ROT_ALL = {0, 1, 2, 3};
    private static final String[] LAB_ALL = {"0°", "90°", "180°", "270°"};
    private static final int[] ROT_PORT = {1, 3};
    private static final String[] LAB_PORT = {"90°", "270°"};

    /**
     * 「屏幕方向」三档的取值。
     *
     * <p>这三个数字只在界面内部用，落盘仍然是 {@link Prefs#isLockPhoneRotation()} 与
     * {@link Prefs#getPhoneRotation()} 两个老字段 —— 这样「保存参数设置」那格、
     * 服务里的下发逻辑、以及旧档案全都不用动。
     */
    private static final int[] DIR_VALS = {0, 1, 2};
    private static final String[] DIR_LABS = {"横屏锁定", "竖屏锁定", "自动旋转"};
    private static final int DIR_LAND = 0, DIR_PORT = 1, DIR_AUTO = 2;

    /** 「开机选项」三档：不干预 / 折叠模式（外屏桌面）/ 展开模式（内屏桌面） */
    private static final int[] BOOT_VALS = {Prefs.BOOT_NONE, Prefs.BOOT_FOLDED, Prefs.BOOT_UNFOLDED};
    private static final String[] BOOT_LABS = {"不设置", "外屏桌面", "内屏桌面"};

    /** 分辨率下限：低于这个值内屏会变得很难用，直接拒绝 */
    private static final int MIN_RES = 480;

    private Prefs prefs;
    private DisplayManager dm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvShizuku, tvInfo, tvLog, tvResState, tvResHint;
    private Switch swEnable, swResAsk, swResAutoReset, swResAutoMatch;
    private Button btnResToggle;
    private RadioGroup rgTabs;
    private LinearLayout panelAuto, panelRes, panelState;
    private Spinner spPortrait, spLandscape, spScreenDir, spBootMode;
    private CheckBox cbFixToUser;
    private EditText etResW, etResH, etResDpi;
    private TextView tvFoldState;

    /** 刷新率小节的最高 / 最低下拉 */
    private Spinner spPeakHz, spMinHz;
    private TextView tvHzState;

    /** 选项卡 1 · 授权与状态 里的「快捷操作」 */
    private Switch swQASync;
    /** 同一排里的「外屏自动旋转」（v4.23）。跟 swQASync 是两件事，一个管内屏方向、一个管封面屏 */
    private Switch swQACoverRot;

    /** 「授权与状态 → 快捷操作」里那个「打开无线调试」按钮（v4.20 是开关，v4.22 改成按钮） */
    private Button btnQAAdbWifi;

    /** 选项卡 4 · 双屏 */
    private LinearLayout panelDual;
    private TextView tvDualState, tvSubState, tvDexInfo;

    /** 选项竖卡 · 按外接屏保存设置（每项一行、「已保存」与「当前」左右两列对比） */
    private TextView tvProfileHead;
    private LinearLayout profileTable;
    private Button btnSaveProfile, btnClearProfile;

    /** 我们最近一次改「锁手机方向」开关的时刻，用来给它一点落盘时间 */
    private long lockToggleAt = 0L;
    /** 连续两次读到「系统自动旋转被打开」才认定用户改过 */
    private int lockSyncStrikes = 0;

    /** 开关按钮的底色，让"开着/关着"一眼能看出来 */
    private static final int COLOR_ON = 0xFF2E7D32;
    private static final int COLOR_OFF = 0xFF757575;

    private boolean binding = true;

    private interface IntSetter {
        void set(int v);
    }

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            // 服务那头也会改设置（例如接屏时自动套用该屏的档案），界面必须跟着回读
            syncFromPrefs();
            // 「按外接屏保存设置」那栏要跟着插拔实时变，但它只在自动旋转页可见时才需要刷
            if (panelAuto != null && panelAuto.getVisibility() == View.VISIBLE) {
                refreshProfileUi();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> runOnUiThread(() -> {
                refreshShizukuState();
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    toast("Shizuku 授权成功");
                    if (prefs.shouldKeepRunning()) {
                        RotationService.start(this);
                    }
                } else {
                    toast("授权被拒绝");
                }
            });

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> runOnUiThread(this::refreshShizukuState);

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::refreshShizukuState);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);

        tvShizuku = findViewById(R.id.tvShizuku);
        tvInfo = findViewById(R.id.tvInfo);
        tvLog = findViewById(R.id.tvLog);
        tvResState = findViewById(R.id.tvResState);
        tvResHint = findViewById(R.id.tvResHint);
        tvProfileHead = findViewById(R.id.tvProfileHead);
        profileTable = findViewById(R.id.profileTable);
        btnSaveProfile = findViewById(R.id.btnSaveProfile);
        btnClearProfile = findViewById(R.id.btnClearProfile);
        swEnable = findViewById(R.id.swEnable);
        btnResToggle = findViewById(R.id.btnResToggle);
        swResAsk = findViewById(R.id.swResAsk);
        swResAutoReset = findViewById(R.id.swResAutoReset);
        swResAutoMatch = findViewById(R.id.swResAutoMatch);
        rgTabs = findViewById(R.id.rgTabs);
        panelAuto = findViewById(R.id.panelAuto);
        panelRes = findViewById(R.id.panelRes);
        panelState = findViewById(R.id.panelState);
        spPortrait = findViewById(R.id.spPortrait);
        spLandscape = findViewById(R.id.spLandscape);
        spScreenDir = findViewById(R.id.spScreenDir);
        spBootMode = findViewById(R.id.spBootMode);
        cbFixToUser = findViewById(R.id.cbFixToUser);
        etResW = findViewById(R.id.etResW);
        etResH = findViewById(R.id.etResH);
        etResDpi = findViewById(R.id.etResDpi);
        tvFoldState = findViewById(R.id.tvFoldState);
        spPeakHz = findViewById(R.id.spPeakHz);
        spMinHz = findViewById(R.id.spMinHz);
        tvHzState = findViewById(R.id.tvHzState);

        // ---- 快捷操作（授权与状态选项卡）
        swQASync = findViewById(R.id.swQASync);
        swQACoverRot = findViewById(R.id.swQACoverRot);

        // 无线调试；v4.22 从开关改成一次性按钮（用户提的）。
        // ⚠ 它写的是系统那个键（Settings.Global.ADB_WIFI_ENABLED）。v4.21 起整条路不再借
        //   Shizuku：先直接写一把，网络没登记过才展开内屏让无障碍点掉确认框（见 AdbWifi）。
        //   这条路最长要好几秒、里面还有 sleep，所以丢后台线程，回来只报结果。
        btnQAAdbWifi = findViewById(R.id.btnQAAdbWifi);
        if (btnQAAdbWifi != null) {
            btnQAAdbWifi.setOnClickListener(v -> openAdbWifiOnce());
        }
        // 一键投屏（v4.31）：切双屏 + 打开投屏控制 + 全屏 + 直接开始投屏，一步做完
        findViewById(R.id.btnQACast).setOnClickListener(v -> onOneKeyCast());
        // 「折叠/展开模式切换」那个按钮去掉了 —— 折叠模式整段搬到了本页下面，
        // 同一页里再留一个入口纯属重复。
        findViewById(R.id.btnQAOneKey).setOnClickListener(v -> onOneKeyDual());

        panelDual = findViewById(R.id.panelDual);
        tvDualState = findViewById(R.id.tvDualState);
        tvSubState = findViewById(R.id.tvSubState);
        tvDexInfo = findViewById(R.id.tvDexInfo);

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
        } catch (Throwable ignored) {
        }

        // ---- 选项卡（界面顺序：0=授权与状态，1=自动旋转，2=内屏管理，3=双屏）
        rgTabs.setOnCheckedChangeListener((group, checkedId) ->
                showTab(checkedId == R.id.tabAuto ? 1
                        : checkedId == R.id.tabRes ? 2
                        : checkedId == R.id.tabDual ? 3 : 0));

        // ---- 右上角的「Beta 功能」入口 → BetaActivity（黑底，另一套界面）
        bindBetaEntry();

        // ---- 标题旁的版本胶囊 → 「版本与更新」页
        bindVersionEntry();

        // ---- 最近任务里的名字用英文名。
        // 桌面图标名由 manifest 的 android:label 决定（中文），最近任务卡片走的是
        // TaskDescription，两处互不影响 —— 这样"桌面中文名、后台英文名"不需要两套 label。
        // 只在最近任务里生效，不动桌面、也不动「设置 → 应用」里那个名字。
        setTaskDescription(new ActivityManager.TaskDescription(getString(R.string.app_name_en)));

        // ---- 界面上所有「ⓘ」详情图标。
        // v2.3 起解释性文字不再常驻在开关/按钮下面（铺开太长没人看），一律收进 ⓘ，
        // 点一下弹对话框。正文都在 strings.xml 的 info_* 里。
        bindInfo(R.id.iSwEnable, "自动方向同步", R.string.info_sync);
        bindInfo(R.id.iQASync, "自适应旋转", R.string.info_sync);
        bindInfo(R.id.iQAAdbWifi, "无线调试", R.string.info_adb_wifi);
        bindInfo(R.id.iQACoverRot, "外屏自动旋转", R.string.info_cover_autorot);
        bindInfo(R.id.iQACast, "一键投屏", R.string.info_qa_cast);
        bindInfo(R.id.iSwLockPhone, "屏幕方向", R.string.info_lock_phone);
        bindInfo(R.id.iBootMode, "开机选项", R.string.info_boot_mode);
        bindInfo(R.id.iFixToUser, "强制忽略应用申请的方向", R.string.info_fix_to_user);
        bindInfo(R.id.iCalib, "手动校准", R.string.info_calib);
        bindInfo(R.id.iRepair, "修复画面", R.string.info_repair);
        bindInfo(R.id.iProfile, "保存参数设置", R.string.info_profile);
        bindInfo(R.id.iResFields, "分辨率与 DPI", R.string.info_res_fields);
        bindInfo(R.id.iRefreshRate, "刷新率", R.string.info_refresh_rate);
        bindInfo(R.id.iResAutoMatch, "分辨率自动加载", R.string.info_res_automatch);
        bindInfo(R.id.iResTiming, "接管时机", R.string.info_res_timing);
        bindInfo(R.id.iFold, "折叠/展开模式切换", R.string.info_fold);
        bindInfo(R.id.iShizuku, "Shizuku", R.string.info_shizuku);
        bindInfo(R.id.iRealtime, "实时状态", R.string.info_realtime);
        // 双屏模式那条说明从独立的 ⓘ 搬到「副屏状态」行尾的文字入口上；
        // 副屏管理的小标题撤了，它那条说明就挂在这儿（跟副屏状态挨着）
        bindInfo(R.id.tvAboutDual, "关于双屏模式", R.string.info_onekey);
        bindInfo(R.id.iSubPanel, "副屏", R.string.info_beta_sub);
        bindInfo(R.id.iPseudo, "副屏桌面上的伪应用", R.string.info_pseudo);
        bindInfo(R.id.iDex, "DeX 模式", R.string.info_dex);

        // ---- 主开关（「自动旋转」选项卡）
        swEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            setSyncEnabled(isChecked);
        });

        // ---- 同一个开关在「授权与状态」的快捷操作里再放一份，两边必须完全同步。
        // 共用 setSyncEnabled + syncQuickSyncSwitch，免得两处各写一套逻辑日后跑偏。
        swQASync.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            setSyncEnabled(isChecked);
        });

        // ---- 外屏自动旋转（v4.23）。跟上面那个「自适应旋转」是两件事：那个管的还是内屏那边的
        //      方向同步，这个管封面屏 —— 外屏原生不跟手机转，靠加速度计判横竖 + 下发旋转命令。
        //      下发要发 shell，丢后台。
        //      ⚠ 先 setChecked 再挂监听，回读那一下用 binding 压住（本类通例）。
        if (swQACoverRot != null) {
            swQACoverRot.setChecked(CoverAutoRot.on(this));
            swQACoverRot.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (binding) {
                    return;
                }
                new Thread(() -> {
                    final String err = CoverAutoRot.set(MainActivity.this, isChecked);
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            return;
                        }
                        if (err != null) {
                            toast(err);
                        } else {
                            toast(isChecked ? "外屏自动旋转：开" : "外屏自动旋转：关");
                        }
                        syncQuickCoverRotSwitch();
                    });
                }, "cover-autorot-sw").start();
            });
        }

        // ---- 角度
        bindSpinner(spPortrait, ROT_PORT, LAB_PORT, prefs.getPortraitAngle(), prefs::setPortraitAngle);
        bindSpinner(spLandscape, ROT_ALL, LAB_ALL, prefs.getLandscapeAngle(), prefs::setLandscapeAngle);

        // ---- 屏幕方向
        // 原来是「关闭手机自动旋转，锁定横向」一个开关，现在摊成三档。落盘还是那两个老字段，
        // 所以「保存参数设置」里「自动旋转」那一行、服务里那条 settings 命令都不必改。
        bindSpinner(spScreenDir, DIR_VALS, DIR_LABS, dirValue(), this::applyScreenDir);

        // ---- 开机选项（在「授权与状态」页）
        bindSpinner(spBootMode, BOOT_VALS, BOOT_LABS, prefs.getBootMode(), prefs::setBootMode);

        cbFixToUser.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            prefs.setFixToUserRotation(isChecked);
            onSettingChanged();
        });

        // ---- 分辨率输入
        etResW.setText(String.valueOf(prefs.getResWidth()));
        etResH.setText(String.valueOf(prefs.getResHeight()));
        etResDpi.setText(String.valueOf(prefs.getResDensity()));
        TextWatcher resWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                saveResFields();
            }
        };
        etResW.addTextChangedListener(resWatcher);
        etResH.addTextChangedListener(resWatcher);
        etResDpi.addTextChangedListener(resWatcher);

        btnResToggle.setOnClickListener(v -> {
            saveResFields();
            boolean wantOn = !prefs.isResolutionEnabled();
            if (wantOn && !resFieldsValid()) {
                toast("宽高至少 " + MIN_RES + "，请先修正分辨率");
                return;
            }
            setResTakeover(wantOn, true);
        });

        swResAsk.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            prefs.setAskOnConnect(isChecked);
        });

        swResAutoReset.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            prefs.setAutoResetSize(isChecked);
        });

        swResAutoMatch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            prefs.setAutoMatchExt(isChecked);
            toast(isChecked ? "接入便携屏时会自动套用它的分辨率（横屏转竖屏）"
                    : "已关闭自动套用，将一直使用你填的分辨率");
        });

        // 手动读一次当前接着的便携屏的分辨率填进输入框
        findViewById(R.id.btnResFromExt).setOnClickListener(v -> fillResFromExt());

        findViewById(R.id.btnApplyRes).setOnClickListener(v -> {
            saveResFields();
            if (!resFieldsValid()) {
                toast("宽高至少 " + MIN_RES);
                return;
            }
            if (!ShellRunner.isReady()) {
                toast("请先完成 Shizuku 授权");
                return;
            }
            setResTakeover(true, true);
        });

        findViewById(R.id.btnResetRes).setOnClickListener(v -> {
            if (!ShellRunner.isReady()) {
                toast("请先完成 Shizuku 授权");
                return;
            }
            RotationService.resetSizeNow(this);
            toast("正在还原内屏原生分辨率…");
        });

        // ---- 刷新率（紧跟在「分辨率与 DPI」下面那一节）
        setupRefreshRate();

        findViewById(R.id.btnOverlay).setOnClickListener(v -> requestOverlayPermission());

        findViewById(R.id.btnTestPrompt).setOnClickListener(v -> RotationService.testPrompt(this));

        // ---- 折叠模式（直接覆盖系统的 DeviceStateManager）
        findViewById(R.id.btnFoldToggle).setOnClickListener(v -> onFoldToggle());
        findViewById(R.id.btnStateAuto).setOnClickListener(v -> onDeviceStateReset());

        // ---- 双屏选项卡：一键把外屏变成副屏桌面
        // 这里刻意只留一个按钮：切并发、点亮外屏、投副屏桌面是一条龙做完的；
        // 挑应用已经在副屏桌面里点了，不需要再放一个"选应用"的入口。
        findViewById(R.id.btnDualOneKey).setOnClickListener(v -> onOneKeyDual());
        // 副屏管理（v4.11 从 Beta 实验室搬来）。三个伪应用开关的缺省值跟着
        // AppRepo.Item.pseudoDefaultOn 走，两边必须一致 —— 那边注释写了为什么
        // 副屏触控板默认关、投屏控制与状态展示柜默认开。
        findViewById(R.id.btnSubOpen).setOnClickListener(v -> doOpenSub());
        // v4.17：开关改成按钮式，每个按钮的底色跟着它那个伪应用的图标色走。
        // 色值取 drawable/ic_pseudo_*.xml 里的 fillColor，改图标要同步改这里。
        bindPseudoToggle(R.id.swPseudoPad, AppRepo.PSEUDO_TOUCHPAD, false, 0xFF2F7DFF);
        bindPseudoToggle(R.id.swPseudoCast, AppRepo.PSEUDO_CAST, true, 0xFF8E6BE8);
        bindPseudoToggle(R.id.swPseudoStatus, AppRepo.PSEUDO_STATUS, true, 0xFF21BFD6);
        bindPseudoToggle(R.id.swPseudoPlayer, AppRepo.PSEUDO_PLAYER, true, 0xFFEC407A);
        findViewById(R.id.btnDexStart).setOnClickListener(v -> startDex());
        // 这一页那个「快捷小组件」按钮撤掉了（用户："按钮及其相关说明去掉"）。
        // pinWidget() 留着 —— 小组件本身没删，从系统的组件列表里照样钉得上，
        // 以后想再给个入口直接接回来就行。

        // ---- 授权按钮
        findViewById(R.id.btnGrant).setOnClickListener(v -> {
            refreshShizukuState();
            if (!ShellRunner.isBinderAlive()) {
                toast("Shizuku 服务未运行，请先在 Shizuku App 里启动服务");
                return;
            }
            if (ShellRunner.hasPermission()) {
                toast("已经拥有权限");
                if (prefs.shouldKeepRunning()) {
                    RotationService.start(this);
                }
                return;
            }
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
            } catch (Throwable t) {
                toast("请求授权失败：" + t);
            }
        });

        findViewById(R.id.btnShizuku).setOnClickListener(v -> openShizuku());

        // ---- 手动测试
        ((Button) findViewById(R.id.btn0)).setOnClickListener(v -> manual(0));
        ((Button) findViewById(R.id.btn90)).setOnClickListener(v -> manual(1));
        ((Button) findViewById(R.id.btn180)).setOnClickListener(v -> manual(2));
        ((Button) findViewById(R.id.btn270)).setOnClickListener(v -> manual(3));
        ((Button) findViewById(R.id.btnFree)).setOnClickListener(v -> manual(-1));

        // ---- 修复画面
        findViewById(R.id.btnRepair).setOnClickListener(v -> {
            if (!ShellRunner.isReady()) {
                toast("请先完成 Shizuku 授权");
                return;
            }
            RotationService.repair(this);
            toast("已对外接屏执行一次完整重排");
        });

        // ---- 按外接屏保存设置
        btnSaveProfile.setOnClickListener(v -> onSaveProfile());
        btnClearProfile.setOnClickListener(v -> onClearProfile());

        // ---- 回填已有设置
        swEnable.setChecked(prefs.isEnabled());
        swResAsk.setChecked(prefs.isAskOnConnect());
        swResAutoReset.setChecked(prefs.isAutoResetSize());
        swResAutoMatch.setChecked(prefs.isAutoMatchExt());
        cbFixToUser.setChecked(prefs.isFixToUserRotation());
        updateVisibility();
        syncQuickSyncSwitch();
        syncQuickCoverRotSwitch();

        // 回到上次停留的选项卡（0=授权与状态，1=自动旋转，2=内屏管理，3=双屏）
        int lastTab = prefs.getLastTab();
        rgTabs.check(lastTab == 1 ? R.id.tabAuto
                : lastTab == 2 ? R.id.tabRes
                : lastTab == 3 ? R.id.tabDual : R.id.tabState);

        binding = false;

        updateResToggleUi();
        refreshSubState();
        refreshShizukuState();

        // 第一次打开这个应用时，先把「这套东西能干什么」说清楚（见 maybeShowIntro）
        maybeShowIntro();
    }

    // ------------------------------------------------------------- ⓘ / 版本入口

    /**
     * 给一个「ⓘ」图标挂上点击 → 弹对话框显示详情。
     *
     * <p>界面上所有解释性文字都走这条路径：布局里只留操作名，细节按需展开。
     * 找不到控件就直接跳过（布局改动时不至于把整个界面崩掉）。
     *
     * @param iconId    ⓘ 图标（布局里 id 以 i 开头）的控件 id
     * @param title     对话框标题，一般就是那个操作的名称
     * @param detailRes 正文，放在 strings.xml 的 info_* 里
     */
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

    /**
     * 右上角的「Beta 功能」入口。
     *
     * <p>它长得像第五个选项卡，但并不切换本页的面板 —— 点它是进另一个界面
     * （{@link BetaActivity}，整块黑底，内部分三页）。所以不要把它塞进 rgTabs，
     * 那会把上面四个选项卡压窄，而且语义上也不是一回事。
     */
    private void bindBetaEntry() {
        View btn = findViewById(R.id.btnBeta);
        if (btn == null) {
            return;
        }
        btn.setOnClickListener(v -> startActivity(new Intent(this, BetaActivity.class)));
    }

    /** 标题旁的版本胶囊：显示当前版本号，点一下进「版本与更新」。 */
    private void bindVersionEntry() {
        TextView chip = findViewById(R.id.btnVersion);
        if (chip == null) {
            return;
        }
        String versionName = "—";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) {
                versionName = pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        // 版本号是从包信息读的，不是写死的 —— 改 build.gradle 之后这里自动跟着变，
        // 不会出现"界面写着 2.2、实际装的是 2.3"这种对不上的情况。
        chip.setText("v" + versionName + " · " + getString(R.string.version_entry));
        chip.setOnClickListener(v ->
                startActivity(new Intent(this, ChangelogActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuState();
        refreshSubState();
        refreshDualState(true);
        refreshProfileUi();
        // 自愈：开关是开着的、Shizuku 也拿到了权限，但服务没在跑
        // （例如刚覆盖安装完、或系统回收过进程）→ 打开界面就把它拉起来
        if (prefs.shouldKeepRunning() && ShellRunner.isReady() && !RotationService.isRunning()) {
            RotationService.start(this);
        }
        // 下拉栏或别处改过「锁手机方向」之后，外屏面板上那个按钮的显示要跟上
        PanelWidgetProvider.updateAll(this);
        handler.post(uiTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(uiTick);
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 分辨率

    private static int parseInt(String s, int def) {
        try {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) {
                return def;
            }
            return Integer.parseInt(t);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 把三个输入框里的值收进设置。低于下限的值先不落盘，等用户改对了再说。 */
    private void saveResFields() {
        if (binding) {
            return;
        }
        int w = parseInt(etResW.getText().toString(), prefs.getResWidth());
        int h = parseInt(etResH.getText().toString(), prefs.getResHeight());
        int dpi = parseInt(etResDpi.getText().toString(), 0);
        if (w < MIN_RES || h < MIN_RES) {
            return;
        }
        if (w > 10000 || h > 10000) {
            return;
        }
        if (dpi < 0 || dpi > 2000) {
            dpi = 0;
        }
        prefs.setResSize(w, h);
        prefs.setResDensity(dpi);
    }

    private boolean resFieldsValid() {
        int w = parseInt(etResW.getText().toString(), 0);
        int h = parseInt(etResH.getText().toString(), 0);
        return w >= MIN_RES && h >= MIN_RES && w <= 10000 && h <= 10000;
    }

    /**
     * 打开/关闭内屏分辨率接管。
     *
     * @param on        true = 开启，false = 关闭
     * @param applyNow  开启时是否立刻把分辨率写上内屏。
     *                  界面上点开关 = true（马上生效，用户能立刻看到）；
     *                  只是恢复设置状态时可以传 false。
     */
    private void setResTakeover(boolean on, boolean applyNow) {
        prefs.setResolutionEnabled(on);
        ensureNotificationPermission();
        if (on) {
            RotationService.start(this);
            if (applyNow) {
                if (!ShellRunner.isReady()) {
                    toast("已开启，但还没拿到 Shizuku 授权，授权后会自动生效");
                } else {
                    RotationService.applySizeNow(this);
                    toast("已开启：内屏 → " + effSizeText()
                            + (prefs.isAutoMatchExt() ? "（接入便携屏时会自动按它的分辨率调整）" : ""));
                }
            }
        } else {
            RotationService.resetSizeNow(this);
            if (!prefs.shouldKeepRunning()) {
                RotationService.stop(this);
            }
            toast("已关闭分辨率接管，内屏还原原生");
        }
        updateResToggleUi();
        updateInfo();
    }

    /** 实际会写进内屏的尺寸文字（已转竖屏） */
    private String effSizeText() {
        int[] p = ScreenSizeUtil.portrait(prefs.getResWidth(), prefs.getResHeight());
        return p[0] + "×" + p[1];
    }

    /** 把开关按钮的画面和设置同步：开=绿底，关=灰底，文字里带上当前分辨率 */
    private void updateResToggleUi() {
        if (btnResToggle == null) {
            return;
        }
        boolean on = prefs.isResolutionEnabled();
        String text = on
                ? "内屏分辨率接管：已开启（" + effSizeText() + "）\n点击关闭"
                : "内屏分辨率接管：已关闭\n点击开启（" + effSizeText() + "）";
        if (!text.contentEquals(btnResToggle.getText())) {
            btnResToggle.setText(text);
        }
        btnResToggle.setBackgroundColor(on ? COLOR_ON : COLOR_OFF);
        btnResToggle.setTextColor(0xFFFFFFFF);
    }

    /** 读一次当前接着的便携屏的分辨率，转成竖屏后填进输入框 */
    private void fillResFromExt() {
        List<Display> ext = DisplayUtil.externalDisplays(dm);
        if (ext.isEmpty()) {
            toast("现在没有检测到便携屏，接上后再点这里");
            return;
        }
        // 多块屏时取像素最多的那块
        int[] best = null;
        Display bestD = null;
        for (Display d : ext) {
            int[] s = DisplayUtil.suggestedInternalSize(d);
            if (s[0] <= 0 || s[1] <= 0) {
                continue;
            }
            if (best == null || (long) s[0] * s[1] > (long) best[0] * best[1]) {
                best = s;
                bestD = d;
            }
        }
        if (best == null) {
            toast("读不到这块屏的分辨率，请手动填写");
            return;
        }
        int[] n = DisplayUtil.nativeSize(bestD);
        binding = true;
        etResW.setText(String.valueOf(best[0]));
        etResH.setText(String.valueOf(best[1]));
        binding = false;
        prefs.setResSize(best[0], best[1]);
        toast("已按便携屏填入 " + best[0] + "×" + best[1]
                + (n[0] > n[1] ? "（原生 " + n[0] + "×" + n[1] + "，已转竖屏）" : ""));
        updateResToggleUi();
    }

    /** 「显示在其他应用上层」：有它才能在后台弹对话框 */
    private void requestOverlayPermission() {
        boolean granted = false;
        try {
            granted = Settings.canDrawOverlays(this);
        } catch (Throwable ignored) {
        }
        if (granted) {
            toast("已经具备弹窗权限");
            return;
        }
        if (!ShellRunner.isReady()) {
            openOverlaySettings();
            return;
        }
        toast("正在尝试通过 Shizuku 授予…");
        final String pkg = getPackageName();
        final int uid = android.os.Process.myUid();
        new Thread(() -> {
            ShellRunner.run("appops set --uid " + uid + " SYSTEM_ALERT_WINDOW allow");
            ShellRunner.run("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            runOnUiThread(() -> {
                boolean ok = false;
                try {
                    ok = Settings.canDrawOverlays(this);
                } catch (Throwable ignored) {
                }
                if (ok) {
                    toast("已授予弹窗权限");
                } else {
                    openOverlaySettings();
                }
            });
        }, "extrot-overlay").start();
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("请打开「允许显示在其他应用上层」");
        } catch (Throwable t) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                toast("请打开「允许显示在其他应用上层」");
            } catch (Throwable t2) {
                toast("打不开设置页：" + t2);
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 「刷新率」小节：最高 / 最低两个下拉 + 应用 / 交回系统 */
    private void setupRefreshRate() {
        if (spPeakHz == null || spMinHz == null) {
            return;
        }
        float[] modes = RefreshRate.supported(this);
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = String.format(Locale.US, "%.0f Hz", modes[i]);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPeakHz.setAdapter(ad);
        spMinHz.setAdapter(ad);
        spPeakHz.setSelection(nearestIndex(modes, RefreshRate.readPeak(this)), false);
        spMinHz.setSelection(nearestIndex(modes, RefreshRate.readMin(this)), false);

        findViewById(R.id.btnApplyHz).setOnClickListener(v -> {
            float peak = modes[spPeakHz.getSelectedItemPosition()];
            float min = modes[spMinHz.getSelectedItemPosition()];
            String err = RefreshRate.apply(this, peak, min);
            toast(err != null ? err
                    : String.format(Locale.US, "已接管刷新率：%.0f ~ %.0f Hz", min, peak));
            refreshRateState();
        });

        findViewById(R.id.btnResetHz).setOnClickListener(v -> {
            String err = RefreshRate.reset(this);
            toast(err != null ? err : "已交回系统，刷新率重新随系统自适应");
            refreshRateState();
        });

        refreshRateState();
    }

    /** 把下拉拨到某个值所在的那一档；系统值不在支持表里（或没设过）就留在第一档 */
    private static int nearestIndex(float[] modes, float hz) {
        for (int i = 0; i < modes.length; i++) {
            if (Math.abs(modes[i] - hz) < 0.5f) {
                return i;
            }
        }
        return 0;
    }

    /** 刷新率那一行的状态文字：系统当前那对值 + 内屏在不在位 */
    private void refreshRateState() {
        if (tvHzState == null) {
            return;
        }
        String now = "系统当前：" + hzText(RefreshRate.readMin(this))
                + " ~ " + hzText(RefreshRate.readPeak(this)) + " Hz";
        tvHzState.setText(now + (RefreshRate.innerActive(this)
                ? " · 内屏在位" : " · 内屏不在位，高刷档位不生效"));
    }

    /** 没设过的值（-1）在界面上显示成破折号，别显示成 -1 */
    private static String hzText(float v) {
        return v < 0 ? "—" : String.format(Locale.US, "%.0f", v);
    }

    private void bindSpinner(Spinner sp, int[] values, String[] labels, int current, IntSetter setter) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                idx = i;
            }
        }
        sp.setSelection(idx, false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding) {
                    return;
                }
                setter.set(values[position]);
                onSettingChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void updateVisibility() {
        // 「同步模式」删掉之后，两个落点角度没有"另一种模式"要避让了，常驻显示。
        // ⚠ 条件从"固定角度模式"改成"手机方向被锁住"。
        // 它管的是外接屏要不要理会应用自己申请的方向，而"用户方向"正是手机那一边锁的那个 ——
        // 手机自己在自动旋转时，这项没有可依的方向，摆出来只会让人以为它此刻在管着事。
        findViewById(R.id.rowFixToUser).setVisibility(
                prefs.isLockPhoneRotation() ? View.VISIBLE : View.GONE);
    }

    /** 切换选项卡。索引按界面顺序：0=授权与状态，1=自动旋转，2=内屏管理，3=双屏 */
    private void showTab(int idx) {
        if (panelAuto == null) {
            return;
        }
        panelState.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        panelAuto.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        panelRes.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        if (panelDual != null) {
            panelDual.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        }
        prefs.setLastTab(idx);
        // 折叠模式整段搬到了本页（idx 0），进来就该看到真状态，
        // 不必等用户去点一下按钮才刷。
        if (idx == 0) {
            refreshFoldState();
        }
        if (idx == 3) {
            refreshDualState(true);
            refreshSubState();
        }
    }

    /** 系统当前的「自动旋转」开关是不是开着的 */
    private boolean sysAutoRotate() {
        try {
            return Settings.System.getInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
        } catch (Throwable t) {
            return false;   // 读不到就当作"没被改过"，别误关用户自己的开关
        }
    }

    /**
     * 盯着「锁手机方向」和系统实际状态对不对得上。
     *
     * <p>用户在系统下拉菜单里把「自动旋转」打开之后，本应用的开关还停在"已开启"，
     * 应用与系统对当前方向的理解就会分叉，镜像几何跟着乱。这里的选择是尊重用户：
     * 既然他自己开了自动旋转，就把本应用这个开关也同步关掉（而不是把系统锁回去）。
     */
    private void syncPhoneLockSwitch() {
        if (spScreenDir == null) {
            return;
        }
        if (!prefs.isLockPhoneRotation() || !sysAutoRotate()
                || System.currentTimeMillis() - lockToggleAt < 2500L) {
            lockSyncStrikes = 0;
            return;
        }
        // 连续两次都读到才算，避免正好落在命令落盘的间隙上
        if (++lockSyncStrikes < 2) {
            return;
        }
        lockSyncStrikes = 0;
        prefs.setLockPhoneRotation(false);
        binding = true;
        spScreenDir.setSelection(indexOf(DIR_VALS, DIR_AUTO), false);
        binding = false;
        updateVisibility();
        toast("检测到系统已恢复「自动旋转」，本应用的手机方向锁定已同步关闭");
    }

    /** 「屏幕方向」此刻是哪一档（把 (lockPhone, phoneRotation) 两个字段折成一个值） */
    private int dirValue() {
        if (!prefs.isLockPhoneRotation()) {
            return DIR_AUTO;
        }
        // 0° = 竖屏，其余（90°/270°）都算横屏 —— 界面只给两个锁定档，
        // 档案里万一存着 270° 也照样落在"横屏锁定"上，不会被抹掉。
        return prefs.getPhoneRotation() == 0 ? DIR_PORT : DIR_LAND;
    }

    /** 选了「屏幕方向」之后，落盘并立刻下发（选「自动旋转」就是把控制权还给系统） */
    private void applyScreenDir(int v) {
        lockToggleAt = System.currentTimeMillis();
        lockSyncStrikes = 0;
        if (v == DIR_AUTO) {
            prefs.setLockPhoneRotation(false);
            RotationService.restorePhoneRotation(this);
            if (!prefs.shouldKeepRunning()) {
                RotationService.stop(this);
            }
            updateVisibility();
            toast("已交还给系统：手机自动旋转恢复");
            return;
        }
        prefs.setLockPhoneRotation(true);
        prefs.setPhoneRotation(v == DIR_PORT ? 0 : 1);
        if (!ShellRunner.isReady()) {
            toast("已记下设置，但还没拿到 Shizuku 授权，授权后会自动锁上");
        } else {
            toast(v == DIR_PORT ? "已开启：关闭手机自动旋转并锁定竖屏"
                    : "已开启：关闭手机自动旋转并锁定横屏");
        }
        RotationService.lockPhoneNow(this);
        updateVisibility();
    }

    private void onSettingChanged() {
        if (prefs.isEnabled()) {
            RotationService.start(this);
        }
        // 外屏面板上有两个开关是"画"上去的，设置一改就顺手刷一遍
        PanelWidgetProvider.updateAll(this);
    }

    /**
     * 打开 / 关闭「自适应旋转」（外接屏跟随手机的横竖屏）。
     *
     * <p>关闭时顺手把手机自己的「自动旋转」还回去：服务很可能还要为「内屏分辨率接管」继续常驻，
     * 从而走不到 ACTION_STOP，手机被关掉自动旋转、方向钉死在横屏的状态就会一直留着 ——
     * 用户看到的是「手机一直是横屏，怎么摆都不转」。
     *
     * <p>但「锁手机方向」现在是独立开关：它自己还开着时只关同步，锁保持不动 —— 用户要的就是手机不自动转。
     */
    private void setSyncEnabled(boolean on) {
        prefs.setEnabled(on);
        if (on) {
            ensureNotificationPermission();
            RotationService.start(this);
        } else {
            if (!prefs.isLockPhoneRotation()) {
                RotationService.restorePhoneRotation(this);
            }
            if (!prefs.shouldKeepRunning()) {
                RotationService.stop(this);
            }
        }
        syncQuickSyncSwitch();
        syncQuickCoverRotSwitch();
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 把界面上的开关 / 单选 / 下拉框拉回与「设置」一致。
     *
     * <p>为什么需要周期性地做这件事：这些设置不只有界面在改 —— 服务那头也会改
     * （最典型的是接上外接屏时自动套用它保存过的档案）。界面不回读的话，
     * 就会出现"设置里明明关了，开关还画成开"这种自相矛盾的状态。
     *
     * <p>回写时先压住 {@code binding}，否则会被监听器当成用户操作再存一遍，
     * 顺便还会弹一堆 Toast。
     */
    private void syncFromPrefs() {
        if (swEnable == null) {
            return;
        }
        boolean b = binding;
        binding = true;
        try {
            if (swEnable.isChecked() != prefs.isEnabled()) {
                swEnable.setChecked(prefs.isEnabled());
            }
            if (swResAutoMatch.isChecked() != prefs.isAutoMatchExt()) {
                swResAutoMatch.setChecked(prefs.isAutoMatchExt());
            }
            if (swResAsk.isChecked() != prefs.isAskOnConnect()) {
                swResAsk.setChecked(prefs.isAskOnConnect());
            }
            if (swResAutoReset.isChecked() != prefs.isAutoResetSize()) {
                swResAutoReset.setChecked(prefs.isAutoResetSize());
            }
            if (cbFixToUser.isChecked() != prefs.isFixToUserRotation()) {
                cbFixToUser.setChecked(prefs.isFixToUserRotation());
            }
            int p = indexOf(ROT_PORT, prefs.getPortraitAngle());
            if (spPortrait.getSelectedItemPosition() != p) {
                spPortrait.setSelection(p, false);
            }
            int l = indexOf(ROT_ALL, prefs.getLandscapeAngle());
            if (spLandscape.getSelectedItemPosition() != l) {
                spLandscape.setSelection(l, false);
            }
            int sd = indexOf(DIR_VALS, dirValue());
            if (spScreenDir.getSelectedItemPosition() != sd) {
                spScreenDir.setSelection(sd, false);
            }
            int bm = indexOf(BOOT_VALS, prefs.getBootMode());
            if (spBootMode.getSelectedItemPosition() != bm) {
                spBootMode.setSelection(bm, false);
            }
        } finally {
            binding = b;
        }
        syncQuickSyncSwitch();
        syncQuickCoverRotSwitch();
        updateVisibility();
    }

    /** 让「授权与状态」里的快捷开关与设置保持一致（改值时临时压住回调，避免来回弹） */
    private void syncQuickSyncSwitch() {
        if (swQASync == null) {
            return;
        }
        boolean on = prefs.isEnabled();
        if (swQASync.isChecked() != on) {
            boolean b = binding;
            binding = true;
            swQASync.setChecked(on);
            binding = b;
        }
    }

    /** 同一个道理，「外屏自动旋转」那一颗也去跟实况对一次 */
    private void syncQuickCoverRotSwitch() {
        if (swQACoverRot == null) {
            return;
        }
        boolean on = CoverAutoRot.on(this);
        if (swQACoverRot.isChecked() != on) {
            boolean b = binding;
            binding = true;
            swQACoverRot.setChecked(on);
            binding = b;
        }
    }

    /**
     * 「打开无线调试」按钮（v4.22 从开关改过来）。
     *
     * <p><b>一次性</b>：点一下把系统那个键打开。已经开着就只提示一声、不做任何事
     * —— 它不是开关，没有"关"这一半。
     *
     * <p>这条路最长要好几秒（网络没登记过时得展开内屏、等无障碍点掉确认框、再折回），
     * 必须丢后台；回来只报结果，界面不留状态（真实状态在系统开发者选项里看）。
     */
    private void openAdbWifiOnce() {
        if (AdbWifi.isOn(this)) {
            toast("无线调试已经是开着的");
            return;
        }
        toast("正在打开无线调试…");
        new Thread(() -> {
            final String err = AdbWifi.set(MainActivity.this, true);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                toast(err == null ? "无线调试已打开" : ("没成功：" + err));
            });
        }, "adb-wifi-on").start();
    }

    // ------------------------------------------------------------------ 按外接屏保存设置

    /**
     * 从当前接着的外接屏里挑一块代表。
     *
     * <p>口径统一走 {@link DisplayUtil#largestByPanel}：界面这一栏、服务里"接屏时套用档案"、
     * 以及"自动读面板分辨率"用的必须是**同一块**，三处各写一份迟早会跑偏。
     */
    private Display mainExternal() {
        return DisplayUtil.largestByPanel(DisplayUtil.externalDisplays(dm));
    }

    /**
     * 把旋转角写成角度数字，例如 {@code 90°}。
     *
     * <p>⚠ 不写「横向锁定 / 竖向锁定」：一个词盖住两个角度（0° 和 180° 都叫「横」，
     * 90° 和 270° 都叫「竖」），表格里「已保存 / 当前」两列一比、两边印着同一句话，等于什么都没说。
     * 方向交给行名交代，值直接给角度。
     */
    private static String orient(int rotation) {
        return DisplayUtil.degrees(rotation) + "°";
    }

    /**
     * 「自动旋转」这一格的值：{@code 开} / {@code 横 90°} / {@code 竖 0°} …
     *
     * <p>⚠ 语义跟 {@link #orient} <b>相反</b>，别合并成一个函数：{@code orient()} 说的是
     * <b>外接屏</b>（便携屏面板天生横放，转 90° 画面才变竖），而手机的 90°/270° 是<b>手机自己</b>
     * 被横过来用 —— 两边刚好反着。
     *
     * <p>{@code 开} = <b>没锁</b>：「锁手机方向」这个开关的含义是「<b>关掉</b>手机自动旋转并固定方向」，
     * 所以开关为 false 时才是「开」。
     *
     * <p>⚠ 必须带上角度数字：光写「横向锁定 / 竖向锁定」的话一个词盖着两个角度
     * （「横向」到底是 90° 还是 270° 看不出来）。行名「自动旋转」自己不表方向，所以那个「横 / 竖」留着。
     * 180° / 270° 只可能在旧档案里出现，一律按横屏算。
     */
    private static String phoneRotation(DisplayProfile.Item it) {
        if (!it.lockPhone) {
            return "开";
        }
        int deg = DisplayUtil.degrees(it.phoneRot);
        return ((deg == 90 || deg == 270) ? "横 " : "竖 ") + deg + "°";
    }


    /**
     * 表格里要显示的项，每项是 {@code {行名, 值}}，顺序即显示顺序。
     *
     * <p>名字一律压在 4 个汉字以内 —— 一行装两项、每格只有 50dp 左右，名字长了会被裁掉
     * （见 item_profile_row.xml 的注释）。
     *
     * <p>「方向同步」「自动旋转」照旧<b>不参与接屏时的自动套用</b>
     * （见 {@link DisplayProfile.Item#applyTo}），「已保存」列只是记下你上次为这块屏保存时它们是什么状态。
     *
     * <p>分辨率与 DPI 不在表里，跟在首行那条摘要后面（见 {@link #profileHead}）。
     */
    private static String[][] profileItems(DisplayProfile.Item it) {
        return new String[][]{
                {"方向同步", it.sync ? "开" : "关"},
                {"自动旋转", phoneRotation(it)},
                {"竖屏方向", orient(it.portrait)},
                {"横屏方向", orient(it.landscape)},
                {"DPI", it.resDensity > 0 ? String.valueOf(it.resDensity) : "不改"},
        };
    }

    /**
     * 首行摘要：这块屏是谁 · 面板是多少 · 给它设了多少分辨率 · 这个分辨率存没存。
     *
     * <p>分辨率不再单占一行 —— 面板像素数本来就印在这一行上，紧跟着写一句
     * "设置分辨率 1080×1920"最省地方，也不会和「自动套面板分辨率」那个开关打架。
     */
    private static String profileHead(Display d, DisplayProfile.Item cur, DisplayProfile.Item saved) {
        String res = cur.resEnabled ? (cur.resW + "×" + cur.resH) : "不改";
        boolean same = saved != null
                && saved.resEnabled == cur.resEnabled
                && saved.resW == cur.resW
                && saved.resH == cur.resH;
        return DisplayProfile.label(d) + " · 设置分辨率 " + res + (same ? "（已保存）" : "（未保存）");
    }

    /**
     * 去档案里按行名取值。
     *
     * <p>**不能按下标对齐**：行名以后还可能增删（v4.13 就删掉了「同步模式」那一行），
     * 按下标取的话删一行就整体错位，会拿"横屏方向"的当前值去比"DPI"的存档值。
     * 所以按行名找，找不到就回一个破折号。
     */
    private static String savedValue(String[][] items, String name) {
        if (items == null) {
            return "—";
        }
        for (String[] kv : items) {
            if (kv[0].equals(name)) {
                return kv[1];
            }
        }
        return "—";
    }

    /**
     * 重建「保存参数设置」那张表。
     *
     * <p>形状是"**一行两项**、每项都有 `已保存` 与 `当前` 两列"。v2.6 那版一行一项、
     * 一共十行，竖着占掉大半屏还看不到底；现在 3 行看完，而"哪项改过还没存"照样
     * 一眼可见 —— 两列不一样的就是。
     *
     * <p>没接外接屏时整张表收起，只留一句提示 + 那排按钮置灰（那时也存不了）。
     */
    private void refreshProfileUi() {
        if (tvProfileHead == null || profileTable == null) {
            return;
        }
        Display d = mainExternal();
        if (d == null) {
            tvProfileHead.setText("未检测到外接屏 —— 接上便携屏后，这里会显示它的参数");
            profileTable.setVisibility(View.GONE);
            btnSaveProfile.setEnabled(false);
            btnClearProfile.setEnabled(false);
            return;
        }
        profileTable.setVisibility(View.VISIBLE);
        btnSaveProfile.setEnabled(true);

        DisplayProfile.Item saved = DisplayProfile.load(this, DisplayProfile.signature(d));
        btnClearProfile.setEnabled(saved != null);

        DisplayProfile.Item cur = DisplayProfile.Item.from(prefs);
        tvProfileHead.setText(profileHead(d, cur, saved));

        String[][] curItems = profileItems(cur);
        String[][] oldItems = saved == null ? null : profileItems(saved);

        // 内容每秒都可能变，整表重建最省事 ——
        // 也免去了维护"第几个子 View 对应第几项设置"这层映射
        profileTable.removeAllViews();
        String[] head = {"设置项", "已保存", "当前"};
        profileTable.addView(profileRow(head, head, true));
        for (int i = 0; i < curItems.length; i += 2) {
            String[] a = item(curItems[i], oldItems);
            String[] b = (i + 1 < curItems.length) ? item(curItems[i + 1], oldItems) : null;
            profileTable.addView(profileRow(a, b, false));
        }
    }

    /** 把一项 {@code {行名, 值}} 配上它在档案里的值，凑成一行三格。 */
    private static String[] item(String[] curItem, String[][] oldItems) {
        return new String[]{curItem[0], savedValue(oldItems, curItem[0]), curItem[1]};
    }

    /**
     * 一行两栏，每栏三格（行名 + 已保存 + 当前）；{@code b} 为 null 时右半留空。
     *
     * <p>行模板在 {@code res/layout/item_profile_row.xml}，这里只填内容 ——
     * 字号、等宽字、权重都留在 XML 里，和界面其它部分一个来源。
     */
    private View profileRow(String[] a, String[] b, boolean header) {
        View row = getLayoutInflater().inflate(R.layout.item_profile_row, profileTable, false);
        fillProfileCell(row, R.id.cellName, R.id.cellSaved, R.id.cellNow, a, header);
        if (b == null) {
            row.findViewById(R.id.cellName2).setVisibility(View.GONE);
            row.findViewById(R.id.cellSaved2).setVisibility(View.GONE);
            row.findViewById(R.id.cellNow2).setVisibility(View.GONE);
        } else {
            fillProfileCell(row, R.id.cellName2, R.id.cellSaved2, R.id.cellNow2, b, header);
        }
        return row;
    }

    /** 填一栏三格；表头压暗一点，和下面的数据行分开。 */
    private void fillProfileCell(View row, int nameId, int savedId, int nowId,
                                 String[] v, boolean header) {
        TextView name = row.findViewById(nameId);
        TextView saved = row.findViewById(savedId);
        TextView now = row.findViewById(nowId);
        name.setText(v[0]);
        saved.setText(v[1]);
        now.setText(v[2]);
        if (header) {
            int hint = 0x99000000;
            name.setTextColor(hint);
            saved.setTextColor(hint);
            now.setTextColor(hint);
        }
    }

    private void onSaveProfile() {
        Display d = mainExternal();
        if (d == null) {
            toast("现在没接外接屏，没法保存");
            return;
        }
        String sig = DisplayProfile.signature(d);
        if (sig.isEmpty()) {
            toast("这块屏没上报可识别的身份信息，存不了（换根线或换台设备试试）");
            return;
        }
        DisplayProfile.saveFrom(this, sig, DisplayProfile.label(d), d.getDisplayId(), prefs);
        refreshProfileUi();
        toast("已把这一整套设置记到这块屏名下，下次它接上来自动套用");
    }

    private void onClearProfile() {
        Display d = mainExternal();
        if (d == null) {
            toast("现在没接外接屏");
            return;
        }
        String sig = DisplayProfile.signature(d);
        if (!DisplayProfile.exists(this, sig)) {
            toast("这块屏还没保存过");
            return;
        }
        DisplayProfile.clear(this, sig);
        refreshProfileUi();
        toast("已清除这块屏的记录");
    }

    private void manual(int rotation) {
        if (!ShellRunner.isReady()) {
            toast("请先完成 Shizuku 授权");
            return;
        }
        RotationService.manual(this, rotation);
        toast(rotation < 0 ? "已恢复自动旋转" : ("已锁定 " + DisplayUtil.degrees(rotation) + "°"));
    }

    private void refreshShizukuState() {
        if (tvShizuku == null) {
            return;
        }
        String text;
        if (!isShizukuInstalled()) {
            text = "Shizuku：未安装。请先安装 Shizuku（点右边按钮），它负责把 ADB 权限借给本应用。";
        } else if (!ShellRunner.isBinderAlive()) {
            text = "Shizuku：已安装但服务没在运行。打开 Shizuku 启动服务后回到这里。";
        } else if (!ShellRunner.hasPermission()) {
            text = "Shizuku：服务运行中，但本应用还没拿到授权 → 点「授权 / 重新检测」。";
        } else {
            text = "Shizuku：就绪 ✓ 已获得 shell（ADB）权限，可以下发旋转命令了。";
        }
        tvShizuku.setText(text);
    }

    private void updateInfo() {
        if (tvInfo == null) {
            return;
        }
        // 系统那一侧可能把「自动旋转」改回去了，先同步一下开关
        syncPhoneLockSwitch();

        Display def = DisplayUtil.defaultDisplay(dm);
        List<Display> ext = DisplayUtil.externalDisplays(dm);

        StringBuilder sb = new StringBuilder();
        // 这一行原来单独占一块「快捷状态」盒子，现在并进实时状态里，省一块版面
        sb.append("屏幕：").append(DisplayUtil.isOn(def) ? "内屏亮（展开）" : "内屏灭（折叠 / 息屏）")
                .append("　外接屏：").append(ext.isEmpty() ? "未接入" : ext.size() + " 块").append('\n');
        // 方向判断的输入源是「真正亮着的那块内置屏」：折叠机合盖后内屏是灭的，
        // 灭屏的 rotation 停在旧值，用它当输入会得出恒定的"竖屏"（→ 外接屏被锁到竖屏角度）。
        Display phone = DisplayUtil.activePhoneDisplay(dm);
        sb.append("手机方向：");
        if (!DisplayUtil.isOn(phone)) {
            sb.append("读不到（屏幕未点亮，维持上一次判断）");
        } else {
            sb.append(DisplayUtil.isPhonePortrait(phone) ? "竖屏" : "横屏")
                    .append("（依据 ").append(DisplayUtil.kindOf(dm, phone))
                    .append(" rotation=").append(phone.getRotation()).append("）");
        }
        sb.append('\n');

        String ov = DisplayUtil.sizeOverrideInfo(def);
        if (ov == null) {
            sb.append("内屏分辨率：" + DisplayUtil.sizeOf(def) + "（原生，无覆盖）\n");
        } else {
            sb.append("内屏分辨率：⚠ 存在覆盖 ").append(ov);
            if (prefs.isSizeApplied()) {
                sb.append(" ← 本应用设置\n");
            } else {
                sb.append(" ← 别的应用设置，点「还原原生」可清除\n");
            }
        }

        if (ext.isEmpty()) {
            sb.append("便携屏：未检测到\n");
        } else {
            for (Display d : ext) {
                sb.append("便携屏：").append(DisplayUtil.describeFull(d)).append('\n');
                int[] s = DisplayUtil.suggestedInternalSize(d);
                if (s[0] > 0) {
                    sb.append("　　　　建议内屏分辨率：").append(s[0]).append("×").append(s[1]).append('\n');
                }
            }
        }
        sb.append("方向同步：").append(prefs.isEnabled() ? "开" : "关");
        // 连角度一起写出来。光写「开（横向）」看不出锁的是 90° 还是 270°，
        // 跟参数表里那个「横向锁定」是同一个毛病。
        // 以前这里写死"横向" —— 界面只能锁横向。现在多了竖屏锁定，按角度自己判。
        sb.append("　手机方向锁定：");
        if (prefs.isLockPhoneRotation()) {
            int d = DisplayUtil.degrees(prefs.getPhoneRotation());
            sb.append("开（").append(d == 0 || d == 180 ? "竖 " : "横 ").append(d).append("°）");
        } else {
            sb.append("关");
        }
        sb.append("　分辨率接管：").append(prefs.isResolutionEnabled() ? "开" : "关");
        sb.append('\n');
        sb.append("系统自动旋转：").append(sysAutoRotate() ? "开" : "关");
        sb.append("　Shizuku：").append(ShellRunner.isReady() ? "就绪" : "未就绪");
        tvInfo.setText(sb.toString());

        if (tvResState != null) {
            StringBuilder rs = new StringBuilder();
            int cw = prefs.getResWidth();
            int ch = prefs.getResHeight();
            int[] eff = ScreenSizeUtil.portrait(cw, ch);
            rs.append("填写的分辨率：").append(cw).append("×").append(ch);
            if (cw > ch) {
                rs.append("（横向）");
            }
            int dpi = prefs.getResDensity();
            if (dpi > 0) {
                rs.append("　DPI：").append(dpi);
            }
            rs.append('\n');
            rs.append("实际写入内屏：").append(eff[0]).append("×").append(eff[1]);
            if (cw > ch) {
                rs.append("　← 已自动转成竖屏");
            }
            rs.append('\n');
            if (ov == null) {
                rs.append("当前状态：内屏为原生分辨率（未覆盖）");
            } else if (prefs.isSizeApplied()) {
                rs.append("当前状态：已由本应用覆盖为 ").append(ov);
            } else {
                rs.append("当前状态：被其他应用覆盖 ").append(ov);
            }
            if (prefs.isSizeApplied()) {
                rs.append(prefs.isResBoundToExt() ? "（便携屏接入时自动加的）" : "（手动应用，不会自动还原）");
            }
            rs.append('\n');
            boolean overlay = false;
            try {
                overlay = Settings.canDrawOverlays(this);
            } catch (Throwable ignored) {
            }
            rs.append("弹窗权限：").append(overlay ? "已授予（可直接弹对话框）"
                    : "未授予（退化为横幅通知，点「授予弹窗权限」可开启）");
            tvResState.setText(rs.toString());
        }

        if (tvResHint != null) {
            int cw = prefs.getResWidth();
            int ch = prefs.getResHeight();
            int[] eff = ScreenSizeUtil.portrait(cw, ch);
            StringBuilder h = new StringBuilder();
            h.append("横屏分辨率会自动转成竖屏：1920×1080 → 1080×1920。\n");
            h.append("当前将写入内屏：").append(eff[0]).append("×").append(eff[1]);
            if (!ext.isEmpty() && prefs.isAutoMatchExt()) {
                int[] s = DisplayUtil.suggestedInternalSize(ext.get(0));
                if (s[0] > 0) {
                    h.append("　|　便携屏建议：").append(s[0]).append("×").append(s[1]);
                }
            }
            tvResHint.setText(h.toString());
        }

        syncQuickSyncSwitch();
        syncQuickCoverRotSwitch();
        updateResToggleUi();

        // 停在「双屏」页时才刷它，避免每秒白跑
        if (panelDual != null && panelDual.getVisibility() == View.VISIBLE) {
            refreshDualState(false);
        }

        String log = RotationService.LAST_LOG;
        tvLog.setText(log == null || log.isEmpty() ? "（暂无执行记录）" : log);
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i == null) {
                i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开 Shizuku：" + t);
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------- 首次打开时的说明

    /** 「首次打开应用」那个说明弹窗弹过没有 */
    private static final String FILE_INTRO = "extrot_intro";
    private static final String KEY_INTRO_SHOWN = "intro_shown";

    /**
     * 第一次打开这个应用时，把「这套东西能干什么」列给用户看一眼。
     *
     * <p>用户的原话是「建议增加第一次打开应用的弹窗显示，让用户能够一眼了解我们软件的核心功能」——
     * <b>首次</b>即可，所以这里记一个"弹过了"的标记，之后不再弹。
     * 跟 {@code BetaActivity} 那个每次进都弹的说明不是一回事：那个是"这一页是实验性质"的
     * 免责说明，看一次不够；这个是产品导览，第一次知道就够了。
     * 想知道每个版本改了什么，主界面上有「更新日志」入口。
     *
     * <p>⚠ 正文用自定义布局 + 固定高度 ScrollView，理由同 {@code dialog_beta_intro.xml}：
     * 正文一长，AlertDialog 的 message 会先把高度吃光，底部被裁掉。
     */
    private void maybeShowIntro() {
        if (isFinishing()) {
            return;
        }
        SharedPreferences sp = getSharedPreferences(FILE_INTRO, MODE_PRIVATE);
        if (sp.getBoolean(KEY_INTRO_SHOWN, false)) {
            return;
        }
        sp.edit().putBoolean(KEY_INTRO_SHOWN, true).apply();
        View body = getLayoutInflater().inflate(R.layout.dialog_app_intro, null);
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setView(body)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------------ 折叠 / 双屏

    /**
     * 折叠 ↔ 展开。
     *
     * <p>状态表与命令封装都在 {@link DualScreen} 里 —— 桌面小组件走的是同一套，
     * 免得两处各写一份、日后跑偏。
     */
    private void onFoldToggle() {
        if (!requireShell()) {
            return;
        }
        new Thread(() -> {
            final int target = DualScreen.toggleFold(this);
            runOnUiThread(() -> {
                toast(target < 0 ? "切换失败：没有 Shizuku 授权"
                        : "已切到「" + DualScreen.stateName(target) + "」");
                refreshFoldState();
            });
        }, "extrot-fold").start();
    }

    /** 交还给折叠传感器（清掉覆盖，恢复自动） */
    private void onDeviceStateReset() {
        if (!requireShell()) {
            return;
        }
        new Thread(() -> {
            DualScreen.resetState();
            runOnUiThread(() -> {
                toast("已交还给折叠传感器（恢复自动）");
                refreshFoldState();
            });
        }, "extrot-dsr").start();
    }

    /** 读一次当前折叠状态刷新到界面（会起线程，别放进每秒刷新的循环里） */
    private void refreshFoldState() {
        if (tvFoldState == null) {
            return;
        }
        if (!ShellRunner.isReady()) {
            tvFoldState.setText("折叠状态：需要先完成 Shizuku 授权");
            return;
        }
        new Thread(() -> {
            final int st = DualScreen.readDeviceState();
            final int phy = DualScreen.readPhysicalState();
            final boolean cover = DualScreen.isCoverMode(MainActivity.this);
            final int exts = DisplayUtil.externalDisplays(dm).size();
            runOnUiThread(() -> {
                if (tvFoldState == null) {
                    return;
                }
                // 三个值一起报：被别的软件改过覆盖时，"当前生效"和"折叠传感器"
                // 会不一样，画面在哪块屏则由实际亮屏情况推出来 —— 一眼能看出谁在说谎
                tvFoldState.setText("当前生效：" + DualScreen.stateName(st)
                        + " [id=" + st + "]\n"
                        + "折叠传感器：" + DualScreen.stateName(phy)
                        + " · 画面在" + (cover ? "外屏" : "内屏") + "\n"
                        + "外接屏数量：" + exts);
            });
        }, "extrot-foldq").start();
    }

    // ------------------------------------------------------------------ 双屏 / 副屏

    /** 封面屏（折叠机外屏）的 display id，找不到返回 -1 */
    private int coverDisplayId() {
        Display c = DisplayUtil.coverDisplay(dm);
        return c == null ? -1 : c.getDisplayId();
    }

    /** 第一块真正的外接屏（便携屏）的 display id，没有返回 -1 */
    private int firstExtDisplayId() {
        List<Display> ext = DisplayUtil.externalDisplays(dm);
        return ext.isEmpty() ? -1 : ext.get(0).getDisplayId();
    }

    /**
     * 一键双屏：切并发 → 等外屏就绪 → 把副屏桌面投到外屏。
     *
     * <p>整套流程（含"切完并发要等系统把外屏那块 Display 建起来"的时序）在
     * {@link DualScreen#oneKeyDual} 里，桌面小组件点一下走的是同一段代码。
     */
    private void onOneKeyDual() {
        if (!requireShell()) {
            return;
        }
        toast("正在切到并发双屏…");
        new Thread(() -> {
            final boolean ok = DualScreen.oneKeyDual(this, dm);
            runOnUiThread(() -> {
                toast(ok ? "已并发点亮，副屏桌面已投到外屏"
                        : "已切到并发，但外屏没就绪；请确认手机是展开的");
                refreshFoldState();
                refreshDualState(false);
            });
        }, "extrot-onekey").start();
    }

    /**
     * 一键投屏：切并发双屏 → 把「投屏控制」投到副屏 → 它自己全屏、直接开始投屏。
     *
     * <p>四步并一步。原来是「启动双屏模式」→ 到副屏上找到「投屏控制」→ 点「开始投屏」，
     * 中间要点三层。整套流程在 {@link DualScreen#oneKeyCast} 里，这边只负责丢后台线程、报结果。
     */
    private void onOneKeyCast() {
        if (!requireShell()) {
            return;
        }
        toast("正在切到双屏并打开投屏…");
        new Thread(() -> {
            final boolean ok = DualScreen.oneKeyCast(this, dm);
            runOnUiThread(() -> {
                toast(ok ? "投屏控制已投到副屏，这就开始投屏"
                        : "已切到并发，但没投上去；请确认手机是展开的");
                refreshFoldState();
                refreshDualState(false);
            });
        }, "extrot-onekey-cast").start();
    }

    /**
     * 把某个小组件钉到当前桌面（Android 8 起的 requestPinAppWidget）。
     *
     * <p>折叠机上翻系统的「小组件」列表挺麻烦，所以给个一键入口。系统会弹一个确认框，
     * 用户点了才真正放上去；桌面本身不支持时退回到提示用户手动添加。
     */
    private void pinWidget(Class<?> provider, String name) {
        try {
            AppWidgetManager awm = (AppWidgetManager) getSystemService(APPWIDGET_SERVICE);
            if (awm == null || !awm.isRequestPinAppWidgetSupported()) {
                toast("当前桌面不支持一键添加，请长按桌面 → 小组件 → 自适应屏幕");
                return;
            }
            if (awm.requestPinAppWidget(new ComponentName(this, provider), null, null)) {
                toast("请在弹出的小窗里点「添加」，把「" + name + "」放到桌面");
            } else {
                toast("系统没接受这个请求，请到桌面的小组件列表里手动添加");
            }
        } catch (Throwable t) {
            toast("添加失败：" + t);
        }
    }

    // ---- 副屏管理（v4.11 从 Beta 实验室的「副屏管理」页整段搬过来）

    /**
     * 「打开副屏桌面」—— 把副屏桌面投到第二块屏上，<b>不切双屏模式</b>。
     *
     * <p>与上面那个「启动双屏模式」的区别就在这一点：那个会先 {@code cmd device_state state 4}
     * 把整机切成并发状态（内屏那边做的事会跟着变），这个只投内容，手机当前是什么状态就还是什么状态。
     */
    private void doOpenSub() {
        if (CoverDisplay.id(this) <= 0) {
            toast("这台机器上没有封面屏");
            return;
        }
        if (!requireShell()) {
            return;
        }
        toast("正在打开副屏…");
        new Thread(() -> {
            final boolean ok = DualScreen.openSecondaryHome(this, dm);
            runOnUiThread(() -> {
                toast(ok ? "副屏桌面已投上去" : "没投上去，检查 Shizuku 与封面屏状态");
                refreshSubState();
            });
        }, "extrot-sub").start();
    }

    /** 副屏管理那行的状态：封面屏在不在、id 是几 */
    private void refreshSubState() {
        if (tvSubState == null) {
            return;
        }
        int id = CoverDisplay.id(this);
        tvSubState.setText(id <= 0
                ? "没找到封面屏。"
                : ("副屏（封面屏）display=" + id + "。"));
    }

    /**
     * 绑一个伪应用开关（v4.17 起是按钮式）。
     *
     * <p>控件是 {@link CheckBox}：用户要的是"按一下变个色"，不是一行一个拨码开关。
     * {@code color} 是那个伪应用图标的主色，开着当底色、关着用半透明灰；
     * 布局里的 id 还叫 {@code swPseudo*}，是历史名字，别按名字去猜控件类型。
     *
     * <p>⚠ <b>先 setChecked 再挂监听，顺序不能反</b>：反过来的话 setChecked 会被当成
     * 「用户刚拨了一下」，把缺省值当场写进 prefs —— 那之后「默认值」就永久变成了
     * 用户第一次进这一页时的样子，以后改代码里的缺省也没用。
     *
     * <p>关掉 / 打开的即时效果：副屏桌面下次 {@code load()} 时才重新取列表，
     * 所以这里只写 prefs + 提示一句，不去骚扰已经开着的副屏桌面。
     */
    private void bindPseudoToggle(int viewId, String pkg, boolean defaultOn, int color) {
        CheckBox cb = findViewById(viewId);
        if (cb == null) {
            return;
        }
        boolean on = ExtPrefs.pseudoOn(this, pkg, defaultOn);
        cb.setChecked(on);
        applyPseudoLook(cb, color, on);
        cb.setOnCheckedChangeListener((b, isOn) -> {
            ExtPrefs.setPseudoOn(this, pkg, isOn);
            applyPseudoLook(cb, color, isOn);
            String name = cb.getText().toString();
            toast(isOn ? "副屏桌面上会显示「" + name + "」"
                    : "「" + name + "」不再显示在副屏桌面上");
        });
    }

    /**
     * 按钮式开关的配色：开的底色 = 图标主色，关的是半透明灰；文字跟着换白 / 灰。
     *
     * <p>圆角取 8dp，跟 {@code drawable/pseudo_toggle_bg.xml} 对齐 —— 那份只在第一帧
     * 垫底，跑起来之后背景由这里整个换掉。
     */
    private void applyPseudoLook(CheckBox cb, int color, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(getResources().getDisplayMetrics().density * 8f);
        bg.setColor(on ? color : 0x1F888888);
        cb.setBackground(bg);
        cb.setTextColor(on ? 0xFFFFFFFF : 0xFFBBBBBB);
    }

    // ---- DeX 模式

    /**
     * DeX 的启动入口。
     *
     * <p>DeX 也算双屏的一种形态（把手机当主机、另开一块桌面），所以跟「双屏模式」「副屏管理」
     * 排在一起。
     *
     * <p><b>只能尽力而为</b>：三星没给第三方留「开 DeX」的接口，这里发的是社区里公认能起作用的
     * 那个组件。它要有一块自己的输出屏，也就是说得先把便携屏插上。系统把它禁掉、或者要求 root 时，
     * 命令行会把原话吐回来，这里照实显示在下面那行，不假装成功。
     */
    private void startDex() {
        if (!requireShell()) {
            return;
        }
        if (tvDexInfo != null) {
            tvDexInfo.setText("正在拉起 DeX…");
        }
        new Thread(() -> {
            String out = ShellRunner.run(
                    "am start -n com.sec.android.app.launcher"
                            + "/com.honeyspace.dexservice.SecondaryLauncher"
                            + " || am start -n com.sec.android.app.desktoplauncher"
                            + "/.DeXHomeActivity", 15);
            final String pretty = prettyDex(out);
            runOnUiThread(() -> {
                if (tvDexInfo != null) {
                    tvDexInfo.setText(pretty);
                }
                toast(pretty.contains("没起") || pretty.contains("挡")
                        ? "没拉起来，看下面那行" : "命令已发出，看便携屏");
            });
        }, "extrot-dex").start();
    }

    /** DeX 那条命令的回话翻成人话（系统返回什么就照着说什么，不替它下结论） */
    private String prettyDex(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "已发出启动命令（系统没回话）。";
        }
        String low = s.toLowerCase();
        String body = "回话：\n" + tail(s, 380);
        if (low.contains("must be root") || low.contains("permission denial")
                || low.contains("securityexception")) {
            return body + "\n\n系统把这条挡住了 —— 这份系统版本不允许从 shell 起 DeX。";
        }
        if (low.contains("error") || low.contains("exception")
                || low.contains("not exist") || low.contains("not found")) {
            return body + "\n\n没起来。先把便携屏插上再试一次；还是不行，"
                    + "就是这份系统里没有可用的 DeX 入口。";
        }
        return "已发出启动命令。\n" + body;
    }

    /** 只留回话的尾巴 —— shell 的输出一长条，铺满屏反而看不见重点 */
    private static String tail(String s, int n) {
        return s.length() <= n ? s : ("…" + s.substring(s.length() - n));
    }

    /** 双屏页顶部的状态文字 */
    private void refreshDualState(boolean withFold) {
        if (tvDualState == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        Display def = DisplayUtil.defaultDisplay(dm);
        sb.append("内屏：ID 0 · ").append(DisplayUtil.sizeOf(def))
                .append(" · ").append(DisplayUtil.isOn(def) ? "已点亮" : "未点亮").append('\n');

        List<Display> secs = DisplayUtil.secondaryDisplays(dm);
        if (secs.isEmpty()) {
            sb.append("其他屏：当前一块都看不到\n");
            sb.append("　（外屏要先点上面的「一键启动双屏」点亮；便携屏要先插上）\n");
        } else {
            for (Display d : secs) {
                sb.append(DisplayUtil.kindOf(dm, d))
                        .append("：ID ").append(d.getDisplayId())
                        .append(" · ").append(DisplayUtil.sizeOf(d));
                int[] n = DisplayUtil.nativeSize(d);
                if (n[0] > 0) {
                    sb.append(" · 原生 ").append(n[0]).append("×").append(n[1]);
                }
                sb.append(" · ").append(DisplayUtil.isOn(d) ? "已点亮 ✓" : "未点亮");
                sb.append(d.getDisplayId() == coverDisplayId() ? "　← 副屏桌面投这里" : "　← 外接屏");
                sb.append('\n');
            }
        }

        // 诊断：每块屏的身份判据。排查「封面屏没被认出来」时就看这段。
        // 注意这里用的是 DisplayUtil.allDisplays（按 id 探测），
        // 因为 dm.getDisplays() 会把封面屏整个漏掉 —— 这正是当初认不出它的原因。
        Display d0 = DisplayUtil.defaultDisplay(dm);
        sb.append("默认屏名：").append(d0 == null ? "?" : String.valueOf(d0.getName())).append('\n');
        try {
            for (Display d : DisplayUtil.allDisplays(dm)) {
                sb.append("id=").append(d.getDisplayId())
                        .append(" 名=").append(String.valueOf(d.getName()))
                        .append(" cutout=").append(d.getCutout() != null)
                        .append(" flags=0x").append(Integer.toHexString(d.getFlags()))
                        .append('\n');
            }
        } catch (Throwable ignored) {
        }

        tvDualState.setText(sb.toString());

        if (withFold) {
            refreshFoldState();
        }
    }

    private boolean requireShell() {
        if (!ShellRunner.isReady()) {
            toast("请先完成 Shizuku 授权");
            return false;
        }
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
