package com.wb.extrotator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 副屏键盘 —— 在封面屏上打字，字推进外接屏那个应用的光标处。
 *
 * <p>做成「输入框 + 发送」而不是自制键盘，是因为中文送不进去：{@code input text} 只敲得出
 * 虚拟键盘上的 ASCII 字符，应用侧无法把中文直接注入别的应用的输入框。两条路都做了：
 * <ul>
 *   <li>直接键入（{@code input text}）—— 纯英文 / 数字最快；</li>
 *   <li>剪贴板 + 粘贴键（发一次 {@code KEYCODE_PASTE}）—— 中文、emoji、长文本走这条。</li>
 * </ul>
 *
 * <p>⚠ 前提：外接屏那边光标已经落在输入框里。注入的按键只走「当前有焦点的窗口」，
 * 没焦点的输入框收不到 —— 先在那边点一下输入框。
 */
public class KeyActivity extends Activity {

    private TextView tvTitle;
    private TextView tvState;
    private EditText etText;
    private Button btnMode;

    private ExtScreen.Dev dev;
    private String lastAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ext_key);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvTitle = findViewById(R.id.tvKeyTitle);
        tvState = findViewById(R.id.tvKeyState);
        etText = findViewById(R.id.etKeyText);
        btnMode = findViewById(R.id.btnKeyMode);

        ExtUi.info(this, R.id.iKeyTop, R.string.ext_key_title, R.string.info_ext_key);

        findViewById(R.id.btnKeyClose).setOnClickListener(v -> finish());

        findViewById(R.id.btnKeySend).setOnClickListener(v -> doSend());
        findViewById(R.id.btnKeyEnter).setOnClickListener(v -> sendKey("KEYCODE_ENTER", "回车"));
        findViewById(R.id.btnKeyDel).setOnClickListener(v -> sendKey("KEYCODE_DEL", "退格"));
        findViewById(R.id.btnKeySpace).setOnClickListener(v -> sendKey("KEYCODE_SPACE", "空格"));
        findViewById(R.id.btnKeyTab).setOnClickListener(v -> sendKey("KEYCODE_TAB", "Tab"));
        findViewById(R.id.btnKeyEsc).setOnClickListener(v -> sendKey("KEYCODE_ESCAPE", "Esc"));
        findViewById(R.id.btnKeyClear).setOnClickListener(v -> {
            etText.setText("");
            lastAction = "已清空输入框";
            refreshState();
        });
        btnMode.setOnClickListener(v -> {
            int now = ExtPrefs.kbMode(this);
            ExtPrefs.setKbMode(this, (now + 1) % 3);
            syncModeButton();
            refreshState();
        });

        syncModeButton();
        resolve();
    }

    private void resolve() {
        tvState.setText(R.string.ext_finding);
        ExtUi.resolve(this, d -> {
            dev = d;
            ExtUi.title(this, tvTitle, R.string.ext_key_title, dev);
            refreshState();
        });
    }

    // ------------------------------------------------------------------ 发送

    /**
     * 发送输入框里的内容。
     *
     * <p>方式三档（按钮上能看见当前是哪档）：自动 / 直接键入 / 剪贴板。
     * 自动的判据就是"有没有非 ASCII 字符" —— 有中文就走剪贴板，
     * 纯英文数字就直接敲（少动一次剪贴板，也少一次系统"已复制"的提示）。
     */
    private void doSend() {
        String text = etText.getText() == null ? "" : etText.getText().toString();
        if (text.isEmpty()) {
            ExtUi.toast(this, "先打点字");
            return;
        }
        if (dev == null) {
            ExtUi.toast(this, getString(R.string.ext_no_screen));
            return;
        }
        int mode = ExtPrefs.kbMode(this);
        if (mode == ExtPrefs.KB_AUTO) {
            mode = isAscii(text) ? ExtPrefs.KB_TEXT : ExtPrefs.KB_CLIP;
        }
        if (mode == ExtPrefs.KB_CLIP) {
            putClipboard(text);
            ExtScreen.paste(dev.id, text);
            lastAction = "已用剪贴板粘贴 " + text.length() + " 字";
        } else {
            ExtScreen.typeText(dev.id, text);
            lastAction = "已键入 " + text.length() + " 字";
        }
        etText.setText("");
        refreshState();
    }

    private void putClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Z flip便携屏扩展", text));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 全是 ASCII 才走"直接键入"；有一个中文就走剪贴板 */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private void sendKey(String keyCode, String label) {
        if (dev == null) {
            ExtUi.toast(this, getString(R.string.ext_no_screen));
            return;
        }
        ExtScreen.key(dev.id, keyCode);
        lastAction = "已发 " + label;
        refreshState();
    }

    private void syncModeButton() {
        switch (ExtPrefs.kbMode(this)) {
            case ExtPrefs.KB_TEXT:
                btnMode.setText(R.string.ext_key_mode_text);
                break;
            case ExtPrefs.KB_CLIP:
                btnMode.setText(R.string.ext_key_mode_clip);
                break;
            default:
                btnMode.setText(R.string.ext_key_mode_auto);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 这一页在用的时候，别让本应用的旋转 / 分辨率那套来动显示设置（见 ExtControlMode）
        ExtControlMode.on();
    }

    @Override
    protected void onStop() {
        ExtControlMode.off();
        super.onStop();
    }

    private void refreshState() {
        if (dev == null) {
            tvState.setText(R.string.ext_no_screen);
            return;
        }
        String base = getString(R.string.ext_key_state_fmt, dev.sizeText());
        tvState.setText(lastAction == null ? base : lastAction + "　·　" + base);
    }
}
