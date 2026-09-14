<script setup lang="ts">
/*
 * 时刻表六页（P6 ⑤）：线路 / 密度 / 车底 / 事件 / 交路 / 指派。
 *
 * 三件事决定了这个文件的样子：
 *
 *  1. **全是"读出来 → 改 → 整条写回去"**。引擎的输入层是 upsert（整条覆盖），没有补丁语义；
 *     所以选中一条线路会把它的**副本**放进草稿，编辑只动草稿，按保存才写。中途取消不会污染现场。
 *  2. **时间只跟人打交道时才是 "HH:MM"**。接口里一律"当日毫秒"，界面上一律 hh:mm，转换只发生在
 *     提交与显示两处（`parseHhmm` / `hhmm`）—— 不把毫秒暴露给人，也不把字符串塞进引擎。
 *  3. **判断都在引擎**。这一页不重算趟次、不算 N、不猜"这趟该不该跳"：趟次表来自
 *     `mmtr-plan-service-plan`，交路三态来自 `mmtr-plan-diagrams` 的 timeline，
 *     "够不够跑"来自引擎给的 `capacityProblem`。网页只负责把引擎说的话摆出来。
 */
import {computed, h, onMounted, ref} from "vue";
import {
	NAlert, NButton, NCard, NDataTable, NDivider, NEmpty, NForm, NFormItem, NInput, NInputNumber,
	NSelect, NSpace, NSpin, NSwitch, NTabPane, NTabs, NTag, useMessage
} from "naive-ui";
import type {DataTableColumns} from "naive-ui";
import {
	assignTrips, deleteEvent, deleteLine, durationText, fetchDiagrams, fetchEvents, fetchPlan,
	fetchServicePlan, fetchWorld, hhmm, hhmmss, parseHhmm, replan, setTakeover, upsertEvent,
	upsertFleet, upsertLine, upsertPattern
} from "../api/plan";
import type {
	DiagramFeed, EventFeed, EventKind, PlanFleet, PlanInputs, PlanLine, PlanWorld,
	PlanSegment, ServicePlanFeed, ServiceTrip, TerminalTreatment, TimelineStep
} from "../api/plan";

const message = useMessage();

// ---------------------------------------------------------------- 现场数据

const loading = ref(false);
const plan = ref<PlanInputs | null>(null);
const world = ref<PlanWorld | null>(null);
const servicePlan = ref<ServicePlanFeed | null>(null);
const diagrams = ref<DiagramFeed | null>(null);
const events = ref<EventFeed | null>(null);
const nowMillis = ref(0);

const configured = computed(() => plan.value?.configured === true);
const problems = computed(() => (plan.value?.errors ?? []).length);

async function refresh(): Promise<void> {
	loading.value = true;
	try {
		const [nextPlan, nextWorld, nextServicePlan, nextDiagrams, nextEvents] = await Promise.all([
			fetchPlan(), fetchWorld(), fetchServicePlan(), fetchDiagrams(), fetchEvents()
		]);
		plan.value = nextPlan;
		world.value = nextWorld;
		servicePlan.value = nextServicePlan;
		diagrams.value = nextDiagrams;
		events.value = nextEvents;
		nowMillis.value = nextEvents.dayTimeMs;
	} catch (error) {
		message.error(`取数失败：${(error as Error).message}`);
	} finally {
		loading.value = false;
	}
}

async function write(action: () => Promise<{ ok: boolean; errors: readonly string[] }>, what: string): Promise<boolean> {
	try {
		const result = await action();
		if (result.ok && result.errors.length === 0) {
			message.success(`${what}：已保存`);
		} else if (result.errors.length > 0) {
			message.warning(`${what}：存进去了，但引擎报了 ${result.errors.length} 处问题`);
		} else {
			message.warning(`${what}：引擎没接受`);
		}
		await refresh();
		return result.ok;
	} catch (error) {
		message.error(`${what}失败：${(error as Error).message}`);
		return false;
	}
}

onMounted(refresh);

// ---------------------------------------------------------------- 现场可选项（下拉框）

const stationOptions = computed(() => (world.value?.stations ?? []).map(station => ({
	label: `${station.name || "（无名）"}  #${station.id}`,
	value: station.id
})));

function platformOptions(stationId: string) {
	const station = (world.value?.stations ?? []).find(candidate => candidate.id === stationId);
	return (station?.platforms ?? []).map(platform => ({
		label: `${platform.name || "站台"}  #${platform.id}（默认停 ${Math.round(platform.dwellMillis / 1000)} 秒）`,
		value: platform.id
	}));
}

const sidingOptions = computed(() => (world.value?.depots ?? []).flatMap(depot =>
	depot.sidings.map(siding => ({
		label: `${depot.name || "车场"}/${siding.name || "股道"}  #${siding.id}（现停 ${siding.vehicles} 台）`,
		value: siding.id
	}))));

const lineOptions = computed(() => (plan.value?.lines ?? []).map(line => ({
	label: `${line.name || line.lineId}（${line.lineId}）`,
	value: line.lineId
})));

const consistOptions = computed(() => (plan.value?.fleet.consists ?? []).map(consist => ({
	label: `${consist.consistId}（${consist.cars.length} 节）`,
	value: consist.consistId
})));

const tripOptions = computed(() => {
	const lines = servicePlan.value?.lines ?? [];
	return lines.flatMap(line => [...line.outbound, ...line.inbound].map(trip => ({
		label: `${trip.tripId}  ${hhmm(trip.departureMillis)}  ${trip.direction === "OUT" ? "去程" : "回程"}`,
		value: trip.tripId
	})));
});

// ---------------------------------------------------------------- ① 线路

interface StopDraft {
	stationId: string;
	platformId: string;
	dwellSeconds: number | null;
}

