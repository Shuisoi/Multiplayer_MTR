/*
 * **站台**（`/mtr/api/map/mmtr-platforms`）的数据形状与"画在哪儿"的几何。
 *
 * <h2>引擎里"站台"是什么</h2>
 * <p>一根**被标记为站台的轨**：`Rail.checkOrCreateSavedRailAndUpdateTiltAngles()` 用轨自己的两个端点建
 * {@code Platform}，而 {@code SavedRailBase.mmtrGraphRail()} 又把它解析回那根轨。所以"哪些轨是站台轨"
 * 是引擎的结论 —— 网页照用，不自己算（与点/区间那几套 feed 同一条规矩）。</p>
 *
 * <h2>网页要它的三样东西</h2>
 * <ol>
 *   <li>**名字**：站名 + 站台号（用户 2026-09-16："包括其方向和站名，站台号"）；</li>
 *   <li>**方向**：站台轴 —— 用来决定"I"字的朝向与文字的旋转（"字体排列方向与站的轨道平行"）；</li>
 *   <li>**范围**：两端 + 所在轨的 hex（前端按 hex 取那根轨在**这一页已经画出来的** path，
 *       于是 I 必然贴着轨 —— 直接拿引擎给的方块角坐标会差半格，见 `Rail.railSampleShift()`）。</li>
 * </ol>
 *
 * <h2>轴是"两向"的，文字要摆正</h2>
 * <p>站台两头都能进车，所以轴没有"正方向"这回事；文字的旋转角必须归一到"能正着读"的那一半
 * （{@link uprightRotation}），否则一半站名会倒过来写。</p>
 */
import {parseFlatPoints} from "./TrackSection.ts";

/** 引擎原始的一条站台。 */
export interface RawPlatform {
	readonly platformId?: string;
	readonly platformHex?: string;
	readonly platformName?: string;
	readonly stationId?: string;
	readonly stationHex?: string;
	readonly stationName?: string;
	readonly dwellMillis?: number;
	readonly railHex?: string;
	readonly x1?: number;
	readonly y1?: number;
	readonly z1?: number;
	readonly x2?: number;
	readonly y2?: number;
	readonly z2?: number;
	readonly lengthM?: number;
	readonly direction?: {readonly dx?: number; readonly dz?: number; readonly angle?: number; readonly label?: string};
}

/** 一条站台（界面用的形状）。 */
export interface Platform {
	readonly platformId: string;
	readonly platformHex: string;
	/** 站台号（引擎里的站台名，例如 "1"）。 */
	readonly platformName: string;
	readonly stationId: string;
	readonly stationHex: string;
	/** 站名（引擎原文，可能带 MTR 的 `|`/`||` 格式语法）。 */
	readonly stationName: string;
	readonly dwellMillis: number;
	/** 站台所在的那根轨（规范 hex，与 `/mmtr-topology` 的 rails[].hex 同一写法）；取不到轨时是空串。 */
	readonly railHex: string;
	/** 站台两端（世界坐标，方块角那一套 —— 只在前端取不到轨的 path 时当退路）。 */
	readonly end1: readonly [number, number];
	readonly end2: readonly [number, number];
	/** 引擎给的方向（轴，弦方向；0 = 南/+z，90 = 西/−x，与 MTR 的 facing 同一套）。 */
	readonly axis: readonly [number, number];
	readonly angle: number;
	readonly directionLabel: string;
	readonly lengthM: number;
}

/** 解析接口给的一批站台。 */
export function parsePlatforms(raw: readonly RawPlatform[]): Platform[] {
	return raw.map(platform => {
		const end1 = [platform.x1 ?? 0, platform.z1 ?? 0] as const;
		const end2 = [platform.x2 ?? 0, platform.z2 ?? 0] as const;
		const dx = platform.direction?.dx ?? 0;
		const dz = platform.direction?.dz ?? 0;
		const length = Math.hypot(dx, dz);
		return {
			platformId: platform.platformId ?? "",
			platformHex: platform.platformHex ?? "",
			platformName: platform.platformName ?? "",
			stationId: platform.stationId ?? "",
			stationHex: platform.stationHex ?? "",
			stationName: platform.stationName ?? "",
			dwellMillis: platform.dwellMillis ?? 0,
			railHex: platform.railHex ?? "",
			end1,
			end2,
			axis: length === 0 ? [0, 1] as const : [dx / length, dz / length] as const,
			angle: platform.direction?.angle ?? 0,
			directionLabel: platform.direction?.label ?? "",
			lengthM: platform.lengthM ?? 0,
		};
	});
}

