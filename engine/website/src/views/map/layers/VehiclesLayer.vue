<script setup lang="ts">
/*
 * 「地图」页图层：**车辆位置**（用户 2026-09-16："读取车辆位置，在地图页显示"）。
 *
 * <h2>画什么</h2>
 * <p>**一节车一个记号**（不是一列车一个）：记号压在它此刻所在的那根轨上、**车头朝着行车方向**；
 * 悬浮提示写清它是谁、第几节、什么车、多快、在不在运行、线路/目的地、车门、下一区间、停在哪个车场。
 * 除此之外不加东西（用户："不要加我要求以外任何元素"）。</p>
 * <p>记号有**三种形状**（用户 2026-09-16："列车箭头以圆角箭头画，无动力车厢用矩形，货车用中空四边形"）：
 * 动车/机车 = 圆角箭头，无动力车厢 = 矩形，无动力挂车跑货运 = 中空四边形。判定与路径生成都在
 * `../vehicleMarkerShapes.ts`（纯函数 + 用例），这一层只负责摆放与朝向。</p>
 *
 * <h2>样式 = 节点那一套（用户 2026-09-16："列车图标样式和节点相同（颜色描边阴影）"）</h2>
 * <p>颜色、描边、阴影三个数**照抄 `RailNodesLayer`**：`fill: var(--fg)`（白）、`stroke: var(--fg-dim)`（灰）、
 * 描边宽 0.1 格、外阴影 = 同形状放大 {@link MARKER_SHADOW_SCALE} 倍填充 {@link SHADOW_GRADIENT_ID}
 * （与节点那个径向渐变同一套 stop 偏移：实心部分正好到记号自己的边，往外渐隐）。
 * 为什么阴影也要放大同一倍数：节点那边"实心到边缘、往外渐隐"这条关系**只由倍数决定**
 * （1 / 2.5 = 40%），所以任何形状——包括 13:1 的长条——用同一个倍数都得到同样的观感。</p>
 * <p>**彩色只剩"选中"那一份**（用户："点击后高亮"）：整列被选中的车换成主题强调色，
 * 于是"这一页哪里是灰的、哪个是你看的那列车"一眼可分（原先车是唯一彩的东西，
 * 现在彩色改由"选中"来用）。</p>
 *
 * <h2>点得动（用户 2026-09-16："游戏内的列车都是通过任务驱动的，让列车图标能够点击"）</h2>
 * <p>命中判定自己做（`../vehicleHit.ts` 的纯函数）：指针 → 画布坐标 → 在记号的**旋转矩形 +
 * 屏幕半径余量**里找最近的。为什么不按元素监听：记号宽只有 1.2 格（默认取景下约 1 px），
 * 按元素就是"拿鼠标去戳一条 1 px 宽的线"。也不按"到中心的距离"：一列车 16 格长，
 * 那样只有车心点得动。选中态与"车压在节点上时点谁"的仲裁都在 `../trainSelection.ts`。</p>
 *
 * <h2>位置怎么来的（这一层唯一的"技术点"）</h2>
 * <p>引擎报的不是坐标，而是**每节车**"在轨上的**弧长**"：`railHex + railArcM + railArcLengthM`。网页按
 * `railArcM / railArcLengthM` 在**自己已经画出来的那条折线**上取点（`pointAtLengthFraction`）——
 * 那条折线是 `alignRail()` 对齐过的（「地图」页把采样形状对齐到轨两端，见 `railAlignment.ts`），
 * 所以落点天然就在线上的正确位置；直接拿引擎的 `headX/headZ` 反而会偏半格
 * （与用户抓到的"信号灯左右分布不均匀"同一个坑）。曲线轨也走同一条路。</p>
 * <p>老引擎（或没有编组体的车）不给 `cars` ⇒ 退回"车头一节"，按动车画一个箭头（不会凭空少画车）。</p>
 *
 * <h2>朝向</h2>
 * <p>用 t±ε 两点的切线定角；这节车的 `forward=false`（车头朝弧减方向）时补 180°。</p>
 *
 * <h2>尺寸（画布单位，1 格 = UNITS_PER_BLOCK）</h2>
 * <p>**长**按引擎给的真车长画（米 → 画布单位，见 `markerPath`），**宽**固定 1.2 格 —— 真车 5 米宽会
 * 盖住 5 根轨，那是"按比例画车"而不是"地图记号"。</p>
 */
