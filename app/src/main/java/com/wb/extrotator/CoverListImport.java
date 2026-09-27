package com.wb.extrotator;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把「系统里已有的那份外屏组件清单」搬进本应用 —— 用户可能已经在里面挑过几十个应用，
 * 不该逼他重挑一遍。
 *
 * <p>清单来自三星 MultiStar 的「外屏启动器组件」，存在安全设置里：
 * <pre>
 *   settings get secure multistar_cover_widget_backup_list
 *   → com.qq,0;com.douyin,0;...
 * </pre>
 * 格式是 {@code 包名,userId} 用分号连接。
 *
 * <p>⚠ 读它属于「以 shell 身份读系统设置」（安全设置对第三方不可见），必须走 {@link ShellRunner}
 * （Shizuku）。本类<b>只读不写</b>，用户原来的配置一个字都不会被碰掉。
 */
public final class CoverListImport {

    private static final String TAG = "CoverListImport";

    /** 那份清单所在的命名空间与键名 */
    private static final String NS = "secure";
    private static final String KEY = "multistar_cover_widget_backup_list";

    private CoverListImport() {
    }

    /**
     * 读出清单里的包名（保持原顺序、去重）。
     *
     * <p>返回空列表表示：没读过 / 没有这份清单 / 格式不对。
     * 读失败和"清单是空的"在这里不做区分 —— 对调用方来说处理方式一样（提示没有可导入的）。
     */
    public static List<String> readRaw() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            String raw = ShellRunner.run("settings get " + NS + " " + KEY);
            if (raw == null) {
                return new ArrayList<>();
            }
            String v = raw.trim();
            if (TextUtils.isEmpty(v) || "null".equals(v)) {
                return new ArrayList<>();
            }
            for (String piece : v.split(";")) {
                String p = piece == null ? "" : piece.trim();
                if (p.isEmpty()) {
                    continue;
                }
                // 每条形如 "包名,userId"，包名里不会出现逗号
                int comma = p.indexOf(',');
                String pkg = (comma > 0 ? p.substring(0, comma) : p).trim();
                if (!pkg.isEmpty() && pkg.indexOf('/') < 0 && pkg.indexOf(' ') < 0) {
                    out.add(pkg);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取清单失败", t);
        }
        return new ArrayList<>(out);
    }

    /**
     * 清单 ∩ 本机当前装着、且有启动图标的应用。
     *
     * <p>必须过这一道：那份清单是历史记录，里面的应用可能早就卸载了。
     * 直接原样搬进来会在外屏上留下点不动的空格子。
     *
     * <p>顺序以清单为准（那是用户当初的排列意图），不是按应用名排。
     */
    public static List<String> readInstalled(Context ctx) {
        List<String> raw = readRaw();
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<AppRepo.Item> installed = AppRepo.load(ctx);
        if (installed == null || installed.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> have = new LinkedHashSet<>();
        for (AppRepo.Item it : installed) {
            if (it != null && it.pkg != null) {
                have.add(it.pkg);
            }
        }
        List<String> out = new ArrayList<>();
        for (String p : raw) {
            if (have.contains(p) && !out.contains(p)) {
                out.add(p);
            }
        }
        Log.i(TAG, "清单 " + raw.size() + " 个 -> 本机可用的 " + out.size() + " 个");
        return out;
    }
}