interface LineDraft {
	lineId: string;
	name: string;
	terminalTreatment: TerminalTreatment;
	loop: boolean;
	yardSidingId: string;
	leadMinutes: number | null;
	stops: StopDraft[];
}

const lineDraft = ref<LineDraft>({
	lineId: "",
	name: "",
	terminalTreatment: "CHANGE_ENDS",
	loop: false,
	yardSidingId: "",
	leadMinutes: 5,
	stops: []
});
/** 草稿有没有东西可编辑（`false` = 还没选线路，表单显示"左边选一条"而不是一条空表单）。 */
const hasLineDraft = ref(false);
const lineDraftIsNew = ref(false);

function editLine(line: PlanLine): void {
	lineDraftIsNew.value = false;
	hasLineDraft.value = true;
	lineDraft.value = {
		lineId: line.lineId,
		name: line.name,
		terminalTreatment: line.terminalTreatment,
		loop: line.loop,
		yardSidingId: line.yardSidingId,
		leadMinutes: Math.round(line.leadTimeMillis / 60000),
		stops: line.stops.map(stop => ({
			stationId: stop.stationId,
			platformId: stop.platformId,
			dwellSeconds: Math.round(stop.dwellMillis / 1000)
		}))
	};
}

function newLine(): void {
	lineDraftIsNew.value = true;
	hasLineDraft.value = true;
	lineDraft.value = {
		lineId: "",
		name: "",
		terminalTreatment: "CHANGE_ENDS",
		loop: false,
		yardSidingId: "",
		leadMinutes: 5,
		stops: [{stationId: "", platformId: "", dwellSeconds: 30}, {stationId: "", platformId: "", dwellSeconds: 30}]
	};
}

function addStop(): void {
	lineDraft.value.stops.push({stationId: "", platformId: "", dwellSeconds: 30});
}

function removeStop(index: number): void {
	lineDraft.value.stops.splice(index, 1);
}

/** 换站要顺带把站台清掉：留着上一个站的台 id 就是"站/台不匹配"，引擎那边只能报错。 */
function onStationChange(stop: StopDraft): void {
	stop.platformId = "";
}

async function saveLine(): Promise<void> {
	const draft = lineDraft.value;
	if (draft.lineId.trim() === "") {
		message.error("线路代码不能为空（密度表、事件、指派都用它认线路）");
		return;
	}
	if (draft.yardSidingId === "") {
		message.error("要选一个出库股道：没有它，交路的第一段（出库）生成不出来");
		return;
	}
	const bad = draft.stops.find(stop => stop.stationId === "" || stop.platformId === "");
	if (bad != null) {
		message.error("每一站都要选站与站台（引擎用台 id 去轨图上找轨道）");
		return;
	}
	const saved = await write(() => upsertLine({
		lineId: draft.lineId.trim(),
		name: draft.name,
		terminalTreatment: draft.terminalTreatment,
		loop: draft.loop,
		yardSidingId: draft.yardSidingId,
		leadTimeMillis: (draft.leadMinutes ?? 0) * 60000,
		stops: draft.stops.map(stop => ({
			stationId: stop.stationId,
			platformId: stop.platformId,
			dwellMillis: (stop.dwellSeconds ?? 0) * 1000
		}))
	}), `线路 ${draft.lineId}`);
	if (saved) {
		lineDraftIsNew.value = false;
	}
}

async function removeLine(lineId: string): Promise<void> {
	await write(() => deleteLine(lineId), `删除线路 ${lineId}`);
	if (lineDraft.value.lineId === lineId) {
		hasLineDraft.value = false;
	}
}

const treatmentOptions = [
	{label: "换端（CHANGE_ENDS）—— 车头车尾对调后往回开", value: "CHANGE_ENDS"},
	{label: "折返（TURNBACK）—— 尽头折返", value: "TURNBACK"},
	{label: "原地停留（STABLE）—— 不折返", value: "STABLE"}
];

// ---------------------------------------------------------------- ② 密度

interface SegmentDraft {
	from: string;
	to: string;
	headwayMinutes: number | null;
}

const patternLineId = ref("");
const segmentDrafts = ref<SegmentDraft[]>([]);

function loadPattern(lineId: string): void {
	patternLineId.value = lineId;
	const pattern = (plan.value?.patterns ?? []).find(candidate => candidate.lineId === lineId);
	segmentDrafts.value = (pattern?.segments ?? []).map(segment => ({
		from: hhmm(segment.fromMillis),
		to: hhmm(segment.toMillis),
		headwayMinutes: Math.round(segment.headwayMillis / 60000)
	}));
}

function addSegment(): void {
	segmentDrafts.value.push({from: "07:00", to: "09:00", headwayMinutes: 5});
}

function removeSegment(index: number): void {
	segmentDrafts.value.splice(index, 1);
}

async function savePattern(): Promise<void> {
	const segments: PlanSegment[] = [];
	for (const draft of segmentDrafts.value) {
		const from = parseHhmm(draft.from);
		const to = parseHhmm(draft.to);
		if (from == null || to == null) {
			message.error(`时间要写成 HH:MM（现在填的是「${draft.from} – ${draft.to}」）`);
			return;
		}
		segments.push({fromMillis: from, toMillis: to, headwayMillis: (draft.headwayMinutes ?? 0) * 60000});
	}
	if (segments.length === 0) {
		message.error("至少要有一段密度（否则这条线哪天都不发车）");
		return;
	}
	await write(() => upsertPattern({lineId: patternLineId.value, segments}), `密度表 ${patternLineId.value}`);
}

/** 24 小时尺子上的位置（百分比）。 */
function positionOf(dayTimeMillis: number): number {
	return Math.min(100, Math.max(0, (dayTimeMillis / 86400000) * 100));
}

const servicePlanOfSelected = computed(() =>
	(servicePlan.value?.lines ?? []).find(line => line.lineId === patternLineId.value) ?? null);

