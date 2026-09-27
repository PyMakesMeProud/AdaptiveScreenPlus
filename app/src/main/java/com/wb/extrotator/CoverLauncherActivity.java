package com.wb.extrotator;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「外屏启动器」的完整界面 —— 投到封面屏上用的配置页（也能在内屏开）。
 *
 * <p><b>它和小组件的关系</b>：小组件是「遥控器」，只能摆图标、点了启动，受 RemoteViews 限制；
 * 本页是「遥控器的设置页」，一个普通 Activity，想怎么画怎么画 —— 于是有了搜索、勾选、拖拽、外观、排序。
 *
 * <p>它自己不关心跑在哪块屏上：读 {@code getDefaultDisplay().getDisplayId()} 知道自己所在屏，
 * 点应用时用 {@link SecondaryLauncher} 往<b>同一块屏</b>投，所以在外屏上点就是在外屏上开。
 *
 * <p><b>这一页只用来配置</b>：点图标 = 把它加进 / 移出启动器。顶栏左边那颗星是收藏夹
 * （拨一下只显示收藏过的应用）；收藏本身在长按菜单里做 —— 长按之后<b>挪动</b> = 拖拽排序
 * （落点写盘，见 {@link DragGrid}），长按之后<b>原地松手</b> = 弹「收藏 / 卸载应用」
 * （{@link DragGrid.OrderListener#onHoldReleased}）。
 *
 * <p><b>列表顺序</b>：配置页看到的 = 已选（按当前排序方式）+ 未选（按名称），所以没勾上的永远在末尾，
 * 勾上之后会跳到前面去；拖拽只在「已选」这一段里有效（{@link DragGrid#setDropLimit}）。
 *
 * <p><b>外观参数实时生效</b>：外观条里改任何一项，配置页这块网格会当场重画（{@link #applyLook}），
 * 小组件那边同一时刻也刷新一次。
 *
 * <p><b>背景图片</b>：顶栏那颗图片图标选图，处理与取用见 {@link LauncherPhoto}。
 * 设好之后配置页和小组件都换成它，跟「不透明度」是叠着的两层（图片在下、半透明黑在上）。
 */
public class CoverLauncherActivity extends Activity implements DragGrid.OrderListener {

    private static final String TAG = "CoverLauncherPage";

    /**
     * 从小组件的 ⇅ 图标进来时带这个 extra：直接把排序选择器弹出来。
     * 这样"在小组件上点排序"和"在配置页上点排序"是同一个东西，不再是一边轮换一边选。
     */
    public static final String EXTRA_OPEN_SORT = "open_sort";

    /** chip 行回调：拿到用户选的那个值 */
    private interface OnPick {
        void on(int value);
    }

    /** chip 行每次重画时现读当前值 */
    private interface IntGetter {
        int get();
    }

    private DragGrid gvApps;
    private EditText etSearch;
    private TextView tvCount;
    private TextView tvHint;
    private ImageView btnFav;
    private ImageView btnLook;
    private ImageView btnPhoto;
    private LinearLayout lookStrip;
    private View btnDone;

    private final List<AppRepo.Item> all = new ArrayList<>();
    private final List<AppRepo.Item> shown = new ArrayList<>();

    /** 收藏的包名。rebuild 时现读一次 —— 格子里的星标每格都要用，每格现读一次 pref 太浪费 */
    private final Set<String> favs = new HashSet<>();
    private PickAdapter adapter;

    private boolean loading = false;

    /** 外观条是不是展开着（它不是弹窗，是页内一条 —— 见布局里的注释） */
    private boolean lookOpen = false;

    /** 外观条的胶囊只画一次，之后只重画选中态 */
    private boolean lookBuilt = false;

    /** "已选"那一段的长度 —— 拖拽的落点上限、导出顺序的长度都用它 */
    private int selectedCount = 0;

    /** 进来时是不是要直接弹排序器（被小组件的 ⇅ 叫起来的） */
    private boolean wantSortDialog = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cover_launcher);

        gvApps = findViewById(R.id.gvPick);
        etSearch = findViewById(R.id.etPickSearch);
        tvCount = findViewById(R.id.tvPickCount);
        tvHint = findViewById(R.id.tvPickHint);
        btnFav = findViewById(R.id.btnPickFav);
        btnLook = findViewById(R.id.btnPickLook);
        btnPhoto = findViewById(R.id.btnPickPhoto);
        lookStrip = findViewById(R.id.lookStrip);
        btnDone = findViewById(R.id.btnPickDone);

        wantSortDialog = "1".equals(extra(EXTRA_OPEN_SORT));

        adapter = new PickAdapter();
        gvApps.setAdapter(adapter);
        gvApps.setDragHost(findViewById(R.id.gridHost));
        gvApps.setOrderListener(this);

        // 单击 = 加进 / 移出启动器（v4.14 起没有"直接打开"那一档了，见类注释）
        gvApps.setOnItemClickListener((parent, view, position, id) -> onTap(position));
        // ⚠ 不设 setOnItemLongClickListener —— 长按归 DragGrid 管，
        //   在这里再挂一个会跟它的长按判定抢事件。

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                rebuild();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        btnFav.setOnClickListener(v -> toggleFavOnly());

        /*
         * 标题右边那个「ⓘ」：把这一页怎么用一次讲清楚。
         *
         * 前面那些图标（编辑 / 外观 / 排序）都是「点一下才知道是什么」的东西，而配置文件里那份
         * 长说明读的人少 —— 干脆挂在标题边上，想看随手点。正文在 strings.xml 的 info_launcher_page。
         */
        View info = findViewById(R.id.iPickTitle);
        if (info != null) {
            info.setOnClickListener(v -> {
                if (isFinishing()) {
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.launcher_title)
                        .setMessage(R.string.info_launcher_page)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }

        View look = findViewById(R.id.btnPickLook);
        if (look != null) {
            look.setOnClickListener(v -> toggleLook());
        }
        View sort = findViewById(R.id.btnPickSort);
        if (sort != null) {
            sort.setOnClickListener(v -> showSort());
        }
        View close = findViewById(R.id.btnPickClose);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }
        View photo = findViewById(R.id.btnPickPhoto);
        if (photo != null) {
            photo.setOnClickListener(v -> onPhotoTap());
        }

        View importBtn = findViewById(R.id.btnPickImport);
        if (importBtn != null) {
            importBtn.setOnClickListener(v -> showIoDialog());
        }
        View clearBtn = findViewById(R.id.btnPickClear);
        if (clearBtn != null) {
            clearBtn.setOnClickListener(v -> confirmClear());
        }
        btnDone.setOnClickListener(v -> {
            CoverLauncherProvider.refreshAll(this);
            int n = LauncherPrefs.apps(this).size();
            toast(n == 0 ? "还没选应用" : "已保存 " + n + " 个应用");
            finish();
        });

        renderFavIcon();
        renderLookIcon();
        renderPhotoIcon();
        applyLook();
        updateCount();
        updateHint();
        load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("1".equals(extra(EXTRA_OPEN_SORT))) {
            showSort();
        }
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从别处改过勾选的话，回来时对齐一下
        if (!loading && !all.isEmpty()) {
            rebuild();
        }
    }

    private String extra(String key) {
        try {
            Intent i = getIntent();
            return i == null ? null : i.getStringExtra(key);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 单击

    private void onTap(int position) {
        if (position < 0 || position >= shown.size()) {
            return;
        }
        AppRepo.Item it = shown.get(position);
        boolean on = LauncherPrefs.toggle(this, it.pkg);
        Log.i(TAG, (on ? "勾选 " : "取消 ") + it.pkg);
        rebuild();
        /*
         * 正在搜索的时候顺手把键盘收掉。
         *
         * 外屏只有 339dp 高，键盘一弹就吃掉 460px —— 就算本页设了 adjustPan
         * （窗口不缩、键盘浮在上面），能看的也就上面那两三行。
         * 所以"搜到了 → 点它"这个动作做完，键盘就没有继续杵着的理由了；
         * 不收掉的话，用户还得自己去按一下返回。
         */
        if (!query().trim().isEmpty()) {
            hideIme();
        }
    }

    private void hideIme() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && etSearch != null) {
                imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 收藏夹

    /**
     * 拨「只看收藏」。跟小组件顶栏那个收藏夹图标<b>共用同一个开关</b>
     * （{@link LauncherPrefs#favOnly}），在哪儿拨的另一边立刻是这个样子。
     *
     * <p>在这一页的效果：网格只留收藏过的应用。这时候拖动排序照样能用，
     * 但只在这几个之间换位 —— 没收藏的那些在启动器里的相对位置不会被搅乱
     * （见 {@link #mergeOrder}）。
     */
    private void toggleFavOnly() {
        boolean on = !LauncherPrefs.favOnly(this);
        LauncherPrefs.setFavOnly(this, on);
        Log.i(TAG, on ? "只看收藏" : "看全部应用");
        renderFavIcon();
        rebuild();
        CoverLauncherProvider.refreshAll(this);
        toast(getString(on ? R.string.launcher_fav_only_on : R.string.launcher_fav_only_off));
    }

    /** 收藏夹图标：开着的时候点亮成绿色（跟排序 / 外观那两个图标一个规矩） */
    private void renderFavIcon() {
        if (btnFav == null) {
            return;
        }
        boolean on = LauncherPrefs.favOnly(this);
        btnFav.setImageResource(on
                ? R.drawable.ic_launcher_star_on : R.drawable.ic_launcher_star);
        btnFav.setAlpha(on ? 1f : 0.72f);
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 排序选择器。选项、名字都取自 {@link LauncherPrefs#SORT_ALL} ——
     * 小组件那边点 ⇅ 也是进这里（{@link #EXTRA_OPEN_SORT}），两边不存在"少一种"的可能。
     */
    private void showSort() {
        final String[] modes = LauncherPrefs.SORT_ALL;
        final String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            names[i] = LauncherPrefs.sortLabel(this, modes[i]);
        }

        int cur = 0;
        String now = LauncherPrefs.sort(this);
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(now)) {
                cur = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_sort)
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    String mode = modes[which];
                    LauncherPrefs.setSort(this, mode);
                    d.dismiss();
                    rebuild();
                    CoverLauncherProvider.refreshAll(this);
                    Log.i(TAG, "排序改成 " + mode);
                    if (LauncherPrefs.SORT_HOME.equals(mode) && !HomePrefs.isConfigured(this)) {
                        toast(getString(R.string.home_order_none));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 外观

    /**
     * 展开 / 收起外观条。
     *
     * <p>这里刻意<b>不用对话框</b>：用户的诉求是"调图标大小和行距的时候要能当场看到变化"，
     * 而对话框会把网格整个盖住（还压一层暗色 scrim），改完得关掉才看得见 —— 那不叫实时反馈。
     * 做成页内一条横条，网格就在它上面，改一格当场变一格。
     */
    private void toggleLook() {
        lookOpen = !lookOpen;
        if (lookOpen && !lookBuilt) {
            buildLookRows();
            lookBuilt = true;
        }
        if (lookStrip != null) {
            lookStrip.setVisibility(lookOpen ? View.VISIBLE : View.GONE);
        }
        renderLookIcon();
        // 条展开后网格会变矮，列数不变但可用高度变了，让它重算一次布局
        if (gvApps != null) {
            gvApps.requestLayout();
        }
    }

    private void renderLookIcon() {
        if (btnLook == null) {
            return;
        }
        btnLook.setImageResource(lookOpen
                ? R.drawable.ic_launcher_tune_on : R.drawable.ic_launcher_tune);
        btnLook.setAlpha(lookOpen ? 1f : 0.72f);
    }

    /**
     * 画外观条的六组胶囊 —— 一排几个 / 图标大小 ｜ 行距 / 应用名称 ｜ 图标形状 / 背景。
     *
     * <p>每组都是「点一下就落盘 + 两边立刻重画」：配置页这块网格当场变（{@link #applyLook}），
     * 小组件那边也刷一次，所以调的时候不用来回切页面看效果。只有「背景」那组例外 ——
     * 浓度是小组件底板的事，配置页这边没东西可重画（见 {@link LauncherPrefs#bgDrawable}）。
     */
    private void buildLookRows() {
        if (lookStrip == null) {
            return;
        }
        LinearLayout hostCols = lookStrip.findViewById(R.id.lookCols);
        LinearLayout hostIcon = lookStrip.findViewById(R.id.lookIcon);
        LinearLayout hostGap = lookStrip.findViewById(R.id.lookGap);
        LinearLayout hostLabel = lookStrip.findViewById(R.id.lookLabel);
        LinearLayout hostShape = lookStrip.findViewById(R.id.lookShape);
        LinearLayout hostAlpha = lookStrip.findViewById(R.id.lookAlpha);

        int[] colValues = LauncherPrefs.COLUMN_CHOICES;
        String[] colLabels = new String[colValues.length];
        for (int i = 0; i < colValues.length; i++) {
            colLabels[i] = getString(R.string.launcher_look_cols_fmt, colValues[i]);
        }
        ChipRow rowCols = new ChipRow(hostCols, colLabels, colValues,
                () -> LauncherPrefs.columns(this), v -> {
            LauncherPrefs.setColumns(this, v);
            applyLook();
            CoverLauncherProvider.refreshAll(this);
        });

        int[] sizeValues = {LauncherPrefs.SIZE_SMALL, LauncherPrefs.SIZE_MID, LauncherPrefs.SIZE_LARGE};
        String[] sizeLabels = {
                getString(R.string.launcher_size_small),
                getString(R.string.launcher_size_mid),
                getString(R.string.launcher_size_large)};
        ChipRow rowIcon = new ChipRow(hostIcon, sizeLabels, sizeValues,
                () -> LauncherPrefs.iconSize(this), v -> {
            LauncherPrefs.setIconSize(this, v);
            applyLook();
            CoverLauncherProvider.refreshAll(this);
        });

        int[] gapValues = {LauncherPrefs.GAP_TIGHT, LauncherPrefs.GAP_MID, LauncherPrefs.GAP_LOOSE};
        String[] gapLabels = {
                getString(R.string.launcher_gap_tight),
                getString(R.string.launcher_gap_mid),
                getString(R.string.launcher_gap_loose)};
        ChipRow rowGap = new ChipRow(hostGap, gapLabels, gapValues,
                () -> LauncherPrefs.rowGap(this), v -> {
            LauncherPrefs.setRowGap(this, v);
            applyLook();
            CoverLauncherProvider.refreshAll(this);
        });

        int[] labelValues = {1, 0};
        String[] labelLabels = {
                getString(R.string.launcher_label_show),
                getString(R.string.launcher_label_hide)};
        ChipRow rowLabel = new ChipRow(hostLabel, labelLabels, labelValues,
                () -> LauncherPrefs.showLabel(this) ? 1 : 0, v -> {
            LauncherPrefs.setShowLabel(this, v == 1);
            applyLook();
            CoverLauncherProvider.refreshAll(this);
        });

        /*
         * 图标形状。档位和标签一一对应，别写死数量 ——
         * 以后加一档（比如"方形"）只要动 LauncherPrefs.SHAPE_ALL 和这三条文案。
         */
        int[] shapeValues = LauncherPrefs.SHAPE_ALL;
        String[] shapeLabels = {
                getString(R.string.launcher_shape_app),
                getString(R.string.launcher_shape_round),
                getString(R.string.launcher_shape_circle)};
        ChipRow rowShape = new ChipRow(hostShape, shapeLabels, shapeValues,
                () -> LauncherPrefs.iconShape(this), v -> {
            LauncherPrefs.setIconShape(this, v);
            applyLook();
            CoverLauncherProvider.refreshAll(this);
        });

        /*
         * 背景浓度。胶囊上直接写百分比 —— "淡/中/浓"这种词
         * 在一块半透明底板上最容易理解反（说"透明度高"到底指更透还是更实？）。
         *
         * ⚠ 这一组要 applyLook()：设过背景图之后，浓度同时管着本页那层遮罩的厚度
         * （没设图时它只影响小组件，多刷一次也不亏）。
         */
        int[] alphaValues = LauncherPrefs.ALPHA_CHOICES;
        String[] alphaLabels = new String[alphaValues.length];
        for (int i = 0; i < alphaValues.length; i++) {
            alphaLabels[i] = getString(R.string.launcher_alpha_fmt, alphaValues[i]);
        }
        ChipRow rowAlpha = new ChipRow(hostAlpha, alphaLabels, alphaValues,
                () -> LauncherPrefs.bgAlpha(this), v -> {
            LauncherPrefs.setBgAlpha(this, v);
            applyLook();
            CoverLauncherProvider.refreshAll(this);
        });

        rowCols.render();
        rowIcon.render();
        rowGap.render();
        rowLabel.render();
        rowShape.render();
        rowAlpha.render();
    }

    /**
     * 一行等宽胶囊。选中态 = 绿描边（launcher_chip_on），没选中 = 极淡白底。
     * 每次点选都整行重画 —— 就几个控件，比逐个改背景省心。
     */
    private class ChipRow {

        private final LinearLayout host;
        private final String[] labels;
        private final int[] values;
        private final IntGetter getter;
        private final OnPick cb;

        ChipRow(LinearLayout host, String[] labels, int[] values, IntGetter getter, OnPick cb) {
            this.host = host;
            this.labels = labels;
            this.values = values;
            this.getter = getter;
            this.cb = cb;
        }

        void render() {
            if (host == null) {
                return;
            }
            host.removeAllViews();
            final int cur = getter.get();
            for (int i = 0; i < labels.length && i < values.length; i++) {
                final int val = values[i];
                TextView t = new TextView(CoverLauncherActivity.this);
                t.setText(labels[i]);
                t.setTextSize(11);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(val == cur ? 0xFF34C759 : 0xFFE8EDF5);
                t.setBackgroundResource(val == cur
                        ? R.drawable.launcher_chip_on : R.drawable.launcher_chip_off);
                // 页内横条比原来的弹窗矮，胶囊跟着收到 26dp
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, dp(26), 1f);
                if (i > 0) {
                    lp.leftMargin = dp(4);
                }
                t.setLayoutParams(lp);
                t.setOnClickListener(v -> {
                    cb.on(val);
                    render();
                });
                host.addView(t);
            }
        }
    }

    /**
     * 把外观设置落到配置页这块网格上。
     *
     * <p>列数可以直接 {@code setNumColumns}（这是真 GridView，不受 RemoteViews 那套限制）；
     * 图标大小和行距在 {@link PickAdapter#getView} 里按当前设置现算，
     * 所以只要 {@code notifyDataSetChanged()} 一下就会重画。
     *
     * <p>整页的底色（背景图 + 那层半透明黑）也从这儿走 —— 见 {@link #applyBackdrop}。
     */
    private void applyLook() {
        if (gvApps != null) {
            gvApps.setNumColumns(LauncherPrefs.columns(this));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        applyBackdrop();
    }

    // ------------------------------------------------------------------ 背景图片

    /** 选图的请求码，跟本页其它请求错开就行 */
    private static final int REQ_PHOTO = 0x2207;

    /**
     * 把整页的底色铺成「背景图 + 半透明黑」。
     *
     * <p>没设过图就退回原来的纯色底（#0E1116），跟老版本一模一样。设过则两层叠起来，
     * 叠法跟小组件那边完全一致 —— 「不透明度」在两边是同一个意思（图片上盖多厚的黑），
     * 所以调这个档位时两边看到的变化一样，不用合上手机去比对。
     *
     * <p>⚠ 读盘解码走后台线程：1520px 的 JPEG 解出来好几 MB，主线程解会掉帧。
     * 解码结果在 {@link LauncherPhoto} 里带缓存，反复调也不会反复读盘。
     */
    private void applyBackdrop() {
        final View root = findViewById(R.id.pickRoot);
        if (root == null) {
            return;
        }
        if (!LauncherPhoto.has(this)) {
            root.setBackgroundColor(0xFF0E1116);
            return;
        }
        final int alpha = LauncherPrefs.bgAlpha(this);
        final Context app = getApplicationContext();
        new Thread(() -> {
            final android.graphics.drawable.Drawable bg =
                    LauncherPhoto.pageBackdrop(app, alpha);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                View v = findViewById(R.id.pickRoot);
                if (v == null) {
                    return;
                }
                if (bg == null) {
                    v.setBackgroundColor(0xFF0E1116);
                } else {
                    v.setBackground(bg);
                }
            });
        }, "pick-backdrop").start();
    }

    /** 没设过图时点图标 = 直接去选；已经设过就弹一下「换 / 撤」 */
    private void onPhotoTap() {
        if (LauncherPhoto.has(this)) {
            showPhotoMenu();
        } else {
            pickPhoto();
        }
    }

    /**
     * 去系统文件选择器挑一张图。
     *
     * <p>用 {@code ACTION_OPEN_DOCUMENT} 而不是 GET_CONTENT：读那张图的权力是随这次选择
     * 临时给的，<b>不需要任何存储权限</b>，也就不会弹权限框。
     *
     * <p>⚠ 这一页可能跑在封面屏上，所以顺手让选择器也投到同一块屏。
     * 投不过去也不拦着 —— 万一它落到内屏，翻开机盖点完再折回来照样收得到结果
     * （走 onActivityResult），只是多一步。
     */
    private void pickPhoto() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        try {
            ActivityOptions op = ActivityOptions.makeBasic();
            op.setLaunchDisplayId(getWindowManager().getDefaultDisplay().getDisplayId());
            startActivityForResult(it, REQ_PHOTO, op.toBundle());
        } catch (Throwable t) {
            Log.w(TAG, "选择器投到本屏没成，退回默认屏", t);
            startActivityForResult(it, REQ_PHOTO);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PHOTO) {
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) {
            Log.i(TAG, "选图取消");
            return;
        }
        final Uri src = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    src, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // 有些来源给不了长期授权，不要紧 —— 下面马上就把它读掉了
        }
        toast(getString(R.string.launcher_photo_saving));
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = LauncherPhoto.save(app, src);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                toast(getString(ok ? R.string.launcher_photo_done : R.string.launcher_photo_fail));
                if (ok) {
                    renderPhotoIcon();
                    applyLook();
                    CoverLauncherProvider.refreshAll(CoverLauncherActivity.this);
                }
            });
        }, "pick-save").start();
    }

    /** 已经设过图时点图标弹的两项：换一张 / 撤掉 */
    private void showPhotoMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_launcher_menu);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));

        final int menuW = dp(168);
        final PopupWindow pw = new PopupWindow(box, menuW,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);

        box.addView(menuRow(getString(R.string.launcher_photo_change), 0xFFECEFF4, v -> {
            pw.dismiss();
            pickPhoto();
        }));
        box.addView(menuRow(getString(R.string.launcher_photo_remove), 0xFFFF7A6E, v -> {
            pw.dismiss();
            LauncherPhoto.clear(this);
            renderPhotoIcon();
            applyLook();
            CoverLauncherProvider.refreshAll(this);
            toast(getString(R.string.launcher_photo_removed));
        }));

        if (btnPhoto == null) {
            pw.showAtLocation(findViewById(R.id.gvPick), Gravity.CENTER, 0, 0);
            return;
        }
        // 图标就在最右边，所以菜单往左下弹、右端起齐它的右端（xoff 为负 = 往左挪）
        pw.showAsDropDown(btnPhoto, dp(34) - menuW, dp(4));
    }

    /** 设过背景图时那颗图标点亮成绿色 —— 跟收藏夹/排序/外观是同一个信号 */
    private void renderPhotoIcon() {
        if (btnPhoto == null) {
            return;
        }
        boolean on = LauncherPhoto.has(this);
        btnPhoto.setImageResource(on
                ? R.drawable.ic_launcher_photo_on : R.drawable.ic_launcher_photo);
        btnPhoto.setAlpha(on ? 1f : 0.72f);
    }

    private int dp(int v) {
        float d;
        try {
            d = getResources().getDisplayMetrics().density;
        } catch (Throwable t) {
            d = 2f;
        }
        return Math.round(v * d);
    }

    // ------------------------------------------------------------------ 长按菜单

    /**
     * 长按之后没挪动、原地松手 —— 弹「收藏 / 卸载应用」两个选项。
     *
     * <p>为什么不直接在格子上放按钮：外屏这一页本来就挤。长按不动＝弹菜单
     * 是最不占地方的做法，而且它跟"长按拖动排序"共用同一次长按，
     * 手不挪就是菜单、挪了就是排序，不会打架。
     */
    @Override
    public void onHoldReleased(int position) {
        if (isFinishing() || position < 0 || position >= shown.size()) {
            return;
        }
        showItemMenu(shown.get(position),
                gvApps.getChildAt(position - gvApps.getFirstVisiblePosition()));
    }

    /**
     * 长按弹出的那两三项（自建小卡片 + {@code PopupWindow}）。
     *
     * <p>原来用系统的 {@link AlertDialog}：标题栏、列表项、下面还压一条取消按钮，三块各占一行，
     * 圆角又是系统那套大圆角，在外屏这点地方特别占位置。现在是自建的小卡片 —— 窄一点、圆角小一点，
     * 应用名只当行首说明，选项直接贴着排。<b>以后要加选项，往 box 里再 append 一行就行。</b>
     *
     * <p>⚠ 又从 Dialog 换成 {@code PopupWindow}：Dialog 的位置由窗口管理器说了算，在外屏上它落到
     * 画面最下面，底边那一截（外壳手势区）点不到 —— 用户实测反馈。{@code PopupWindow.showAsDropDown}
     * 天生就是「贴着某个 View 弹」：把被长按的那一格当锚点，格子在上半屏就往下弹、在下半屏就往上弹，
     * 横向再夹一次，不越出网格区。
     *
     * @param cell 被长按的那一格；拿不到时（理论上不该发生）退回画面正中
     */
    private void showItemMenu(final AppRepo.Item it, final View cell) {
        final boolean faved = favs.contains(it.pkg);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_launcher_menu);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView head = new TextView(this);
        head.setText(it.label);
        head.setTextSize(12f);
        head.setTextColor(0xFF8A94A6);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        head.setPadding(dp(12), dp(6), dp(12), dp(6));
        box.addView(head);

        View div = new View(this);
        div.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.setMargins(dp(8), 0, dp(8), dp(2));
        box.addView(div, dlp);

        final int menuW = dp(186);
        final PopupWindow pw = new PopupWindow(box, menuW,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);

        box.addView(menuRow(getString(faved ? R.string.launcher_unfav : R.string.launcher_fav),
                0xFFECEFF4, v -> {
                    pw.dismiss();
                    toggleFav(it);
                }));
        box.addView(menuRow(getString(R.string.launcher_uninstall), 0xFFFF7A6E, v -> {
            pw.dismiss();
            confirmUninstall(it);
        }));

        View host = findViewById(R.id.gridHost);
        if (cell == null || host == null) {   // 拿不到落点就退回正中，总比不弹强
            pw.showAtLocation(findViewById(R.id.gvPick), Gravity.CENTER, 0, 0);
            return;
        }

        // 先把卡片量一遍：知道它多高，才判得出上下各还剩多少地方
        box.measure(View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int menuH = box.getMeasuredHeight();

        // 上下：默认往下弹；下面放不下就往上弹；两边都放不下就挑宽的那边，剩下的交给系统夹
        // （比的是「相对网格区的位置」，两边都取窗口坐标，整体偏多少都会抵消）
        int[] cl = new int[2];
        int[] hl = new int[2];
        cell.getLocationInWindow(cl);
        host.getLocationInWindow(hl);
        int roomBelow = (hl[1] + host.getHeight()) - (cl[1] + cell.getHeight());
        int roomAbove = cl[1] - hl[1];
        boolean below;
        if (roomBelow >= menuH + dp(4)) {
            below = true;
        } else if (roomAbove >= menuH + dp(4)) {
            below = false;
        } else {
            below = roomBelow >= roomAbove;
        }

        // 横向：对准这一格，再夹进网格区（左右各留 2dp）
        int cellLeftInHost = gvApps.getLeft() + cell.getLeft();
        int left = cellLeftInHost + (cell.getWidth() - menuW) / 2;
        int lo = dp(2);
        int hi = Math.max(lo, host.getWidth() - menuW - dp(2));
        if (left < lo) {
            left = lo;
        } else if (left > hi) {
            left = hi;
        }

        pw.showAsDropDown(cell, left - cellLeftInHost, below ? 0 : -dp(6),
                below ? Gravity.NO_GRAVITY : Gravity.TOP);
    }

    /** 菜单里的一行。按下时浮一层白（ripple 在 drawable 里） */
    private View menuRow(String text, int color, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackgroundResource(R.drawable.launcher_menu_item);
        tv.setClickable(true);
        tv.setOnClickListener(click);
        return tv;
    }

    /** 收藏 / 取消收藏。收藏时顺带把它加进启动器（不在启动器里就筛不出来）。 */
    private void toggleFav(AppRepo.Item it) {
        boolean now = LauncherPrefs.toggleFav(this, it.pkg);
        Log.i(TAG, (now ? "收藏 " : "取消收藏 ") + it.pkg);
        rebuild();
        CoverLauncherProvider.refreshAll(this);
        toast(getString(now ? R.string.launcher_fav_added : R.string.launcher_fav_removed, it.label));
    }

    /** 卸载是不可逆的，所以从长按菜单进来之后还要再确认一次 */
    private void confirmUninstall(final AppRepo.Item it) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.launcher_uninstall_confirm, it.label))
                .setMessage(R.string.launcher_uninstall_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.launcher_uninstall, (d, w) -> doUninstall(it))
                .show();
    }

    /**
     * 真卸。走 Shizuku 的 shell（{@code pm uninstall --user 0}）——
     * 所以在这一页里就能把应用卸掉，不用先翻开手机去系统设置里找。
     * 失败时把命令行原话的第一行照实显示出来，不假装成功。
     */
    private void doUninstall(final AppRepo.Item it) {
        if (!ShellRunner.isReady()) {
            toast(getString(R.string.launcher_uninstall_no_shizuku));
            return;
        }
        // 包名只可能是这几种字符；不匹配就别往 shell 里拼了
        if (!it.pkg.matches("[A-Za-z0-9._]+")) {
            toast(getString(R.string.launcher_uninstall_bad_pkg));
            return;
        }
        toast(getString(R.string.launcher_uninstalling, it.label));
        new Thread(() -> {
            String out = ShellRunner.run("pm uninstall --user 0 " + it.pkg, 40);
            final boolean ok = out != null && out.contains("Success");
            final String why = firstLine(out);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (!ok) {
                    Log.w(TAG, "卸载失败 " + it.pkg + " => " + out);
                    toast(getString(R.string.launcher_uninstall_fail, why));
                    return;
                }
                // 卸掉了就把两处记录一起清掉，别留一个点不开的图标
                LauncherPrefs.forget(this, it.pkg);
                all.remove(it);
                Log.i(TAG, "已卸载 " + it.pkg);
                rebuild();
                CoverLauncherProvider.refreshAll(this);
                toast(getString(R.string.launcher_uninstall_done, it.label));
            });
        }, "extrot-launcher-uninstall").start();
    }

    /** 取命令输出的第一行 —— 失败原因给用户看一行就够 */
    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    // ------------------------------------------------------------------ 拖拽回调

    @Override
    public void onOrderMove(int from, int to) {
        if (from < 0 || to < 0 || from >= shown.size() || to >= shown.size()) {
            return;
        }
        // 拖动经过：数据当场挪过去，后面的格子才能"让位"
        AppRepo.Item it = shown.remove(from);
        shown.add(to, it);
        adapter.notifyDataSetChanged();
    }

    /**
     * 松手：把"现在看到的这个顺序"固化成本启动器的手动顺序。
     *
     * <p>为什么不直接对 {@code LauncherPrefs} 做一次 move ——
     * 因为用户可能是在"按名称"排的状态下拖的。这时候看到的顺序跟存的手动顺序是两回事，
     * 按索引去挪会挪错人。整份重新截一遍最省心，顺带把排序切成"手动"（顺序已经是用户定的了）。
     */
    @Override
    public void onOrderChanged(int from, int to) {
        List<String> seg = new ArrayList<>();
        for (int i = 0; i < selectedCount && i < shown.size(); i++) {
            seg.add(shown.get(i).pkg);
        }
        List<String> order = mergeOrder(seg);
        String was = LauncherPrefs.sort(this);
        LauncherPrefs.setSort(this, LauncherPrefs.SORT_MANUAL);
        LauncherPrefs.setApps(this, order);
        CoverLauncherProvider.refreshAll(this);
        Log.i(TAG, "拖动排序 " + from + " -> " + to + "，手动顺序已更新为 " + order.size() + " 项"
                + (LauncherPrefs.SORT_MANUAL.equals(was) ? "" : "（排序方式 " + was + " -> 手动）"));
        // 只在本来的排序方式不是"手动"时提一句 —— 每次放手都弹一个提示太吵
        if (!LauncherPrefs.SORT_MANUAL.equals(was)) {
            toast(getString(R.string.launcher_manual_on));
        }
        updateHint();
    }

    @Override
    public void onDragStateChanged(boolean dragging) {
        if (dragging) {
            tvHint.setText(R.string.launcher_hint_drag);
        } else {
            updateHint();
        }
    }

    /**
     * 把"屏幕上这一段的新顺序"并回启动器那份完整名单。
     *
     * <p>没开「只看收藏」时屏幕上就是全部，直接整份覆盖（跟以前一样）。
     * 开了之后屏幕上只有收藏那几个 —— 那时候不能整份写回去，
     * 否则没收藏的应用会被这一笔抹掉。做法是<b>按位填回</b>：
     * 走一遍原名单，每碰到一个收藏就取新顺序里的下一个补上，没收藏的原地不动。
     * 效果 = 收藏那几个之间换了位，其它没受影响。
     */
    private List<String> mergeOrder(List<String> seg) {
        if (!LauncherPrefs.favOnly(this)) {
            return seg;
        }
        List<String> full = LauncherPrefs.apps(this);
        int k = 0;
        for (int i = 0; i < full.size() && k < seg.size(); i++) {
            if (favs.contains(full.get(i))) {
                full.set(i, seg.get(k++));
            }
        }
        return full;
    }

    // ------------------------------------------------------------------ 列表

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        tvHint.setText("正在读取应用…");
        new Thread(() -> {
            final List<AppRepo.Item> list = AppRepo.load(this);
            runOnUiThread(() -> {
                loading = false;
                all.clear();
                all.addAll(list);
                rebuild();
                if (wantSortDialog) {
                    wantSortDialog = false;
                    showSort();
                }
            });
        }, "extrot-launcher-load").start();
    }

    private String query() {
        return etSearch == null || etSearch.getText() == null
                ? "" : etSearch.getText().toString();
    }

    /**
     * 重排配置页的列表：已选（按当前排序）在前，未选（按名称）在后。
     *
     * <p>每次勾选、每次搜索、每次改排序都走它 —— 只有这一个入口，
     * 顺序规则就不会出现"这里对了那里不对"。
     */
    private void rebuild() {
        String q = query();
        final List<AppRepo.Item> filtered = AppRepo.filter(all, q);
        final Set<String> picked = new HashSet<>(LauncherPrefs.apps(this));

        // 收藏那份一起读进来：格子里的星标、长按菜单那一档的名字都要用
        favs.clear();
        favs.addAll(LauncherPrefs.favs(this));

        final boolean favOnly = LauncherPrefs.favOnly(this);
        List<AppRepo.Item> sel = new ArrayList<>();
        List<AppRepo.Item> un = new ArrayList<>();
        for (AppRepo.Item it : filtered) {
            if (favOnly) {
                // 只看收藏：没收藏的整个不出现（它们的勾选状态没变，只是不在这一屏显示）
                if (favs.contains(it.pkg)) {
                    sel.add(it);
                }
                continue;
            }
            if (picked.contains(it.pkg)) {
                sel.add(it);
            } else {
                un.add(it);
            }
        }
        // 已选那段按当前排序方式排；未选那段保持 AppRepo 给的中文名称序
        LauncherPrefs.sortItems(this, sel);

        shown.clear();
        shown.addAll(sel);
        shown.addAll(un);
        selectedCount = sel.size();

        if (gvApps != null) {
            gvApps.setDropLimit(selectedCount);
            // 搜索的时候列表只剩几条，拖它会把顺序拖乱 —— 直接禁用
            gvApps.setDragEnabled(q.trim().isEmpty() && selectedCount > 1);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateCount();
        updateHint();
    }

    private void updateCount() {
        if (tvCount == null) {
            return;
        }
        if (LauncherPrefs.favOnly(this)) {
            // 筛选态下"已选 N 个"会被看错（屏幕上根本没有 N 个）—— 直接说在筛什么
            tvCount.setText(getString(R.string.launcher_fav_count, favs.size()));
            return;
        }
        int n = LauncherPrefs.apps(this).size();
        tvCount.setText(n == 0
                ? getString(R.string.launcher_pick_none)
                : getString(R.string.launcher_apps_fmt, n));
    }

    private void updateHint() {
        if (tvHint == null) {
            return;
        }
        String q = query().trim();
        if (loading) {
            tvHint.setText("正在读取应用…");
        } else if (all.isEmpty()) {
            tvHint.setText("没读到应用");
        } else if (!q.isEmpty()) {
            tvHint.setText(shown.isEmpty()
                    ? getString(R.string.launcher_search_empty)
                    : getString(R.string.launcher_hint_search));
        } else {
            tvHint.setText(LauncherPrefs.favOnly(this)
                    ? R.string.launcher_hint_fav : R.string.launcher_hint_tap);
        }
    }

    // ------------------------------------------------------------------ 导入 / 导出

    /**
     * 底栏那个「导入」：其实是「清单与顺序」两个方向的事，所以弹出来让用户挑。
     *
     * <p><b>导入系统外屏清单</b> —— 读系统里那份 multistar 白名单（{@link CoverListImport}），
     * 整份替换当前勾选。<b>把当前顺序写入副屏桌面</b> —— 反向：把这里排好的顺序<b>一次性</b>交给
     * 副屏桌面（{@link HomePrefs}）。两边平时是各走各的两份顺序，只有点了这一项才同步一次。
     *
     * <p>「读副屏桌面的顺序」没有做成按钮 —— 它是排序器里的一个选项
     * （{@link LauncherPrefs#SORT_HOME}）：做成排序方式比做成一次性导入更好，副屏桌面那份改了
     * 这一项跟着变，不用再导一次。
     */
    private void showIoDialog() {
        final String[] titles = {
                getString(R.string.launcher_io_sys),
                getString(R.string.launcher_io_export)};
        final String[] subs = {
                getString(R.string.launcher_io_sys_sub),
                getString(R.string.launcher_io_export_sub)};

        BaseAdapter ia = new BaseAdapter() {
            @Override
            public int getCount() {
                return titles.length;
            }

            @Override
            public Object getItem(int position) {
                return titles[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = getLayoutInflater().inflate(R.layout.item_io_choice, parent, false);
                }
                ((TextView) v.findViewById(R.id.ioTitle)).setText(titles[position]);
                ((TextView) v.findViewById(R.id.ioSub)).setText(subs[position]);
                return v;
            }
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_io_title)
                .setAdapter(ia, (d, which) -> {
                    if (which == 0) {
                        doImportSystem();
                    } else {
                        doExportHome();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 把系统里那份已有的外屏应用清单搬进来。
     *
     * <p>整件事是"读系统设置"，必须走 Shizuku，而且是阻塞调用 ⇒ 丢后台线程，
     * 结果回主线程弹确认框（覆盖已有勾选这种事不能默默干）。
     */
    private void doImportSystem() {
        if (!ShellRunner.isReady()) {
            toast(getString(R.string.launcher_import_fail));
            return;
        }
        toast("正在读取清单…");
        new Thread(() -> {
            final List<String> found = CoverListImport.readInstalled(this);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (found.isEmpty()) {
                    toast(getString(R.string.launcher_import_none));
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.launcher_import)
                        .setMessage(getString(R.string.launcher_import_confirm, found.size()))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            LauncherPrefs.setApps(this, found);
                            rebuild();
                            Log.i(TAG, "导入了 " + found.size() + " 个应用");
                            toast(getString(R.string.launcher_import_done, found.size()));
                        })
                        .show();
            });
        }, "extrot-launcher-import").start();
    }

    /**
     * 反向：把本页现在的顺序<b>一次性</b>交给副屏桌面（{@link SecondaryHomeActivity}）。
     *
     * <p>这是两份顺序之间<b>唯一</b>的搬运方式 —— 搬完就各走各的：以后在启动器里再调手动顺序、
     * 或者切成按名称 / 按常用，副屏桌面都不会跟着变。
     *
     * <p>写进去的是「所见即所写」：当前屏幕上「已选」那一段的先后，就是副屏桌面拿到的顺序
     * （所以正按名称排时，导过去的就是名称序）。
     */
    private void doExportHome() {
        List<String> order;
        if (query().trim().isEmpty()) {
            // 所见即所写：用户看到的"已选"那一段，就是导出去的顺序
            order = new ArrayList<>();
            for (int i = 0; i < selectedCount && i < shown.size(); i++) {
                order.add(shown.get(i).pkg);
            }
        } else {
            order = LauncherPrefs.apps(this);
        }
        if (order.isEmpty()) {
            toast(getString(R.string.launcher_io_export_empty));
            return;
        }
        HomePrefs.setApps(this, order);
        toast(getString(R.string.launcher_io_export_done, order.size()));
        Log.i(TAG, "已把 " + order.size() + " 项顺序写入副屏桌面");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_clear)
                .setMessage(R.string.launcher_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    LauncherPrefs.setApps(this, new ArrayList<>());
                    rebuild();
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 适配器

    private class PickAdapter extends BaseAdapter implements DragGrid.HiddenSlot {

        /** 正被拖着的那一格（要藏起来）。-1 = 没有。 */
        private int hiddenPos = -1;

        @Override
        public void setHiddenPosition(int position) {
            hiddenPos = position;
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_app_pick, parent, false);
            }
            AppRepo.Item it = shown.get(position);
            boolean on = LauncherPrefs.isSelected(CoverLauncherActivity.this, it.pkg);

            ImageView ic = v.findViewById(R.id.pickIcon);
            TextView tv = v.findViewById(R.id.pickLabel);
            ImageView ck = v.findViewById(R.id.pickCheck);
            ImageView star = v.findViewById(R.id.pickFav);

            tv.setText(it.label);

            // 「图标大小」：布局里那个 40dp 只是占位，真正多大在这里定
            final int side = dp(iconDp());

            /*
             * 「图标形状」：跟小组件那边走同一份裁剪路径
             * （CoverLauncherService.maskPath），所以配置页里看着是什么形状，
             * 外屏上就是什么形状 —— 这两边要是各画各的，用户调完还得合上手机确认。
             * 「原样」档直接挂 drawable：没必要为了"什么都不做"先画一张位图。
             */
            final int shape = LauncherPrefs.iconShape(CoverLauncherActivity.this);
            try {
                if (shape == LauncherPrefs.SHAPE_APP) {
                    ic.setImageDrawable(it.icon);
                } else {
                    ic.setImageBitmap(CoverLauncherService.toBitmap(it.icon, side, shape));
                }
            } catch (Throwable ignored) {
                ic.setImageDrawable(null);
            }

            ViewGroup.LayoutParams lp = ic.getLayoutParams();
            if (lp != null && (lp.width != side || lp.height != side)) {
                lp.width = side;
                lp.height = side;
                ic.setLayoutParams(lp);
            }

            // 「行距」：GridView 的 verticalSpacing 改不了，只能靠格子自己撑上下边距
            final int pad = dp(LauncherPrefs.rowPadDp(CoverLauncherActivity.this));
            final boolean showLabel = LauncherPrefs.showLabel(CoverLauncherActivity.this);
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(),
                    showLabel ? pad : pad * 2);

            // 勾选态不画框：没勾上的整体压暗，勾上的全亮 + 右上角绿勾
            ic.setAlpha(on ? 1f : 0.42f);
            tv.setAlpha(on ? 1f : 0.55f);
            tv.setVisibility(showLabel ? View.VISIBLE : View.GONE);
            ck.setVisibility(on ? View.VISIBLE : View.GONE);
            // 收藏过的：图标左上角一颗小星
            star.setVisibility(favs.contains(it.pkg) ? View.VISIBLE : View.GONE);

            /*
             * 拖拽相关：
             *   · 被拖着的那一格要透明（不能用 INVISIBLE，那会牵动布局）；
             *   · 位移必须清回 0 —— 视图是回收复用的，上一轮让位动画留下的
             *     translation 不清掉，格子会带着偏移冒出来。
             */
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setAlpha(position == hiddenPos ? 0f : 1f);
            return v;
        }
    }

    /** 「图标大小」三档对应的边长（dp）—— 4 列时中档 40dp，格子约八十来 dp 宽 */
    private int iconDp() {
        switch (LauncherPrefs.iconSize(this)) {
            case LauncherPrefs.SIZE_SMALL:
                return 34;
            case LauncherPrefs.SIZE_LARGE:
                return 48;
            default:
                return 40;
        }
    }
}
