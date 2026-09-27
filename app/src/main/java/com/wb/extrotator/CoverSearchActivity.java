package com.wb.extrotator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 「外屏启动器」的搜索页 —— 投到封面屏上用的快速查找。
 *
 * <p>小组件上就那么十几个格子，想开一个没放上去的应用，以前只能翻开手机；这页给出外屏上的
 * 「应用抽屉 + 搜索」，边打字边筛，点一下<b>就在这块屏</b>上启动。
 *
 * <p>跟配置页（{@link CoverLauncherActivity}）的分工：配置页管「放哪些、怎么摆」，这页只管
 * 「找出来、打开」，所以这页没有勾选框，点下去就是启动。
 *
 * <p>它自己不关心跑在哪块屏上：读出自己所在屏，启动时往<b>同一块屏</b>投 —— 在外屏上搜，
 * 就在外屏上开。
 */
public class CoverSearchActivity extends Activity {

    private static final String TAG = "CoverSearchPage";

    private EditText etInput;
    private TextView tvState;
    private GridView gvResults;

    private final List<AppRepo.Item> all = new ArrayList<>();
    private final List<AppRepo.Item> shown = new ArrayList<>();
    private ResultAdapter adapter;

    private int displayId = 0;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cover_search);

        etInput = findViewById(R.id.etSearchInput);
        tvState = findViewById(R.id.tvSearchState);
        gvResults = findViewById(R.id.gvSearch);

        try {
            displayId = getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable ignored) {
        }

        // 结果用单列列表而不是网格 —— 这页会自动弹键盘，外屏只剩一百来 dp 高，
        // 网格"一行四个小格子"在这个高度里连一整行都塞不满。
        // 换成一行一条（item_search_result.xml），键盘上方能看见仨结果。

        adapter = new ResultAdapter();
        gvResults.setAdapter(adapter);
        gvResults.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < shown.size()) {
                launchHere(shown.get(position));
            }
        });

        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        View close = findViewById(R.id.btnSearchClose);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }

        // 刻意**不**自动把键盘叫出来：外屏 339dp 高，而键盘连同上面的候选词条要吃掉 460px 上下，
        // 弹出来之后结果区只剩三十来 dp，连一整行都露不全，等于进来就什么都看不见
        // （实测见 process/coverlaunch/13_search_on_cover.png）。
        // 所以这页进来先当「外屏应用抽屉」使（能看满六行、可滑动），要打字再点搜索框。
        // adjustResize 留着：真打字时列表还会让位给键盘。
        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) {
        }

        load();
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 在当前屏启动。{@link SecondaryLauncher} 那三条路里，shell 是阻塞的，
     * 所以丢后台线程，完事回主线程关掉自己。没 Shizuku 也能成（走三星封面屏通道）。
     */
    private void launchHere(AppRepo.Item it) {
        Toast.makeText(this, "正在第 " + displayId + " 屏打开「" + it.label + "」…",
                Toast.LENGTH_SHORT).show();

        final int display = displayId;
        new Thread(() -> {
            Intent li = null;
            try {
                li = getPackageManager().getLaunchIntentForPackage(it.pkg);
            } catch (Throwable ignored) {
            }
            if (li == null) {
                li = it.toIntent();
            }
            boolean ok = SecondaryLauncher.launch(this, display, li);
            Log.i(TAG, "搜索页启动 " + it.pkg + " => " + ok);
            if (ok) {
                LauncherPrefs.bump(this, it.pkg);
            }
            runOnUiThread(this::finish);
        }, "extrot-search-launch").start();
    }

    // ------------------------------------------------------------------ 应用列表

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        tvState.setText("正在读取应用…");
        new Thread(() -> {
            final List<AppRepo.Item> list = AppRepo.load(this);
            runOnUiThread(() -> {
                loading = false;
                all.clear();
                all.addAll(list);
                applyFilter();
            });
        }, "extrot-search-load").start();
    }

    private void applyFilter() {
        String q = etInput.getText() == null ? "" : etInput.getText().toString();
        shown.clear();
        shown.addAll(AppRepo.filter(all, q));
        adapter.notifyDataSetChanged();

        if (loading) {
            tvState.setText("正在读取应用…");
        } else if (all.isEmpty()) {
            tvState.setText("没读到应用");
        } else if (q.trim().isEmpty()) {
            // 没打字时把全部应用摊出来，相当于外屏上的应用抽屉
            tvState.setText(all.size() + " 个应用 · 打几个字缩小范围");
        } else if (shown.isEmpty()) {
            tvState.setText(getString(R.string.launcher_search_empty));
        } else {
            tvState.setText(shown.size() + " 个结果");
        }
    }

    // ------------------------------------------------------------------ 适配器

    private class ResultAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_search_result, parent, false);
            }
            AppRepo.Item it = shown.get(position);
            ImageView ic = v.findViewById(R.id.srIcon);
            TextView tv = v.findViewById(R.id.srLabel);
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
