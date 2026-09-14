/**
 * 道岔（转辙器）实体：节点上的一组可开通腿。
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-points`（见 `SystemMapServlet.getMmtrPoints`）。
 * 引擎已经按**方向感知**的口径枚举好了每个道岔：以 (节点, 盆轨) 为键，腿按
 * 直通 → 左 → 右 → 其它 排序（`MmtrPoint.computeOrderedLegs`），所以界面**不需要**自己
 * 从几何去算"哪条是左哪条是右"——那正是两边各算一遍就会对不上的地方。</p>
 *
 * <p>开通位（`manual`）是引擎侧的持久状态：操作员设定它、走行层按它决定列车走哪条腿。
 * 界面上改它 = 下 `point set` 那条指令，或者走 `mmtr-point-op` 接口。</p>
 */

/** 一条可开通的腿（引擎的原始形状）。 */
export interface RawPointLeg {
	/** 这条腿对应的轨（hex）。 */
	readonly hex: string;
	/** 几何分类：`STRAIGHT` / `LEFT` / `RIGHT` / `OTHER`。 */
	readonly kind?: string;
	/** 这条腿当前是不是**禁止通行**（被道岔位置切断）。物理道岔才有。 */
	readonly prohibited?: boolean;
}

/** 引擎原始道岔数据（改动时要与 `SystemMapServlet.getMmtrPoints` 同步）。 */
export interface RawPoint {
	readonly x: number;
	readonly y: number;
	readonly z: number;
	/** 盆轨（进向轨）的 hex：道岔以 (节点, 盆轨) 为键。 */
	readonly via: string;
	/** 形态：`FORK` / `TEE` / `MULTI` / `PASS_THROUGH` / `DEAD_END`。 */
	readonly form?: string;
	readonly legs?: readonly RawPointLeg[];
	/** 操作员设定的开通位（腿序号）；-1 = 没设过（引擎默认按 0 直通）。 */
	readonly manual?: number;
	readonly locked?: boolean;
	/** 当前持有这组道岔的持有者（进路/任务授权的），空串 = 没人持有。 */
	readonly holder?: string;
	readonly holderLeg?: number;
	readonly queue?: readonly string[];
	/**
	 * **物理道岔**（一处道岔一个位置，用户 2026-09-13 的规格）：只有引擎把这个节点认成单开道岔时才有。
	 * 位置 0 = 正线贯通（岔股禁止通行）；位置 1 = 岔股开放（正线被断开的那一侧禁止通行）。
	 */
	readonly position?: number;
	/** 当前位置下**禁止通行**的那条轨（hex）。 */
	readonly prohibited?: string;
	/** 根部轨：两个位置都连通的那一侧。 */
	readonly stem?: string;
	/** 正线远端（位置 0 时与根部连通）。 */
	readonly far?: string;
	/** 岔股（位置 1 时与根部连通）。 */
	readonly branch?: string;
	/**
	 * **这个节点为什么不是一处单开道岔**（引擎的一行结论）。
	 *
	 * <p>用户 2026-09-14 要求"道岔的呈现要统一"：不是单开道岔的节点不该换一套卡片让人猜，
	 * 而应在同一张卡片上说清原因（例如"四条线交汇 / 三条线在一个点上交汇"）。
	 * 引擎侧与 `point why` 走同一段判定代码。</p>
	 */
	readonly whyNotTurnout?: string;
}

/** 一条腿（界面用的形状）：序号 + 分类 + 轨。 */
export class PointLeg {
	/** 腿序号 —— 这就是下指令时要传的 `branch` 值。 */
	readonly index: number;
	readonly kind: string;
	readonly railHex: string;
	/** 这条腿当前是不是禁止通行（被道岔位置切断）。 */
	readonly prohibited: boolean;

	constructor(index: number, raw: RawPointLeg) {
		this.index = index;
		this.kind = raw.kind ?? "OTHER";
		this.railHex = raw.hex;
		this.prohibited = raw.prohibited ?? false;
	}

	/** 分类的中文说法（界面显示用）。 */
	get kindText(): string {
		switch (this.kind) {
			case "STRAIGHT":
				return "直通";
			case "LEFT":
				return "左";
			case "RIGHT":
				return "右";
			default:
				return "其它";
		}
	}

