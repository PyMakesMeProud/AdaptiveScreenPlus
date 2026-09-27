package com.wb.extrotator;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * 状态展示柜（伪应用）：副屏上的实时监控面板，合盖立着时当常亮仪表盘用。
 * 不改显示设置，所以不占 {@link ExtControlMode} 的窗口。
 */
public class StatusActivity extends Activity {

    private StatusBoardView board;
    private StatusMonitor monitor;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ext_status);
        // 常驻监控页，熄屏就没意义了
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        board = findViewById(R.id.statusBoard);
        board.setTap(new StatusBoardView.Tap() {
            @Override
            public void onInfo() {
                showInfo();
            }

            @Override
            public void onClose() {
                finish();
            }
        });

        monitor = new StatusMonitor(this, snap -> board.setSnap(snap));
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitor.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开前台就停采样。这一页是常驻的，不主动停就是一直在读文件
        monitor.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        monitor.release();
    }

    private void showInfo() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ext_status_title)
                .setMessage(R.string.info_ext_status)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
