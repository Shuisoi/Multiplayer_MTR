/*
 * **股道**（车辆段里那一条停车的道）—— 界面只需要它的名字。
 *
 * <h2>为什么要解析它</h2>
 * <p>车辆的任务（`mission.kind/state`）里，目标写的是**股道 id**（`targetSidingId`）、起点也是 id
 * （`startSidingId`）。要提示"任务目标"就得把 id 换成看得懂的名字 —— 而股道清单就在**同一份
 * `/mmtr-trains` 响应**里（`sidings[]`，引擎 2026-09-16 之前就有），所以**不用多发一个请求**。</p>
 *
 * <p>名字分两段：`depotName`（车辆段/车场）与 `sidingName`（股道号）。两个都可能为空
 * （dev 世界里有 4 个车场、16 条股道，也有 `depotName` 为空的股道），所以 {@link sidingLabel}
 * 按"有什么写什么"拼，而不是假定两段都在。</p>
 */

/** 引擎原始的一条股道记录。 */
export interface RawSiding {
	readonly sidingId?: string;
	readonly sidingName?: string;
	readonly depotName?: string;
	readonly manual?: boolean;
	readonly vehiclesTotal?: number;
	readonly vehiclesParked?: number;
}

/** 一条股道（界面用的形状）。 */
export interface Siding {
	readonly id: string;
	readonly name: string;
	readonly depotName: string;
	readonly manual: boolean;
	readonly vehiclesTotal: number;
	readonly vehiclesParked: number;
}

/** 解析接口给的一批股道。 */
export function parseSidings(raw: readonly RawSiding[]): Siding[] {
	return raw.map(siding => ({
		id: siding.sidingId ?? "",
		name: siding.sidingName ?? "",
		depotName: siding.depotName ?? "",
		manual: siding.manual === true,
		vehiclesTotal: siding.vehiclesTotal ?? 0,
		vehiclesParked: siding.vehiclesParked ?? 0,
	}));
}

/** 股道 id → 那条股道（提示"任务目标"要用）。找不到返回 null（id 可能是很久以前的记录）。 */
export function sidingById(sidings: readonly Siding[], id: string): Siding | null {
	if (id === "") {
		return null;
	}
	return sidings.find(siding => siding.id === id) ?? null;
}

/**
 * 股道在提示里怎么写：`车场 aassdd · 股道 1`。
 *
 * <p>两段都可能缺（`depotName` 为空 = 这条股道不属于某个车场），所以只写有的那部分；
 * 两段都没有就退回 id —— **宁可难看也不要写"未知"**：id 至少能拿去对数据。</p>
 */
export function sidingLabel(siding: Siding): string {
	const parts: string[] = [];
	if (siding.depotName !== "") {
		parts.push(`车场 ${siding.depotName}`);
	}
	if (siding.name !== "") {
		parts.push(`股道 ${siding.name}`);
	}
	return parts.length === 0 ? `股道 ${siding.id}` : parts.join(" · ");
}