/** 趟次表的列（写在脚本里而不是模板里：模板里的 render 回调拿不到类型，容易悄悄变成 any）。 */
const tripColumns: DataTableColumns<ServiceTrip> = [
	{title: "趟次", key: "tripId"},
	{title: "方向", key: "direction", render: row => row.direction === "OUT" ? "去程" : "回程"},
	{title: "发车", key: "departureMillis", render: row => hhmm(row.departureMillis)},
	{title: "终点处理完", key: "terminalDoneMillis", render: row => hhmm(row.terminalDoneMillis)},
	{title: "时长", key: "durationMillis", render: row => durationText(row.durationMillis)}
];

const tripRows = computed(() => {
	const line = servicePlanOfSelected.value;
	return line == null ? [] : [...line.outbound, ...line.inbound];
});

// ---------------------------------------------------------------- ③ 车底

interface ConsistDraft {
	consistId: string;
	maxSpeedKmh: number | null;
	carCount: number | null;
}

const vehicleCount = ref<number | null>(0);
const consistDrafts = ref<ConsistDraft[]>([]);
const spareDrafts = ref<ConsistDraft[]>([]);

const defaultCar = {vehicleId: "saf101", length: 16, width: 5, capacity: 400, powered: true};

function loadFleet(fleet: PlanFleet): void {
	vehicleCount.value = fleet.vehicleCount;
	consistDrafts.value = fleet.consists.map(consist => ({
		consistId: consist.consistId,
		maxSpeedKmh: consist.maxSpeedKmh,
		carCount: consist.cars.length
	}));
	spareDrafts.value = fleet.spares.map(spare => ({
		consistId: spare.consistId,
		maxSpeedKmh: spare.maxSpeedKmh,
		carCount: spare.cars.length
	}));
}

/** 从引擎重读车底草稿（"撤销这一页的改动"）。 */
function reloadFleet(): void {
	if (plan.value != null) {
		loadFleet(plan.value.fleet);
	}
}

function loadPatternFromSelect(value: string | number | null): void {
	loadPattern(String(value ?? ""));
}

async function saveFleet(): Promise<void> {
	const toConsist = (draft: ConsistDraft) => ({
		consistId: draft.consistId,
		maxSpeedKmh: draft.maxSpeedKmh ?? 80,
		cars: Array.from({length: Math.max(1, draft.carCount ?? 1)}, () => ({...defaultCar}))
	});
	await write(() => upsertFleet({
		vehicleCount: vehicleCount.value ?? 0,
		consists: consistDrafts.value.filter(draft => draft.consistId.trim() !== "").map(toConsist),
		spares: spareDrafts.value.filter(draft => draft.consistId.trim() !== "").map(toConsist)
	}), "车底");
}

/** "所需 N vs 实际"：N 由引擎算（`requiredConsists`），这里只负责把它和配了几个摆在一起。 */
interface CapacityRow {
	lineId: string;
	required: number;
	configured: number;
	spares: number;
	ringMillis: number;
	peakHeadwayMillis: number;
	problem: string;
}

const capacityRows = computed<CapacityRow[]>(() => (diagrams.value?.lines ?? []).map(line => ({
	lineId: line.lineId,
	required: line.requiredConsists,
	configured: (plan.value?.fleet.consists ?? []).length,
	spares: (plan.value?.fleet.spares ?? []).length,
	ringMillis: line.ringMillis,
	peakHeadwayMillis: line.peakHeadwayMillis,
	problem: line.capacityProblem ?? ""
})));

const capacityColumns: DataTableColumns<CapacityRow> = [
	{title: "线路", key: "lineId"},
	{title: "周转", key: "ringMillis", render: row => durationText(row.ringMillis)},
	{title: "高峰间隔", key: "peakHeadwayMillis", render: row => durationText(row.peakHeadwayMillis)},
	{title: "所需 N", key: "required"},
	{title: "已配编组", key: "configured"},
	{title: "替补", key: "spares"},
	{title: "引擎的判词", key: "problem", render: row => row.problem === "" ? "够跑" : row.problem}
];

// ---------------------------------------------------------------- ④ 事件

const eventKindOptions = [
	{label: "临时高峰（PEAK_SURGE）", value: "PEAK_SURGE"},
	{label: "晚点（DELAY）", value: "DELAY"},
	{label: "故障下线（FAULT）", value: "FAULT"},
	{label: "限速（SPEED_RESTRICTION）", value: "SPEED_RESTRICTION"}
];

interface EventDraft {
	eventId: string;
	kind: EventKind;
	start: string;
	end: string;
	reason: string;
	headwayMinutes: number | null;
	consistId: string;
	delayMinutes: number | null;
	strategy: number;
	railHex: string;
	speedKmh: number | null;
}

const eventDraft = ref<EventDraft>({
	eventId: "",
	kind: "PEAK_SURGE",
	start: "08:00",
	end: "09:00",
	reason: "",
	headwayMinutes: 3,
	consistId: "",
	delayMinutes: 5,
	strategy: 0,
	railHex: "",
	speedKmh: 40
});

async function saveEvent(): Promise<void> {
	const draft = eventDraft.value;
	const start = parseHhmm(draft.start);
	if (start == null) {
		message.error("起始时间要写成 HH:MM");
		return;
	}
	const end = parseHhmm(draft.end);
	const eventId = draft.eventId.trim() === "" ? `${draft.kind}-${start}` : draft.eventId.trim();
	const common = {
		eventId,
		kind: draft.kind,
		startMillis: start,
		reason: draft.reason
	};
	const payload = draft.kind === "FAULT"
		? {...common, consistId: draft.consistId, vehicleDown: true}
		: draft.kind === "DELAY"
			? {...common, consistId: draft.consistId, delayMillis: (draft.delayMinutes ?? 0) * 60000, strategy: draft.strategy}
			: draft.kind === "SPEED_RESTRICTION"
				? {...common, endMillis: end ?? undefined, railHex: draft.railHex, speedKmh: draft.speedKmh ?? 40}
				: {...common, endMillis: end ?? undefined, headwayMillis: (draft.headwayMinutes ?? 5) * 60000};
	await write(() => upsertEvent(payload), `事件 ${eventId}`);
}

