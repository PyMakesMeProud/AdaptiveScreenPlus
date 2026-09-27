package com.wb.extrotator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;

/**
 * 一个能<b>长按拖拽排序</b>的 GridView。
 *
 * <p><b>为什么自己写，不用系统的拖放框架</b>：{@code startDragAndDrop()} 那套是跨应用拖数据的
 * （拖文件、拖文本），阴影是系统画的、大小固定、alpha 固定，松手还得回一个 DragEvent ——
 * 做图标排序等于绕一大圈还拿不回手感。这里就三件事，全都自己来：
 * <b>跟手</b> —— 长按之后把那一格画成位图，塞进外层 FrameLayout 当浮层，手指一动就改它的 margin
 * （位移 1:1，没有阻尼也没有延迟）；<b>让位</b> —— 拖到哪一格数据就当场挪过去，被挤开的格子用
 * 170ms 平移动画滑到自己新位置上；<b>落位</b> —— 松手时把最终位置回报给 {@link OrderListener}，
 * 由页面写盘。
 *
 * <p><b>怎么用</b>：
 * <pre>
 *   DragGrid g = findViewById(R.id.gvPick);
 *   g.setDragHost(findViewById(R.id.gridHost));   // ⚠ 必须是包住它的 FrameLayout
 *   g.setOrderListener(this);
 *   g.setDropLimit(n);                            // 只允许在「已选」那一段里拖
 * </pre>
 *
 * <p>⚠ <b>两个必须配合的约定</b>：被拖走的那一格要「消失」，靠适配器实现 {@link HiddenSlot}
 * 按位置把 alpha 置 0 —— 不能用 {@code setVisibility(INVISIBLE)}，那会牵动 GridView 的布局；
 * 适配器的 {@code getView} 里要把 {@code translationX/Y} 清回 0（视图是回收复用的，
 * 不清就会带着上一轮的位移冒出来）。
 */
public class DragGrid extends GridView {

    /** 拖拽的回调。位置都是适配器里的 position。 */
    public interface OrderListener {
        /**
         * 拖动过程中经过了一格：数据该从 from 挪到 to 了。
         * 实现里要做的就一件事 —— 改数据 + {@code notifyDataSetChanged()}。
         */
        void onOrderMove(int from, int to);

        /** 松手了，最终落在 to。写盘、刷新小组件之类在这里做。from == to 表示没挪动。 */
        void onOrderChanged(int from, int to);

        /** 拖起来了 / 放下了。页面借这个换提示文案。 */
        void onDragStateChanged(boolean dragging);

        /**
         * 长按起了拖，但<b>手指没挪到别的格子</b>就松手了。
         *
         * <p>页面拿它弹"长按菜单"（配置页弹的是收藏 / 卸载两个选项）。
         * ⚠ 只有"按下去 → 长按判定成立 → 原地松手"才会来；
         * 手指挪过格子（那是真在排序）或者被系统 CANCEL 掉，都不会来。
         */
        void onHoldReleased(int position);
    }

    /** 适配器实现它，就能按位置把某一格藏起来（拖起来的那一格） */
    public interface HiddenSlot {
        void setHiddenPosition(int position);
    }

    /** 长按多久算"要拖"（毫秒）。比系统的 500ms 短一点，拖图标不该等那么久。 */
    private static final int HOLD_MS = 240;
    /** 离上下边缘多少像素开始自动滚动 */
    private static final int EDGE_PX = 72;
    /** 每次自动滚多少像素 / 隔多久滚一次 */
    private static final int SCROLL_STEP = 10;
    private static final int SCROLL_EVERY_MS = 24;

    private OrderListener listener;
    private ViewGroup dragHost;

    private boolean dragEnabled = true;
    /** 只允许落在 [0, dropLimit) 这一段里 —— 配置页用它挡住"未勾选"那一段 */
    private int dropLimit = Integer.MAX_VALUE;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private int holdPos = -1;
    private boolean dragging;
    /** 起拖之后有没有挪到过别的格子 —— 没挪过才算"长按原地松手" */
    private boolean movedSinceHold;
    private int dragFrom = -1;
    private int dragTo = -1;
    private View dragView;
    private Bitmap dragBmp;
    private float downX, downY, lastX, lastY;
    private float grabDX, grabDY;
    private int touchSlop;
    private int scrollDir;

    /** 这一帧还没摆的让位动画（同一帧里连挪两格时以后一个为准） */
    private boolean animPending;
    private int animFrom = -1;
    private int animTo = -1;

    private final Runnable holdTrigger = new Runnable() {
        @Override
        public void run() {
            startDrag();
        }
    };