	/** hex 的前 8 位（界面上够区分，完整值放 title）。 */
	get shortHex(): string {
		return this.railHex.slice(0, 8);
	}
}

export class Point {
	readonly x: number;
	readonly y: number;
	readonly z: number;
	readonly via: string;
	readonly form: string;
	readonly legs: readonly PointLeg[];
	/**
	 * 操作员设定的开通位；-1 = 没设过。
	 *
	 * <p>没设过时引擎按 **0（直通）** 走 —— 所以界面把 `-1` 显示成"未设（按 0 直通）"，
	 * 而不是显示成"没有开通位"：那是两种不同的实情，混在一起就没人知道列车到底会走哪条。</p>
	 */
	readonly manualLeg: number;
	readonly locked: boolean;
	readonly holder: string;
	readonly holderLeg: number;
	readonly queueCount: number;
	/*
	 * 物理道岔（null = 这个条目不属于单开道岔，走老的"按进向设 0/1"）。
	 *
	 * <p>为什么界面必须知道这件事：单开道岔在接口里是**三行**（每个进向一行），但物理上只有**一个
	 * 位置**。逐行显示腿号时，"从岔股进来"那一行在位置 0 是禁止通行（引擎写 -1），如果界面照着
	 * "没设过 = 按 0 直通"去显示，就会把"这一侧禁止通行"说成"默认直通" —— 实测用户就是这么被绕住的。</p>
	 */
	readonly turnoutPosition: number | null;
	readonly prohibitedHex: string;
	readonly stemHex: string;
	readonly farHex: string;
	readonly branchHex: string;
	/** 不是单开道岔时的原因（引擎给的一行字）；是道岔时为空串。 */
	readonly whyNotTurnoutText: string;

	constructor(raw: RawPoint) {
		this.x = raw.x;
		this.y = raw.y;
		this.z = raw.z;
		this.via = raw.via;
		this.form = raw.form ?? "FORK";
		this.legs = (raw.legs ?? []).map((leg, index) => new PointLeg(index, leg));
		this.manualLeg = raw.manual ?? -1;
		this.locked = raw.locked ?? false;
		this.holder = raw.holder ?? "";
		this.holderLeg = raw.holderLeg ?? -1;
		this.queueCount = raw.queue?.length ?? 0;
		this.turnoutPosition = raw.position ?? null;
		this.prohibitedHex = raw.prohibited ?? "";
		this.stemHex = raw.stem ?? "";
		this.farHex = raw.far ?? "";
		this.branchHex = raw.branch ?? "";
		this.whyNotTurnoutText = raw.whyNotTurnout ?? "";
	}

	/** 平面图坐标 = 世界 {@code (x, z)} 直映（见 `Node.planeZ` 的说明）。 */
	get planeX(): number {
		return this.x;
	}

	get planeY(): number {
		return this.z;
	}

	/** 稳定键（节点坐标）—— 重取数据后用它重新查，不要攥着对象。 */
	get key(): string {
		return `${this.x},${this.y},${this.z}`;
	}

	get coords(): string {
		return `${this.x}, ${this.y}, ${this.z}`;
	}

	/**
	 * **当前实际开通**的腿序号：操作员的设定优先，没设过就是 0（引擎的默认 = 直通）。
	 *
	 * <p>与走行层同一口径（`MmtrPointAuthority` 的"手动优先、其次授权"在这里表现为
	 * "manual 优先、无设定即 0"）：界面要显示"列车现在会走哪条"，就不能把 -1 当成"没有"。</p>
	 */
	get activeLeg(): number {
		return this.manualLeg >= 0 ? this.manualLeg : 0;
	}

	/** 当前开通那条腿（越界时返回 null，例如世界改画后腿变少了）。 */
	get activeLegObject(): PointLeg | null {
		return this.legs[this.activeLeg] ?? null;
	}

	/** 操作员是否显式设定过（false = 用的是默认值 0）。 */
	get isManuallySet(): boolean {
		return this.manualLeg >= 0;
	}

