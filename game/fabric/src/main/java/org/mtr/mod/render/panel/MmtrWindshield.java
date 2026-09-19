package org.mtr.mod.render.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * MMTR windshield: an animated precipitation layer plus a sweeping wiper, drawn on the model's
 * {@code mmtr_windshield[_<n>]} face.
 *
 * <p><b>Render ordering is not free.</b> {@link MainRenderer} walks {@link QueuedRenderLayer} in enum
 * order, but inside ONE layer the order comes from an identifier-keyed map - which is exactly why the
 * cab-panel documentation insists on one texture per bucket. So the water goes in
 * {@code EXTERIOR_TRANSLUCENT} and the wiper in {@code LIGHT_2}, which is LATER in the enum, guaranteeing
 * the wiper covers the water it has not wiped yet. The wiper is also pushed further off the glass than
 * the water, so depth agrees with layer order instead of fighting it.</p>
 *
 * <p>The layers are also chosen for their DEPTH behaviour, not just their order: the rain is a soft,
 * semi-transparent image and needs {@code getEntityTranslucentCull} (blended, no depth write, no alpha
 * cutoff). {@code LIGHT_2} is {@code RenderLayer.getText()}, which WRITES depth and discards anything
 * below an alpha threshold - fine for the solid wiper bars, wrong for water.</p>
 *
 * <p><b>All of this is client-side and cosmetic.</b> The only inputs are the local clock, the local
 * weather and the mirrored vehicle speed. Nothing here can change what the train does, and nothing is
 * sent to the server.</p>
 */
public final class MmtrWindshield {

	private MmtrWindshield() {
	}

	private static final Logger LOGGER = LogManager.getLogger("MMTR-WSHLD");

	/** Water on the glass, pushed off the modelled face (same order of magnitude as the cab panel). */
	private static final float WATER_OFFSET_M = 0.030F;
	/** The wiper sits above the water so layer order and depth agree. */
	private static final float WIPER_OFFSET_M = 0.045F;
	/** Pixel width of the regenerated precipitation image; the height follows the glass aspect ratio. */
	private static final int RAIN_CANVAS_WIDTH_PX = 128;
	/** Solid-colour quads reuse MTR's own white texture rather than shipping one. */
	private static final Identifier WHITE_TEXTURE = new Identifier("minecraft", "textures/misc/white.png");
	/** Rebuilding the image costs a full texture upload; rain does not need 60 Hz. */
	private static final long RAIN_MIN_REBUILD_MILLIS = 90;
	/** Once the weather has been dry this long the windshield forgets its droplets. */
	private static final long DRY_FORGET_MILLIS = 4000;

	/**
	 * Wiper stalk positions. There is deliberately no AUTO: the driver decides, and the weather only
	 * decides how wet the glass is (which is what the droplets are for).
	 */
	public enum WiperMode {
		OFF(0, 0),
		SLOW(1.55, 1),
		FAST(0.78, 2);

		/** Seconds for one complete out-and-back stroke. */
		public final double periodS;
		/** Seconds the blade rests at the park position between strokes. */
		public final double dwellS;

		WiperMode(double periodS, double dwellS) {
			this.periodS = periodS;
			this.dwellS = dwellS;
		}

		public WiperMode next() {
			return this == OFF ? SLOW : this == SLOW ? FAST : OFF;
		}
	}

	/** The wiper stalk, PER CLIENT. It is a driver input, not a property of the glass. */
	private static WiperMode wiperMode = WiperMode.OFF;
	/** Key edge detection: {@code KeyBinding} only exposes isPressed(). */
	private static boolean wiperKeyPressed = false;

	/**
	 * Handles the wiper key. Called once per client tick from {@link MainRenderer}, next to the cab
	 * interaction tick, so the action-bar feedback and the state change happen on the tick thread.
	 *
	 * <p>The mode is only accepted while the player actually holds a cab: a passenger pressing the key
	 * must not start wiping the train they are sitting in. The cycle is 关 -> 慢 -> 快 -> 关; switching
	 * position mid-stroke keeps the blade where it is (see {@link State#advance}), so no mode change
	 * ever snaps the arm across the glass.</p>
	 */
	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_WIPER.isPressed();
		final boolean justPressed = pressed && !wiperKeyPressed;
		wiperKeyPressed = pressed;
		if (!justPressed) {
			return;
		}

		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final org.mtr.mapping.holder.ClientPlayerEntity player = minecraftClient.getPlayerMapped();
		if (player == null) {
			return;
		}
		if (!driverOnBoardAnyCab()) {
			// Not our train to switch: say so instead of silently ignoring the key.
			player.sendMessage(new Text(TextHelper.literal("需要先坐上驾驶位 / take a cab first").data), true);
			return;
		}

