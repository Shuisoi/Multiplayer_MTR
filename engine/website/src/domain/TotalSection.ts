/*
 * **总区间**（`/mtr/api/map/mmtr-total-sections`）的数据形状与"显示状态"。
 *
 * <h2>它解决什么</h2>
 * <p>L2 行车区间是**有方向**的，而灯是**错开**布置的，所以一辆车夹在错开的一段里时
 * **既在上行区间中、也在下行区间中** —— 地图上按 L2 画就是两条带压在同一根轨上，
 * 既重叠、又说不清"这一段现在归谁"。总区间把"位置"和"归属"分开：</p>
 * <ul>
 *   <li>**位置只有一条**：几何就是 L1 轨道区间（相邻两个 L1 段之间必有一盏灯 ⇒ 再想变粗就只能
 *       放弃某个方向的灯当界，那与"切点只由灯产生"冲突 —— 所以总区间不是第三层划分）；</li>
 *   <li>**归属逐方向列出**：{@code covers[]} —— 覆盖这一处的每条行车区间，各带它自己的显示与占用。</li>
 * </ul>
 *
 * <h2>显示状态为什么取"最不利的那一条"</h2>
 * <p>一处位置上可能挂着两个方向的区间（咽喉重叠处更多），各自的红/黄/绿不一定相同。地图上一条带
 * 只有一个颜色，所以取**最不利**：有车 ⇒ 占用；否则按 RED &gt; 单黄 &gt; 双黄 &gt; 绿 取最严的那一档。
 * 这与引擎对"一盏灯多腿"的取法同源（多支取最不利）；"这一处现在能不能随便进"，答案就该是那个
 * 最严的。</p>
 *
 * <p>补出来的**无灯大区间**（没有入口灯）单独一档 {@code unsignalled}：画成灰的，**绝不能当绿灯画**。</p>
 */
import type {BlockSectionState, BlockSpan, RawBlockDirection, RawBlockSpan} from "./BlockSection.ts";
import {parseFlatPoints} from "./TrackSection.ts";

/** 引擎原始的一条 cover（覆盖这一处的某一条行车区间）。 */
export interface RawTotalCover {
	readonly section?: string;
	readonly entrySignal?: string;
	readonly exitSignal?: string;
	readonly next?: string;
	readonly aspect?: string;
	readonly occupied?: boolean;
	readonly uncovered?: boolean;
	readonly length?: number;
	readonly direction?: RawBlockDirection;
}

/** 引擎原始的一条总区间。 */
export interface RawTotalSection {
	readonly id: string;
	readonly length?: number;
	readonly occupied?: boolean;
	/** 覆盖它的**行车方向数**（2 = 上下行都照到这一处）。 */
	readonly directions?: number;
	/** **错开**：两个方向都照到，但两段区间不是同一段路（起止不重合）。 */
	readonly staggered?: boolean;
	readonly covers?: readonly RawTotalCover[];
	readonly spans?: readonly RawBlockSpan[];
}

/** 一条 cover（界面用的形状）。 */
export interface TotalCover {
	readonly sectionId: string;
	readonly entrySignal: string;
	readonly exitSignal: string;
	readonly nextId: string;
	readonly aspect: string;
	readonly occupied: boolean;
	/** 无入口灯 = 补出来的无灯大区间。 */
	readonly uncovered: boolean;
	readonly lengthM: number;
	readonly directionLabel: string;
}

/** 一条总区间（界面用的形状）。 */
export interface TotalSection {
	readonly id: string;
	readonly lengthM: number;
	readonly occupied: boolean;
	readonly directionCount: number;
	readonly staggered: boolean;
	readonly covers: readonly TotalCover[];
	readonly spans: readonly BlockSpan[];
}

/** 总区间在图上该显示成哪种状态：L2 那几档 ＋ {@code unsignalled}（无信号区段）。 */
export type TotalSectionState = BlockSectionState | "unsignalled";

/** 显示档的严重度（数字越大越严）——"取最不利"就是取最大的那个。 */
function aspectSeverity(aspect: string): number {
	switch (aspect) {
		case "RED":
			return 3;
		case "SINGLE_YELLOW":
			return 2;
		case "DOUBLE_YELLOW":
			return 1;
		default:
			return 0;
	}
}

/** 严重度 → 显示档（与 {@link aspectSeverity} 一一对应）。 */
function severityState(severity: number): BlockSectionState {
	switch (severity) {
		case 3:
			return "red";
		case 2:
			return "singleYellow";
		case 1:
			return "doubleYellow";
		default:
			return "clear";
	}
}

/**
 * 一处位置的状态（见文件头的优先级说明）。
 *
 * <p>顺序是"占用 → 无信号 → 最不利的灯位"：占用是**物理事实**（有车就是有车，压过任何灯位）；
 * 两侧都没有灯（补出来的无灯大区间）才是 {@code unsignalled}；只要有一条方向区间是灯守着的，
 * 就按它的灯位画 —— 无灯那一侧不参与比较（"没灯"不是"红灯"）。</p>
 */
export function totalSectionState(total: TotalSection): TotalSectionState {
	if (total.occupied) {
		return "occupied";
	}
	let worst = -1;
	for (const cover of total.covers) {
		if (cover.uncovered) {
			continue;
		}
		worst = Math.max(worst, aspectSeverity(cover.aspect));
	}
	return worst < 0 ? "unsignalled" : severityState(worst);
}

/** 解析接口给的一批总区间。 */
export function parseTotalSections(raw: readonly RawTotalSection[]): TotalSection[] {
	return raw.map(total => ({
		id: total.id,
		lengthM: total.length ?? 0,
		occupied: total.occupied === true,
		directionCount: total.directions ?? 0,
		staggered: total.staggered === true,
		covers: (total.covers ?? []).map(cover => ({
			sectionId: cover.section ?? "",
			entrySignal: cover.entrySignal ?? "",
			exitSignal: cover.exitSignal ?? "",
			nextId: cover.next ?? "",
			aspect: cover.aspect ?? "",
			occupied: cover.occupied === true,
			uncovered: cover.uncovered === true || (cover.entrySignal ?? "") === "",
			lengthM: cover.length ?? 0,
			directionLabel: cover.direction?.label ?? "",
		})),
		spans: (total.spans ?? []).map(span => ({
			railHex: span.hex,
			fromM: span.from,
			toM: span.to,
			// 总区间的几何是 L1（无方向），spans 不带 dirOfTravel/reachable —— 按"顺向、走得到"填
			alongDirection: true,
			reachable: true,
			points: parseFlatPoints(span.points),
		})),
	}));
}
