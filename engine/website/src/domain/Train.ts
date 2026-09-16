/*
 * **车辆**（`/mtr/api/map/mmtr-trains`）的数据形状 —— 地图页只取"它在哪儿"这一半。
 *
 * <h2>位置为什么不用坐标，而用"在轨上的弧长"</h2>
 * <p>引擎也发 `headX/headZ`（世界坐标，采样那一套），但「地图」页画轨时会把整条 path 对齐到轨的两个
 * **端点**上（见 `railAlignment.ts` 文件头）—— 直接拿 headX/headZ 落点，车会偏半格。
 * 所以这一层用引擎给的 {@code railHex + railArcM + railArcLengthM}，在**那一页已经画出来的折线**上
 * 按比例取点，曲线轨也对得上。用户 2026-09-16 的要求原文：「读取车辆位置，在地图页显示」。</p>
 *
 * <h2>朝向</h2>
 * <p>{@code arcIncreasing} 说清"行车方向与那条轨的弧增方向是否一致" —— 箭头朝向靠它定，
 * 不然会有一半的车头画反。</p>
 */
import {railHexKey} from "./MapNode.ts";

/** 引擎原始的一节车（`mmtr-trains` 的 `cars[]`；2026-09-16 新增，按用户要求"按节画不同形状"）。 */
export interface RawTrainCar {
	readonly index?: number;
	readonly railHex?: string;
	readonly railArcM?: number;
	readonly railArcLengthM?: number;
	/** 这节车的车头是否指向**弧增**方向（箭头朝哪边靠它）。 */
	readonly forward?: boolean;
	readonly lengthM?: number;
	readonly stockId?: string;
	/** 有没有动力（引擎 `VehicleCar.mmtrPowered`）。 */
	readonly powered?: boolean;
	readonly capacity?: number;
}

/** 一节车（界面用的形状）。 */
export interface TrainCar {
	readonly index: number;
	readonly railHex: string;
	readonly railKey: string;
	readonly arcM: number;
	readonly arcLengthM: number;
	readonly forward: boolean;
	readonly lengthM: number;
	readonly stockId: string;
	readonly powered: boolean;
	readonly capacity: number;
}

/** 引擎原始的一条车辆记录（只挑地图页要用的字段）。 */
export interface RawTrain {
	readonly vehicleId?: string;
	readonly railHex?: string;
	readonly railArcM?: number;
	readonly railArcLengthM?: number;
	readonly arcIncreasing?: boolean;
	readonly headX?: number;
	readonly headZ?: number;
	readonly speedKmh?: number;
	readonly onRoute?: boolean;
	readonly moving?: boolean;
	readonly doorsOpen?: boolean;
	readonly routeNumber?: string;
	readonly routeName?: string;
	readonly destination?: string;
	readonly nextSection?: string;
	readonly sidingName?: string;
	readonly depotName?: string;
	readonly activeCab?: string;
	readonly cabManned?: boolean;
	/** 任务（`mission.kind` = PASSENGER / FREIGHT / MANEUVER）—— "货车"就靠它认，目标股道也在这里。 */
	readonly mission?: {
		readonly kind?: string;
		readonly state?: string;
		readonly executor?: string;
		readonly startSidingId?: string;
		readonly targetSidingId?: string;
		readonly assignedMillis?: number;
		readonly failureReason?: string;
	};
	/**
	 * **当前进路**（引擎 `MmtrRoute`；没有进路时这个键**不存在**）。
	 *
	 * <p>这就是"路线图"的数据源：`rails[]` 是这条进路要走的**全部轨**（按顺序），
	 * `state` 是 SET / PENDING（联锁到底立没立起来）。</p>
	 */
	readonly route?: {
		readonly kind?: string;
		readonly state?: string;
		readonly entryRail?: string;
		readonly targetRail?: string;
		readonly railCount?: number;
		readonly forkCount?: number;
		readonly rails?: readonly string[];
		readonly stateReason?: string;
	};
	readonly cars?: readonly RawTrainCar[];
}

