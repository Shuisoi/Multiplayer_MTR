import {isAxisAligned} from "./railGeometry";

/**
 * 轨实体。
 *
 * <p>数据来自引擎的 `/mtr/api/map/mmtr-topology` 的 `rails` 数组：每条轨给出两端的世界坐标
 * （端点一定落在节点上，实测 134/134）与两端限速。</p>
 *
 * <p>线型（直线/曲线）由世界坐标决定，这里只做"给人看"的封装，不含几何计算——
 * 几何在 `domain/railGeometry.ts`。</p>
 */

/** 引擎原始轨数据（接口形状，改动时要与 `SystemMapServlet.getMmtrTopology` 同步）。 */
export interface RawRail {
	readonly hex: string;
	readonly x1: number;
	readonly y1: number;
	readonly z1: number;
	readonly x2: number;
	readonly y2: number;
	readonly z2: number;
	/**
	 * 沿轨里程采样的真实形状（世界坐标三元组，从轨的一个端点到另一个端点）。
	 *
	 * <p>引擎的轨由**两段圆弧**拼成，所以可以弯成 U 型甚至 S 型；只有两个端点的话前端只能自己
	 * 编一个中间形状，编出来的（单弧、贝塞尔）跟真实轨道明显不一样。老接口刻意不带这个字段
	 * （原注释写的是 "no in-game curve sampling"），那是把地图当成抽象拓扑图了。</p>
	 *
	 * <p>可选：老版本引擎不带这个字段时，前端退回"端点 + 线型规则"的画法。</p>
	 */
	readonly path?: readonly (readonly number[])[];
	readonly speedLimitKmh1?: number;
	readonly speedLimitKmh2?: number;
}

export class Rail {
	readonly hex: string;
	readonly x1: number;
	readonly y1: number;
	readonly z1: number;
	readonly x2: number;
	readonly y2: number;
	readonly z2: number;
	/** 沿轨采样点（世界坐标）。为空表示引擎没给形状，调用方退回端点画法。 */
	readonly path: readonly {x: number; y: number; z: number}[];
	readonly speedLimitKmh1: number;
	readonly speedLimitKmh2: number;

	constructor(raw: RawRail) {
		this.hex = raw.hex;
		this.x1 = raw.x1;
		this.y1 = raw.y1;
		this.z1 = raw.z1;
		this.x2 = raw.x2;
		this.y2 = raw.y2;
		this.z2 = raw.z2;
		this.path = (raw.path ?? [])
			.filter(sample => sample.length >= 3)
			.map(sample => ({x: sample[0]!, y: sample[1]!, z: sample[2]!}));
		this.speedLimitKmh1 = raw.speedLimitKmh1 ?? 0;
		this.speedLimitKmh2 = raw.speedLimitKmh2 ?? 0;
	}

	/**
	 * 平面图坐标 = 世界 {@code (x, z)} 直映（见 `Node.planeZ` 的说明）。
	 *
	 * <p>同一份映射必须各处一致：节点、轨的两端、轨的采样点、信号灯位 —— 少改一处，
	 * 那一样东西就会**上下颠倒**地画到图上（比全反更难发现）。所以这里连注释都指向同一处说明。</p>
	 */
	get planeX1(): number {
		return this.x1;
	}

	get planeY1(): number {
		return this.z1;
	}

	get planeX2(): number {
		return this.x2;
	}

	get planeY2(): number {
		return this.z2;
	}

	/** 是否"同一轴"：x 或 z 任一相同 → 画直线，否则画曲线。 */
	get isAxisAligned(): boolean {
		return isAxisAligned(this.x1, this.z1, this.x2, this.z2);
	}

	/** 两个方向的限速是否一致（不一致的轨在界面上单独标出来）。 */
	get speedLimitsEqual(): boolean {
		return this.speedLimitKmh1 === this.speedLimitKmh2;
	}

	/** 取较高的限速用来配色（双方向限速不同时，画出来的是"较好的那条"）。 */
	get speedLimitKmh(): number {
		return Math.max(this.speedLimitKmh1, this.speedLimitKmh2);
	}

	/** 平面长度（米），用于信息卡。 */
	get length(): number {
		return Math.hypot(this.x2 - this.x1, this.z2 - this.z1);
	}
}
