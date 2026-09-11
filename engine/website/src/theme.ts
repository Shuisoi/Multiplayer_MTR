import type {GlobalThemeOverrides} from "naive-ui";

/**
 * 把 Naive UI 的深色主题对齐 C# 端 ShuisoiSimUniverse 的外观：
 * 纯黑底、小圆角（4 px）、强调色 #0078D7、DIN 数字字体。
 *
 * 只覆盖需要的部分——Naive UI 的 darkTheme 本身已经足够暗，这里主要是把"黑"压到底、
 * 把强调色换成 C# 端的那个蓝，并让所有组件的字体继承我们的 token。
 */
export function themeOverrides(): GlobalThemeOverrides {
	return {
		common: {
			primaryColor: "#0078d7",
			primaryColorHover: "#1a8bea",
			primaryColorPressed: "#0069bd",
			primaryColorSuppl: "#1a8bea",
			bodyColor: "#000000",
			cardColor: "#0a0a0a",
			modalColor: "#0a0a0a",
			popoverColor: "#0a0a0a",
			borderColor: "#1f1f1f",
			dividerColor: "#1f1f1f",
			textColorBase: "#ffffff",
			textColor1: "#ffffff",
			textColor2: "#c8c8c8",
			textColor3: "#8a8a8a",
			fontFamily: '"Alte DIN 1451", "HarmonyOS Sans SC", "Microsoft YaHei", system-ui, sans-serif',
			fontFamilyMono: '"Alte DIN 1451", ui-monospace, Consolas, monospace',
			fontSize: "13px",
			borderRadius: "4px",
			borderRadiusSmall: "3px",
		},
		Card: {
			color: "#0a0a0a",
			borderColor: "#1f1f1f",
			titleFontSizeMedium: "15px",
		},
		Layout: {
			color: "#000000",
			siderColor: "#050505",
			headerColor: "#050505",
		},
		DataTable: {
			thColor: "#0f0f0f",
			tdColor: "#000000",
			tdColorHover: "#141414",
			borderColor: "#1f1f1f",
			thFontWeight: "600",
		},
		Menu: {
			color: "#050505",
			itemTextColor: "#c8c8c8",
			itemTextColorActive: "#ffffff",
			itemTextColorActiveHover: "#ffffff",
			itemTextColorHover: "#ffffff",
			itemColorActive: "rgba(0, 120, 215, 0.16)",
			itemColorActiveHover: "rgba(0, 120, 215, 0.22)",
			itemColorHover: "rgba(255, 255, 255, 0.06)",
			itemIconColorActive: "#0078d7",
			borderRadius: "4px",
		},
	};
}