/** 一辆车（界面用的形状）。 */
export interface Train {
	readonly vehicleId: string;
	/** 它现在站在哪根轨上（规范 hex，与 `/mmtr-topology` 的 rails[].hex 同一写法）；空 = 引擎没说。 */
	readonly railHex: string;
	/** 比对用的规范化键（两端排序）——hex 的写法跟着方向走，见 `railHexKey`。 */
	readonly railKey: string;
	readonly arcM: number;
	readonly arcLengthM: number;
	/** 行车方向与弧增方向是否一致（箭头朝向）。 */
	readonly arcIncreasing: boolean;
	readonly head: readonly [number, number];
	readonly speedKmh: number;
	readonly onRoute: boolean;
	readonly moving: boolean;
	readonly doorsOpen: boolean;
	readonly routeNumber: string;
	readonly routeName: string;
	readonly destination: string;
	readonly nextSection: string;
	readonly sidingName: string;
	readonly depotName: string;
	readonly activeCab: string;
	readonly cabManned: boolean;
	/** 任务种类（`PASSENGER` / `FREIGHT` / `MANEUVER`，空 = 引擎没说）。 */
	readonly missionKind: string;
	/** 任务状态（`ASSIGNED` / `DISPATCHED` / `AT_TARGET` / `COMPLETE` / `FAILED` / `CANCELED`）。 */
	readonly missionState: string;
	/** 谁来执行（`AUTOPILOT` / `PLAYER` / `AI`）。 */
	readonly missionExecutor: string;
	/** 任务起点股道 id / **目标股道 id**（要换成名字得配 `domain/Siding.ts` 的股道清单）。 */
	readonly startSidingId: string;
	readonly targetSidingId: string;
	/** 任务失败的原因（只有 `FAILED` 时才有）。 */
	readonly missionFailure: string;
	/** 进路种类（`MAIN` 列车进路 / `SHUNT` 调车进路；空 = 现在没有进路）。 */
	readonly routeKind: string;
	/** 进路状态（`SET` 已建立 / `PENDING` 还没立起来）。 */
	readonly routeState: string;
	/** 进路没立起来的原因（只有 `PENDING` 时引擎才给）。 */
	readonly routeStateReason: string;
	/** 进路的**全部轨**（规范键，与 `/mmtr-topology` 的 rails[].hex 同一套比对方式）—— 路线图叠加层就画这些。 */
	readonly routeRailKeys: readonly string[];
	/** 进路要过几个道岔（引擎报的 `forkCount`）。 */
	readonly routeForkCount: number;
	/** 每节车（引擎没给就是空数组 —— 老引擎只有车头，那一节按"动车"画一个箭头）。 */
	readonly cars: readonly TrainCar[];
}

