/**
 * 信号灯实体（轨道层节点上的灯）。
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-signals`。引擎登记的是**方块坐标 + 朝向角**（MTR 的朝向角约定，
 * 与放置方块时的 rotation 一致：南=0、西=90、北=180、东=270），加上这盏灯守的区间与当前状态。</p>
 *
 * <p>状态由引擎给出（`RED` / `SINGLE_YELLOW` / `DOUBLE_YELLOW` / `GREEN`）：闭塞层自己走完
 * "这盏灯开的区间有多深、里面有没有车、前方节点是否未解锁"，控制台只负责显示结论，
 * 不重算（重算就会两边不一致）。</p>
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
	}

	/** 平面图坐标（沿用 `(x, -z)` 约定）。 */
	get planeX(): number {
		return this.x;
	}

	get planeY(): number {
		return -this.z;
	}

	/** 显示坐标。 */
	get coords(): string {
		return `${this.x}, ${this.y}, ${this.z}`;
	}

	/**
	 * 朝向角 → 8 方位（屏幕方向：上=北）。
	 *
	 * <p>MTR 的朝向角是"南=0、顺时针 90 一档"：南(0) 西(90) 北(180) 东(270)。
	 * 屏幕上北朝上、东朝右，所以映射是 北=up、东=right、南=down、西=left。</p>
	 */
	get direction(): SignalDirection {
		// 归一化到 [0, 360)，每 45° 一档；MTR 的角是顺时针从南起算，所以先换成"从北起算的罗盘角"。
		const normalized = ((this.angle % 360) + 360) % 360;
		const compass = (normalized + 180) % 360;   // 南=0 → 罗盘 180；北=180 → 罗盘 0
		const index = Math.round(compass / 45) % 8;
		return (["north", "northEast", "east", "southEast", "south", "southWest", "west", "northWest"] as const)[index]!;
	}

	/** 箭头在屏幕上的旋转角（度，CSS 顺时针；基准是"朝上"的三角形）。 */
	get arrowRotation(): number {
		const compass = (this.angle + 180) % 360;   // 从北起算的罗盘角
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
