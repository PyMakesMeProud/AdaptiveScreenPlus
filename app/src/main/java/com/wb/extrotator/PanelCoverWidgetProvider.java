package com.wb.extrotator;

/**
 * 「双屏快捷面板（外屏）」—— 给封面屏用的那一份，行为完全继承内屏那份，
 * 只有清单里的声明不同（见 {@code res/xml/widget_panel_cover.xml}）。
 *
 * <p>⚠ 必须单独一个类：接收器的身份 = 包名 + 类名，两个 {@code <receiver>} 不能指向同一个类。
 *
 * <p>父类按 {@code getClass()} 挑布局，所以这里拿到的是 {@code widget_panel_2x2.xml}
 * （2×2 四格铺满、四个按钮），内屏那份仍是 2×1 横条。
 */
public class PanelCoverWidgetProvider extends PanelWidgetProvider {
}
