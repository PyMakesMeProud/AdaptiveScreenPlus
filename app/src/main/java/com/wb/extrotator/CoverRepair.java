package com.wb.extrotator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 「修复界面」—— 把某个应用加进 / 移出三星外屏那份「能正常显示」的名单。
 *
 * <p><b>它到底是干什么的</b>：三星外屏不是「能不能打开」的问题 —— 不在名单里的应用照样能在外屏
 * 拉起来，但它会以<b>兼容 / 缩放</b>的方式显示：字体大小不对、弹窗画在屏幕外面点不到。进了名单，
 * 应用就以外屏自己的尺寸原生渲染：字体正常、弹窗落在屏内、点得到。
 *
 * <p><b>名单存在哪儿</b>：存在 {@code Settings.Secure} 里，由 Good Lock 的 MultiStar 管
 * （就是设置里「在外屏运行的应用」那页）。要动它得同时改好几个 key，少写一个 SystemUI 就不认 ——
 * 这是照抄「阿田自用」的 {@code CoverPolicyInjector} 做出来的，格式一个字没改：
 *
 * <table>
 *   <tr><td>{@value #K_MASTER}</td><td>{@code 1}</td></tr>
 *   <tr><td>{@value #K_REPO}</td><td>29 段用 {@code /} 隔开的旧格式，第 <b>25</b> 段放名单</td></tr>
 *   <tr><td>{@value #K_BACKUP}</td><td>{@code pkg,userId;pkg,userId;…}</td></tr>
 *   <tr><td>{@value #K_JSON}</td><td>JSON 串，名单在 {@code coverWidgetList}</td></tr>
 *   <tr><td>{@value #K_POLICY}</td><td><b>加密</b>：{@code Base64(AES-CBC(pkg))},480,2.0;…</td></tr>
 *   <tr><td>{@value #K_ENABLED}</td><td>{@code 1}</td></tr>
 *   <tr><td>{@value #K_LARGE}（System）</td><td>1</td></tr>
 * </table>
 *
 * <p>加密细节（照抄，别自己发挥）：{@code AES/CBC/PKCS5Padding}，key 取
 * {@code goodlock_multistar} 的 UTF-8 前 16 字节（正好是 {@code goodlock_multist}），
 * IV = {@link #POLICY_IV}，Base64 用 {@code NO_WRAP}。写入之后还要给 {@code com.android.systemui}
 * 发一条 {@code com.sec.android.app.sublauncher_COMPONENT_ENABLED_CHANGED}，它才会重读名单。
 *
 * <p>⚠ <b>这是「整份重写」，所以先拍快照</b>：这几个 key 是 MultiStar 自己的地盘，我们读回来、
 * 改一个包、再整份写回去（其他字段原样保留），但万一手一抖写坏一次，用户的外屏名单就毁了。
 * 所以<b>第一次真写之前</b>把六个 key 的原值整份存进 {@link #SNAP}，「一键重置」就是把它原样
 * 写回去（{@link #restore}）。
 *
 * <p>全部走 {@link ShellRunner}（Shizuku → shell 身份）：shell 自带 WRITE_SECURE_SETTINGS，
 * {@code settings put} 直接就能写。
 */
public final class CoverRepair {

    private static final String TAG = "CoverRepair";

    /**
     * 从 shell 回读时用的行首标记：{@code @@key@@}。
     *
     * <p>⚠⚠ 别顺手写成 {@code <<key>>}：{@code <<} 在 sh 里是 here-doc 重定向符，
     * {@code echo <<foo>>; settings get …} 会被解析成「读一段到这里为止的标准输入」，整条命令当场
     * {@code syntax error: unexpected ';'} —— 后面的命令一条都不执行，六个 key 全读成空
     * （症状：日志永远「读到 0 个 key」）。{@code @@} 在 sh 里没有任何特殊含义。
     */
    private static final String MARK = "@@";

    /** 名字跟三星 MultiStar 里那套一模一样，别改 */
    static final String K_MASTER = "multistar_master_setting";
    static final String K_REPO = "multistar_setting_repository";
    static final String K_BACKUP = "multistar_cover_widget_backup_list";
    static final String K_JSON = "multistar_setting_json_repository";
    static final String K_POLICY = "multistar_cover_widget_policy_list";
    static final String K_ENABLED = "cover_screen_apps_enabled";
    /** 唯一一个 {@code Settings.System} 的（其余都在 Secure） */
    static final String K_LARGE = "large_cover_screen_apps";

    /** 名单在旧格式里的段号 */
    private static final int REPO_SLOT = 25;
    /** 旧格式固定 29 段 */
    private static final int REPO_LEN = 29;

    /** 加密用的固定 IV（阿田自用里就是这串字面量） */
    private static final byte[] POLICY_IV = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 17, 18, 19, 20, 21, 22};

    /** 快照存放处 */
    private static final String SNAP = "extrot_repair";
    private static final String SNAP_TIME = "snap_time";

    private static final String[] SECURE_KEYS = {
            K_MASTER, K_REPO, K_BACKUP, K_JSON, K_POLICY, K_ENABLED};

    private CoverRepair() {
    }

    /**
     * 当前用户 id。
     *
     * <p>⚠ 不能写 {@code UserHandle.getUserId(uid)} —— 那个是 {@code @SystemApi}，
     * 普通应用编不过。这里按 uid 的编码自己算：{@code uid = userId * 100000 + appId}
     * （主用户 0 的 uid 都在 10xxxx 一段，工作资料是 10xxxxx）。
     */
    public static int userId() {
        try {
            return Process.myUid() / 100000;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================================================================== 读

    /** 六个 key 的原值 */
    public static final class Snapshot {
        public final Map<String, String> raw = new LinkedHashMap<>();

        public String get(String key) {
            return raw.get(key);
        }

        /** 六个 key 都读到了才算有效（读空说明 shell 没通，这时候千万别写） */
        public boolean valid() {
            return raw.size() >= SECURE_KEYS.length;
        }
    }

    /** 一次 shell 把六个 key 全读回来（每个前面打一个 {@code <<key>>} 标记） */
    public static Snapshot read() {
        if (!ShellRunner.isReady()) {
            Log.w(TAG, "Shizuku 未就绪，读不了");
            return null;
        }
        StringBuilder cmd = new StringBuilder();
        for (String k : SECURE_KEYS) {
            cmd.append("echo ").append(mark(k)).append("; settings get secure ").append(k).append("; ");
        }
        cmd.append("echo ").append(mark(K_LARGE)).append("; settings get system ").append(K_LARGE);
        String out = ShellRunner.run(cmd.toString(), 30);
        Snapshot s = new Snapshot();
        String cur = null;
        if (out != null) {
            for (String line : out.split("\n")) {
                String t = line.replace("\r", "");
                if (t.startsWith(MARK) && t.endsWith(MARK)) {
                    cur = t.substring(MARK.length(), t.length() - MARK.length());
                    continue;
                }
                if (cur != null && !s.raw.containsKey(cur)) {
                    // 一个 key 就一行（值里不会换行）；unset 的会回 "null"，照收不误
                    s.raw.put(cur, t);
                    cur = null;
                }
            }
        }
        // 读空 = shell 那条命令根本没跑起来（最可能就是标记写错），这时候千万别往下写
        Log.i(TAG, "读到 " + s.raw.size() + "/" + (SECURE_KEYS.length + 1) + " 个 key");
        for (Map.Entry<String, String> en : s.raw.entrySet()) {
            String v = en.getValue();
            Log.d(TAG, "  " + en.getKey() + " = "
                    + (v == null ? "(null)" : v.length() + " 字符"));
        }
        return s;
    }

    /** 从六个 key 里合出当前名单（三个来源取并集，去重保序） */
    public static List<String> packages(Snapshot s) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (s == null) {
            return new ArrayList<>(set);
        }
        int uid = userId();
        addWithUser(set, s.get(K_BACKUP), uid);

        String repo = s.get(K_REPO);
        if (!TextUtils.isEmpty(repo) && !"null".equals(repo)) {
            String[] f = trimSlash(repo).split("/", -1);
            if (f.length > REPO_SLOT) {
                addWithUser(set, f[REPO_SLOT], uid);
            }
        }

        String js = s.get(K_JSON);
        if (!TextUtils.isEmpty(js) && !"null".equals(js)) {
            try {
                JSONObject st = new JSONObject(js).optJSONObject("settings");
                if (st != null) {
                    addWithUser(set, st.optString("coverWidgetListIncludedUserId", ""), uid);
                    addPlain(set, st.optString("coverWidgetList", ""));
                }
            } catch (Throwable t) {
                Log.d(TAG, "json 名单解析失败：" + t);
            }
        }
        return new ArrayList<>(set);
    }

    public static boolean isOn(Context c, Snapshot s, String pkg) {
        return packages(s).contains(pkg);
    }

    // ==================================================================== 写

    /**
     * 加一批 / 减一批，然后整份写回。
     *
     * @return 一行给人看的结果（成功是 {@code 名单 N 个（回读 N 个）}，失败以 {@code ERR:} 开头）
     */
    public static String apply(Context c, Collection<String> on, Collection<String> off) {
        if (!ShellRunner.isReady()) {
            return "ERR: Shizuku 未就绪";
        }
        Snapshot s = read();
        if (s == null || !s.valid()) {
            return "ERR: 读不回现有名单（shell 没通？），这次什么都没改";
        }
        List<String> list = packages(s);
        if (!off.isEmpty()) {
            list.removeAll(off);
        }
        for (String p : on) {
            if (!list.contains(p)) {
                list.add(p);
            }
        }
        Collections.sort(list);
        return write(c, s, list);
    }

    /** 一键修复：把所有已装应用都加进去（阿田自用里叫 ensureAllLauncherApps） */
    public static String applyAll(Context c) {
        List<String> pkgs = launcherPackages(c);
        if (pkgs.isEmpty()) {
            return "ERR: 没取到已装应用";
        }
        return apply(c, pkgs, Collections.<String>emptyList()) + "（共 " + pkgs.size() + " 个应用）";
    }

    /** 一键重置：把第一次改动之前的快照原样写回去 */
    public static String restore(Context c) {
        if (!hasSnapshot(c)) {
            return "ERR: 还没动过，没有可还原的快照";
        }
        if (!ShellRunner.isReady()) {
            return "ERR: Shizuku 未就绪";
        }
        SharedPreferences sp = snap(c);
        StringBuilder cmd = new StringBuilder();
        for (String k : SECURE_KEYS) {
            cmd.append(put(k, sp.getString(k, "null"), false));
        }
        cmd.append(put(K_LARGE, sp.getString(K_LARGE, "null"), true));
        String out = ShellRunner.run(cmd.toString(), 30);
        notifySystemUi(c);
        Snapshot back = read();
        int n = back == null ? -1 : packages(back).size();
        Log.i(TAG, "reset → 回读名单 " + n + " 个；shell: " + oneLine(out));
        return n < 0 ? "ERR: 写回去了但回读失败" : "已还原到改动前（名单 " + n + " 个）";
    }

    public static boolean hasSnapshot(Context c) {
        return snap(c).contains(SNAP_TIME);
    }

    /** 真正落盘那一步 */
    private static String write(Context c, Snapshot s, List<String> list) {
        if (!hasSnapshot(c)) {
            saveSnapshot(c, s);
        }
        int uid = userId();
        String withUser = joinWithUser(list, uid);
        String plain = join(list, ";");
        StringBuilder cmd = new StringBuilder();
        cmd.append(put(K_MASTER, "1", false));
        cmd.append(put(K_REPO, legacyRepo(s.get(K_REPO), withUser), false));
        cmd.append(put(K_BACKUP, withUser, false));
        cmd.append(put(K_JSON, jsonRepo(s.get(K_JSON), withUser, plain), false));
        cmd.append(put(K_POLICY, policyList(list), false));
        cmd.append(put(K_ENABLED, "1", false));
        cmd.append(put(K_LARGE, "1", true));

        String out = ShellRunner.run(cmd.toString(), 60);
        notifySystemUi(c);

        // 写完回读一次 —— 不靠"应该是成功的"，靠读回来的数字
        Snapshot back = read();
        int n = back == null ? -1 : packages(back).size();
        Log.i(TAG, "写入 " + list.size() + " 个 → 回读 " + n + " 个；shell: " + oneLine(out));
        if (n < 0) {
            return "ERR: 写了但回读失败（Shizuku 掉线？）";
        }
        if (n != list.size()) {
            return "名单 " + list.size() + " 个，回读 " + n + " 个（对不上，可能被 MultiStar 改回）";
        }
        return "名单 " + n + " 个";
    }

    private static void saveSnapshot(Context c, Snapshot s) {
        SharedPreferences.Editor e = snap(c).edit();
        for (Map.Entry<String, String> en : s.raw.entrySet()) {
            e.putString(en.getKey(), en.getValue());
        }
        e.putString(SNAP_TIME, String.valueOf(System.currentTimeMillis()));
        e.apply();
        Log.i(TAG, "已把改动前的六个 key 存成快照");
    }

    private static SharedPreferences snap(Context c) {
        return c.getApplicationContext().getSharedPreferences(SNAP, Context.MODE_PRIVATE);
    }

    /**
     * 一条命令：{@code settings put ...}；值一律单引号包住
     * （名单里只有包名 / 数字 / JSON，不会带单引号）。
     *
     * <p>值为 {@code null}（原本就没有这个 key）时改成 {@code settings delete} ——
     * {@code settings get} 把「不存在」回显成字符串 {@code null}，原样写回去会<b>真的建出一个值为
     * null 的 key</b>，MultiStar 可能当真解析。
     */
    private static String put(String key, String value, boolean system) {
        String ns = system ? "system " : "secure ";
        if (value == null || "null".equals(value)) {
            return "settings delete " + ns + key + "; ";
        }
        return "settings put " + ns + key + " '" + value.replace("'", "'\\''") + "'; ";
    }

    /** 回读用的行首标记，见 {@link #MARK} */
    private static String mark(String key) {
        return MARK + key + MARK;
    }

    /** 通知 SystemUI 重读名单 —— 不发这条，改完要等重启才生效 */
    private static void notifySystemUi(Context c) {
        for (String p : new String[]{"com.samsung.android.multistar", c.getPackageName()}) {
            try {
                c.sendBroadcast(new Intent("com.sec.android.app.sublauncher_COMPONENT_ENABLED_CHANGED")
                        .setPackage("com.android.systemui")
                        .putExtra("pkgName", p));
            } catch (Throwable t) {
                Log.d(TAG, "通知 SystemUI 失败：" + t);
            }
        }
    }

    public static List<String> launcherPackages(Context c) {
        List<String> out = new ArrayList<>();
        for (AppRepo.Item it : AppRepo.load(c)) {
            if (it != null && !it.isPseudo() && !TextUtils.isEmpty(it.pkg)) {
                out.add(it.pkg);
            }
        }
        return out;
    }

    // ==================================================================== 拼装

    private static String legacyRepo(String raw, String withUser) {
        String t = trimSlash(raw == null ? "" : raw);
        String[] f = (TextUtils.isEmpty(t) || "null".equals(t)) ? new String[0] : t.split("/", -1);
        String[] out = f;
        if (out.length < REPO_LEN) {
            out = new String[REPO_LEN];
            System.arraycopy(f, 0, out, 0, f.length);
        }
        for (int i = 0; i < out.length; i++) {
            if (TextUtils.isEmpty(out[i])) {
                out[i] = "0";
            }
        }
        out[REPO_SLOT] = withUser;
        StringBuilder b = new StringBuilder();
        for (String p : out) {
            b.append(p).append('/');
        }
        return b.toString();
    }

    private static String jsonRepo(String raw, String withUser, String plain) {
        try {
            JSONObject root = (TextUtils.isEmpty(raw) || "null".equals(raw))
                    ? new JSONObject() : new JSONObject(raw);
            JSONObject st = root.optJSONObject("settings");
            if (st == null) {
                st = new JSONObject();
            }
            st.put("coverWidgetListIncludedUserId", withUser);
            st.put("coverWidgetList", plain);
            st.put("coverWidgetFwBugFix", true);
            if (!root.has("version")) {
                root.put("version", 1);
            }
            root.put("settings", st);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "旧 json 读不动，按全新的一份写", t);
            return "{\"version\":1,\"settings\":{\"coverWidgetListIncludedUserId\":\""
                    + withUser.replace("\"", "\\\"") + "\",\"coverWidgetList\":\""
                    + plain.replace("\"", "\\\"") + "\",\"coverWidgetFwBugFix\":true}}";
        }
    }

    /** 加密名单。包名一个都加不进去时返回空串，调用方自己看着办 */
    private static String policyList(List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String p : list) {
            String e = encrypt(p);
            if (TextUtils.isEmpty(e)) {
                continue;
            }
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(e).append(",480,2.0");
        }
        return b.toString();
    }

    static String encrypt(String pkg) {
        try {
            byte[] key = Arrays.copyOf("goodlock_multistar".getBytes("UTF-8"), 16);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(POLICY_IV));
            byte[] out = c.doFinal(pkg.getBytes("UTF-8"));
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "加密失败：" + pkg, t);
            return "";
        }
    }

    private static String joinWithUser(List<String> list, int uid) {
        StringBuilder b = new StringBuilder();
        for (String p : list) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(p).append(',').append(uid);
        }
        return b.toString();
    }

    static String join(List<String> list, String sep) {
        StringBuilder b = new StringBuilder();
        for (String p : list) {
            if (b.length() > 0) {
                b.append(sep);
            }
            b.append(p);
        }
        return b.toString();
    }

    private static String trimSlash(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** {@code pkg,uid;pkg,uid} 这种串，只挑属于当前用户的 */
    private static void addWithUser(Collection<String> out, String v, int uid) {
        if (TextUtils.isEmpty(v) || "null".equals(v)) {
            return;
        }
        String suffix = "," + uid;
        for (String item : v.split(";")) {
            if (TextUtils.isEmpty(item)) {
                continue;
            }
            int i = item.indexOf(',');
            if (i < 0) {
                if (item.endsWith(suffix)) {
                    item = item.substring(0, item.length() - suffix.length());
                }
            } else {
                String u = item.substring(i + 1);
                if (TextUtils.isEmpty(u) || String.valueOf(uid).equals(u)) {
                    item = item.substring(0, i);
                } else {
                    continue;   // 别的用户（工作资料）的，跳过
                }
            }
            valid(out, item);
        }
    }

    /** {@code pkg;pkg} 这种串 */
    private static void addPlain(Collection<String> out, String v) {
        if (TextUtils.isEmpty(v) || "null".equals(v)) {
            return;
        }
        for (String item : v.split(";")) {
            valid(out, item);
        }
    }

    private static void valid(Collection<String> out, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return;
        }
        String p = pkg.trim();
        if ("backup".equals(p) || p.indexOf('.') <= 0) {
            return;
        }
        out.add(p);
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace("\n", " | ");
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
