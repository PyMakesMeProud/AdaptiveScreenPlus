package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「老虎机」那三个快捷位的配置页 —— 在组件上切到自定义模式后点摇杆进来的。
 *
 * <p>为什么做成一个正经 Activity 而不是组件里再点几层：RemoteViews 的交互只有"点一下、
 * 发一个 PendingIntent"，没法在组件上摆下拉选单。而这一步又是**必须有的**（三个位挂什么
 * 得用户自己定），所以干脆跳到应用这边用系统对话框选完再回来 —— 顺带选中项、当前值、
 * 取消这些都不用自己造。
 *
 * <p>选完立刻 {@link SlotWidgetProvider#refreshAll} 刷一遍内外屏两份组件，
 * 用户回到桌面就看见结果了，不用等下一次系统刷新。
 *
 * <p>⚠ 这里是**唯一**写 {@link SlotConfig} 的地方；组件那边只读不写（写会跟它的
 * "画崩就整块报废"这条线混在一起）。
 */
public class SlotConfigActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.widget_slot_cfg_title);
        build();
    }

    private void build() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText(R.string.widget_slot_cfg_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setAlpha(0.72f);
        hint.setPadding(0, 0, 0, pad / 2);
        root.addView(hint);

        for (int i = 0; i < SlotConfig.SLOTS; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(getString(R.string.widget_slot_cfg_row, i + 1, SlotConfig.label(this, i)));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            row.setPadding(pad / 2, pad, pad / 2, pad);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> pick(idx));
            root.addView(row);
        }
        setContentView(root);
    }

    private void pick(final int idx) {
        final String[] keys = SlotConfig.choices(this);
        final String[] names = new String[keys.length + 1];
        names[0] = getString(R.string.widget_slot_cfg_none);
        for (int i = 0; i < keys.length; i++) {
            names[i + 1] = QuickActions.label(this, keys[i]);
        }
        // 当前挂的那条要预先选中
        int cur = 0;
        String now = SlotConfig.slot(this, idx);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(now)) {
                cur = i + 1;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.widget_slot_cfg_pick, idx + 1))
                .setSingleChoiceItems(names, cur, (dlg, which) -> {
                    SlotConfig.setSlot(this, idx, which == 0 ? "" : keys[which - 1]);
                    dlg.dismiss();
                    build();
                    SlotWidgetProvider.refreshAll(this,
                            SlotWidgetProvider.class, SlotCoverWidgetProvider.class);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
