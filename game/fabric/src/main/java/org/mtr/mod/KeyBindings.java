package org.mtr.mod;

import org.lwjgl.glfw.GLFW;
import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mod.generated.lang.TranslationProvider;

public final class KeyBindings {

	static {
		LIFT_MENU = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_LIFT_MENU.key, GLFW.GLFW_KEY_Z, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		/*
		 * ⚠️ Most of the train-driving key set (accelerate / brake / toggle doors / reverser / brake
		 * apply+release / AWS acknowledge / per-side door keys) is GONE, together with the riding layer
		 * that consumed it (notes/185). They are deleted from here rather than left registered, because a
		 * registered key with no reader still shows up in the controls menu and silently does nothing -
		 * the worst of both worlds while the driving layer is rebuilt.
		 *
		 * MTR's own TRAIN_* bindings are deleted with them for the same reason: their only reader was the
		 * same tick. Rebuild steps B5/B6 bring back the ones the new ride session needs.
		 */
		// B2: the cab interaction key. Aim at a driver's door ({@code mmtr_cabdoor}) and press it to take
		// that cab (key in, camera onto the seat), press it again to give the cab back. Restored
		// 2026-09-19 with the interaction class it drives - see MmtrCabInteraction.
		MMTR_CAB_INTERACT = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.cab", GLFW.GLFW_KEY_G, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// MMTR wiper: one key cycles 关 / 慢 / 快 while holding a cab. Deliberately NOT the rain's
		// business - the driver decides when to wipe, exactly like the real stalk.
		MMTR_WIPER = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.wiper", GLFW.GLFW_KEY_J, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// C7: aim at a train and press K to couple onto it / cut a coupler in front of the aimed car.
		MMTR_COUPLE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.couple", GLFW.GLFW_KEY_K, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		/*
		 * **计划内接管**：坐在司机位上按一下，把本车当前挂着的作业单接过来（再按一下还回去）。
		 *
		 * 为什么要有这个键：接管是**司机自己的决定**，不该要求他切出去到网页指令栏敲一行。
		 * 引擎侧的闸门（车必须停稳）照旧 —— 车在动时按键会被如实拒绝，并在动作栏说清原因。
		 */
		MMTR_TASK = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.task", GLFW.GLFW_KEY_B, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		/*
		 * **子任务的双向确认**：司机按下它 = "我这边确认这一步"（上行那一半）。
		 *
		 * 客户端也会在**看到引擎把某一条判为完成**时自动回一次确认（"我显示的和你判的一致"），
		 * 所以这个键是**人工追加确认**用的，不是链能否往下的必要条件 —— 链的推进始终由引擎的观测说了算
		 * （车停没停稳、门开没开）。
		 */
		MMTR_TASK_CONFIRM = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.task.confirm", GLFW.GLFW_KEY_N, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		/*
		 * **司机的车门键**（用户口径"按键开门 … 关门"）。
		 *
		 * Y = 本列车两侧门一起开/关（站台作业最常用的一下）；U = 只动右侧（靠站台那一侧的日常做法）。
		 * 站在站台上瞄准任意一节车的门按 Y 也有效（引擎侧 {@code doors} 指令本来就不要求驾驶权）。
		 */
		MMTR_DOORS = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.doors", GLFW.GLFW_KEY_Y, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_DOORS_SIDE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.doors.side", GLFW.GLFW_KEY_U, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		/*
		 * 三手柄机车（BR101）的驾驶输入，见 docs/01-设计/驾驶输入与控制模型.md §7。
		 *
		 * 油门手柄是一根**双向**手柄：↑ 往牵引侧、↓ 往电阻制动侧，中央 = 关闭；1% 一档，长按会扫。
		 * 制动手柄是离散的 11 个位置（运行/1A/1B/2…8/EB），; 施加、' 缓解，一直按到 EB 就是紧急。
		 * 定速巡航（AFB）暂定数字键 9 / 0（−5 / +5 km/h），这是用户本轮指定的键位。
		 * 换向器沿用 ← →（引擎红线：只在停稳时可动）。
		 */
		MMTR_DRIVE_TRACTION = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.drive.traction", GLFW.GLFW_KEY_UP, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_DRIVE_ELECTRIC_BRAKE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.drive.electric_brake", GLFW.GLFW_KEY_DOWN, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_BRAKE_APPLY = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.brake.apply", GLFW.GLFW_KEY_SEMICOLON, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_BRAKE_RELEASE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.brake.release", GLFW.GLFW_KEY_APOSTROPHE, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_AFB_DOWN = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.afb.down", GLFW.GLFW_KEY_9, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_AFB_UP = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.afb.up", GLFW.GLFW_KEY_0, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_REVERSER_FORWARD = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.reverser.forward", GLFW.GLFW_KEY_RIGHT, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_REVERSER_BACK = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.reverser.back", GLFW.GLFW_KEY_LEFT, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_1_NEGATIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_1_NEGATIVE.key, GLFW.GLFW_KEY_KP_4, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_2_NEGATIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_2_NEGATIVE.key, GLFW.GLFW_KEY_KP_5, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_3_NEGATIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_3_NEGATIVE.key, GLFW.GLFW_KEY_KP_6, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_1_POSITIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_1_POSITIVE.key, GLFW.GLFW_KEY_KP_7, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_2_POSITIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_2_POSITIVE.key, GLFW.GLFW_KEY_KP_8, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_3_POSITIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_3_POSITIVE.key, GLFW.GLFW_KEY_KP_9, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_ROTATE_CATEGORY_NEGATIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_CYCLE_NEGATIVE.key, GLFW.GLFW_KEY_KP_SUBTRACT, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		DEBUG_ROTATE_CATEGORY_POSITIVE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_DEBUG_CYCLE_POSITIVE.key, GLFW.GLFW_KEY_KP_ADD, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
	}

