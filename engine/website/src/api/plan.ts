import {postJson, requestJson} from "./client";

/**
 * 时刻表（P 系列）接口：`/mtr/api/map/mmtr-plan*`。
 *
 * <h3>为什么单独一个文件、而且类型写得这么细</h3>
 * <p>这六个页面是**配置界面**：网页发出去的东西引擎要能原样读回来，引擎算出来的东西网页要能原样画出来。
 * 字段名/单位写错一次，就会变成"存进去是 0"（站台 id 用 hex 那次就是这么错的）或者"时间差 1000 倍"。
 * 所以这里的 interface 是照**引擎实际返回的 JSON 逐字段**写的，构建时 `vue-tsc` 会拿它去校模板里的取数
 * —— 页面里 `row.fromMillis` 拼错一个字母，构建就红。</p>
 *
 * <h3>三件不能忘的约定</h3>
 * <ol>
 *   <li><b>时间一律是"当日毫秒"</b>（07:00 = 25_200_000），不是纪元毫秒，也不带时区；</li>
 *   <li><b>id 一律十进制字符串</b>：64 位整数装不进浏览器的 number，引擎也按十进制字符串解析
 *       （`MmtrPlanIds`）；</li>
 *   <li><b>写进去的输入是"整条覆盖"</b>（upsert）：改线路就是提交整条线路，不是打补丁。</li>
 * </ol>
 */

// ---------------------------------------------------------------- 通用

/** 一次写操作的回复（`planResult`）：问题清单 + 是否可用。 */
export interface PlanWriteResult {
	readonly ok: boolean;
	readonly configured: boolean;
	readonly problems: number;
	readonly errors: readonly string[];
}

export interface PlanAssignment {
	readonly fromConsistId: string;
	/** 空串 = 该编组今天全部趟次。 */
	readonly fromTripId: string;
	readonly toConsistId: string;
}

// ---------------------------------------------------------------- ① 输入层（线路 / 密度 / 车底）

export interface PlanStop {
	/** 站 id（十进制字符串）。 */
	stationId: string;
	/** 去程（1→10）用的站台 id（十进制字符串）。 */
	platformId: string;
	/**
	 * 回程（10→1）用的站台 id（十进制字符串；空/`"0"` = 与去程同一个台）。
	 *
	 * <p>现场每个站有两个站台，回程该走另一侧 —— 站序仍是一份，两个方向各自的台由这一项给出。</p>
	 */
	returnPlatformId?: string;
	readonly dwellMillis: number;
}

export type TerminalTreatment = "CHANGE_ENDS" | "TURNBACK" | "STABLE";

export interface PlanLine {
	lineId: string;
	name: string;
	terminalTreatment: TerminalTreatment;
	loop: boolean;
	/** 出库股道（十进制字符串）。 */
	yardSidingId: string;
	leadTimeMillis: number;
	stops: PlanStop[];
}

export interface PlanSegment {
	/** 密度段起点（当日毫秒，含）。 */
	fromMillis: number;
	/** 密度段终点（当日毫秒，不含）。 */
	toMillis: number;
	headwayMillis: number;
}

export interface PlanPattern {
	lineId: string;
	segments: PlanSegment[];
}

export interface PlanCar {
	vehicleId: string;
	length: number;
	width: number;
	capacity: number;
	powered: boolean;
}

export interface PlanConsist {
	consistId: string;
	maxSpeedKmh: number;
	cars: PlanCar[];
}

export interface PlanFleet {
	vehicleCount: number;
	consists: PlanConsist[];
	spares: PlanConsist[];
}

export interface PlanInputs {
	readonly lines: readonly PlanLine[];
	readonly patterns: readonly PlanPattern[];
	readonly fleet: PlanFleet;
	readonly errors: readonly string[];
	readonly valid: boolean;
	readonly configured: boolean;
	readonly assignments: readonly PlanAssignment[];
}

export function fetchPlan(): Promise<PlanInputs> {
	return requestJson<PlanInputs>("map/mmtr-plan");
}

export function upsertLine(line: PlanLine): Promise<PlanWriteResult> {
	return postJson<PlanWriteResult>("map/mmtr-plan-line-upsert", line);
}

