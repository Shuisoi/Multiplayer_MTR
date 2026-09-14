/**
 * 坐标的两种写法，**别混用**。
 *
 * <h3>为什么单开一个文件</h3>
 * <p>同一个坐标在这套界面里有两个用途，写法**必须不同**，而之前只有一种写法：</p>
 * <ul>
 *   <li><b>给人读</b>：{@code -70, -59, -167}（逗号+空格）—— 卡片、提示、日志里显示用；</li>
 *   <li><b>给引擎吃</b>：{@code -70 -59 -167}（**空格分隔的整数**）—— 指令的位置参数就是这个格式
 *       （{@code signal why -70 -59 -167}、{@code query node x y z}）。</li>
 * </ul>
 *
 * <p>实测的坑：界面上只有第一种，于是"复制坐标"粘进指令栏必然解析失败（两个逗号要手改）。
 * 所以凡是"要把坐标送进指令/记录成可复现文本"的地方，一律走 {@link commandCoords}。</p>
 */

/** 一个带世界坐标的对象（节点/灯/股道端点都满足）。 */
export interface HasCoords {
	readonly x: number;
	readonly y: number;
	readonly z: number;
}

/** 给人读的写法：`-70, -59, -167`。 */
export function readableCoords(coords: HasCoords): string {
	return `${coords.x}, ${coords.y}, ${coords.z}`;
}

/** 给引擎吃的写法：`-70 -59 -167`（指令的位置参数格式）。 */
export function commandCoords(coords: HasCoords): string {
	return `${coords.x} ${coords.y} ${coords.z}`;
}

/** 只取平面坐标（x z）：用户口头报灯位时常这么说（例如"(-70,-167)"）。 */
export function planeCoords(coords: HasCoords): string {
	return `${coords.x}, ${coords.z}`;
}
