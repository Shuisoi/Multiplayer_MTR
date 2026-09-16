/*
 * 车辆的**三种记号形状**（用户 2026-09-16）："列车箭头以圆角箭头画，无动力车厢用矩形，货车用中空四边形"。
 *
 * <h2>怎么判定是哪一种</h2>
 * <p><b>先看这节车自己有没有动力</b>（`VehicleCar.mmtrPowered`）：</p>
 * <ol>
 *   <li>**有动力 ⇒ 圆角箭头**（动车 / 机车 —— 机车也算"车头"，所以**货运任务的机车也是箭头**，
 *       用户 2026-09-16 追加裁定："只有无动力挂车中空、机车画箭头"）；</li>
 *   <li>**没有动力 + 货运任务 ⇒ 中空四边形**（挂车 / 货车）；</li>
 *   <li>**没有动力 + 其它任务 ⇒ 矩形**（无动力客车 / 回送车）。</li>
 * </ol>
 * <p>判定顺序就是这样：**动力**是第一判据，"是不是货车"只在"没有动力"时才参与 —— 因为引擎里
 * "货运"只有**任务级**判据（`MmtrMission.Kind = {PASSENGER, FREIGHT, MANEUVER}`），
 * 车本身不带"我拉货"这个字段；而"机车"在数据上就是"有动力的那节"。</p>
 *
 * <h2>形状（局部坐标，中心在原点、**车头朝 +x**）</h2>
 * <p>长度按**引擎给的真车长**（米 → 画布单位）画，宽度是固定记号宽度 —— 真车 5 米宽会盖住 5 根轨，
 * 那是"按比例画车"而不是"地图记号"。三个形状都返回一条 `<path>` 的 `d`，模板里只用一种元素。</p>
 */
import type {Train, TrainCar} from "@/domain/Train";

/** 记号种类。 */
export type CarMarkerKind = "arrow" | "rectangle" | "hollow";

/**
 * 这节车该画哪一种（规则见文件头：**动力是第一判据**，"货运"只在无动力时才看）。
 *
 * @param train 整列车（只有它带任务种类）
 * @param car   这一节车（带自己的动力标记）
 */
export function carMarkerKind(train: Train, car: TrainCar): CarMarkerKind {
	if (car.powered) {
		return "arrow";
	}
	return train.missionKind === "FREIGHT" ? "hollow" : "rectangle";
}

/** 把数字按 0.01 修约后再写进 `d`：免得浮点尾巴把探针的"逐字符比对"搞得没法读。 */
function num(value: number): string {
	return String(Math.round(value * 100) / 100);
}

/**
 * 一条记号的路径（局部坐标：中心在原点，车头朝 +x）。
 *
 * @param kind         形状
 * @param lengthUnits  车长（画布单位，来自引擎的真车长）
 * @param widthUnits   记号宽度（画布单位，固定值）
 */
export function markerPath(kind: CarMarkerKind, lengthUnits: number, widthUnits: number): string {
	const halfLength = Math.max(lengthUnits, widthUnits) / 2;
	const halfWidth = widthUnits / 2;
	// 车尾圆角半径：不能超过半宽，也不能超过半长的一定比例（长车才显得出圆角）
	const radius = Math.min(halfWidth * 0.8, halfLength * 0.35);
	/*
	 * 车头（收尖段）的长度：**不能只按宽度算**。真车 13 倍长宽比，只按半宽收尖的话车头只有 2 个单位，
	 * 画出来是"一根棍子"而不是箭头；所以至少占车长的 30%。
	 */
	const nose = Math.min(halfLength * 0.6, Math.max(halfWidth, halfLength * 0.3));
	switch (kind) {
		case "rectangle":
			// 无动力车厢：正正方方一个矩形（四角见棱）
			return [
				`M ${num(-halfLength)} ${num(-halfWidth)}`,
				`L ${num(halfLength)} ${num(-halfWidth)}`,
				`L ${num(halfLength)} ${num(halfWidth)}`,
				`L ${num(-halfLength)} ${num(halfWidth)}`,
				"Z",
			].join(" ");
		case "hollow":
			// 货车：**中空**四边形（后端满宽、前端收一点，一眼与矩形区分）
			return [
				`M ${num(-halfLength)} ${num(-halfWidth)}`,
				`L ${num(halfLength - radius)} ${num(-halfWidth * 0.72)}`,
				`L ${num(halfLength - radius)} ${num(halfWidth * 0.72)}`,
				`L ${num(-halfLength)} ${num(halfWidth)}`,
				"Z",
			].join(" ");
		default:
			// 列车箭头：**圆角**箭头 —— 车尾两角圆、车头收成一条圆润的鼻子
			return [
				`M ${num(-halfLength + radius)} ${num(-halfWidth)}`,
				`Q ${num(-halfLength)} ${num(-halfWidth)} ${num(-halfLength)} ${num(-halfWidth + radius)}`,
				`L ${num(-halfLength)} ${num(halfWidth - radius)}`,
				`Q ${num(-halfLength)} ${num(halfWidth)} ${num(-halfLength + radius)} ${num(halfWidth)}`,
				`L ${num(halfLength - nose * 2)} ${num(halfWidth)}`,
				// 车头：两条二次曲线收成圆润的尖（控制点压在收尖段的九成处）
				`Q ${num(halfLength - nose * 0.4)} ${num(halfWidth)} ${num(halfLength)} 0`,
				`Q ${num(halfLength - nose * 0.4)} ${num(-halfWidth)} ${num(halfLength - nose * 2)} ${num(-halfWidth)}`,
				"Z",
			].join(" ");
	}
}

/** 只有中空四边形不填充（"中空"就是它这个形状的意义）。 */
export function markerFilled(kind: CarMarkerKind): boolean {
	return kind !== "hollow";
}

/** 记号种类在悬浮提示里的说法（跟形状一一对应：圆角箭头 / 矩形 / 中空四边形）。 */
export function markerKindLabel(kind: CarMarkerKind): string {
	switch (kind) {
		case "rectangle":
			return "无动力车厢";
		case "hollow":
			return "货车";
		default:
			return "动车";
	}
}
