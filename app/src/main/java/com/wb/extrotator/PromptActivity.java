package com.wb.extrotator;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 插入便携显示屏时的询问对话框 —— 一个「对话框样式」的 Activity，由 {@link RotationService}
 * 在检测到便携屏接入时拉起。
 *
 * <p>因为要在后台环境下显示，所以需要「显示在其他应用上层」权限；拿不到该权限时服务会退化成
 * 用 shell（{@code am start}）拉起，或者只挂一条横幅通知 —— 三种方式任一生效用户都能看到询问。
 *
 * <p><b>自适应</b>：手机横过来时窗口只有 300 多 dp 高，固定字号会把按钮顶到屏幕外面去，
 * 而对话框本身不可滚动 —— 用户就卡在「看不到确定按钮」上。做法是：整块内容按当前窗口大小
 * <b>等比缩放</b>（字号 / 内边距 / 间距一起缩，任何 DPI 下文字都是清楚的），正文放进 ScrollView
 * （窗口再小也能滚动），两个按钮固定在底部<b>不参与滚动</b> —— 永远看得见、点得到。
 */
public class PromptActivity extends Activity {

    private static final String TAG = "PromptActivity";

    public static final String EXTRA_DESC = "desc";
    public static final String EXTRA_W = "w";
    public static final String EXTRA_H = "h";

    /** 缩放上限 / 下限：窗口富裕时可以把字放到 1.2 倍，最紧也不低于 0.62 倍 */
    private static final float MAX_SCALE = 1.20f;
    private static final float MIN_SCALE = 0.62f;
    /** 弹窗最多占屏幕的比例（高度留点余量，免得贴着状态栏和导航栏） */
    private static final float MAX_W_RATIO = 0.94f;
    private static final float MAX_H_RATIO = 0.85f;
    /** 窗口最大宽度：大屏（横屏 880dp 宽）上别铺成一条横幅 */
    private static final float MAX_W_DP = 430f;

    private CheckBox cbNoAsk;

    /** 参与等比缩放的控件，以及它们的设计尺寸（只记录一次） */
    private final List<TextView> scaledText = new ArrayList<>();
    private final List<Float> scaledTextBase = new ArrayList<>();
    private final List<View> scaledPad = new ArrayList<>();
    private final List<int[]> scaledPadBase = new ArrayList<>();
    private final List<View> scaledMargin = new ArrayList<>();
    private final List<int[]> scaledMarginBase = new ArrayList<>();

