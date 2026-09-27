package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「自定义操作」组件的配置页 —— 从组件上点空格子进来的。
 *
 * <p>两种进来方式，共用一个类：
 * <ul>
 *   <li>带着 {@link #EXTRA_SLOT}（点的是某一格）→ 直接把那一格的选单弹出来，选完就回桌面；</li>
 *   <li>不带（以后想做"整块设置"入口时用）→ 把四行都列出来，逐行点。</li>
 * </ul>
 *
 * <p>为什么做成正经 Activity 而不是在组件里点几层：RemoteViews 的交互只有"点一下、
 * 发一个 PendingIntent"，没法在组件上摆下拉选单。而这一步又是必须有的（四格挂什么得用户自己定），
 * 所以干脆跳到应用这边用系统对话框选完再回来 —— 顺带选中项、当前值、取消这些都不用自己造。
 *
 * <p>选完立刻 {@link CustomCoverWidgetProvider#refreshAll} 刷一遍组件，用户回到桌面
 * 就看见结果了，不用等下一次系统刷新。
 *
 * <p>⚠ 这里是<b>唯一</b>写 {@link CustomWidgetCfg} 的地方；组件那边只读不写（写会跟它的
 * "画崩就整块报废"这条线混在一起）。
 */
public class CustomCfgActivity extends Activity {

    /** 点的是第几格（0 起）；不带就是"全列出来" */
    public static final String EXTRA_SLOT = "slot";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.widget_custom_cfg_title);
        int direct = -1;
        try {
            direct = getIntent().getIntExtra(EXTRA_SLOT, -1);
        } catch (Throwable ignored) {
        }
        if (direct >= 0 && direct < CustomWidgetCfg.SLOTS) {
            pick(direct, true);
        } else {
            build();
        }
    }

    /** 四行全列出来（带当前挂的动作名） */
    private void build() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText(R.string.widget_custom_cfg_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setAlpha(0.72f);
        hint.setPadding(0, 0, 0, pad / 2);
        root.addView(hint);

        for (int i = 0; i < CustomWidgetCfg.SLOTS; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(getString(R.string.widget_custom_cfg_row, i + 1, CustomWidgetCfg.label(this, i)));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            row.setPadding(pad / 2, pad, pad / 2, pad);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> pick(idx, false));
            root.addView(row);
        }
        setContentView(root);
    }

    /**
     * 给第 idx 格挑一个动作。
     *
     * @param finishAfter 选完就退出（从组件点空格子进来时）；false = 留在本页接着配下一格
     */
    private void pick(final int idx, final boolean finishAfter) {
        final String[] keys = SlotConfig.choices(this);
        final String[] names = new String[keys.length + 1];
        names[0] = getString(R.string.widget_slot_cfg_none);
        for (int i = 0; i < keys.length; i++) {
            names[i + 1] = QuickActions.label(this, keys[i]);
        }
        // 当前挂的那条要预先选中
        int cur = 0;
        String now = CustomWidgetCfg.slot(this, idx);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(now)) {
                cur = i + 1;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.widget_custom_cfg_pick, idx + 1))
                .setSingleChoiceItems(names, cur, (dlg, which) -> {
                    CustomWidgetCfg.setSlot(this, idx, which == 0 ? "" : keys[which - 1]);
                    dlg.dismiss();
                    CustomCoverWidgetProvider.refreshAll(this);
                    if (finishAfter) {
                        finish();
                    } else {
                        build();
                    }
                })
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> {
                            if (finishAfter) {
                                finish();
                            }
                        })
                .setOnCancelListener(d -> {
                    if (finishAfter) {
                        finish();
                    }
                })
                .show();
    }
}
