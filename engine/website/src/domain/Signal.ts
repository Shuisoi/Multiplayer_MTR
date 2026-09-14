/**
 * 信号灯实体（轨道层节点上的灯）。
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-signals`。引擎登记的是**方块坐标 + 朝向角**（MTR 的朝向角约定，
 * 与放置方块时的 rotation 一致：南=0、西=90、北=180、东=270），加上这盏灯守的区间与当前状态。</p>
 *
 * <p>状态由引擎给出（`RED` / `SINGLE_YELLOW` / `DOUBLE_YELLOW` / `GREEN`）：闭塞层自己走完
 * "这盏灯开的区间有多深、里面有没有车、前方节点是否未解锁"，控制台只负责显示结论，
 * 不重算（重算就会两边不一致）。</p>
 *
 * <h3>角的语义（2026-09-13 引擎纠正，与 {@code MmtrDirectionalBlockService.headingOf} 同口径）</h3>
 * <p>方块状态里的角是 {@code FACING.asRotation()}，也就是**这盏灯管辖的方向**（司机迎着灯面开过来的
 * 那一侧）；**肉眼看到的灯面在它的反面**。实测两条判据：角 0 的灯管南边、灯面朝北；
 * 角 90 的灯管西边、灯面朝东。所以下面 {@link #direction}／{@link #arrowRotation} 画的是**管辖方向**，
 * 卡片里的行名也叫"管辖方向"，不要读成"灯面朝哪边"。</p>
 */

/** 引擎原始信号数据（接口形状，改动时要与 `SystemMapServlet.getMmtrSignals` 同步）。 */
export interface RawSignal {
	readonly key: string;
	readonly x: number;
	readonly y: number;
	readonly z: number;
	/** MTR 朝向角（度）：南=0、西=90、北=180、东=270。 */
	readonly angle: number;
	/** 灯位数：2 / 3 / 4。 */
	readonly aspects?: number;
	/** `AUTO` = 按位置推断；`BOUND` = 显示被绑定对象的状态。 */
	readonly mode?: string;
	readonly target?: string;
	/** 当前显示状态：`RED` / `SINGLE_YELLOW` / `DOUBLE_YELLOW` / `GREEN`，空串表示引擎未给出。 */
	readonly aspect?: string;
	/** 这盏灯是否真的开了一个区间（false = 闭塞层不认识它，界面上标为未接入）。 */
	readonly hasSection?: boolean;
	/** 这盏灯现在守的轨（hex，可多条 = 一灯多腿）。 */
	readonly boundRails?: readonly string[];
	/** 守轨是不是人工点选指定的（false = 引擎按站位与朝向推断的）。 */
	readonly boundExplicit?: boolean;
	/** 这盏灯**可以点**的候选轨（hex）—— 点选绑定就从中挑。 */
	readonly candidateRails?: readonly string[];
}

/** 显示状态。`unknown` = 引擎没给出（例如灯刚放上、还没被闭塞层纳入）。 */
export type SignalState = "red" | "singleYellow" | "doubleYellow" | "green" | "unknown";

/** 一个 8 方位方向（用于把 MTR 朝向角转成屏幕上的箭头方向）。 */
export type SignalDirection = "north" | "northEast" | "east" | "southEast" | "south" | "southWest" | "west" | "northWest";

export class Signal {
	readonly key: string;
	readonly x: number;
	readonly y: number;
	readonly z: number;
	/** MTR 朝向角（度）。 */
	readonly angle: number;
	readonly aspectCount: number;
	readonly mode: string;
	readonly target: string;
	readonly state: SignalState;
	readonly hasSection: boolean;
	/**
	 * 这盏灯现在守的轨（hex）。
	 *
	 * <p>**必须来自引擎**（`boundRails`），界面不自己算：点选绑定的语义是"点哪根就守哪根"，
	 * 如果界面按自己的几何猜一遍，就会出现"图上高亮的是 A、引擎其实守 B"这种最难查的分叉。</p>
	 */
	readonly boundRails: readonly string[];
	/** 守轨是人工指定的（true）还是引擎推断的（false）。 */
	readonly boundExplicit: boolean;
	/** 可点的候选轨（hex）：点选绑定从这里挑一根。 */
	readonly candidateRails: readonly string[];

