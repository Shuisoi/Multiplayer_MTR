package org.mtr.mod.render;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.client.VehicleRidingMovement;
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
	 * Entering a cab. <b>LIVE since 2026-09-19</b> (rebuild step B2): {@code MmtrCabInteraction} consumes
	 * the key - it resolves which cab the player is aiming at (this class's own search, so the prompt and
	 * the action can never disagree about the target), asks the engine to put the key in, and moves the
	 * camera onto the seat.
	 */
	private static final Action ACTION_ENTER_CAB = new Action(keyLabel("MMTR_CAB_INTERACT", "G"), "进入驾驶室", true);

	/**
	 * **下车**。同一个 G 键、同一个车门锚点 —— 变的只是**玩家此刻的状态**：他已经坐在这个锚点所属的
	 * 那间驾驶室里了（判据 {@link #isSeatedIn(MmtrDriverSeat.Seat, long, int, int, long, int, int)}）。
	 *
	 * <p>2026-10-05 之前这里只会写"进入驾驶室"，**即使人已经坐在里面**（用户现场：按 G 上了车，
	 * 再看向同一扇门，提示行仍然写"进入驾驶室"）—— 提示行说假话比不提示更坏：玩家会以为 G 只会上车，
	 * 于是永远找不到下车那条路。文案由 {@link #actionFor} 按状态二选一，键位标签从同一条
	 * {@code MMTR_CAB_INTERACT} 读，所以改了键位两边一起改。</p>
	 */
	private static final Action ACTION_ALIGHT = new Action(keyLabel("MMTR_CAB_INTERACT", "G"), "下车", true);

	/**
	 * Doors. <b>LIVE since 2026-09-21</b>: {@code MmtrDoorInteraction} consumes Y (both sides) and
	 * U (right side only) — the station sub-task chain requires the driver to actually open and close
	 * the doors, and the riding-layer rebuild had left no key bound to that engine command at all.
	 */
	private static final Action ACTION_DOORS = new Action(keyLabel("MMTR_DOORS", "Y"), "开门/关门", true);

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

		final ObjectArrayList<Candidate> candidates = collect(player);		if (candidates.isEmpty()) {
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
		/*
		 * **"我正坐在这间驾驶室里" → 那扇司机门不受 50° 视野锥限制**（2026-10-05，用户口径"对着门按 G
		 * 下车"）。
		 *
		 * <p>为什么非绕过不可：司机门的锚点在**司机背后**（saf420cab 座位 |z|=8.2、司机门 7.502、
		 * 风挡 9.725，司机朝风挡坐），所以坐着的时候那扇门永远在视野背后，永远过不了
		 * {@link #MAX_VIEW_ANGLE_DEGREES} —— 于是 {@code [G] 下车} 根本画不出来，"对着门按 G"这条
		 * 通路等于不存在。站在门口按 G 是"我要进来"的口径，坐着朝前看是"我要出去"的口径，
		 * 后者不该要求玩家把头转 180° 去证明他坐在哪儿。</p>
		 *
		 * <p>**只放宽角度，不放宽距离**：{@link #REACH_M} 照旧在下面判（人真的在那节车的那一端），
		 * 而"我在不在这个驾驶室"用的是位置判据 {@link MmtrDriverSeat#current()} 与锚点的
		 * {@code vehicleId/carNumber/cab} 比对 —— 与"能不能操作手柄"同一条规则，不另立一套。</p>
		 *
		 * <p>只在**目标就是自己那间驾驶室**时放宽：别的车门（包括同一列车另一端的驾驶室门）仍然要
		 * 玩家真的朝它看，否则车厢里坐着不动就会看到自己背后飘着一行"下车"。</p>
		 *
		 * <p>⚠️ 位置判据**依赖风挡锚点**（{@link MmtrDriverSeat#current()} 用
		 * {@code MmtrVehicleAnchors.nearestCab} 找"离我最近的风挡"），而 SAF101 这类模型根本没有
		 * {@code mmtr_windshield} 锚点 ⇒ 在那些车上它永远返回 null，这条放宽就永远不成立、
		 * "对着门按 G 下车"在那里等于没有。所以判据退化为：**位置判据优先，判不出来时才用本地骑乘
		 * 状态**（我握着的车/车节/驾驶室编号 —— 进舱时写入、{@code leaveRide} 清空，判据见
		 * {@link #isSeatedIn(MmtrDriverSeat.Seat, long, int, int, long, int, int)} 的注释）。这份本地状态在同一帧里
		 * 就是"我在哪间驾驶室"的等价说法。</p>
		 */
		final MmtrDriverSeat.Seat seat = MmtrDriverSeat.current();
		final long heldCabVehicleId = VehicleRidingMovement.mmtrCabVehicleId();
		final int heldCabCarNumber = VehicleRidingMovement.mmtrCabCarNumber();
		final int heldCabNumber = VehicleRidingMovement.mmtrCabNumber();

		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final ObjectArrayList<CarTransform> cars = carTransforms(vehicle);
			for (int carNumber = 0; carNumber < cars.size(); carNumber++) {
				final CarTransform car = cars.get(carNumber);
				final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(car.vehicleId);
				if (anchors.isEmpty()) {
					continue;
				}
				for (final Anchor anchor : anchors) {
					final Action action = actionFor(anchor, seat, heldCabVehicleId, heldCabCarNumber, heldCabNumber, vehicle.getId(), carNumber);
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
					if ((dx * lookX + dy * lookY + dz * lookZ) / distance < cosMaxAngle
							&& !(anchor.kind == MmtrVehicleAnchors.Kind.CABDOOR
								&& isSeatedIn(seat, heldCabVehicleId, heldCabCarNumber, heldCabNumber, vehicle.getId(), carNumber, anchor.cab))) {
						continue;
					}
					result.add(new Candidate(
							world.x(), world.y() + LABEL_LIFT_M, world.z(),
							distanceSquared,
							labelFor(anchor, action),
							action,
							vehicle.getId(),
							carNumber,
							anchor.cab,
							car.vehicleId,
							car.rotation
					));
				}
			}
		}

		result.sort((a, b) -> Double.compare(a.distanceSquared, b.distanceSquared));
		return result;
	}

	/**
	 * 这个锚点此刻对应哪个动作 —— 同时决定**提示行写什么**与**按 G 会发生什么**（见 {@link #findAlightTarget}）。
	 *
	 * <p>司机门有两种含义，取决于玩家站在哪儿/坐在哪儿：门外的"进入驾驶室"，和坐在里面时的"下车"。
	 * 判据是 {@link #isSeatedIn(MmtrDriverSeat.Seat, long, int, int, long, int, int)}：**位置优先**
	 * （{@link MmtrDriverSeat#current()}，与司机 HUD / 手柄 / "谁是司机"上报同源，所以上车请求被引擎
	 * 拒绝时——人还在车上但钥匙不是他的——提示行不会骗人说"你能下车"），位置判不出来时才退回本地
	 * 骑乘状态。</p>
	 */
	@Nullable
	private static Action actionFor(Anchor anchor, @Nullable MmtrDriverSeat.Seat seat, long heldCabVehicleId, int heldCabCarNumber, int heldCabNumber, long vehicleId, int carNumber) {
		switch (anchor.kind) {
			case CABDOOR:
				return isSeatedIn(seat, heldCabVehicleId, heldCabCarNumber, heldCabNumber, vehicleId, carNumber, anchor.cab) ? ACTION_ALIGHT : ACTION_ENTER_CAB;
			case DOOR:
				return ACTION_DOORS;
			default:
				return null;
		}
	}

	/**
	 * **我此刻是不是坐在 {@code (vehicleId, carNumber, cab)} 这间驾驶室里**。
	 *
	 * <p>比的是三元组 {@code 车 + 车节 + 驾驶室编号}，而驾驶室编号用的是**模型自己的**
	 * 锚点编号（{@code anchor.cab}）：{@link MmtrDriverSeat.Seat#cab()} 与
	 * {@link MmtrVehicleAnchors#nearestCab} 是同一个编号空间，锚点编号与引擎的 A/B 端在 BR101 上
	 * 是反的，所以这里绝不能拿引擎端来比（见 {@link MmtrVehicleAnchors#engineEndOfSeat}）。</p>
	 *
	 * <h3>两级判据</h3>
	 * <ol>
	 *   <li><b>位置判据优先</b>：{@link MmtrDriverSeat#current()}（"我离这节车的哪个风挡最近，且站在
	 *       操纵位附近"）。它与司机 HUD、手柄、"谁是司机"上报是同一条规则，所以提示行不会说假话。</li>
	 *   <li><b>判不出来时退回本地骑乘状态</b>（{@code heldCabVehicleId/CarNumber/Number}）。
	 *       为什么需要这一级：位置判据要**风挡锚点**才成立，而 SAF101 没有
	 *       {@code mmtr_windshield} 锚点 ⇒ 在那辆车上它永远是 null，只用第一级的话
	 *       "对着门按 G 下车"在 SAF101 上根本没救（提示不出现、也没有任何提示说明为什么）。
	 *       本地这三项在进舱时写入、在 {@code leaveRide} 清空，同一帧里就是同一个事实。</li>
	 * </ol>
	 *
	 * <p>两级都要求驾驶室编号相等，所以"同一列车另一端的驾驶室门"在两种判据下都不会被误认成
	 * "我在的那间"。</p>
	 */
	private static boolean isSeatedIn(@Nullable MmtrDriverSeat.Seat seat, long heldCabVehicleId, int heldCabCarNumber, int heldCabNumber, long vehicleId, int carNumber, int cab) {
		if (seat != null && seat.vehicleId() == vehicleId && seat.carNumber() == carNumber && seat.cab() == cab) {
			return true;
		}
		return heldCabVehicleId != 0 && heldCabVehicleId == vehicleId && heldCabCarNumber == carNumber && heldCabNumber == cab;
	}

	/**
	 * **玩家此刻瞄准的那扇车门属于哪台车**（0 = 没瞄准任何车门）。
	 *
	 * <p>给司机车门键用（{@code MmtrDoorInteraction}）：没坐在车上时，站在站台上也能开一列停着的车。
	 * 判据复用本类自己的搜索（{@link #collect}），所以"提示里写着 [Y] 开门"与"按 Y 真的有反应"
	 * 永远是同一个目标 —— 这是这个类存在的**全部理由**，门键必须走它。</p>
	 */
	public static long aimedDoorVehicleId() {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return 0;
		}
		for (final Candidate candidate : collect(player)) {
			if (candidate.action == ACTION_DOORS) {
				return candidate.vehicleId;
			}
		}
		return 0;
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
		logProjection(window, candidate, point, projectionSource());

		final String text = candidate.text;
		// 字体：MMTR 屏幕 UI 字体（DIN 1451 西文 + HarmonyOS Sans SC 中文，notes/221）。
		// 背景框的宽度必须用**同一份带样式的文本**量出来 —— 换字体后字宽会变，否则框会与字错位。
		final MutableText styledText = IDrawing.withUIFont(TextHelper.literal(text));
		final int textWidth = GraphicsHolder.getTextWidth(styledText);
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

		graphicsHolder.drawText(styledText, point.x - halfWidth, textTop, candidate.action.live ? TEXT_COLOR : TEXT_COLOR_INACTIVE, true, GraphicsHolder.getDefaultLight());
	}

	/**
	 * One line proving the layer is alive, printed the first time it actually runs.
	 *
	 * <p>Without it, "I see nothing in the log" has two indistinguishable causes: the layer never ran (hook
	 * not registered / no player), or it ran and found nothing to draw. This separates them.</p>
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

	/**
	 * Diagnostic, printed when it is useful rather than on a timer: the first frame a prompt is drawn, and
	 * again whenever the projection SOURCE changes (which is how a silent fallback announces itself).
	 *
	 * <p>An earlier version printed every second, which is both noisy and still easy to miss when it
	 * matters. Keyed on the source instead, the log gains one line per state change - so
	 * "投影=FOV 重建" appearing where "投影=矩阵" was expected is impossible to overlook.</p>
	 */
	private static String lastLoggedProjectionSource;

	private static void logProjection(Window window, Candidate candidate, ScreenPoint point, String projectionSource) {
		// Dedup on the SOURCE only, never on `projectionSource`, which is the human-readable line and
		// carries the per-frame scale/FOV numbers. Keying on the whole string defeated this guard's own
		// purpose: the numbers change every frame, so it fired on every frame a prompt was drawn (44 lines
		// in one second in a real session, measured 2026-09-19) and buried the one state change it exists
		// to announce. `lastProjectionSource` is the stable state - the matrix diagonal when the matrix was
		// readable, otherwise the named reason it was not.
		if (lastProjectionSource.equals(lastLoggedProjectionSource)) {
			return;
		}
		lastLoggedProjectionSource = lastProjectionSource;
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final org.mtr.mapping.holder.Camera camera = minecraftClient.getGameRendererMapped().getCamera();
		org.mtr.mod.Init.LOGGER.info("[MMTR-PROMPT] {} 屏幕=({},{}) 中心=({},{}) 窗口={}x{} 投影={} 相机=({},{},{}) yaw={} pitch={} 目标=({},{},{})",
				candidate.text, point.x, point.y, window.getScaledWidth() / 2, window.getScaledHeight() / 2,
				window.getScaledWidth(), window.getScaledHeight(), projectionSource,
				round(camera.getPos().getXMapped()), round(camera.getPos().getYMapped()), round(camera.getPos().getZMapped()),
				Math.round(camera.getYaw() * 100) / 100.0, Math.round(camera.getPitch() * 100) / 100.0,
				round(candidate.x), round(candidate.y), round(candidate.z));
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
		// Camera basis in world space. Derived once and pinned by the unit checks in
		// mmtr/tools/projection-check, because THREE sign choices in here are all invisible at the screen
		// centre and each one was wrong in a shipped version:
		//
		//   forward = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
		//   right   = normalize(cross(forward, worldUp))     worldUp = (0,1,0)
		//   up      = cross(forward, right)
		//
		//   * right = cross(worldUp, forward) NEGATES the horizontal axis: turning the view left dragged
		//     the label further left. It ALSO reproduced the logged screen position (482 vs the logged
		//     483), so matching a screenshot could not have caught it.
		//   * up = cross(right, forward) NEGATES the vertical axis: the label sinks when you look up.
		//     Measured directly - at yaw 0 pitch 0 it evaluates to (0,-1,0) instead of (0,1,0).
		//
		// Known case: at yaw 0 the camera faces +Z, so `right` must be (-1,0,0) and `up` (0,1,0). Both do.
		double rightX = -forwardZ;
		double rightZ = forwardX;
		final double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
		if (rightLength < 1.0E-6) {
			// Looking straight up or down: any "right" is arbitrary and nothing horizontal is in view.
			return null;
		}
		rightX /= rightLength;
		rightZ /= rightLength;
		// up = cross(right, forward). Written out by hand from the definition rather than derived on the
		// fly, because BOTH cross-product orders have been wrong in this file at different times and each
		// one silently mirrored an axis. Working it through for this exact `right` and `forward`:
		//
		//   up = right x forward
		//      = ((ry*fz - rz*fy), (rz*fx - rx*fz), (rx*fy - ry*fx))     ry = 0
		//      = (-rz*fy,          (rz*fx - rx*fz), rx*fy)
		//
		// which at yaw 0, pitch 0 gives (0, 1, 0) as it must, and slopes correctly for pitch. The other
		// order, forward x right, is the negative of this and inverts the vertical axis.
		final double upX = -rightZ * forwardY;
		final double upY = rightZ * forwardX - rightX * forwardZ;
		final double upZ = rightX * forwardY;

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
	 *
	 * <p>⚠️ 也被 {@code RenderRails} 用来做**逐实例视锥剔除**（同一包内可见）：那里需要的是这两个
	 * 对角线本身的语义 —— 相机空间里 {@code |x| * m00 <= z} 且 {@code |y| * m11 <= z} 即在视锥内。
	 * 所以**改这个方法的语义会同时影响交互提示与钢轨剔除**。</p>
	 */
	@Nullable
	static double[] projectionScale(MinecraftClient minecraftClient) {
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

			final float fov = ((Number) fovMethod.invoke(gameRenderer, camera, 1.0F, true)).floatValue();			// The FOV parameter may be declared float or double; convert for whichever it is.
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
		} catch (Throwable e) {
			// Throwable, not Exception, and the type is NAMED: a reflective failure here was swallowed
			// silently once already (the log said "FOV 重建" with no reason, which is indistinguishable
			// from the method not existing).
			lastProjectionSource = "FOV 重建（" + describeFailure(e) + "）";
			return null;
		}
	}

	/** One-line description of a reflective failure, including the root cause. */
	private static String describeFailure(Throwable throwable) {
		final StringBuilder description = new StringBuilder(throwable.getClass().getSimpleName());
		final String message = throwable.getMessage();
		if (message != null) {
			description.append(':').append(message.replace('\n', ' '));
		}
		Throwable cause = throwable.getCause();
		int depth = 0;
		while (cause != null && depth++ < 3) {
			description.append(" <- ").append(cause.getClass().getSimpleName());
			if (cause.getMessage() != null) {
				description.append(':').append(cause.getMessage().replace('\n', ' '));
			}
			cause = cause.getCause();
		}
		return description.length() > 200 ? description.substring(0, 200) : description.toString();
	}

	/**
	 * Finds a method by name, parameter count, and how many of its parameters must accept a number
	 * (the rest are objects). Returns null when there is no unique match.
	 */
	/**
	 * Finds a method by name, parameter count, and how many of its parameters are numbers.
	 *
	 * <p>Walks DECLARED methods up the class hierarchy, <b>not</b> {@code getMethods()}. That was the bug:
	 * {@code getMethods()} returns only public members, and the methods this needs are private
	 * ({@code GameRenderer.getFov} and {@code getBasicProjectionMatrix} are both private in 1.20.4), so the
	 * lookup silently found nothing and the caller fell back - reported in-game as
	 * "投影=FOV 重建（找不到 getFov）".</p>
	 *
	 * <p>Ambiguity refuses rather than guesses: two candidates with the same shape means the signature is
	 * not distinctive enough to pick safely, and picking wrong here produces a wrong projection.</p>
	 */
	@Nullable
	private static java.lang.reflect.Method findMethod(Object target, String name, int parameterCount, int numberParameterCount) {
		java.lang.reflect.Method found = null;
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			for (final java.lang.reflect.Method method : type.getDeclaredMethods()) {
				if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
					continue;
				}
				int numbers = 0;
				for (final Class<?> parameterType : method.getParameterTypes()) {
					if (parameterType == float.class || parameterType == double.class || parameterType == int.class || parameterType == long.class) {
						numbers++;
					}
				}
				if (numbers != numberParameterCount) {
					continue;
				}
				if (found != null) {
					return null;
				}
				found = method;
			}
		}
		if (found != null) {
			found.setAccessible(true);
		}
		return found;
	}


	private static String lastProjectionSource = "?";

	/** The two diagonal terms in force this frame, refreshed once per render pass. */
	private static double[] projectionScaleForFrame;
	private static double scaleXForFrame = 1;
	private static double scaleYForFrame = 1;

	/**
	 * Resolves the projection constants once per frame: the two NDC-per-unit diagonal terms and, for the
	 * diagnostic, where they came from.
	 *
	 * <p>Preference order matters. The game's own matrix is authoritative; the FOV reconstruction is a
	 * guess about which FOV and which GUI scale, and a wrong guess shows up as a prompt that is correct at
	 * one FOV setting and drifts as the player changes it - so the source is recorded and logged rather
	 * than silently chosen.</p>
	 */
	private static void refreshProjection() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final Window window = minecraftClient.getWindow();
		final double width = window.getScaledWidth();
		final double height = window.getScaledHeight();
		final double[] scale = projectionScale(minecraftClient);
		if (scale != null) {
			projectionScaleForFrame = scale;
			scaleXForFrame = scale[0];
			scaleYForFrame = scale[1];
			return;
		}
		projectionScaleForFrame = null;
		// Reconstruct from the FOV. scaleY = 1/tan(fov/2); scaleX carries the aspect, because the matrix's
		// m00 is m11/aspect and the GUI is wider than it is tall.
		final double tanHalfFov = Math.tan(Math.toRadians(fovDegrees(minecraftClient)) / 2);
		scaleYForFrame = tanHalfFov;
		scaleXForFrame = tanHalfFov * width / height;
		if (!lastProjectionSource.startsWith("FOV 重建")) {
			lastProjectionSource = "FOV 重建";
		}
	}

	private static String projectionSource() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		return lastProjectionSource
				+ " scaleX=" + Math.round(scaleXForFrame * 10000) / 10000.0
				+ " scaleY=" + Math.round(scaleYForFrame * 10000) / 10000.0
				+ " FOV=" + Math.round(fovDegrees(minecraftClient) * 100) / 100.0
				+ '(' + lastFovSource + ")"
				+ " 选项原值=" + rawFovOption(minecraftClient);
	}

	/**
	 * The fov option's stored value, printed verbatim for the diagnostic.
	 *
	 * <p>Two DIFFERENT numbers have been wrong in this file (a x0.1 that the option does not need, and a
	 * changingFov=false that pinned the value to 70), and both times the log could not tell them apart from
	 * a correct read. Printing the raw option next to the value actually used means the next disagreement
	 * is arithmetic instead of another round of guessing.</p>
	 */
	private static String rawFovOption(MinecraftClient minecraftClient) {
		try {
			final Object options = minecraftClient.getOptionsMapped().data;
			final Object simpleOption = options.getClass().getField("fov").get(options);
			final Object value = simpleOption.getClass().getMethod("getValue").invoke(simpleOption);
			return String.valueOf(value);
		} catch (Exception e) {
			return describeFailure(e);
		}
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

	/**
	 * One interactable, with everything an ACTION needs to carry it out.
	 *
	 * <p>{@code vehicleId}/{@code carNumber}/{@code cab} are only meaningful for a cab door, but they are
	 * carried on every candidate: the interaction class must act on the SAME target the prompt drew, and
	 * re-deriving "which cab is nearest" a second time is how the label and the key end up disagreeing
	 * (the prompt lists every candidate, the action must pick exactly one).</p>
	 */
	private record Candidate(double x, double y, double z, double distanceSquared, String text, Action action,
			long vehicleId, int carNumber, int cab, String modelId, PositionAndRotation carTransform) {

		/** 准星选中的那个门 → 给动作层用的目标（引擎端在这里算，见 CabTarget 的注释）。 */
		CabTarget toCabTarget() {
			final MmtrVehicleAnchors.CabView view = MmtrVehicleAnchors.cabView(MmtrVehicleAnchors.get(modelId), cab);
			final int engineEnd = view == null ? cab : MmtrVehicleAnchors.engineEndOfSeat(view.z);
			return new CabTarget(vehicleId, carNumber, cab, carTransform, engineEnd);
		}
	}

	/**
	 * The cab door the player is aiming at: the nearest one inside {@link #REACH_M} and the view cone.
	 *
	 * <p>Exposed for {@code MmtrCabInteraction}, and computed by the same {@link #collect} pass that draws
	 * the labels, so the {@code [G] 进入驾驶室1} a player sees is exactly the cab the key takes. Aims by
	 * DISTANCE (not by the smallest angle): among the doors of one end the nearest is the one the player
	 * is standing at, which is what "take this cab" means.</p>
	 *
	 * <p>{@code carTransform} is the aimed CAR's world transform - the interaction needs it to turn the
	 * cab's car-local facing into the entity yaw the driver should start with.</p>
	 */
	@Nullable
	public static CabTarget findCabTarget(ClientPlayerEntity player) {
		for (final Candidate candidate : collect(player)) {
			if (candidate.action == ACTION_ENTER_CAB) {
				return candidate.toCabTarget();
			}
		}
		return null;
	}

	/**
	 * **准星方向有没有"能下车的那扇门"** —— 给 {@code MmtrCabInteraction} 的"对着门按 G 下车"用。
	 *
	 * <p>两个条件，缺一不可：</p>
	 * <ol>
	 *   <li><b>门属于我正握着的那间驾驶室</b>（车 + 车节 + 驾驶室编号三元组，见
	 *       {@link #isSeatedIn(MmtrDriverSeat.Seat, long, int, int, long, int, int)}）。
	 *       不比对的话，站在门口按 G 会"下车"到同一列车的另一端去 —— 玩家在 1A 里朝 2A 的门按 G，
	 *       期望是下车，而不是被丢进 2A 接着开。</li>
	 *   <li><b>它出现在 {@link #collect} 的结果里</b>，也就是在 {@link #REACH_M} 内（6 m）。
	 *       5.4 m 的车里"坐着就能下车"是刻意的：司机门在背后，绕过视野锥之后距离是唯一还成立的约束。</li>
	 * </ol>
	 *
	 * <p>复用 {@code collect} 而不是自己遍历锚点：提示行画的与按 G 执行的必须是**同一批候选**，
	 * 否则就会出现"提示写着下车、按下去上了车"这种自相矛盾（这个类存在的全部理由）。</p>
	 *
	 * <ul>
	 *   <li>{@code heldCabVehicleId}：玩家正握着的驾驶室所属车（0 = 没握着，调用方应先判掉）</li>
	 *   <li>{@code heldCabCarNumber}：正握着的那节车（{@code VehicleRidingMovement.mmtrCabCarNumber()}）</li>
	 *   <li>{@code heldCabNumber}：正握着的那间驾驶室编号（{@link MmtrVehicleAnchors} 的编号空间）</li>
	 * </ul>
	 */
	@Nullable
	public static CabTarget findAlightTarget(ClientPlayerEntity player, long heldCabVehicleId, int heldCabCarNumber, int heldCabNumber) {
		if (heldCabVehicleId == 0) {
			return null;
		}
		for (final Candidate candidate : collect(player)) {
			if (candidate.action == ACTION_ALIGHT
					&& candidate.vehicleId == heldCabVehicleId
					&& candidate.carNumber == heldCabCarNumber
					&& candidate.cab == heldCabNumber) {
				return candidate.toCabTarget();
			}
		}
		return null;
	}

	/** @see #findCabTarget(ClientPlayerEntity) */
	/**
	 * @param cab       模型自己的驾驶室编号（{@code mmtr_cabdoor_<cab>}）
	 * @param engineEnd **引擎的端**（1 = A，2 = B）—— 由座位点的 Z 符号定，**不**等于 {@code cab}：
	 *                  锚点编号是模型约定，引擎端是脊柱约定，两者在 BR101 上是反的
	 *                  （见 {@link MmtrVehicleAnchors#engineEndOfSeat}）。
	 */
	public record CabTarget(long vehicleId, int carNumber, int cab, PositionAndRotation carTransform, int engineEnd) {
	}

	/**
	 * **这辆车上所有可进入的驾驶室**，与准星/距离无关。
	 *
	 * <h3>为什么需要它</h3>
	 * <p>{@link #findCabTarget} 回答的是"我正瞄着哪个门"，因此要求玩家站在门口、且在视野锥里 ——
	 * 那是**人自己走进去**时的口径。而"把人直接放进驾驶室"（{@code /mtr mmtrboard}、引擎指令栏的
	 * {@code train board}）没有准星可用，它需要的是"这辆车有哪些驾驶室"这件事本身。</p>
	 *
	 * <p>刻意复用同一条 {@link #carTransforms} + {@code MmtrVehicleAnchors.get} + {@code Anchor.cab} 的取法：
	 * 两个入口对"哪节车的哪个端算驾驶室"必须永远一致，各推一遍就会分叉（notes/163 的教训）。</p>
	 *
	 * @return 按"车节从前往后、每节 A 端在前"排序；车不在客户端镜像里时返回空表
	 */
	public static ObjectArrayList<CabTarget> cabTargetsOf(long vehicleId) {
		final ObjectArrayList<CabTarget> result = new ObjectArrayList<>();
		final VehicleExtension vehicle = vehicleById(vehicleId);
		if (vehicle == null) {
			return result;
		}
		final ObjectArrayList<CarTransform> cars = carTransforms(vehicle);
		for (int carNumber = 0; carNumber < cars.size(); carNumber++) {
			final CarTransform car = cars.get(carNumber);
			final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(car.vehicleId);
			for (final Anchor anchor : anchors) {
				if (anchor.kind == MmtrVehicleAnchors.Kind.CABDOOR && anchor.cab > 0) {
					// 引擎端由座位点的 Z 符号定，不是锚点编号 —— 见 MmtrVehicleAnchors.engineEndOfSeat
					final MmtrVehicleAnchors.CabView view = MmtrVehicleAnchors.cabView(anchors, anchor.cab);
					final int engineEnd = view == null ? anchor.cab : MmtrVehicleAnchors.engineEndOfSeat(view.z);
					result.add(new CabTarget(vehicleId, carNumber, anchor.cab, car.rotation, engineEnd));
				}
			}
		}
		return result;
	}

	/** 客户端镜像里的这辆车；不在镜像里（太远 / 还没同步）时为 {@code null}。 */
	@Nullable
	private static VehicleExtension vehicleById(long vehicleId) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	/**
	 * 这辆车**在不在客户端的车辆镜像里**。
	 *
	 * <p>与 {@link #cabTargetsOf} 分开问，是为了让"车还没同步过来"（等一等就好）与"这辆车的模型没有
	 * 驾驶室锚点"（等多久都不会好）在调用方那里是两件不同的事 —— 合成一个空表就分不出来了。</p>
	 */
	public static boolean isVehicleMirrored(long vehicleId) {
		return vehicleById(vehicleId) != null;
	}

	/**
	 * The player's vertical field of view in degrees.
	 *
	 * <p>This is the single number that decides the projection's magnification, and getting it WRONG
	 * produces a signature symptom: the prompt lines up at one FOV setting and drifts as the player
	 * changes it. Reported in-game as "FOV 60 is perfect, but turning it up does not adapt".</p>
	 *
	 * <p>The value is taken from {@code GameOptions.fov}'s own value, which the bytecode of
	 * {@code GameRenderer.getFov} confirms is <b>already in degrees</b>:</p>
	 *
	 * <pre>
	 *   11: ldc2_w 70.0d                       // default 70
	 *   27: GameOptions.getFov().getValue()    // Integer - straight into the local, no scaling
	 *   42: dload_4 ; lerp(1, fovMultiplier) ; dmul   // sprint / spyglass only
	 * </pre>
	 *
	 * <p>An earlier version multiplied this by 0.1 (on the belief that the slider stored a tenth of the
	 * angle). It does not, so every prompt was magnified about ten times too much - which happens to look
	 * CORRECT at exactly one setting (the 70-degree default: tan(35) there is 0.700, matching the log
	 * line) and drifts at every other, including the 60 the user tested.</p>
	 *
	 * <p>{@code fovMultiplier} (sprint / spyglass / nausea) is deliberately not modelled: this is a static
	 * prompt, and matching a transient zoom would make it swim.</p>
	 */
	private static String lastFovSource = "?";

	private static double fovDegrees(MinecraftClient minecraftClient) {
		// Read the OPTION, not GameRenderer.getFov. The bytecode of getFov is:
		//
		//   11: ldc2_w 70.0d                       // value = 70
		//   16: iload_3 ; ifeq 60                  // if (!changingFov) return 70   <-- !!
		//   20..40: value = GameOptions.getFov().getValue()   // the player's setting, in degrees
		//   42..57: value *= lerp(1, fovMultiplier)           // sprint / spyglass only
		//
		// So `changingFov = false` does NOT mean "skip the transient multiplier", it means "return the
		// hardcoded 70" - the parameter is a whether-to-apply-the-player's-setting flag. Passing false (as
		// an earlier version did, to avoid the sprint zoom) pinned every prompt to a 70-degree projection,
		// which is exactly the reported "perfect at one FOV, does not adapt when I raise it". The option
		// value is used directly instead: it is already the angle, and it carries no transient multiplier,
		// which is what this prompt wants.
		try {
			final Object options = minecraftClient.getOptionsMapped().data;
			final Object simpleOption = options.getClass().getField("fov").get(options);
			final Object value = simpleOption.getClass().getMethod("getValue").invoke(simpleOption);
			if (value instanceof Number number && number.doubleValue() > 1) {
				lastFovSource = "option";
				return Math.max(30, Math.min(110, number.doubleValue()));
			}
		} catch (Exception ignored) {
			// Fall through.
		}

		// Fallback: ask the renderer, WITH changingFov = true so it actually reads the player's setting.
		try {
			final Object gameRenderer = minecraftClient.getGameRendererMapped().data;
			final java.lang.reflect.Method fovMethod = findMethod(gameRenderer, "getFov", 3, 1);
			if (fovMethod != null) {
				final Object value = fovMethod.invoke(gameRenderer, minecraftClient.getGameRendererMapped().getCamera().data, 1.0F, true);
				if (value instanceof Number number && number.doubleValue() > 1) {
					lastFovSource = "getFov(true)";
					return number.doubleValue();
				}
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
