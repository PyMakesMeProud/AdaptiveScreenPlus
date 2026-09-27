package com.wb.extrotator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * 按键快捷指令的「外部入口」—— 一个透明的中转 Activity：不显示任何界面，收到 intent 就执行动作、
 * 给一句 toast、立刻关掉。
 *
 * <p>用 Activity 而不是纯广播：三星「侧键 → 唤醒数字助手」这条链只能唤起一个<b>组件</b>
 * （见 {@code settings get secure assistant}），它认的就是 Activity；广播入口另有
 * {@link QuickActionReceiver}。
 *
 * <p>三种被调用的方式：<b>长按电源键</b> —— 在「设置 → 高级功能 → 侧键」里把长按设为「唤醒数字
 * 助手」并指向本组件（走 {@code ACTION_ASSIST}）；<b>三星日常程序 / MacroDroid / Tasker</b> ——
 * 动作填自定义 action {@code com.wb.extrotator.action.RUN_ACTION}、extra 传 {@code action_key}；
 * <b>静态快捷方式</b> —— 见 {@code res/xml/quick_actions.xml}，桌面长按图标 / 日常程序的
 * 「应用快捷方式」里能直接选到。
 *
 * <p>⚠ 防重入：动作只在 onCreate 里跑一次。折叠/展开会换屏，系统可能把这个 Activity 掀起来重造，
 * 不判的话动作会执行两遍（小组件那边已栽过一次，见 RULES §9）。
 */
public class QuickActionActivity extends Activity {

    private static final String TAG = "QuickAction";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ① 重建就不干活：我们不是真的界面，重造只可能是被系统掀起来的
        if (savedInstanceState != null) {
            Log.i(TAG, "onCreate 重建，跳过（防止动作跑两遍）");
            finish();
            return;
        }

        final String key = resolveKey(getIntent());
        if (key == null) {
            Log.w(TAG, "没拿到动作参数，直接退出");
            finish();
            return;
        }

        // ② 必须在后台线程：这些动作里有 sleep 和 binder 调用
        new Thread(() -> {
            final String err = QuickActions.run(getApplicationContext(), key);
            runOnUiThread(() -> {
                String text = (err == null)
                        ? QuickActions.label(this, key) + " 已完成"
                        : QuickActions.label(this, key) + "：" + err;
                try {
                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
                finish();
            });
        }, "quick-action").start();
    }

    /**
     * 从 intent 里推出要执行哪个动作。
     * 没有 {@code action_key} 时：
     *  - 被当成「数字助手」唤起（ACTION_ASSIST / VOICE_ASSIST）→ 用设置里指定的默认动作
     *  - 其它情况 → null，直接退出，不乱猜
     */
    private String resolveKey(Intent it) {
        if (it == null) {
            return null;
        }
        String k = it.getStringExtra(QuickActions.EXTRA_ACTION);
        if (QuickActions.isValidKey(this, k)) {
            return k;
        }
        String action = it.getAction();
        if (Intent.ACTION_ASSIST.equals(action)
                || "android.intent.action.VOICE_ASSIST".equals(action)) {
            return QuickPrefs.assistAction(this);
        }
        return null;
    }
}