const eventColumns: DataTableColumns<EventFeed["events"][number]> = [
	{title: "编号", key: "eventId"},
	{title: "类型", key: "kindName", render: row => h(NTag, {size: "small", type: "info"}, {default: () => row.kindName})},
	{title: "目标", key: "targetName"},
	{title: "起", key: "startMillis", render: row => hhmm(row.startMillis)},
	{title: "止", key: "endMillis", render: row => row.endMillis > 0 ? hhmm(row.endMillis) : "—"},
	{title: "状态", key: "state"},
	{title: "剩余", key: "remaining"},
	{title: "理由", key: "reason"},
	{
		title: "操作",
		key: "actions",
		render: row => h(NSpace, {size: 4}, {
			default: () => [
				h(NButton, {
					size: "tiny",
					onClick: () => void write(() => deleteEvent(row.eventId, true), `结束事件 ${row.eventId}`)
				}, {default: () => "立即结束"}),
				h(NButton, {
					size: "tiny",
					quaternary: true,
					onClick: () => void write(() => deleteEvent(row.eventId), `删除事件 ${row.eventId}`)
				}, {default: () => "删除"})
			]
		})
	}
];

// ---------------------------------------------------------------- ⑤ 交路

const diagramLineId = ref("");
const diagramLine = computed(() =>
	(diagrams.value?.lines ?? []).find(line => line.lineId === diagramLineId.value) ?? null);

function workingSpan(timeline: { entries: readonly {startMillis: number; endMillis: number}[] }): number {
	if (timeline.entries.length === 0) {
		return 1;
	}
	const start = Math.min(...timeline.entries.map(entry => entry.startMillis));
	const end = Math.max(...timeline.entries.map(entry => entry.endMillis));
	return Math.max(1, end - start);
}

function entryWidthPercent(timeline: { entries: readonly {startMillis: number; endMillis: number}[] }, entry: {startMillis: number; endMillis: number}): number {
	return Math.max(1, ((entry.endMillis - entry.startMillis) / workingSpan(timeline)) * 100);
}

function stepColumnsFor(): DataTableColumns<TimelineStep> {
	return [
		{title: "#", key: "taskId"},
		{title: "类型", key: "kind"},
		{title: "计划时刻", key: "dueMs", render: row => hhmmss(row.dueMs)},
		{title: "内容", key: "describe"},
		{
			title: "实际",
			key: "state",
			render: row => row.awaiting
				? h(NTag, {size: "small", type: "warning"}, {default: () => "正在等这一步"})
				: row.dispatched
					? h(NTag, {size: "small", type: "success"}, {default: () => "已派出"})
					: "—"
		}
	];
}

async function takeover(consistId: string, player: boolean): Promise<void> {
	try {
		const result = await setTakeover(consistId, player);
		message.info(result.message);
		await refresh();
	} catch (error) {
		message.error(`接管操作失败：${(error as Error).message}`);
	}
}

async function forceReplan(): Promise<void> {
	try {
		const result = await replan();
		message.success(result.message);
		await refresh();
	} catch (error) {
		message.error(`重排失败：${(error as Error).message}`);
	}
}

// ---------------------------------------------------------------- ⑥ 指派

const assignFrom = ref("");
const assignTrip = ref("");
const assignTo = ref("");

const assignmentColumns: DataTableColumns<{fromConsistId: string; fromTripId: string; toConsistId: string; describe: string}> = [
	{title: "原编组", key: "fromConsistId"},
	{title: "范围", key: "describe"},
	{title: "接管编组", key: "toConsistId"}
];

const assignmentRows = computed(() => (plan.value?.assignments ?? []).map(assignment => ({
	...assignment,
	describe: assignment.fromTripId === "" ? "今天全部趟次" : `自 ${assignment.fromTripId} 起`
})));

async function doAssign(): Promise<void> {
	if (assignFrom.value === "" || assignTo.value === "") {
		message.error("要选「从哪一组」和「交给哪一组」");
		return;
	}
	try {
		const result = await assignTrips(assignFrom.value, assignTrip.value, assignTo.value);
		message.success(result.message);
		await refresh();
	} catch (error) {
		message.error(`指派失败：${(error as Error).message}`);
	}
}

/** 指派页的"谁在跑什么"一行。 */
interface WorkingRow {
	lineId: string;
	consistId: string;
	vehicleId: string;
	steps: number;
	dispatchedSteps: number;
	playerDriven: boolean;
	note: string;
	next: string;
}

const workingRows = computed<WorkingRow[]>(() => (diagrams.value?.lines ?? []).flatMap(line =>
	line.workings.map(working => ({
		lineId: line.lineId,
		consistId: working.consistId,
		vehicleId: working.vehicleId,
		steps: working.steps,
		dispatchedSteps: working.dispatchedSteps,
		playerDriven: working.playerDriven,
		note: working.note ?? "",
		next: working.nextDescribe ?? ""
	}))));

const workingColumns: DataTableColumns<WorkingRow> = [
	{title: "线路", key: "lineId"},
	{title: "编组", key: "consistId"},
	{title: "车列", key: "vehicleId"},
	{title: "步数", key: "steps"},
	{title: "已派", key: "dispatchedSteps"},
	{title: "执行者", key: "playerDriven", render: row => row.playerDriven ? "玩家" : "AI"},
	{title: "下一步 / 说明", key: "next", render: row => row.next || row.note}
];
</script>