import {computed, onMounted, onBeforeUnmount, watch} from "vue";
import {railHexKey} from "@/domain/MapNode";
import {describeCar, type TrainCar} from "@/domain/Train";
import {UNITS_PER_BLOCK, useMapContext} from "../mapContext";
import {useLiveFeeds} from "../liveFeeds";
import {alignRail, pointAtLengthFraction, type AlignedRail} from "../railAlignment";
import {carMarkerKind, markerFilled, markerKindLabel, markerPath, type CarMarkerKind} from "../vehicleMarkerShapes";
import {hitMarker, type HitMarker} from "../vehicleHit";
import {hoveredTrainId, registerVehicleHitTest, selectTrain, selectedTrainId} from "../trainSelection";

/** 记号宽度（画布单位）：1.2 格 —— **不按真车宽画**（真车 5 米宽会盖住 5 根轨，那是"按比例画车"而不是"地图记号"）。 */
const VEHICLE_WIDTH = UNITS_PER_BLOCK * 1.2;
/** 兜底车长（米）：引擎没给车长时按 16 米（标准 B 型车）画。 */
const FALLBACK_CAR_LENGTH_M = 16;
/** 记号描边宽度（画布单位）：与节点同一个数（节点 = 1 格的 10%）。 */
const MARKER_STROKE = UNITS_PER_BLOCK * 0.1;
/** 外阴影的放大倍数：与节点的 `NODE_SHADOW_SCALE` 同一个数（见文件头"样式 = 节点那一套"）。 */
const MARKER_SHADOW_SCALE = 2.5;
/** 外阴影渐变（这个 SVG 里的 id；节点层用的是 `mmtr-node-shadow`，两边各留一份免得互相依赖）。 */
const SHADOW_GRADIENT_ID = "mmtr-vehicle-shadow";
/**
 * 命中余量（**屏幕像素**，不是画布单位）：记号只有 1.2 格宽 —— 默认取景下约 1 px，
 * 直接按元素监听就等于"要拿鼠标去戳一个亚像素的线"。判定按屏幕像素扩一圈，任何缩放级别手感一样。
 */
const HIT_RADIUS_PX = 12;
/** 取切线时的比例步长（±0.5%）。 */
const TANGENT_STEP = 0.005;
/*
 * 刷新节拍不在这里 —— 它收在页面的活数据 store 里（`mapContext.LIVE_REFRESH_MILLIS`，
 * 用户 2026-09-16："所有可变的都需要 0.5s 一次变动"）：车、灯、道岔、总区间**同一个节拍**，
 * 每拍每路只取一次。这里只负责"把最新的车摆到它那根轨上"。
 */

/** 画布里的一节车。 */
interface Placed {
	readonly key: string;
	/** 这节车属于哪列车（点击按**车**选，不按节：一列车的每一节一起高亮）。 */
	readonly trainId: string;
	readonly x: number;
	readonly y: number;
	/** 记号的旋转角（度，顺时针，0 = 车头朝 +x）。 */
	readonly rotation: number;
	/** 记号长度（画布单位 = 真车长）/ 宽度（固定）。命中判定要用，所以也放在这里。 */
	readonly lengthUnits: number;
	readonly widthUnits: number;
	/** 记号路径（局部坐标，见 `../vehicleMarkerShapes.ts`）。 */
	readonly d: string;
	/** 记号种类（箭头 / 矩形 / 中空四边形）—— 只用来上样式钩子，判定在 `carMarkerKind()`。 */
	readonly kind: CarMarkerKind;
	/** 要不要填充（只有中空四边形不填）。 */
	readonly filled: boolean;
	readonly title: string;
}

