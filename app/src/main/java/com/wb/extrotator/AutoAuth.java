package com.wb.extrotator;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 代点弹窗的<b>肯定性按钮</b>。
 *
 * <p>⚠ <b>默认不代点</b>：常开会把系统里<b>所有</b>带「允许 / 确定」的弹窗都点掉。只有
 * {@link AdbWifi#unfoldRoute} 在展开内屏那一小段里 {@link #arm()}、折回后 {@link #disarm()}。
 *
 * <p>判定顺序：先勾 {@code android:id/sec_alwaysUse}（勾上后该网络永久进信任名单、不再弹框），
 * 再按 {@code android:id/button1}（安卓对话框正面按钮的通用 id，别的授权弹窗也有效）；都没有
 * 才按文字<b>精确等于</b>「允许 / Allow / 确定 / OK」找 —— 必须精确等于，无线调试设置页整页都有
 * 「无线调试」四个字，用 contains 会把设置页本身当成弹窗。
 *
 * <p>⚠ 取根只用 {@code getRootInActiveWindow()}；{@code event.getSource()} 常常只是「变化的那一个
 * 节点」，拿它当根一个按钮都找不到。
 */
public final class AutoAuth {

    private static final String TAG = "AutoAuth";

    /**
     * 事件到达后等这么久再动手：弹窗先被创建，几百毫秒后按钮才画进无障碍树，
     * 立刻抓树是抓不到的。
     */
    private static final long DELAY_MS = 500L;

    /** 两次处理之间的最小间隔，避免设置页那种"事件刷屏"把 CPU 烧了 */
    private static final long THROTTLE_MS = 400L;

    /** 按钮本身点不动时，往上找几层可点的祖先 */
    private static final int MAX_ANCESTOR = 6;

    /** 找不到 {@code android:id/button1} 时的兜底文字（必须精确相等） */
    private static final String[] POSITIVE_TEXTS = {"允许", "Allow", "确定", "OK"};

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable CLICK = AutoAuth::click;

    private static volatile AccessibilityService sSvc;
    private static volatile long sLastAt;

    /**
     * 现在要不要代点。⚠ 必须常关：开着的代价是系统里所有「允许 / 确定」的弹窗都被点掉。
     */
    private static volatile boolean sArmed;

    private AutoAuth() {
    }

    /** 无障碍服务连上 / 断开时由 {@link VolumeKeyService} 通知。 */
    static void attach(AccessibilityService svc) {
        sSvc = svc;
        Log.i(TAG, "代点就绪（服务已连接）");
    }

    static void detach() {
        sSvc = null;
        H.removeCallbacks(CLICK);
    }

    /** 无障碍服务在跑才点得动 —— {@link AdbWifi} 用它决定要不要走"展开"那条路。 */
    public static boolean isReady() {
        return sSvc != null;
    }

    /** 武装：从现在起开始代点。由 {@link AdbWifi#unfoldRoute} 在展开内屏之前调用。 */
    public static void arm() {
        sArmed = true;
        sLastAt = 0L;
        Log.i(TAG, "代点已开启（只在这一次开无线调试期间）");
    }

    /**
     * 卸下：立刻停止代点，并把还没执行的那一次点击取消掉。
     * {@link AdbWifi#unfoldRoute} 在折回之后无论成败都会调它。
     */
    public static void disarm() {
        sArmed = false;
        H.removeCallbacks(CLICK);
        Log.i(TAG, "代点已关闭");
    }

    /**
     * 从 {@link VolumeKeyService#onAccessibilityEvent} 调进来。
     *
     * <p>⚠ 配置里必须订 {@code typeWindowContentChanged}：按钮画进树那一刻发的是它，
     * 只订 {@code typeWindowStateChanged} 等于"看一眼没有、永远没有第二次机会"。
     */
    static void handle(AccessibilityEvent ev, AccessibilityService svc) {
        if (ev == null || svc == null) {
            return;
        }
        sSvc = svc;
        if (!sArmed) {
            return;   // 没在开无线调试 => 不看、不点，弹窗交给用户自己
        }
        int type = ev.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        CharSequence p = ev.getPackageName();
        if (p != null && isExcluded(p.toString())) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastAt < THROTTLE_MS) {
            return;
        }
        sLastAt = now;
        H.postDelayed(CLICK, DELAY_MS);
    }

    private static boolean isExcluded(String pkg) {
        // 自己的界面不要代点（我们会自己处理）；Shizuku 的授权框也不碰（阿田同样排除）
        return "com.wb.extrotator".equals(pkg) || "moe.shizuku.privileged.api".equals(pkg);
    }

    private static void click() {
        AccessibilityService svc = sSvc;
        if (svc == null || !sArmed) {
            return;   // 延后这 500ms 里可能已经被 disarm 了
        }
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) {
                return;
            }

            // ① 先勾「始终允许」——它和肯定性按钮是一对，勾了才不再弹
            AccessibilityNodeInfo cb = findById(root, "android:id/sec_alwaysUse");
            if (cb == null) {
                cb = findById(root, "android:id/alwaysUse");
            }
            if (cb != null && cb.isCheckable() && !cb.isChecked()) {
                if (cb.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "勾上「始终允许」: " + cb.getViewIdResourceName());
                }
            }

            // ② 再找肯定性按钮：先认通用 id，退到精确文字
            AccessibilityNodeInfo ok = findById(root, "android:id/button1");
            String how = "button1";
            if (ok == null) {
                ok = findByText(root, POSITIVE_TEXTS);
                how = "文字";
            }
            if (ok == null) {
                return;   // 这棵树里没有"允许"类按钮 ⇒ 不是要代点的弹窗，什么都不做
            }

            String what = how + "=" + text(ok);
            if (performClick(ok)) {
                Log.i(TAG, "点了肯定性按钮: " + what);
            } else {
                Log.w(TAG, "找到了按钮但点不动: " + what);
            }
        } catch (Throwable t) {
            Log.w(TAG, "代点失败", t);
        }
    }

    /** 按 view id 全树找 */
    private static AccessibilityNodeInfo findById(AccessibilityNodeInfo node, String id) {
        if (node == null) {
            return null;
        }
        try {
            if (id.equals(node.getViewIdResourceName())) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo hit = findById(node.getChild(i), id);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 按文字 / 无障碍说明**精确等于**目标找（不是 contains） */
    private static AccessibilityNodeInfo findByText(AccessibilityNodeInfo node, String[] wants) {
        if (node == null) {
            return null;
        }
        try {
            if (matches(node.getText(), wants) || matches(node.getContentDescription(), wants)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo hit = findByText(node.getChild(i), wants);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean matches(CharSequence cs, String[] wants) {
        if (cs == null) {
            return false;
        }
        String s = cs.toString().trim();
        for (String w : wants) {
            if (w.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点下去。按钮自己常常不可点（真正常用的 clickable 挂在它的父容器上），
     * 所以先试自己、再往上找最多 {@link #MAX_ANCESTOR} 层可点的祖先。
     */
    private static boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i <= MAX_ANCESTOR && cur != null; i++) {
            try {
                if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                cur = cur.getParent();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static String text(AccessibilityNodeInfo n) {
        try {
            CharSequence t = n.getText();
            if (t == null) {
                t = n.getContentDescription();
            }
            String s = t == null ? "?" : t.toString();
            String id = n.getViewIdResourceName();
            return id == null ? s : s + " (" + id + ")";
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