<template>
	<div class="plan">
		<div class="plan-head">
			<div class="readout">
				<span class="clock">{{ hhmmss(nowMillis) }}</span>
				<span class="dim">当日时刻（引擎口径，07:00 = 25200000）</span>
			</div>
			<div class="readout">
				<NTag :type="configured ? 'success' : 'default'" size="small">
					{{ configured ? "已配置" : "未配置时刻表" }}
				</NTag>
				<NTag v-if="problems > 0" type="error" size="small">{{ problems }} 处问题</NTag>
				<NTag v-else-if="configured" type="info" size="small">校验通过</NTag>
			</div>
			<NSpace>
				<NButton size="small" :loading="loading" @click="refresh">刷新</NButton>
				<NButton size="small" type="primary" ghost @click="forceReplan">重新排班</NButton>
			</NSpace>
		</div>

		<NAlert
			v-if="problems > 0"
			type="warning"
			class="problems"
			:title="`引擎报了 ${problems} 处问题（坏配置不会跑起来）`"
		>
			<div v-for="(error, index) in plan?.errors ?? []" :key="index" class="problem-line">{{ error }}</div>
		</NAlert>

		<NSpin :show="loading">
			<NTabs type="line" animated>
				<!-- ① 线路 -->
				<NTabPane name="lines" tab="线路">
					<div class="split">
						<div class="pane">
							<NCard title="线路" size="small">
								<template #header-extra>
									<NButton size="tiny" @click="newLine">新建</NButton>
								</template>
								<NEmpty v-if="lineOptions.length === 0" description="还没有线路"/>
								<div
									v-for="line in plan?.lines ?? []"
									:key="line.lineId"
									class="row-click"
									:class="{active: hasLineDraft && lineDraft.lineId === line.lineId}"
									@click="editLine(line)"
								>
									<span class="row-title">{{ line.name || "（无名）" }}</span>
									<span class="dim">{{ line.lineId }} · {{ line.stops.length }} 站 ·
										{{ line.loop ? "环线" : "往返" }} · 出库前置 {{ Math.round(line.leadTimeMillis / 60000) }} 分</span>
									<NButton size="tiny" quaternary type="error" @click.stop="removeLine(line.lineId)">删除</NButton>
								</div>
							</NCard>
						</div>
						<div class="pane">
							<NCard size="small" :title="lineDraftIsNew ? '新建线路' : '编辑线路'">
								<NEmpty v-if="!hasLineDraft" description="左边选一条线路（或新建）"/>
								<NForm v-else label-placement="left" label-width="92" size="small">
									<NFormItem label="线路代码">
										<NInput v-model:value="lineDraft.lineId" :disabled="!lineDraftIsNew" placeholder="L1"/>
									</NFormItem>
									<NFormItem label="名称"><NInput v-model:value="lineDraft.name" placeholder="1 号线"/></NFormItem>
									<NFormItem label="终点处理">
										<NSelect v-model:value="lineDraft.terminalTreatment" :options="treatmentOptions"/>
									</NFormItem>
									<NFormItem label="环线"><NSwitch v-model:value="lineDraft.loop"/></NFormItem>
									<NFormItem label="出库股道">
										<NSelect
											v-model:value="lineDraft.yardSidingId"
											:options="sidingOptions"
											placeholder="选车辆段股道"
											filterable
										/>
									</NFormItem>
									<NFormItem label="出库提前量">
										<NInputNumber v-model:value="lineDraft.leadMinutes" :min="0" :max="120">
											<template #suffix>分钟</template>
										</NInputNumber>
									</NFormItem>
									<NDivider title-placement="left">站序（第一站是起点）</NDivider>
									<div v-for="(stop, index) in lineDraft.stops" :key="index" class="stop-row">
										<span class="stop-index">{{ index + 1 }}</span>
										<NSelect
											v-model:value="stop.stationId"
											:options="stationOptions"
											placeholder="站"
											filterable
											class="grow"
											@update:value="onStationChange(stop)"
										/>
										<NSelect
											v-model:value="stop.platformId"
											:options="platformOptions(stop.stationId)"
											placeholder="站台"
											filterable
											class="grow"
										/>
										<NInputNumber v-model:value="stop.dwellSeconds" :min="0" :max="1800" class="dwell">
											<template #suffix>秒</template>
										</NInputNumber>
										<NButton size="tiny" quaternary type="error" @click="removeStop(index)">×</NButton>
									</div>
									<NSpace class="form-actions">
										<NButton size="small" @click="addStop">加一站</NButton>
										<NButton size="small" type="primary" @click="saveLine">保存线路</NButton>
									</NSpace>
								</NForm>
							</NCard>
						</div>
					</div>
				</NTabPane>

				<!-- ② 密度 -->
				<NTabPane name="patterns" tab="密度">
					<NSpace vertical :size="12">
						<NSpace align="center">
							<span class="dim">线路</span>
							<NSelect
								v-model:value="patternLineId"
								:options="lineOptions"
								placeholder="选一条线路"
								style="width: 260px"
								@update:value="loadPatternFromSelect"
							/>
							<NButton size="small" @click="addSegment">加一段</NButton>
							<NButton size="small" type="primary" @click="savePattern">保存密度表</NButton>
						</NSpace>

						<div class="scale">
							<div
								v-for="(draft, index) in segmentDrafts"
								:key="index"
								class="scale-segment"
								:style="{
									left: `${positionOf(parseHhmm(draft.from) ?? 0)}%`,
									width: `${Math.max(0.5, positionOf(parseHhmm(draft.to) ?? 0) - positionOf(parseHhmm(draft.from) ?? 0))}%`
								}"
								:title="`${draft.from}–${draft.to} 每 ${draft.headwayMinutes} 分钟`"
							>
								<span>{{ draft.headwayMinutes }}′</span>
							</div>
							<div class="scale-now" :style="{left: `${positionOf(nowMillis)}%`}" title="现在"/>
							<div class="scale-tick" v-for="hour in [0, 6, 12, 18, 24]" :key="hour" :style="{left: `${(hour / 24) * 100}%`}">
								<span>{{ String(hour % 24).padStart(2, "0") }}</span>
							</div>
						</div>

						<NCard size="small" title="密度段（左闭右开，HH:MM）">
							<NEmpty v-if="segmentDrafts.length === 0" description="这条线还没有密度表：选线路后加一段"/>
							<div v-for="(draft, index) in segmentDrafts" :key="index" class="segment-row">
								<NInput v-model:value="draft.from" placeholder="07:00" style="width: 88px"/>
								<span class="dim">→</span>
								<NInput v-model:value="draft.to" placeholder="09:00" style="width: 88px"/>
								<span class="dim">每</span>
								<NInputNumber v-model:value="draft.headwayMinutes" :min="1" :max="180" style="width: 120px">
									<template #suffix>分钟一班</template>
								</NInputNumber>
								<NButton size="tiny" quaternary type="error" @click="removeSegment(index)">×</NButton>
							</div>
						</NCard>

						<NCard size="small" title="引擎算出来的趟次（乘客视角 · 与车无关）">
							<NEmpty v-if="servicePlanOfSelected == null" description="保存密度表后这里出现趟次"/>
							<div v-else class="readouts">
								<span>周转 <b>{{ durationText(servicePlanOfSelected.ringMillis) }}</b></span>
								<span>高峰间隔 <b>{{ durationText(servicePlanOfSelected.peakHeadwayMillis) }}</b></span>
								<span>共 <b>{{ servicePlanOfSelected.tripCount }}</b> 趟（去程 {{ servicePlanOfSelected.outbound.length }} / 回程 {{ servicePlanOfSelected.inbound.length }}）</span>
								<span v-if="servicePlanOfSelected.outbound.length > 0">
									首班 <b>{{ hhmm(servicePlanOfSelected.outbound[0].departureMillis) }}</b>
								</span>
								<span v-if="servicePlanOfSelected.outbound.length > 0">
									末班 <b>{{ hhmm(servicePlanOfSelected.outbound[servicePlanOfSelected.outbound.length - 1].departureMillis) }}</b>
								</span>
							</div>
							<NDataTable
								v-if="servicePlanOfSelected != null"
								:columns="tripColumns"
								:data="tripRows"
								size="small"
								:max-height="240"
								:pagination="{pageSize: 12}"
							/>
						</NCard>
					</NSpace>
				</NTabPane>

				<!-- ③ 车底 -->
				<NTabPane name="fleet" tab="车底">
					<NSpace vertical :size="12">
						<NCard size="small" title="车底">
							<NForm label-placement="left" label-width="120" size="small">
								<NFormItem label="车辆总数">
									<NInputNumber v-model:value="vehicleCount" :min="0" :max="999"/>
								</NFormItem>
							</NForm>
							<div class="consist-head">
								<span class="grow">编组（上场）</span>
								<NButton size="tiny" @click="consistDrafts.push({consistId: `C${consistDrafts.length + 1}`, maxSpeedKmh: 80, carCount: 1})">
									加一组
								</NButton>
							</div>
							<div v-for="(draft, index) in consistDrafts" :key="index" class="consist-row">
								<NInput v-model:value="draft.consistId" placeholder="C1" style="width: 120px"/>
								<NInputNumber v-model:value="draft.maxSpeedKmh" :min="10" :max="300" style="width: 140px">
									<template #suffix>km/h</template>
								</NInputNumber>
								<NInputNumber v-model:value="draft.carCount" :min="1" :max="12" style="width: 120px">
									<template #suffix>节</template>
								</NInputNumber>
								<NButton size="tiny" quaternary type="error" @click="consistDrafts.splice(index, 1)">×</NButton>
							</div>
							<div class="consist-head">
								<span class="grow">替补（不上场，故障时顶）</span>
								<NButton size="tiny" @click="spareDrafts.push({consistId: `S${spareDrafts.length + 1}`, maxSpeedKmh: 80, carCount: 1})">
									加一组
								</NButton>
							</div>
							<div v-for="(draft, index) in spareDrafts" :key="index" class="consist-row">
								<NInput v-model:value="draft.consistId" placeholder="S1" style="width: 120px"/>
								<NInputNumber v-model:value="draft.maxSpeedKmh" :min="10" :max="300" style="width: 140px">
									<template #suffix>km/h</template>
								</NInputNumber>
								<NInputNumber v-model:value="draft.carCount" :min="1" :max="12" style="width: 120px">
									<template #suffix>节</template>
								</NInputNumber>
								<NButton size="tiny" quaternary type="error" @click="spareDrafts.splice(index, 1)">×</NButton>
							</div>
							<NSpace class="form-actions">
								<NButton size="small" @click="reloadFleet">从引擎重读</NButton>
								<NButton size="small" type="primary" @click="saveFleet">保存车底</NButton>
							</NSpace>
						</NCard>

						<NCard size="small" title="够不够跑（所需 N vs 实际）">
							<NEmpty v-if="capacityRows.length === 0" description="还没排班：先配线路 + 密度 + 车底"/>
							<NDataTable v-else :columns="capacityColumns" :data="capacityRows" size="small"/>
						</NCard>
					</NSpace>
				</NTabPane>

				<!-- ④ 事件 -->
				<NTabPane name="events" tab="事件">
					<NSpace vertical :size="12">
						<NCard size="small" title="生效中 / 即将生效">
							<NEmpty v-if="(events?.feed ?? []).length === 0" description="现在没有事件"/>
							<NAlert
								v-for="(line, index) in events?.feed ?? []"
								:key="index"
								:title="line"
								type="info"
								class="feed-line"
							/>
						</NCard>

						<NCard size="small" title="新增事件">
							<NForm label-placement="left" label-width="92" size="small">
								<NFormItem label="类型">
									<NSelect v-model:value="eventDraft.kind" :options="eventKindOptions" style="width: 240px"/>
								</NFormItem>
								<NFormItem label="编号">
									<NInput v-model:value="eventDraft.eventId" placeholder="留空就按类型+起始时间自动起名" style="width: 320px"/>
								</NFormItem>
								<NFormItem label="起止">
									<NSpace align="center">
										<NInput v-model:value="eventDraft.start" style="width: 88px"/>
										<span class="dim">→</span>
										<NInput v-model:value="eventDraft.end" style="width: 88px"/>
										<span class="dim">（HH:MM；限速/高峰用"止"，晚点/故障只看"起"）</span>
									</NSpace>
								</NFormItem>
								<NFormItem v-if="eventDraft.kind === 'PEAK_SURGE'" label="加密到">
									<NInputNumber v-model:value="eventDraft.headwayMinutes" :min="1" :max="60">
										<template #suffix>分钟一班</template>
									</NInputNumber>
								</NFormItem>
								<NFormItem v-if="eventDraft.kind === 'DELAY' || eventDraft.kind === 'FAULT'" label="编组">
									<NSelect
										v-model:value="eventDraft.consistId"
										:options="consistOptions"
										placeholder="选一组车底"
										style="width: 240px"
									/>
								</NFormItem>
								<NFormItem v-if="eventDraft.kind === 'DELAY'" label="晚点">
									<NSpace align="center">
										<NInputNumber v-model:value="eventDraft.delayMinutes" :min="1" :max="240">
											<template #suffix>分钟</template>
										</NInputNumber>
										<NSelect
											v-model:value="eventDraft.strategy"
											:options="[{label: '保表（换车）', value: 0}, {label: '保车（平移）', value: 1}]"
											style="width: 180px"
										/>
									</NSpace>
								</NFormItem>
								<NFormItem v-if="eventDraft.kind === 'SPEED_RESTRICTION'" label="限速">
									<NSpace align="center">
										<NInput v-model:value="eventDraft.railHex" placeholder="轨道 hex" style="width: 220px"/>
										<NInputNumber v-model:value="eventDraft.speedKmh" :min="5" :max="300">
											<template #suffix>km/h</template>
										</NInputNumber>
									</NSpace>
								</NFormItem>
								<NFormItem label="理由">
									<NInput v-model:value="eventDraft.reason" placeholder="给运营台看的一句话（会出现在事件条上）" style="width: 420px"/>
								</NFormItem>
								<NButton size="small" type="primary" @click="saveEvent">提交事件</NButton>
							</NForm>
						</NCard>

						<NCard size="small" title="全部事件">
							<NEmpty v-if="(events?.events ?? []).length === 0" description="还没有事件"/>
							<NDataTable v-else :columns="eventColumns" :data="[...(events?.events ?? [])]" size="small"/>
						</NCard>
					</NSpace>
				</NTabPane>

				<!-- ⑤ 交路 -->
				<NTabPane name="diagrams" tab="交路">
					<NSpace vertical :size="12">
						<NSpace align="center">
							<span class="dim">线路</span>
							<NSelect
								v-model:value="diagramLineId"
								:options="(diagrams?.lines ?? []).map(line => ({label: line.lineId, value: line.lineId}))"
								placeholder="选一条线路"
								style="width: 240px"
							/>
							<span v-if="diagramLine != null" class="dim">
								ring {{ durationText(diagramLine.ringMillis) }} · N={{ diagramLine.requiredConsists }} ·
								已派 {{ diagramLine.dispatchedTotal }} 步 · 跳过 {{ diagramLine.skippedSteps }} 步 · 重试 {{ diagramLine.retryCount }}
							</span>
						</NSpace>

						<NAlert v-if="diagramLine?.capacityProblem" type="warning" :title="diagramLine.capacityProblem"/>
						<NEmpty v-if="diagramLine == null" description="选一条已排班的线路"/>

						<NCard
							v-for="working in diagramLine?.workings ?? []"
							:key="working.consistId"
							size="small"
							:title="`${working.consistId} · 车列 ${working.vehicleId}`"
						>
							<template #header-extra>
								<NSpace align="center" size="small">
									<NTag size="small" :type="working.playerDriven ? 'warning' : 'default'">
										{{ working.playerDriven ? "玩家在开" : "AI 在开" }}
									</NTag>
									<NButton
										size="tiny"
										@click="takeover(working.consistId, !working.playerDriven)"
									>{{ working.playerDriven ? "归还给 AI" : "玩家接管" }}</NButton>
								</NSpace>
							</template>

							<div v-if="working.idle" class="dim">{{ working.note }}</div>
							<template v-else>
								<div class="working-readouts">
									<span>步数 <b>{{ working.steps }}</b></span>
									<span>已派 <b>{{ working.dispatchedSteps }}</b></span>
									<span v-if="working.awaitingTaskId">正在等 <b>{{ working.awaitingTaskId }}</b></span>
									<span v-if="working.nextDescribe">下一步 {{ hhmm(working.nextDueMs ?? 0) }} <b>{{ working.nextDescribe }}</b></span>
								</div>
								<div class="strip">
									<div
										v-for="(entry, index) in working.timeline.entries"
										:key="index"
										class="strip-entry"
										:class="`phase-${entry.phase.toLowerCase()}`"
										:style="{width: `${entryWidthPercent(working.timeline, entry)}%`}"
										:title="`${entry.kind} ${hhmm(entry.startMillis)}–${hhmm(entry.endMillis)}`"
									>
										<span class="strip-label">
											{{ entry.kind === "TRIP" ? (entry.tripId ?? "趟次") : (entry.kind === "DEPART_YARD" ? "出库" : "回库") }}
											{{ hhmm(entry.startMillis) }}
										</span>
									</div>
								</div>
								<NDataTable
									:columns="stepColumnsFor()"
									:data="[...working.timeline.steps]"
									size="small"
									:max-height="260"
									:pagination="{pageSize: 10}"
								/>
							</template>
						</NCard>
					</NSpace>
				</NTabPane>

				<!-- ⑥ 指派 -->
				<NTabPane name="assign" tab="指派">
					<NSpace vertical :size="12">
						<NCard size="small" title="手工指派（人说了算，排在自动排班之后）">
							<NForm label-placement="left" label-width="92" size="small">
								<NFormItem label="原编组">
									<NSelect v-model:value="assignFrom" :options="consistOptions" placeholder="把谁的趟次搬走" style="width: 240px"/>
								</NFormItem>
								<NFormItem label="从哪一趟">
									<NSelect
										v-model:value="assignTrip"
										:options="[{label: '今天全部趟次', value: ''}, ...tripOptions]"
										placeholder="留空 = 全部"
										filterable
										style="width: 320px"
									/>
								</NFormItem>
								<NFormItem label="交给编组">
									<NSelect v-model:value="assignTo" :options="consistOptions" placeholder="接手的那一组" style="width: 240px"/>
								</NFormItem>
								<NButton size="small" type="primary" @click="doAssign">指派</NButton>
							</NForm>
						</NCard>

						<NCard size="small" title="已登记的手工覆盖">
							<NEmpty v-if="assignmentRows.length === 0" description="还没有手工指派"/>
							<NDataTable v-else :columns="assignmentColumns" :data="assignmentRows" size="small"/>
						</NCard>

						<NCard size="small" title="指派之后谁在跑什么">
							<NEmpty v-if="workingRows.length === 0" description="还没排班"/>
							<NDataTable v-else :columns="workingColumns" :data="workingRows" size="small"/>
						</NCard>
					</NSpace>
				</NTabPane>
			</NTabs>
		</NSpin>
	</div>
