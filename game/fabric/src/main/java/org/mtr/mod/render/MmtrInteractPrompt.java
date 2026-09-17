package org.mtr.mod.render;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;

import javax.annotation.Nullable;

/**
 * The world-anchored interaction prompt: a black box with white text that follows the thing you can
 * interact with, e.g. {@code [G] 进入驾驶室1} hovering over a cab door.
 *
 * <h2>Why it is one place instead of a message per feature</h2>
 *
 * <p>Every interaction used to announce itself its own way - the cab key printed to the action bar, the
 * coupler had its own prompt, boarding printed "on board" - so a player standing next to a train got an
 * action-bar line that named one action and hid the rest, and had no way to see WHICH part of the train
 * the line was about. Drawing the prompt AT the target fixes both: it says what the target is and what
 * the key does, and it disappears when you look away.</p>
 *
 * <h2>How a world point becomes a screen point</h2>
 *
 * <p>The HUD pass has no access to the world matrices, but it does not need them. What is required is the
 * camera's position and orientation, and MTR exposes both, so the camera basis is rebuilt from yaw and
 * pitch and the point is projected by hand:</p>
 *
 * <pre>
 *   right   = normalize(cross(worldUp, forward))     // worldUp = (0,1,0)
 *   up      = cross(forward, right)                  // already unit if forward is
 *   camera  = (delta . right, delta . up, delta . forward)
 *   ndcX    = camera.x / (camera.z * m00)
 *   ndcY    = camera.y / (camera.z * m11)
 *   screenX = width/2  + ndcX * width/2
 *   screenY = height/2 - ndcY * height/2
 * </pre>
 *
 * <p>{@code m00} and {@code m11} are the diagonal terms of the game's own projection matrix (read
 * reflectively in {@link #projectionScale}), NOT a hand-derived {@code tan(fov/2)}: see that method for
 * why guessing at the FOV semantics is what produced a prompt that drifted towards the screen edges.</p>
 *
 * <p>Nothing is drawn when the point is behind the camera, which is why the {@code . forward} component
 * is tested first - a negative depth would otherwise project a mirror-image prompt onto the screen.</p>
 *
 * <h2>What it does not do yet</h2>
 *
 * <p>It DRAWS every prompt but only some of them DO anything: the actions that used to be wired to these
 * keys were deleted with the riding layer (notes/185). Each {@link Action} says whether it is live, and
 * the label is drawn dimmed when it is not, so the layer can be built out one action at a time without
 * ever showing a key that silently does nothing. See {@link #ACTION_ENTER_CAB}.</p>
 */
public final class MmtrInteractPrompt {

	private MmtrInteractPrompt() {
	}

	/** How far away (blocks) an interactable may be and still be offered. */
	private static final double REACH_M = 6.0;
	/**
	 * How far off the crosshair (degrees) the player must be looking for a prompt to show. Generous
	 * compared with a "which one am I aiming at" test, because several prompts may be on screen at once -
	 * this is a cone around the view direction, not a pick.
	 */
	private static final double MAX_VIEW_ANGLE_DEGREES = 50;
	/** Screen-space margin (pixels) outside which a projected prompt is dropped. */
	private static final double OFF_SCREEN_MARGIN_PX = 64;
	/**
	 * How far above the anchor (blocks) the label floats. Zero = exactly on the anchor, which for a
	 * {@code mmtr_cabdoor} anchor is the CENTRE of the door - what the user asked for. Raise it if the
	 * label ends up covering something it should not.
	 */
	private static final double LABEL_LIFT_M = 0.0;
	/** Show the key in brackets and the action after it. */
	private static final int BACKGROUND_COLOR = 0xC0000000;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int TEXT_COLOR_INACTIVE = 0x80FFFFFF;
	private static final int PADDING = 3;

	// ---- the action table -------------------------------------------------------------------------

