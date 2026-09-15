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
 * 缩放倍率的注入键（1 = 正好取景）。
 *
 * <p>元素要按它把"规格尺寸"换算成当前屏幕像素（见 `domain/mapElements.ts#mapScale`）：
 * 口径是"世界不动、动的是摄像机"，所以规格值（例如 6× 下 8 px）要跟着倍率一起变。</p>
 *
 * <p>与 {@link CAMERA} 同一个理由用 `provide/inject`：这个值要跨层（画布 → 各层 → 各元素），
 * 逐层传 prop 迟早会在某一跳上丢。缺省 1（拿不到就按 1 算，不报错）。</p>
 */
export const ZOOM_RATIO: InjectionKey<Ref<number>> = Symbol("mmtr-zoom-ratio");

/*
 * 注：元素尺寸规格（6× 下图标 8 px、灯点 4 px、偏移 10/16 px…）**不在这里** —— 它们在
 * `domain/mapElements.ts` 的表里，与"世界坐标怎么落到屏幕上"的规则放在一起。
 * 这个文件只管跨层注入（注入键），不放数值。
 */