export function deleteLine(lineId: string): Promise<PlanWriteResult & { readonly deleted: boolean }> {
	return postJson<PlanWriteResult & { readonly deleted: boolean }>("map/mmtr-plan-line-delete", {lineId});
}

export function upsertPattern(pattern: PlanPattern): Promise<PlanWriteResult> {
	return postJson<PlanWriteResult>("map/mmtr-plan-pattern-upsert", pattern);
}

export function upsertFleet(fleet: PlanFleet): Promise<PlanWriteResult> {
	return postJson<PlanWriteResult>("map/mmtr-plan-fleet-upsert", fleet);
}

// ---------------------------------------------------------------- ③ 现场可选项（站/台、车场股道）

export interface WorldPlatform {
	readonly id: string;
	readonly hex: string;
	readonly name: string;
	readonly dwellMillis: number;
}

export interface WorldStation {
	readonly id: string;
	readonly hex: string;
	readonly name: string;
	readonly platforms: readonly WorldPlatform[];
}

export interface WorldSiding {
	readonly id: string;
	readonly hex: string;
	readonly name: string;
	readonly vehicles: number;
	readonly railLength: number;
}

export interface WorldDepot {
	readonly id: string;
	readonly hex: string;
	readonly name: string;
	readonly sidings: readonly WorldSiding[];
}

export interface PlanWorld {
	readonly stations: readonly WorldStation[];
	readonly depots: readonly WorldDepot[];
	readonly dayTimeMs: number;
}

export function fetchWorld(): Promise<PlanWorld> {
	return requestJson<PlanWorld>("map/mmtr-plan-world");
}

// ---------------------------------------------------------------- ② 趟次表（乘客视角）

export interface ServiceStopTime {
	readonly stopIndex: number;
	readonly stationId: string;
	readonly platformId: string;
	readonly arrivalMillis: number;
	readonly departureMillis: number;
	readonly dwellMillis: number;
}

export interface ServiceTrip {
	readonly tripId: string;
	readonly sequence: number;
	readonly direction: "OUT" | "BACK";
	readonly departureMillis: number;
	readonly terminalDoneMillis: number;
	readonly durationMillis: number;
	readonly terminalTreatment: string;
	readonly stops: readonly ServiceStopTime[];
}

export interface ServicePlanLine {
	readonly lineId: string;
	readonly name: string;
	readonly terminalTreatment: string;
	readonly loop: boolean;
	readonly ringMillis: number;
	readonly peakHeadwayMillis: number;
	readonly tripCount: number;
	readonly outbound: readonly ServiceTrip[];
	readonly inbound: readonly ServiceTrip[];
}

export interface ServicePlanFeed {
	readonly lines: readonly ServicePlanLine[];
	readonly dayTimeMs: number;
	readonly configured: boolean;
	readonly problems: number;
}

export function fetchServicePlan(): Promise<ServicePlanFeed> {
	return requestJson<ServicePlanFeed>("map/mmtr-plan-service-plan");
}

// ---------------------------------------------------------------- ⑤ 交路（计划 vs 实际）

export type EntryPhase = "PAST" | "NOW" | "FUTURE";

export interface TimelineEntry {
	readonly kind: "DEPART_YARD" | "TRIP" | "STABLE_YARD";
	readonly startMillis: number;
	readonly endMillis: number;
	readonly waitBeforeMillis: number;
	readonly phase: EntryPhase;
	readonly tripId?: string;
	readonly direction?: "OUT" | "BACK";
	readonly stopCount?: number;
	readonly stationId?: string;
	readonly platformId?: string;
	readonly sidingId?: string;
}

export interface TimelineStep {
	readonly taskId: string;
	readonly kind: string;
	readonly dueMs: number;
	readonly earliestMs: number;
	readonly targetRef: string;
	readonly describe: string;
	readonly dispatched: boolean;
	readonly awaiting: boolean;
}

export interface WorkingTimeline {
	readonly consistId: string;
	readonly vehicleId: string;
	readonly dispatchedSteps: number;
	readonly awaitingTaskId: string;
	readonly stepCount: number;
	readonly entries: readonly TimelineEntry[];
	readonly steps: readonly TimelineStep[];
}