		wiperMode = wiperMode.next();
		player.sendMessage(new Text(TextHelper.literal("雨刷 " + wiperModeLabel()).data), true);
	}

	/** The wiper's current stalk position, for the cab panel's indicator lamp. */
	public static WiperMode wiperModeForDisplay() {
		return wiperMode;
	}

	private static String wiperModeLabel() {
		return wiperMode == WiperMode.OFF ? "关 / off" : wiperMode == WiperMode.SLOW ? "慢 / slow" : "快 / fast";
	}

	/**
	 * How far ahead of the blade the wipe fades in (degrees). Wide enough that the clear band appears to
	 * travel with the arm rather than snap, narrow enough that the blade has visibly done the work.
	 */
	private static final double WIPE_FADE_DEG = 14;

	/**
	 * Airflow force per (m/s)^2 of speed. Chosen, not measured: it puts the balance point between the
	 * downward pull of gravity and the updraught at about 22 m/s (80 km/h), so beads hold station there
	 * and only creep upward at line speed. That single number is what makes a stationary train and a
	 * train at speed look different.
	 */
	private static final double AIRFLOW_COEFFICIENT = 0.02;
	/** How far in FRONT of the blade the film starts thinning, in metres (the band path's equivalent of
	 * {@link #WIPE_FADE_DEG}, which is an angle and only means anything for a blade through the pivot). */
	private static final double WIPE_FADE_M = 0.03;
	/** Ceiling on the acceleration used to throw beads sideways, so a physics glitch cannot smear them. */
	private static final double MAX_LATERAL_ACCELERATION = 1.2;
	/** Ceiling on the lateral bead speed (m/s), for the same reason. */
	private static final double MAX_LATERAL_VELOCITY = 0.45;
	/** How fast a bead's own painted tail dries; the wetness FIELD is what keeps the streaks. */
	private static final double TRAIL_DRY_MPS = 0.02;
	/**
	 * How far a running bead travels before it sheds a smaller bead (metres). Spacing, not rate, so a
	 * slow rivulet and a fast one leave the same speckled wake density.
	 */
	private static final double PINCH_OFF_DISTANCE_M = 0.035;
	/**
	 * How much of a bead is left right after the blade passes. Not zero: the glass is wet, it just has
	 * no standing beads on it. At the grow-in rate in {@code advanceDrops} this re-wets over ~1 s, which
	 * is the visible trail of a real wiper.
	 */
	private static final double RE_WET_INK = 0.30;

	private static final Object2ObjectOpenHashMap<String, State> STATES = new Object2ObjectOpenHashMap<>();

	/** Plane frames already logged, so the geometry is reported once per windshield instead of per frame. */
	private static final Object2ObjectOpenHashMap<String, Boolean> PLANE_LOGGED = new Object2ObjectOpenHashMap<>();

	/** One-shot diagnostic for "the layer is in the wrong place" reports. The panel had the same class of
	 * bug and the fix was impossible to guess from the screenshot; printing the actual solved frame (and
	 * three corners of the quad) makes it arithmetic instead. {@code normal} is the direction the layer is
	 * drawn along, {@code right}/{@code up} span it - if the quad ends up perpendicular to the glass, one
	 * of those three is not what the model authored.
	 */
	private static void logPlaneOnce(String key, Plane plane, double widthM, double heightM) {
		if (PLANE_LOGGED.containsKey(key)) {
			return;
		}
		PLANE_LOGGED.put(key, Boolean.TRUE);
		final Vector bl = plane.pointAt(-widthM / 2, -heightM / 2);
		final Vector br = plane.pointAt(widthM / 2, -heightM / 2);
		final Vector tl = plane.pointAt(-widthM / 2, heightM / 2);
		final double edgeX = br.x() - bl.x();
		final double edgeY = br.y() - bl.y();
		final double edgeZ = br.z() - bl.z();
		final double upX = tl.x() - bl.x();
		final double upY = tl.y() - bl.y();
		final double upZ = tl.z() - bl.z();
		// The quad's own face normal, from the corner winding (bottom-left, bottom-right, top-left).
		final double faceX = edgeY * upZ - edgeZ * upY;
		final double faceY = edgeZ * upX - edgeX * upZ;
		final double faceZ = edgeX * upY - edgeY * upX;
		Init.LOGGER.info("[MMTR-WSHLD] {} centre=({}, {}, {}) normal=[{}, {}, {}] up=[{}, {}, {}] right=[{}, {}, {}]",
				key, round(plane.position.x()), round(plane.position.y()), round(plane.position.z()),
				round(plane.normal.x()), round(plane.normal.y()), round(plane.normal.z()),
				round(plane.up.x()), round(plane.up.y()), round(plane.up.z()),
				round(plane.right.x()), round(plane.right.y()), round(plane.right.z()));
		Init.LOGGER.info("[MMTR-WSHLD] {} size={}x{}  blockCorner=({}, {}, {})  edge(right)=({}, {}, {})  edge(up)=({}, {}, {})  faceNormal=({}, {}, {})",
				key, round(widthM), round(heightM),
				round(bl.x()), round(bl.y()), round(bl.z()),
				round(edgeX), round(edgeY), round(edgeZ),
				round(upX), round(upY), round(upZ),
				round(faceX), round(faceY), round(faceZ));

		// The quad is now built straight from the face basis, so measure THAT: each corner's distance from
		// the GLASS must equal the lift, and the quad must span the anchor's own width and height.
		//
		// The distance is measured from the plane's own centre, not from the world origin. That distinction
		// matters more than it looks: "corner . normal" is the distance from the ORIGIN to the glass plane,
		// which on a train parked a few hundred blocks out is a large number that has nothing to do with
		// whether the layer is coplanar. The first version of this line printed 8.5 m and read as a bug.
		final Vector[] corners = quadCorners(plane, widthM, heightM, WATER_OFFSET_M);
		double worstOutOfPlane = 0;
		double minAlongRight = Double.MAX_VALUE;
		double maxAlongRight = -Double.MAX_VALUE;
		double minAlongUp = Double.MAX_VALUE;
		double maxAlongUp = -Double.MAX_VALUE;
		final StringBuilder sb = new StringBuilder();
		for (final Vector corner : corners) {
			final double offsetX = corner.x() - plane.position.x();
			final double offsetY = corner.y() - plane.position.y();
			final double offsetZ = corner.z() - plane.position.z();
			final double alongNormal = offsetX * plane.normal.x() + offsetY * plane.normal.y() + offsetZ * plane.normal.z();
			worstOutOfPlane = Math.max(worstOutOfPlane, Math.abs(alongNormal - WATER_OFFSET_M));
			final double alongRight = offsetX * plane.right.x() + offsetY * plane.right.y() + offsetZ * plane.right.z();
			final double alongUp = offsetX * plane.up.x() + offsetY * plane.up.y() + offsetZ * plane.up.z();
			minAlongRight = Math.min(minAlongRight, alongRight);
			maxAlongRight = Math.max(maxAlongRight, alongRight);
			minAlongUp = Math.min(minAlongUp, alongUp);
			maxAlongUp = Math.max(maxAlongUp, alongUp);
			sb.append(String.format(" (%+.3f,%+.3f,%+.3f)", alongRight, alongUp, alongNormal));
		}
		Init.LOGGER.info("[MMTR-WSHLD] {} quad corners (right,up,out-of-plane), all out-of-plane must be {}:", key, WATER_OFFSET_M);
		Init.LOGGER.info("[MMTR-WSHLD] {}   {}", key, sb);
		// Orthogonality tells us whether the model's authored frame is usable at all.
		final double dotRightUp = plane.right.x() * plane.up.x() + plane.right.y() * plane.up.y() + plane.right.z() * plane.up.z();
		final double dotUpNormal = plane.up.x() * plane.normal.x() + plane.up.y() * plane.normal.y() + plane.up.z() * plane.normal.z();
		Init.LOGGER.info("[MMTR-WSHLD] {} worst out-of-plane offset={} m (0 = the quad IS in the glass plane)  "
						+ "spans right={} m (anchor {}), up={} m (anchor {})  right.up={}  up.normal={}",
				key, round(worstOutOfPlane),
				round(maxAlongRight - minAlongRight), round(widthM),
				round(maxAlongUp - minAlongUp), round(heightM),
				round(dotRightUp), round(dotUpNormal));
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	/**
	 * Draws every windshield of one car. Called from the per-car loop in {@code RenderVehicles}, right
	 * next to the cab-panel call.
	 *
	 * @param vehicleSpeedMetersPerMs mirrored vehicle speed; only scales how fast the water runs
	 */
	public static void render(String vehicleId, int carNumber, StoredMatrixTransformations carTransform, double vehicleSpeedMetersPerMs) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		// getWorldMapped() is already the client flavour; it is a sibling of MTR's World, not a subtype.
		final org.mtr.mapping.holder.ClientWorld world = minecraftClient.getWorldMapped();
		if (world == null) {
			return;
		}

		// The client's synced copy of the weather. The gradient is the same 0..1 intensity vanilla uses
		// for its own rain particles; here it is the WETNESS of the glass (how many beads, how fast the
		// film forms) and no longer the wiper speed - the wiper is on the driver's key.
		final net.minecraft.client.world.ClientWorld clientWorld = ((net.minecraft.client.world.ClientWorld) world.data);
		final float rainGradient = clientWorld != null && clientWorld.isRaining() ? clientWorld.getRainGradient(1) : 0;
		final boolean precipitating = rainGradient > 0.02F;
		final long now = System.currentTimeMillis();

		// The wiper is drawn ONLY on the train this client actually drives. Every other train in the
		// world keeps its blades parked, which is also what stops a passenger's key from wiping anything.
		final WiperMode mode = driverOnBoard(vehicleId) ? wiperMode : WiperMode.OFF;

		for (final Anchor anchor : MmtrVehicleAnchors.findWindshields(MmtrVehicleAnchors.get(vehicleId), carNumber)) {
			if (anchor.widthM <= 0 || anchor.heightM <= 0) {
				continue;
			}

			final String key = vehicleId + ":" + carNumber + ":" + anchor.name;
			State state = STATES.get(key);
			if (state == null) {
				// The rain-vs-snow choice is a WORLD property, so it is resolved once per windshield
				// rather than per car: every glass on the train sees the same sky.
				state = new State(anchor, WindshieldConfig.get(vehicleId, anchor.name), isSnow(clientWorld));
				STATES.put(key, state);
			}

			// The state is forgotten only when the glass is BOTH dry and parked, so a stopped wiper
			// cannot lose the drops it was in the middle of clearing.
			final boolean wiping = mode != WiperMode.OFF && state.config.wiper;
			if (precipitating) {
				state.lastWetMillis = now;
			} else if (!wiping && now - state.lastWetMillis > DRY_FORGET_MILLIS) {
				STATES.remove(key);
				continue;
			}

			state.advance(now, rainGradient, vehicleSpeedMetersPerMs, mode);
			state.draw(carTransform);
		}
	}

	private static boolean configAllowsWiper(State state) {
		return state.config.wiper;
	}

	/**
	 * Whether this client is driving a train whose model uses {@code resourceId}.
	 *
	 * <p>The comparison has to go through the model ID because the two sides do not speak the same
	 * language: the cab key is held against a numeric vehicle id, while the render path carries the
	 * resource id the model was loaded from. A consist can mix models, so this asks the client's own
	 * vehicle list for the driven vehicle and compares its car models.</p>
	 *
	 * <p>⚠️ TEMPORARILY ALWAYS FALSE while the riding logic is being rebuilt (notes/185). The cab
	 * interaction that used to answer this was deleted with the rest of the boarding layer, so the wiper
	 * stalk has nothing to gate on yet. The wiper still DRAWS (parked); pressing the stalk key reports
	 * "需要先坐上驾驶位" because there is no way to take a cab at this checkpoint. Restore this when the new
	 * ride session can answer "which cab am I in" (rebuild step B2).</p>
	 */
	private static boolean driverOnBoard(String resourceId) {
		return false;
	}

	/**
	 * Whether this client holds ANY cab, for the wiper stalk. Stubbed to false with {@link #driverOnBoard}
	 * while the ride logic is rebuilt (notes/185).
	 */
	private static boolean driverOnBoardAnyCab() {
		return false;
	}

	/**
	 * Whether this world's precipitation falls as snow. Vanilla picks rain-vs-snow per position from the
	 * biome's temperature, so the wiper ends up agreeing with the weather the player can actually see.
	 */
	private static boolean isSnow(@Nullable net.minecraft.client.world.ClientWorld clientWorld) {
		if (clientWorld == null) {
			return false;
		}
		try {
			final net.minecraft.util.math.BlockPos pos = clientWorld.getSpawnPos();
			final net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> entry = clientWorld.getBiome(pos);
			return entry != null && entry.value().getTemperature() < 0.15F;
		} catch (Exception e) {
			LOGGER.warn("Could not determine the windshield precipitation type", e);
			return false;
		}
	}

	/** Drops every cached windshield (resource reload / world change). */
	public static void clear() {
		STATES.clear();
	}

	// ---------------------------------------------------------------------------------------------
	// Per-windshield state
	// ---------------------------------------------------------------------------------------------

	private static final class State {

		private final Anchor anchor;
		private final WindshieldConfig config;
		private final Drop[] drops;
		private final MmtrPanelCanvas canvas;
		private final MmtrPanelTexture texture;
		private final Random random;
		/**
		 * The wetness FIELD: moisture left on the glass by running beads, in a coarse square grid over the
		 * face, 0..1 per cell. It is what makes a streak outlive the bead that drew it, and it gives the
		 * wiper one thing to clear instead of a hundred per-bead tails. Null for snow, which does not run
		 * and therefore leaves nothing behind.
		 */
		private final float[] wetness;
		private long lastAdvanceMillis;
		private long lastRebuildMillis;
		private long lastWetMillis;
		/** Where the arm is now, and where it was at the last rebuild - the pair is the wiped sector. */
		private double wiperAngleDeg;
		private double wipedFromDeg;
		/** Position inside the current stroke: 0 = parked, 1 = fully out, 2 = parked again. */
		private double wiperPhase;
		private float intensity;
		private boolean snow;

		// --- forces ---------------------------------------------------------------------------
		/** The speed this windshield saw last frame (m/ms), so acceleration can be derived from it. */
		private double lastSpeedMps;
		private boolean speedKnown;
		/** Smoothed acceleration along the glass's right axis (m/s^2): + = the driver is thrown right. */
		private double lateralAcceleration;
		/** Fractional beads waiting to be spawned, so a low spawn rate does not round down to zero. */
		private double spawnAccumulator;
		/** Whether the blade moved during THIS advance; the film and the wipe both key off it. */
		private boolean bladeMoving;

		private State(Anchor anchor, WindshieldConfig config, boolean snow) {
			this.anchor = anchor;
			this.config = config;
			this.snow = snow;
			this.drops = new Drop[config.raindrops];
			this.random = new Random(anchor.name.hashCode() * 31L + anchor.car);
			this.canvas = MmtrPanelCanvas.createPixels(anchor.widthM, anchor.heightM, RAIN_CANVAS_WIDTH_PX);
			this.wetness = snow ? null : new float[Math.max(1, config.wetnessCells) * Math.max(1, config.wetnessCells)];
			this.texture = MmtrPanelTexture.get("windshield:" + anchor.name + ":" + anchor.car);
			for (int i = 0; i < drops.length; i++) {
				drops[i] = newDrop(random.nextDouble(), random.nextDouble());
			}
			this.wiperAngleDeg = config.parkAngleDeg;
			this.wipedFromDeg = config.parkAngleDeg;
		}

		private void advance(long now, float rainGradient, double speedMetersPerMs, WiperMode mode) {
			final long elapsed = lastAdvanceMillis == 0 ? 16 : Math.min(250, Math.max(0, now - lastAdvanceMillis));
			lastAdvanceMillis = now;
			final double elapsedSeconds = elapsed / 1000.0;

			intensity = Math.max(0, Math.min(1, rainGradient));
			// Internal speeds are m/ms; every force below is SI, so convert once.
			final double speedMps = speedMetersPerMs * 1000;

			// Fall direction: drops run DOWN THE GLASS, not down the panel's local -Y. A raked
			// windscreen makes those two different, and using the local axis gives rain that slides
			// sideways across the screen. So project world down onto the glass plane.
			final double[] down = panelDown();
			advanceWiper(elapsedSeconds, mode);
			trackAcceleration(elapsedSeconds, speedMps);
			advanceDrops(elapsedSeconds, speedMps, down);
			mergeDrops();

			if (now - lastRebuildMillis >= RAIN_MIN_REBUILD_MILLIS) {
				lastRebuildMillis = now;
				// A full-image upload is not free, so only repaint when something visible actually changed.
				// The signature covers the blade AND the bead field: without the bead term the rain froze
				// solid whenever the wiper was parked, because a parked blade has a constant signature.
				final String signature = signature();
				if (texture.needsRedraw(signature)) {
					rebuildCanvas();
				}
			}
		}

		/**
		 * Moves the arm. The blade travels at a CONSTANT angular rate (a real linkage moves roughly
		 * linearly, and the old cosine ease made the arm crawl at both ends and blur through the middle),
		 * then rests at the park position for {@link WiperMode#dwellS}.
		 *
		 * <p>The phase is a 0..2 triangle that STARTS at 0 - parked - so switching from 关 to 慢 begins
		 * where the blade already is. Every mode change is a plain rate change with no repositioning:
		 * that is what makes 慢 -> 快 -> 慢 feel like a stalk rather than a cut.</p>
		 */
		private void advanceWiper(double elapsedSeconds, WiperMode mode) {
			final double previousAngleDeg = wiperAngleDeg;
			if (mode == WiperMode.OFF || !config.wiper) {
				// Off: the blade holds position and NOTHING is wiped. A blade left mid-screen is honest -
				// it is what a driver sees when they switch a wiper off mid-stroke - but a parked wiper
				// must not keep clearing glass, or the screen dries itself out with the stalk at 关.
				bladeMoving = false;
				wipedFromDeg = wiperAngleDeg;
				return;
			}
			final double strokeRate = 2 / Math.max(0.05, mode.periodS);
			final double strokeTime = 2 / strokeRate;
			final double cycle = strokeTime + mode.dwellS;
			double time = wiperPhase / strokeRate + elapsedSeconds;
			if (time >= cycle) {
				// Wrap, keeping the overshoot so a long frame does not slow the arm down.
				time -= cycle;
			}
			if (time >= strokeTime) {
				// Dwell at park. The blade is already there, so this only holds the phase.
				time = 0;
				bladeMoving = false;
			} else {
				bladeMoving = true;
			}
			wiperPhase = time * strokeRate;
			wiperAngleDeg = config.parkAngleDeg + sweepUnit(wiperPhase) * config.sweepDeg;
			// The sector the arm covered since the last repaint is what gets the wet film drawn on it.
			wipedFromDeg = previousAngleDeg;
		}

		/** 0 -> 1 -> 2 as a triangle, so the blade sweeps out and back at one speed. */
		private double sweepUnit(double phase) {
			final double wrapped = phase % 2;
			return wrapped <= 1 ? wrapped : 2 - wrapped;
		}

		/**
		 * Derives the acceleration the driver feels from the mirrored speed. The engine mirrors a speed
		 * every tick but not an acceleration, so a finite difference is the honest source here.
		 *
		 * <p>Sign convention: the glass's {@code right} axis is the driver's right (that is what the
		 * model author drew), so a POSITIVE value here throws the beads toward it. A train can only
		 * accelerate forwards or backwards - the same axis - so this is captured exactly; sideways
		 * (curving) acceleration is not mirrored and is not attempted.</p>
		 */
		private void trackAcceleration(double elapsedSeconds, double speedMps) {
			if (speedKnown && elapsedSeconds > 1.0E-4) {
				final double raw = (speedMps - lastSpeedMps) / elapsedSeconds;
				// Smooth: one frame of mirrored speed is quantised, so the raw difference is spiky.
				lateralAcceleration += (raw - lateralAcceleration) * Math.min(1, elapsedSeconds * 8);
			}
			lastSpeedMps = speedMps;
			speedKnown = true;
		}

		/**
		 * The droplet model: <b>capillary pinning and runoff</b>, which is what makes rain on glass look
		 * like rain on glass rather than like falling dots.
		 *
		 * <h2>The three regimes a bead lives through</h2>
		 *
		 * <ol>
		 *   <li><b>Pinned.</b> A small bead does not move at all. Surface tension holds it against
		 *       gravity - this is why a windscreen in drizzle is covered in specks that sit still for
		 *       minutes. The previous model moved every bead every frame, which is exactly the "it looks
		 *       like falling dots" complaint.</li>
		 *   <li><b>Growing.</b> A bead in the rain collects water and swells. Nothing else happens to it
		 *       yet.</li>
		 *   <li><b>Running.</b> Past a critical radius {@code staticThresholdM} the bead is too heavy for
		 *       surface tension to hold. It starts to slide, accelerates, and lays down a WET TRAIL behind
		 *       it that stays on the glass long after the bead has gone.</li>
		 * </ol>
		 *
		 * <p>That critical radius is the whole effect, and it is <b>not uniform</b>: it scales with the
		 * bead's own radius, so a fat bead runs early and a fine one is pinned almost forever. A field of
		 * identical beads all starting to move at the same moment reads as an animation; a field where
		 * some are stuck and some are streaming reads as weather.</p>
		 *
		 * <h2>Trails are a wetness FIELD, not a tail on the bead</h2>
		 *
		 * <p>A running bead deposits moisture into a coarse {@link #wetness} grid and the deposit decays
		 * by evaporation. This is the difference between "a bead with a line drawn behind it" (the old
		 * model, which also lost the trail the instant the bead went off-screen) and a glass that stays
		 * streaked: the trail belongs to the GLASS, so it survives its bead, and the wiper has one field
		 * to clear instead of a hundred tails to chase.</p>
		 *
		 * <h2>Beads pinch off</h2>
		 *
		 * <p>A running bead sheds a smaller bead behind it every {@code pinchOffDistanceM}. That is the
		 * real mechanism behind a rivulet's speckled wake, and it is also what refills the pinned
		 * population without the spawner having to invent beads in mid-air.</p>
		 */
		private void advanceDrops(double elapsedSeconds, double speedMps, double[] down) {
			final double gravityDown = config.fallMps * (snow ? 0.35 : 1) * (0.6 + 0.9 * intensity);
			// Balance point: airflow == gravity. AIRFLOW_COEFFICIENT is fixed so the crossover sits at a
			// plausible line speed (about 22 m/s) rather than being another thing to tune per model.
			final double airflow = AIRFLOW_COEFFICIENT * speedMps * speedMps;
			final double surfaceSpeedClimb = Math.max(-gravityDown, Math.min(config.creepMps, airflow - gravityDown));
			final double lateralAccel = Math.max(-MAX_LATERAL_ACCELERATION, Math.min(MAX_LATERAL_ACCELERATION, lateralAcceleration));
			for (final Drop drop : drops) {
				advanceDrop(drop, elapsedSeconds, surfaceSpeedClimb, lateralAccel, down);
			}
			spawnForWeather(elapsedSeconds);
			mergeDrops();
			decayWetness(elapsedSeconds);
		}

		/** One bead: grow, decide whether it runs, move it, and deposit what it leaves behind. */
		private void advanceDrop(Drop drop, double elapsedSeconds, double surfaceSpeedClimb, double lateralAccel, double[] down) {
			// Growth first: a bead in a stream collects water and swells until it runs. This is the only
			// thing a pinned bead does.
			drop.radiusM = Math.min(config.maxBeadRadiusM, drop.radiusM + config.growthMps * elapsedSeconds * (0.4 + intensity));
			if (drop.ink < 1) {
				drop.ink = Math.min(1, drop.ink + elapsedSeconds * 0.8);
			}

			// Surface tension holds a bead of this size. Scaled by the bead's own personality across a
			// 1.1x..1.8x band. Calibrated against the offline harness rather than guessed: with 4.5 mm base
			// that is a 4.95..8.1 mm threshold, so at 0.8 mm/s a bead is PINNED for roughly 2.4 s to 7.7 s
			// and a shower settles into a MIX - most beads stuck, a minority streaming and laying trails.
			// (Two earlier bands failed in opposite directions: 0.35x..1.0x let almost everything run within
			// two seconds, 1.5x..2.5x left 89% of the field pinned at twenty seconds with no trails at all.)
			final double staticThreshold = config.staticThresholdM * (1.1 + 0.7 * drop.variation);
			final boolean running = drop.radiusM > staticThreshold || surfaceSpeedClimb > 0;
			if (!running) {
				// Pinned: NO motion at all. The bead still beads up as it grows, and it still dries.
				drop.surfaceSpeedMps = 0;
				drop.trail = Math.max(0, drop.trail - elapsedSeconds * TRAIL_DRY_MPS);
				return;
			}

			// A running bead moves at the surface speed, faster for a heavier one. Positive = running UP
			// the glass, negative = sliding down it; down[] points downhill, so "up" is the opposite.
			final double sizeScale = 0.5 + 0.5 * (drop.radiusM / Math.max(1.0E-6, config.maxBeadRadiusM));
			final double climbSpeed = surfaceSpeedClimb * sizeScale;
			// The acceleration term uses a nominal 0.35 s of exposure, NOT the frame time: a frame is
			// 16 ms, and 1.2 m/s^2 for 16 ms is 2 cm/s - invisible. 0.35 s is "how long the driver holds
			// the brake on before the beads have visibly moved", which is the effect wanted.
			final double exposureSeconds = 0.35;
			double lateralVelocity = drop.lateralVelocity + (lateralAccel * exposureSeconds + drop.random.nextGaussian() * config.jitterMps) * elapsedSeconds;
			lateralVelocity = Math.max(-MAX_LATERAL_VELOCITY, Math.min(MAX_LATERAL_VELOCITY, lateralVelocity));
			drop.lateralVelocity = lateralVelocity * Math.max(0, 1 - elapsedSeconds * 2.5);

			final double stepX = -down[0] * climbSpeed - down[1] * lateralVelocity;
			final double stepY = -down[1] * climbSpeed + down[0] * lateralVelocity;
			final double speed = Math.hypot(stepX, stepY);
			drop.x += stepX * elapsedSeconds;
			drop.y += stepY * elapsedSeconds;
			drop.surfaceSpeedMps = speed;
			if (speed > 1.0E-6) {
				drop.directionX = stepX / speed;
				drop.directionY = stepY / speed;
			}

			// The trail: distance run since the last deposit, capped so one fast bead cannot paint a
			// stripe the length of the screen in a single tick.
			drop.trail = Math.min(config.maxStreakM, drop.trail + speed * elapsedSeconds * (snow ? 0 : 1.6));
			depositWetness(drop, elapsedSeconds);

			// Pinch off: drop a smaller bead roughly every PINCH_OFF_DISTANCE_M of travel. This is what
			// puts the speckled wake behind a rivulet, and it refills the pinned population for free.
			if (!snow) {
				drop.pinchAccumulatorM += speed * elapsedSeconds;
				if (drop.pinchAccumulatorM >= PINCH_OFF_DISTANCE_M && drop.radiusM > config.staticThresholdM * 1.4) {
					drop.pinchAccumulatorM = 0;
					pinchOff(drop);
				}
			}

			// Off the glass: respawn somewhere else. -0.05..1.05 rather than 0..1 so beads enter and
			// leave past the frame instead of popping into existence at the edge.
			if (drop.x < -0.05 || drop.x > 1.05 || drop.y < -0.10 || drop.y > 1.10) {
				respawn(drop);
			}
		}

		/**
		 * Sheds a small bead behind a runner. The child is placed at the parent's centre - the parent has
		 * already moved past that point - and is small enough to be PINNED by surface tension, which is
		 * what makes a rivulet's wake a line of stationary specks.
		 */
		private void pinchOff(Drop parent) {
			Drop child = null;
			for (final Drop candidate : drops) {
				if (candidate != parent && candidate.ink <= 0.05) {
					child = candidate;
					break;
				}
			}
			if (child == null) {
				return;
			}
			child.x = parent.x - parent.directionX * 0.004;
			child.y = parent.y - parent.directionY * 0.004;
			child.radiusM = parent.radiusM * 0.55;
			child.ink = 0.75;
			child.trail = 0;
			child.lateralVelocity = 0;
			child.pinchAccumulatorM = 0;
			child.directionX = parent.directionX;
			child.directionY = parent.directionY;
		}

		// ---- the wetness field -------------------------------------------------------------------

		private double wetnessCellWidthM() {
			return Math.max(1.0E-3, anchor.widthM / Math.max(1, config.wetnessCells));
		}

		private double wetnessCellHeightM() {
			return Math.max(1.0E-3, anchor.heightM / Math.max(1, config.wetnessCells));
		}

		/**
		 * Adds moisture where a running bead has just been. The deposit is written to the bead's CURRENT
		 * cell each advance, so a bead crossing cells paints a continuous line - the deposit rate is per
		 * second, and the cell size is what makes the line the right thickness.
		 */
		private void depositWetness(Drop drop, double elapsedSeconds) {
			if (wetness == null || snow) {
				return;
			}
			final int cellX = (int) Math.floor(drop.x * config.wetnessCells);
			final int cellY = (int) Math.floor(drop.y * config.wetnessCells);
			if (cellX < 0 || cellY < 0 || cellX >= config.wetnessCells || cellY >= config.wetnessCells) {
				return;
			}
			final int index = cellY * config.wetnessCells + cellX;
			wetness[index] = (float) Math.min(1, wetness[index] + elapsedSeconds * config.wetnessDepositPerSecond);
		}

		/** Evaporation: a streak thins out and disappears, slowly. */
		private void decayWetness(double elapsedSeconds) {
			if (wetness == null || snow) {
				return;
			}
			final float decay = (float) (elapsedSeconds * config.wetnessDryPerSecond);
			for (int i = 0; i < wetness.length; i++) {
				if (wetness[i] > 0) {
					wetness[i] = Math.max(0, wetness[i] - decay);
				}
			}
		}


		/**
		 * A bead that ran off the glass is replaced by a new one entering it - <b>at a random size</b>.
		 *
		 * <p>Resetting to the minimum radius instead (the first version) looked harmless and was not: at
		 * steady state every bead that runs off comes back as a speck, so the whole population converges
		 * on the smallest possible size and the glass gets sparser and sparser. The population has to look
		 * like rain FALLING ON it, so a new bead is drawn from the same size distribution as the initial
		 * field.</p>
		 */
		private void respawn(Drop drop) {
			drop.x = random.nextDouble();
			drop.y = random.nextDouble();
			drop.radiusM = newBeadRadiusM();
			drop.trail = 0;
			drop.lateralVelocity = 0;
			drop.ink = 0;
			drop.directionX = 0;
			drop.directionY = -1;
			drop.surfaceSpeedMps = 0;
			drop.pinchAccumulatorM = 0;
		}

		/**
		 * Tops the field back up to {@code raindrops} beads. Beads are neither created nor destroyed on a
		 * whim: one that runs off the glass re-enters it, and this only replaces the ones a wipe has
		 * knocked out, so the population stays at the configured number and the only thing the weather
		 * changes is how fast a knocked-out bead becomes visible again.
		 */
		private void spawnForWeather(double elapsedSeconds) {
			if (intensity <= 0.02 || drops.length == 0) {
				return;
			}
			final int target = (int) Math.round(drops.length * (0.35 + 0.65 * intensity));
			int alive = 0;
			for (final Drop drop : drops) {
				if (drop.ink > 0.25) {
					alive++;
				}
			}
			if (alive >= target) {
				spawnAccumulator = 0;
				return;
			}
			spawnAccumulator += elapsedSeconds * config.spawnPerSecond * intensity;
			while (spawnAccumulator >= 1 && alive < target) {
				spawnAccumulator -= 1;
				// The oldest knocked-out bead is the one that has been invisible longest.
				Drop weakest = null;
				for (final Drop drop : drops) {
					if (weakest == null || drop.ink < weakest.ink) {
						weakest = drop;
					}
				}
				if (weakest == null || weakest.ink > 0.25) {
					break;
				}
				final double keepInk = weakest.ink;
				respawn(weakest);
				// Falling into the field is instant, not a fade: a bead that has just landed is as wet as
				// any other. The grow-in belongs to the WIPE, which is why a wiped bead keeps its low ink.
				weakest.ink = Math.max(keepInk, 0.55);
				alive++;
			}
		}

		/**
		 * Beads that touch merge, using a uniform spatial hash so this is linear in the bead count.
		 *
		 * <p>Pinch-off made this matter: the shed children roughly triple the population, and the old
		 * all-pairs pass was O(n^2) on every advance. Bucketing by a cell the size of the merge distance
		 * means a bead only ever looks at its own cell and its eight neighbours - the minimum any correct
		 * merge can get away with.</p>
		 */
		private void mergeDrops() {
			if (drops.length < 2) {
				return;
			}
			final double cellWidth = Math.max(config.mergeDistanceM / Math.max(1.0E-3, anchor.widthM), 1.0E-3);
			final double cellHeight = Math.max(config.mergeDistanceM / Math.max(1.0E-3, anchor.heightM), 1.0E-3);
			final int columns = Math.max(1, (int) Math.ceil(1 / cellWidth));
			final int rows = Math.max(1, (int) Math.ceil(1 / cellHeight));
			final Object2ObjectOpenHashMap<Long, ObjectArrayList<Drop>> cells = new Object2ObjectOpenHashMap<>();
			for (final Drop drop : drops) {
				if (drop.ink <= 0.05) {
					continue;
				}
				final int cellX = Math.max(0, Math.min(columns - 1, (int) (drop.x / cellWidth)));
				final int cellY = Math.max(0, Math.min(rows - 1, (int) (drop.y / cellHeight)));
				final long key = (long) cellY * columns + cellX;
				cells.computeIfAbsent(key, ignored -> new ObjectArrayList<>()).add(drop);
			}

			for (final Drop a : drops) {
				if (a.ink <= 0.05) {
					continue;
				}
				final int cellX = Math.max(0, Math.min(columns - 1, (int) (a.x / cellWidth)));
				final int cellY = Math.max(0, Math.min(rows - 1, (int) (a.y / cellHeight)));
				for (int offsetY = -1; offsetY <= 1; offsetY++) {
					for (int offsetX = -1; offsetX <= 1; offsetX++) {
						final int neighbourX = cellX + offsetX;
						final int neighbourY = cellY + offsetY;
						if (neighbourX < 0 || neighbourY < 0 || neighbourX >= columns || neighbourY >= rows) {
							continue;
						}
						final ObjectArrayList<Drop> bucket = cells.get((long) neighbourY * columns + neighbourX);
						if (bucket == null) {
							continue;
						}
						for (final Drop b : bucket) {
							mergePair(a, b);
						}
					}
				}
			}
		}

		/** Merges {@code b} into {@code a} when they touch, leaving {@code b} empty to be reused. */
		private void mergePair(Drop a, Drop b) {
			// A bead must not merge with itself, and the pair is visited from both directions.
			if (a == b || a.ink <= 0.05 || b.ink <= 0.05) {
				return;
			}
			final double deltaX = (a.x - b.x) * anchor.widthM;
			final double deltaY = (a.y - b.y) * anchor.heightM;
			if (deltaX * deltaX + deltaY * deltaY > config.mergeDistanceM * config.mergeDistanceM) {
				return;
			}
			// The larger bead absorbs the smaller, keeps its own place, and gets bigger.
			final Drop keeper = a.radiusM >= b.radiusM ? a : b;
			final Drop absorbed = keeper == a ? b : a;
			keeper.radiusM = Math.min(config.maxBeadRadiusM, Math.hypot(keeper.radiusM, absorbed.radiusM));
			keeper.ink = Math.min(1, keeper.ink + absorbed.ink * 0.5);
			respawn(absorbed);
			absorbed.ink = 0;
		}

		/**
		 * Rasterises the whole precipitation image.
		 *
		 * <p>Four things are deliberately layered, back to front, because that is the order they exist in
		 * on a real windscreen:</p>
		 *
		 * <ol>
		 *   <li>a faint wet sheen over the whole glass, so it reads as wet between the beads;</li>
		 *   <li>the SMEAR the blade leaves: the sector it has just crossed is drawn as a film, then the
		 *       glass is clear in its wake. Without this a wiper looks like an eraser;</li>
		 *   <li>each bead as a real shape - a highlight, a body, and a tapered head in the direction it
		 *       is running, the classic water-on-glass teardrop;</li>
		 *   <li>nothing at all where the blade is about to arrive, which is what makes the clean arc the
		 *       driver sees appear to track the arm.</li>
		 * </ol>
		 */
		private void rebuildCanvas() {
			final double widthM = anchor.widthM;
			final double heightM = anchor.heightM;
			canvas.fill(0, 0, widthM, heightM, 0x00000000);
			if (intensity <= 0.02) {
				// Nothing to draw; an all-transparent image keeps the last upload from lingering.
				texture.redraw(canvas, signature());
				return;
			}
			// A faint wet sheen, so the glass reads as wet between the beads.
			canvas.fill(0, 0, widthM, heightM, argb(0x12, 0xA8C4D8));
			// The trails, BEFORE the beads: a streak belongs to the glass, so it must sit under every bead
			// and survive the bead that drew it.
			drawWetness();

			// The wipe sector has to agree with the arm the model actually sees, or the glass goes clear
			// in a ring around the middle of the screen while the blade sweeps along the bottom. The
			// canvas origin is the TOP-left and the face's origin is bottom-left, so v is mirrored - the
			// ONLY place that conversion happens.
			final double pivotX = config.pivotU * widthM;
			final double pivotY = (1 - config.pivotV) * heightM;
			final boolean canWipe = config.wiper && bladeMoving;
			// A PARALLEL LINKAGE clears a BAND between two blade positions (its blade never passes
			// through a pivot, so no sector describes it). A single-pivot wiper keeps the angular sector
			// test it has always used, which is exact for that motion - so every existing model, and every
			// new single-axis one, behaves exactly as before.
			final boolean bandWipe = canWipe && config.usesBandWipe();
			final double[][] bladeFrom = bandWipe ? config.bladeSegmentM(wipedFromDeg, anchor) : null;
			final double[][] bladeTo = bandWipe ? config.bladeSegmentM(wiperAngleDeg, anchor) : null;
			if (canWipe) {
				if (bandWipe) {
					drawWiperFilmBand(bladeFrom, bladeTo);
				} else {
					drawWiperFilm(pivotX, pivotY);
				}
			}
			final double[] down = panelDown();

			for (final Drop drop : drops) {
				if (drop.ink <= 0.02) {
					continue;
				}
				final double dropX = drop.x * widthM;
				final double dropY = drop.y * heightM;
				if (canWipe) {
					final double wipe = bandWipe
							? wipeFactorBand(dropX, dropY, bladeFrom, bladeTo)
							: wipeFactor(dropX, dropY, pivotX, pivotY);
					if (wipe < 0) {
						continue;
					}
					if (wipe > 0) {
						// The blade knocks the bead off the glass and leaves a film where it was. The bead
						// is not deleted, it is knocked back and grows in again: that slow re-wetting
						// behind the blade is the whole reason a real wiper looks like a wiper and not an
						// eraser. Deleting it outright (the first implementation) made the glass snap dry,
						// and relying on the wipe alone made it snap WHEREVER the blade happened to be.
						//
						// `wipe` is 1 well behind the blade and falls to 0 at its leading edge, so a bead
						// fades out as the blade arrives and fades back in as it leaves. It is a geometric
						// distance, not a distance from the pivot, which is the bug that made an earlier
						// version clear a ring around the middle of the screen.
						final double target = RE_WET_INK + (1 - RE_WET_INK) * (1 - wipe);
						drop.trail = 0;
						drop.ink = Math.min(drop.ink, target);
						drop.lateralVelocity = 0;
						// The blade also takes the moisture OFF the glass, which is the visible work it does:
						// without this the trails would just sit there and a wipe would only move beads.
						clearWetness(dropX, dropY, wipe);
						continue;
					}
				}
				drawDrop(drop, dropX, dropY, down, widthM, heightM);
			}

			texture.redraw(canvas, signature());
		}

		/**
		 * The blade takes moisture off the glass. Called per bead inside the swept band, which is enough:
		 * the band is swept by many beads' worth of cells as it travels, so the field clears at the same
		 * rate beads are cleared. Scaling by {@code wipe} means the leading edge thins the film before the
		 * blade arrives, instead of leaving a hard line.
		 */
		private void clearWetness(double pointX, double pointY, double wipe) {
			if (wetness == null) {
				return;
			}
			final int cellX = (int) Math.floor(pointX / wetnessCellWidthM());
			final int cellY = (int) Math.floor(pointY / wetnessCellHeightM());
			final int cells = config.wetnessCells;
			if (cellX < 0 || cellY < 0 || cellX >= cells || cellY >= cells) {
				return;
			}
			final int index = cellY * cells + cellX;
			wetness[index] = (float) (wetness[index] * Math.max(0, 1 - wipe * 0.5));
		}

		/**
		 * How hard the blade is clearing a point: 1 = fully wiped, 0 = untouched, and -1 = the point is
		 * outside the arm's reach entirely (so the caller can skip it).
		 *
		 * <p>The wiped region is simply <b>from the park angle to the blade</b>, expressed in the arm's
		 * own direction of travel. That formulation is worth spelling out, because two earlier versions
		 * got it wrong in opposite directions:</p>
		 *
		 * <ul>
		 *   <li>"between the previous angle and the current angle" (what the sector test first was) is only
		 *       the lost ground of ONE repaint - at 90 ms that is 12 deg, so the glass re-wetted itself
		 *       behind the blade and nothing ever looked cleared.</li>
		 *   <li>"within N degrees either side of the blade" wipes half a stroke's worth of glass the
		 *       instant the arm leaves park, because the leading and trailing sides are indistinguishable
		 *       from the angular offset alone.</li>
		 * </ul>
		 *
		 * <p>Park-to-blade has neither problem: it is unambiguous without any sign test, it is exactly the
		 * region the blade has crossed, and it stays correct through the turnaround, where the swept extent
		 * is momentarily the whole arc in both directions anyway.</p>
		 */
		private double wipeFactor(double pointX, double pointY, double pivotX, double pivotY) {
			final double deltaX = pointX - pivotX;
			final double deltaY = pointY - pivotY;
			final double reach = config.armM(anchor);
			if (deltaX * deltaX + deltaY * deltaY > reach * reach) {
				return -1;
			}
			// Sheet v grows DOWNWARD and the face's up grows the other way, so the vertical component is
			// negated here to put the angle back in the face's own frame (0 = right edge, + = up).
			final double angleDeg = Math.toDegrees(Math.atan2(-deltaY, deltaX));
			// How far this point is along the arm's travel, measured from park.
			final double fromParkDeg = normaliseSigned(angleDeg - config.parkAngleDeg) * config.sweepSign;
			final double sweptDeg = normaliseSigned(wiperAngleDeg - config.parkAngleDeg) * config.sweepSign;
			if (fromParkDeg < 0 || fromParkDeg > Math.max(0, sweptDeg) + WIPE_FADE_DEG) {
				return 0;
			}
			if (fromParkDeg <= sweptDeg) {
				// The blade has crossed it: fully wiped.
				return 1;
			}
			// The leading edge: not touched at the far side of the fade, fully wiped at the blade.
			return 1 - (fromParkDeg - sweptDeg) / WIPE_FADE_DEG;
		}

		/** Whether a point is being cleared at all; kept for readability at the call site. */
		private boolean wasWiped(double pointX, double pointY, double pivotX, double pivotY) {
			return wipeFactor(pointX, pointY, pivotX, pivotY) > 0;
		}

		/**
		 * The same question as {@link #wipeFactor}, asked of a MODELLED blade instead of an angular
		 * sector: is this point inside the band the blade swept since the last repaint?
		 *
		 * <p>This is what makes a parallel linkage work. A sector test is only valid when the blade
		 * passes through the pivot (a single-axis wiper); a pantograph blade does not, it translates
		 * across the glass, and the region it clears is the quadrilateral between where it was and where
		 * it is now.</p>
		 *
		 * <p>Returns 1 well inside the band, 1..0 across the leading edge, and 0 elsewhere. It never
		 * returns -1: that value means "skip this bead entirely" to the caller, and a bead the blade has
		 * not touched must still be DRAWN.</p>
		 *
		 * @param from the blade at the previous repaint's angle, {@code {{ax, ay}, {bx, by}}}
		 * @param to   the blade now
		 */
		private static double wipeFactorBand(double pointX, double pointY, double[][] from, double[][] to) {
			final double[] ax = {from[0][0], from[0][1], to[0][0], to[0][1]};
			final double[] ay = {from[1][0], from[1][1], to[1][0], to[1][1]};
			if (insideConvexQuad(pointX, pointY, ax, ay)) {
				return 1;
			}
			// The leading edge: the blade is on its way here, so thin the film and knock the bead back
			// gradually instead of snapping at a hard line.
			final double distance = distanceToSegmentM(pointX, pointY, to[0], to[1]);
			return distance >= WIPE_FADE_M ? 0 : 1 - distance / WIPE_FADE_M;
		}

		private static boolean insideConvexQuad(double px, double py, double[] xs, double[] ys) {
			boolean positive = false, negative = false;
			for (int i = 0; i < 4; i++) {
				final int j = (i + 1) % 4;
				final double cross = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i]);
				if (cross > 1.0E-9) positive = true;
				if (cross < -1.0E-9) negative = true;
			}
			return !(positive && negative);
		}

		private static double distanceToSegmentM(double px, double py, double[] a, double[] b) {
			final double dx = b[0] - a[0];
			final double dy = b[1] - a[1];
			final double lengthSquared = dx * dx + dy * dy;
			final double t = lengthSquared < 1.0E-12 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (py - a[1]) * dy) / lengthSquared));
			return Math.hypot(px - (a[0] + dx * t), py - (a[1] + dy * t));
		}

		/**
		 * The film the blade is dragging, for a MODELLED blade: the quadrilateral between where the
		 * blade was at the last repaint and where it is now. For a single-axis wiper that quadrilateral
		 * is a triangle through the pivot - the sector the old sector-fill drew, minus the arc bulge -
		 * and for a parallel linkage it is the band that slid across the glass.
		 */
		private void drawWiperFilmBand(double[][] from, double[][] to) {
			canvas.fillPolygon(
					new double[]{from[0][0], from[1][0], to[1][0], to[0][0]},
					new double[]{from[0][1], from[1][1], to[1][1], to[0][1]},
					argb(0x2E, 0xE8F4FF)
			);
			canvas.line(to[0][0], to[0][1], to[1][0], to[1][1], 0.02, argb(0x38, 0xFFFFFF));
		}

		/**
		 * The film the blade is dragging. Drawn as the sector between where the arm was at the last
		 * repaint and where it is now, so it is ALWAYS exactly the glass the blade has just crossed -
		 * no particle bookkeeping, no lifetime to tune, and it self-corrects when the arm reverses.
		 */
		private void drawWiperFilm(double pivotX, double pivotY) {
			final double from = wipedFromDeg;
			final double to = wiperAngleDeg;
			if (Math.abs(to - from) < 0.05) {
				return;
			}
			// Widen the sector slightly so no un-swept sliver is left between frames.
			final double low = Math.min(from, to) - 2;
			final double high = Math.max(from, to) + 2;
			canvas.fillSector(pivotX, pivotY, config.armM(anchor), low, high, argb(0x2E, 0xE8F4FF));
			// A brighter, thinner core, so the film has a wet leading edge rather than a flat wash.
			canvas.arc(pivotX, pivotY, config.armM(anchor) * 0.97, 0.02, low, high, argb(0x38, 0xFFFFFF));
		}

		/**
		 * Paints the wetness field: one soft blob per cell that holds moisture.
		 *
		 * <p>Blobs are drawn about twice the cell size so neighbouring cells overlap and a run of wet cells
		 * reads as a continuous streak instead of a dotted line. Cells are square in PIXELS but not in
		 * metres (the glass is wider than it is tall), so each cell is drawn as an ellipse of its own
		 * metre size - drawing it round would make every streak lean.</p>
		 */
		private void drawWetness() {
			if (wetness == null) {
				return;
			}
			final int cells = config.wetnessCells;
			final double cellWidthM = wetnessCellWidthM();
			final double cellHeightM = wetnessCellHeightM();
			for (int cellY = 0; cellY < cells; cellY++) {
				for (int cellX = 0; cellX < cells; cellX++) {
					final float amount = wetness[cellY * cells + cellX];
					if (amount <= 0.02F) {
						continue;
					}
					// The trail is a film seen through glass, so it is pale and low-contrast - a strong
					// alpha here would make the screen read as fogged rather than as streaked.
					final int alpha = (int) Math.min(0x3C, 0x0C + amount * 0x3C);
					canvas.circle(
							(cellX + 0.5) * cellWidthM,
							(cellY + 0.5) * cellHeightM,
							Math.max(cellWidthM, cellHeightM) * 0.85,
							argb(alpha, 0xBEDCEC)
					);
				}
			}
		}

		/**
		 * One bead: highlight, body, and - when it is running - a tapered head pointing the way it went.
		 *
		 * <p>The tail direction is the drop's OWN last movement rather than "down the glass": a bead that
		 * is creeping up at line speed must streak upward, or the whole airflow model reads as noise.</p>
		 */
		private void drawDrop(Drop drop, double dropX, double dropY, double[] down, double widthM, double heightM) {
			final double radiusM = drop.radiusM * (0.7 + 0.6 * drop.variation) * (0.5 + 0.5 * drop.ink);
			if (radiusM <= 0) {
				return;
			}
			final boolean running = !snow && drop.trail > 0.004;
			final double tailM = running ? Math.min(config.maxStreakM, drop.trail) : 0;
			// Sheet space scales x and y by different numbers of metres, so scale the direction the same
			// way before using it as a length - otherwise a wide screen makes every streak lean sideways.
			double dirSheetX = 0;
			double dirSheetY = 0;
			if (running) {
				dirSheetX = drop.directionX * widthM;
				dirSheetY = drop.directionY * heightM;
				final double magnitude = Math.hypot(dirSheetX, dirSheetY);
				if (magnitude < 1.0E-6) {
					// No recorded movement (a bead that has just respawned): fall back to the glass slope.
					dirSheetX = down[0] * widthM;
					dirSheetY = down[1] * heightM;
					final double fallback = Math.max(1.0E-6, Math.hypot(dirSheetX, dirSheetY));
					dirSheetX /= fallback;
					dirSheetY /= fallback;
				} else {
					dirSheetX /= magnitude;
					dirSheetY /= magnitude;
				}
			}

			final double headX = dropX + dirSheetX * tailM;
			final double headY = dropY + dirSheetY * tailM;
			if (running) {
				// The wake: a tapering line from the head to the bead. Two strokes give it a soft edge.
				canvas.line(headX, headY, dropX, dropY, radiusM * 1.7, argb((int) (0x22 * drop.ink), 0xC8E0F4));
				canvas.line(headX, headY, dropX, dropY, radiusM * 0.95, argb((int) (0x4A * drop.ink), 0xE4F0FF));
			}
			// Body. Snow gets a matte white flake, rain a cool translucent bead.
			final int body = snow ? argb((int) (0xC0 * drop.ink), 0xFFFFFF) : argb((int) (0x86 * drop.ink), 0xDCEBF8);
			canvas.circle(dropX, dropY, radiusM, body);
			if (!snow) {
				// Rim, so a bead reads as a lens and not a flat dot.
				canvas.arc(dropX, dropY, radiusM * 0.92, radiusM * 0.22, 0, 360, argb((int) (0x50 * drop.ink), 0x9CC0DC));
				// Specular highlight, offset up and to the right (the sky is above and the light comes
				// over the driver's shoulder on both ends of a double-headed loco).
				canvas.circle(dropX + radiusM * 0.26, dropY + radiusM * 0.30, Math.max(0.0008, radiusM * 0.30),
						argb((int) (0xCC * drop.ink), 0xFFFFFF));
			}
		}

		/**
		 * Everything the image depends on, so an identical image is not re-uploaded.
		 *
		 * <p>The blade angle is included at a coarse quantum, and the BEAD FIELD as a coarse position
		 * hash - that second term is load-bearing. {@code MmtrPanelTexture.needsRedraw} compares the whole
		 * string, so a signature without it makes the rain freeze the moment the wiper is parked: the
		 * image would be rebuilt at 11 Hz but with the same content every time, because nothing in the
		 * signature moved.</p>
		 */
		private String signature() {
			final StringBuilder builder = new StringBuilder();
			builder.append(Math.round(wiperAngleDeg * 4)).append(':').append(Math.round(intensity * 16)).append(':').append(snow ? 1 : 0);
			// Coarse on purpose: this only has to change when the picture would change, and the beads move
			// far less than a pixel per repaint at 11 Hz.
			int hash = 0;
			for (final Drop drop : drops) {
				hash = hash * 31 + (int) Math.round(drop.y * 40) * 7 + (int) Math.round(drop.x * 40) + (int) Math.round(drop.ink * 8);
			}
			return builder.append(':').append(hash).toString();
		}

		private void draw(StoredMatrixTransformations carTransform) {
			final Plane plane = Plane.of(anchor);
			if (plane == null) {
				return;
			}
			logPlaneOnce(anchor.name + "@" + anchor.car, plane, anchor.widthM, anchor.heightM);
			drawRain(carTransform, plane);
			// ONE wiper per anchor. A pair of wipers is two mmtr_windshield_<cab>_<pane> quads, each with
			// its own pivot and its own park direction - no mirroring rule to get wrong.
			//
			// config.wiper is the ANIMATION (does this glass get wiped at all); config.drawBlade is the
			// mod's OWN blade geometry. A model that carries a solid wiper_<cab>_<pane> part sets
			// drawBlade=false so the two blades do not sit on top of each other, while the glass still
			// gets wiped and the (modelled) arm is what the animation is meant to move.
			if (config.wiper && config.drawBlade) {
				drawWiper(carTransform, plane);
			}
		}

		/**
		 * The precipitation image, placed from the face's centre like the cab panel does.
		 *
		 * <p>Unlike the cab panel this is drawn on BOTH sides by default, and that is not a nicety: the
		 * panel's "which side is the driver on?" test asks whether the face normal points at the car's
		 * centre, which is right for a dashboard but meaningless for a windscreen (the driver and the
		 * weather are on OPPOSITE sides of the glass) and degenerates completely for a side window, where
		 * the normal has no horizontal component towards the origin. Drawing both sides sidesteps the
		 * question and is also what the player sees from outside.</p>
		 *
		 * <p>ONE quad is enough: this goes into {@code EXTERIOR_TRANSLUCENT_DOUBLE}, which resolves to
		 * {@code getEntityTranslucent} and therefore does NOT cull back faces. The earlier version drew two
		 * copies inside {@code EXTERIOR_TRANSLUCENT} (which is {@code getEntityTranslucentCull}), so both
		 * copies faced the same way, both were culled from behind, and the "second side" only doubled the
		 * blend on the side that was already visible.</p>
		 */

		private void drawRain(StoredMatrixTransformations carTransform, Plane plane) {
			final float uLeft = anchor.panelFlipU ? 1 : 0;
			final float uRight = anchor.panelFlipU ? 0 : 1;
			final Vector[] corners = quadCorners(plane, anchor.widthM, anchor.heightM, WATER_OFFSET_M);
			final Vector c0 = corners[0];
			final Vector c1 = corners[1];
			final Vector c2 = corners[2];
			final Vector c3 = corners[3];

			// twoSided picks the LAYER, not a second quad: EXTERIOR_TRANSLUCENT_DOUBLE is
			// getEntityTranslucent (no back-face culling) while EXTERIOR_TRANSLUCENT is
			// getEntityTranslucentCull, so a single quad is visible from both sides on the former and from
			// one side on the latter. Default is the two-sided one; a rear window that is only ever looked
			// at from the platform can set twoSided=false and save the blend.
			final QueuedRenderLayer layer = config.twoSided ? QueuedRenderLayer.EXTERIOR_TRANSLUCENT_DOUBLE : QueuedRenderLayer.EXTERIOR_TRANSLUCENT;
			MainRenderer.scheduleRender(texture.identifier(), false, layer, (graphicsHolder, offsetVector) -> {
				carTransform.transform(graphicsHolder, offsetVector);
				// Corners are given as bottom-left, bottom-right, top-right, top-left in the FACE's own
				// basis, and v2 is the TOP edge (the cab panel's hard-won contract). No local rotation is
				// involved: the points above are already absolute car-local positions on the glass.
				//
				// The Vector3d overload is named EXPLICITLY on purpose. There are three near-identical
				// drawTexture overloads, and a 13-argument call also matches
				// (x, y, width, height, u1, v1, u2, v2, facing, color, light) - where the 4th corner
				// silently becomes a `height` and the quad collapses into a sliver. Passing the
				// playerOffset makes the 4-corner overload the only candidate.
				IDrawing.drawTexture(
						graphicsHolder,
						(float) c0.x(), (float) c0.y(), (float) c0.z(),
						(float) c1.x(), (float) c1.y(), (float) c1.z(),
						(float) c2.x(), (float) c2.y(), (float) c2.z(),
						(float) c3.x(), (float) c3.y(), (float) c3.z(),
						new Vector3d(0, 0, 0),
						uLeft, 0, uRight, 1,
						Direction.UP, IGUI_WHITE, GraphicsHolder.getDefaultLight()
				);
				graphicsHolder.pop();
			});
		}

		/**
		 * The arm: two flat quads (a thin blade and a thinner arm above it) rotated inside the wiper
		 * plane. Solid colour, so the model author supplies geometry, not artwork.
		 *
		 * <p>Angles are measured from the face's own "right" edge, so the modelled quad states the park
		 * direction outright: draw the wiper face with its right edge pointing where a parked blade should
		 * lie, and {@code parkAngleDeg = 0} is correct.</p>
		 *
		 * <p>The PIVOT is at the middle of the face's BOTTOM edge (a wiper is mounted on the scuttle, not
		 * in the middle of the screen). The face's centre is still the reference for the swept sector, so
		 * the two are deliberately different points and {@code pivotV} can lift the pivot if a model wants
		 * it higher.</p>
		 */
		private void drawWiper(StoredMatrixTransformations carTransform, Plane plane) {
			final double armM = config.armM(anchor);
			// Distance from the face CENTRE down to the pivot, in metres: half the face height minus the
			// configured inset. Negative only if pivotV > 0.5, which is the author's business.
			final double pivotAlongRight = config.pivotU * anchor.widthM - anchor.widthM / 2;
			final double pivotAlongUp = config.pivotV * anchor.heightM - anchor.heightM / 2;
			drawOneWiper(carTransform, plane, false, armM, pivotAlongRight, pivotAlongUp);
			if (config.dualWiper) {
				// Two blades on one anchor (a pair of wipers sharing the scuttle): the second mirrors the
				// first about the face's vertical centre line and sweeps the other way. A pair that needs
				// its own pivots wants one anchor each (mmtr_windshield_1 / _2) instead.
				drawOneWiper(carTransform, plane, true, armM, -pivotAlongRight, pivotAlongUp);
			}
		}

		private void drawOneWiper(StoredMatrixTransformations carTransform, Plane plane, boolean mirrored, double armM, double pivotAlongRight, double pivotAlongUp) {
			final double radians = Math.toRadians(wiperAngleDeg);
			// Angle 0 = the face's right edge; +angle rotates UP (right -> up is counterclockwise seen
			// from +normal, because right = up x normal). sweepSign flips it if the model faces the
			// other way; flipping the anchor's normal is the other way to reverse it.
			final double sign = mirrored ? -config.sweepSign : config.sweepSign;
			final double alongRight = Math.cos(radians) * sign;
			final double alongUp = Math.sin(radians) * sign;

			drawWiperBar(carTransform, plane, pivotAlongRight, pivotAlongUp, alongRight, alongUp,
					armM, config.bladeWidthM, config.colour, WIPER_OFFSET_M, WHITE_TEXTURE);
			// The arm stalk: shorter, thinner, a shade lighter, sitting just under the blade.
			drawWiperBar(carTransform, plane, pivotAlongRight, pivotAlongUp, alongRight, alongUp,
					armM * 0.98, config.bladeWidthM * 0.55, config.armColour, WIPER_OFFSET_M - 0.002F, WHITE_TEXTURE);
		}

		/**
		 * One bar of the wiper, drawn on BOTH sides so it reads from inside and outside the cab.
		 *
		 * <p>{@code LIGHT_2} is {@code RenderLayer.getText()}, which keeps back-face culling, so a single
		 * quad is only visible from one side. The second copy lifts the quad the other way along the
		 * normal and reverses the winding, which keeps the blade's shape correct from behind.</p>
		 */
		private void drawWiperBar(StoredMatrixTransformations carTransform, Plane plane, double pivotAlongRight, double pivotAlongUp,
				double alongRight, double alongUp, double lengthM, double widthM, int colour, float offsetM, Identifier textureIdentifier) {
			final double halfWidth = widthM / 2;
			// Perpendicular inside the plane, so the bar gains width without leaving the glass.
			final double perpendicularRight = -alongUp;
			final double perpendicularUp = alongRight;

			final Vector pivot = plane.pointAt(pivotAlongRight, pivotAlongUp);
			final Vector tip = plane.pointAt(pivotAlongRight + alongRight * lengthM, pivotAlongUp + alongUp * lengthM);
			final Vector side = plane.direction(perpendicularRight * halfWidth, perpendicularUp * halfWidth);

			final Vector nearLeft = add(pivot, scale(side, -1));
			final Vector nearRight = add(pivot, side);
			final Vector farRight = add(tip, side);
			final Vector farLeft = add(tip, scale(side, -1));

			// Two copies, one per side. The visible (front) copy keeps the natural winding; the back copy
			// gets the reversed winding AND is pushed the other way along the normal, so it survives
			// back-face culling from behind without mirroring the blade's shape.
			for (final int face : new int[]{1, -1}) {
				final float lift = offsetM * face;
				final Vector q1 = offsetAlong(face > 0 ? nearLeft : nearLeft, plane.normal, lift);
				final Vector q2 = offsetAlong(face > 0 ? nearRight : farLeft, plane.normal, lift);
				final Vector q3 = offsetAlong(face > 0 ? farRight : farRight, plane.normal, lift);
				final Vector q4 = offsetAlong(face > 0 ? farLeft : nearRight, plane.normal, lift);
				MainRenderer.scheduleRender(textureIdentifier, false, QueuedRenderLayer.LIGHT_2, (graphicsHolder, offset) -> {
					carTransform.transform(graphicsHolder, offset);
					IDrawing.drawTexture(
							graphicsHolder,
							(float) q1.x(), (float) q1.y(), (float) q1.z(),
							(float) q2.x(), (float) q2.y(), (float) q2.z(),
							(float) q3.x(), (float) q3.y(), (float) q3.z(),
							(float) q4.x(), (float) q4.y(), (float) q4.z(),
							new Vector3d(0, 0, 0),
							0.02F, 0.02F, 0.03F, 0.03F,
							Direction.UP, colour, GraphicsHolder.getDefaultLight()
					);
					graphicsHolder.pop();
				});
			}
		}

		/**
		 * World down projected onto the glass plane, expressed in the plane's (right, up) basis.
		 * Falls back to straight down the panel when the glass is horizontal enough to decide nothing.
		 */
		private double[] panelDown() {
			final Plane plane = Plane.of(anchor);
			if (plane == null) {
				return new double[]{0, -1};
			}
			final double alongRight = -plane.right.y();
			final double alongUp = -plane.up.y();
			final double magnitude = Math.sqrt(alongRight * alongRight + alongUp * alongUp);
			if (magnitude < 1.0E-4) {
				return new double[]{0, -1};
			}
			return new double[]{alongRight / magnitude, alongUp / magnitude};
		}

		/**
		 * The size range a NEW bead is drawn from.
		 *
		 * <p>Spread out (so the field is not all one size, and beads do not all cross the pinning threshold
		 * together) but deliberately capped below {@code staticThresholdM}: a bead must ARRIVE pinned and
		 * grow into a runner, otherwise rain lands already streaming and the glass loses its specks
		 * entirely. Writing the cap as {@code min(maxBeadRadius, threshold)} means raising the threshold in
		 * a config can never make new beads spawn above it.</p>
		 */
		private double newBeadRadiusM() {
			final double ceiling = Math.min(config.maxBeadRadiusM, config.staticThresholdM);
			return config.minBeadRadiusM + random.nextDouble() * Math.max(0, ceiling - config.minBeadRadiusM) * 0.9;
		}

		/**
		 * A fresh bead on the glass: random place, random personality, and a random STARTING SIZE.
		 *
		 * <p>The spread matters. If every bead enters at the minimum radius and grows at the same rate,
		 * they all cross the pinning threshold at the same moment and the field pulses - a wave of
		 * simultaneous runners instead of a glass where some beads are stuck and others are streaming.</p>
		 */
		private Drop newDrop(double x, double y) {
			final Drop drop = new Drop(wrap(x), wrap(y), random.nextDouble(), new Random(random.nextLong()));
			drop.radiusM = newBeadRadiusM();
			return drop;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Geometry helpers
	// ---------------------------------------------------------------------------------------------

	/** The glass face in model space, with a right-handed (right, up, normal) basis. */
	private static final class Plane {

		private final Vector position;
		private final Vector normal;
		private final Vector up;
		private final Vector right;
		private final double yaw;
		private final double pitch;
		private final double roll;

		private Plane(Vector position, Vector normal, Vector up, Vector right) {
			this.position = position;
			this.normal = normal;
			this.up = up;
			this.right = right;
			// The same Euler solve the cab panel uses: rotateY(yaw) * rotateX(pitch) * rotateZ(roll) =
			// [right, up, normal]. Kept identical so both features agree on what "the face" is.
			this.pitch = Math.toDegrees(Math.asin(clamp(-up.y())));
			this.yaw = Math.toDegrees(Math.atan2(normal.x(), normal.z()));
			this.roll = Math.toDegrees(Math.atan2(up.x(), up.y()));
		}

		@Nullable
		private static Plane of(Anchor anchor) {
			final Vector normal = toModelSpace(anchor.fileNormal).normalize();
			if (length(normal) < 1.0E-6) {
				return null;
			}
			final Vector up = orthonormalise(toModelSpace(anchor.fileUp).normalize(), normal);
			final Vector right = cross(up, normal).normalize();
			return new Plane(toModelSpace(anchor.filePosition), normal, up, right);
		}

		/** A point in the plane, {@code alongRight}/{@code alongUp} in metres from the face centre. */
		private Vector pointAt(double alongRight, double alongUp) {
			return new Vector(
					position.x() + right.x() * alongRight + up.x() * alongUp,
					position.y() + right.y() * alongRight + up.y() * alongUp,
					position.z() + right.z() * alongRight + up.z() * alongUp
			);
		}

		/** An in-plane offset vector (a direction, not a point). */
		private Vector direction(double alongRight, double alongUp) {
			return new Vector(
					right.x() * alongRight + up.x() * alongUp,
					right.y() * alongRight + up.y() * alongUp,
					right.z() * alongRight + up.z() * alongUp
			);
		}
	}

	private static Vector toModelSpace(Vector fileVector) {
		return new Vector(fileVector.x(), -fileVector.y(), -fileVector.z());
	}

	private static Vector orthonormalise(Vector vector, Vector normal) {
		final double projected = dot(vector, normal);
		final Vector result = new Vector(vector.x() - normal.x() * projected, vector.y() - normal.y() * projected, vector.z() - normal.z() * projected);
		return length(result) < 1.0E-4 ? new Vector(0, 1, 0) : result.normalize();
	}

	private static Vector cross(Vector a, Vector b) {
		return new Vector(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
	}

	private static Vector add(Vector a, Vector b) {
		return new Vector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
	}

	private static Vector scale(Vector vector, double factor) {
		return new Vector(vector.x() * factor, vector.y() * factor, vector.z() * factor);
	}

	private static Vector offsetAlong(Vector point, Vector direction, double distance) {
		return new Vector(point.x() + direction.x() * distance, point.y() + direction.y() * distance, point.z() + direction.z() * distance);
	}

	/**
	 * The four corners of the layer, built DIRECTLY from the face's own basis vectors.
	 *
	 * <p>This deliberately does not go through the generic Euler solve that {@code MmtrPanelQuad} uses.
	 * That solve was written for dashboard-style upright panels: the windshield's authored "up" edge comes
	 * out running ALONG the glass rather than up it, so reusing the panel's rotation put the layer in a
	 * plane rotated 90 degrees about the car's length axis - standing across the windscreen instead of
	 * lying on it. Placing the corners as {@code centre + right*a + up*b + normal*lift} is the windshield
	 * plane BY CONSTRUCTION, whatever winding the model happens to have.</p>
	 */
	private static Vector[] quadCorners(Plane plane, double widthM, double heightM, double liftM) {
		final double halfWidthM = widthM / 2;
		final double halfHeightM = heightM / 2;
		return new Vector[]{
				offsetAlong(plane.pointAt(-halfWidthM, -halfHeightM), plane.normal, liftM),
				offsetAlong(plane.pointAt(halfWidthM, -halfHeightM), plane.normal, liftM),
				offsetAlong(plane.pointAt(halfWidthM, halfHeightM), plane.normal, liftM),
				offsetAlong(plane.pointAt(-halfWidthM, halfHeightM), plane.normal, liftM)
		};
	}

	private static double dot(Vector a, Vector b) {
		return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
	}

	private static double length(Vector vector) {
		return Math.sqrt(dot(vector, vector));
	}

	private static double clamp(double value) {
		return Math.max(-1, Math.min(1, value));
	}

	private static double wrap(double value) {
		return value - Math.floor(value);
	}

	/** Normalises an angle into [0, 360), so a swept sector can be compared without sign surprises. */
	private static double normaliseDegrees(double degrees) {
		final double wrapped = degrees % 360;
		return wrapped < 0 ? wrapped + 360 : wrapped;
	}

	/** Normalises an angle into (-180, 180], so "how far past the blade" has a sign. */
	private static double normaliseSigned(double degrees) {
		final double wrapped = normaliseDegrees(degrees);
		return wrapped > 180 ? wrapped - 360 : wrapped;
	}

	private static int argb(int alpha, int rgb) {
		return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
	}

	private static final int IGUI_WHITE = IGui.ARGB_WHITE;

	/**
	 * A precipitation bead on the glass.
	 *
	 * <p>Position is 0..1 in the face's own coordinates ({@code y} = 0 at the bottom edge), {@code trail}
	 * is how far it has run IN METRES, and {@code radiusM} is the bead's actual size - a bead is a
	 * physical object with a size, not a fixed-size sprite, which is what lets a shower look like a
	 * shower and a downpour look like one too.</p>
	 */
	private static final class Drop {

		private double x;
		private double y;
		/** Fixed personality (0..1): how big this bead grew and how readily it runs. */
		private final double variation;
		private final Random random;
		private double trail;
		/** Direction of the last run, in face coordinates, so the trail points the right way. */
		private double directionX;
		private double directionY = -1;
		/** Bead radius in metres. */
		private double radiusM;
		/** Sideways speed left over from the last frame (m/s), damped by friction on the glass. */
		private double lateralVelocity;
		/** The bead's surface velocity on the last advance (m/s). Read by the offline harness. */
		private double surfaceSpeedMps;
		/** Travel since the last shed bead, so a rivulet pinches off at a fixed SPACING not a rate. */
		private double pinchAccumulatorM;
		/** 1 = fully formed; a wiped bead is knocked back and grows in again, which reads as a smear. */
		private double ink = 1;

		private Drop(double x, double y, double variation, Random random) {
			this.x = x;
			this.y = y;
			this.variation = variation;
			this.random = random;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Config: assets/mtr/mmtr_anchors_<id>.json -> "windshield": { "<anchorName>": { ... } }
	// ---------------------------------------------------------------------------------------------

	private static final class WindshieldConfig {

		private static final Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, WindshieldConfig>> CACHE = new Object2ObjectOpenHashMap<>();
		private static final WindshieldConfig DEFAULT = new WindshieldConfig(new JsonObject());

		private final int raindrops;
		private final double fallMps;
		private final double maxStreakM;
		/** Whether this anchor carries a wiper at all (false = rain only, e.g. a rear screen). */
		private final boolean wiper;
		/**
		 * Whether the CLIENT draws its own blade. False when the model carries a solid wiper part
		 * ({@code wiper_<cab>_<pane>}): the glass must still be wiped (that is {@link #wiper}), but the
		 * drawn blade would sit on top of the modelled one. The packager sets this from the model, so a
		 * model without a solid wiper keeps the drawn blade exactly as before.
		 */
		private final boolean drawBlade;
		/** 0 = derive the arm from the modelled quad (half its shorter side). */
		private final double armMConfigured;
		private final double parkAngleDeg;
		private final double sweepDeg;
		private final double sweepSign;
		private final double periodS;
		private final double bladeWidthM;
		private final int colour;
		private final int armColour;
		private final boolean snow;
		/** Draw the precipitation on BOTH sides of the face (default). See {@code drawRain}. */
		private final boolean twoSided;
		/** A second blade mirroring the first about the plane's centre (a single anchor covering a pair). */
		private final boolean dualWiper;
		/**
		 * Where the wiper is mounted on the modelled face, as fractions of its width/height (0..1, origin
		 * bottom-left, same convention as the panel layout). Defaults put the pivot at the middle of the
		 * BOTTOM edge, because a wiper sits on the scuttle and not in the centre of the screen. The swept
		 * sector's reference point is still the face centre, so these two are independent.
		 */
		private final double pivotU;
		private final double pivotV;
		/**
		 * The SECOND pivot of a parallel-linkage wiper, and the blade's two ends in its parked position.
		 * All in the same fractions-from-the-left/bottom convention as {@link #pivotU}/{@link #pivotV}.
		 *
		 * <p>Absent for a wiper the models does not carry geometry for, in which case the client keeps
		 * drawing and sweeping its own synthetic blade along the arm - exactly as it always did.</p>
		 */
		private final boolean hasBlade;
		private final double pivot2U;
		private final double pivot2V;
		private final double bladeAU;
		private final double bladeAV;
		private final double bladeBU;
		private final double bladeBV;

		// --- droplet physics (all optional; the defaults are tuned for a raked main windscreen) -------
		/** Ceiling on the upward creep the airflow produces at line speed (m/s). */
		private final double creepMps;
		/** Random ride-quality jitter applied to every bead (m/s). */
		private final double jitterMps;
		private final double minBeadRadiusM;
		private final double maxBeadRadiusM;
		/** How fast a bead swells while it sits in the rain (m/s). */
		private final double growthMps;
		/** New beads per second at full intensity. */
		private final double spawnPerSecond;
		/**
		 * Bead radius past which surface tension can no longer hold it, so it starts to run (metres).
		 *
		 * <p>THE parameter of this effect, and it only means anything next to {@code growthMps}: a bead
		 * starts at {@code minBeadRadiusM} and swells at {@code growthMps}, so the time a bead spends
		 * PINNED is {@code (staticThresholdM - minBeadRadiusM) / growthMps}. The first attempt at this
		 * model set the threshold at 5.5 mm with a growth rate of 4 mm/s - a crossing time of half a
		 * second - so every bead was running almost immediately and the pinned regime simply did not
		 * exist. Keep the crossing time in the 5-20 s range: that is the difference between "a glass
		 * covered in specks" and "a glass where everything is falling at once".</p>
		 */
		private final double staticThresholdM;
		/** Side of the wetness grid (cells per side). Bigger = finer streaks, more work. */
		private final int wetnessCells;
		/** Wetness laid down per second by a running bead. */
		private final double wetnessDepositPerSecond;
		/** Wetness lost per second to evaporation. Below the deposit rate, or nothing accumulates. */
		private final double wetnessDryPerSecond;
		/** Bead separation below which two beads merge (metres); also the spatial hash's cell size. */
		private final double mergeDistanceM;

		private WindshieldConfig(JsonObject json) {
			raindrops = clampInt(getDouble(json, "raindrops", 90), 0, 400);
			fallMps = Math.max(0.01, getDouble(json, "fallMps", 0.55));
			maxStreakM = Math.max(0, getDouble(json, "maxStreakM", 0.10));
			wiper = getBoolean(json, "wiper", true);
			drawBlade = getBoolean(json, "drawBlade", true);
			armMConfigured = getDouble(json, "armM", 0);
			// The park direction is modelled, so 0 is the correct default: the blade lies along the
			// face's own "right" edge. These two only nudge it off that line.
			parkAngleDeg = getDouble(json, "parkAngleDeg", 0);
			sweepDeg = Math.max(5, getDouble(json, "sweepDeg", 88));
			sweepSign = getDouble(json, "sweepSign", 1) < 0 ? -1 : 1;
			periodS = Math.max(0.2, getDouble(json, "periodS", 1.6));
			bladeWidthM = Math.max(0.005, getDouble(json, "bladeWidthM", 0.035));
			colour = parseColor(getString(json, "colour", "#FF14181C"), 0xFF14181C);
			armColour = parseColor(getString(json, "armColour", "#FF3A4148"), 0xFF3A4148);
			snow = getBoolean(json, "snow", false);
			twoSided = getBoolean(json, "twoSided", true);
			dualWiper = getBoolean(json, "dualWiper", false);
			pivotU = getDouble(json, "pivotU", 0.5);
			pivotV = getDouble(json, "pivotV", 0.0);
			final boolean bladeGiven = json.has("bladeAU") && json.has("bladeBU");
			hasBlade = bladeGiven;
			bladeAU = getDouble(json, "bladeAU", 0);
			bladeAV = getDouble(json, "bladeAV", 0);
			bladeBU = getDouble(json, "bladeBU", 0);
			bladeBV = getDouble(json, "bladeBV", 0);
			// No second pivot means the two ends rotate about the SAME point, which is exactly a
			// single-axis wiper - so a missing pivot2 is not a special case anywhere in the maths.
			pivot2U = getDouble(json, "pivot2U", pivotU);
			pivot2V = getDouble(json, "pivot2V", pivotV);
			creepMps = Math.max(0, getDouble(json, "creepMps", 0.09));
			jitterMps = Math.max(0, getDouble(json, "jitterMps", 0.008));
			minBeadRadiusM = Math.max(0.0005, getDouble(json, "minBeadRadiusM", 0.0035));
			maxBeadRadiusM = Math.max(minBeadRadiusM * 1.2, getDouble(json, "maxBeadRadiusM", 0.014));
			// Crossing time = (staticThreshold x band - minBeadRadiusM) / growthMps. At 4.5 mm base and a
			// 1.1x..1.8x band that is 4.95..8.1 mm, so at 0.8 mm/s a bead stays PINNED for about 2.4 s to
			// 7.7 s. See the field docs on staticThresholdM: these two numbers are only meaningful together,
			// and the pair was calibrated with the offline harness (mmtr/tools/wiper-preview).
			growthMps = Math.max(0, getDouble(json, "growthMps", 0.0008));
			// Beads are a POPULATION, not a stream of particles: this rate only refills what a wipe
			// knocks out (and the odd bead lost off the edge), so keeping it well under
			// raindrops / (1 / 0.8 s) is what stops every bead from being perma-fresh and invisible.
			spawnPerSecond = Math.max(0, getDouble(json, "spawnPerSecond", 2));
			staticThresholdM = Math.max(0.0005, getDouble(json, "staticThresholdM", 0.0045));
			wetnessCells = clampInt(getDouble(json, "wetnessCells", 48), 4, 128);
			// Only a minority of beads run at any moment now (that is the point of the pinning model), so the
			// deposit has to be generous per bead or the trails never become visible: at these defaults a
			// trail cell reaches full wetness after about 0.17 s of a bead inside it, against drying of
			// 0.03/s - which is what lets a streak outlive the bead that drew it by tens of seconds.
			wetnessDepositPerSecond = Math.max(0, getDouble(json, "wetnessDepositPerSecond", 6));
			wetnessDryPerSecond = Math.max(0, getDouble(json, "wetnessDryPerSecond", 0.03));
			mergeDistanceM = Math.max(0.001, getDouble(json, "mergeDistanceM", 0.012));
		}

		/**
		 * The wiper's reach. Defaults to half the modelled quad's SHORTER side, which is exactly the
		 * largest arm that still fits inside the plane the author drew - so a correctly sized wiper face
		 * needs no {@code armM} at all.
		 */
		private double armM(Anchor anchor) {
			return armMConfigured > 0 ? armMConfigured : Math.max(0.05, Math.min(anchor.widthM, anchor.heightM) / 2);
		}

		/**
		 * Whether this wiper's blade sweeps a BAND rather than a sector - i.e. whether it has a second
		 * pivot, which is what a parallel linkage (a train's pantograph wiper) adds.
		 *
		 * <p>The split is by GEOMETRY, not by whether the model carries blade art, because the two tests
		 * are exact for different motions:</p>
		 * <ul>
		 *   <li>ONE pivot: the blade passes through the pivot, so the region it clears is exactly the
		 *       angular sector between park and the blade - the original test, kept unchanged.</li>
		 *   <li>TWO pivots: the blade never passes through a pivot, so no sector describes it. The quad
		 *       between two blade positions covers a pure translation exactly (a parallelogram), and for
		 *       a partially-rotating linkage it is exact to within the arc bulge over one repaint.</li>
		 * </ul>
		 */
		private boolean usesBandWipe() {
			return hasBlade && (Math.abs(pivot2U - pivotU) > 1.0E-9 || Math.abs(pivot2V - pivotV) > 1.0E-9);
		}

		/**
		 * The blade's two ends at a given stroke angle, in canvas METRES with y UP and the origin at the
		 * bottom-left - the same space the beads are simulated in, so the wiped region can never end up
		 * mirrored against the beads it is supposed to be clearing.
		 *
		 * <p>Both wiper families are these two expressions, and NOTHING else differs between them:</p>
		 * <pre>
		 *   A(theta) = P1 + R(theta) * (A0 - P1)
		 *   B(theta) = P2 + R(theta) * (B0 - P2)
		 * </pre>
		 * <p>With one pivot ({@code P1 = P2}) the blade rotates rigidly - a single-axis car wiper. With
		 * equal link vectors (the parallelogram a train uses) the difference {@code B - A} is constant, so
		 * the blade keeps its direction and only translates. Both fall out of the geometry; there is no
		 * branch on "which kind of wiper is this".</p>
		 *
		 * @return {@code {{ax, ay}, {bx, by}}}, or null when the model carries no blade geometry
		 */
		@Nullable
		private double[][] bladeSegmentM(double thetaDeg, Anchor anchor) {
			if (!hasBlade) {
				return null;
			}
			final double widthM = anchor.widthM;
			final double heightM = anchor.heightM;
			final double radians = Math.toRadians(thetaDeg);
			final double cos = Math.cos(radians);
			final double sin = Math.sin(radians);
			return new double[][]{
					rotateAbout(pivotU * widthM, pivotV * heightM, bladeAU * widthM, bladeAV * heightM, cos, sin),
					rotateAbout(pivot2U * widthM, pivot2V * heightM, bladeBU * widthM, bladeBV * heightM, cos, sin)
			};
		}

		private static double[] rotateAbout(double pivotX, double pivotY, double pointX, double pointY, double cos, double sin) {
			final double dx = pointX - pivotX;
			final double dy = pointY - pivotY;
			return new double[]{pivotX + dx * cos - dy * sin, pivotY + dx * sin + dy * cos};
		}

		private static WindshieldConfig get(String vehicleId, String anchorName) {
			final WindshieldConfig config = CACHE.computeIfAbsent(vehicleId, WindshieldConfig::read).get(anchorName);
			return config == null ? DEFAULT : config;
		}

		private static Object2ObjectOpenHashMap<String, WindshieldConfig> read(String vehicleId) {
			final Object2ObjectOpenHashMap<String, WindshieldConfig> result = new Object2ObjectOpenHashMap<>();
			final String[] content = {""};
			try {
				ResourceManagerHelper.readResource(new Identifier("mtr", "mmtr_anchors_" + vehicleId + ".json"), inputStream -> {
					try (final InputStream stream = inputStream) {
						content[0] = IOUtils.toString(stream, StandardCharsets.UTF_8);
					} catch (Exception e) {
						LOGGER.warn("Failed to read windshield config for {}", vehicleId, e);
					}
				});
			} catch (Exception e) {
				return result;
			}
			if (content[0].isEmpty()) {
				return result;
			}
			try {
				final JsonElement root = JsonParser.parseString(content[0]);
				if (!root.isJsonObject()) {
					return result;
				}
				final JsonElement block = root.getAsJsonObject().get("windshield");
				if (block == null || !block.isJsonObject()) {
					return result;
				}
				for (final String key : block.getAsJsonObject().keySet()) {
					final JsonElement entry = block.getAsJsonObject().get(key);
					result.put(key, new WindshieldConfig(entry != null && entry.isJsonObject() ? entry.getAsJsonObject() : new JsonObject()));
				}
				Init.LOGGER.info("[MMTR-WSHLD] {} config entries for {}", result.size(), vehicleId);
			} catch (Exception e) {
				LOGGER.error("Failed to parse windshield config for {}", vehicleId, e);
			}
			return result;
		}

		private static int clampInt(double value, int min, int max) {
			return (int) Math.max(min, Math.min(max, Math.round(value)));
		}

		private static String getString(JsonObject object, String key, String fallback) {
			final JsonElement element = object.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsString();
		}

		private static double getDouble(JsonObject object, String key, double fallback) {
			final JsonElement element = object.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
		}

		private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
			final JsonElement element = object.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
		}

		private static int parseColor(String value, int fallback) {
			try {
				return (int) Long.parseLong(value.replace("#", "").trim(), 16);
			} catch (Exception e) {
				return fallback;
			}
		}
	}
}