import {ref, type Ref} from "vue";

/*
 * **车辆交互的共享状态**：车辆图层（`layers/VehiclesLayer.vue`）做命中判定并往这里写，
 * 路线图叠加层（`layers/TrainRouteLayer.vue`）与浮层卡片（`layers/TrainCard.vue`）读它。
 *
 * <h2>为什么和节点那套一样要拆组件</h2>
 * <p>图层的模板渲染在 SVG 的 `<g>` 里，写不出能显示的 HTML（`Teleport` 也救不了：它会把 SVG
 * 命名空间一起带过去，详见 `layers/nodeInteraction.ts` 头部）。所以 SVG 里只画记号，
 * HTML 卡片由框架的 `#overlay` 插槽渲染，两边共用这一份模块级状态 —— 地图页只有一个实例，
 * 真要支持多实例时再改成 provide/inject（与 nodeInteraction 同一约定）。</p>
 *
 * <h2>选中是"按车"而不是"按节"</h2>
 * <p>用户点的是"这一列车"：一列 8 节编组的 8 个记号要**一起**高亮、卡片写一份任务、路线画一套。
 * 所以这里存 `vehicleId`，而不是某一节的 key。</p>
 */

/** 当前选中的车（`vehicleId`）；null = 没选。 */
export const selectedTrainId: Ref<string | null> = ref(null);

/** 鼠标下面压着哪列车（只用来换鼠标形状：手型 = 这里点得动）。 */
export const hoveredTrainId: Ref<string | null> = ref(null);

/** 选中/取消（点同一列车再点一次 = 取消；点空白 = 取消）。 */
export function selectTrain(vehicleId: string | null): void {
	selectedTrainId.value = vehicleId;
}

/*
 * **命中归谁**：车压在节点上时，点下去应当算"点车"。
 *
 * <p>两个图层各自挂在同一个舞台元素上做命中判定（节点层在自己的 `onPointerDown` 里开菜单）。
 * 谁先挂谁先收到事件是不可依赖的，所以做成**显式仲裁**：车辆层把自己的命中判定注册上来，
 * 节点层在开菜单之前先问一句"这里是不是压着一列车" —— 车画在节点上面，所以点车优先。</p>
 */
let vehicleHitTest: ((clientX: number, clientY: number) => boolean) | null = null;

/** 车辆层注册（卸载时传 null）。 */
export function registerVehicleHitTest(hook: ((clientX: number, clientY: number) => boolean) | null): void {
	vehicleHitTest = hook;
}

/** 这个屏幕位置上有没有车（节点层用它让路）。没注册时一律 false。 */
export function vehicleHitAt(clientX: number, clientY: number): boolean {
	return vehicleHitTest === null ? false : vehicleHitTest(clientX, clientY);
}