    private boolean hasScaled = false;
    /** 当前生效的缩放系数 */
    private float lastK = MAX_SCALE;
    private int targetW = 0;
    private int maxH = 0;
    /** 最近一次真正设给窗口的高度 */
    private int windowH = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt);

        // 必须点按钮才算回答，避免"点到外面就消失"把这次询问静默跳过去
        setFinishOnTouchOutside(false);

        int w = getIntent().getIntExtra(EXTRA_W, 1080);
        int h = getIntent().getIntExtra(EXTRA_H, 1920);
        String desc = getIntent().getStringExtra(EXTRA_DESC);

        TextView tvExt = findViewById(R.id.tvPromptExt);
        tvExt.setText(desc == null || desc.isEmpty() ? "已连接便携显示屏" : desc);

        TextView tvRes = findViewById(R.id.tvPromptRes);
        // 传进来的 w/h 已经过竖屏规范化，这里明确标出来，免得用户以为自己填的横屏数字丢了
        tvRes.setText(w + " × " + h + "（竖屏）");

        cbNoAsk = findViewById(R.id.cbPromptNoAsk);

        findViewById(R.id.btnPromptYes).setOnClickListener(v -> answer(true));
        findViewById(R.id.btnPromptNo).setOnClickListener(v -> answer(false));

        // 既然弹窗已经出来了，兜底的横幅通知就撤掉
        RotationService.dismissPromptNotification(this);

        final View root = findViewById(R.id.promptRoot);
        root.post(() -> fitToWindow(root));
    }

    /**
     * 按当前窗口大小等比缩放内容，并把弹窗尺寸设成「内容刚好放得下」。
     *
     * <p>为什么不是简单套公式：窗口宽高和内容需要的高度之间没有稳定关系 —— 弹窗背景的圆角
     * 内边距、按钮的主题最小高度、文字折几行，只有真正布局过一次才知道。早先用「按比例估算」，
     * 结果横屏下总差那么几十像素，正好把最后一行（勾选框）挤出可视区。
     *
     * <p>所以分两步，并且<b>第二步以实测为准</b>：先给个大致高度把窗口摆上去、让布局真正跑一遍；
     * 再让 {@link #settle} 读出控件的<b>实测高度</b>算出精确需要多少 —— 比可用高度还高就整体缩小
     * 字号重来，否则把窗口设成「刚好装下」。字号能大就大（{@link #MAX_SCALE}），实在放不下才缩到
     * {@link #MIN_SCALE}，缩到下限之后仍放不下就交给正文滚动（底部按钮在滚动区之外，
     * 任何情况下都看得见）。
     */
    private void fitToWindow(View root) {
        if (hasScaled) {
            return;
        }
        hasScaled = true;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density <= 0f ? 1f : dm.density;

        // 窗口宽度：不超过屏幕的 94%，大屏上也不超过 430dp（免得铺成一条横幅）
        targetW = (int) Math.min(dm.widthPixels * MAX_W_RATIO, MAX_W_DP * density + 0.5f);
        maxH = (int) (dm.heightPixels * MAX_H_RATIO);

        collect(root);

        lastK = MAX_SCALE;
        applyScale(lastK);

        // 第一步：先按估的宽度粗量一下，把窗口摆上去让布局跑起来
        int estimateInner = Math.max(1, targetW - Math.round(density * 26f));
        int natural = measureNatural(estimateInner);
        windowH = Math.max(1, Math.min(natural, maxH));
        getWindow().setLayout(targetW, windowH);

        root.postDelayed(() -> settle(root, 0), 40L);
    }

    /**
     * 第二步（可重复）：用<b>实测</b>的「正文高度 vs 可视区高度」做闭环反馈，把窗口调到刚好。
     *
     * <p>为什么不直接算：正文高度在布局发生之前只能估（弹窗背景的内边距、按钮的主题最小高度、
     * 文字折几行都只有布局完才知道，差几十像素正好把最后一行挤出去），所以这里不猜 ——
     * 每轮读一次真实值，缺多少补多少，补到上限就整体缩字号重来。
     *
     * @param round 已重试次数，用来兜住「布局还没发生」的情况
     */
    private void settle(View root, int round) {
        View body = findViewById(R.id.promptBody);
        View footer = findViewById(R.id.promptFooter);
        ScrollView scroll = findViewById(R.id.promptScroll);
        if (body == null || footer == null || scroll == null) {
            return;
        }
        int viewport = scroll.getHeight();
        int content = body.getHeight();
        if (viewport <= 0 || content <= 0) {
            if (round < 6) {
                root.postDelayed(() -> settle(root, round + 1), 40L);
            }
            return;
        }

        int deficit = content - viewport;   // >0 = 正文比可视区高，最后几行被挡在外面
        Log.d(TAG, "settle k=" + lastK + " win=" + windowH
                + " viewport=" + viewport + " content=" + content
                + " deficit=" + deficit + " maxH=" + maxH);

        if (deficit > 0) {
            int want = Math.min(maxH, windowH + deficit);
            if (want > windowH) {
                // 还有变高的余地：撑到刚好装下
                windowH = want;
                getWindow().setLayout(targetW, want);
                root.postDelayed(() -> settle(root, round + 1), 40L);
                return;
            }
            // 已经撑到可用高度上限还是装不下 → 整体缩小字号再来一轮
            if (lastK > MIN_SCALE + 0.01f) {
                lastK = Math.max(MIN_SCALE, lastK * 0.9f);
                applyScale(lastK);
                windowH = maxH;
                getWindow().setLayout(targetW, maxH);
                root.postDelayed(() -> settle(root, 0), 40L);
                return;
            }
            // 缩到下限仍放不下：正文交给滚动，底部按钮在滚动区外，依然看得见
            return;
        }

        // 有富余（或刚好）就把窗口收成贴合内容，别留一大片空白
        int want = Math.max(1, Math.min(maxH, windowH + deficit));
        if (want < windowH) {
            windowH = want;
            getWindow().setLayout(targetW, want);
        }
    }

    /** 量一下内容在给定宽度下的"自然高度"（仅用于第一遍粗估窗口尺寸） */
    private int measureNatural(int innerW) {
        View root = findViewById(R.id.promptRoot);
        ScrollView scroll = findViewById(R.id.promptScroll);
        View body = findViewById(R.id.promptBody);
        View footer = findViewById(R.id.promptFooter);

        int specW = View.MeasureSpec.makeMeasureSpec(Math.max(1, innerW), View.MeasureSpec.EXACTLY);
        int specH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        body.measure(specW, specH);
        footer.measure(specW, specH);

        int footerGap = 0;
        ViewGroup.LayoutParams flp = footer.getLayoutParams();
        if (flp instanceof ViewGroup.MarginLayoutParams) {
            footerGap = ((ViewGroup.MarginLayoutParams) flp).topMargin;
        }
        return root.getPaddingTop() + root.getPaddingBottom()
                + body.getMeasuredHeight()
                + scroll.getPaddingTop() + scroll.getPaddingBottom()
                + footerGap + footer.getMeasuredHeight();
    }

    // ---------------------------------------------------------------- 等比缩放

    /** 递归记下每个控件的设计字号 / 内边距 / 间距 */
    private void collect(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            scaledText.add(tv);
            scaledTextBase.add(tv.getTextSize());
        }
        if (v.getPaddingLeft() != 0 || v.getPaddingTop() != 0
                || v.getPaddingRight() != 0 || v.getPaddingBottom() != 0) {
            scaledPad.add(v);
            scaledPadBase.add(new int[]{v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom()});
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            if (m.leftMargin != 0 || m.topMargin != 0
                    || m.rightMargin != 0 || m.bottomMargin != 0) {
                scaledMargin.add(v);
                scaledMarginBase.add(new int[]{m.leftMargin, m.topMargin,
                        m.rightMargin, m.bottomMargin});
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i));
            }
        }
    }

    private void applyScale(float k) {
        for (int i = 0; i < scaledText.size(); i++) {
            scaledText.get(i).setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledTextBase.get(i) * k);
        }
        for (int i = 0; i < scaledPad.size(); i++) {
            int[] p = scaledPadBase.get(i);
            scaledPad.get(i).setPadding(Math.round(p[0] * k), Math.round(p[1] * k),
                    Math.round(p[2] * k), Math.round(p[3] * k));
        }
        for (int i = 0; i < scaledMargin.size(); i++) {
            int[] m = scaledMarginBase.get(i);
            View v = scaledMargin.get(i);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mp = (ViewGroup.MarginLayoutParams) lp;
                mp.leftMargin = Math.round(m[0] * k);
                mp.topMargin = Math.round(m[1] * k);
                mp.rightMargin = Math.round(m[2] * k);
                mp.bottomMargin = Math.round(m[3] * k);
                v.setLayoutParams(mp);
            }
        }
    }

    // ---------------------------------------------------------------- 回答

    private void answer(boolean yes) {
        boolean noAsk = cbNoAsk != null && cbNoAsk.isChecked();
        RotationService.answerPrompt(this, yes, noAsk);
        finish();
    }

    @Override
    public void onBackPressed() {
        // 按返回键 = 本次不启用，但也不关闭「以后询问」这个开关
        RotationService.answerPrompt(this, false, false);
        finish();
    }
}
