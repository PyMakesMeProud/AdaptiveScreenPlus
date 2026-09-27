package com.wb.extrotator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 「装了 / 卸了 / 更新了应用」→ 让 {@link AppIconLib} 重新对一遍图标库。
 *
 * <p><b>为什么需要它</b>：老虎机的摇奖图案是手机里已装应用的图标，图标库是抄下来的副本；
 * 新装一个应用如果不补进去，摇奖盘上就永远少那一张。用户不会为此专门去点个"同步"按钮，
 * 所以这条链路得自己接上。
 *
 * <p>⚠ 真正的活全丢给后台线程：这几条广播是**广播队列里**送过来的，接收器卡住会牵连
 * 后面的广播；而且同步要读别的 APK 的资源，慢的时候上百毫秒。
 *
 * <p>⚠ 这里只认「包名」这一个 extra（{@link Intent#getData()} 的 scheme 是 package），
 * 不做任何删除动作 —— 该删什么由 {@link AppIconLib#sync} 自己按当前清单算，
 * 免得两个地方各有一套"什么算失效"的判断，迟早跑偏。
 */
public class AppIconReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String act = intent == null ? null : intent.getAction();
        if (act == null) {
            return;
        }
        if (!Intent.ACTION_PACKAGE_ADDED.equals(act)
                && !Intent.ACTION_PACKAGE_REMOVED.equals(act)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(act)) {
            return;
        }
        // 只跟着"装了/卸了"走，不管数据清空、不跟 partition 变化
        if (Intent.ACTION_PACKAGE_REMOVED.equals(act)
                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return;   // 更新过程中的那一次 REMOVED，接下来还有一条 ADDED，让它一次做完
        }
        AppIconLib.syncAsync(context);
        // 通知面板左栏「选常用应用」那一页也存着一份应用清单，跟着一起作废
        // （不然刚装的应用要等下次进程重启才出现在列表里）
        CoverNotifCenter.dropAppCache();
    }
}
