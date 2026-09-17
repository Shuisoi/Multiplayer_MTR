package org.mtr.mod;

import org.lwjgl.glfw.GLFW;
import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mod.generated.lang.TranslationProvider;

public final class KeyBindings {

	static {
		LIFT_MENU = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_LIFT_MENU.key, GLFW.GLFW_KEY_Z, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		/*
		 * ⚠️ The train-driving key set (accelerate / brake / toggle doors / reverser / brake apply+release /
		 * AWS acknowledge / per-side door keys / cab interact) is GONE, together with the riding layer that
		 * consumed it (notes/185). They are deleted from here rather than left registered, because a
		 * registered key with no reader still shows up in the controls menu and silently does nothing -
		 * the worst of both worlds while the driving layer is rebuilt.
		 *
		 * MTR's own TRAIN_* bindings are deleted with them for the same reason: their only reader was the
		 * same tick. Rebuild steps B2/B5/B6 bring back the ones the new ride session needs.
		 */
		// MMTR wiper: one key cycles 关 / 慢 / 快 while holding a cab. Deliberately NOT the rain's
		// business - the driver decides when to wipe, exactly like the real stalk.
		//
		// (It is `static` in MmtrWindshield.tick() only for now: the "am I in a cab" gate went with the
		// deleted cab interaction, so the stalk reports "需要先坐上驾驶位" until rebuild step B2.)
		MMTR_WIPER = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.wiper", GLFW.GLFW_KEY_J, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// C7: aim at a train and press K to couple onto it / cut a coupler in front of the aimed car.
		MMTR_COUPLE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.couple", GLFW.GLFW_KEY_K, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
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
	/** C7: the coupling interaction key. */
	public static final KeyBinding MMTR_COUPLE;
	/** One key, three positions: the windshield wiper stalk (关 / 慢 / 快). */
	public static final KeyBinding MMTR_WIPER;
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