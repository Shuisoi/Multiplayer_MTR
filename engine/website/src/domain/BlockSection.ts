/*
 * L2 **行车区间**（`/mtr/api/map/mmtr-block-sections`）的数据形状与"显示状态"。
 *
 * <h2>引擎给的是什么</h2>
 * <p>一条行车区间 = **有方向**、灯到灯、跨轨的一段路；无灯的连通块整块算一段。它是**授权**
 * （显示与停车）的单位。每条带：入口/出口灯、下一区间、{@code aspect}（入口灯现在的灯位）、
 * {@code occupied}、{@code direction}（这区间服务哪个行车方向）、{@code members}（由哪些 L1 轨道区间拼成），
 * 以及若干 {@code spans}（沿轨的一截 + 采样点 + 是否顺向 + 是否走得到）。</p>
 *
 * <h2>"显示状态"为什么不是只读 aspect</h2>
 * <p>实测这张世界的交叉表（101 条）：</p>
 * <pre>
 *   GREEN          + 不占用 : 67
 *   RED            + 不占用 : 13   ← 灯是红的，但区间里**没有车**（前方占用导致的）
 *   RED            + 占用   : 8
 *   SINGLE_YELLOW  + 不占用 : 7
 *   DOUBLE_YELLOW  + 不占用 : 6
 * </pre>
 * <p>也就是"占用 ⟹ 红灯，但红灯不一定占用"。所以状态判据要显式写下来（{@link sectionState}），
 * 而不是"看灯位"或"看有没有车"各读一半 —— 两处各读一半，图上就会出现"红灯区间被画成绿的"。</p>
 */
import {parseFlatPoints} from "./TrackSection.ts";

/** 引擎原始的一段。 */
export interface RawBlockSpan {
	readonly hex: string;
	readonly from: number;
	readonly to: number;
	readonly dirOfTravel?: boolean;
	readonly reachable?: boolean;
	/** **扁平**采样点 `[x0,z0,x1,z1,…]`（与 L1 同一套坐标：方块中心）。 */
	readonly points?: readonly number[];
}

/** 引擎原始的行车方向。 */
export interface RawBlockDirection {
	readonly angle?: number;
	readonly label?: string;
	readonly dx?: number;
	readonly dz?: number;
}

/** 引擎原始的一条行车区间。 */
export interface RawBlockSection {
	readonly id: string;
	readonly entrySignal?: string;
	readonly exitSignal?: string;
	readonly next?: string;
	readonly aspect?: string;
	readonly occupied?: boolean;
	readonly length?: number;
	readonly uncovered?: boolean;
	readonly direction?: RawBlockDirection;
	readonly members?: readonly string[];
	readonly spans?: readonly RawBlockSpan[];
}

/** 一段（界面用的形状）。 */
export interface BlockSpan {
	readonly railHex: string;
	readonly fromM: number;
	readonly toM: number;
	/** 这一段是不是顺着区间方向走。 */
	readonly alongDirection: boolean;
	/** 走得到（道岔当前位置没把它切掉）。 */
	readonly reachable: boolean;
	readonly points: readonly (readonly [number, number])[];
}

/** 一条行车区间（界面用的形状）。 */
export interface BlockSection {
	readonly id: string;
	readonly entrySignal: string;
	readonly exitSignal: string;
	readonly nextId: string;
	readonly aspect: string;
	readonly occupied: boolean;
	readonly lengthM: number;
	readonly uncovered: boolean;
	readonly directionLabel: string;
	readonly dx: number;
	readonly dz: number;
	readonly memberIds: readonly string[];
	readonly spans: readonly BlockSpan[];
}

/**
 * 区间在图上该显示成哪种状态。
 *
 * <ul>
 *   <li>{@code occupied} —— 里面有车（**优先级最高**：有车就是有车）；</li>
 *   <li>{@code singleYellow} / {@code doubleYellow} —— 入口灯单黄 / 双黄（用户点名的两档）；</li>
 *   <li>{@code red} —— 入口灯红但区间空着（前方占用导致的，实测 13 条）；</li>
 *   <li>{@code clear} —— 绿灯且空着（实测 67 条）。</li>
 * </ul>
 *
 * <p>灯位没给（空串）时算 {@code clear}：那情形是"接口没给结论"，与"绿灯"同色至少不会吓人；
 * 实测当前世界没有这种区间。</p>
 */
export type BlockSectionState = "occupied" | "singleYellow" | "doubleYellow" | "red" | "clear";

/** 状态判据（见 {@link BlockSectionState} 的优先级）。 */
export function sectionState(section: BlockSection): BlockSectionState {
	if (section.occupied) {
		return "occupied";
	}
	switch (section.aspect) {
		case "SINGLE_YELLOW":
			return "singleYellow";
		case "DOUBLE_YELLOW":
			return "doubleYellow";
		case "RED":
			return "red";
		default:
			return "clear";
	}
}

/** 解析接口给的一批行车区间。 */
export function parseBlockSections(raw: readonly RawBlockSection[]): BlockSection[] {
	return raw.map(section => ({
		id: section.id,
		entrySignal: section.entrySignal ?? "",
		exitSignal: section.exitSignal ?? "",
		nextId: section.next ?? "",
		aspect: section.aspect ?? "",
		occupied: section.occupied === true,
		lengthM: section.length ?? 0,
		uncovered: section.uncovered === true,
		directionLabel: section.direction?.label ?? "",
		dx: section.direction?.dx ?? 0,
		dz: section.direction?.dz ?? 0,
		memberIds: section.members ?? [],
		spans: (section.spans ?? []).map(span => ({
			railHex: span.hex,
			fromM: span.from,
			toM: span.to,
			alongDirection: span.dirOfTravel !== false,
			reachable: span.reachable !== false,
			points: parseFlatPoints(span.points),
		})),
	}));
}

/**
 * 一个区间的"带"该往哪边让：**垂直于它的行车方向**的单位向量。
 *
 * <p>为什么必须让：同一条轨上南行与北行各是一条区间，不让开就完全重叠、后画的把先画的盖掉，
 * 看上去只有一种颜色。两边的 `direction` 相反 ⇒ 垂直向量也相反 ⇒ 各自落在轨道两侧（实测
 * 南行 `dx=0,dz=1` → 让到 (−1,0)、北行 `dz=−1` → 让到 (1,0)）。</p>
 *
 * <p>画布坐标的 y 就是世界 z（不翻转），屏幕 y 向下，所以"行进方向的右手侧" = 把方向向量
 * 顺时针转 90° = `(−dz, dx)`。</p>
 */
export function bandOffsetDirection(section: BlockSection): readonly [number, number] {
	const length = Math.hypot(section.dx, section.dz);
	if (length === 0) {
		return [0, 0];
	}
	// 归一掉 `-0`：`-0` 与 `0` 数值相同、渲染也相同，但它会让"逐位相等"的断言与日志看着别扭。
	const offsetX = -section.dz / length;
	const offsetY = section.dx / length;
	return [offsetX === 0 ? 0 : offsetX, offsetY === 0 ? 0 : offsetY];
}
