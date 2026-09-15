import {computed, inject, provide} from "vue";
import type {ComputedRef, InjectionKey, Ref} from "vue";
import type {Camera} from "@/domain/camera";

/*
 * ============================ 地图上下文（跨层注入） ============================
 *
 * 名字是 `mapContext` 而不是 `mapInject`：这个文件对外只有**两个读口**（`useCamera` / `useZoomRatio`）
 * 和一个**写口**（`provideMapContext`），注入键本身是模块私有的 —— 见下面"为什么不导出注入键"。
 */

/**
 * 摄像机的注入键（**模块私有**）。
 *
 * <p>轨道层、节点层、区间层都要把世界坐标投影到屏幕，它们必须用**同一份**摄像机。
 * 用 `provide/inject` 而不是逐层传 prop：少一层中转就少一次"传丢了"的机会——
 * 上一版把比例从视口经父组件传到节点，就在这一跳上丢过一次（节点按过期比例渲染成 0.3px）。</p>
 */
const CAMERA: InjectionKey<Ref<Camera>> = Symbol("mmtr-camera");

/**
 * 缩放倍率的注入键（**模块私有**，1 = 正好取景）。
 *
 * <p>元素要按它把"规格尺寸"换算成当前屏幕像素（见 `domain/mapElements.ts#mapScale`）：
 * 口径是"世界不动、动的是摄像机"，所以规格值（例如 6× 下 8 px）要跟着倍率一起变。</p>
 */
const ZOOM_RATIO: InjectionKey<Ref<number>> = Symbol("mmtr-zoom-ratio");

/**
 * **取景基准比例**的注入键（= `Camera.scale` 在 1× 时的值）。
 *
 * <p>用途只有一个：把"屏幕像素"的规格折算成**世界单位**。设了 `viewBox` 之后，线宽的单位是世界单位，
 * 而规格是"6× 时 6 px、并随倍率缩放"，两者之间的换算需要"1× 时的比例"：
 * {@code 世界宽度 = 规格 × 6 / 倍率 / 基准比例}。</p>
 *
 * <p>为什么不让各层自己拿 `Camera.scale` 顶替：那个值是"当前视口像素/世界单位"，
 * 按它折算等于把线宽做成**屏幕固定**（1× 时 4 px 会变成 8.6 世界单位 = 17 格宽的轨，实测过）。
 * 基准比例是"最后一次取景得到的比例"，只有持有相机的那一层知道，所以它跟着相机一起注入。</p>
 */
const BASE_SCALE: InjectionKey<Ref<number>> = Symbol("mmtr-base-scale");

/*
 * ============================ 为什么注入键不导出 ============================
 *
 * 因为 `inject()` 的失败方式是**静默**的：它靠"当前组件实例"找提供者，而组件实例**只在 `setup()`
 * 期间**是确定的；在别处调用它并不报错，只是拿缺省值。
 *
 * <p>曾经五处都写着 {@code computed(() => inject(ZOOM_RATIO, undefined)?.value ?? 1)} ——
 * 看着无害，实际会这样翻车：那个 computed 被 `v-bind` 的样式副作用在 setup 之外首次求值，
 * `inject` 找不到提供者 → 静默返回 1 → **样式里的宽度永远停在全览倍率**。
 * 而同一个 computed 在渲染时求值又能拿到真值，所以 JS 里量到的数是对的 ——
 * 症状于是表现为"轨道线、区间线心不随缩放变粗，只有图标在长"，也就是"线和图标脱层"。
 * 实测数据：6× 下轨道线 0.667 px（应为 4.1 px），区间线心 1 px（应为 6.2 px）。</p>
 *
 * <p>不导出键，就没法再写那种"把 inject 塞进 computed"的写法：唯一的读口是在 `setup()` 里调一次的
 * 这两个函数，之后拿到的只是 ref，跟"当时是谁在求值"再没关系。</p>
 */

/** 把地图上下文交给子层。只在画布组件的 `setup()` 里调一次。 */
export function provideMapContext(camera: Ref<Camera>, zoomRatio: Ref<number>, baseScale: Ref<number>): void {
	provide(CAMERA, camera);
	provide(ZOOM_RATIO, zoomRatio);
	provide(BASE_SCALE, baseScale);
}

/**
 * 读**取景基准比例**（1× 时的"视口像素/世界单位"）。
 *
 * <p>**必须在 `setup()` 里调用**，理由与 {@link useZoomRatio} 同。</p>
 */
export function useBaseScale(): Ref<number> {
	const injected = inject(BASE_SCALE, undefined);
	if (injected === undefined) {
		throw new Error("useBaseScale() 必须在 provideMapContext() 的子树内、且必须在 setup() 里调用");
	}
	return injected;
}

/**
 * **规格像素 → 世界单位**的那个常量（`unitsPerPx` = 1 屏幕像素等于多少世界单位）。
 *
 * <p>它由**取景**校准一次：取景时"世界跨度 ÷ 视口跨度"就是它。之后整张图的尺寸都用它换算，
 * 于是**所有元素共用一处换算**，而"跟着相机缩放"是自动的（相机由外层承担）。</p>
 *
 * <p><b>不要**改成"1 / 当前相机比例"**：那会把缩放正好抵消掉 ⇒ 屏幕尺寸恒定
 * （实测过 1× 与 5.35× 都量到 8 px），那是"屏幕固定大小"，不是地图。这条踩过好几次，
 * 所以名字与注释都写死。</b></p>
 */
export function useUnitsPerPx(): ComputedRef<number> {
	const baseScale = useBaseScale();
	return computed(() => {
		const scale = baseScale.value;
		return scale > 0 ? 1 / scale : 1;
	});
}

/**
 * 读当前摄像机（世界坐标 → 屏幕全靠它）。
 *
 * <p>**必须在 `setup()` 里调用**：函数体内就是 `inject`，晚一步就静默拿不到（理由见上）。</p>
 */
export function useCamera(): Ref<Camera> {
	const injected = inject(CAMERA, undefined);
	if (injected === undefined) {
		throw new Error("useCamera() 必须在 provideMapContext() 的子树内、且必须在 setup() 里调用");
	}
	return injected;
}

/**
 * 读**当前相机倍率**（规格尺寸换算要用它，见 `domain/mapElements.ts#mapScale`）。
 *
 * <p>**必须在 `setup()` 里调用**，理由与 {@link useCamera} 同：setup 期间读一次、缓存住 ref，
 * 之后的求值只读这个 ref，不再依赖"当时是谁在求值"。缺省 1 只在**整块地图没接上下文**时出现
 * （那种情况不该发生，所以这里直接抛错，不再静默降级）。</p>
 */
export function useZoomRatio(): Ref<number> {
	const injected = inject(ZOOM_RATIO, undefined);
	if (injected === undefined) {
		throw new Error("useZoomRatio() 必须在 provideMapContext() 的子树内、且必须在 setup() 里调用");
	}
	return injected;
}

/*
 * 注：元素尺寸规格（6× 下图标 8 px、灯点 4 px、偏移 10/16 px…）**不在这里** —— 它们在
 * `domain/mapElements.ts` 的表里，与"世界坐标怎么落到屏幕上"的规则放在一起。
 * 这个文件只管跨层注入（读口/写口），不放数值。
 */