	constructor(raw: RawSignal) {
		this.key = raw.key;
		this.x = raw.x;
		this.y = raw.y;
		this.z = raw.z;
		this.angle = raw.angle;
		this.aspectCount = raw.aspects ?? 2;
		this.mode = raw.mode ?? "AUTO";
		this.target = raw.target ?? "";
		this.state = Signal.parseState(raw.aspect ?? "");
		this.hasSection = raw.hasSection ?? false;
		this.boundRails = raw.boundRails ?? [];
		this.boundExplicit = raw.boundExplicit ?? false;
		this.candidateRails = raw.candidateRails ?? [];
	}

	/** 平面图坐标 = 世界 {@code (x, z)} 直映（见 `Node.planeZ` 的说明）。 */
	get planeX(): number {
		return this.x;
	}

	get planeY(): number {
		return this.z;
	}

	/** 显示坐标。 */
	get coords(): string {
		return `${this.x}, ${this.y}, ${this.z}`;
	}

	/**
	 * 朝向角 → 8 方位（屏幕方向：上=北）。
	 *
	 * <p>角的数值就是 {@code FACING.asRotation()}（南=0、西=90、北=180、东=270），也就是**管辖方向**：
	 * 角 0 的灯管南边、角 270 的灯管东边（灯面在它的反面）。屏幕上北朝上、东朝右，
	 * 所以映射是 北=up、东=right、南=down、西=left。</p>
	 */
	get direction(): SignalDirection {
		// 归一化到 [0, 360)，每 45° 一档。FACING 角换算成罗盘角要 +180：
		// 角 0（FACING 南）→ 罗盘 180 = 南；角 270（FACING 东）→ 罗盘 90 = 东。
		const normalized = ((this.angle % 360) + 360) % 360;
		const compass = (normalized + 180) % 360;
		const index = Math.round(compass / 45) % 8;
		return (["north", "northEast", "east", "southEast", "south", "southWest", "west", "northWest"] as const)[index]!;
	}

	/** 箭头在屏幕上的旋转角（度，CSS 顺时针；基准是"朝上"的三角形）= 管辖方向的罗盘角。 */
	get arrowRotation(): number {
		const compass = (this.angle + 180) % 360;
		return compass;
	}

	/** 朝向的文字说明（信息卡用）。 */
	get directionText(): string {
		switch (this.direction) {
			case "north":
				return "北（世界 z 减小）";
			case "northEast":
				return "东北";
			case "east":
				return "东（世界 x 增大）";
			case "southEast":
				return "东南";
			case "south":
				return "南（世界 z 增大）";
			case "southWest":
				return "西南";
			case "west":
				return "西（世界 x 减小）";
			default:
				return "西北";
		}
	}

	/** 状态的文字说明。 */
	get stateText(): string {
		switch (this.state) {
			case "red":
				return "红灯（停车）";
			case "singleYellow":
				return "单黄（注意）";
			case "doubleYellow":
				return "双黄（减速）";
			case "green":
				return "绿灯（通行）";
			default:
				return "未接入（引擎未给出状态）";
		}
	}

	/** 灯位数的说明。 */
	get aspectsText(): string {
		return `${this.aspectCount} 灯位`;
	}

	/** 守轨情况的说明（信息卡用）：人工绑了几根 / 引擎推断出的几根 / 一根都没有。 */
	get bindingText(): string {
		if (this.boundRails.length === 0) {
			return "没有守任何轨（不参与闭塞）";
		}
		return `${this.boundExplicit ? "人工绑定" : "引擎推断"}：守 ${this.boundRails.length} 根轨`;
	}

	/** 某条轨是不是它守的（界面高亮判定）。 */
	guards(railHex: string): boolean {
		return this.boundRails.includes(railHex);
	}

	/** 某条轨是不是它能点的候选（界面高亮判定）。 */
	canBind(railHex: string): boolean {
		return this.candidateRails.includes(railHex);
	}

	private static parseState(aspect: string): SignalState {
		switch (aspect) {
			case "RED":
				return "red";
			case "SINGLE_YELLOW":
				return "singleYellow";
			case "DOUBLE_YELLOW":
				return "doubleYellow";
			case "GREEN":
				return "green";
			default:
				return "unknown";
		}
	}
}
