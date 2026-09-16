<script setup lang="ts">
/*
 * 地图页 = **框架 + 图层 + 浮层**。
 *
 * <ul>
 *   <li>框架（`map/MapFrame.vue`）：舞台、SVG、相机（svg-pan-zoom）、内建坐标系；</li>
 *   <li>图层（`map/layers/*.vue`）：各自取数、各自往画布坐标系里放东西（**在相机里**，随缩放变化）；</li>
 *   <li>浮层（默认插槽之外的那个 `#overlay`）：HTML 界面（**在相机外**，不随缩放变化）——
 *       它只能由框架的浮层插槽渲染，因为图层那边在 SVG 命名空间里画不出 HTML。</li>
 * </ul>
 *
 * <p><b>加一个图层</b>：在 `map/layers/` 新建组件，用 `useMapContext()` 拿 `project()`，
 * 然后在这里加一行 —— **写的顺序就是叠放顺序**（先写的在下面）。图层之间不互相依赖，
 * 也不碰相机：框架已经把"画多大、摆在哪、怎么缩放"三件事分开了。</p>
 */
import MapFrame from "./map/MapFrame.vue";
import MapActions from "./map/layers/MapActions.vue";
import NodeMenu from "./map/layers/NodeMenu.vue";
import RailNodesLayer from "./map/layers/RailNodesLayer.vue";
import SignalLayer from "./map/layers/SignalLayer.vue";
import TrainCard from "./map/layers/TrainCard.vue";
import TrainRouteLayer from "./map/layers/TrainRouteLayer.vue";
import VehiclesLayer from "./map/layers/VehiclesLayer.vue";
import {provideLiveFeeds} from "./map/liveFeeds";

/*
 * 这一页**会变**的数据：道岔（轨的淡出）、信号灯（显示）、车辆（位置）。
 * 一个节拍（`LIVE_REFRESH_MILLIS`）里每路只取一次，三个图层共用（见 `liveFeeds.ts`）——
 * 用户 2026-09-16："所有可变的都需要 0.5s 一次变动"。
 */
provideLiveFeeds(["points", "signals", "trains"]);
</script>

<template>
	<MapFrame>
		<!-- 基础图层：线网（轨道线 + 节点）。它同时把世界范围报给框架当锚点。 -->
		<RailNodesLayer/>
		<!-- 进路叠加层：点中一列车之后，把它当前进路要走的轨用强调色压上去（在灯与车**下面**）。 -->
		<TrainRouteLayer/>
		<!-- 信号灯图层：节点旁的状态图标 + `^` 方向。写在基础图层之后 = 画在它上面。 -->
		<SignalLayer/>
		<!-- 车辆图层：**一节车一个记号**（形状见 vehicleMarkerShapes），压在它此刻所在的那根轨上、朝着行车方向。 -->
		<VehiclesLayer/>
		<!-- 浮层：节点菜单（悬停坐标 / 扳岔）、车辆任务卡片（点中一列车）、右侧动作按钮条。 -->
		<template #overlay>
			<NodeMenu/>
			<TrainCard/>
			<!--
				右侧动作按钮条（用户 2026-09-16："UI 右侧设计按钮"）：每个按钮 = 引擎指令通道里的一条指令。
				只放"按一下要对世界做一件事"的动作，不放读数（读数在悬浮提示/卡片里）。
			-->
			<MapActions/>
		</template>
	</MapFrame>
</template>
