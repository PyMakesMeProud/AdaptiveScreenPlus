# -*- coding: utf-8 -*-
"""
从 4 列版派生「外屏启动器」小组件的 5 / 6 / 7 列布局。

── 为什么需要这个脚本 ────────────────────────────────────────────────
小组件布局里 GridView 的 numColumns **不能运行时改**：
setNumColumns 没有 @RemotableViewMethod 注解，RemoteViews 在 targetSdk 28+
上会拒绝反射调用它（见 widget_launcher.xml 顶部注释）。所以"一排几个"
只能靠换布局文件 —— 四份内容几乎一样，只有 numColumns 不同。

手抄四份迟早会漂（改了一处忘了另外三处），所以从 4 列版派生：
**唯一的真源是 widget_launcher.xml**，改完它重跑本脚本即可。

⚠ 加档位（比如 8 列）时：改这里的 NUMBERS、LauncherPrefs.COLUMN_CHOICES、
  CoverLauncherProvider.layoutFor，三处一起加。

── 用法 ──────────────────────────────────────────────────────────────
    python ExtDisplayRotator/scripts/gen_launcher_column_layouts.py

输出：widget_launcher_c5.xml / _c6.xml / _c7.xml（覆盖写）
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LAYOUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "res", "layout")

SRC = os.path.join(LAYOUT_DIR, "widget_launcher.xml")

# 要派生的列数
NUMBERS = (5, 6, 7)

# 头部注释里这两句要跟着换（其余原文照抄）
TITLE_FROM = u"小组件的 RemoteViews 布局（4 列版）"
BODY_FROM = (u"本文件 4 列，另有\n"
             u"  widget_launcher_c5.xml / _c6.xml / _c7.xml，由 CoverLauncherProvider\n"
             u"  按设置挑一份 inflate。改这四份里的任何一份，记得四份一起改。")

BODY_TO = (u"这份是从 widget_launcher.xml **派生的**，只改了 numColumns。\n"
           u"  别单独改这一份 —— 要改就改 4 列那份再重跑 gen_launcher_column_layouts.py，\n"
           u"  四份由 CoverLauncherProvider 按设置挑一份 inflate。")


def main():
    src_path = os.path.normpath(SRC)
    if not os.path.exists(src_path):
        print("找不到源文件: " + src_path)
        return 1

    with io.open(src_path, encoding="utf-8") as f:
        src = f.read()

    if u'android:numColumns="4"' not in src:
        print(u"源文件里没找到 numColumns=\"4\"，先确认 widget_launcher.xml 的样子")
        return 1
    if TITLE_FROM not in src:
        print(u"头部注释标题对不上，脚本里的 TITLE_FROM 要跟着改")
        return 1
    if BODY_FROM not in src:
        print(u"⚠ 头部那段列数说明没匹配上 —— 先把脚本里的 BODY_FROM 改成"
              u" widget_launcher.xml 里的原文，否则派生出来的注释会留着旧说法")

    for n in NUMBERS:
        out = src.replace(TITLE_FROM, u"小组件的 RemoteViews 布局（%d 列版）" % n)
        out = out.replace(u'android:numColumns="4"', u'android:numColumns="%d"' % n)
        if BODY_FROM in out:
            out = out.replace(BODY_FROM, BODY_TO)
        else:
            print(u"⚠ %d 列版的头部注释没匹配上，保留原文" % n)

        dst = os.path.join(LAYOUT_DIR, "widget_launcher_c%d.xml" % n)
        with io.open(dst, "w", encoding="utf-8", newline="\n") as f:
            f.write(out)
        print(u"写出 %s  (%d 字节)" % (os.path.basename(dst), len(out.encode("utf-8"))))

    print(u"完成：%d 份布局结构一致，只有 numColumns 不同" % (len(NUMBERS) + 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
