package com.wb.extrotator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 按键快捷指令的「广播入口」—— 给自动化 App（MacroDroid / Tasker / 三星日常程序）用：
 * <pre>
 *   action : com.wb.extrotator.action.RUN_ACTION
 *   extra  : action_key = toggle_fold | cover_home | cover_rotate | cover_rotate_reset
 * </pre>
 *
 * <p>⚠ 广播是 exported 的（否则外部发不进来），但只认自定义 action，且 action_key 必须命中
 * {@link QuickActions#isValidKey} 白名单 —— 不给「传什么就执行什么」的口子。
 *
 * <p>⚠ {@code goAsync()} 必须：后面要跑 shell 和 sleep，直接放在 onReceive 里会阻塞主线程，
 * 且 onReceive 一返回进程可能就被回收。
 */
public class QuickActionReceiver extends BroadcastReceiver {

    private static final String TAG = "QuickActionRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !QuickActions.ACTION_RUN.equals(intent.getAction())) {
            return;
        }
        String key = intent.getStringExtra(QuickActions.EXTRA_ACTION);
        if (!QuickActions.isValidKey(context, key)) {
            Log.w(TAG, "忽略非法动作: " + key);
            return;
        }
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String msg = QuickActions.run(app, key);
                // 回写给「老虎机」：方向同步这类动作没有肉眼可见的即时变化，不回写的话
                // 用户按完只会觉得"这格子坏了"。组件那边把这句话写在底部那行。
                SlotWidgetProvider.noteResult(app, msg == null
                        ? app.getString(R.string.widget_slot_ran, QuickActions.label(app, key))
                        : msg);
            } catch (Throwable t) {
                Log.e(TAG, "执行失败: " + key, t);
            } finally {
                pr.finish();
            }
        }, "quick-action-rx").start();
    }
}