const ctx = useMapContext();
/*
 * 数据来自页面的活数据 store（`../liveFeeds.ts`）：**车辆每 `LIVE_REFRESH_MILLIS` 换一次**
 * （用户 2026-09-16："所有可变的都需要 0.5s 一次变动"），轨是静态的那份（挂载取一次）。
 * 这一层因此没有自己的定时器 —— 节拍只有一个，在 `liveFeeds.ts`。
 */
const feeds = useLiveFeeds();
const trains = feeds.trains;
/** 轨 hex（规范化）→ 对齐后的折线：轨是静态的，跟着 store 走即可。 */
const alignedByKey = computed<ReadonlyMap<string, AlignedRail>>(() => {
	const map = new Map<string, AlignedRail>();
	for (const rail of feeds.rails.value) {
		map.set(railHexKey(rail.hex), alignRail(rail));
	}
	return map;
});

/*
 * 记号：**一节车一个**（用户 2026-09-16："列车箭头以圆角箭头画，无动力车厢用矩形，货车用中空四边形"）。
 * 形状与判定在 `../vehicleMarkerShapes.ts`（纯函数 + 用例）；这里只负责"把每节车摆到它那根轨上"。
 * 长度按引擎给的真车长画（米 → 画布单位），宽度是固定记号宽度。
 */
const placed = computed<readonly Placed[]>(() => {
	ctx.anchor.value;
	const out: Placed[] = [];
	for (const train of trains.value) {
		// 引擎没给 `cars`（老引擎 / 没有编组体）⇒ 退回"车头一节"：按动车画一个箭头
		const cars: readonly TrainCar[] = train.cars.length > 0 ? train.cars : [{
			index: 0,
			railHex: train.railHex,
			railKey: train.railKey,
			arcM: train.arcM,
			arcLengthM: train.arcLengthM,
			forward: train.arcIncreasing,
			lengthM: FALLBACK_CAR_LENGTH_M,
			stockId: "",
			powered: true,
			capacity: 0,
		}];
		for (const car of cars) {
			if (car.railHex === "" || car.arcLengthM <= 0) {
				continue;
			}
			const aligned = alignedByKey.value.get(car.railKey);
			if (!aligned || aligned.points.length < 2) {
				continue;
			}
			const t = Math.max(0, Math.min(1, car.arcM / car.arcLengthM));
			// 对齐过的折线上按弧长比例取点 —— 落点天然在「地图」页画出来的那条线上
			const sample = pointAtLengthFraction(aligned.points, t);
			const [x, y] = ctx.project(sample[0], sample[1]);
			// 朝向：t±ε 两点的切线；这节车的车头若朝"弧减"方向就补 180°
			const before = pointAtLengthFraction(aligned.points, Math.max(0, t - TANGENT_STEP));
			const after = pointAtLengthFraction(aligned.points, Math.min(1, t + TANGENT_STEP));
			let rotation = Math.atan2(after[1] - before[1], after[0] - before[0]) * 180 / Math.PI;
			if (!car.forward) {
				rotation += 180;
			}
			const kind = carMarkerKind(train, car);
			const lengthUnits = Math.max(car.lengthM, 0) * UNITS_PER_BLOCK;
			out.push({
				key: `${train.vehicleId}#${car.index}`,
				trainId: train.vehicleId,
				x,
				y,
				rotation,
				lengthUnits,
				widthUnits: VEHICLE_WIDTH,
				d: markerPath(kind, lengthUnits, VEHICLE_WIDTH),
				kind,
				filled: markerFilled(kind),
				title: describeCar(train, car, markerKindLabel(kind)),
			});
		}
	}
	return out;
});

