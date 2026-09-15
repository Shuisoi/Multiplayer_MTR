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

/**
 * 图标缩放比的注入键（信号灯 / 道岔菱形用）。
 *
 * <p>图标是屏幕像素画的（灯 20 px、菱形 23 px），位置随相机走、**尺寸却不变** —— 于是把地图缩小
 * （或全览）时它们相对世界越来越大，站场里几十盏灯挤成一片、把轨与区间都遮住
 * （用户 2026-09-15："信号灯和道岔的图标在缩小时不会一起跟着缩小"）。所以图标要拿到"现在缩小了多少"
 * 并跟着缩。</p>
 *
 * <p>与 {@link CAMERA} 同一个理由用 `provide/inject`：这个值要跨层（画布 → 灯层/道岔层 → 图标），
 * 逐层传 prop 迟早会在某一跳上丢。缺省 1（不缩）—— 拿不到就当作没有这个功能，不报错。</p>
 */
export const ICON_SCALE: InjectionKey<Ref<number>> = Symbol("mmtr-icon-scale");