	/**
	 * One thing the player can do, and the key that does it.
	 *
	 * @param live false = the prompt is drawn but dimmed, because the code behind the key is not written
	 *             yet. Set a flag to true the moment its action is implemented; never draw a key as if it
	 *             worked when it does not.
	 */
	private record Action(String keyLabel, String label, boolean live) {
	}

	/**
	 * Entering a cab. <b>NOT LIVE YET</b>: deleting the riding layer (notes/185) removed the cab
	 * interaction that this key drove, so the prompt is shown dimmed until rebuild step B2 restores it.
	 */
	private static final Action ACTION_ENTER_CAB = new Action(keyLabel("MMTR_CAB_INTERACT", "G"), "进入驾驶室", false);

	/** Doors. The per-side keys went with the riding layer for the same reason. */
	private static final Action ACTION_DOORS = new Action(keyLabel("MMTR_DOOR_LEFT", "Y"), "开门", false);

	/** Coupling is LIVE: {@code MmtrCoupleInteraction} still handles the key. */
	private static final Action ACTION_COUPLE = new Action(keyLabel("MMTR_COUPLE", "K"), "连挂", true);

	// ---- entry point ------------------------------------------------------------------------------

	/** Registered once, from {@code InitClient}, on the GUI rendering hook. */
	public static void render(GraphicsHolder graphicsHolder) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayerEntity player = minecraftClient.getPlayerMapped();
		if (player == null || minecraftClient.getCurrentScreenMapped() != null) {
			return;
		}
		logRegisteredOnce();

		final ObjectArrayList<Candidate> candidates = collect(player);
		if (candidates.isEmpty()) {
			return;
		}
		// The projection constants are per-frame, not per-prompt, and reading them means reflection into
		// the renderer - so they are resolved once here rather than once per label.
		refreshProjection();

		final Window window = minecraftClient.getWindow();
		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		graphicsHolder.push();
		// The HUD pass is already in screen space; a reset avoids inheriting a scale from whatever drew
		// before us (MTR's own overlays translate/scale freely).
		graphicsHolder.translate(0, 0, 0);

		for (final Candidate candidate : candidates) {
			drawCandidate(graphicsHolder, guiDrawing, window, candidate);
		}

