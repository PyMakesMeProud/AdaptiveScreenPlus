package com.wb.extrotator;

/**
 * 「老虎机（外屏）」—— 封面屏那一份，逻辑与内屏完全相同。
 *
 * <p>单独拆一个类，是因为三星封面屏的小组件要求**内外屏各一个 receiver**：
 * 外屏那个只声明 {@code widgetCategory="keyguard"}+ 三星私有 meta-data，
 * 内屏那个只声明 {@code home_screen}。混在一个 receiver 里时外屏托盘列不出来
 * （依据与实测见 {@code res/xml/widget_panel_cover.xml}）。
 *
 * <p>两边的摇奖、判定、战绩都是同一套代码（父类 {@link SlotWidgetProvider}），
 * 唯一分开写的是 res/xml 里那两份尺寸声明：
 * 内屏 4×2 = 250×110dp，外屏 4×2 = 312×133dp（三星自家的 4×2 槽位尺寸）。
 *
 * <p>⚠ 战绩是**两份共用的**（同一个 SharedPreferences）—— 内外屏各摇一次算两次，
 * 这是刻意的：它就是一台机器上的同一个游戏。
 */
public class SlotCoverWidgetProvider extends SlotWidgetProvider {
}
