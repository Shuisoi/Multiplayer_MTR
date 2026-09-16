<script setup lang="ts">
/*
 * **区间地图**页：专门用来显示**区间**（与"地图"页分开，两张图各自演化）。
 *
 * <h2>页面现在是什么</h2>
 * <p>只有框架（舞台 + SVG + 相机 + 内建坐标系）——**图层等下一步指令再画**。
 * 之所以先把页面立起来：区间的数据、画法、交互都要一轮一轮定，页面边界先划清，
 * 后面每一步都只动这个文件里的图层清单。</p>
 *
 * <h2>数据来源（引擎 2026-09-16 确认的新接口）</h2>
 * <ul>
 *   <li>{@code GET /mtr/api/map/mmtr-track-sections} —— **L1 轨道区间**：切点只由灯产生、
 *       **无方向**、双向共用；占用判定在这一层。每条 {@code id/length/occupied/spans[{hex,from,to,points[]}]}，
 *       顶层 {@code count/busyCount/railCount}。</li>
 *   <li>{@code GET /mtr/api/map/mmtr-block-sections} —— **L2 行车区间**：**有方向**、灯到灯、跨轨；
 *       无灯连通块整块一段。每条还有 {@code entrySignal/exitSignal/next/aspect/direction{angle,label,dx,dz}/
 *       uncovered/members[]}，spans 带 {@code dirOfTravel/reachable}。</li>
 *   <li>{@code GET /mtr/api/map/mmtr-total-sections} —— **总区间**：**地图上一条带**（几何 = L1），
 *       多出来的是归属 {@code covers[]}（这一处由哪几个方向的哪几段覆盖、各自什么显示）。
 *       错开处一辆车"既在上行、也在下行"，靠它才画得成一条。</li>
 * </ul>
 *
 * <h2>加图层时注意两件事</h2>
 * <ol>
 *   <li>**锚点要有人报到**：框架的锚点由"基础图层"`ctx.setAnchor()` 报一次（只有第一次生效）。
 *       这一页如果没有轨网底图，就由区间图层自己报（用它的世界范围中心）——
 *       否则画面会停在世界的原点附近。</li>
 *   <li>spans 带的 {@code points[]} 是**沿轨采样的世界坐标**（与"地图"页轨道线同一套），
 *       直接用 `ctx.project()` 落进画布。</li>
 * </ol>
 */
import MapFrame from "./map/MapFrame.vue";
import PlatformsLayer from "./map/layers/PlatformsLayer.vue";
import SectionCutPointsLayer from "./map/layers/SectionCutPointsLayer.vue";
import SectionTrackLayer from "./map/layers/SectionTrackLayer.vue";
import SignalLayer from "./map/layers/SignalLayer.vue";
import TotalSectionsLayer from "./map/layers/TotalSectionsLayer.vue";
import {provideLiveFeeds} from "./map/liveFeeds";

/*
 * 这一页**会变**的数据：道岔（轨的淡出）、信号灯（显示）、总区间（带的颜色）。
 * 一个节拍（`LIVE_REFRESH_MILLIS` = 500 ms）里每路只取一次，各图层共用（见 `liveFeeds.ts`）——
 * 用户 2026-09-16："所有可变的都需要 0.5s 一次变动"。车不在这一页，所以不取车辆那一份。
 */
provideLiveFeeds(["points", "signals", "totalSections"]);
</script>

<template>
	<!--
		四层，顺序 = 叠放顺序：
		  ① 轨道线（引擎原始形状 + 道岔状态淡出）；
		  ② **总区间的带**（一处一条，宽 1 格 = 与端点同样宽，按最不利状态上色）—— 这一页的主色带；
		  ③ 区间端点（2 格、白心灰边 + 外阴影，压在带上面才看得出区间在哪儿断开）；
		  ④ **信号灯**（用户 2026-09-16："信号灯也一起显示在里面"）—— 直接复用「地图」页那一个图层：
		     同一个组件、同一套尺寸与颜色，所以两页的灯逐盏一致（探针逐盏比对过）。
		     **但锚点要指明坐标系**（`anchor-frame="rail-sample"`）：这一页的轨/带/端点都画在
		     **方块中心**（引擎的采样写法，= 节点坐标 + (0.5, 0.5) 格），灯若照「地图」页那样锚在
		     节点坐标（方块角）上，就会整层偏半格 —— 用户亲眼看到的是"灯左右分布不均匀"
		     （实测右灯离点 1.58 格、左灯 2.55 格）。指明之后 86/96 盏正好 2.000 格。
		     这个偏移是**量出来的**（`railSampleShift()`，众数），不是写死的 0.5。
		  ⑤ **站台**（用户 2026-09-16："先把站台读取出来……绘制进地图中，用白色的大写I字型线段在站台侧
		     表示站，然后字体排列方向与站的轨道平行"）—— 压在最上面：它是"站"，要比灯更显眼，
		     而且站名/台号是文字，被别的图层盖住就没意义了。
		未用：`BlockSectionsLayer`（L2 每方向一条、0.4 格、两侧错开）—— 1 格宽的总区间带会把它整条盖住，
		所以从页面撤下了；真要"总区间当主角 + 两侧细条"就把它调窄加回来（文件与用例都还在）。
	-->
	<MapFrame>
		<SectionTrackLayer/>
		<TotalSectionsLayer/>
		<SectionCutPointsLayer/>
		<SignalLayer anchor-frame="rail-sample"/>
		<PlatformsLayer/>
	</MapFrame>
</template>