		graphicsHolder.pop();
	}

	// ---- collecting -------------------------------------------------------------------------------

	/**
	 * Every interactable within reach and near the view direction, nearest first.
	 *
	 * <p>Deliberately a LIST rather than a single best hit. "Which one am I aiming at" is the right
	 * question for an action, but the wrong one for a prompt: the player wants to see that there is a cab
	 * door here AND a coupler there, and then choose.</p>
	 */
	private static ObjectArrayList<Candidate> collect(ClientPlayerEntity player) {
		final ObjectArrayList<Candidate> result = new ObjectArrayList<>();
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final org.mtr.mapping.holder.Camera camera = minecraftClient.getGameRendererMapped().getCamera();
		final double cameraX = camera.getPos().getXMapped();
		final double cameraY = camera.getPos().getYMapped();
		final double cameraZ = camera.getPos().getZMapped();
		final double yaw = Math.toRadians(camera.getYaw());
		final double pitch = Math.toRadians(camera.getPitch());
		final double cosPitch = Math.cos(pitch);
		final double lookX = -Math.sin(yaw) * cosPitch;
		final double lookY = -Math.sin(pitch);
		final double lookZ = Math.cos(yaw) * cosPitch;
		final double cosMaxAngle = Math.cos(Math.toRadians(MAX_VIEW_ANGLE_DEGREES));

		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final ObjectArrayList<CarTransform> cars = carTransforms(vehicle);
			for (int carNumber = 0; carNumber < cars.size(); carNumber++) {
				final CarTransform car = cars.get(carNumber);
				final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(car.vehicleId);
				if (anchors.isEmpty()) {
					continue;
				}
				for (final Anchor anchor : anchors) {
					final Action action = actionFor(anchor);
					if (action == null) {
						continue;
					}
					final Vector world = car.rotation.transformForwards(anchor.position, Vector::rotateX, Vector::rotateY, Vector::add);
					final double dx = world.x() - cameraX;
					final double dy = world.y() - cameraY;
					final double dz = world.z() - cameraZ;
					final double distanceSquared = dx * dx + dy * dy + dz * dz;
					if (distanceSquared > REACH_M * REACH_M || distanceSquared < 1.0E-4) {
						continue;
					}
					final double distance = Math.sqrt(distanceSquared);
					if ((dx * lookX + dy * lookY + dz * lookZ) / distance < cosMaxAngle) {
						continue;
					}
					result.add(new Candidate(
							world.x(), world.y() + LABEL_LIFT_M, world.z(),
							distanceSquared,
							labelFor(anchor, action),
							action
					));
				}
			}
		}

		result.sort((a, b) -> Double.compare(a.distanceSquared, b.distanceSquared));
		return result;
	}

	@Nullable
	private static Action actionFor(Anchor anchor) {
		switch (anchor.kind) {
			case CABDOOR:
				return ACTION_ENTER_CAB;
			case DOOR:
				return ACTION_DOORS;
			default:
				return null;
		}
	}

	/**
	 * The whole visible line, e.g. {@code [G] 进入驾驶室1}. The cab number comes from the anchor's
	 * {@code cab} field, which the packager fills from the anchor name's {@code _<n>} suffix - so a
	 * single-ended model says "1" and a double-ended loco says "1" and "2".
	 */
	private static String labelFor(Anchor anchor, Action action) {
		final String suffix = anchor.kind == MmtrVehicleAnchors.Kind.CABDOOR && anchor.cab > 0 ? String.valueOf(anchor.cab) : "";
		return "[" + action.keyLabel + "] " + action.label + suffix;
	}

	// ---- drawing ----------------------------------------------------------------------------------

	private static void drawCandidate(GraphicsHolder graphicsHolder, GuiDrawing guiDrawing, Window window, Candidate candidate) {
		final ScreenPoint point = project(window, candidate);
		if (point == null) {
			return;
		}
		logProjection(window, candidate, point);

		final String text = candidate.text;
		final int textWidth = GraphicsHolder.getTextWidth(text);
		final int halfWidth = textWidth / 2;
		final int textTop = point.y - IGui.TEXT_HEIGHT / 2;

		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(
				point.x - halfWidth - PADDING,
				textTop - PADDING,
				point.x + halfWidth + PADDING,
				textTop + IGui.TEXT_HEIGHT + PADDING,
				BACKGROUND_COLOR
		);
		guiDrawing.finishDrawingRectangle();

		graphicsHolder.drawText(text, point.x - halfWidth, textTop, candidate.action.live ? TEXT_COLOR : TEXT_COLOR_INACTIVE, true, GraphicsHolder.getDefaultLight());
	}

	/**
	 * Throttled diagnostic, so "the label is a bit off the door" can be answered with numbers.
	 *
	 * <p>Repeats every second rather than once: a single line is easy to miss in the log and impossible to
	 * correlate with "and now I moved the camera". Everything needed to recompute the projection by hand is
	 * printed, so an unexplained offset can be arithmetic instead of another round of guessing.</p>
	 */
	private static long lastProjectionLogMillis;

	/**
	 * One line proving the layer is alive, printed the first time it actually runs.
	 *
	 * <p>Without it, "I see nothing in the log" has two indistinguishable causes: the layer never ran
	 * (hook not registered / no player), or it ran and found nothing to draw. This separates them.</p>
	 */
	private static boolean registrationLogged;

	private static void logRegisteredOnce() {
		if (registrationLogged) {
			return;
		}
		registrationLogged = true;
		org.mtr.mod.Init.LOGGER.info("[MMTR-PROMPT] 交互提示层已运行（GUI 钩子生效；{} 台车已知）",
				MinecraftClientData.getInstance().vehicles.size());
	}

	private static void logProjection(Window window, Candidate candidate, ScreenPoint point) {
		final long now = System.currentTimeMillis();
		if (now - lastProjectionLogMillis < 1000) {
			return;
		}
		lastProjectionLogMillis = now;
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final org.mtr.mapping.holder.Camera camera = minecraftClient.getGameRendererMapped().getCamera();
		final double dx = candidate.x - camera.getPos().getXMapped();
		final double dy = candidate.y - camera.getPos().getYMapped();
		final double dz = candidate.z - camera.getPos().getZMapped();
		org.mtr.mod.Init.LOGGER.info("[MMTR-PROMPT] {} 屏幕=({},{}) 中心=({},{}) 窗口={}x{} 投影={} 相机=({},{},{}) yaw={} pitch={} 目标=({},{},{}) 位移=({},{},{})",
				candidate.text, point.x, point.y, window.getScaledWidth() / 2, window.getScaledHeight() / 2,
				window.getScaledWidth(), window.getScaledHeight(), projectionSource(),
				round(camera.getPos().getXMapped()), round(camera.getPos().getYMapped()), round(camera.getPos().getZMapped()),
				Math.round(camera.getYaw() * 100) / 100.0, Math.round(camera.getPitch() * 100) / 100.0,
				round(candidate.x), round(candidate.y), round(candidate.z),
				round(dx), round(dy), round(dz));
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}

	/**
	 * Projects the candidate to screen pixels, or returns null when it cannot be shown.
	 *
	 * <p>See the class documentation for the derivation. The depth test comes first and uses the same
	 * forward vector the projection uses, so "behind me" and "off screen" cannot disagree.</p>
	 */
	@Nullable
	private static ScreenPoint project(Window window, Candidate candidate) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final org.mtr.mapping.holder.Camera camera = minecraftClient.getGameRendererMapped().getCamera();
		final double yaw = Math.toRadians(camera.getYaw());
		final double pitch = Math.toRadians(camera.getPitch());

		// Camera basis in world space.
		final double cosYaw = Math.cos(yaw);
		final double sinYaw = Math.sin(yaw);
		final double cosPitch = Math.cos(pitch);
		final double sinPitch = Math.sin(pitch);
		final double forwardX = -sinYaw * cosPitch;
		final double forwardY = -sinPitch;
		final double forwardZ = cosYaw * cosPitch;
		// right = normalize(cross((0,1,0), forward)) = normalize((forward.z, 0, -forward.x))
		double rightX = forwardZ;
		double rightZ = -forwardX;
		final double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
		if (rightLength < 1.0E-6) {
			// Looking straight up or down: any "right" is arbitrary and nothing horizontal is in view.
			return null;
		}
		rightX /= rightLength;
		rightZ /= rightLength;
		// up = cross(forward, right)
		final double upX = forwardY * rightZ;
		final double upY = forwardZ * rightX - forwardX * rightZ;
		final double upZ = -forwardY * rightX;

		final double deltaX = candidate.x - camera.getPos().getXMapped();
		final double deltaY = candidate.y - camera.getPos().getYMapped();
		final double deltaZ = candidate.z - camera.getPos().getZMapped();
		final double depth = deltaX * forwardX + deltaY * forwardY + deltaZ * forwardZ;
		if (depth < 0.1) {
			return null;
		}

		final double cameraX = deltaX * rightX + deltaZ * rightZ;
		final double cameraY = deltaX * upX + deltaY * upY + deltaZ * upZ;

		// Magnification: the per-frame values refreshed in render(). See refreshProjection().
		final double scaleX = scaleXForFrame;
		final double scaleY = scaleYForFrame;
		final double width = window.getScaledWidth();
		final double height = window.getScaledHeight();

		final double screenX = width / 2 + cameraX / (depth * scaleX) * (width / 2);
		final double screenY = height / 2 - cameraY / (depth * scaleY) * (height / 2);
		if (screenX < -OFF_SCREEN_MARGIN_PX || screenX > width + OFF_SCREEN_MARGIN_PX
				|| screenY < -OFF_SCREEN_MARGIN_PX || screenY > height + OFF_SCREEN_MARGIN_PX) {
			return null;
		}
		return new ScreenPoint((int) Math.round(screenX), (int) Math.round(screenY));
	}

	// ---- projection -------------------------------------------------------------------------------

	/**
	 * The projection's two diagonal scale terms, read from the matrix the game actually renders with.
	 *
	 * <p>This exists because deriving the magnification by hand from an FOV number is a guess about
	 * semantics - which FOV, which aspect, GUI scale or framebuffer scale - and a wrong guess has one
	 * signature: the prompt is right at the screen centre and drifts further off towards the edges. That
	 * is exactly the bug this replaces, so the number is now taken from
	 * {@code GameRenderer.getBasicProjectionMatrix(fov)} instead of reconstructed.</p>
	 *
	 * <p>{@code Matrix4f} is column-major and {@code perspective()} writes its diagonal as
	 * {@code m00 = m11 / aspect} and {@code m11 = 1 / tan(fov/2)}. Those two are already in NDC-per-unit,
	 * so the pixel transform is {@code half + (camera / depth / m) * half}.</p>
	 *
	 * @return {m00, m11} in framebuffer pixels per NDC unit, or null when it could not be read
	 */
	@Nullable
	private static double[] projectionScale(MinecraftClient minecraftClient) {
		try {
			final Object gameRenderer = minecraftClient.getGameRendererMapped().data;
			final Object camera = minecraftClient.getGameRendererMapped().getCamera().data;

			// Find the methods BY SHAPE, not by an exact parameter-type list. The first version asked for
			// getBasicProjectionMatrix(float) and getFov(Camera, float, boolean) exactly, and BOTH lookups
			// failed - seen in the log as "投影=FOV 重建" - because MC declares them with a double FOV and a
			// float tickDelta, or similar. Matching on arity and number-convertibility survives that.
			final java.lang.reflect.Method fovMethod = findMethod(gameRenderer, "getFov", 3, 1);
			final java.lang.reflect.Method projectionMethod = findMethod(gameRenderer, "getBasicProjectionMatrix", 1, 1);
			if (fovMethod == null || projectionMethod == null) {
				lastProjectionSource = "FOV 重建（找不到 " + (fovMethod == null ? "getFov" : "getBasicProjectionMatrix") + "）";
				return null;
			}

			final float fov = ((Number) fovMethod.invoke(gameRenderer, camera, 1.0F, true)).floatValue();
			// The FOV parameter may be declared float or double; convert for whichever it is.
			final Object matrix = projectionMethod.getParameterTypes()[0] == double.class
					? projectionMethod.invoke(gameRenderer, (double) fov)
					: projectionMethod.invoke(gameRenderer, fov);
			final float m00 = matrix.getClass().getField("m00").getFloat(matrix);
			final float m11 = matrix.getClass().getField("m11").getFloat(matrix);
			if (m00 <= 0 || m11 <= 0) {
				lastProjectionSource = "FOV 重建（矩阵对角为 " + m00 + "," + m11 + "）";
				return null;
			}
			// The matrix is in NDC-per-unit, so no aspect correction belongs here. (Multiplying by the
			// aspect - as an earlier version of this method did - scales the horizontal axis by ~1.78 and
			// is exactly the "worse towards the screen edges" error this method exists to remove.)
			lastProjectionSource = "矩阵 m00=" + Math.round(m00 * 1000) / 1000.0 + " m11=" + Math.round(m11 * 1000) / 1000.0;
			return new double[] {m00, m11};
		} catch (Exception e) {
			lastProjectionSource = "FOV 重建（" + e.getClass().getSimpleName() + "）";
			return null;
		}
	}

	/**
	 * Finds a method by name, parameter count, and how many of its parameters must accept a number
	 * (the rest are objects). Returns null when there is no unique match.
	 */
	@Nullable
	private static java.lang.reflect.Method findMethod(Object target, String name, int parameterCount, int numberParameterCount) {
		java.lang.reflect.Method found = null;
		for (final java.lang.reflect.Method method : target.getClass().getMethods()) {
			if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
				continue;
			}
			int numbers = 0;
			for (final Class<?> type : method.getParameterTypes()) {
				if (type == float.class || type == double.class || type == int.class || type == long.class) {
					numbers++;
				}
			}
			if (numbers != numberParameterCount) {
				continue;
			}
			if (found != null) {
				// Ambiguous (an overload we cannot choose between): refuse rather than guess.
				return null;
			}
			found = method;
		}
		if (found != null) {
			found.setAccessible(true);
		}
		return found;
	}


	private static String lastProjectionSource = "?";

	/** The two diagonal terms in force this frame, refreshed once per render pass. */
	private static double scaleXForFrame = 1;
	private static double scaleYForFrame = 1;

	/**
	 * Resolves the projection constants once per frame: the two NDC-per-unit diagonal terms and, for the
	 * diagnostic, where they came from.
	 *
	 * <p>Preference order matters. The game's own matrix is authoritative; the FOV reconstruction is a
	 * guess about which FOV and which aspect, and a wrong guess shows up as a prompt that is correct at the
	 * screen centre and drifts towards the edges - so the source is recorded and logged rather than
	 * silently chosen.</p>
	 */
	private static void refreshProjection() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final Window window = minecraftClient.getWindow();
		final double width = window.getScaledWidth();
		final double height = window.getScaledHeight();
		final double[] scale = projectionScale(minecraftClient);
		if (scale != null) {
			scaleXForFrame = scale[0];
			scaleYForFrame = scale[1];
		} else {
			// Reconstruct from the FOV. scaleY = 1/tan(fov/2); scaleX carries the aspect, because the
			// matrix's m00 is m11/aspect and the GUI is wider than it is tall.
			final double tanHalfFov = Math.tan(Math.toRadians(fovDegrees(minecraftClient)) / 2);
			scaleYForFrame = tanHalfFov;
			scaleXForFrame = tanHalfFov * width / height;
			lastProjectionSource = "FOV 重建";
		}
	}

	private static String projectionSource() {
		return lastProjectionSource + " scaleX=" + Math.round(scaleXForFrame * 10000) / 10000.0 + " scaleY=" + Math.round(scaleYForFrame * 10000) / 10000.0;
	}

	// ---- helpers ----------------------------------------------------------------------------------

	/**
	 * The label for a key binding, falling back to a literal key name.
	 *
	 * <p>The fallback is the point: several of these bindings were DELETED with the riding layer
	 * (notes/185), and {@code KeyBindings.<field>} would then be a compile error rather than a missing
	 * key. Reading them reflectively keeps the prompt table independent of which bindings currently
	 * exist, so restoring a binding is a one-line change here and nothing else.</p>
	 */
	private static String keyLabel(String bindingName, String fallback) {
		try {
			final java.lang.reflect.Field field = KeyBindings.class.getField(bindingName);
			final Object value = field.get(null);
			if (value instanceof org.mtr.mapping.holder.KeyBinding keyBinding) {
				return keyBinding.getBoundKeyLocalizedText().getString();
			}
		} catch (Exception ignored) {
			// Not registered right now: show the conventional key so the prompt is still useful.
		}
		return fallback;
	}

	/** The consist's cars with their model ID and world transform. */
	private static ObjectArrayList<CarTransform> carTransforms(VehicleExtension vehicle) {
		final ObjectArrayList<CarTransform> result = new ObjectArrayList<>();
		final boolean hasPitch = vehicle.getTransportMode().hasPitchAscending || vehicle.getTransportMode().hasPitchDescending;
		for (final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<org.mtr.core.data.VehicleCar, ObjectArrayList<org.mtr.core.data.Vehicle.BogiePosition>> carAndPositions : vehicle.getVehicleCarsAndPositions()) {
			final ObjectArrayList<PositionAndRotation> bogiePositions = new ObjectArrayList<>();
			for (final org.mtr.core.data.Vehicle.BogiePosition bogiePosition : carAndPositions.right()) {
				bogiePositions.add(new PositionAndRotation(bogiePosition.positionAndTiltAngle1().position(), bogiePosition.positionAndTiltAngle2().position(), true));
			}
			result.add(new CarTransform(carAndPositions.left().getVehicleId(), new PositionAndRotation(bogiePositions, carAndPositions.left(), hasPitch)));
		}
		return result;
	}

	private record Candidate(double x, double y, double z, double distanceSquared, String text, Action action) {
	}

	/**
	 * The player's vertical field of view in degrees, defaulting to vanilla's 70 when it cannot be read.
	 *
	 * <p>Read reflectively because MTR's {@code GameOptions} wrapper does not re-export {@code fov}, and a
	 * hard dependency on {@code net.minecraft.client.option.GameOptions} would tie this renderer to one
	 * MC version. A wrong FOV only shifts prompts slightly, so the fallback is safe.</p>
	 */
	/**
	 * The player's VERTICAL field of view in degrees.
	 *
	 * <p>This is the single number that decides the projection's magnification, and getting it wrong
	 * produces exactly one symptom: the prompt is correct near the screen centre and drifts further off
	 * towards the edges. It is therefore worth being explicit about where it comes from.</p>
	 *
	 * <p>{@code GameRenderer.getFov(camera, tickDelta, changingFov)} is the authoritative value - it is
	 * what builds the projection matrix the world is drawn with - so it is tried first. Everything else is
	 * a fallback and is LOGGED with its source, because a silent fallback here is indistinguishable from
	 * a correct FOV that happens to be wrong.</p>
	 */
	private static double lastFovDegrees;
	private static String lastFovSource = "?";

	private static double fovDegrees(MinecraftClient minecraftClient) {
		// 1. The game's own value, which is what the projection actually uses.
		try {
			final Object gameRenderer = minecraftClient.getGameRendererMapped().data;
			for (final java.lang.reflect.Method method : gameRenderer.getClass().getMethods()) {
				if (method.getName().equals("getFov") && method.getParameterCount() == 3) {
					method.setAccessible(true);
					final Object value = method.invoke(gameRenderer, minecraftClient.getGameRendererMapped().getCamera().data, 1.0F, true);
					if (value instanceof Number number && number.doubleValue() > 1) {
						lastFovSource = "GameRenderer.getFov";
						return number.doubleValue();
					}
				}
			}
		} catch (Exception ignored) {
			// Fall through.
		}

		// 2. The option value. NOTE the game does not use this raw: `GameRenderer.getFov()` clamps it to
		//    30..110 and MULTIPLIES BY 0.1, so a slider at 70 is 7 degrees, not 70. Getting that factor
		//    wrong is a 10x magnification error, which is why this fallback is reported in the log.
		try {
			final Object options = minecraftClient.getOptionsMapped().data;
			final Object simpleOption = options.getClass().getField("fov").get(options);
			final Object value = simpleOption.getClass().getMethod("getValue").invoke(simpleOption);
			if (value instanceof Number number) {
				lastFovSource = "option*0.1";
				return Math.max(30, Math.min(110, number.doubleValue())) * 0.1;
			}
		} catch (Exception ignored) {
			// Fall through.
		}

		lastFovSource = "default";
		return 70;
	}

	private record ScreenPoint(int x, int y) {
	}

	private record CarTransform(String vehicleId, PositionAndRotation rotation) {
	}
}
