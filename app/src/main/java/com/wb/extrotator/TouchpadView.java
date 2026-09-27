package com.wb.extrotator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 副屏触控板的触摸面 —— <b>一块纯手势面板</b>，什么都不显示。
 *
 * <p>按笔记本触控板来：面板上不看图，手指盲摸，光标出现在你正在看的那块屏上。所以这个类不再
 * 持有画面，也不再有「画面分数坐标」那一层 —— 它只把手指的位移和手势交给 {@link Listener}，
 * 光标位置由 Activity 统一维护（光标已经搬到目标屏上去了，见 {@link CursorOverlay}）。
 *
 * <p><b>位移是相对的</b>：手指滑 dx/dy 像素 → 光标就往那个方向走 dx/dy × 灵敏度 像素，
 * 跟笔记本一样。不是「手指在哪就映射到屏幕的哪个位置」—— 那是数位板，不是触控板。
 *
 * <p><b>手势</b>：
 * <pre>
 *   单指滑动        移光标（跟手，逐帧）
 *   单指轻点        在光标处单击
 *   按住不动        长按（500ms 判出，此时才把 DOWN 发下去）
 *   按住再滑动      拖拽 —— <b>从判出那一刻起就跟手</b>：手指在哪，被拖的东西就走到哪，不必等松手
 *   双指上下滑      滚动
 *   双指左右滑      翻页
 * </pre>
 *
 * <p>⚠ <b>长按拖动为什么分两段</b>：旧版把「长按还是拖拽」全压到<b>松手那一刻</b>才判，然后发一条
 * {@code input swipe 起点→终点} —— 手指划过去的过程中屏幕上什么都没发生，松手才跳一下
 * （用户说的「得我松手后才能滑动」）。现在按下时就分好：
 * <pre>
 *   长按判出 → onHoldStart() → Activity 发 DOWN
 *   手指移动 → onMove()（移光标）+ onDragMove()（透传 MOVE，目标屏跟手）
 *   松手     → onHoldEnd()   → Activity 发 UP
 * </pre>
 * 代价是 MOVE 得节流（每条指令一个 shell 进程），按 ~130ms 一条发，丢了中间帧也不要紧 ——
 * 每次送的都是<b>最新</b>位置，视觉上跟得上。
 *
 * <p>⚠ <b>容差必须用 dp 尺度的 slop</b>：这个 View 的坐标空间是 1496×1440（物理屏 748×720 的
 * 2 倍），写「5 个视图像素」实际只有 <b>1.2dp</b> —— 真手指按住不动时的那点抖动就能跨过去，
 * 长按当场被取消，松手反而发出一条几乎零距离的「拖拽」。所以用 {@link ViewConfiguration}
 * 的系统标准值（已按密度缩放），并且「是长按还是拖拽」由这里一次判完，交给 Activity 执行。
 */
public class TouchpadView extends View {

    /** 手势回调。位移是视图像素，光标坐标由 Activity 管 */
    public interface Listener {
        /** 手指位移（视图 px），已按"要不要当抖动丢掉"过滤过 */
        void onMove(float dx, float dy);

        /** 在光标处单击 */
        void onTap();

        /**
         * 长按判出 —— 此刻手指<b>还按着</b>，Activity 应当把 DOWN 发下去。
         * 从这一下到 {@link #onHoldEnd()} 之间都是"按住"状态。
         */
        void onHoldStart();

        /**
         * 按住期间手指移动（视图 px）。
         *
         * <p>跟 {@link #onMove} 的分工：那个是移光标（逐帧、不走 shell）；
         * 这个是已经把 DOWN 按下去了，除了光标还要把位移透到目标屏上，
         * 被拖的东西才跟手。两条都会来 —— 别在其中一条里做去重。
         */
        void onDragMove(float dx, float dy);

        /** 松手：这次按住 / 拖动到此为止，Activity 应当把 UP 补上 */
        void onHoldEnd();

        /** 双指上下滑（正 = 向下），单位视图 px */
        void onScroll(float dy);

        /**
         * 双指左右翻页。
         *
         * <p>为什么不靠拖动去翻：拖着一个图标蹭到屏幕边缘等它翻页，又慢又累，
         * 而且拖动本身还是逐帧注入。翻页做成一个独立手势，一次到位。
         *
         * @param dir +1 = 看下一屏（手指从右往左），-1 = 上一屏
         */
        void onPage(int dir);

