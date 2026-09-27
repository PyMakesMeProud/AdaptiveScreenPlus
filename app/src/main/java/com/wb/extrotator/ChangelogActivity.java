package com.wb.extrotator;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「版本与更新」页，从标题旁边那个「vX.Y · 更新说明」胶囊点进来。
 * 记录按时间倒序排，最新在最上面。
 */
public class ChangelogActivity extends Activity {

    /**
     * 更新日志。每行是 {版本号, 日期, 第 1 条, 第 2 条, ...}，倒序排列。
     */
    // ⚠ 写作规矩：只提新增 / 修复了什么，一两句打住；新功能只标出在哪儿、不写用法，
    //   UI 或文案优化只说哪变了、不说变成什么，涉及多处就归类叙述。
    // ⚠ 版本号规矩：某版里出现被否掉的改动时，下一版不推进版本号，并整条删掉那次日志，
    //   等改动确认有效后再写回、那时才推进。
    private static final String[][] LOG = {
            {"v4.23", "2026-09-26",
                    "外屏通知面板：排版与对齐有调整，快捷开关可以自己挑并新增三个动作；外屏自动旋转方向修正",
                    "新增外屏组件「自定义操作」「手机状态」、副桌面伪应用「副屏播放器」，以及首次打开的功能导览"},
            {"v4.22", "2026-09-26",
                    "外屏通知面板重做：铺满全屏，左栏常用应用、顶部快捷开关都可以自己挑，按钮与图标有调整",
                    "新增「外屏自动旋转」（Beta 实验室 · 外屏管理、快捷操作、老虎机小组件）；修：老虎机摇奖时没有滚动动画"},
            {"v4.21", "2026-09-26",
                    "外屏启动器、回主界面与返回不再依赖 Shizuku，「指令操作」新增「授予安全设置权限」",
                    "新增「老虎机」小组件（摇奖图案为应用图标）；外屏「双屏快捷面板」的按钮有调整"},
            {"v4.20", "2026-09-26",
                    "外屏侧边栏：「面板内容」可以调顺序，动画新增「抽奖轮盘」"},
            {"v4.19", "2026-09-23",
                    "优化内屏手势的动画效果",
                    "优化内屏手势的游戏体验",
                    "修：左侧边缘划入不返回"},
            {"v4.18", "2026-09-23",
                    "「副屏遥控板」已移除"},
            {"v4.17", "2026-09-22",
                    "主界面快捷操作、双屏面板伪应用开关的排版调整",
                    "Beta 实验室应用黑边的设置移入「应用管理」，各设置项的说明文字精简"},
            {"v4.16", "2026-09-22",
                    "投屏控制新增「全屏」，快捷操作新增「一键投屏」",
                    "全屏期间外屏的侧滑栏与手势条自动收起；修：全屏画面铺不满、点着偏移，一键投屏退出后落到锁屏页"},
            {"v4.15", "2026-09-22",
                    "新增「状态展示柜」（副屏）与「刷新率」（内屏管理）",
                    "文案：全应用的 ⓘ 说明再重写一轮",
                    "修：Beta 实验室的「打开无线调试」会把自己关掉"},
            {"v4.14", "2026-09-22",
                    "外屏启动器可以设背景图片了（配置页顶栏的图片图标）"},
            {"v4.13", "2026-09-22",
                    "「打开无线调试」改成一次性按钮（快捷操作）",
                    "全应用的 ⓘ 说明重写；外屏启动器底板改名「不透明度」并重设档位，小组件右上角图标左移避开侧边栏"},
            {"v4.12", "2026-09-21",
                    "新增「打开无线调试」按钮（快捷操作）",
                    "Beta 新增「应用管理」；外屏启动器长按菜单改为就地弹出"},
            {"v4.11", "2026-09-21",
                    "「双屏」页重排",
                    "外屏启动器新增收藏夹"},
            {"v4.10", "2026-09-21",
                    "「自动旋转」页重排，「同步模式」整块删除",
                    "「授权与状态」页新增「开机后进入」"},
            {"v4.9", "2026-09-20",
                    "「外屏手势后台」改名「手势条」，新增「外屏 0° 时固定在左下角」",
                    "「副屏管理」搬进主界面「双屏」选项卡，新增「启动 DeX 模式」"},
            {"v4.8", "2026-09-20",
                    "修：外屏手势呼出最近任务时偶尔显示「无最近使用的应用程序」"},
            {"v4.7", "2026-09-20",
                    "底部手势区改成两个动作：上滑回主界面、上滑停住开最近任务",
                    "手势区缩到导航条正中一段，非手势的触摸原样交给下面的应用"},
            {"v4.5", "2026-09-20",
                    "底部手势区与侧边栏各加一条常驻亮杠",
                    "修：通知消失时应用崩溃"},
            {"v4.4", "2026-09-20",
                    "新增「外屏侧边栏」"},
            {"v4.3", "2026-09-20",
                    "修：「外屏手势后台」误触发",
                    "修：外屏应用底部按钮点不动"},
            {"v4.2", "2026-09-19",
                    "「去掉应用黑边」改成按钮并移到「指令操作」页；「屏幕增强」页改名「手势增强」",
                    "「外屏手势后台」触发时加一次短震动"},
            {"v4.1", "2026-09-19",
                    "新增「屏幕增强」页，收进「全局去掉黑边」与「外屏手势后台」"},
            {"v4.0", "2026-09-19",
                    "新增「投屏控制」",
                    "「副屏触控屏」改名「副屏触控板」；副屏各功能的开关收进「副屏管理」"},
            {"v3.2", "2026-09-18",
                    "「手动顺序」与「副屏桌面顺序」拆成两份，各自独立"},
            {"v3.1", "2026-09-18",
                    "修：长按音量键会先掉一格音量；无障碍服务的能力声明一次备齐"},
            {"v3.0", "2026-09-18",
                    "新增「Beta 实验室」，并加入可高度自定义的「外屏启动器」",
                    "新增外屏分辨率与 DPI 覆盖、强制旋转、侧键操作与自定义指令"},
            {"v2.17", "2026-09-15",
                    "修：「保存参数设置」表里补回「方向同步」与「自动旋转」两行（v2.14 误删）"},
            {"v2.16", "2026-09-15",
                    "修：「折叠/展开模式切换」切到折叠后屏幕会黑"},
            {"v2.9–v2.15", "2026-09-15",
                    "修：锁横与自适应旋转的联动、只支持竖屏的应用里画面留黑边、外屏小组件从列表消失、点「折叠/展开」连切两次",
                    "改：外屏小组件铺满 2×2；接屏套用的档案不再改「方向同步 / 锁横」两个开关"},
            {"v2.8", "2026-09-15",
                    "修：折叠/展开切换改看「内屏亮没亮」；参数表「自动旋转」显示反了",
                    "改动：尝试缩小外屏小组件占位（v2.9 已按 2×2 重做）"},
            {"v2.7", "2026-09-15",
                    "文案：全部详情按「功能 / 用法 / 注意事项」重写",
                    "表格与命名调整（保存参数设置 · 分辨率自动加载 · 折叠/展开模式切换 · 启动双屏模式 · 快捷小组件）"},
            {"v2.6", "2026-09-15",
                    "「按外接屏保存设置」提到页面顶端，改成已保存 / 当前两列对照",
                    "修：封面屏小组件被拉伸铺满整页；折叠 / 展开切换偶尔要点两次"},
            {"v2.5", "2026-09-15",
                    "「锁手机方向」成为独立开关；新增「按外接屏保存设置」",
                    "修：封面屏的小组件列表里看不到本应用"},
            {"v2.4", "2026-09-15",
                    "「内屏管理」页的状态信息提到最上面",
                    "双屏页分节改名，两个开关并排成一行"},
            {"v2.3", "2026-09-14",
                    "界面精简：详细说明收进操作名旁的「ⓘ」",
                    "新增本页"},
            {"v2.2", "2026-09-14",
                    "修：开启双屏模式后外接屏被锁死在 270°",
                    "两个桌面小组件合并为一个「双屏快捷面板」"},
            {"v2.1", "2026-09-14",
                    "首个选项卡重新排版"},
            {"v2.0", "2026-09-13",
                    "修：合盖或折叠时外接屏被当场转 270°、每转一次手机就多排一次版、关掉开关后仍被钉在横屏",
                    "首个选项卡新增三个快捷操作"},
            {"v1.9", "2026-09-13",
                    "双屏页收敛成「一键启动双屏」",
                    "新增桌面小组件"},
            {"v1.8", "2026-09-13",
                    "封面屏点亮后不再是黑屏：往那块屏投一张副屏桌面",
                    "新增「外接屏桌面模式」开关"},
            {"v1.7", "2026-09-13",
                    "改名「自适应屏幕」，界面重排为四个选项卡",
                    "新增折叠 / 展开状态切换与「内屏管理」选项卡"},
            {"v1.6", "2026-09-13",
                    "修：偶发只剩 1/4 屏（改看几何校验）",
                    "开关与系统状态双向同步"},
            {"v1.5", "2026-09-13",
                    "响应速度：方向跟随从 2.6~3.3 秒降到 0.44 秒",
                    "改用显示变化事件驱动，不再定时轮询"},
            {"v1.4", "2026-09-13",
                    "内屏分辨率改成按钮开关",
                    "自动读取便携屏面板分辨率"},
            {"v1.3", "2026-09-13",
                    "接管手机内屏分辨率，可替代 SecondScreen",
                    "插入便携屏时弹窗询问，拔掉后自动还原"},
            {"v1.2", "2026-09-13",
                    "修：手机横竖切换时镜像画面错位",
                    "新增手机方向锁定"},
            {"v1.1", "2026-09-13",
                    "修：Shizuku 显示已授权，应用却检测不到"},
            {"v1.0", "2026-09-13",
                    "初版：手机竖屏时自动把外接便携屏旋转 90°"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_changelog);

        findViewById(R.id.btnChangeBack).setOnClickListener(v -> finish());

        String versionName = "—";
        long versionCode = 0L;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pi.versionName == null ? "—" : pi.versionName;
            versionCode = pi.getLongVersionCode();
        } catch (Throwable ignored) {
        }