	public static final KeyBinding LIFT_MENU;
	/** B2: take / give back the cab you are aiming at. */
	public static final KeyBinding MMTR_CAB_INTERACT;
	/** C7: the coupling interaction key. */
	public static final KeyBinding MMTR_COUPLE;
	/** 计划内接管：把本车的作业单接过来 / 还回去（B）。 */
	public static final KeyBinding MMTR_TASK;
	/** 子任务确认（N）：司机对当前子任务清单的人工确认（双向确认的上行）。 */
	public static final KeyBinding MMTR_TASK_CONFIRM;
	/** 司机车门键（Y）：本列车两侧门一起开/关。 */
	public static final KeyBinding MMTR_DOORS;
	/** 司机车门键（U）：只动右侧门。 */
	public static final KeyBinding MMTR_DOORS_SIDE;
	/** One key, three positions: the windshield wiper stalk (关 / 慢 / 快). */
	public static final KeyBinding MMTR_WIPER;
	/** 三手柄：油门手柄往牵引侧（+1%，长按扫）。 */
	public static final KeyBinding MMTR_DRIVE_TRACTION;
	/** 三手柄：油门手柄往电阻制动侧（−1%，长按扫）。 */
	public static final KeyBinding MMTR_DRIVE_ELECTRIC_BRAKE;
	/** 三手柄：气制动 +1 档（施加，一直按到 EB）。 */
	public static final KeyBinding MMTR_BRAKE_APPLY;
	/** 三手柄：气制动 −1 档（缓解，最低回"运行"位）。 */
	public static final KeyBinding MMTR_BRAKE_RELEASE;
	/** 三手柄：定速巡航 −5 km/h（到 0 = 关闭）。 */
	public static final KeyBinding MMTR_AFB_DOWN;
	/** 三手柄：定速巡航 +5 km/h（上限 160）。 */
	public static final KeyBinding MMTR_AFB_UP;
	/** 换向器前进（引擎红线：只在停稳时可动）。 */
	public static final KeyBinding MMTR_REVERSER_FORWARD;
	/** 换向器后退。 */
	public static final KeyBinding MMTR_REVERSER_BACK;
	public static final KeyBinding DEBUG_1_NEGATIVE;
	public static final KeyBinding DEBUG_2_NEGATIVE;
	public static final KeyBinding DEBUG_3_NEGATIVE;
	public static final KeyBinding DEBUG_1_POSITIVE;
	public static final KeyBinding DEBUG_2_POSITIVE;
	public static final KeyBinding DEBUG_3_POSITIVE;
	public static final KeyBinding DEBUG_ROTATE_CATEGORY_NEGATIVE;
	public static final KeyBinding DEBUG_ROTATE_CATEGORY_POSITIVE;

	public static void init() {
		Init.LOGGER.info("Registering Minecraft Transit Railway key bindings");
	}
}