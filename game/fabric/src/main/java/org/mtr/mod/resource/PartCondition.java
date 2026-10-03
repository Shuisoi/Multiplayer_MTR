package org.mtr.mod.resource;

public enum PartCondition {
	NORMAL, AT_DEPOT,
	ON_ROUTE_FORWARDS, ON_ROUTE_BACKWARDS,
	DOORS_CLOSED, DOORS_OPENED,
	CHRISTMAS_LIGHT_RED,
	CHRISTMAS_LIGHT_YELLOW,
	CHRISTMAS_LIGHT_GREEN,
	CHRISTMAS_LIGHT_BLUE,
	/**
	 * MMTR 车灯**灯罩**（notes/374，2026-10-03）。
	 *
	 * <p>这组几何**一直画**（画不画不是它的语义）：颜色由"这一节车的灯罩在哪一端 + 那一端的档位"
	 * 逐 draw 决定 —— 近光/远光 = 白、尾灯 = 红、关闭/回库 = 暗玻璃。颜色在
	 * {@code MmtrHeadlights.lampColor} 里算，由 {@code VehicleResource.queue} 传给 MTR 的优化渲染器
	 * （{@code OptimizedRenderer.queue(..., color, light)} 的 color 就是逐 draw 的顶点色）。</p>
	 *
	 * <p>为什么要有这么一个新的条件值：MTR 自带的那些条件只认**方向**（{@code ON_ROUTE_FORWARDS} /
	 * {@code ON_ROUTE_BACKWARDS} = 车头/车尾），而 MMTR 的灯是**状态驱动**的（同一个灯罩既当近光/
	 * 远光、也当尾灯，颜色跟着引擎的档位走）⇒ 需要"这一组几何是灯罩"这个身份，
	 * 才能只在它身上染色。车底写法：{@code "partSpecs": { "headlights": [
	 * {"renderStage": "ALWAYS_ON_LIGHT", "condition": "MMTR_LAMP"} ] }}。</p>
	 */
	MMTR_LAMP,
}