	/** 形态的中文说法。 */
	get formText(): string {
		/*
		 * 不是单开道岔的节点**不许写"道岔"**：那种节点在卡片上已经有一行"为什么不是"，
		 * 形态再自称"道岔（含直通腿）"就自相矛盾（用户 2026-09-14 要求呈现统一）。
		 */
		const prefix = this.isTurnout ? "道岔" : "岔口";
		switch (this.form) {
			case "FORK":
				return `${prefix}（含直通腿）`;
			case "TEE":
				return `${prefix}（只有左右）`;
			case "MULTI":
				return `${prefix}（多腿）`;
			case "PASS_THROUGH":
				return "直通（无分支）";
			default:
				return "尽头";
		}
	}

	/** 信息卡里的一句话状态。 */
	get stateText(): string {
		if (this.isTurnout) {
			// 单开道岔：物理事实只有一个位置，逐行腿号只是它的派生视图 —— 说人话。
			return `位置 ${this.turnoutPosition}（${this.positionText}）`;
		}
		const leg = this.activeLegObject;
		const legText = leg === null ? `腿 ${this.activeLeg}（越界）` : `腿 ${leg.index}（${leg.kindText}）`;
		const manual = this.isManuallySet ? "" : "，未设定（默认直通）";
		const locked = this.locked ? "，已锁闭" : "";
		const held = this.holder === "" ? "" : `，被 ${this.holder} 持有（腿 ${this.holderLeg}）`;
		return `开通 ${legText}${manual}${locked}${held}`;
	}

	// ---------------------------------------------------------------- 物理道岔（一处一个位置）

	/** 这个条目属于一处**单开道岔**（引擎把它认成了物理道岔）。 */
	get isTurnout(): boolean {
		return this.turnoutPosition !== null;
	}

	/** 位置的中文说法。 */
	get positionText(): string {
		return this.turnoutPosition === 1 ? "岔股开放" : "正线贯通";
	}

	/**
	 * 菱形里该显示的数字。
	 *
	 * <p>物理道岔显示**节点位置 0/1**（三行说的是同一件事，逐行腿号会互相打架：位置 1 时
	 * "正线远端"那一行的腿号是 -1，显示成 0 就等于在说反话）；老式岔口仍旧显示这一行的腿号。</p>
	 */
	get markerNumber(): number {
		return this.isTurnout ? (this.turnoutPosition ?? 0) : this.activeLeg;
	}

	/** 这一行是不是**根部**那一行（唯一能表达两个位置的进向）。 */
	get isStemRow(): boolean {
		return this.isTurnout && this.via === this.stemHex;
	}

	/** 当前位置下禁止通行的那条轨是哪一根（根部/正线远端/岔股）。 */
	get prohibitedText(): string {
		if (!this.isTurnout) {
			return "";
		}
		if (this.prohibitedHex === this.branchHex) {
			return "岔股";
		}
		if (this.prohibitedHex === this.farHex) {
			return "正线远端";
		}
		return "（未知）";
	}

	/**
	 * 在**根部那一行**里，"把道岔扳到位置 {@code position}"对应第几条腿。
	 *
	 * <p>引擎的腿序是"直通→左→右→其它"，位置与腿号没有固定对应，所以只能按**轨**去认：
	 * 位置 0 要接正线远端、位置 1 要接岔股。</p>
	 *
	 * @returns 腿序号；-1 = 这一行里找不到那条轨（世界改画了，重新读取即可）
	 */
	turnoutLegFor(position: number): number {
		if (!this.isStemRow) {
			return -1;
		}
		const wanted = position === 1 ? this.branchHex : this.farHex;
		const leg = this.legs.find(item => item.railHex === wanted);
		return leg === undefined ? -1 : leg.index;
	}

	/** 位置 0/1 各自接哪条轨（按钮提示用）。 */
	turnoutPositionText(position: number): string {
		return position === 1
			? `岔股开放：根部接岔股 ${this.branchHex.slice(0, 8)}…，正线远端禁止通行`
			: `正线贯通：根部接正线远端 ${this.farHex.slice(0, 8)}…，岔股禁止通行`;
	}
}
