import type {InjectionKey, Ref} from "vue";
import type {Camera} from "@/domain/camera";

/**
 * 摄像机的注入键。
 *
 * <p>轨道层与节点层都需要把世界坐标投影到屏幕，它们必须用**同一份**摄像机。
 * 用 `provide/inject` 而不是逐层传 prop：少一层中转就少一次"传丢了"的机会——
 * 上一版把比例从视口经父组件传到节点，就在这一跳上丢过一次（节点按过期比例渲染成 0.3px）。</p>
 */
export const CAMERA: InjectionKey<Ref<Camera>> = Symbol("mmtr-camera");
