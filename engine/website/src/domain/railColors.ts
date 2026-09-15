/**
 * 轨道线的颜色（限速 → 颜色），**共享**给需要跟轨道同色的图层。
 *
 * <p>为什么单独抽一个文件：用户 2026-09-15 的要求是"区间颜色从目前 web 生成的线派生，别独立生成了"。
 * 区间是"一段轨上的弧窗"，它画出来的颜色**必须与那段轨本身一样** —— 否则同一段路在屏幕上出现两种颜色，
 * 看图的人会以为它们是两样东西。但区间层不该因此去 import `RailLayer.vue`（一个组件）：
 * 颜色是**几何/样式约定**，属于 domain。</p>
 *
 * <p>所以：`RailLayer` 与 `SectionLayer` 都从这里取色，改一处两边同时变。</p>
 */

/** 限速门槛与对应颜色（从高到低）。轨道层与区间层共用这一份。 */
const SPEED_BANDS: readonly {minKmh: number; color: string}[] = [
	{minKmh: 300, color: "#8b9aab"},
	{minKmh: 200, color: "#75839a"},
	{minKmh: 160, color: "#616e80"},
	{minKmh: 80, color: "#4e585f"},
];

/** 低于最低门槛的颜色（站场/库线这类慢速轨）。 */
const SLOW_COLOR = "#414a50";

/**
 * **占用**的区间色。
 *
 * <p>这是**状态**色，不是配色：区间带平时用轨道的颜色，但"这一段被占用"是当下的事实，
 * 必须一眼看出来。现场实测的渲染值 `rgb(255, 123, 116)` 就是这个色。</p>
 *
 * <p>放在这里而不是散在组件里：这样"页面上一共会出现哪些颜色"是**一处定义**的，
 * 检查脚本也就能按名字断言"除了这个状态色，带色必须全部来自轨道线"。</p>
 */
export const SECTION_OCCUPIED_COLOR = "#ff7b74";

/**
 * 限速 → 轨道线颜色。速度越高越亮，高限速干线在暗底上自然浮起来。
 *
 * @param speedKmh 限速（km/h）；引擎未给时按 0 处理（= 最慢那一档）
 */
export function speedBandColor(speedKmh: number): string {
	for (const band of SPEED_BANDS) {
		if (speedKmh >= band.minKmh) {
			return band.color;
		}
	}
	return SLOW_COLOR;
}