/** 解析接口给的一批车辆。 */
export function parseTrains(raw: readonly RawTrain[]): Train[] {
	return raw.map(train => {
		const railHex = train.railHex ?? "";
		return {
			vehicleId: train.vehicleId ?? "",
			railHex,
			railKey: railHex === "" ? "" : railHexKey(railHex),
			arcM: train.railArcM ?? 0,
			arcLengthM: train.railArcLengthM ?? 0,
			arcIncreasing: train.arcIncreasing !== false,
			head: [train.headX ?? 0, train.headZ ?? 0] as const,
			speedKmh: train.speedKmh ?? 0,
			onRoute: train.onRoute === true,
			moving: train.moving === true,
			doorsOpen: train.doorsOpen === true,
			routeNumber: train.routeNumber ?? "",
			routeName: train.routeName ?? "",
			destination: train.destination ?? "",
			nextSection: train.nextSection ?? "",
			sidingName: train.sidingName ?? "",
			depotName: train.depotName ?? "",
			activeCab: train.activeCab ?? "",
			cabManned: train.cabManned === true,
			missionKind: train.mission?.kind ?? "",
			missionState: train.mission?.state ?? "",
			missionExecutor: train.mission?.executor ?? "",
			startSidingId: train.mission?.startSidingId ?? "",
			targetSidingId: train.mission?.targetSidingId ?? "",
			missionFailure: train.mission?.failureReason ?? "",
			routeKind: train.route?.kind ?? "",
			routeState: train.route?.state ?? "",
			routeStateReason: train.route?.stateReason ?? "",
			/*
			 * 进路的轨：**规范化键**（两端排序，与 `railHexKey` 同一套）—— 图层的轨也是这么比对的，
			 * 引擎给的 hex 方向可能相反。顺手去重（同一条轨在一次进路里只该出现一次；真有重复也只画一遍）。
			 */
			routeRailKeys: [...new Set((train.route?.rails ?? []).map(railHexKey))].filter(key => key !== ""),
			routeForkCount: train.route?.forkCount ?? 0,
			cars: (train.cars ?? []).map(car => {
				const carRailHex = car.railHex ?? "";
				return {
					index: car.index ?? 0,
					railHex: carRailHex,
					railKey: carRailHex === "" ? "" : railHexKey(carRailHex),
					arcM: car.railArcM ?? 0,
					arcLengthM: car.railArcLengthM ?? 0,
					// 缺省当"弧增"：老引擎的 `cars` 根本没有这个字段，而它也不会有 per-car 数据
					forward: car.forward !== false,
					lengthM: car.lengthM ?? 0,
					stockId: car.stockId ?? "",
					// 缺省当"有动力"：这样"引擎没说是无动力"时画的是箭头，不会凭空多出一堆矩形
					powered: car.powered !== false,
					capacity: car.capacity ?? 0,
				};
			}),
		};
	});
}

/** 这辆车能不能在图上落到轨上（要有轨、要有轨长）。 */
export function canPlaceTrain(train: Train): boolean {
	return train.railHex !== "" && train.arcLengthM > 0;
}

/** 车在轨上的位置比例（0 = 轨的一端、1 = 另一端；按弧长算）。 */
export function trainRailFraction(train: Train): number {
	if (train.arcLengthM <= 0) {
		return 0;
	}
	return Math.max(0, Math.min(1, train.arcM / train.arcLengthM));
}

/** 悬浮提示要写的那一行（速度、任务、车门…）。 */
export function describeTrain(train: Train): string {
	const parts = [`车 ${train.vehicleId}`];
	parts.push(`${Math.round(train.speedKmh * 10) / 10} km/h`);
	parts.push(train.onRoute ? "在运行" : "停着");
	if (train.routeNumber !== "" || train.routeName !== "") {
		parts.push(`线路 ${[train.routeNumber, train.routeName].filter(part => part !== "").join(" ")}`);
	}
	if (train.destination !== "") {
		parts.push(`目的地 ${train.destination}`);
	}
	if (train.doorsOpen) {
		parts.push("车门开着");
	}
	if (train.nextSection !== "") {
		parts.push(`下一区间 ${train.nextSection}`);
	}
	if (train.depotName !== "" || train.sidingName !== "") {
		parts.push(`车场 ${[train.depotName, train.sidingName].filter(part => part !== "").join(" ")}`);
	}
	if (train.cabManned) {
		parts.push(`有人驾驶（${train.activeCab}）`);
	}
	return parts.join(" · ");
}

/**
 * 一节车的悬浮提示：整车那行 + "第几节 / 什么车 / 多长 / 有没有动力 / 载客"。
 *
 * @param train     整列车（节数与整车信息都从它来）
 * @param car       这一节车
 * @param kindLabel 这一节的记号说法（"动车" / "无动力车厢" / "货车"，由调用方按画出来的形状给）
 */
