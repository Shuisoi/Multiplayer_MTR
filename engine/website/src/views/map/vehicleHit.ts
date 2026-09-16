/*
 * **点得动车辆记号**：命中判定的纯函数部分。
 *
 * <h2>为什么不能按元素监听</h2>
 * <p>记号很细（宽 1.2 格）而很长（真车长，16 米 = 16 格）：默认取景下宽度只有约 1 px。
 * 拿鼠标去戳一个 1 px 宽的箭头等于点不到（节点层踩过同一个坑，见 `RailNodesLayer.hitTest`），
 * 所以判定自己做：指针 → **画布坐标**，再在"记号的**旋转矩形** + 一个屏幕半径的余量"里找最近的。
 * 余量定在**屏幕像素**上（调用方把 `radiusUnits = HIT_RADIUS_PX / 相机比例` 传进来），
 * 于是任何缩放级别的手感都一样。</p>
 *
 * <h2>为什么是矩形而不是"到中心的距离"</h2>
 * <p>一列车 16 格长、1.2 格宽：按"到中心 12 px"判，就只有车心那一小块点得动，而它明明画了一整条。
 * 矩形判定让**整条车身**都能点，且点在车头/车尾/车心都算 —— 与"看起来是什么"一致。</p>
 *
 * <p>纯函数放这里（而不是写在组件里）是为了能用例钉住：旋转、余量、多节重叠时选谁，
 * 这三件事都是"错了也点得动"的那种。</p>
 */

/** 一个能点的记号（组件算好的画布坐标 + 尺寸）。 */
export interface HitMarker<T> {
	readonly item: T;
	/** 记号中心（画布坐标）。 */
	readonly x: number;
	readonly y: number;
	/** 记号的旋转角（**度**，顺时针，0 = 车头朝 +x）——与模板上 `rotate()` 同一个数。 */
	readonly rotation: number;
	/** 记号长度（画布单位，= 真车长）。 */
	readonly lengthUnits: number;
	/** 记号宽度（画布单位，固定值）。 */
	readonly widthUnits: number;
}

/** 把点换算到记号的**局部坐标**（中心为原点、车头朝 +x）：世界→局部的逆变换（先平移、再反向旋转）。 */
export function toMarkerLocal(marker: HitMarker<unknown>, x: number, y: number): readonly [number, number] {
	const radians = -marker.rotation * Math.PI / 180;
	const dx = x - marker.x;
	const dy = y - marker.y;
	return [dx * Math.cos(radians) - dy * Math.sin(radians), dx * Math.sin(radians) + dy * Math.cos(radians)];
}

/** 这个点是否落在记号的矩形（各自外扩 `radiusUnits`）里。 */
export function hitsMarker(marker: HitMarker<unknown>, x: number, y: number, radiusUnits: number): boolean {
	const [localX, localY] = toMarkerLocal(marker, x, y);
	return Math.abs(localX) <= marker.lengthUnits / 2 + radiusUnits && Math.abs(localY) <= marker.widthUnits / 2 + radiusUnits;
}

/**
 * 命中的是哪一个记号：**重叠时取中心离指针最近的那个**（编组里相邻两节会挨着，
 * 取最近才符合"我点的是我看到的那一节"）。
 *
 * @param markers     能点的记号（含每一节车；一列车的每一节都单独命中，返回的那一项由调用方认车）
 * @param x           指针的画布坐标
 * @param y           指针的画布坐标
 * @param radiusUnits 命中余量（**画布单位**；调用方按屏幕像素 ÷ 相机比例算好）
 */
export function hitMarker<T>(markers: readonly HitMarker<T>[], x: number, y: number, radiusUnits: number): T | null {
	let best: T | null = null;
	let bestDistance = Number.POSITIVE_INFINITY;
	for (const marker of markers) {
		if (!hitsMarker(marker, x, y, radiusUnits)) {
			continue;
		}
		const distance = Math.hypot(marker.x - x, marker.y - y);
		if (distance < bestDistance) {
			best = marker.item;
			bestDistance = distance;
		}
	}
	return best;
}