</template>

<style scoped>
.plan {
	padding: 16px;
	color: var(--fg);
}

.plan-head {
	display: flex;
	align-items: center;
	gap: 16px;
	margin-bottom: 12px;
}

.readout {
	display: flex;
	align-items: center;
	gap: 8px;
}

.clock {
	font-family: var(--font-value);
	font-size: 20px;
	letter-spacing: 0.04em;
}

.dim {
	color: var(--fg-dim);
	font-size: 12px;
}

.problems {
	margin-bottom: 12px;
}

.problem-line {
	font-size: 12px;
	color: var(--fg-secondary);
}

.split {
	display: flex;
	gap: 12px;
	align-items: flex-start;
}

.split .pane {
	flex: 1 1 0;
	min-width: 0;
}

.row-click {
	display: flex;
	align-items: center;
	gap: 8px;
	padding: 6px 8px;
	border-radius: var(--radius);
	cursor: pointer;
}

.row-click:hover {
	background: var(--panel-hover);
}

.row-click.active {
	background: var(--accent-soft);
}

.row-title {
	font-weight: 600;
}

.stop-row,
.segment-row,
.consist-row {
	display: flex;
	align-items: center;
	gap: 8px;
	margin-bottom: 6px;
}

.stop-index {
	width: 18px;
	color: var(--fg-dim);
	font-family: var(--font-value);
}