export interface DiagramWorking {
	readonly consistId: string;
	readonly vehicleId: string;
	readonly steps: number;
	readonly dispatchedSteps: number;
	readonly awaitingTaskId: string;
	readonly playerDriven: boolean;
	readonly idle?: boolean;
	readonly note?: string;
	readonly nextKind?: string;
	readonly nextTarget?: string;
	readonly nextDueMs?: number;
	readonly nextDescribe?: string;
	readonly timeline: WorkingTimeline;
}

export interface DiagramLine {
	readonly lineId: string;
	readonly ringMillis: number;
	readonly peakHeadwayMillis: number;
	readonly requiredConsists: number;
	readonly yardSidingId: string;
	readonly dispatchedTotal: number;
	readonly retryCount: number;
	readonly skippedSteps: number;
	readonly complete: boolean;
	readonly capacityProblem?: string;
	readonly workings: readonly DiagramWorking[];
}

export interface DiagramFeed {
	readonly lines: readonly DiagramLine[];
	readonly configured: boolean;
	readonly problems: number;
}

export function fetchDiagrams(): Promise<DiagramFeed> {
	return requestJson<DiagramFeed>("map/mmtr-plan-diagrams");
}

// ---------------------------------------------------------------- ④ 事件

export type EventKind = "PEAK_SURGE" | "DELAY" | "FAULT" | "SPEED_RESTRICTION";

export interface PlanEventView {
	readonly eventId: string;
	readonly kind: EventKind;
	readonly kindName: string;
	readonly targetKind: string;
	readonly targetName: string;
	readonly startMillis: number;
	readonly endMillis: number;
	readonly state: string;
	readonly remaining: string;
	readonly reason: string;
	readonly describe: string;
}

export interface EventFeed {
	readonly dayTimeMs: number;
	readonly feed: readonly string[];
	readonly events: readonly PlanEventView[];
}

export function fetchEvents(): Promise<EventFeed> {
	return requestJson<EventFeed>("map/mmtr-plan-events");
}

/** 一条事件的提交体（`kind` 决定哪些字段有意义，见 servlet 的 `readPlanEvent`）。 */
export interface EventUpsert {
	eventId: string;
	kind: EventKind;
	startMillis: number;
	endMillis?: number;
	reason?: string;
	/** PEAK_SURGE：加密到哪个间隔。 */
	headwayMillis?: number;
	stationId?: string;
	/** DELAY / FAULT：哪一组车底。 */
	consistId?: string;
	delayMillis?: number;
	strategy?: number;
	/** SPEED_RESTRICTION：哪一段轨（hex）。 */
	railHex?: string;
	speedKmh?: number;
	vehicleDown?: boolean;
}

export function upsertEvent(event: EventUpsert): Promise<PlanWriteResult> {
	return postJson<PlanWriteResult>("map/mmtr-plan-event-upsert", event);
}

export function deleteEvent(eventId: string, end = false): Promise<PlanWriteResult & { readonly removed: boolean }> {
	return postJson<PlanWriteResult & { readonly removed: boolean }>("map/mmtr-plan-event-delete", {eventId, end});
}

// ---------------------------------------------------------------- ⑥ 指派 / 接管 / 重排

export function assignTrips(fromConsistId: string, fromTripId: string, toConsistId: string): Promise<{
	readonly ok: boolean;
	readonly message: string;
}> {
	return postJson("map/mmtr-plan-assign", {fromConsistId, fromTripId, toConsistId});
}

export function setTakeover(consistId: string, player: boolean): Promise<{
	readonly ok: boolean;
	readonly changed: boolean;
	readonly player: boolean;
	readonly message: string;
}> {
	return postJson("map/mmtr-plan-takeover", {consistId, player});
}

export function replan(): Promise<{
	readonly ok: boolean;
	readonly rebuiltLines: number;
	readonly configured: boolean;
	readonly problems: number;
	readonly message: string;
}> {
	return postJson("map/mmtr-plan-replan", {});
}

// ---------------------------------------------------------------- 时间格式化（当日毫秒 ↔ HH:MM）

/*
 * 换算本身放在 `planTime.ts`（一个 import 都没有的纯函数文件）并从**这里转出去**：
 * 页面只认 `../api/plan`，而那条换算能被 Node 直接跑测试（`npm run test:plan`）。
 * 这不是为了"多一个文件"，是因为它是前后端之间最容易错、又最不容易看出来的一条口径。
 */
export {durationText, hhmm, hhmmss, parseHhmm} from "./planTime";