    private final Runnable scrollTick = new Runnable() {
        @Override
        public void run() {
            if (!dragging || scrollDir == 0 || !canScrollVertically(scrollDir)) {
                return;
            }
            scrollListBy(scrollDir * SCROLL_STEP);
            hover(lastX, lastY);
            ui.postDelayed(this, SCROLL_EVERY_MS);
        }
    };

    public DragGrid(Context c) {
        super(c);
        init();
    }

    public DragGrid(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public DragGrid(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    // ------------------------------------------------------------------ 配置

    public void setOrderListener(OrderListener l) {
        this.listener = l;
    }

    /**
     * 浮层要加在哪儿。
     *
     * <p>⚠ 必须是<b>包住这个 GridView 的 FrameLayout</b>：浮层用 leftMargin/topMargin 定位，
     * 坐标靠 {@code GridView.getLeft() + child.getLeft()} 算，所以两者之间不能再夹别的容器。
     */
    public void setDragHost(ViewGroup host) {
        this.dragHost = host;
    }

    public void setDragEnabled(boolean v) {
        this.dragEnabled = v;
    }

    /** 允许落下的位置上限（不含）。配置页传"已选个数"，未勾选那一段就拖不进去。 */
    public void setDropLimit(int limit) {
        this.dropLimit = limit <= 0 ? 0 : limit;
    }

    // ------------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragEnabled || dragHost == null || getAdapter() == null) {
            return super.onTouchEvent(e);
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                holdPos = pointToPosition((int) downX, (int) downY);
                movedSinceHold = false;
                if (holdPos == INVALID_POSITION || holdPos >= dropLimit) {
                    holdPos = -1;
                } else {
                    ui.postDelayed(holdTrigger, HOLD_MS);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                lastX = e.getX();
                lastY = e.getY();
                if (dragging) {
                    hover(lastX, lastY);
                    return true;
                }
                if (holdPos >= 0
                        && (Math.abs(lastX - downX) > touchSlop
                        || Math.abs(lastY - downY) > touchSlop)) {
                    // 手已经在滑了，说明是要滚列表，不是要拖图标
                    cancelHold();
                }
                break;

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    endDrag(true);
                    return true;
                }
                cancelHold();
                break;

            case MotionEvent.ACTION_CANCEL:
                // 被系统 / 父容器收走的那一下不算"原地松手"，不弹长按菜单
                if (dragging) {
                    endDrag(false);
                    return true;
                }
                cancelHold();
                break;

            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    private void cancelHold() {
        holdPos = -1;
        ui.removeCallbacks(holdTrigger);
    }

    // ------------------------------------------------------------------ 起拖

    private void startDrag() {
        ui.removeCallbacks(holdTrigger);
        final int pos = holdPos;
        holdPos = -1;
        if (pos < 0 || pos >= dropLimit || !isAttachedToWindow()) {
            return;
        }
        final View child = getChildAt(pos - getFirstVisiblePosition());
        if (child == null || child.getWidth() <= 0 || child.getHeight() <= 0) {
            return;
        }

        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(child.getWidth(), child.getHeight(), Bitmap.Config.ARGB_8888);
            child.draw(new Canvas(bmp));
        } catch (Throwable ignored) {
        }
        if (bmp == null) {
            return;
        }
        dragBmp = bmp;

        ImageView iv = new ImageView(getContext());
        iv.setImageBitmap(bmp);
        iv.setAlpha(0.92f);
        iv.setElevation(dp(8));
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(child.getWidth(), child.getHeight());
        lp.leftMargin = getLeft() + child.getLeft();
        lp.topMargin = getTop() + child.getTop();
        dragHost.addView(iv, lp);
        dragView = iv;

        // 手指按住的那一点，在浮层里对应哪个位置 —— 这样图标不会"跳"到手指中心
        grabDX = downX - child.getLeft();
        grabDY = downY - child.getTop();

        dragging = true;
        dragFrom = pos;
        dragTo = pos;
        hide(pos);

        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ViewParentCompat.requestDisallow(getParent());
        if (listener != null) {
            listener.onDragStateChanged(true);
        }
    }

    // ------------------------------------------------------------------ 拖动中

    private void hover(float x, float y) {
        // 1) 浮层跟手
        if (dragView != null && dragView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dragView.getLayoutParams();
            lp.leftMargin = Math.round(getLeft() + x - grabDX);
            lp.topMargin = Math.round(getTop() + y - grabDY);
            dragView.setLayoutParams(lp);
        }

        // 2) 该落在第几格
        int pos = pointToPosition((int) x, (int) y);
        if (pos != INVALID_POSITION) {
            if (pos >= dropLimit) {
                pos = dropLimit - 1;
            }
            if (pos >= 0 && pos != dragTo) {
                final int from = dragTo;
                dragTo = pos;
                movedSinceHold = true;
                if (listener != null) {
                    listener.onOrderMove(from, pos);
                }
                hide(pos);
                animateShift(from, pos);
            }
        }

        // 3) 贴边时自动滚，方便把图标拖出可视区
        int dir = 0;
        if (y < EDGE_PX + getPaddingTop()) {
            dir = -1;
        } else if (y > getHeight() - EDGE_PX - getPaddingBottom()) {
            dir = 1;
        }
        if (dir != scrollDir) {
            scrollDir = dir;
            ui.removeCallbacks(scrollTick);
            if (dir != 0) {
                ui.postDelayed(scrollTick, SCROLL_EVERY_MS);
            }
        }
    }

    /** 告诉适配器把哪一格藏起来，然后重画 */
    private void hide(int pos) {
        if (getAdapter() instanceof HiddenSlot && getAdapter() instanceof BaseAdapter) {
            ((HiddenSlot) getAdapter()).setHiddenPosition(pos);
            ((BaseAdapter) getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 让位动画。
     *
     * <p>数据这时候刚改完但还没重新布局，所以不能马上算坐标 ——
     * 挂一个"画之前"的钩子，等这一帧布局落定再摆。
     *
     * <p>位移量不用查旧坐标表：格子大小是均匀的，{@code position} 本身就能算出行列，
     * 旧位置减新位置直接乘格子宽高就是该滑多远。这样连"从屏幕外滑进来"也天然算得对。
     */
    private void animateShift(final int from, final int to) {
        animFrom = from;
        animTo = to;
        if (animPending) {
            return;
        }
        animPending = true;
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver vto = getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnPreDrawListener(this);
                }
                animPending = false;
                applyShift(animFrom, animTo);
                return true;
            }
        });
    }