/** 一列车的记号是不是"被选中"（整列一起高亮 —— 用户点的是车，不是某一节）。 */
function isSelected(item: Placed): boolean {
	return item.trainId === selectedTrainId.value;
}

/*
 * 阴影与记号分两趟画（阴影全部先画、记号再全部压上去），与节点层同一个理由：
 * 相邻两节的阴影会盖到前一节的记号上（阴影放大 2.5 倍，比车身还长）。
 */

// ------------------------------------------------------------------ 命中与点选

/** 可命中的记号（`../vehicleHit.ts` 的形状）。 */
const hittable = computed<readonly HitMarker<Placed>[]>(() => placed.value.map(item => ({
	item,
	x: item.x,
	y: item.y,
	rotation: item.rotation,
	lengthUnits: item.lengthUnits,
	widthUnits: item.widthUnits,
})));

/** 指针下面是哪一节车（命中判定见 `../vehicleHit.ts`）。 */
function pick(clientX: number, clientY: number): Placed | null {
	const point = ctx.toCanvas(clientX, clientY);
	if (!point) {
		return null;
	}
	return hitMarker(hittable.value, point.x, point.y, HIT_RADIUS_PX / point.scale);
}

/*
 * 鼠标形状：悬停在车上时给"手型"。**必须写在 viewport `<g>` 的行内 style 上** ——
 * svg-pan-zoom 自己给那个 `<g>` 定了 `cursor: grab` 的类规则，写在别处都会被它压住
 * （行内样式才赢），而它平时是空的，所以取消悬停就是把它清回空串。
 */
function setCursor(pointer: boolean): void {
	const viewport = ctx.stage.value?.querySelector<SVGGElement>(".svg-pan-zoom_viewport");
	if (viewport) {
		viewport.style.cursor = pointer ? "pointer" : "";
	}
}

function onPointerMove(event: PointerEvent): void {
	const found = pick(event.clientX, event.clientY);
	hoveredTrainId.value = found === null ? null : found.trainId;
	setCursor(found !== null);
}

function onPointerLeave(): void {
	hoveredTrainId.value = null;
	setCursor(false);
}

/** 左键点车 → 选中（再点一次取消）；点空白 → 取消。 */
function onPointerDown(event: PointerEvent): void {
	if (event.button !== 0) {
		return;
	}
	// 浮层（任务卡片）也在舞台里：它的点击不该被当成"点了空白"而把选中清掉（节点层踩过同一个坑）
	if (!(event.target instanceof SVGElement)) {
		return;
	}
	const found = pick(event.clientX, event.clientY);
	selectTrain(found === null || found.trainId === selectedTrainId.value ? null : found.trainId);
}

/** 点地图以外的地方（上边栏、切页…）也取消选中。 */
function onDocumentPointerDown(event: PointerEvent): void {
	if (selectedTrainId.value === null) {
		return;
	}
	const target = event.target as Node | null;
	if (target && ctx.stage.value?.contains(target)) {
		return; // 地图内部的点击交给 onPointerDown 裁决
	}
	if (target && (target as HTMLElement).closest?.(".train-card") !== null) {
		return; // 卡片自己的点击
	}
	selectTrain(null);
}

/*
 * 监听挂在**框架的舞台元素**上（与节点层同一套）：命中判定本来就要按屏幕半径找最近的记号，
 * 一个一个元素监听既解决不了"记号太细点不到"，也会在缩放后失效。
 */
let listeningOn: HTMLElement | null = null;

function detachListeners(): void {
	listeningOn?.removeEventListener("pointermove", onPointerMove);
	listeningOn?.removeEventListener("pointerleave", onPointerLeave);
	listeningOn?.removeEventListener("pointerdown", onPointerDown);
	listeningOn = null;
}

function attachListeners(stage: HTMLElement | null): void {
	if (listeningOn === stage) {
		return;
	}
	detachListeners();
	listeningOn = stage;
	stage?.addEventListener("pointermove", onPointerMove);
	stage?.addEventListener("pointerleave", onPointerLeave);
	stage?.addEventListener("pointerdown", onPointerDown);
}

