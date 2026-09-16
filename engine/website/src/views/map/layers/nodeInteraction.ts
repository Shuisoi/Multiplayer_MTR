import {computed, ref, type ComputedRef} from "vue";
import {turnoutActions, type MapNode, type TurnoutAction} from "@/domain/MapNode";

/*
 * 节点交互的**共享状态**：图层（`RailNodesLayer.vue`）负责命中判定并往这里写，
 * 浮层（`NodeMenu.vue`）负责显示与动作。
 *
 * <h2>为什么非要拆成两个组件</h2>
 * <p>图层的模板渲染在 SVG 的 `<g>` 里面 —— 那里只能放 SVG 元素。想在里面写 `<div>`：</p>
 * <ul>
 *   <li>直接写 → 被当成未知 SVG 元素，**不渲染**；</li>
 *   <li>用 `<Teleport to="body">` 也不行：Teleport **会把 SVG 命名空间一起带过去**，
 *       落地的仍是 SVG 元素（实测：`namespaceURI` 是 svg、`getBoundingClientRect()` 是 0×0、
 *       `button.disabled` 是 undefined），看上去"DOM 里有"，实际屏幕上什么都没有。</li>
 * </ul>
 * <p>所以浮层由**框架的 HTML 浮层插槽**渲染（`MapFrame` 的 `#overlay`，在 SVG 外面），
 * 图层只管把"鼠标下面是哪个节点"写进这里。两个组件共用这一份模块级状态 —— 地图页只有一个实例，
 * 真要支持多实例时再改成 provide/inject。</p>
 */

/** 鼠标下的节点（悬停显示坐标用）。 */
export const hovered = ref<MapNode | null>(null);
/** 当前指针位置（client 坐标）：浮层跟着它走，所以完全不需要相机信息。 */
export const pointer = ref({x: 0, y: 0});
/** 打开着的菜单（节点 + 打开时的指针位置）。 */
export const menu = ref<{node: MapNode; x: number; y: number} | null>(null);
/** 正在下发扳岔（避免连点）。 */
export const busy = ref(false);

/** 当前菜单里能点的动作（普通节点为空 = 菜单只会有「复制坐标」）。 */
export const menuActions: ComputedRef<readonly TurnoutAction[]> = computed(
	() => menu.value ? turnoutActions(menu.value.node) : [],
);

/**
 * 图层注册进来的"重新取数并找回这个节点"钩子。
 *
 * <p>扳岔成功之后必须重画（道岔位置变了 → 禁止通行的那根出口线换了、菜单里的"当前位"也换了），
 * 而且**要回读才算数**（引擎那个接口不回传拒绝原因）。状态在这里、数据在图层里，所以由图层把钩子
 * 注册上来；没注册时返回 null（浮层仍然可用，只是不做回读核对）。</p>
 */
let reloadAndFind: ((key: string) => Promise<MapNode | null>) | null = null;

export function registerReload(hook: ((key: string) => Promise<MapNode | null>) | null): void {
	reloadAndFind = hook;
}

export async function reloadNode(key: string): Promise<MapNode | null> {
	return reloadAndFind === null ? null : await reloadAndFind(key);
}
