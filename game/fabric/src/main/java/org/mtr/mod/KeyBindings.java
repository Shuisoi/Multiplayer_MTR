package org.mtr.mod;

import org.lwjgl.glfw.GLFW;
import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mod.generated.lang.TranslationProvider;

public final class KeyBindings {

	static {
		LIFT_MENU = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_LIFT_MENU.key, GLFW.GLFW_KEY_Z, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		TRAIN_ACCELERATE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_TRAIN_ACCELERATE.key, GLFW.GLFW_KEY_UP, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		TRAIN_BRAKE = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_TRAIN_BRAKE.key, GLFW.GLFW_KEY_DOWN, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		TRAIN_TOGGLE_DOORS = InitClient.REGISTRY_CLIENT.registerKeyBinding(TranslationProvider.KEY_MTR_TRAIN_TOGGLE_DOORS.key, GLFW.GLFW_KEY_R, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// MMTR separated controls (raw keys until translations are added)
		MMTR_REVERSER_UP = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.reverser_up", GLFW.GLFW_KEY_LEFT, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_REVERSER_DOWN = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.reverser_down", GLFW.GLFW_KEY_RIGHT, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_BRAKE_APPLY = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.brake_apply", GLFW.GLFW_KEY_SEMICOLON, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_BRAKE_RELEASE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.brake_release", GLFW.GLFW_KEY_APOSTROPHE, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// B7.6c: press F to enter/leave the cab (classic "enter vehicle" key).
		// G (not F): F is vanilla's "swap item with offhand", which the crew hits constantly in the cab.
		MMTR_CAB_INTERACT = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.cab_interact", GLFW.GLFW_KEY_G, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// C7: aim at a train and press K to couple onto it / cut a coupler in front of the aimed car.
		MMTR_COUPLE = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.couple", GLFW.GLFW_KEY_K, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// A3: AWS point-warning acknowledge (the yellow/black cancel button on a real desk). An
		// unacknowledged warning becomes a SPAD emergency stop after ~2.5 s, so this key is part of
		// the driver workflow, not an optional extra.
		MMTR_AWS_ACK = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.aws_ack", GLFW.GLFW_KEY_H, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		// B7.6h: per-side door keys in the cab (rail practice: open only the platform side).
		MMTR_DOOR_LEFT = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.door_left", GLFW.GLFW_KEY_Y, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
		MMTR_DOOR_RIGHT = InitClient.REGISTRY_CLIENT.registerKeyBinding("key.mmtr.door_right", GLFW.GLFW_KEY_U, TranslationProvider.CATEGORY_MTR_KEYBINDING.key);
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
	public static final KeyBinding TRAIN_ACCELERATE;
	public static final KeyBinding TRAIN_BRAKE;
	public static final KeyBinding TRAIN_TOGGLE_DOORS;
	public static final KeyBinding MMTR_REVERSER_UP;
	public static final KeyBinding MMTR_REVERSER_DOWN;
	public static final KeyBinding MMTR_BRAKE_APPLY;
	public static final KeyBinding MMTR_BRAKE_RELEASE;
	public static final KeyBinding MMTR_CAB_INTERACT;
	/** C7: the coupling interaction key. */
	public static final KeyBinding MMTR_COUPLE;
	/** A3: the AWS acknowledge (cancel) key. */
	public static final KeyBinding MMTR_AWS_ACK;
	public static final KeyBinding MMTR_DOOR_LEFT;
	public static final KeyBinding MMTR_DOOR_RIGHT;
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