watch(ctx.stage, attachListeners, {immediate: true, flush: "post"});

onMounted(() => {
	document.addEventListener("pointerdown", onDocumentPointerDown);
	/*
	 * 把"我的命中判定"交给节点层仲裁：**车画在节点上面，所以车压在节点上时点下去算点车**。
	 * 两个图层挂在同一个舞台元素上，谁先收到事件不可依赖，所以做成显式询问（见 trainSelection 头部）。
	 */
	registerVehicleHitTest((clientX, clientY) => pick(clientX, clientY) !== null);
});

onBeforeUnmount(() => {
	detachListeners();
	setCursor(false);
	registerVehicleHitTest(null);
	document.removeEventListener("pointerdown", onDocumentPointerDown);
});
</script>

<template>
	<!--
		两层，按这个顺序叠：记号的外阴影 → 记号本身（与节点层"阴影夹在中间"同一个做法，
		只不过这里没有"线在下面"的问题，所以阴影放在最前面）。
	-->
	<defs>
		<!--
			记号的外阴影：与节点那个 `mmtr-node-shadow` 同一套 stop 偏移。
			objectBoundingBox 单位：0% = 阴影中心、100% = 阴影边缘，而记号自己的边在
			`1 / MARKER_SHADOW_SCALE`（= 40%）处 —— 所以"记号底下是实心黑、出了记号往外渐隐"
			这条关系**只由放大倍数决定**，与记号是圆点还是 13:1 的长条无关。
			id 在整个 SVG 里唯一：别的图层要用渐变请另取名字（节点层用的是它自己那份）。
		-->
		<radialGradient :id="SHADOW_GRADIENT_ID">
			<stop offset="0%" stop-color="#000000" stop-opacity="1"/>
			<stop :offset="`${100 / MARKER_SHADOW_SCALE}%`" stop-color="#000000" stop-opacity="1"/>
			<stop offset="100%" stop-color="#000000" stop-opacity="0"/>
		</radialGradient>
	</defs>

	<path
		v-for="item in placed"
		:key="`shadow-${item.key}`"
		class="vehicle-shadow"
		:d="item.d"
		:transform="`translate(${item.x} ${item.y}) rotate(${item.rotation}) scale(${MARKER_SHADOW_SCALE})`"
		:fill="`url(#${SHADOW_GRADIENT_ID})`"
	/>
	<path
		v-for="item in placed"
		:key="item.key"
		class="vehicle"
		:class="[`vehicle-${item.kind}`, {'vehicle-selected': isSelected(item)}]"
		:d="item.d"
		:transform="`translate(${item.x} ${item.y}) rotate(${item.rotation})`"
		:stroke-width="MARKER_STROKE"
	>
		<title>{{ item.title }}</title>
	</path>
</template>

<style scoped>
/* 记号：与节点同一套颜色与描边（用户："列车图标样式和节点相同（颜色描边阴影）"） */
.vehicle {
	fill: var(--fg);
	stroke: var(--fg-dim);
	stroke-linejoin: round;
}

/*
 * **选中的那一列**（用户："点击后高亮"）：换成主题强调色 + 白描边。
 * 全页只有它和它的进路是彩的 —— 于是"我在看哪列车"一眼可分。
 */
.vehicle-selected {
	fill: var(--accent);
	stroke: var(--fg);
}

/*
 * 货车：**中空**四边形 —— 不填充，只留边线（"中空"就是它这个形状的意义）。
 * 权重与 `.vehicle` 相同，靠"后面这条赢"覆盖 `fill`；选中时仍然中空（高亮走描边）。
 */
.vehicle-hollow {
	fill: none;
}

.vehicle-hollow.vehicle-selected {
	fill: none;
	stroke: var(--accent);
}

.vehicle-shadow {
	pointer-events: none;
}
</style>
