package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * 副屏页共用的界面小工具。
 *
 * <p>为什么单开一层：几个页面的顶栏长得一样（标题 + 关闭），
 * "取目标屏"这件事写三遍必然跑偏（比如某一次忘了在后台线程查屏 ——
 * {@code dumpsys display} 要几百毫秒，放主线程就是点一下卡半秒）。
 * 收在这里，三个页面只调方法。
 */
public final class ExtUi {

    private ExtUi() {
    }

    /**
     * 查目标屏（后台线程做完再回调）。
     *
     * @param cb 主线程回调；找不到外接屏时传 null
     */
    public static void resolve(final Activity a, final Consumer<ExtScreen.Dev> cb) {
        new Thread(() -> {
            final ExtScreen.Dev dev = ExtScreen.find(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                cb.accept(dev);
            });
        }, "extrot-ext-find").start();
    }

    /**
     * 「ⓘ」弹窗 —— 跟主界面 / Beta 页同一套写法：正文在 strings.xml 的 info_* 里。
     * 界面上不放长说明，解释一律走这里。
     */
    public static void info(final Activity a, int iconId, final int titleRes, final int detailRes) {
        View icon = a.findViewById(iconId);
        if (icon == null) {
            return;
        }
        icon.setOnClickListener(v -> {
            if (a.isFinishing() || a.isDestroyed()) {
                return;
            }
            new AlertDialog.Builder(a)
                    .setTitle(titleRes)
                    .setMessage(detailRes)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    /**
     * 顶栏标题写什么：正常写「页面名 · 内屏 1080×2640」，读不到屏信息时直说。
     * 尺寸还是要摆出来 —— 分辨率被改过（wm 覆盖）时，一眼能看出注入坐标空间变了。
     */
    public static void title(Activity a, TextView tv, int pageRes, ExtScreen.Dev dev) {
        if (tv == null) {
            return;
        }
        String page = a.getString(pageRes);
        // ⚠ 屏名走 dev.labelRes()，不写死"内屏"：顶栏得如实写出目标屏的名字，用户据此判断"这一按发给谁"
        tv.setText(dev == null
                ? a.getString(R.string.ext_title_none, page)
                : a.getString(R.string.ext_title_dev, page,
                        a.getString(dev.labelRes()), dev.sizeText()));
    }

    public static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    /** 没有目标屏时的统一提示文案 */
    public static String noScreenHint(Activity a) {
        return a.getString(R.string.ext_no_screen);
    }
}
