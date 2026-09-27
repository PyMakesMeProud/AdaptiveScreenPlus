package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「修复界面」的名单页 —— 从 Beta 的「应用管理」进来。
 *
 * <p>逐行列出本机所有能启动的应用，每行一个开关：开 = 该应用进了三星外屏那份「正常显示」名单
 * （外屏上以原生尺寸渲染，字体正常、弹窗落在屏内点得到），关 = 外屏上缩放显示。
 * 名单本身与原理见 {@link CoverRepair}。
 *
 * <p>单开一页是为了用 ListView 正经回收：几百行的列表塞进 Beta 的 ScrollView 里既不能自己滚、
 * 又要一次量完所有行，滑起来卡。
 *
 * <p>⚠ 写盘必须串行：每次开关都是「读整份名单 → 改一个 → 整份写回」，
 * 两条叠在一起会互相覆盖，所以统一丢进 {@link #pool}，并用 {@link #busy} 挡连点。
 */
public class RepairActivity extends Activity {

    private static final String TAG = "RepairPage";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private ListView list;
    private TextView tvState;
    private TextView btnAll;
    private TextView btnReset;

    private final List<AppRepo.Item> apps = new ArrayList<>();
    /** 当前在名单里的包名 */
    private final Set<String> on = new HashSet<>();

    private RowAdapter adapter;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repair);

        View back = findViewById(R.id.btnRepairBack);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        list = findViewById(R.id.lvRepair);
        tvState = findViewById(R.id.tvRepairState);
        btnAll = findViewById(R.id.btnRepairAll);
        btnReset = findViewById(R.id.btnRepairReset);

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        bindInfo(R.id.iRepairList, R.string.info_repair_list);

        if (btnAll != null) {
            btnAll.setOnClickListener(v -> oneKey(true));
        }
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> confirmReset());
        }

        if (!ShellRunner.isReady()) {
            toast("Shizuku 未就绪，先回主界面授权");
        }
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从别处（MultiStar / 阿田自用）改过名单再回来，看到的必须是刚读的那一份
        reload();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ==================================================================== 读

    private void reload() {
        setBusy(true);
        tvState.setText("读取中…");
        pool.execute(() -> {
            final List<AppRepo.Item> loaded = new ArrayList<>();
            for (AppRepo.Item it : AppRepo.load(getApplicationContext())) {
                if (it != null && !it.isPseudo() && !TextUtils.isEmpty(it.pkg)) {
                    loaded.add(it);
                }
            }
            CoverRepair.Snapshot snap = CoverRepair.read();
            final Set<String> now = new HashSet<>(CoverRepair.packages(snap));
            final boolean readable = snap != null && snap.valid();
            ui.post(() -> {
                apps.clear();
                apps.addAll(loaded);
                on.clear();
                on.addAll(now);
                adapter.notifyDataSetChanged();
                setBusy(false);
                if (!readable) {
                    tvState.setText("读不回名单（Shizuku 掉线了？）—— 这次只能看，别改");
                } else {
                    updateState();
                }
            });
        });
    }

    private void updateState() {
        int hit = 0;
        for (AppRepo.Item it : apps) {
            if (on.contains(it.pkg)) {
                hit++;
            }
        }
        String snap = CoverRepair.hasSnapshot(this)
                ? "已存改动前的快照，可以一键重置" : "还没改动过（首次改动会自动存快照）";
        tvState.setText("名单 " + on.size() + " 个 · 本机可启动应用 " + apps.size()
                + " 个（其中 " + hit + " 个已开启）\n" + snap);
    }

    // ==================================================================== 写

    private void toggle(AppRepo.Item it, boolean want) {
        if (busy) {
            return;
        }
        setBusy(true);
        final String pkg = it.pkg;
        pool.execute(() -> {
            String msg = CoverRepair.apply(getApplicationContext(),
                    want ? Collections.singletonList(pkg) : Collections.<String>emptyList(),
                    want ? Collections.<String>emptyList() : Collections.singletonList(pkg));
            Log.i(TAG, (want ? "开启 " : "关闭 ") + pkg + " → " + msg);
            ui.post(() -> {
                setBusy(false);
                if (msg.startsWith("ERR")) {
                    toast(msg);
                    reload();          // 写失败就以盘上的为准，别让界面留着假状态
                } else {
                    if (want) {
                        on.add(pkg);
                    } else {
                        on.remove(pkg);
                    }
                    updateState();
                }
            });
        });
    }

    /** 一键修复 / 一键重置 */
    private void oneKey(final boolean fix) {
        if (busy) {
            return;
        }
        if (!fix && !CoverRepair.hasSnapshot(this)) {
            toast("还没改动过，没有可还原的快照");
            return;
        }
        setBusy(true);
        tvState.setText(fix ? "正在把已装应用全部打开…" : "正在还原…");
        pool.execute(() -> {
            String msg = fix ? CoverRepair.applyAll(getApplicationContext())
                    : CoverRepair.restore(getApplicationContext());
            Log.i(TAG, (fix ? "一键修复 → " : "一键重置 → ") + msg);
            ui.post(() -> {
                setBusy(false);
                toast(msg);
                reload();
            });
        });
    }

    private void confirmReset() {
        if (busy) {
            return;
        }
        if (!CoverRepair.hasSnapshot(this)) {
            toast("还没改动过，没有可还原的快照");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("一键重置")
                .setMessage("把外屏名单还原到本应用第一次改动之前的那一份（改动前存下的快照）。\n\n"
                        + "⚠ 这会连同你在 MultiStar 里后来做的改动一起覆盖掉。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("还原", (d, w) -> oneKey(false))
                .show();
    }

    // ==================================================================== 界面

    private void setBusy(boolean b) {
        busy = b;
        if (btnAll != null) {
            btnAll.setEnabled(!b);
            btnAll.setAlpha(b ? 0.5f : 1f);
        }
        if (btnReset != null) {
            btnReset.setEnabled(!b);
            btnReset.setAlpha(b ? 0.5f : 1f);
        }
    }

    /** 一行 = 应用名 + 开关。自己拼，不占一个布局文件（跟启动器那套长按菜单同一个路子） */
    private final class RowAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reuse, ViewGroup parent) {
            LinearLayout row;
            TextView tv;
            Switch sw;
            if (reuse instanceof LinearLayout && reuse.getTag() != null) {
                row = (LinearLayout) reuse;
                tv = (TextView) row.getChildAt(0);
                sw = (Switch) row.getChildAt(1);
            } else {
                row = new LinearLayout(RepairActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = dp(9);
                row.setPadding(0, pad, 0, pad);
                row.setTag(Boolean.TRUE);

                tv = new TextView(RepairActivity.this);
                tv.setTextColor(0xFFF0F0F0);
                tv.setTextSize(14f);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                sw = new Switch(RepairActivity.this);
                tint(sw);

                row.addView(tv);
                row.addView(sw);
            }
            final AppRepo.Item it = apps.get(position);
            tv.setText(it.label);
            // 先摘监听再设状态 —— 不然回收时会当成"用户拨了一下"，往盘上乱写
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(on.contains(it.pkg));
            sw.setOnCheckedChangeListener((b, checked) -> toggle(it, checked));
            return row;
        }
    }

    /** 开关配色跟 Beta 页一致（系统默认那个开/关两态在白底上分不清） */
    private void tint(Switch sw) {
        try {
            ColorStateList thumb = getResources().getColorStateList(R.color.beta_switch_thumb);
            ColorStateList track = getResources().getColorStateList(R.color.beta_switch_track);
            sw.setThumbTintList(thumb);
            sw.setTrackTintList(track);
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void bindInfo(int iconId, int detailRes) {
        View icon = findViewById(iconId);
        if (icon == null) {
            return;
        }
        icon.setOnClickListener(v -> {
            if (isFinishing()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("修复界面")
                    .setMessage(detailRes)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
