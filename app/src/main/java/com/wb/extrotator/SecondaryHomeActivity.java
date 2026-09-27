package com.wb.extrotator;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 副屏桌面 —— 投到封面屏（或便携屏）上的一块可用桌面。
 *
 * <p>为什么需要它：{@code cmd device_state state 4}（CONCURRENT）能把封面屏点亮，但系统只在那块
 * 屏上放一个空跑的 SubHomeActivity，看起来是黑的、谈不上「能操作的第二块屏」；本 Activity 就是
 * 往那块屏上放真正能点的东西。
 *
 * <p>它不关心跑在哪块屏上：用 {@code getDefaultDisplay()} 读出自己所在屏的 id，点图标就用
 * {@link SecondaryLauncher} 往<b>同一块屏</b>投 —— 既能当封面屏桌面，也能当便携屏桌面。
 *
 * <p>图标数多时读图标是 IO，全部在后台线程做完再上屏。
 */
public class SecondaryHomeActivity extends Activity {

    private GridView gvApps;
    private TextView tvClock, tvDate, tvBattery, tvEmpty;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<AppRepo.Item> items = new ArrayList<>();
    private AppAdapter adapter;

    /** 本 Activity 落在哪块屏上 */
    private int displayId = 0;
    private boolean loading = false;
    private int tick = 0;

    private final SimpleDateFormat fmtTime = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat fmtDate = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            // 电池不用每秒读，10 秒一次够了
            if (tick % 10 == 0) {
                updateBattery();
            }
            tick++;
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_secondary_home);

        gvApps = findViewById(R.id.gvApps);
        tvClock = findViewById(R.id.tvClock);
        tvDate = findViewById(R.id.tvDate);
        tvBattery = findViewById(R.id.tvBattery);
        tvEmpty = findViewById(R.id.tvEmpty);

        try {
            displayId = getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable ignored) {
        }

        // 按屏宽决定列数：封面屏 748px/340dpi ≈ 352dp → 4 列；便携屏更宽就多几列
        int wDp = 360;
        try {
            wDp = getResources().getConfiguration().screenWidthDp;
        } catch (Throwable ignored) {
        }
        int cols = Math.max(3, Math.min(6, wDp / 80));
        gvApps.setNumColumns(cols);

        adapter = new AppAdapter();
        gvApps.setAdapter(adapter);
        gvApps.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            launch(items.get(position));
        });

        findViewById(R.id.btnSHomeRefresh).setOnClickListener(v -> load());
        findViewById(R.id.btnSHomeHome).setOnClickListener(v -> {
            if (SecondaryLauncher.goHome(this, displayId)) {
                toast("已让本屏回到系统桌面");
            } else {
                toast("需要 Shizuku 授权才能切回系统桌面");
            }
        });
        findViewById(R.id.btnSHomeExit).setOnClickListener(v -> finish());

        updateClock();
        updateBattery();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    // ------------------------------------------------------------------ 启动应用

    private void launch(AppRepo.Item it) {
        if (it.isPseudo()) {
            // 三个控制页是拿来操作内屏的，用的时候本应用的旋转/分辨率那套一律别动 ——
            // 这里先预置一个定时窗口，因为服务被系统拉回来的时刻可能早于页面 onStart
            // （实测写锁 19:24:29.917、页面挂光标 19:24:29.984，早 70ms）。见 ExtControlMode。
            ExtControlMode.arm(20000L);
        }
        Intent li = null;
        try {
            li = getPackageManager().getLaunchIntentForPackage(it.pkg);
        } catch (Throwable ignored) {
        }
        if (li == null) {
            li = it.toIntent();
        }
        if (SecondaryLauncher.launch(this, displayId, li)) {
            toast("已在第 " + displayId + " 屏打开「" + it.label + "」");
        } else {
            toast("启动失败：可能没有 Shizuku 授权");
        }
    }

    // ------------------------------------------------------------------ 应用列表

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("正在加载应用…");
        new Thread(() -> {
            final List<AppRepo.Item> list = AppRepo.load(this);
            // 副屏桌面自己的顺序：表里的排前面，其余按名称跟在后面。
            // 没设过顺序时 HomePrefs.sort 直接返回，行为跟以前一样是纯名称序。
            HomePrefs.sort(this, list);
            // 伪应用恒置顶：它们不是真应用，AppRepo.load 里没有这几个，
            // 在这里插到最前面 —— 排序对它们没有意义，所以"排完再插"，
            // 顺序就永远由这一段说了算，不会被手动顺序或副屏桌面顺序搅乱。
            list.addAll(0, AppRepo.pseudoItems(this));
            runOnUiThread(() -> {
                loading = false;
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                if (items.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("没有读到应用。\n如果装的是 release 版，请确认清单里声明了 <queries> 的 MAIN/LAUNCHER。");
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
            });
        }, "extrot-cover-apps").start();
    }

    // ------------------------------------------------------------------ 时钟 / 电池

    private void updateClock() {
        Date now = new Date();
        tvClock.setText(fmtTime.format(now));
        tvDate.setText(fmtDate.format(now));
    }

    private void updateBattery() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) {
                return;
            }
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                tvBattery.setText("—");
                return;
            }
            int pct = (int) (level * 100f / scale);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            tvBattery.setText(pct + "%" + (charging ? " ⚡" : ""));
        } catch (Throwable t) {
            tvBattery.setText("—");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 适配器

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_app_grid, parent, false);
            }
            AppRepo.Item it = items.get(position);
            ImageView ic = v.findViewById(R.id.ivGridIcon);
            TextView tv = v.findViewById(R.id.tvGridName);
            try {
                ic.setImageDrawable(it.icon);
            } catch (Throwable ignored) {
                ic.setImageDrawable(null);
            }
            tv.setText(it.label);
            return v;
        }
    }
}