/**
 * 文字的旋转角（度，SVG 的顺时针为正、y 轴向下 = 世界 z）。
 *
 * <p>轴 (dx, dz) 直接取 `atan2(dz, dx)` 就是"文字基线沿轴"的角度；但轴是两向的，
 * 落在 (−180, −90] 或 (90, 180] 时文字会**倒着写**，所以再加/减 180° 归一：
 * 结果恒在 (−90, 90] —— 这也是所有地图标注的常规做法。</p>
 */
export function uprightRotation(axisX: number, axisZ: number): number {
	const raw = Math.atan2(axisZ, axisX) * 180 / Math.PI;
	if (raw > 90) {
		return raw - 180;
	}
	if (raw <= -90) {
		return raw + 180;
	}
	return raw;
}

/**
 * 站台**外侧**的单位向量：I 字与文字画在这一侧（用户："在站台侧表示站"）。
 *
 * <p>引擎不记"站台的实体方块在轨的哪一侧"（`Platform` 只有两端与停站时间），所以这里按
 * **背离本站其他站台**挑一侧：两个站台的站（最常见）自然把 I 画在两根轨的外侧，
 * 看起来就是一对侧式站台；只有一个站台、或两个站台重合时退回固定的一侧（轴的顺时针法向）。</p>
 *
 * @param axis         站台轴（单位向量）
 * @param towardOthers 从**本站台**中点指向**本站其他站台**中点的向量（没有就传 null）——
 *                     外侧取的是它的**反方向**
 */
export function platformSideDirection(axis: readonly [number, number], towardOthers: readonly [number, number] | null): readonly [number, number] {
	// 屏幕坐标（x 右、y 下 = 世界 z）里"顺时针转 90°"= (−dz, dx)。
	const clockwise: readonly [number, number] = [normalizeZero(-axis[1]), normalizeZero(axis[0])];
	if (towardOthers === null) {
		return clockwise;
	}
	const otherLength = Math.hypot(towardOthers[0], towardOthers[1]);
	if (otherLength < 1e-9) {
		return clockwise;
	}
	// 要"背离"其他站台 ⇒ 取与 towardOthers 反向的那一侧
	const dot = clockwise[0] * towardOthers[0] + clockwise[1] * towardOthers[1];
	return dot <= 0 ? clockwise : [normalizeZero(axis[1]), normalizeZero(-axis[0])];
}

/**
 * 把 `-0` 归一成 `0`：数值与渲染都一样，但它会让"逐位相等"的断言与日志看着别扭
 * （`Object.is(-0, 0)` 是 false）—— 与引擎里 `bandOffsetDirection` 的同一条处理。
 */
function normalizeZero(value: number): number {
	return value === 0 ? 0 : value;
}

/** 一条站台的范围：I 字要走的那条折线（世界坐标）—— 有轨就用轨的采样点，没有就用两端。 */
export function platformExtent(platform: Platform, railPath: readonly (readonly [number, number])[] | null): readonly (readonly [number, number])[] {
	if (railPath !== null && railPath.length >= 2) {
		return railPath;
	}
	return [platform.end1, platform.end2];
}

/** 折线两端的方向（弦，单位向量）—— 文字旋转与外侧判断都用它，保证与画出来的线一致。 */
export function extentAxis(points: readonly (readonly [number, number])[]): readonly [number, number] {
	if (points.length < 2) {
		return [0, 1];
	}
	const first = points[0]!;
	const last = points[points.length - 1]!;
	const dx = last[0] - first[0];
	const dz = last[1] - first[1];
	const length = Math.hypot(dx, dz);
	return length === 0 ? [0, 1] : [dx / length, dz / length];
}

/** 折线中点（按弧长）。 */
export function extentMidpoint(points: readonly (readonly [number, number])[]): readonly [number, number] {
	if (points.length === 0) {
		return [0, 0];
	}
	if (points.length === 1) {
		return points[0]!;
	}
	const lengths: number[] = [0];
	for (let i = 1; i < points.length; i++) {
		lengths.push(lengths[i - 1]! + Math.hypot(points[i]![0] - points[i - 1]![0], points[i]![1] - points[i - 1]![1]));
	}
	const half = lengths[lengths.length - 1]! / 2;
	for (let i = 1; i < points.length; i++) {
		if (lengths[i]! >= half) {
			const t = (half - lengths[i - 1]!) / (lengths[i]! - lengths[i - 1]!);
			return [
				points[i - 1]![0] + (points[i]![0] - points[i - 1]![0]) * t,
				points[i - 1]![1] + (points[i]![1] - points[i - 1]![1]) * t,
			];
		}
	}
	return points[points.length - 1]!;
}

/** 把接口给的扁平 `points` 换成世界坐标点对（供 `platformExtent` 用）。 */
export function parsePlatformRailPath(points: readonly number[] | null): readonly (readonly [number, number])[] | null {
	if (points === null || points.length < 4) {
		return null;
	}
	return parseFlatPoints(points);
}
