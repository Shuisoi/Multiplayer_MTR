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

	/**
	 * 这盏灯应当**从节点往哪边挪一点**（屏幕平面单位向量，x 向右、y 向下）。
	 *
	 * <h3>为什么要挪</h3>
	 * <p>灯是**立在轨道旁边**的，而它的坐标是方块中心、往往就落在节点上 —— 屏幕上把 8 px 的图标
	 * 正好压在节点/轨道上，看不出"它在轨道哪一侧"。用户 2026-09-15 的规格：
	 * "信号灯就固定在节点左右的 10px 吧"。</p>
	 *
	 * <h3>为什么是"管辖方向的反面"（= 司机的左手侧）</h3>
	 * <p>现实里信号机立在**它所管辖方向的列车左侧**。而这里的 {@code angle} 是**管辖方向**
	 * （见类注释：角 0 管南边），所以灯要在那个方向的反面一侧。</p>
	 *
	 * <p>实测两处世界数据都对得上，而且它们**共同**定死了方向（差一个符号就会有一处跑到轨道另一边）：</p>
	 * <ul>
	 *   <li>南行灯（角 0，管辖方向 = +z）立在 {@code x=-157}，管的是 {@code x=-155} 那条轨
	 *       ⇒ 灯要往 <b>−x</b>（西）挪；</li>
	 *   <li>北行灯（角 180，管辖方向 = −z）立在 {@code x=-145}，管的是 {@code x=-147} 那条轨
	 *       ⇒ 灯要往 <b>+x</b>（东）挪。</li>
	 * </ul>
	 * <p>把管辖方向在**屏幕坐标里**（x 向右、y 向下）转 90° 得 {@code (-dy, dx)}，正好给出这两个方向 ——
	 * 也就是"**管辖方向那一侧的反面**"，与"信号机立在它所管辖列车的左侧"这条现实做法一致。</p>
	 *
	 * <p><b>屏幕尺度、固定像素</b>：返回值只表示方向，乘多少像素由调用方定（规格是 10 px），
	 * 所以它与缩放无关 —— 这正是用户要的"固定"。世界坐标不能拿来当偏移量：那样缩放时
	 * 灯相对节点会滑走。</p>
	 */
	get sideOffsetDirection(): {x: number; y: number} {
		const radians = (this.angle * Math.PI) / 180;
		// 管辖方向的屏幕向量：headingOf = (-sin, cos) 落在 (x, z)；屏幕上 z 映射到 y（向下）
		const dx = -Math.sin(radians);
		const dy = Math.cos(radians);
		// 屏幕坐标（x 右、y 下）里转 90°：(dx, dy) → (-dy, dx)
		return {x: -dy, y: dx};
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
