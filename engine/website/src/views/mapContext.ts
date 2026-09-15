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

/*
 * 注：贴片尺寸（图标 8 px、灯点 4 px、偏移 10/16 px…）**不在这里** —— 它们在
 * `domain/mapElements.ts` 的规格表里，与"世界坐标怎么落到屏幕上"的规则放在一起。
 * 这个文件只管跨层注入（注入键），不放数值。
 */