export function describeCar(train: Train, car: TrainCar, kindLabel: string): string {
	const total = train.cars.length > 0 ? train.cars.length : 1;
	const parts = [`第 ${car.index + 1}/${total} 节（${kindLabel}）`];
	if (car.stockId !== "") {
		parts.push(car.stockId);
	}
	if (car.lengthM > 0) {
		parts.push(`${Math.round(car.lengthM * 10) / 10} 米`);
	}
	parts.push(car.powered ? "有动力" : "无动力");
	if (car.capacity > 0) {
		parts.push(`载客 ${car.capacity}`);
	}
	return `${describeTrain(train)} · ${parts.join(" · ")}`;
}

/*
 * ------------------------------------------------------------------ 任务与进路
 *
 * 用户 2026-09-16：「游戏内的列车都是通过任务驱动的，让列车图标能够点击，点击后高亮并提示任务目标
 * 还有路线图叠加层。」—— 下面这几个纯函数就是"点开之后要写什么"的唯一真源（浮层只负责排版）。
 *
 * 三件事分开写，因为它们的**可靠程度不同**：
 *   · `mission.*` 是"这列车被派去干什么"（引擎的任务状态机说了算）；
 *   · `route.rails[]` 是"联锁实际给它排了哪条路"（可能还没立起来 = PENDING）；
 *   · 目标股道要拿 `sidings[]` **换名字**（id 换成人看得懂的车场 + 股道号）。
 */

/** 任务种类的说法。 */
export function missionKindLabel(kind: string): string {
	switch (kind) {
		case "PASSENGER":
			return "客运任务";
		case "FREIGHT":
			return "货运任务";
		case "MANEUVER":
			return "调车任务";
		default:
			return kind === "" ? "没有任务" : kind;
	}
}

/** 任务状态的说法（引擎 `MmtrMission.State`）。 */
export function missionStateLabel(state: string): string {
	switch (state) {
		case "ASSIGNED":
			return "已指派";
		case "DISPATCHED":
			return "已派出";
		case "AT_TARGET":
			return "已到目标";
		case "COMPLETE":
			return "已完成";
		case "FAILED":
			return "失败";
		case "CANCELED":
			return "已取消";
		default:
			return state;
	}
}

/** 谁在执行。 */
export function missionExecutorLabel(executor: string): string {
	switch (executor) {
		case "AUTOPILOT":
			return "自动驾驶";
		case "PLAYER":
			return "玩家驾驶";
		case "AI":
			return "AI";
		default:
			return executor;
	}
}

/** 进路种类的说法（引擎 `MmtrRoute.Kind`）。 */
export function routeKindLabel(kind: string): string {
	switch (kind) {
		case "MAIN":
			return "列车进路";
		case "SHUNT":
			return "调车进路";
		default:
			return kind === "" ? "没有进路" : kind;
	}
}

/** 进路状态的说法：**`SET` 才算立起来了**，`PENDING` 是"申请了还没批"。 */
export function routeStateLabel(state: string): string {
	switch (state) {
		case "SET":
			return "已建立";
		case "PENDING":
			return "等待联锁";
		default:
			return state;
	}
}

/** 一列车的任务一句话（"客运任务 · 已派出 · 自动驾驶"）；没有任务时返回空串。 */
export function describeMission(train: Train): string {
	if (train.missionKind === "") {
		return "";
	}
	const parts = [missionKindLabel(train.missionKind)];
	if (train.missionState !== "") {
		parts.push(missionStateLabel(train.missionState));
	}
	if (train.missionExecutor !== "") {
		parts.push(missionExecutorLabel(train.missionExecutor));
	}
	if (train.missionFailure !== "") {
		parts.push(`原因 ${train.missionFailure}`);
	}
	return parts.join(" · ");
}

/** 这列车现在有没有可画的进路（有轨才算，`PENDING` 也有轨 —— 那正是"联锁还没批"要看的东西）。 */
export function hasRoute(train: Train): boolean {
	return train.routeRailKeys.length > 0;
}