        /** 手指按下 / 抬起 —— 用来给光标做按下反馈 */
        void onPress(boolean down);
    }

    /** 系统标准 touch slop（已按本 View 的密度缩放）：判"点了一下"还是"在移动 / 长按" */
    private final int slop;
    /** 按住多久算长按：用系统标准值（500ms），跟目标应用那边的判定对齐 */
    private final long longPressMs;

    private Listener listener;

    /** 面板右下角那行小字（光标坐标），由 Activity 喂 */
    private String infoText = "";
    /** 面板中央的静态提示，由 Activity 喂 */
    private String centerHint = "";

    private boolean pressed;
    private boolean moved;
    private boolean longPressed;
    private boolean scrollActive;
    private float lastX, lastY;
    private float downX, downY;
    private float touchX, touchY;
    /** 双指手势的累计位移：纵向 = 滚动，横向 = 翻页（松手时比大小决定算哪个） */
    private float scrollAcc, scrollAccX;

    private final Paint touchRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!pressed) {
                return;
            }
            // 判据是"此刻离按下点多远"，不是"这一路有没有发生过 MOVE"。
            // 用 moved 那个标志会把长按冤杀掉：手指按下去的第一下抖动只要越过 slop，
            // moved 就被置位，500ms 到点时长按直接被否决，松手又因为 moved=true 连轻点都不发
            // —— 表现就是"按了半天什么都没发生"，正是用户说的"经常没长按上"（v3.5 修）。
            if (Math.hypot(touchX - downX, touchY - downY) > slop) {
                return;   // 真在拖：那不是长按
            }
            longPressed = true;
            // ⚠ 判出来就**立刻**让 Activity 把 DOWN 发下去，不再等到松手。
            // 等到松手就是旧版的行为：用户按住 → 手指划过去（屏上没动静）→ 松手才跳一下。
            if (listener != null) {
                listener.onHoldStart();
            }
            invalidate();
        }
    };

    public TouchpadView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        slop = Math.max(1, ViewConfiguration.get(c).getScaledTouchSlop());
        longPressMs = ViewConfiguration.getLongPressTimeout();
        // 面板坐标空间跟注入空间差好几倍，slop 换算过去到底多少，别靠猜 —— 打出来。
        android.util.Log.i("ExtTouchpad", "面板 density=" + d
                + " 本视图 " + getResources().getDisplayMetrics().widthPixels
                + "×" + getResources().getDisplayMetrics().heightPixels
                + " slop=" + slop + "px 长按阈值=" + longPressMs + "ms");

        touchRing.setStyle(Paint.Style.STROKE);
        touchRing.setStrokeWidth(2f * d);
        touchRing.setColor(0x66FFFFFF);
        touchDot.setColor(0x44FFFFFF);

        infoPaint.setColor(0xFF7C8898);
        infoPaint.setTextSize(12f * d);

        hintPaint.setColor(0xFF5A6472);
        hintPaint.setTextSize(13f * d);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1f * d);
        edgePaint.setColor(0x22FFFFFF);

        setFocusable(true);
        setClickable(true);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 右下角的状态小字（光标坐标 / 尺寸） */
    public void setInfoText(String s) {
        infoText = s == null ? "" : s;
        invalidate();
    }

    /** 面板中央的静态提示 */
    public void setCenterHint(String s) {
        centerHint = s == null ? "" : s;
        invalidate();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);

        // 一圈淡淡的边，暗示这块就是触控面
        float inset = 6f;
        cv.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, 10f, 10f, edgePaint);

        if (pressed) {
            // 手指在哪就在哪画个圈 —— 面板不看图，但得知道手摸到哪了
            float u = getResources().getDisplayMetrics().density;
            cv.drawCircle(touchX, touchY, 34f * u, touchDot);
            cv.drawCircle(touchX, touchY, 34f * u, touchRing);
            if (longPressed) {
                cv.drawCircle(touchX, touchY, 13f * u, touchDot);
            }
        }

        if (!centerHint.isEmpty()) {
            float w = hintPaint.measureText(centerHint);
            cv.drawText(centerHint, (getWidth() - w) / 2f, getHeight() / 2f, hintPaint);
        }

        if (!infoText.isEmpty()) {
            float w = infoPaint.measureText(infoText);
            cv.drawText(infoText, getWidth() - w - 16f, getHeight() - 16f, infoPaint);
        }
    }

    // ------------------------------------------------------------------ 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                moved = false;
                longPressed = false;
                scrollActive = false;
                scrollAcc = 0f;
                scrollAccX = 0f;
                lastX = downX = touchX = e.getX();
                lastY = downY = touchY = e.getY();
                postDelayed(longPress, longPressMs);
                if (listener != null) {
                    listener.onPress(true);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    // 两根手指 = 滚动。长按立刻取消，不然"两指刚落下"会被当成点了一下
                    removeCallbacks(longPress);
                    // 已经在按住状态了就先把手势收掉，否则目标屏会一直"按着"
                    if (longPressed) {
                        longPressed = false;
                        if (listener != null) {
                            listener.onHoldEnd();
                        }
                    }
                    moved = true;
                    scrollActive = true;
                    scrollAcc = 0f;
                    scrollAccX = 0f;
                    lastX = e.getX(0);
                    lastY = e.getY(0);
                    touchX = e.getX(0);
                    touchY = e.getY(0);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                float x = e.getX(0), y = e.getY(0);
                touchX = x;
                touchY = y;
                if (scrollActive || e.getPointerCount() >= 2) {
                    scrollAcc += (y - lastY);
                    scrollAccX += (x - lastX);
                    lastY = y;
                    lastX = x;
                    invalidate();
                    return true;
                }
                // ⚠ 这里**不更新 lastX/lastY**：手指真动起来时，这段被吞掉的位移
                // 会算进第一笔 onMove，不会凭空丢掉。
                if (!moved && Math.hypot(x - lastX, y - lastY) < slop) {
                    return true;   // 还在 slop 里：不算移动，光标一格格都不挪，长按继续候着
                }
                float dx = x - lastX, dy = y - lastY;
                moved = true;
                // ⚠ 这里**不 removeCallbacks(longPress)**：长按倒计时交给它自己去判
                // （判据是"离按下点的距离"）。一 MOVE 就撤掉的话，手指刚开始那一下抖动
                // 只要越过 slop 就把长按提前毙了，而那时离 500ms 还早着呢。
                // 真正的拖拽不需要在这里取消 —— 到点时位移已经远超 slop，runnable 自己会退。
                lastX = x;
                lastY = y;
                if (listener != null) {
                    listener.onMove(dx, dy);
                    if (longPressed) {
                        // 按住状态：位移同时透到目标屏，被拖的东西跟手走
                        listener.onDragMove(dx, dy);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                return true;

            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                pressed = false;
                if (listener != null) {
                    listener.onPress(false);
                }
                if (scrollActive) {
                    scrollActive = false;
                    float accY = scrollAcc, accX = scrollAccX;
                    scrollAcc = 0f;
                    scrollAccX = 0f;
                    // 横向比纵向大 ⇒ 当翻页；否则当滚动。
                    // 判据放在松手这一刻，是因为两指手势一开始分不出用户要往哪边划。
                    if (Math.abs(accX) > Math.abs(accY)) {
                        if (Math.abs(accX) > slop * 2 && listener != null) {
                            // 手指往左划 = 看右边那一屏（内容左移），跟手机上的习惯一致
                            listener.onPage(accX < 0 ? +1 : -1);
                        }
                    } else if (Math.abs(accY) > slop && listener != null) {
                        listener.onScroll(accY);
                    }
                    invalidate();
                    return true;
                }
                // "按住 / 拖动"从这里结束。是长按还是拖拽、拖了多远，都由上面那一串
                // DOWN + MOVE 自己表达了，这里不再回头补一条 swipe —— 补了就成了"两次动作"。
                if (longPressed) {
                    longPressed = false;
                    if (listener != null) {
                        listener.onHoldEnd();
                    }
                    invalidate();
                    return true;
                }
                if (!moved && listener != null) {
                    listener.onTap();
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                pressed = false;
                if (longPressed) {
                    longPressed = false;
                    if (listener != null) {
                        listener.onHoldEnd();
                    }
                }
                scrollActive = false;
                if (listener != null) {
                    listener.onPress(false);
                }
                invalidate();
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        // ⚠ 页面被拆掉时如果还按着，必须把 UP 补出去 ——
        // 少这一下目标屏那边会一直维持"按下"状态（拖到一半就卡住不放）。
        if (longPressed) {
            longPressed = false;
            if (listener != null) {
                listener.onHoldEnd();
            }
        }
        super.onDetachedFromWindow();
    }
}