    private void applyShift(int from, int to) {
        final int cols = getNumColumns();
        if (cols <= 1 || getChildCount() == 0) {
            return;
        }
        final View first = getChildAt(0);
        final int cw = first.getWidth();
        final int ch = first.getHeight();
        if (cw <= 0 || ch <= 0) {
            return;
        }
        final int hs = getHorizontalSpacing();
        final int vs = getVerticalSpacing();
        final int fv = getFirstVisiblePosition();

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            final int p = fv + i;
            int prev = p;
            if (from < to) {
                if (p >= from && p < to) {
                    prev = p + 1;
                }
            } else if (from > to) {
                if (p > to && p <= from) {
                    prev = p - 1;
                }
            }
            if (prev == p) {
                continue;
            }
            final int dr = prev / cols - p / cols;
            final int dc = prev % cols - p % cols;
            if (dr == 0 && dc == 0) {
                continue;
            }
            child.setTranslationX(dc * (cw + hs));
            child.setTranslationY(dr * (ch + vs));
            child.animate().translationX(0f).translationY(0f).setDuration(170).start();
        }
    }

    // ------------------------------------------------------------------ 落位

    private void endDrag(boolean released) {
        ui.removeCallbacks(scrollTick);
        ui.removeCallbacks(holdTrigger);
        scrollDir = 0;

        if (dragView != null && dragView.getParent() == dragHost) {
            dragHost.removeView(dragView);
        }
        dragView = null;
        if (dragBmp != null && !dragBmp.isRecycled()) {
            dragBmp.recycle();
        }
        dragBmp = null;

        hide(-1);

        final int from = dragFrom;
        final int to = dragTo;
        dragging = false;
        dragFrom = -1;
        dragTo = -1;

        if (listener != null) {
            listener.onDragStateChanged(false);
            if (from >= 0 && to >= 0 && from != to) {
                listener.onOrderChanged(from, to);
            } else if (released && from >= 0 && !movedSinceHold) {
                listener.onHoldReleased(from);
            }
        }
    }

    // ------------------------------------------------------------------ 杂项

    private int dp(int v) {
        float d;
        try {
            d = getResources().getDisplayMetrics().density;
        } catch (Throwable t) {
            d = 2f;
        }
        return Math.round(v * d);
    }

    /** 小工具：请求父容器别抢这次触摸。写成静态是为了把 API 差异挡在一处。 */
    private static final class ViewParentCompat {
        static void requestDisallow(android.view.ViewParent p) {
            try {
                if (p != null) {
                    p.requestDisallowInterceptTouchEvent(true);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