        ((TextView) findViewById(R.id.tvChangeVersion)).setText("v" + versionName);
        ((TextView) findViewById(R.id.tvChangeBuild)).setText(
                getString(R.string.changelog_current)
                        + " v" + versionName + "（versionCode " + versionCode + "）");

        renderLog(findViewById(R.id.changeContainer), versionName);
    }

    /** 把 {@link #LOG} 铺成一条条版本记录。 */
    private void renderLog(LinearLayout box, String currentVersion) {
        if (box == null) {
            return;
        }
        for (int i = 0; i < LOG.length; i++) {
            String[] entry = LOG[i];
            boolean isCurrent = ("v" + currentVersion).equalsIgnoreCase(entry[0]);

            // ---- 版本号 + 日期（第一条离顶部近一点）----
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            headLp.topMargin = dp(i == 0 ? 14 : 22);
            head.setLayoutParams(headLp);

            TextView ver = new TextView(this);
            ver.setText(entry[0]);
            ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            ver.setTypeface(Typeface.DEFAULT_BOLD);
            ver.setTextColor(0xFF1565C0);
            head.addView(ver);

            TextView date = new TextView(this);
            date.setText(entry[1]);
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            date.setTextColor(0x99000000);
            LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            dateLp.leftMargin = dp(8);
            date.setLayoutParams(dateLp);
            head.addView(date);

            if (isCurrent) {
                TextView now = new TextView(this);
                now.setText("当前");
                now.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                now.setTextColor(0xFF1565C0);
                now.setBackgroundResource(R.drawable.version_chip);
                now.setPadding(dp(8), dp(2), dp(8), dp(2));
                LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                nowLp.leftMargin = dp(8);
                now.setLayoutParams(nowLp);
                head.addView(now);
            }
            box.addView(head);

            // ---- 这一版的改动条目 ----
            for (int k = 2; k < entry.length; k++) {
                TextView bullet = new TextView(this);
                bullet.setText("·  " + entry[k]);
                bullet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                bullet.setTextColor(0xDE000000);
                bullet.setLineSpacing(dp(3), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(5);
                lp.leftMargin = dp(2);
                bullet.setLayoutParams(lp);
                box.addView(bullet);
            }
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