.grow {
	flex: 1 1 0;
	min-width: 0;
}

.dwell {
	width: 130px;
}

.form-actions {
	margin-top: 8px;
}

.scale {
	position: relative;
	height: 40px;
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	overflow: hidden;
}

.scale-segment {
	position: absolute;
	top: 4px;
	bottom: 4px;
	background: var(--accent);
	opacity: 0.75;
	border-radius: 2px;
	display: flex;
	align-items: center;
	justify-content: center;
	font-size: 11px;
	color: #fff;
	overflow: hidden;
}

.scale-now {
	position: absolute;
	top: 0;
	bottom: 0;
	width: 1px;
	background: var(--warn);
}

.scale-tick {
	position: absolute;
	top: 0;
	bottom: 0;
	width: 1px;
	background: var(--line);
}

.scale-tick span {
	position: absolute;
	bottom: 2px;
	left: 2px;
	font-size: 10px;
	color: var(--fg-faint);
}

.readouts {
	display: flex;
	flex-wrap: wrap;
	gap: 16px;
	margin-bottom: 8px;
	font-size: 12px;
	color: var(--fg-secondary);
}

.readouts b {
	color: var(--fg);
	font-family: var(--font-value);
}

.consist-head {
	display: flex;
	align-items: center;
	gap: 8px;
	margin: 10px 0 6px;
	color: var(--fg-secondary);
	font-size: 12px;
}

.feed-line {
	margin-bottom: 6px;
}

.working-readouts {
	display: flex;
	flex-wrap: wrap;
	gap: 16px;
	margin-bottom: 8px;
	font-size: 12px;
	color: var(--fg-secondary);
}

.working-readouts b {
	color: var(--fg);
	font-family: var(--font-value);
}

.strip {
	display: flex;
	gap: 2px;
	height: 26px;
	margin-bottom: 10px;
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	padding: 2px;
	overflow: hidden;
}

.strip-entry {
	display: flex;
	align-items: center;
	justify-content: center;
	border-radius: 2px;
	font-size: 10px;
	overflow: hidden;
	white-space: nowrap;
}

/* 三态与派发器的判据同源：灰 = 已过、亮 = 正在这一段、浅 = 还没到 */
.phase-past {
	background: #2a2a2a;
	color: var(--fg-dim);
}

.phase-now {
	background: var(--accent);
	color: #fff;
}

.phase-future {
	background: #12324d;
	color: var(--fg-secondary);
}

.strip-label {
	padding: 0 4px;
	overflow: hidden;
	text-overflow: ellipsis;
}
</style>
