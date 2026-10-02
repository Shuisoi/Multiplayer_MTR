package org.mtr.mod.render.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mapping.mapper.TextHelper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mtr.mod.Init;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.glass.MmtrGlass;
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

	/**
	 * Default distance the precipitation layer is pushed off the modelled face, along the glass normal
	 * (positive = away from the car, i.e. towards the weather). Per-glass override: {@code waterOffsetM}.
	 *
	 * <p><b>Which side it goes on is a visibility decision, not a physical one, and it is decided by the
	 * DRAW ORDER.</b> The vehicle's own meshes - including the modelled glass pane - are submitted from
	 * {@code RenderVehicles} while this layer is only QUEUED, and the queue is consumed later inside
	 * {@code MainRenderer}. So the glass is rasterised first, writes depth, and whatever is behind it
	 * from the camera's side is then depth-rejected. With the layer outside the pane the driver sees
	 * nothing (the pane is between the camera and the water); with it inside, the driver sees it and the
	 * outside view additionally loses it to whichever surface is nearer. The BR101 pack sets it negative
	 * per pane; see notes/205.</p>
	 */
	private static final float WATER_OFFSET_M = 0.030F;
	/** The wiper sits above the water so layer order and depth agree. */
	private static final float WIPER_OFFSET_M = 0.045F;
	/** Solid-colour quads reuse MTR's own white texture rather than shipping one. */
	private static final Identifier WHITE_TEXTURE = new Identifier("minecraft", "textures/misc/white.png");
	/** How often {@code [MMTR-WSHLD] motion} reports the bead field, counted in advance() calls (frames). */
	private static final int MOTION_LOG_EVERY_N_FRAMES = 300;
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
	 * Shortest modelled blade that still counts as a blade: below this the band between two blade
	 * positions is degenerate and the sector fallback is the better answer (see
	 * {@code WiperConfig.usesBandWipe}).
	 */
	private static final double MIN_BLADE_LENGTH_M = 0.05;

	/**
	 * The speed at which the airflow term exactly cancels the fall of the water, in m/s. Above it beads
	 * creep upward, below it they run down; the coefficient is DERIVED from this so the crossover is a
	 * stated intent instead of a number nobody can re-derive.
	 *
	 * <p>The value is a compromise and both ends of it are load-bearing. Upstream's comment claimed about
	 * 22 m/s (80 km/h), but the offline harness's own assertions want "still falling at 15 km/h" and
	 * "nearly stopped at 120 km/h", and a 22 m/s crossover makes beads pin as soon as a train is rolling
	 * at all - which in game reads as rain that does not move. 30 km/h (8.33 m/s) keeps the drops visibly
	 * running through shunting speeds and pins them by line speed, which is the behaviour the assertions
	 * describe.</p>
	 */
	private static final double BALANCE_SPEED_MPS = 8.33;
	/** Gravity along the glass at the reference intensity, i.e. the force the airflow has to cancel. */
	private static final double REFERENCE_GRAVITY_MPS2 = 0.55 * (0.6 + 0.9 * 1.0);
	/**
	 * Airflow force per (m/s)^2 of speed, at the reference intensity. Derived so that
	 * {@code coefficient * BALANCE_SPEED_MPS^2 == REFERENCE_GRAVITY_MPS2}.
	 *
	 * <p>It is additionally scaled by the glass's own slope at run time (see {@code advanceDrops}):
	 * on a raked windscreen the world's down only projects into the glass at
	 * {@code sqrt(1 - up.y()^2)} of its magnitude, so without that factor the crossover slides with the
	 * windscreen angle - measured on BR101 (projection 0.646) it fell from 18.9 to 15.2 km/h.</p>
	 */
	private static final double AIRFLOW_COEFFICIENT = REFERENCE_GRAVITY_MPS2 / (BALANCE_SPEED_MPS * BALANCE_SPEED_MPS);
	/**
	 * How far clear of the blade the put-back line is laid, in metres, ON TOP of the capture distance that
	 * took the water off the glass ({@code bladeWidthM / 2 + bead radius}).
	 *
	 * <p>The offset is what keeps the two rules from fighting: the put-back line is on the glass where the
	 * blade is, so without a margin the very next repaint would cut it again and the water would flicker
	 * out of existence instead of running down the glass. It also keeps the line off the wiper, which is
	 * the whole point of laying the water down rather than carrying it.</p>
	 */
	private static final double RIDGE_CLEARANCE_M = 0.010;
	/**
	 * How far the put-back water is thrown OUTWARD from the blade on top of its clearance, in metres, and the
	 * speed it keeps going at afterwards - the water's own MOMENTUM at the moment the blade lets it go.
	 *
	 * <p>Reported as "it is still swallowing it", and the clearance alone is not enough to stop that: a bead
	 * laid down at exactly {@code capture + RIDGE_CLEARANCE_M} has no room for error, and the field around it
	 * moves (beads drift, merge and grow, and the blade itself keeps turning). A real blade does not place the
	 * water it has scraped - it <b>throws</b> it: whatever the rubber was pushing keeps travelling the way it
	 * was going and only friction on the glass stops it. So the release gives each bead an outward step along
	 * the direction the blade was travelling plus a decaying speed in that direction (see
	 * {@link Drop#flingX}), which puts it 40-90 mm clear of the line instead of 10 and lets it keep moving
	 * away while the blade is still standing there.</p>
	 */
	private static final double RELEASE_FLING_M = 0.025;
	/**
	 * The fallback speed for the release, in metres per second, used only by a wiper that has never moved (so
	 * there is no measured blade speed yet). The real release speed is the BLADE'S OWN - {@code omega * r} for
	 * a rotating blade, its translation speed for a parallelogram - see {@code releaseSpeedFor}: the water
	 * leaves the rubber at the speed the rubber was moving, which is what makes the release a whip.
	 */
	private static final double RELEASE_FLING_SPEED = 0.9;
	/**
	 * How far the release's flick CARRIES a bead, in metres - the same distance for every bead, whatever speed
	 * it left the blade at.
	 *
	 * <p>This is the quantity that has to be constant, not the speed and not the decay time. With the speed
	 * taken from the blade (0.9 m/s off the inner end of SAF420's blade, 5.4 m/s off the outer end in FAST)
	 * and a fixed decay TIME, the distance travelled varies by the same 6x - so some of the water stopped just
	 * outside the blade and some of it shot across the glass and off the far edge. Reported as "sometimes it
	 * stays on the glass, sometimes it flies straight off it". Fitting the decay to a fixed DISTANCE instead
	 * makes every bead pop out the same visible amount, and it is also the physical statement: a drop crossing
	 * the film on the glass loses its momentum over a distance set by the film, not by how fast it arrived.</p>
	 *
	 * <p>Each bead gets 0.6x-1.4x of it ({@code RELEASE_SCATTER}), and it is capped by the room the bead
	 * actually has: a bead released beside a blade near the frame edge is carried to a fixed margin short of
	 * that edge rather than into it, because a bead whose centre reaches the edge is culled by
	 * {@code drawBeads} and leaving the frame is not a flick, it is a deletion.</p>
	 */
	private static final double RELEASE_FLICK_M = 0.040;
	/** How far short of the frame edge the flick is stopped, in metres, on top of the bead's own radius. */
	private static final double RELEASE_EDGE_MARGIN_M = 0.008;
	/**
	 * The fallback decay time for a bead whose flick was fitted to a distance, in seconds - see
	 * {@code Drop.flingDecayS}. Only used when a bead has no flick of its own.
	 */
	private static final double RELEASE_FLING_DECAY_S = 0.06;
	/**
	 * How much of the release's distance and speed is left to chance, as a fraction of the values above: a
	 * bead is thrown between {@code 0.3x} and {@code 1.7x} of {@code RELEASE_FLING_M} and of
	 * {@code RELEASE_FLING_SPEED}, and lands anywhere inside its own share of the blade rather than at an
	 * even spacing.
	 *
	 * <p>Reported as "the released drops form a straight line, which is unnatural", and it was: an evenly
	 * spaced row thrown at one speed moves as a rigid row, which is the one thing a splash of water never
	 * does. Everything here is drawn from the Random a bead ALREADY owns ({@code Drop.random}, the field's
	 * own jitter generator) - four {@code nextDouble()} calls per RELEASED bead, twice per stroke, nothing
	 * allocated and no new state, so the cost is nil next to the per-frame work on the same field.</p>
	 */
	private static final double RELEASE_SCATTER = 1.4;
	/**
	 * Sideways spread of the release, in metres per second along the blade: each bead gets a random push of
	 * up to this either way, so the row fans out instead of travelling as one.
	 */
	private static final double RELEASE_SIDE_MPS = 0.12;
	/** Ceiling on the acceleration used to throw beads sideways, so a physics glitch cannot smear them. */
	private static final double MAX_LATERAL_ACCELERATION = 1.2;
	/** Ceiling on the lateral bead speed (m/s), for the same reason. */
	private static final double MAX_LATERAL_VELOCITY = 0.45;
	/** How fast a bead's own painted tail dries. Tails are off for now, so this only decays a zero. */
	private static final double TRAIL_DRY_MPS = 0.02;
	/**
	 * How far a running bead travels before it sheds a smaller bead (metres). Spacing, not rate, so a
	 * slow rivulet and a fast one leave the same speckled wake density.
	 */
	private static final double PINCH_OFF_DISTANCE_M = 0.035;
	/**
	 * How much of {@code runoffMps} a patch runs at <b>the instant it lets go</b>, i.e. at exactly
	 * {@code densityThresholdPerM2}. Crowding above the threshold adds the rest of the way, saturating
	 * once a patch holds {@code 1 + (1 - RUNOFF_MARGINAL_FACTOR)} = 1.6x the threshold density.
	 *
	 * <p>The two halves of the rule do different jobs, and getting them the wrong way round is what the
	 * first version of this model did:</p>
	 *
	 * <ul>
	 *   <li><b>The threshold decides WHETHER.</b> Surface tension is a threshold, not a slope: below
	 *       {@code densityThresholdPerM2} the water is held and does not move AT ALL. (This is a hard
	 *       latch, and it has to be - the very first attempt multiplied the speed by the excess over the
	 *       threshold without checking the sign, which made every bead on the glass run, including a lone
	 *       one. The offline harness caught it as "a single bead on a dry screen drifts 0.13 m/s".)</li>
	 *   <li><b>Crowding decides HOW FAST</b>, from {@code RUNOFF_MARGINAL_FACTOR} to 1.0. Proportional-
	 *       to-the-excess instead (the second attempt) measured at 0.026 m/s on BR101, because the field
	 *       is <b>self-regulating</b>: a running patch drains itself in under a second, so the population
	 *       sits just under the threshold almost all the time and the excess is nearly always tiny. The
	 *       fraction of the glass that is running is then the interesting output (measured: 4% in drizzle
	 *       against 15-57% in a downpour), not the speed of a marginal patch.</li>
	 * </ul>
	 */
	private static final double RUNOFF_MARGINAL_FACTOR = 0.4;
	/**
	 * The speed a bead slides downhill at when NOTHING around it is crowded, as a fraction of
	 * {@code runoffMps}.
	 *
	 * <p>Crowding decides how FAST water runs, not whether it runs at all: below the density threshold a
	 * bead used to be pinned outright ("NO motion at all"), which on a still glass in moderate rain is
	 * every bead on the screen - the whole field hung there and nothing ever fell or left. Reported as
	 * "the drops should fall and disappear naturally". Water on glass does creep; what the threshold
	 * decides is when it lets go and RUNS.</p>
	 */
	private static final double FALL_CREEP_FRACTION = 0.12;

	/** One state per windshield, keyed by vehicle id + car + anchor name. */
	private static final Object2ObjectOpenHashMap<String, State> STATES = new Object2ObjectOpenHashMap<>();

	/** Plane frames already logged, so the geometry is reported once per windshield instead of per frame. */
	private static final Object2ObjectOpenHashMap<String, Boolean> PLANE_LOGGED = new Object2ObjectOpenHashMap<>();

	/** One-shot diagnostic for "the layer is in the wrong place" reports. The panel had the same class of
	 * bug and the fix was impossible to guess from the screenshot; printing the actual solved frame (and
	 * three corners of the quad) makes it arithmetic instead. {@code normal} is the direction the layer is
	 * drawn along, {@code right}/{@code up} span it - if the quad ends up perpendicular to the glass, one
	 * of those three is not what the model authored.
	 */
	/**
	 * @param waterOffsetM signed, in the SAME sense as {@link #quadCorners} (positive = away from the
	 *                     car). The log reports the distance from the glass plane, so a negative offset
	 *                     is printed as such rather than being folded to its magnitude - "the layer is on
	 *                     the driver's side" has to be readable from the log, not inferred.
	 */
	private static void logPlaneOnce(String key, Plane plane, double widthM, double heightM, double waterOffsetM) {
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
		final Vector[] corners = quadCorners(plane, widthM, heightM, waterOffsetM);
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
			worstOutOfPlane = Math.max(worstOutOfPlane, Math.abs(alongNormal - waterOffsetM));
			final double alongRight = offsetX * plane.right.x() + offsetY * plane.right.y() + offsetZ * plane.right.z();
			final double alongUp = offsetX * plane.up.x() + offsetY * plane.up.y() + offsetZ * plane.up.z();
			minAlongRight = Math.min(minAlongRight, alongRight);
			maxAlongRight = Math.max(maxAlongRight, alongRight);
			minAlongUp = Math.min(minAlongUp, alongUp);
			maxAlongUp = Math.max(maxAlongUp, alongUp);
			sb.append(String.format(" (%+.3f,%+.3f,%+.3f)", alongRight, alongUp, alongNormal));
		}
		Init.LOGGER.info("[MMTR-WSHLD] {} quad corners (right,up,out-of-plane), all out-of-plane must be {}:", key, waterOffsetM);
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
	/**
	 * MMTR：真半透明玻璃（一块面板一个四边形）。
	 *
	 * <p><b>为什么玻璃必须由 mod 画</b>：MTR 的 OBJ 优化渲染路径实际只做 <b>cutout</b> —— 实测
	 * （notes/345 §7.14）贴图 α=40/255 会画成<b>实心</b>、α=0 会被 discard。模型自己的材质在这条路径上
	 * 拿不到 alpha 混合，所以玻璃改走一条**真正混合**的层：{@code QueuedRenderLayer.LIGHT_TRANSLUCENT}
	 * 底层是 {@code RenderLayer.getBeaconBeam(texture, true)}（混合开、深度测试开、<b>不写深度</b>、双面）。</p>
	 *
	 * <p>位置/尺寸/朝向仍来自 <b>Blender 里建的锚点</b>（{@code mmtr_windshield_*}），所以分工是
	 * "建模在 Blender、混合在 mod"。颜色/不透明度/偏移见 {@link MmtrGlass}（run/mmtr-lightfield.properties，
	 * 2 秒热重载）。图层顺序：本层排在 {@code EXTERIOR_TRANSLUCENT_DOUBLE}（水膜/雨滴）之前 ⇒ 玻璃先画、
	 * 水膜后叠 ✓。</p>
	 */
	private static void drawGlassTint(StoredMatrixTransformations carTransform, Anchor anchor, double waterSide) {
		if (!MmtrGlass.isEnabled()) {
			return;
		}
		final int colour = MmtrGlass.argb();
		if ((colour >>> 24) == 0) {
			return;
		}
		final Plane plane = Plane.of(anchor, waterSide);
		if (plane == null) {
			return;
		}
		final double liftM = MmtrGlass.offsetM();
		final double halfWidthM = anchor.widthM / 2;
		final double halfHeightM = anchor.heightM / 2;
		final Vector bl = plane.pointAt(-halfWidthM, -halfHeightM, liftM);
		final Vector br = plane.pointAt(halfWidthM, -halfHeightM, liftM);
		final Vector tr = plane.pointAt(halfWidthM, halfHeightM, liftM);
		final Vector tl = plane.pointAt(-halfWidthM, halfHeightM, liftM);
		// 图层：EXTERIOR_TRANSLUCENT_DOUBLE = MoreRenderLayers.getExteriorTranslucentDoubleSided
		// = RenderLayer.getEntityTranslucent（半透明 + **无背面剔除**）⇒ 同一个四边形两面都看得见 ✓
		// （第一版用了 LIGHT_TRANSLUCENT = getBeaconBeam(tex, true)，它有背面剔除 ⇒ 只有车内能看到
		//  玻璃颜色、车外被剔除。实测踩过。）
		// 与水膜同层，但本方法在 state.draw(...) 之前调用 ⇒ 同一层内先入队先画 ⇒ 玻璃在下、水膜在上 ✓
		MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.EXTERIOR_TRANSLUCENT_DOUBLE, (graphicsHolder, offset) -> {
			carTransform.transform(graphicsHolder, offset);
			IDrawing.drawTexture(
					graphicsHolder,
					(float) bl.x(), (float) bl.y(), (float) bl.z(),
					(float) br.x(), (float) br.y(), (float) br.z(),
					(float) tr.x(), (float) tr.y(), (float) tr.z(),
					(float) tl.x(), (float) tl.y(), (float) tl.z(),
					new Vector3d(0, 0, 0),
					0.0F, 1.0F, 0.0F, 1.0F,
					Direction.UP, colour, GraphicsHolder.getDefaultLight()
			);
			graphicsHolder.pop();
		});
	}

	public static void render(String vehicleId, int carNumber, int modelCar, StoredMatrixTransformations carTransform, double vehicleSpeedMetersPerMs) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		// getWorldMapped() is already the client flavour; it is a sibling of MTR's World, not a subtype.
		final org.mtr.mapping.holder.ClientWorld world = minecraftClient.getWorldMapped();
		if (world == null) {
			return;
		}

		// The client's synced copy of the weather. The gradient is the same 0..1 intensity vanilla uses
		// for its own rain particles; here it is how WET the glass gets (how many beads, how fast they
		// grow) and no longer the wiper speed - the wiper is on the driver's key.
		final net.minecraft.client.world.ClientWorld clientWorld = ((net.minecraft.client.world.ClientWorld) world.data);
		final float rainGradient = clientWorld != null && clientWorld.isRaining() ? clientWorld.getRainGradient(1) : 0;
		final boolean precipitating = rainGradient > 0.02F;
		final long now = System.currentTimeMillis();

		// The wiper is drawn ONLY on the cab this client is actually sitting in. Every other cab, and every
		// other train, keeps its blades parked - which is also what stops a passenger's key from wiping
		// anything. The decision is per PANE and not per car, because a model may put cab 1 and cab 2 on the
		// SAME car: the real saf101 model does exactly that (windshield_1 at z = +7.79 m, windshield_2 at
		// z = -7.78 m), so "which car am I in" cannot tell the two ends of one car apart and both blades
		// would sweep together.
		// 查锚点用 **模型内车节序号**（modelCar），不用编组序号：一个模型挂 N 节时，锚点 JSON 只有一份
		// （notes/365）。carNumber 仍然用于身份（缓存键、日志、"司机在哪节车"），两者不能混。
		final ObjectArrayList<Anchor> panes = MmtrVehicleAnchors.findWindshields(MmtrVehicleAnchors.get(vehicleId), modelCar);
		logGate(vehicleId, carNumber, panes);
		// MMTR：模型自带的玻璃（由 Blender 的 OBJ + 贴图决定）—— 每节车画一次，与天气/雨刷无关。
		// 必须在这里（天气状态机之前）：下面那段在"干燥且雨刷归位"时会 continue，挂进去天一晴玻璃就没了。
		final boolean hasModelGlass = MmtrGlass.hasModelGlass(vehicleId);
		MmtrGlass.draw(carTransform, vehicleId);
		for (final Anchor anchor : panes) {
			if (anchor.widthM <= 0 || anchor.heightM <= 0) {
				continue;
			}
			if (!hasModelGlass) {
				// 兜底：模型没带 glass 段时，才在挡风锚点上画一块纯色玻璃（颜色来自 run 属性）
				drawGlassTint(carTransform, anchor, waterSideOf(vehicleId, carNumber, anchor));
			}
			final WiperMode mode = driverOnBoard(vehicleId, carNumber, anchor) ? wiperMode : WiperMode.OFF;

			final String key = vehicleId + ":" + carNumber + ":" + anchor.name;
			State state = STATES.get(key);
			if (state == null) {
				// The rain-vs-snow choice is a WORLD property, so it is resolved once per windshield
				// rather than per car: every glass on the train sees the same sky.
				state = new State(anchor, WindshieldConfig.get(vehicleId, anchor.name), isSnow(clientWorld), waterSideOf(vehicleId, carNumber, anchor));
				STATES.put(key, state);
			}

			// The state is forgotten only when the glass is BOTH dry and parked, so a stopped wiper
			// cannot lose the drops it was in the middle of clearing.
			final boolean wiping = mode != WiperMode.OFF && state.config.hasWiper();
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

	/**
	 * W4: pushes the rigid transform of a MODELLED wiper part, or returns false when this part is not one.
	 * The caller must {@code pop()} exactly when this returns true.
	 *
	 * <p>The parts are named independently (docs §1.4④) and each moves differently, which is the whole
	 * reason the mechanism had to be fitted rather than assumed:</p>
	 * <ul>
	 *   <li>{@code wiperarm_<cab>_<pane>} - the crank, so it rotates about the SPINDLE.</li>
	 *   <li>{@code wiperrod_<cab>_<pane>} - the follower, rotating about the SECOND pivot.</li>
	 *   <li>{@code wiper_<cab>_<pane>} - the blade, carried by its two pins: rotate about the parked pin by
	 *       the angle the pin pair turned through, then translate that pin onto its current position. On a
	 *       single-axis wiper that degenerates to a rotation about the spindle and on a parallel linkage to
	 *       a pure translation, which is exactly right for both.</li>
	 * </ul>
	 *
	 * <p>Applied as matrix-stack operations around the part's draw - the same shape as MTR's own 180 degree
	 * flip - so the optimized model's baked geometry is untouched and nothing has to be re-uploaded.</p>
	 */
	/**
	 * Matches the independently named mechanism parts:
	 * {@code wiper_ / wiperarm_ / wiperrod_ <cab>_<pane>[_<wiper>]}.
	 *
	 * <p>The optional third index is WHICH wiper of that glass the part belongs to. A glass with one wiper
	 * names its parts without it ({@code wiper_1_1}) and a glass with two names them {@code wiper_1_1} and
	 * {@code wiper_1_1_2}, so this regex is the client's half of the naming contract in docs §1.4②.</p>
	 */
	private static final Pattern WIPER_PART = Pattern.compile("^wiper(arm|rod)?_(\\d+)_(\\d+)(?:_(\\d+))?$", Pattern.CASE_INSENSITIVE);

	public static boolean pushPartTransform(GraphicsHolder graphicsHolder, Iterable<String> partNames, String vehicleId, int carNumber, int modelCar) {
		for (final String name : partNames) {
			final Matcher matcher = WIPER_PART.matcher(name);
			if (!matcher.matches()) {
				continue;
			}
			final int cab = Integer.parseInt(matcher.group(2));
			final int pane = Integer.parseInt(matcher.group(3));
			final int wiperIndex = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
			// 同上：锚点按模型内序号查（carNumber 只用于缓存键与日志）
			final Anchor anchor = MmtrVehicleAnchors.findWindshield(MmtrVehicleAnchors.get(vehicleId), modelCar, cab, pane);
			if (anchor == null) {
				return false;
			}
			final WindshieldConfig config = WindshieldConfig.get(vehicleId, anchor.name);
			// The part says WHICH wiper it is, so it is driven by that wiper's own pivot, park direction and
			// angle. A part with no matching config (a mistyped index, or a blade whose fan was dropped)
			// stays at its modelled position - say so once, because the visible symptom is only "this blade
			// does not move", which is indistinguishable from several other causes.
			final WiperConfig wiper = config.wiperAt(wiperIndex);
			if (wiper == null || !wiper.hasBlade) {
				if (GATE_LOG.put("nopart:" + vehicleId + ":" + carNumber + ":" + name, 1) == null) {
					LOGGER.info("[MMTR-WSHLD] modelled wiper part {} {} has no fitted wiper {}, so it cannot be moved "
									+ "(the glass has {} configured wiper(s))", vehicleId, name, wiperIndex, config.wipers.length);
				}
				return false;
			}
			final Plane plane = Plane.of(anchor, waterSideOf(vehicleId, carNumber, anchor));
			if (plane == null) {
				return false;
			}
			final State state = STATES.get(vehicleId + ":" + carNumber + ":" + anchor.name);
			// One line per part, ever: a solid wiper blade is moved by rotating the MESH part here, so if
			// this path silently does nothing the blade just sits at its modelled position with no other
			// symptom - "the wiper does not move" and "the mode never changed" look identical in game. The
			// only silent no-op left in here is `state == null` (the angle then falls back to the park
			// angle, theta becomes 0 and the part is left alone), so say which one it is.
			final WiperState wiperState = state == null ? null : state.wiperState(wiper);
			final double stateAngleDeg = wiperState == null ? wiper.parkAngleDeg : wiperState.angleDeg;
			if (GATE_LOG.put("part:" + vehicleId + ":" + carNumber + ":" + anchor.name + ":" + wiperIndex, 1) == null) {
				LOGGER.info("[MMTR-WSHLD] wiper part {} {} part={} wiper={} hasBlade={} state={} angle={} park={} sign={}",
						vehicleId, anchor.name, matcher.group(0), wiperIndex, wiper.hasBlade, wiperState != null,
						stateAngleDeg, wiper.parkAngleDeg, wiper.sweepSign);
			}
			final double angleDeg = stateAngleDeg;
			final double theta = (angleDeg - wiper.parkAngleDeg) * wiper.sweepSign;
			final double widthM = anchor.widthM;
			final double heightM = anchor.heightM;
			// The config stores every pin and pivot as a FRACTION from the glass's left/bottom, while
			// Plane.pointAt takes METRES FROM THE GLASS'S CENTRE. Shifting the whole set once, here, is what
			// makes the two agree - and it is safe because every expression below (the crank rotation, the
			// loop closure, the pin-pair turn) is a function of the DIFFERENCES between these points, which
			// a common translation cannot change. The matrix stack is the part that is NOT shift invariant:
			// feeding pointAt a raw fraction*size puts the spindle half a screen off the glass, so the arm
			// swings about a point that is not on it and the rods head for the sky.
			final double halfWidthM = widthM / 2;
			final double halfHeightM = heightM / 2;
			final double pivotX = wiper.pivotU * widthM - halfWidthM;
			final double pivotY = wiper.pivotV * heightM - halfHeightM;
			final double[] m0 = {wiper.pinAU * widthM - halfWidthM, wiper.pinAV * heightM - halfHeightM};
			final double[] br0 = {wiper.pinBU * widthM - halfWidthM, wiper.pinBV * heightM - halfHeightM};
			final double[] p2 = {wiper.pivot2U * widthM - halfWidthM, wiper.pivot2V * heightM - halfHeightM};
			final boolean coaxial = Math.abs(p2[0] - pivotX) < 1.0E-9 && Math.abs(p2[1] - pivotY) < 1.0E-9;
			// The linkage is solved ONCE, because all three parts are driven by the same two points: the
			// arm's pin m (the crank) and the rod's pin br (the follower, from the loop closure). Only one
			// of them is needed for the arm, but the rod's own angle and the BLADE's turn are different
			// quantities and both come out of here - which is why they cannot be a single `theta`.
			final double thetaRadians = Math.toRadians(theta);
			final double cos = Math.cos(thetaRadians), sin = Math.sin(thetaRadians);
			final double[] m = {pivotX + (m0[0] - pivotX) * cos - (m0[1] - pivotY) * sin,
					pivotY + (m0[0] - pivotX) * sin + (m0[1] - pivotY) * cos};
			// The arm turns by the crank angle; with one pivot the rod does too. With two, the rod is rigid
			// with one end on p2 and the other on the blade, so its angle is whatever the closure puts br at:
			// passing `theta` (as this did) is exact only for an ideal parallelogram, and on a real linkage
			// it leaves the rod's pin off the blade's pin by |rod| times the link-vector residual.
			double rodTurnDeg = theta;
			// The blade turns by the angle the PIN PAIR turned through - zero on a parallelogram, which is
			// what "the blade translates instead of turning" means.
			double bladeTurnDeg = theta;
			if (!coaxial) {
				final double spanM = Math.hypot(br0[0] - m0[0], br0[1] - m0[1]);
				final double followerM = Math.hypot(br0[0] - p2[0], br0[1] - p2[1]);
				// The mode is read off the PARK configuration - its park crank pin is m0, not br0.
				final int mode = wiper.assemblyMode(m0, p2, br0, spanM, followerM);
				final double[] br = wiper.followerEndFor(m, p2, spanM, followerM, mode);
				if (br != null) {
					bladeTurnDeg = Math.toDegrees(Math.atan2(br[1] - m[1], br[0] - m[0]) - Math.atan2(br0[1] - m0[1], br0[0] - m0[0]));
					rodTurnDeg = Math.toDegrees(Math.atan2(br[1] - p2[1], br[0] - p2[0]) - Math.atan2(br0[1] - p2[1], br0[0] - p2[0]));
				}
			}
			final boolean isArm = "arm".equals(matcher.group(1));
			final boolean isRod = "rod".equals(matcher.group(1));
			if (isArm || isRod) {
				// The arm turns about the SPINDLE; the rod about its own second pivot. Both are exact: each
				// part is rigid with one end pinned to that pivot and the other end on m / br.
				if (isArm) {
					applyRotation(graphicsHolder, plane, pivotX, pivotY, theta);
				} else {
					applyRotation(graphicsHolder, plane, p2[0], p2[1], rodTurnDeg);
				}
			} else {
				// The blade: rotate about the PARKED arm pin by the angle the pin pair turned, then move
				// that pin onto where it is now. (m0 -> m, with the rotation the pair went through.)
				graphicsHolder.push();
				final Vector pin = plane.pointAt(m[0], m[1]);
				final Vector parkedPin = plane.pointAt(m0[0], m0[1]);
				graphicsHolder.translate(pin.x(), pin.y(), pin.z());
				applyPlaneRotation(graphicsHolder, plane, bladeTurnDeg);
				graphicsHolder.translate(-parkedPin.x(), -parkedPin.y(), -parkedPin.z());
				return true;
			}
			return true;
		}
		return false;
	}

	/**
	 * Rotates about {@code (pivotX, pivotY)} within the glass plane, by {@code angleDeg}.
	 *
	 * <p>{@code pivotX}/{@code pivotY} are PLANE coordinates in METRES FROM THE GLASS'S CENTRE (what
	 * {@link Plane#pointAt} takes); the config's own fractions-from-the-left/bottom are converted by the
	 * caller. The matrix stack lives in MODEL space, so the pivot has to be lifted onto the glass first:
	 * translating by the raw (u, v, 0) swings the part about an axis through the model origin - which is
	 * how the rods end up in the sky while the blade merely looks bent.</p>
	 */
	private static void applyRotation(GraphicsHolder graphicsHolder, Plane plane, double pivotX, double pivotY, double angleDeg) {
		final Vector pivot = plane.pointAt(pivotX, pivotY);
		graphicsHolder.push();
		graphicsHolder.translate(pivot.x(), pivot.y(), pivot.z());
		applyPlaneRotation(graphicsHolder, plane, angleDeg);
		graphicsHolder.translate(-pivot.x(), -pivot.y(), -pivot.z());
	}

	/**
	 * A rotation about the glass's NORMAL, by mapping the plane's own frame onto the world axes, turning
	 * about local Z (which that mapping sends to the normal), and mapping back.
	 *
	 * <p>MTR's GraphicsHolder only rotates about X/Y/Z, so an arbitrary axis has to be reached this way.
	 * {@link Plane} extracts the three angles from the glass's own basis, which is what makes the
	 * conjugation below turn about the NORMAL by exactly {@code angleDeg} and nothing else. (A panel is
	 * placed differently - {@link MmtrPanelQuad} maps its corners - because a quad does not need the
	 * axis.)</p>
	 */
	private static void applyPlaneRotation(GraphicsHolder graphicsHolder, Plane plane, double angleDeg) {
		graphicsHolder.rotateYDegrees((float) plane.yaw);
		graphicsHolder.rotateXDegrees((float) plane.pitch);
		graphicsHolder.rotateZDegrees((float) plane.roll);
		graphicsHolder.rotateZDegrees((float) angleDeg);
		graphicsHolder.rotateZDegrees((float) -plane.roll);
		graphicsHolder.rotateXDegrees((float) -plane.pitch);
		graphicsHolder.rotateYDegrees((float) -plane.yaw);
	}

	private static boolean configAllowsWiper(State state) {
		return state.config.hasWiper();
	}

	/**
	 * Whether this client is driving a train whose model uses {@code resourceId}.
	 *
	 * <p>The comparison has to go through the model ID because the two sides do not speak the same
	 * language: the cab key is held against a numeric vehicle id, while the render path carries the
	 * resource id the model was loaded from. A consist can mix models, so this asks the client's own
	 * vehicle list for the driven vehicle and compares its car models.</p>
	 *
	 * <p>This was hard-coded false from notes/185 (the clean-slate delete) until now, and that single
	 * false is why the wiper could never leave its parked position in game: the drawn blade is gated on a
	 * question nothing answered. It is answered here from the ride session - the client's own record of
	 * which car of which vehicle the player is inside.</p>
	 *
	 * <p>WHICH CAB is decided by the player's position ALONG the car, compared against the panes' own
	 * positions, and NOT by the car number. The earlier version assumed a cab is always a car of its own -
	 * and the real model falsifies that: saf101 carries cab 1 (windshield_1, z = +7.79 m) and cab 2
	 * (windshield_2, z = -7.78 m) on car 0. Matching on the car alone therefore closed BOTH blades at once,
	 * which is the exact failure this has to avoid. Both quantities are in MTR's riding space (anchors are
	 * converted by {@code MmtrVehicleAnchors.toRidingSpace}, the ride offset is already in it), so they are
	 * directly comparable, and the nearest pane wins - no front/back convention is assumed, and a model with
	 * more than two cabs still resolves.</p>
	 */
	private static boolean driverOnBoard(String resourceId, int carNumber, Anchor anchor) {
		final VehicleExtension ridingVehicle = ridingVehicle();
		if (ridingVehicle == null || resourceId == null) {
			return false;
		}
		final IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> ridingCar = VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(ridingVehicle.getId());
		if (ridingCar == null) {
			return false;
		}
		final int ridingCarNumber = ridingCar.leftInt();
		// The resource id is per CAR (ModelPropertiesPart does the same lookup), so a consist whose cars
		// carry different models still matches only the car the player is actually inside.
		if (ridingCarNumber != carNumber || !resourceId.equals(resourceIdFor(ridingVehicle, ridingCarNumber))) {
			return false;
		}
		// The offset is the latest movePlayer result and is null until that has run at least twice, so a
		// ride that was just established has no position yet. Answering "nobody is on board" is both true
		// and harmless for the wiper; dereferencing it here threw an NPE out of render(), which aborted
		// RenderVehicles' car loop BEFORE its movePlayer call - so the position was never computed and the
		// ride timed out. That is what made cab entry impossible (found in-game 2026-09-19).
		final Vector3d playerOffset = ridingCar.right().left();
		if (playerOffset == null) {
			return false;
		}
		final double playerZ = playerOffset.getZMapped();
		// 规则只有一份：MmtrVehicleAnchors.nearestCab（最近的风挡锚点 = 我坐的是哪个驾驶室）。
		return MmtrVehicleAnchors.nearestCab(MmtrVehicleAnchors.get(resourceId), carNumber, playerZ) == anchor.cab;
	}

	/** [MMTR-DBG] Last logged gating situation per car, so the log gets one line per CHANGE, not per frame. */
	private static final java.util.Map<String, Integer> GATE_LOG = new java.util.HashMap<>();

	/**
	 * [MMTR-DBG] One line per CHANGE of the wiper's gating situation: which vehicle and car this client
	 * believes it is riding, how many panes that car even has, and which of them it resolves to the
	 * cockpit.
	 *
	 * <p>This exists because the gate cannot be exercised offline at all (notes/189). Without it, "the
	 * wiper does not move" in game cannot be told apart from "driverOnBoard answered no", from "it picked
	 * the other cab", or from "this car has no pane at all" - three failures that need completely different
	 * fixes and look identical on screen.</p>
	 */
	private static void logGate(String vehicleId, int carNumber, ObjectArrayList<Anchor> panes) {
		final long ridingVehicleId = VehicleRidingMovement.getRidingVehicleId();
		if (ridingVehicleId == 0) {
			return;
		}
		final IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> ridingCar = VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(ridingVehicleId);
		if (ridingCar == null) {
			return;
		}
		// The offset vector is the LATEST movePlayer result, so it is null in the window between entering a
		// vehicle and that method's first run. Reading it unconditionally here threw a NullPointerException
		// OUT of MmtrWindshield.render, which aborted RenderVehicles.iterateWithIndex BEFORE its movePlayer
		// call - so the offset was never computed, the ride timed out, and cab entry was impossible. A
		// diagnostic must never be able to do that: report what is known and carry on.
		final Vector3d offset = ridingCar.right().left();
		final int offsetSignature = offset == null ? -1 : (int) Math.round(offset.getZMapped() * 10) * 7;
		// Cheap signature of everything that can change the answer, so the strings below are only built
		// when the situation actually differs from the last logged one.
		final int signature = panes.size() * 1000003 + carNumber * 101 + wiperMode.ordinal() * 17
				+ offsetSignature + (int) (ridingVehicleId % 97);
		final String key = vehicleId + ":" + carNumber;
		final Integer previous = GATE_LOG.put(key, signature);
		if (previous != null && previous.intValue() == signature) {
			return;
		}
		final StringBuilder builder = new StringBuilder();
		builder.append("riding=").append(ridingVehicleId).append(" ridingCar=").append(ridingCar.leftInt())
				.append(" panes=").append(panes.size()).append(" stalk=").append(wiperMode);
		for (final Anchor pane : panes) {
			builder.append(" | ").append(pane.name).append(" cab=").append(pane.cab)
					.append(" paneZ=").append(Math.round(pane.position.z() * 100) / 100.0)
					.append(" driven=").append(driverOnBoard(vehicleId, carNumber, pane));
		}
		LOGGER.info("[MMTR-DBG] wiper gate {} -> {}", key, builder);
	}

	/**
	 * Whether this client holds ANY cab, for the wiper stalk. False while riding something whose cars
	 * carry no windshield (a lift, or a wagon set with no cab), so pressing the key there still says
	 * "take a cab first" instead of switching a wiper that does not exist.
	 */
	private static boolean driverOnBoardAnyCab() {
		final VehicleExtension ridingVehicle = ridingVehicle();
		if (ridingVehicle == null) {
			return false;
		}
		final IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> ridingCar = VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(ridingVehicle.getId());
		if (ridingCar == null) {
			return false;
		}
		final int ridingCarNumber = ridingCar.leftInt();
		final String resourceId = resourceIdFor(ridingVehicle, ridingCarNumber);
		return resourceId != null && !MmtrVehicleAnchors.findWindshields(MmtrVehicleAnchors.get(resourceId), ridingCarNumber).isEmpty();
	}

	/** The model's resource id for one car of a consist - the same lookup ModelPropertiesPart uses. */
	@Nullable
	private static String resourceIdFor(VehicleExtension vehicle, int carNumber) {
		final ObjectArrayList<ObjectObjectImmutablePair<org.mtr.core.data.VehicleCar, ObjectArrayList<org.mtr.core.data.Vehicle.BogiePosition>>> cars = vehicle.getVehicleCarsAndPositions();
		return carNumber < 0 || carNumber >= cars.size() ? null : cars.get(carNumber).left().getVehicleId();
	}

	/** The vehicle the local player is riding, or null when they are riding nothing (or a lift). */
	@Nullable
	private static VehicleExtension ridingVehicle() {
		final long ridingVehicleId = VehicleRidingMovement.getRidingVehicleId();
		if (ridingVehicleId == 0) {
			return null;
		}
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == ridingVehicleId) {
				return vehicle;
			}
		}
		return null;
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

	/**
	 * Which way along this pane's normal the DRIVER is: +1 if +normal points at the driver, -1 if it
	 * points away.
	 *
	 * Read from the cab's dashboard anchor, whose normal points at the driver by contract. The pane
	 * cannot answer this itself - its normal is whatever winding the modeller's mesh has, which is
	 * exactly the trap this closes: BR101 V25 reversed every pane's normal on re-export and the water
	 * moved to the OUTSIDE of the glass, invisible from the cab (notes/205, notes/211).
	 */
	private static double waterSideOf(String vehicleId, int carNumber, Anchor anchor) {
		final Anchor hud = MmtrVehicleAnchors.findHud(MmtrVehicleAnchors.get(vehicleId), carNumber, anchor.cab);
		if (hud == null) {
			LOGGER.info("[MMTR-WSHLD] {} has no dashboard anchor for cab {} - the side the water is drawn "
					+ "on falls back to the glass normal itself", anchor.name, anchor.cab);
			return -1;
		}
		final Vector glassNormal = toModelSpace(anchor.fileNormal);
		final Vector driverNormal = toModelSpace(hud.fileNormal);
		final double dot = glassNormal.x() * driverNormal.x() + glassNormal.y() * driverNormal.y()
				+ glassNormal.z() * driverNormal.z();
		return dot > 0 ? 1 : -1;
	}

	/**
	 * The moving part of ONE wiper's state. The water is NOT here: a glass has one bead field however many
	 * wipers it carries, so {@link State} owns the drops and holds one of these per wiper.
	 */
	private static final class WiperState {

		/** Where the blade is now. The blade's angle is also what the wipe test is measured against. */
		private double angleDeg;
		/**
		 * The blade's angle at the PREVIOUS step - the near edge of the area it has just swept, which is
		 * half of the blade's line test in {@code onBlade}. It must be captured before
		 * {@link State#advanceWiper} moves the blade, once per advance.
		 */
		private double previousAngleDeg;
		/** Position inside the current stroke: 0 = parked, 1 = fully out, 2 = parked again. */
		private double phase;
		/** Counts advance() calls, so the wipe diagnostic prints one line per ~10 frames. */
		private int logCounter;
		/**
		 * Which way this wiper is sweeping: +1 outward, -1 on the return. Held across the animation's wrap
		 * (see the wipe), which is what makes it usable as the turnaround test - the moment the water the
		 * blade is holding goes back on the glass. It starts at +1 to match the +1 direction convention, so
		 * a bead can never be put back for disagreeing with a direction that has not been established yet.
		 */
		private int travelDirection = 1;
		/**
		 * Whether this blade moved during THIS advance. The put-back and the wipe log both key off it - per
		 * wiper, because with two blades on one glass one of them can be dwelling while the other is
		 * mid-stroke.
		 */
		private boolean moving;
		/**
		 * THE RIDGE: the water this blade has taken off the glass and has not put back yet. One list per
		 * wiper is the whole of the carry state - the beads in it are invisible, tagged with this wiper's
		 * index ({@code Drop.heldByWiper}) and out of the rain's reach until {@code putBackRidge} lays them
		 * down along the blade's own line.
		 */
		private final ObjectArrayList<Drop> ridge = new ObjectArrayList<>();
		/**
		 * The last direction this blade was actually travelling, as a unit vector in canvas metres. The
		 * put-back needs it precisely when the blade is NOT travelling any more (it has just stopped, or
		 * turned around), so it is remembered rather than recomputed. Defaults to straight down the glass.
		 */
		private double lastPushX;
		private double lastPushY = -1;
		/**
		 * How fast the blade was moving the last time it moved, held for the release: {@code omegaRadPerS} is
		 * the angular rate of a co-axial blade (its points move at {@code omega * radius}, so the water off a
		 * TIP leaves three times as fast as the water off the inner end) and {@code bladeSpeedMps} is the one
		 * speed of a translating blade. See {@code RELEASE_FLING_M}: these are what the water is thrown at.
		 */
		private double omegaRadPerS;
		private double bladeSpeedMps;
		/**
		 * Whether the put-back has already been reported during the stop this blade is in. A dwell runs the
		 * put-back on every frame (see applyWipe) and only the first one is worth a log line.
		 */
		private boolean putBackReported;

		private WiperState(WiperConfig wiper) {
			angleDeg = wiper.parkAngleDeg;
			previousAngleDeg = wiper.parkAngleDeg;
		}
	}

	private static final class State {

		private final Anchor anchor;
		private final WindshieldConfig config;
		/** See waterSideOf: +1 when +normal points at the driver, -1 when it points away. */
		private final double waterSide;
		private final Drop[] drops;
		private final Random random;
		private long lastAdvanceMillis;
		private long lastWetMillis;
		/**
		 * One entry per wiper on this glass, index-aligned with {@code config.wipers}. Each wiper has its
		 * own angle, phase and travel direction, because two blades on one screen are two independent
		 * mechanisms - they may park at opposite ends, run at different speeds or be switched on separately,
		 * and they clear the same water.
		 */
		private final WiperState[] wiperStates;
		private float intensity;
		private boolean snow;
		/** For the motion diagnostic only: whether the glass is wet at all this frame. */
		private boolean precipitatingFlag;
		private int motionLogCounter;
		/** New beads created since the last {@code [MMTR-WSHLD] spawn} line, for that line's report. */
		private int spawnedInWindow;
		/** Previous bead positions, so the diagnostic can report how far the field actually moved. */
		private final double[] previousX;
		private final double[] previousY;
		/**
		 * How crowded the glass is around each bead, in beads per square metre, index-aligned with
		 * {@code drops} - refreshed once per advance by {@link #measureDensity()}.
		 *
		 * <p>This is the quantity the whole runoff model is gated on, so it is a FIELD rather than a
		 * per-bead property: whether water runs depends on its neighbours, not on itself. It cannot be
		 * measured inside the per-bead step, either - beads that have already moved this frame would be
		 * counted at their new positions, so the first bead of a cluster would see a different
		 * neighbourhood than the last and the patch would tear itself apart in one frame.</p>
		 */
		private final double[] densityPerM2;
		/** Scratch cell occupancy for {@link #measureDensity()}, kept between frames so a frame allocates none. */
		private int[] densityCellCounts = new int[0];
		/** Beads the blade shoved on this frame's wipe, and how many it shoved clean off the glass. */
		private int pushedInWindow;
		private int pushedOffGlassInWindow;
		/** Reported-window accumulators: see {@code logMotionOnce} for why one frame is not enough. */
		private double windowMaxStep;
		private double windowTravelled;
		private int windowMovers;
		private int windowFramesAdvancing;

		// --- forces ---------------------------------------------------------------------------
		/** The speed this windshield saw last frame (m/ms), so acceleration can be derived from it. */
		private double lastSpeedMps;
		private boolean speedKnown;
		/** Smoothed acceleration along the glass's right axis (m/s^2): + = the driver is thrown right. */
		private double lateralAcceleration;
		/** Fractional beads waiting to be spawned, so a low spawn rate does not round down to zero. */
		private double spawnAccumulator;

		private State(Anchor anchor, WindshieldConfig config, boolean snow, double waterSide) {
			this.anchor = anchor;
			this.waterSide = waterSide;
			this.config = config;
			this.snow = snow;
			this.drops = new Drop[config.raindrops];
			this.random = new Random(anchor.name.hashCode() * 31L + anchor.car);
			this.previousX = new double[drops.length];
			this.previousY = new double[drops.length];
			this.densityPerM2 = new double[drops.length];
			for (int i = 0; i < drops.length; i++) {
				drops[i] = newDrop(random.nextDouble(), random.nextDouble());
				previousX[i] = drops[i].x;
				previousY[i] = drops[i].y;
			}
			this.wiperStates = new WiperState[config.wipers.length];
			for (int i = 0; i < wiperStates.length; i++) {
				wiperStates[i] = new WiperState(config.wipers[i]);
			}
		}

		/**
		 * The state of one wiper's blade, or null when this config object is not the one this state was
		 * built from (a pack reload between the two). Null keeps the part parked rather than driving the
		 * WRONG blade, which is the failure a positional lookup would produce.
		 */
		@Nullable
		private WiperState wiperState(WiperConfig wiper) {
			for (int index = 0; index < config.wipers.length; index++) {
				if (config.wipers[index] == wiper) {
					return wiperStates[index];
				}
			}
			return null;
		}

		private void advance(long now, float rainGradient, double speedMetersPerMs, WiperMode mode) {
			// THE SAME INSTANT, SEEN TWICE. advance() is reached once per RENDER PASS, and a cab whose
			// outside and inside are both being drawn gets two passes carrying the SAME
			// System.currentTimeMillis(). The second one must be a no-op. With the clock at zero the arm
			// does not move at all, and a step that measured zero travel used to be indistinguishable
			// from the blade turning around: the release test in applyWipe read it as "the blade is not
			// going anywhere" and put the WHOLE wave down on the spot. Measured on BR101 cab 2
			// (logs/latest.log, 2172 blade-moving steps): 561 of them (25.8%) were these repeats, 501 of
			// those dumped water, worth 2031 beads - 97.8% of every release in the session, against 11
			// genuine turnarounds. So the water was dropped several times per stroke and never travelled
			// further than one pass' worth of blade motion, which is exactly the "it only pushes the
			// water a little bit to one side" report. Which of the two happens depends only on whether
			// the two passes fall inside one millisecond, and that is why the symptom came and went.
			if (now == lastAdvanceMillis) {
				return;
			}
			final long elapsed = lastAdvanceMillis == 0 ? 16 : Math.min(250, Math.max(0, now - lastAdvanceMillis));
			lastAdvanceMillis = now;
			final double elapsedSeconds = elapsed / 1000.0;

			intensity = Math.max(0, Math.min(1, rainGradient));
			precipitatingFlag = rainGradient > 0.02F;
			// Internal speeds are m/ms; every force below is SI, so convert once.
			final double speedMps = speedMetersPerMs * 1000;

			// Fall direction: drops run DOWN THE GLASS, not down the panel's local -Y. A raked
			// windscreen makes those two different, and using the local axis gives rain that slides
			// sideways across the screen. So project world down onto the glass plane.
			final double[] down = panelDown();
			// The blades' angles from the PREVIOUS step, captured before they move: each is the near edge of
			// the band its blade is about to sweep. (It used to be maintained only for the image rebuild,
			// which is gone, so after the cleanup it was never assigned at all and stayed at the park
			// angle - a band starting at park every frame.)
			for (final WiperState wiperState : wiperStates) {
				wiperState.previousAngleDeg = wiperState.angleDeg;
			}
			advanceWipers(elapsedSeconds, mode);
			trackAcceleration(elapsedSeconds, speedMps);
			advanceDrops(elapsedSeconds, speedMps, down);
			// (There used to be a second mergeDrops() call here. advanceDrops already ends with one, and
			// nothing happens in between, so the field was merged twice per frame for nothing - a wasted
			// O(n) spatial-hash pass, and a second pass in the same frame lets a chain of merges run
			// further than one step of the model should. The offline harness only ever had the one call,
			// which is why the divergence was invisible.)
			// THE WIPERS' WORK. This is what "the rain is wiped off" means: every bead a blade has crossed
			// is taken OFF the glass on this frame, and the population is refilled by new water at a rate
			// that follows the rain.
			//
			// It lives here, immediately after the step and before the draw, for two reasons. The wipe used
			// to be applied inside the image rebuild - which is gone, and with it the only caller of the
			// wipe test, so the blade had stopped touching the beads at all. And doing it after the step
			// rather than at draw time keeps the order the old code had: the field is advanced, then wiped,
			// then looked at. drawBeads therefore only reads drop.visible and needs no wipe logic of its own.
			//
			// EVERY wiper on the glass runs its own pass over the same field, in order: two blades clearing
			// one screen is two mechanisms working the same water, and the second one sees what the first
			// left behind.
			applyWipe(elapsedSeconds);

			// There is no image to rebuild any more: the beads are geometry, drawn every frame by
			// drawBeads, so the only thing advance() still has to do after stepping the field is report it.
			// Measure the field's OWN movement, after the step. Two numbers, because the first attempt at
			// this reported only the LAST frame ("maxStep/frame") and was therefore usually 0: a single
			// frame mostly contains pinned beads that correctly do not move, so the sample said "frozen"
			// about a field that was running. What matters is the maximum over the whole reporting window.
			double step = 0;
			double travelled = 0;
			int movers = 0;
			for (int index = 0; index < drops.length; index++) {
				final double dx = drops[index].x - previousX[index];
				final double dy = drops[index].y - previousY[index];
				final double distance = Math.hypot(dx, dy);
				if (distance > 1.0E-6) {
					movers++;
				}
				travelled += distance;
				step = Math.max(step, distance);
				previousX[index] = drops[index].x;
				previousY[index] = drops[index].y;
			}
			windowMaxStep = Math.max(windowMaxStep, step);
			windowTravelled += travelled;
			windowMovers = Math.max(windowMovers, movers);
			windowFramesAdvancing++;
			logMotionOnce(now, down, speedMps);
		}

		/**
		 * [MMTR-WSHLD] One line every ~2 s per pane while it is wet, because "the rain does not move in
		 * game" cannot be told apart from any of: the weather never reached the glass, the beads are all
		 * legitimately pinned, the bead field never changes, or the image is rebuilt identically. Those
		 * need completely different fixes and look the same on screen.
		 *
		 * <p>Prints the field's OWN state, not the forces: how many beads are inside the glass, how many
		 * have grown past their pinning threshold, the mean surface speed, the spread of positions, and
		 * how many times the image has actually been rebuilt. A live field moves all of those; a frozen
		 * one moves none of them.</p>
		 */
		private void logMotionOnce(long now, double[] down, double speedMps) {
			// Counted in FRAMES, not milliseconds, and unconditional: the interesting case is exactly the
			// one where the glass is dry, and a time gate plus a "only when wet" gate would hide it.
			if (++motionLogCounter < MOTION_LOG_EVERY_N_FRAMES) {
				return;
			}
			motionLogCounter = 0;
			int alive = 0;
			int flowing = 0;
			int offGlass = 0;
			double speedSum = 0;
			double radiusSum = 0;
			double densitySum = 0;
			double maxDensity = 0;
			double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
			double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible) {
					continue;
				}
				alive++;
				// "Flowing" is the density latch of advanceDrop, restated for the log - a bead is running
				// because its PATCH is crowded, not because the bead itself is big. Reading a bead's own
				// radius here was how the old line could report pastPin=120 next to a completely still
				// screen: at a fixed generation it pinned, because pinning was never about the bead.
				if (densityPerM2[index] > config.densityThresholdPerM2) {
					flowing++;
				}
				speedSum += drop.surfaceSpeedMps;
				radiusSum += drop.radiusM;
				densitySum += densityPerM2[index];
				maxDensity = Math.max(maxDensity, densityPerM2[index]);
				minX = Math.min(minX, drop.x);
				maxX = Math.max(maxX, drop.x);
				minY = Math.min(minY, drop.y);
				maxY = Math.max(maxY, drop.y);
				if (drop.x < 0 || drop.x > 1 || drop.y < 0 || drop.y > 1) {
					offGlass++;
				}
			}
			LOGGER.info("[MMTR-WSHLD] motion {} prev={} intensity={} snow={} speedMps={} down=({}, {}) "
							+ "alive={} flowing={} density=(mean {}, max {}, threshold {}) meanSurfaceSpeed={} meanRadiusMm={} "
							+ "windowMaxStep={} travelled={} movers={} frames={} "
							+ "spread=({}, {}) offGlass={} cutByBlades={} leftGlass={} mode={} angle={}",
					anchor.name, precipitatingFlag, round(intensity), snow, round(speedMps),
					round(down[0]), round(down[1]), alive, flowing,
					alive == 0 ? 0 : (int) Math.round(densitySum / alive), (int) Math.round(maxDensity),
					(int) Math.round(config.densityThresholdPerM2),
					alive == 0 ? 0 : round(speedSum / alive),
					alive == 0 ? 0 : round(radiusSum / alive * 1000),
					round(windowMaxStep), round(windowTravelled),
					windowMovers, windowFramesAdvancing,
					alive == 0 ? 0 : round(maxX - minX), alive == 0 ? 0 : round(maxY - minY),
					offGlass, pushedInWindow, pushedOffGlassInWindow, wiperMode, round(wiperStates[0].angleDeg));
			// The spawn rate is a BEHAVIOUR ("heavier rain lands water faster"), so it gets its own line
			// rather than being something the driver has to feel out: rate/s is what this frame's weather
			// asked for, spawnedWindow is how many beads actually appeared since the previous report.
			//
			// holding/waiting are the POPULATION ACCOUNTING, and they are the two numbers that decide
			// whether the spawner can do its job: holding is water in a blade's ridge (off the glass by the
			// wiper's doing and not available to the rain), waiting is water that is merely invisible and
			// free to be placed. A glass that looks too dry is either spawn-limited (waiting near 0) or
			// blade-limited (holding large) - and those two need opposite fixes.
			int holding = 0;
			int waiting = 0;
			for (final Drop drop : drops) {
				if (drop.heldByWiper != 0) {
					holding++;
				} else if (!drop.visible) {
					waiting++;
				}
			}
			LOGGER.info("[MMTR-WSHLD] spawn {} intensity={} rate/s={} alive={} cap={} holding={} waiting={} spawnedWindow={}",
					anchor.name, round(intensity), round(spawnRatePerSecond()), alive, spawnCap(), holding, waiting,
					spawnedInWindow);
			spawnedInWindow = 0;
			pushedInWindow = 0;
			pushedOffGlassInWindow = 0;
			windowMaxStep = 0;
			windowTravelled = 0;
			windowMovers = 0;
			windowFramesAdvancing = 0;
		}

		/**
		 * Moves every blade on this glass. Each travels at a CONSTANT angular rate (a real linkage moves
		 * roughly linearly, and the old cosine ease made the arm crawl at both ends and blur through the
		 * middle), then rests at its park position for {@link WiperMode#dwellS}.
		 *
		 * <p>The phase is a 0..2 triangle that STARTS at 0 - parked - so switching from 关 to 慢 begins
		 * where the blade already is. Every mode change is a plain rate change with no repositioning:
		 * that is what makes 慢 -> 快 -> 慢 feel like a stalk rather than a cut.</p>
		 *
		 * <p>The wipers are stepped in order and INDEPENDENTLY: a glass may carry two, and nothing requires
		 * them to share a park angle, a stroke or even a phase. They do share the stalk, the weather and the
		 * water.</p>
		 */
		private void advanceWipers(double elapsedSeconds, WiperMode mode) {
			for (int index = 0; index < config.wipers.length; index++) {
				advanceWiper(config.wipers[index], wiperStates[index], elapsedSeconds, mode);
			}
		}

		private void advanceWiper(WiperConfig wiper, WiperState state, double elapsedSeconds, WiperMode mode) {
			if (mode == WiperMode.OFF || !wiper.wiper) {
				// Off: the blade holds position and NOTHING is wiped. A blade left mid-screen is honest -
				// it is what a driver sees when they switch a wiper off mid-stroke - but a parked wiper
				// must not keep clearing glass, or the screen dries itself out with the stalk at 关.
				state.moving = false;
				return;
			}
			// The stroke speed is the STALK's (mode.periodS), exactly as before: every wiper on the train
			// runs at the mode's rate. (A blade's own `periodS` in the config is deliberately NOT applied
			// here - it never was, and switching it on now would change the speed of any pack that happens
			// to set it.)
			final double strokeRate = 2 / Math.max(0.05, mode.periodS);
			final double strokeTime = 2 / strokeRate;
			final double cycle = strokeTime + mode.dwellS;
			double time = state.phase / strokeRate + elapsedSeconds;
			if (time >= cycle) {
				// Wrap, keeping the overshoot so a long frame does not slow the arm down.
				time -= cycle;
			}
			if (time >= strokeTime) {
				// Dwell at park. The blade is already there, so this only holds the phase.
				time = 0;
				state.moving = false;
			} else {
				state.moving = true;
			}
			state.phase = time * strokeRate;
			state.angleDeg = wiper.parkAngleDeg + sweepUnit(state.phase) * wiper.sweepDeg;
			// The sector the arm covered since the last repaint is what gets the wet film drawn on it.
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
		 *   <li><b>Running.</b> A patch whose LOCAL BEAD DENSITY is past {@code densityThresholdPerM2}
		 *       lets go: surface tension can no longer bridge the gaps between neighbours and the water
		 *       runs down the glass. A lone bead on a sparse screen never runs, however long it sits
		 *       there - which is what a drizzle looks like.</li>
		 * </ol>
		 *
		 * <p>That density threshold is the whole effect, and it REPLACED a per-bead critical radius
		 * ({@code radiusM > staticThresholdM}). The radius gate could not produce the behaviour asked for
		 * and contradicted itself in game: growth is scaled by intensity, so in light rain no bead ever
		 * reached the radius and the field never moved at all - reported as "sometimes it falls,
		 * sometimes nothing moves". Density has no such blind spot, because it is a property of the SPACE
		 * and not of a bead: what the weather changes is how likely a patch is to be crowded, not whether
		 * a rule can fire at all.</p>
		 *
		 * <p>It is also what makes the wiper's shove visible, which is the other half of the model: the
		 * blade <b>pushes</b> the water it crosses ({@link #applyWipe}) instead of deleting it, so the
		 * beads pile up along the blade's leading edge - and a pile is by construction a patch past the
		 * threshold, so it runs. "The wiper pushes the rain, the pushed rain is too much water to stay
		 * put, so it runs down" is those two rules and nothing else.</p>
		 *
		 * <h2>Beads pinch off</h2>
		 *
		 * <p>A running bead sheds a smaller bead behind it every {@code pinchOffDistanceM}. That is the
		 * real mechanism behind a rivulet's speckled wake, and it is also what refills the pinned
		 * population without the spawner having to invent beads in mid-air.</p>
		 *
		 * <p><b>What is deliberately NOT here any more:</b> the wetness field. A running bead used to
		 * deposit moisture into a coarse grid on the glass, which was drawn as the streaks and which the
		 * wiper cleared. That whole layer - field, canvas, texture, upload - has been removed, and the
		 * beads are now the only thing drawn, as geometry. Tails are off as well ({@code drop.trail} stays
		 * 0), so a bead is a plain round drop.</p>
		 */
		private void advanceDrops(double elapsedSeconds, double speedMps, double[] down) {
			// Gravity ALONG the glass. It no longer moves anything by itself - see the pinning latch in
			// advanceDrop - but it is still the force the airflow has to cancel, so it still sets the speed
			// at which the airflow term takes over.
			final double gravityDown = config.fallMps * (snow ? 0.35 : 1) * (0.6 + 0.9 * intensity);
			// Balance point: airflow == gravity. AIRFLOW_COEFFICIENT is calibrated so the crossover sits
			// at a plausible line speed (about 30 km/h) rather than being another thing to tune per model.
			//
			// That calibration is only valid for a glass whose slope the harness also used, and the
			// harness hard-codes `down = (0,-1)` with a vertical-referenced `up`. On a real raked
			// windscreen the world's down projects into the glass at only `sqrt(1 - up.y()^2)` of its
			// magnitude, and both terms have to shrink together or the crossover moves. Measured on
			// BR101: the projection is 0.646, so the at-rest fall is 0.55 * 0.646 = 0.356 m/s ALONG the
			// glass and the crossover fell to 4.2 m/s = 15 km/h - i.e. the rain pinned itself into a still
			// picture as soon as the train was rolling. Scaling the coefficient by the same factor puts the
			// crossover back at the speed it was calibrated for, at any windscreen angle, with no new
			// number to maintain.
			final double slope = Math.max(0.2, Math.hypot(down[0], down[1]));
			final double airflow = AIRFLOW_COEFFICIENT * slope * speedMps * speedMps;
			// The ONLY uphill driver, and the only thing that can move an UNCROWDED bead: wind strong
			// enough to beat the slope. Below the crossover this is 0, and crowding alone decides who runs.
			final double liftSpeed = Math.max(0, Math.min(config.creepMps, airflow - gravityDown));
			final double lateralAccel = Math.max(-MAX_LATERAL_ACCELERATION, Math.min(MAX_LATERAL_ACCELERATION, lateralAcceleration));
			// WHO IS CROWDED is measured BEFORE anyone moves, so every bead in a patch sees the same
			// neighbourhood on this frame. Measuring it inside the step instead would let beads that have
			// already moved this frame be counted at their new positions, and the patch would tear itself
			// apart within one frame - the first bead of a cluster running, the last one still pinned.
			measureDensity();
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible) {
					// Off the glass: either the rain has not put it there yet, or a blade is holding it in its
					// ridge. Neither is water on the windscreen, so neither grows, falls or sheds.
					continue;
				}
				advanceDrop(drop, densityPerM2[index], elapsedSeconds, liftSpeed, lateralAccel, down);
			}
			spawnForWeather(elapsedSeconds);
			mergeDrops();
		}

		/**
		 * One bead: grow it, decide whether the water AROUND it has let go, move it, and shed what it
		 * leaves behind.
		 *
		 * @param densityPerM2 how crowded this bead's patch is, in beads per square metre, from
		 *                     {@link #measureDensity()} - the quantity the pinning latch is built on
		 * @param liftSpeed    uphill speed the airflow can produce right now (m/s); 0 when it cannot beat
		 *                     the slope at all, which is the normal case below about 30 km/h
		 */
		private void advanceDrop(Drop drop, double densityPerM2, double elapsedSeconds, double liftSpeed, double lateralAccel, double[] down) {
			// Growth first: a bead in a stream collects water and swells until it runs. This is the only
			// thing a pinned bead does.
			drop.radiusM = Math.min(config.maxBeadRadiusM, drop.radiusM + config.growthMps * elapsedSeconds * (0.4 + intensity));
			// NO ink grow-in any more: a bead that is visible is fully visible. See Drop.visible.

			// THE FLING RUNS OUT FIRST, before any of the latch below can return early: water a blade has just
			// thrown keeps going whatever the crowding says, and friction on the glass is all that stops it.
			// It is in METRES per second and converted here, so its speed does not change with the shape of the
			// glass the way the fraction-space runoff speeds do.
			if (drop.flingX != 0 || drop.flingY != 0) {
				drop.x += drop.flingX * elapsedSeconds / anchor.widthM;
				drop.y += drop.flingY * elapsedSeconds / anchor.heightM;
				final double keep = drop.flingDecayS > 1.0E-4
						? Math.max(0, 1 - elapsedSeconds / drop.flingDecayS)
						: 0;
				drop.flingX *= keep;
				drop.flingY *= keep;
				if (Math.abs(drop.flingX) < 1.0E-4 && Math.abs(drop.flingY) < 1.0E-4) {
					drop.flingX = 0;
					drop.flingY = 0;
				}
			}

			// THE PINNING LATCH, AND THE FLOOR BENEATH IT. Motion down the glass has three sources now:
			//
			//   1. the patch is CROWDED - more beads per square metre than densityThresholdPerM2, so
			//      pressure > 0. The surplus over the threshold is how far past "it lets go" the patch is,
			//      and it becomes the runoff speed. This is the rule that reads "the water only starts to
			//      RUN once there is enough of it", and it is what makes the line a wiper puts back run off
			//      the glass: that line is crowded by construction.
			//   2. the AIRFLOW is strong enough to beat the slope (liftSpeed > 0), the only thing that can
			//      move a bead UPHILL, and the only thing that can move a bead with no neighbours at all.
			//   3. the FLOOR: a bead that is neither crowded nor lifted still slides down at
			//      FALL_CREEP_FRACTION of the runoff speed. See the constant: without it a still glass in
			//      moderate rain is a frozen picture, which is what "the drops should fall and disappear"
			//      was reported against.
			//
			// Size is deliberately NOT a gate. It used to be (radiusM > staticThresholdM) and the two terms
			// fought: growth is scaled by intensity, so in light rain nothing ever crossed the threshold and
			// a sparse glass was indistinguishable from a frozen one. Size only scales the SPEED.
			//
			// What crowding decides is therefore how FAST, not whether - and that is the whole difference
			// between "the water only lets go once there is enough of it" and "nothing ever moves".
			final double pressure = Math.max(0, densityPerM2 / Math.max(1.0E-6, config.densityThresholdPerM2) - 1);
			// Heavier beads run a little faster than fine ones. A second-order taste detail now, not the
			// gate it used to be: it only scales a speed the density has already allowed.
			final double sizeScale = 0.5 + 0.5 * (drop.radiusM / Math.max(1.0E-6, config.maxBeadRadiusM));
			// WHETHER the water runs is the threshold's job (pressure > 0), HOW FAST is the crowding's.
			// The pressure term is guarded so that a patch AT the threshold is still pinned: without the
			// guard the mobility term is a positive constant and the latch disappears entirely.
			final double mobility = pressure <= 0 ? 0 : Math.min(1, RUNOFF_MARGINAL_FACTOR + pressure);
			final double runoffSpeed = config.runoffMps * mobility * sizeScale;
			// THE FLOOR UNDER THE FALL (2026-09-24). Below the crowding threshold a bead is no longer pinned
			// outright - it creeps downhill at FALL_CREEP_FRACTION of the runoff speed, so a screen in
			// moderate rain at a standstill drains and refills instead of holding a still picture for ever.
			// Crowding still decides how FAST: a patch past the threshold runs at the full runoff speed, which
			// is what makes the line a blade puts back visibly run off the glass.
			final double fallSpeed = Math.max(runoffSpeed, FALL_CREEP_FRACTION * config.runoffMps);
			if (liftSpeed <= 0 && fallSpeed <= 0) {
				// Only reachable with runoffMps = 0, i.e. a pack that asks for a frozen glass.
				drop.surfaceSpeedMps = 0;
				drop.trail = Math.max(0, drop.trail - elapsedSeconds * TRAIL_DRY_MPS);
				return;
			}

			// A running bead moves at the surface speed, faster for a heavier one. Positive = running UP
			// the glass (the airflow has won), negative = sliding down it; down[] points downhill, so "up"
			// is the opposite and the sign is carried through the step below.
			final double climbSpeed = (liftSpeed > 0 ? liftSpeed : -fallSpeed) * sizeScale;
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

			// NO TAIL IS ACCUMULATED. It used to grow with distance run and was drawn as a stretched quad,
			// which is what made every bead look like a long bar. The field is composed of round drops for
			// now; a wake, when it comes back, will be the moisture a drop leaves ON THE GLASS (the wetness
			// field), not a growing length of geometry. drop.trail is therefore kept at 0 and only
			// TRAIL_DRY_MPS still touches it.
			drop.trail = 0;

			// Pinch off: drop a smaller bead roughly every PINCH_OFF_DISTANCE_M of travel. This is what
			// puts the speckled wake behind a rivulet, and it refills the pinned population for free.
			//
			// THE CHILD'S WATER IS THE PARENT'S (2026-09-24). This used to hand the child 0.55 of the
			// parent's radius and leave the parent untouched - i.e. it CREATED 30% of the parent's area at
			// every shed, and a runner's wake grew water out of nothing. AREA is conserved here exactly as
			// it is in mergePair, and the consequence is the behaviour that was asked for: a drop that runs
			// and sheds gets smaller as it goes, and a drop that has given everything it had is GONE.
			if (!snow) {
				drop.pinchAccumulatorM += speed * elapsedSeconds;
				if (drop.pinchAccumulatorM >= PINCH_OFF_DISTANCE_M && drop.radiusM > config.staticThresholdM * 1.4) {
					drop.pinchAccumulatorM = 0;
					pinchOff(drop);
				}
			}

			// OFF THE GLASS IS GONE (2026-09-24). This used to respawn the bead on the spot - a fresh random
			// position and a fresh size - which is invisible either way, but it also let a bead run 10% of
			// the glass BELOW the frame before it counted as gone, and it blurred "ran off the bottom" into
			// "was moved somewhere else". A drop that reaches the frame simply leaves; new water arrives with
			// the rain, at the rate the weather deserves (see spawnForWeather). The sides still overrun,
			// because a bead entering and leaving PAST the frame is what stops the edges looking like a wall.
			if (drop.x < -0.05 || drop.x > 1.05 || drop.y < 0 || drop.y > 1.10) {
				drop.visible = false;
				drop.heldByWiper = 0;
				pushedOffGlassInWindow++;
			}
		}

		/**
		 * How crowded the glass is around every bead, in <b>beads per square metre</b>.
		 *
		 * <p>The probe is the 3x3 block of {@code densityCellM} cells around the bead, divided by the
		 * block's true area. Three properties of that choice are load bearing:</p>
		 *
		 * <ul>
		 *   <li><b>It is an area, not a count.</b> A count would mean something different on every
		 *       windscreen; beads per square metre is the same physical statement on a 1.2 m2 cab glass
		 *       and on a 4.7 m2 bus one, so {@code densityThresholdPerM2} is a value a model author can
		 *       reuse instead of re-deriving.</li>
		 *   <li><b>It is smoothed over nine cells</b> rather than read off one. A single 5 cm cell holds
		 *       well under one bead on average at this population, so a one-cell density would report 0 or
		 *       400/m2 and the runoff would fire noise instead of patches. Nine cells is the smallest
		 *       window in which "crowded" is a meaningful word at 140 beads per glass.</li>
		 *   <li><b>The probe area is the full 3x3 even at the frame's edge.</b> A bead on the edge really
		 *       does have fewer neighbours on that side, and inflating its density to compensate would
		 *       start rivulets running along the frame that no patch caused.</li>
		 * </ul>
		 *
		 * <p>Cost is linear in the bead count - one bucket count and one nine-cell sum per bead - and the
		 * scratch array is reused between frames, so a 60 Hz glass allocates nothing here.</p>
		 */
		private void measureDensity() {
			if (drops.length == 0) {
				return;
			}
			final double cellWidth = Math.max(1.0E-3, config.densityCellM / Math.max(1.0E-3, anchor.widthM));
			final double cellHeight = Math.max(1.0E-3, config.densityCellM / Math.max(1.0E-3, anchor.heightM));
			final int columns = Math.max(1, (int) Math.ceil(1 / cellWidth));
			final int rows = Math.max(1, (int) Math.ceil(1 / cellHeight));
			final int cells = columns * rows;
			if (densityCellCounts.length < cells) {
				densityCellCounts = new int[cells];
			} else {
				java.util.Arrays.fill(densityCellCounts, 0, cells, 0);
			}
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				final int cellX = Math.max(0, Math.min(columns - 1, (int) (drop.x / cellWidth)));
				final int cellY = Math.max(0, Math.min(rows - 1, (int) (drop.y / cellHeight)));
				densityCellCounts[cellY * columns + cellX]++;
			}
			final double blockAreaM2 = 9 * config.densityCellM * config.densityCellM;
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible) {
					densityPerM2[index] = 0;
					continue;
				}
				final int cellX = Math.max(0, Math.min(columns - 1, (int) (drop.x / cellWidth)));
				final int cellY = Math.max(0, Math.min(rows - 1, (int) (drop.y / cellHeight)));
				int neighbours = 0;
				for (int offsetY = -1; offsetY <= 1; offsetY++) {
					final int neighbourY = cellY + offsetY;
					if (neighbourY < 0 || neighbourY >= rows) {
						continue;
					}
					for (int offsetX = -1; offsetX <= 1; offsetX++) {
						final int neighbourX = cellX + offsetX;
						if (neighbourX < 0 || neighbourX >= columns) {
							continue;
						}
						neighbours += densityCellCounts[neighbourY * columns + neighbourX];
					}
				}
				densityPerM2[index] = neighbours / blockAreaM2;
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
				if (candidate != parent && !candidate.visible && candidate.heldByWiper == 0) {
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
			// The parent pays for it - see the note at the caller. A parent left below the smallest drawable
			// bead is spent, and this is one of the two ways water actually leaves the windscreen.
			final double remainingAreaM2 = parent.radiusM * parent.radiusM - child.radiusM * child.radiusM;
			if (remainingAreaM2 <= config.minBeadRadiusM * config.minBeadRadiusM) {
				parent.visible = false;
				parent.heldByWiper = 0;
			} else {
				parent.radiusM = Math.sqrt(remainingAreaM2);
			}
			child.visible = true;
			child.trail = 0;
			child.lateralVelocity = 0;
			// A shed speck is PINNED by surface tension - it does not inherit the parent's fling, which would
			// send the whole wake skating after the runner.
			child.flingX = 0;
			child.flingY = 0;
			child.pinchAccumulatorM = 0;
			child.directionX = parent.directionX;
			child.directionY = parent.directionY;
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
			// A bead that re-enters the glass is OFF until the spawner puts it back on, so a bead leaving
			// the frame and a bead being wiped look the same to the renderer: not there.
			drop.visible = false;
			drop.heldByWiper = 0;
			drop.flingX = 0;
			drop.flingY = 0;
			drop.directionX = 0;
			drop.directionY = -1;
			drop.surfaceSpeedMps = 0;
			drop.pinchAccumulatorM = 0;
			drop.heldByWiper = 0;
		}

		/**
		 * Tops the field back up to {@code raindrops} beads. Beads are neither created nor destroyed on a
		 * whim: one that runs off the glass re-enters it, and this only replaces the ones a wipe has
		 * knocked out, so the population stays at the configured number and the only thing the weather
		 * changes is how fast a knocked-out bead becomes visible again.
		 *
		 * <p>The bead that comes back gets a FRESH RANDOM SIZE rather than the minimum (see
		 * {@link #respawn}), and it arrives wet (ink at least 0.55): "falling into the field" is instant,
		 * while the 0.3 -> 1 grow-in belongs to the WIPE.</p>
		 */
		private void spawnForWeather(double elapsedSeconds) {
			if (intensity <= 0.02 || drops.length == 0) {
				return;
			}
			final int cap = spawnCap();
			int alive = 0;
			for (final Drop drop : drops) {
				if (drop.visible) {
					alive++;
				}
			}
			// THE RATE FOLLOWS THE RAIN; the count is only a CEILING.
			//
			// This used to stop dead once the population reached `target`, which made the glass behave
			// like a fixed set of slots: wipe them away and little came back until the count fell under
			// the target, then refills trickled in at a flat rate whatever the weather was doing. What the
			// driver should see is the opposite - the harder it rains, the faster new water appears - so
			// the RATE is a fraction of the population per second, scaled by intensity, and the
			// intensity-scaled count is only a cap that stops a downpour from filling every pixel.
			//
			// The accumulator keeps running while at the cap so that a gap the blade has just opened starts
			// filling IMMEDIATELY rather than after the next whole bead is due. The ceiling is expressed in
			// SECONDS OF RATE rather than a fixed count: a fixed 4 was fine when the rate was 2/s, but it
			// also silently capped the rate itself at 4/s, which made every value of the config knob above
			// that do nothing at all.
			final double ratePerSecond = spawnRatePerSecond();
			spawnAccumulator = Math.min(Math.max(4, ratePerSecond * 0.25), spawnAccumulator + elapsedSeconds * ratePerSecond);
			while (spawnAccumulator >= 1 && alive < cap) {
				spawnAccumulator -= 1;
				// Any bead that is currently OFF the glass is one a wipe or the frame edge removed; which
				// one it is does not matter, so take the first. (This used to search for the lowest ink,
				// which only meant anything while a bead had a fade-in value.)
				Drop weakest = null;
				for (final Drop drop : drops) {
					// NOT a bead a blade is holding: those are off the glass by the wiper's doing and they go
					// back where the blade leaves them, not wherever the rain would have put them.
					if (!drop.visible && drop.heldByWiper == 0) {
						weakest = drop;
						break;
					}
				}
				if (weakest == null) {
					break;
				}
				respawn(weakest);
				// Landing is instant: a bead that has just arrived is as wet as any other. There is no
				// grow-in, because that was an artefact of the old image repaint.
				weakest.visible = true;
				alive++;
				spawnedInWindow++;
			}
		}

		/**
		 * New beads per second at the current rain intensity. LINEAR in intensity on purpose, so the
		 * behaviour is legible rather than something to feel out: light rain (0.3) lands water at 30% of a
		 * downpour's rate (1.0).
		 *
		 * <p><b>The rate is a FRACTION OF THE GLASS PER SECOND, not a count.</b> It used to be the raw
		 * config value {@code spawnPerSecond} (default 2), which meant a downpour added TWO beads a second
		 * to a glass that holds 140 - a stripped screen took over a minute to refill, reported in game as
		 * "the spawn rate is a bit slow". A count only means anything relative to the capacity, so the
		 * knob is now "what fraction of the population lands per second": 0.25/s refills a downpour-stripped
		 * screen in about 4 seconds, which is the order of magnitude a real windscreen re-wets at.</p>
		 */
		private double spawnRatePerSecond() {
			return config.spawnPopulationPerSecond * drops.length * intensity;
		}

		/**
		 * Ceiling on how many beads the glass may hold, scaled by intensity: a downpour fills the glass, a
		 * drizzle speckles it. A LIMIT, not a switch - below it the spawn RATE decides, which is what makes
		 * "wipe a hole and watch it refill at the speed the weather deserves" work at all.
		 */
		private int spawnCap() {
			return (int) Math.round(drops.length * (0.25 + 0.75 * intensity));
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
			// THE CELL SIZE IS THE LARGEST DISTANCE A MERGE CAN REACH, not the merge threshold. A 3x3
			// neighbourhood search only finds every pair within one cell, so now that the merge criterion
			// reaches 2 x maxBeadRadiusM the cells have to be at least that wide or pairs are silently
			// missed - a merge rule that is right and a search that cannot find the pair is still no merge.
			final double reachM = Math.max(config.mergeDistanceM, 2 * config.maxBeadRadiusM);
			final double cellWidth = Math.max(reachM / Math.max(1.0E-3, anchor.widthM), 1.0E-3);
			final double cellHeight = Math.max(reachM / Math.max(1.0E-3, anchor.heightM), 1.0E-3);
			final int columns = Math.max(1, (int) Math.ceil(1 / cellWidth));
			final int rows = Math.max(1, (int) Math.ceil(1 / cellHeight));
			final Object2ObjectOpenHashMap<Long, ObjectArrayList<Drop>> cells = new Object2ObjectOpenHashMap<>();
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				final int cellX = Math.max(0, Math.min(columns - 1, (int) (drop.x / cellWidth)));
				final int cellY = Math.max(0, Math.min(rows - 1, (int) (drop.y / cellHeight)));
				final long key = (long) cellY * columns + cellX;
				cells.computeIfAbsent(key, ignored -> new ObjectArrayList<>()).add(drop);
			}

			for (final Drop a : drops) {
				if (!a.visible) {
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
			// A bead must not merge with itself, the pair is visited from both directions, and a bead that
			// is not on the glass has no water to give.
			if (a == b || !a.visible || !b.visible) {
				return;
			}
			// There is no exemption for the water a blade is working any more, because there is no water a
			// blade is working ON the glass: the blade takes what it touches off it (applyWipe), so the only
			// beads that can merge are free rain and the ridge the blade has just put back - and that line
			// coalescing as it runs is exactly what the driver should see.
			final double deltaX = (a.x - b.x) * anchor.widthM;
			final double deltaY = (a.y - b.y) * anchor.heightM;
			// THEY TOUCH. Two discs are in contact when the distance between their centres is their two
			// radii added together - not when it is under some fixed number of millimetres. The fixed
			// comparison this replaces (against mergeDistanceM) meant merging stopped working as soon as a
			// bead grew: on a settled field of this glass (mean radius 10.6 mm) SIX pairs of discs
			// overlapped and the rule refused ALL SIX, by up to 14.5 mm of overlap, because a grown bead's
			// contact distance is nearly twice the threshold. A fat drop rolling over the small ones in its
			// path - the merge that actually matters - could never happen. Measured over 5 s of the same
			// rain from an identical field: 788 merges under the fixed threshold, 858 under contact, with
			// beads over 10 mm going 67 -> 73 and beads at the cap 45 -> 53. The old rule was not inert -
			// freshly spawned beads are 3.5 mm, so their 7 mm contact fits inside 12 mm and they merged all
			// along - it was inert for everything that had grown.
			final double contactM = a.radiusM + b.radiusM;
			if (deltaX * deltaX + deltaY * deltaY > contactM * contactM) {
				return;
			}
			// The larger bead absorbs the smaller, keeps its own place, and gets bigger by hypot() - i.e.
			// AREA is conserved (pi r1^2 + pi r2^2 = pi (r1^2 + r2^2)), which is the one thing a merge of two
			// drops must get right.
			final Drop keeper = a.radiusM >= b.radiusM ? a : b;
			final Drop absorbed = keeper == a ? b : a;
			keeper.radiusM = Math.min(config.maxBeadRadiusM, Math.hypot(keeper.radiusM, absorbed.radiusM));
			respawn(absorbed);
			absorbed.visible = false;
		}

		/**
		 * Runs EVERY wiper on this glass over the bead field, in order.
		 *
		 * <p>There is one field per glass however many blades it carries, so the passes are sequential and
		 * the second wiper sees what the first left: two wipers on one screen clear the same rain. Water one
		 * blade has taken off the glass is held by THAT blade and off the field entirely
		 * ({@code Drop.heldByWiper}), so the second pass cannot see it at all, let alone fight the first one
		 * for it.</p>
		 */
		private void applyWipe(double elapsedSeconds) {
			for (int index = 0; index < config.wipers.length; index++) {
				// The wiper's OWN index, not its array position: a glass whose only fan is wiper 2 has one
				// entry with index 2, and the water it picks up must be tagged with 2 - otherwise the two
				// ownership tests would disagree about which blade owns a bead.
				final WiperConfig wiper = config.wipers[index];
				applyWipe(wiper, wiperStates[index], wiper.index, elapsedSeconds);
			}
		}

		/**
		 * THE BLADE IS A LINE, AND WHAT IT TOUCHES COMES OFF THE GLASS (2026-09-24).
		 *
		 * <p>Three rules, and no state on the beads at all:</p>
		 *
		 * <ol>
		 *   <li><b>CUT.</b> A bead the blade's line is on - see {@link #onBlade} - is HIDDEN on the spot.
		 *       It is not shoved, not carried and not tinted: the glass behind the blade is clean, and the
		 *       blade itself never has a string of drops sitting on it.</li>
		 *   <li><b>HOLD.</b> What the blade has taken off the glass is kept as ONE RIDGE
		 *       ({@code WiperState.ridge}), and that is the whole of the carry state. No per-bead
		 *       ownership, no band membership, no bow wave and no release test are left to get wrong.</li>
		 *   <li><b>PUT BACK.</b> When the blade stops (the park dwell, or the stalk at OFF) or reaches the
		 *       end of a stroke and TURNS AROUND, the ridge is let go - each bead back where it was taken
		 *       from, thrown a little further along the way the blade was travelling. The water lands on the
		 *       glass where the blade has just been, and then behaves like any other water: it creeps, runs
		 *       where it is crowded, and leaves by the frame edge.</li>
		 * </ol>
		 *
		 * <p>What this replaced, and why it had to go: the old model kept a per-bead carry plus a bow wave,
		 * a shove clamp, a handover of ownership between two blades and a band-membership test, and it left
		 * the water it gathered ON the blade's leading line - which is 30 mm on the DRIVER's side of the
		 * modelled blade ({@code WATER_OFFSET_M}), so every sweep drew a string of drops across the wiper.
		 * Reported as "there is still residue on the wiper". Hide-and-put-back cannot produce that picture:
		 * a bead is either on the glass or in the ridge, and nothing in the ridge is drawn.</p>
		 */
		private void applyWipe(WiperConfig wiper, WiperState state, int wiperIndex, double elapsedSeconds) {
			if (!wiper.wiper) {
				return;
			}
			final boolean bandWipe = wiper.usesBandWipe(anchor);
			final double[][] bladeFrom = bandWipe ? wiper.bladeSegmentM(state.previousAngleDeg, anchor) : null;
			final double[][] bladeTo = bandWipe ? wiper.bladeSegmentM(state.angleDeg, anchor) : null;
			if (bandWipe && (bladeFrom == null || bladeTo == null)) {
				return;
			}
			final double pivotX = wiper.pivotU * anchor.widthM;
			final double pivotY = wiper.pivotV * anchor.heightM;
			final double[] push = pushDirectionM(wiper, state, bandWipe, bladeFrom, bladeTo, pivotX, pivotY);
			// THE WAY THE BLADE WAS TRAVELLING *BEFORE* THIS FRAME, which is the direction the release line is
			// thrown in. Captured here, before the running value is updated, and this is not a detail:
			// on the frame the blade TURNS AROUND, pushDirectionM already reports the NEW direction - the first
			// step of the return stroke - so a release thrown along the running value is thrown into exactly
			// the glass the returning blade is about to sweep, and is taken straight back. Reported as "the
			// offset is reversed, the return stroke can sweep it". Thrown the other way (the way the finished
			// stroke was going) it lands BEHIND the returning blade, which then sweeps away from it.
			final double releasePushX = state.lastPushX;
			final double releasePushY = state.lastPushY;
			if (push[0] * push[0] + push[1] * push[1] > 1.0E-12) {
				// Kept, because the put-back needs "the way the blade was travelling" at a moment when the
				// blade is by definition NOT travelling any more.
				state.lastPushX = push[0];
				state.lastPushY = push[1];
			}
			final double bladeAdvanceM = bandWipe
					? Math.hypot((bladeTo[0][0] + bladeTo[1][0] - bladeFrom[0][0] - bladeFrom[1][0]) / 2,
							(bladeTo[0][1] + bladeTo[1][1] - bladeFrom[0][1] - bladeFrom[1][1]) / 2)
					: Math.toRadians(Math.abs(state.angleDeg - state.previousAngleDeg)) * wiper.armM(anchor);
			final boolean bladeTravelling = (push[0] * push[0] + push[1] * push[1] > 1.0E-12)
					&& bladeAdvanceM > 1.0E-9;
			// HOW FAST THE BLADE IS MOVING, which is how fast the water leaves it (see RELEASE_FLING_M).
			// Held on the state because the release happens on a frame where the blade is NOT moving: the
			// speed wanted there is the one the stroke had a moment ago. Two quantities because the two
			// linkage families need different ones - a co-axial blade rotates, so every point of it moves at
			// omega * its own radius, while a parallelogram blade translates at one speed for all of it.
			if (bladeTravelling && elapsedSeconds > 1.0E-4) {
				state.omegaRadPerS = Math.toRadians(Math.abs(state.angleDeg - state.previousAngleDeg)) / elapsedSeconds;
				state.bladeSpeedMps = bladeAdvanceM / elapsedSeconds;
			}
			// THE REVERSAL is the sign of the wiper's own step, held across the animation's wrap: the sign
			// of a jump is not travel, so the last non-zero sign is what "which way is it going" means until
			// the blade genuinely starts moving the other way. This frame disagreeing with it is the far end
			// of the stroke - one of the two moments the ridge goes back on the glass.
			final double angleStep = normaliseSigned(state.angleDeg - state.previousAngleDeg) * wiper.sweepSign;
			final int stepSign = Math.abs(angleStep) > 1.0E-9 ? (angleStep > 0 ? 1 : -1) : 0;
			final boolean turnedAround = stepSign != 0 && stepSign != state.travelDirection;
			if (stepSign != 0) {
				state.travelDirection = stepSign;
			}
			// --- 1. CUT -----------------------------------------------------------------------------
			// ONLY WHILE THE BLADE IS MOVING. This is the rule advanceWiper already states - "a parked wiper
			// must not keep clearing glass, or the screen dries itself out with the stalk at 关" - and the
			// rewrite had broken it: a stopped blade went on eating every bead that reached its line and
			// laying it down again just past itself, so water running down the glass vanished AT the wiper and
			// reappeared below it. Reported as "the water comes out and is sucked straight back in". A parked
			// blade is not wiping, so it takes nothing. The trade is that a bead may be drawn sitting on a
			// parked blade's line, which is what a real parked wiper with water on it looks like.
			int cut = 0;
			if (bladeTravelling) {
				// THE LINE, SAMPLED WHERE IT WAS, WHERE IT IS, AND ONCE IN BETWEEN. Three line tests, no
				// polygon: "is this bead on the blade's line" asked at three angles is what stops the blade
				// from stepping over a bead between two repaints. The gap between neighbouring samples is half
				// the frame's advance, so a swept bead is at worst a quarter of the advance from the nearest
				// of them - 13.5 mm on the fastest SAF420 stroke at the blade's outer end, against a 23.5 mm
				// capture for a 6 mm bead. Measuring the bead against the AREA the line swept instead (what
				// this replaced) says the same thing with a quad, an inflation and a fade, and it is the region
				// the model no longer has any use for: the blade does not push water out of a swath, it
				// ADSORBS what its own line touches.
				final double[][] bladeBetween = bandWipe
						? wiper.bladeSegmentM((state.previousAngleDeg + state.angleDeg) / 2, anchor)
						: null;
				for (final Drop drop : drops) {
					if (!drop.visible) {
						continue;
					}
					if (!onBlade(wiper, state, bandWipe, bladeFrom, bladeBetween, bladeTo, pivotX, pivotY,
							drop.x * anchor.widthM, drop.y * anchor.heightM, drop.radiusM)) {
						continue;
					}
					drop.visible = false;
					drop.heldByWiper = wiperIndex;
					state.ridge.add(drop);
					cut++;
				}
			}
			pushedInWindow += cut;
			// --- 3. PUT BACK ------------------------------------------------------------------------
			final boolean bladeStopped = !state.moving || !bladeTravelling;
			if (bladeStopped || turnedAround) {
				final int put = putBackRidge(wiper, state, wiperIndex, bandWipe, bladeTo, pivotX, pivotY,
						releasePushX, releasePushY);
				// Reported once per stop, not once per frame: a dwell lasts 1-2 s and this runs on EVERY frame
				// of it. It has to run that often - water that creeps onto the parked blade is caught and laid
				// down again, which is what lets the runoff pass a parked wiper instead of piling against it -
				// but only the first of those frames is news.
				if (put > 0 && (turnedAround || (bladeStopped && !state.putBackReported))) {
					LOGGER.info("[MMTR-WSHLD] wipe {} wiper={} PUT BACK {} bead(s) along the blade's line - {}",
							anchor.name, wiperIndex, put,
							turnedAround ? "turnaround" : state.moving ? "blade stopped" : "wiper off or parked");
				}
			}
			state.putBackReported = bladeStopped;
			// One line per ~10 frames, while the blade is moving. Read cut/held against alive in the motion
			// line: cut is how much water the blade is taking off the glass, held is how much it owes it.
			if (state.moving && state.logCounter++ % 10 == 0) {
				LOGGER.info("[MMTR-WSHLD] wipe {} wiper={} angle={} span={}deg band={} cut={} held={} advance={} dir={}",
						anchor.name, wiperIndex, round(state.angleDeg),
						round(Math.abs(state.angleDeg - state.previousAngleDeg)), bandWipe, cut,
						state.ridge.size(), round(bladeAdvanceM), state.travelDirection);
			}
		}

		/**
		 * Whether the blade's LINE is on this point - the whole wipe test, in one place, and the whole of what
		 * the blade does to the water: it ADSORBS what its own line touches.
		 *
		 * <p>One statement, asked of the line where the blade was, where it is, and once in between (the
		 * caller passes those three angles): <b>the bead's disc and the blade's segment overlap</b>, i.e. the
		 * distance from the bead's centre to that segment is at most half the blade's width plus the bead's
		 * own radius. That is a physical statement rather than a tuned one - a 9 mm bead goes when the line is
		 * within 27 mm of its centre, because that is where the two shapes actually touch.</p>
		 *
		 * <p>There is deliberately no AREA in this test any more. It used to also ask whether the bead was
		 * inside the quad between the blade's previous and current positions, which is the region the segment
		 * swept; sampling the line instead covers the same ground (see the caller for the arithmetic) without
		 * a polygon, an inflation distance or a fade width to keep in agreement with each other.</p>
		 *
		 * <p>A pack with no modelled blade (the fan-only fallback) asks the same thing in bearings, because
		 * there the blade IS the ray at the wiper's own angle - and that ray's sweep is an angular interval,
		 * which is the one case where the swept set really is worth naming.</p>
		 */
		private boolean onBlade(WiperConfig wiper, WiperState state, boolean bandWipe, double[][] bladeFrom,
				double[][] bladeBetween, double[][] bladeTo, double pivotX, double pivotY, double pointX, double pointY,
				double radiusM) {
			final double captureM = 0.5 * wiper.bladeWidthM + radiusM;
			if (!bandWipe) {
				final double radialX = pointX - pivotX;
				final double radialY = pointY - pivotY;
				final double radius = Math.hypot(radialX, radialY);
				if (radius < 1.0E-6 || radius > wiper.armM(anchor) + radiusM) {
					return false;
				}
				final double bearingDeg = Math.toDegrees(Math.atan2(radialY, radialX));
				// The blade's width expressed at THIS radius: the same overlap statement as above.
				if (Math.abs(normaliseSigned(bearingDeg - state.angleDeg)) <= Math.toDegrees(captureM / radius)) {
					return true;
				}
				final double stepDeg = normaliseSigned(state.angleDeg - state.previousAngleDeg);
				final double fromPreviousDeg = normaliseSigned(bearingDeg - state.previousAngleDeg);
				return stepDeg >= 0 ? fromPreviousDeg >= 0 && fromPreviousDeg <= stepDeg
						: fromPreviousDeg <= 0 && fromPreviousDeg >= stepDeg;
			}
			if (distanceToSegmentM(pointX, pointY, bladeTo[0], bladeTo[1]) <= captureM) {
				return true;
			}
			if (distanceToSegmentM(pointX, pointY, bladeFrom[0], bladeFrom[1]) <= captureM) {
				return true;
			}
			return bladeBetween != null
					&& distanceToSegmentM(pointX, pointY, bladeBetween[0], bladeBetween[1]) <= captureM;
		}

		/**
		 * Lets the ridge go: <b>one line, along the blade, thrown clear of it in the direction the blade was
		 * travelling</b> - the release "pops out" beside the blade at the moment it turns around, and empties
		 * the ridge.
		 *
		 * <p>The line is the blade's own segment: the water the blade has scraped is lying along its leading
		 * edge, and that is where it goes back on the glass, spread evenly over the blade's length. What makes
		 * it stick is the OFFSET, and the offset is along the ROTATION - the way the blade was moving when it
		 * let go - not perpendicular to the blade:</p>
		 *
		 * <ul>
		 *   <li><b>It is the water's own momentum.</b> The blade was pushing this water ahead of itself, so
		 *       that is the direction it keeps going in.</li>
		 *   <li><b>It is what survives the turnaround.</b> At the end of a stroke the blade reverses, so water
		 *       thrown AHEAD of the old direction of travel is behind the new one and the returning blade
		 *       sweeps away from it. Offset sideways instead and the returning stroke runs straight over it
		 *       again.</li>
		 *   <li><b>The blade lies across its own travel anyway</b>, so an offset along the rotation is 93-95%
		 *       perpendicular to the blade's line on SAF420 (measured at park and at the turnaround), which is
		 *       what buys the clearance in the first place.</li>
		 * </ul>
		 *
		 * <p>The offset starts at the capture distance that took the water off the glass plus
		 * {@code RIDGE_CLEARANCE_M}, and is then pushed out in the same direction until the bead is clear of
		 * EVERY blade on the glass - not just this one: SAF420's two blades park 31.6 mm apart with their lines
		 * overlapping over 142 mm, so a line laid clear of one lands inside the other's capture and is gone
		 * before it is ever drawn.</p>
		 *
		 * <p>Each bead also keeps a decaying outward speed ({@code RELEASE_FLING_SPEED}), so the line does not
		 * merely appear beside the blade - it carries on the way the blade was throwing it.</p>
		 *
		 * @return how many beads were put back
		 */
		private int putBackRidge(WiperConfig wiper, WiperState state, int wiperIndex, boolean bandWipe,
				double[][] bladeTo, double pivotX, double pivotY, double releasePushX, double releasePushY) {
			final int count = state.ridge.size();
			if (count == 0) {
				return 0;
			}
			// The blade's own segment, or the ray at its angle for a fan-only pack (the line is then an arc).
			// Its direction is taken once, here: the per-bead work below is four Random draws, two multiply-adds
			// and (when a blade is not clear) one distance test - nothing that allocates and nothing per frame.
			final double spanX = bandWipe && bladeTo != null ? bladeTo[1][0] - bladeTo[0][0] : 0;
			final double spanY = bandWipe && bladeTo != null ? bladeTo[1][1] - bladeTo[0][1] : 0;
			final double spanM = Math.hypot(spanX, spanY);
			final boolean alongBlade = spanM > 1.0E-9;
			final double rayRadians = Math.toRadians(state.angleDeg);
			final double bladeDirX = alongBlade ? spanX / spanM : Math.cos(rayRadians);
			final double bladeDirY = alongBlade ? spanY / spanM : Math.sin(rayRadians);
			// What the sideways part of the throw is measured along: the blade itself, or - for a fan - the
			// direction across the ray, which is the same statement in bearings.
			final double sideDirX = alongBlade ? bladeDirX : -bladeDirY;
			final double sideDirY = alongBlade ? bladeDirY : bladeDirX;
			final double stepM = 0.5 * wiper.bladeWidthM + RIDGE_CLEARANCE_M;
			for (int index = 0; index < count; index++) {
				final Drop drop = state.ridge.get(index);
				// FOUR DRAWS FROM THE BEAD'S OWN RANDOM (see RELEASE_SCATTER): where along the blade it lands,
				// how far out it is thrown, how fast, and how much sideways - so the release is a splash
				// rather than a row stamped onto the glass.
				final double along = (index + drop.random.nextDouble()) / count;
				final double throwScale = 0.3 + RELEASE_SCATTER * drop.random.nextDouble();
				final double baseX;
				final double baseY;
				if (alongBlade) {
					baseX = bladeTo[0][0] + spanX * along;
					baseY = bladeTo[0][1] + spanY * along;
				} else {
					final double radius = wiper.armM(anchor) * (0.15 + 0.85 * along);
					baseX = pivotX + bladeDirX * radius;
					baseY = pivotY + bladeDirY * radius;
				}
				// OUT ALONG THE ROTATION - the way the stroke that has just ENDED was going (see the caller:
				// on the turnaround frame the running direction is already the new one) - far enough to clear
				// every blade on the glass.
				double offsetM = stepM + drop.radiusM + RELEASE_FLING_M * throwScale;
				double x = baseX + releasePushX * offsetM;
				double y = baseY + releasePushY * offsetM;
				for (int attempt = 0; attempt < 8 && !clearOfOtherBlades(x, y, drop.radiusM, wiperIndex); attempt++) {
					offsetM += stepM;
					x = baseX + releasePushX * offsetM;
					y = baseY + releasePushY * offsetM;
				}
				// Never past the frame: a bead whose centre is within its own radius of the edge is clipped by
				// drawBeads, so "thrown past the edge" is the same as "deleted".
				final double marginX = Math.min(0.5 * anchor.widthM, drop.radiusM);
				final double marginY = Math.min(0.5 * anchor.heightM, drop.radiusM);
				drop.x = Math.max(marginX, Math.min(anchor.widthM - marginX, x)) / anchor.widthM;
				drop.y = Math.max(marginY, Math.min(anchor.heightM - marginY, y)) / anchor.heightM;
				final double speed = releaseSpeedFor(wiper, state, baseX - pivotX, baseY - pivotY)
						* (0.85 + 0.3 * drop.random.nextDouble());
				final double side = (drop.random.nextDouble() - 0.5) * RELEASE_SIDE_MPS;
				drop.flingX = releasePushX * speed + sideDirX * side;
				drop.flingY = releasePushY * speed + sideDirY * side;
				// THE SAME FLICK FOR EVERYONE: the decay is fitted so this bead travels RELEASE_FLICK_M
				// (0.6x-1.4x of it) whatever speed the blade handed it, capped by the room it has to the frame
				// edge. See RELEASE_FLICK_M for why the distance and not the speed is the constant.
				final double roomM = roomToEdgeM(x, y, releasePushX, releasePushY)
						- drop.radiusM - RELEASE_EDGE_MARGIN_M;
				final double flickM = Math.max(0, Math.min(RELEASE_FLICK_M * (0.6 + 0.8 * drop.random.nextDouble()),
						roomM));
				drop.flingDecayS = speed > 1.0E-4 && flickM > 1.0E-4 ? 2 * flickM / speed : 0;
				if (drop.flingDecayS <= 0) {
					drop.flingX = 0;
					drop.flingY = 0;
				}
				drop.visible = true;
				drop.heldByWiper = 0;
				drop.lateralVelocity = 0;
				drop.surfaceSpeedMps = 0;
			}
			state.ridge.clear();
			return count;
		}

		/**
		 * The speed the blade was moving at a point {@code (offsetX, offsetY)} metres from the spindle - the
		 * speed the water there leaves at.
		 *
		 * <p>A rotating blade (one pivot) moves its points at {@code omega * r}, so the water off the outer end
		 * of SAF420's blade leaves at 2.7 m/s while the water off the inner end leaves at 0.9 m/s in SLOW: the
		 * release is a WHIP, not a row moving as one, and that difference is the whole reason the two ends of
		 * the line do not look stamped. A parallelogram blade (two pivots) translates, so every point of it
		 * moves at the same speed and that one speed is used.</p>
		 */
		private double releaseSpeedFor(WiperConfig wiper, WiperState state, double offsetX, double offsetY) {
			final boolean coaxial = Math.abs(wiper.pivot2U - wiper.pivotU) < 1.0E-9
					&& Math.abs(wiper.pivot2V - wiper.pivotV) < 1.0E-9;
			if (!coaxial) {
				return state.bladeSpeedMps > 1.0E-3 ? state.bladeSpeedMps : RELEASE_FLING_SPEED;
			}
			final double speed = state.omegaRadPerS * Math.hypot(offsetX, offsetY);
			// A wiper that has never moved has no measured rate yet; fall back to the taste value rather than
			// releasing the water dead still.
			return speed > 1.0E-3 ? speed : RELEASE_FLING_SPEED;
		}

		/** How far a point can travel along a unit direction before it reaches the frame edge, in metres. */
		private double roomToEdgeM(double pointX, double pointY, double dirX, double dirY) {
			double room = Double.MAX_VALUE;
			if (dirX > 1.0E-9) {
				room = Math.min(room, (anchor.widthM - pointX) / dirX);
			} else if (dirX < -1.0E-9) {
				room = Math.min(room, -pointX / dirX);
			}
			if (dirY > 1.0E-9) {
				room = Math.min(room, (anchor.heightM - pointY) / dirY);
			} else if (dirY < -1.0E-9) {
				room = Math.min(room, -pointY / dirY);
			}
			return room == Double.MAX_VALUE ? Math.max(anchor.widthM, anchor.heightM) : Math.max(0, room);
		}

		/**
		 * Whether a point is clear of every OTHER blade on this glass - close enough to a blade that the next
		 * pass would take the water straight back off the glass is not clear.
		 *
		 * <p>Two blades on one screen are two mechanisms over one water field, and at rest they can lie almost
		 * on top of each other: SAF420's park 31.6 mm apart, overlapping over 142 mm of their length, and a
		 * bead put back by one is then inside the other's capture on the same frame. This is the test that
		 * stops the put-back from feeding the neighbouring ridge instead of the glass.</p>
		 */
		private boolean clearOfOtherBlades(double pointX, double pointY, double radiusM, int ownWiperIndex) {
			for (int index = 0; index < config.wipers.length; index++) {
				final WiperConfig other = config.wipers[index];
				if (other.index == ownWiperIndex || !other.wiper) {
					continue;
				}
				final double[][] segment = other.bladeSegmentM(wiperStates[index].angleDeg, anchor);
				if (segment == null) {
					continue;
				}
				if (distanceToSegmentM(pointX, pointY, segment[0], segment[1])
						<= 0.5 * other.bladeWidthM + radiusM + RIDGE_CLEARANCE_M) {
					return false;
				}
			}
			return true;
		}




		/**
		 * How far the blade's <b>leading end</b> sits ahead of its <b>middle</b>, in degrees, in the frame
		 * of this frame's travel - the angular offset {@code applyWipe} measures a bead's position against.
		 *
		 * <p>The leading end is the end the blade is turning TOWARDS: of the two ends, the one whose
		 * bearing has moved farthest along {@code rotationSign} relative to the blade's middle. It is
		 * positive by construction (the middle is between the ends), and it is 0 whenever there is no
		 * modelled blade or the blade passes through its own pivot - both ends then lie on the ray the
		 * middle is on, so the offset vanishes and the caller's formula is unchanged.</p>
		 *
		 * <p>Why it matters is on {@code leadOffsetDeg} in {@code applyWipe}: on a blade modelled as a
		 * chord offset from the spindle, the strip between the leading edge and the pin is otherwise read
		 * as "not reached yet" and is never picked up by the blade that is cutting it.</p>
		 */
		private static double leadingEndOffsetDeg(@Nullable double[][] bladeTo, double pivotX, double pivotY, double midBearingRadians, double rotationSign) {
			if (bladeTo == null) {
				return 0;
			}
			final double midDeg = Math.toDegrees(midBearingRadians);
			final double first = normaliseSigned(Math.toDegrees(Math.atan2(bladeTo[0][1] - pivotY, bladeTo[0][0] - pivotX)) - midDeg) * rotationSign;
			final double second = normaliseSigned(Math.toDegrees(Math.atan2(bladeTo[1][1] - pivotY, bladeTo[1][0] - pivotX)) - midDeg) * rotationSign;
			return Math.max(first, second);
		}

		private double[] pushDirectionM(WiperConfig wiper, WiperState state, boolean bandWipe, double[][] bladeFrom, double[][] bladeTo, double pivotX, double pivotY) {
			if (bandWipe) {
				if (bladeFrom == null || bladeTo == null) {
					return new double[]{0, 0};
				}
				return unitOrZero((bladeTo[0][0] + bladeTo[1][0] - bladeFrom[0][0] - bladeFrom[1][0]) / 2,
						(bladeTo[0][1] + bladeTo[1][1] - bladeFrom[0][1] - bladeFrom[1][1]) / 2);
			}
			final double deltaDeg = (state.angleDeg - state.previousAngleDeg) * wiper.sweepSign;
			if (Math.abs(deltaDeg) < 1.0E-9) {
				return new double[]{0, 0};
			}
			// The push is the velocity direction of the point the ARM is bolted to - a real train arm is
			// pinned to the MIDDLE of the blade, so that point is the blade's own middle, and for a rigid
			// rotation about the spindle its velocity is exactly the tangent at its CURRENT bearing.
			//
			// This used to be a bearing formula, `(-sin(theta), cos(theta)) * sign`, with theta the turn
			// AWAY from park - i.e. it treated a rotation offset as an absolute bearing. That is only right
			// when parkAngleDeg is 0 (a hand-drawn blade whose face right edge already points along the park
			// direction, per the drawBlade contract) and is wrong by `park` degrees otherwise: SAF420's
			// fitted park is 180 deg, so every bead was shoved exactly BACKWARDS, under the blade and
			// towards the spindle - measured in game as "the drops move, but nowhere near the wiper".
			// A 1 deg finite difference of the blade's own middle agrees with this tangent to 0.0 deg on
			// both of SAF420's wipers (see _push_dir.py) and is unchanged for a park = 0 fixture.
			final double[] pin = {wiper.pinAU * anchor.widthM, wiper.pinAV * anchor.heightM};
			final double thetaRadians = Math.toRadians((state.angleDeg - wiper.parkAngleDeg) * wiper.sweepSign);
			final double[] rotatedPin = WindshieldConfig.rotateAbout(pivotX, pivotY, pin[0], pin[1],
					Math.cos(thetaRadians), Math.sin(thetaRadians));
			final double sign = deltaDeg > 0 ? 1 : -1;
			return unitOrZero(-(rotatedPin[1] - pivotY) * sign, (rotatedPin[0] - pivotX) * sign);
		}

		/** Normalises a direction, treating "too short to have one" as no push rather than as noise. */
		private static double[] unitOrZero(double dx, double dy) {
			final double length = Math.hypot(dx, dy);
			return length < 1.0E-6 ? new double[]{0, 0} : new double[]{dx / length, dy / length};
		}

		/**
		 * Distance from a point to a LINE SEGMENT, in metres - the whole of the wipe test's geometry (see
		 * onBlade). There is no polygon test next to it any more: the swept area the old band model measured
		 * beads against is not something this model has, because a blade that ADSORBS what its line touches
		 * never needs to know the shape of the ground it covered.
		 */
		private static double distanceToSegmentM(double px, double py, double[] a, double[] b) {
			final double dx = b[0] - a[0];
			final double dy = b[1] - a[1];
			final double lengthSquared = dx * dx + dy * dy;
			final double t = lengthSquared < 1.0E-12 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (py - a[1]) * dy) / lengthSquared));
			return Math.hypot(px - (a[0] + dx * t), py - (a[1] + dy * t));
		}




		/**
		 * The beads, drawn as GEOMETRY on the white texture - one small quad per bead, placed from its
		 * live position every frame.
		 *
		 * <p><b>Why geometry and not pixels.</b> The beads used to be painted into the panel's uploaded
		 * image. That image provably reached the screen exactly once per glass: a wall-clock test pattern
		 * drawn on the same canvas never moved, while the identical pipeline (same canvas, same texture,
		 * same upload) drove the dashboard's speed readout perfectly. So the upload path was fine and the
		 * rain layer specifically kept showing its first paint. A quad re-placed every frame cannot be
		 * stale, and the solid wiper blade is drawn the same way.</p>
		 *
		 * <p>All beads go into ONE scheduled callback rather than one per bead: a lambda each would be
		 * hundreds of allocations per pane per frame, and the positions are read at schedule time either
		 * way (the queue is drained in the same frame).</p>
		 */
		private void drawBeads(StoredMatrixTransformations carTransform, Plane plane) {
			final ObjectArrayList<double[]> quads = new ObjectArrayList<>();
			final ObjectArrayList<Integer> colours = new ObjectArrayList<>();
			final double lift = config.waterOffsetM;
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				// The drawn size: the bead's own radius, mildly scaled by its personality, clamped into the
				// configured visible range. Real rain on a windscreen is 1-6 mm, and at cab distance 6 mm is
				// already nearly one pixel of the uploaded canvas, so the floor is what makes the field
				// visible at all and the ceiling is what stops a fat bead hanging over the edge.
				final double radiusM = Math.min(config.maxVisibleRadiusM,
						Math.max(config.minVisibleRadiusM, drop.radiusM * (0.9 + 0.5 * drop.variation)));
				// CLIP TO THE GLASS. The field deliberately lets a bead drift to -0.05..1.05 so it can enter
				// and leave past the frame; those beads were being drawn outside the windscreen until this
				// test was added.
				final double halfWidthFraction = radiusM / Math.max(1.0E-6, anchor.widthM);
				final double halfHeightFraction = radiusM / Math.max(1.0E-6, anchor.heightM);
				if (drop.x < halfWidthFraction || drop.x > 1 - halfWidthFraction
						|| drop.y < halfHeightFraction || drop.y > 1 - halfHeightFraction) {
					continue;
				}
				// CENTRED: drop.x/drop.y are 0..1 across the GLASS, while pointAt() takes metres from the
				// glass's CENTRE. Without the -0.5 term every bead lands in the positive quadrant, which in
				// game is a small patch of rain in one corner of the screen.
				final double x = (drop.x - 0.5) * anchor.widthM;
				final double y = (drop.y - 0.5) * anchor.heightM;
				// NO TAIL: every bead is drawn as a plain square of its own radius. It used to be stretched
				// along the direction of travel into a streak, which read as a long bar rather than as a
				// droplet; a real drop's wake belongs to the glass (the water it leaves behind), not to the
				// geometry of the drop itself.
				final double low = y - radiusM;
				final double high = y + radiusM;
				final double left = x - radiusM;
				final double right = x + radiusM;
				// Winding bottom-left, bottom-right, top-right, top-left - the order IDrawing's four-corner
				// overload expects (see MmtrPanelQuad.drawFrame).
				final Vector c1 = plane.pointAt(left, low, lift);
				final Vector c2 = plane.pointAt(right, low, lift);
				final Vector c3 = plane.pointAt(right, high, lift);
				final Vector c4 = plane.pointAt(left, high, lift);
				quads.add(new double[]{
						c1.x(), c1.y(), c1.z(), c2.x(), c2.y(), c2.z(),
						c3.x(), c3.y(), c3.z(), c4.x(), c4.y(), c4.z()
				});
				// A bead that is on the glass is drawn at FULL opacity, always. There is no per-bead
				// formation value: rain either is on the glass or it is not, so the only thing that varies
				// between beads is their size.
				colours.add(snow ? argb(0xE6, 0xFFFFFF) : argb(0xE6, 0xE8F4FF));
			}
			if (quads.isEmpty()) {
				return;
			}
			MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.EXTERIOR_TRANSLUCENT_DOUBLE, (graphicsHolder, offset) -> {
				carTransform.transform(graphicsHolder, offset);
				for (int index = 0; index < quads.size(); index++) {
					final double[] quad = quads.get(index);
					IDrawing.drawTexture(
							graphicsHolder,
							(float) quad[0], (float) quad[1], (float) quad[2],
							(float) quad[3], (float) quad[4], (float) quad[5],
							(float) quad[6], (float) quad[7], (float) quad[8],
							(float) quad[9], (float) quad[10], (float) quad[11],
							new Vector3d(0, 0, 0),
							0.02F, 0.02F, 0.04F, 0.04F,
							Direction.UP, colours.get(index), GraphicsHolder.getDefaultLight()
					);
				}
				graphicsHolder.pop();
			});
		}

		private void draw(StoredMatrixTransformations carTransform) {
			final Plane plane = Plane.of(anchor, waterSide);
			if (plane == null) {
				return;
			}
			logPlaneOnce(anchor.name + "@" + anchor.car, plane, anchor.widthM, anchor.heightM, config.waterOffsetM);
			// The BEADS as geometry, not as pixels. See drawBeads: they used to be painted into the
			// uploaded image, and that image provably reached the screen exactly once.
			drawBeads(carTransform, plane);
			// ONE quad-family per wiper: a glass with two wipers draws two blades, each with its OWN pivot,
			// park direction and angle. A pair sharing one anchor is two mmtr_wipersweep_<cab>_<pane> /
			// _<pane>_2 fans, each with its own wiper_ part set - no mirroring rule to get wrong.
			//
			// config.<wiper>.wiper is the ANIMATION (does this glass get wiped at all);
			// config.<wiper>.drawBlade is the mod's OWN blade geometry. A model that carries a solid
			// wiper_<cab>_<pane>[_<n>] part sets drawBlade=false for THAT wiper so the two blades do not sit
			// on top of each other, while the glass still gets wiped and the (modelled) arm is what the
			// animation is meant to move.
			for (int index = 0; index < config.wipers.length; index++) {
				final WiperConfig wiper = config.wipers[index];
				if (!wiper.wiper || !wiper.drawBlade) {
					continue;
				}
				drawWiper(carTransform, plane, wiper, wiperStates[index]);
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
		private void drawWiper(StoredMatrixTransformations carTransform, Plane plane, WiperConfig wiper, WiperState wiperState) {
			final double armM = wiper.armM(anchor);
			// Distance from the face CENTRE down to the pivot, in metres: half the face height minus the
			// configured inset. Negative only if pivotV > 0.5, which is the author's business.
			final double pivotAlongRight = wiper.pivotU * anchor.widthM - anchor.widthM / 2;
			final double pivotAlongUp = wiper.pivotV * anchor.heightM - anchor.heightM / 2;
			drawOneWiper(carTransform, plane, wiper, wiperState, false, armM, pivotAlongRight, pivotAlongUp);
			if (wiper.dualWiper) {
				// Two blades on one anchor (a pair of wipers sharing the scuttle): the second mirrors the
				// first about the face's vertical centre line and sweeps the other way. A pair that needs
				// its own pivots wants one wiper each (mmtr_wipersweep_1_1 / _1_1_2) instead.
				drawOneWiper(carTransform, plane, wiper, wiperState, true, armM, -pivotAlongRight, pivotAlongUp);
			}
		}

		private void drawOneWiper(StoredMatrixTransformations carTransform, Plane plane, WiperConfig wiper, WiperState wiperState, boolean mirrored, double armM, double pivotAlongRight, double pivotAlongUp) {
			final double radians = Math.toRadians(wiperState.angleDeg);
			// Angle 0 = the face's right edge; +angle rotates UP (right -> up is counterclockwise seen
			// from +normal, because right = up x normal). sweepSign flips it if the model faces the
			// other way; flipping the anchor's normal is the other way to reverse it.
			final double sign = mirrored ? -wiper.sweepSign : wiper.sweepSign;
			final double alongRight = Math.cos(radians) * sign;
			final double alongUp = Math.sin(radians) * sign;

			drawWiperBar(carTransform, plane, pivotAlongRight, pivotAlongUp, alongRight, alongUp,
					armM, wiper.bladeWidthM, wiper.colour, WIPER_OFFSET_M, WHITE_TEXTURE);
			// The arm stalk: shorter, thinner, a shade lighter, sitting just under the blade.
			drawWiperBar(carTransform, plane, pivotAlongRight, pivotAlongUp, alongRight, alongUp,
					armM * 0.98, wiper.bladeWidthM * 0.55, wiper.armColour, WIPER_OFFSET_M - 0.002F, WHITE_TEXTURE);
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
		 * World down projected onto the glass plane, expressed in the plane's (right, up) basis, so the
		 * result is a UNIT VECTOR pointing downhill along the glass.
		 *
		 * <p><b>Two separate things went wrong here before, and they cancelled each other's symptoms
		 * just well enough to waste a lot of measurement:</b></p>
		 *
		 * <ol>
		 *   <li>The world's down was taken to be model-space {@code -Y}. It is {@code +Y} in this
		 *       module, because the anchors are authored y-up and {@code toModelSpace} mirrors y (see
		 *       the body of this method for the two proofs, from the seat and the dashboard anchors).</li>
		 *   <li>The correction for an anchor whose authored {@code up} points the wrong way negated BOTH
		 *       components of the pair whenever that {@code up} pointed downwards. That is not a basis
		 *       flip but a 180-degree rotation, and it pinned the answer to the canvas's -y, which is the
		 *       real up on one of BR101's two cabs. On BR101 V25 the water was driven INTO the top edge of
		 *       the glass, and the field then looked frozen: drops were driven uphill, recycled at the
		 *       frame, and driven again, while the diagnostics still reported a healthy population and a
		 *       0.7 m/s surface speed.</li>
		 * </ol>
		 *
		 * <p>Both are gone: down is the projection of the real down vector, taken in the plane's own
		 * basis, which needs no flip and cannot be turned over by the modeller's winding. That is what
		 * the BR101 pair of cabs demands - their anchors disagree about which way {@code up} points, and
		 * this one formula puts water down the glass on both.</p>
		 */
		private double[] panelDown() {
			final Plane plane = Plane.of(anchor, waterSide);
			if (plane == null) {
				return new double[]{0, -1};
			}
			// THE WORLD'S DOWN, PROJECTED INTO THE CANVAS - and nothing else. The canvas maps a world
			// vector w to (w . right, w . up), so the down vector is (down . right, down . up) by
			// definition; normalising it is the whole calculation.
			//
			// WHICH WORLD VECTOR "DOWN" IS, is the entire trap, and it is NOT (0,-1,0): THE SPACE THIS
			// MODULE WORKS IN HAS +Y POINTING DOWN. The anchors are authored y-UP and Plane.of converts
			// them with toModelSpace(x,-y,-z), which mirrors y. Two independent proofs that the anchors
			// are y-up, both readable straight out of the packed anchor file:
			//
			//   seat_1.up = [0, 1, 0]               a seat's up is the sky, authored +y;
			//   hud_1.y = 2.661 < glass y = 3.020   the dashboard is BELOW its windscreen.
			//
			// So real down is +Y here, and down is (right.y, up.y). This was (-right.y, -up.y) with a
			// "gravity must have a negative y" guard bolted on, and the guard was a tautology - the pair
			// is (-right.y, -up.y), whose y is -(right.y^2 + up.y^2), negative whatever the anchor says -
			// so it never flipped anything and simply sent the water the wrong way on every pane.
			// Measured per pane on the real BR101 anchors (sandbox/_down_direction_check.js), the
			// real-vertical component of the direction the field is sent:
			//
			//   windshield_1_1/1_2   (-right.y,-up.y) -> UP, i.e. the rain climbed   (right.y,up.y) -> DOWN
			//   windshield_2_1/2_2   (-right.y,-up.y) -> UP, i.e. the rain climbed   (right.y,up.y) -> DOWN
			//
			// THE PROJECTION IS WINDING-INDEPENDENT, which is what makes it safe to ship: it is the
			// projection of a fixed vector onto the glass plane expressed in that plane's own basis, so
			// it does not matter which way the modeller's `up` came out. It has to be, because the two
			// cabs disagree - BR101's cab 2 has up.y = +0.651 here (its `up` points at the COWL) against
			// cab 1's -0.651 - and both still have to run water down the glass.
			final double alongRight = plane.right.y();
			final double alongUp = plane.up.y();
			final double magnitude = Math.sqrt(alongRight * alongRight + alongUp * alongUp);
			if (magnitude < 1.0E-4) {
				// The glass is near-horizontal (or edge-on to gravity): nothing to project, so fall back to
				// straight down the panel, which is the direction that makes the drops run.
				return new double[]{0, -1};
			}
			// GRAVITY CANNOT POINT UP, AND THAT IS TRUE BY CONSTRUCTION RATHER THAN BY THE ANCHOR'S
			// WINDING: in this y-down space a downhill vector has a POSITIVE y, and this pair's y is
			// right.y^2 + up.y^2, i.e. always positive. The guard is kept as an executable statement of
			// the intent, not as a branch that can fire.
			final double downY = alongRight * plane.right.y() + alongUp * plane.up.y();
			final double sign = downY < 0 ? -1 : 1;
			return new double[]{sign * alongRight / magnitude, sign * alongUp / magnitude};
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
		/**
		 * The CURVED surface's sag GRID: how far the real face sits from this plane, sampled on a
		 * {@code Anchor.SAG_NX x Anchor.SAG_NY} lattice over the face's own extent. Empty for a flat face.
		 *
		 * <p>{@link #sagAt} turns it into the offset for a point in the (right, up) domain. Everything else
		 * in this file - the density field, the wipe test, the transport, the wiper kinematics - measures
		 * in that domain and is therefore UNAFFECTED; only the mapping from it to 3-D needs the
		 * correction, which is why it lives here and nowhere else.</p>
		 */
		private final double[] sagM;
		private final double sagUMinM;
		private final double sagUMaxM;
		private final double sagVMinM;
		private final double sagVMaxM;
		/** +1 when +normal points at the driver, -1 when it points away: see waterSideOf. */
		private final double waterSide;
		private final double yaw;
		private final double pitch;
		private final double roll;

		private Plane(Vector position, Vector normal, Vector up, Vector right, double[] sagM,
				double sagUMinM, double sagUMaxM, double sagVMinM, double sagVMaxM, double waterSide) {
			this.position = position;
			this.normal = normal;
			this.up = up;
			this.right = right;
			this.sagM = sagM;
			this.sagUMinM = sagUMinM;
			this.sagUMaxM = sagUMaxM;
			this.sagVMinM = sagVMinM;
			this.sagVMaxM = sagVMaxM;
			this.waterSide = waterSide;
			// Extract the Euler angles of M = rotateY(yaw) * rotateX(pitch) * rotateZ(roll) whose
			// columns are (right, up, normal). Reading them off the basis vectors by eye is wrong for
			// a tilted face -- up.y() is cos(pitch)*cos(roll), not -sin(pitch) -- so take the entries
			// straight out of the product: with
			//     row1 = [cp*sr, cp*cr, -sp]
			//     col2 = (sy*cp, -sp, cy*cp)
			// we get sin(pitch) = -normal.y() (NOT up.y()), yaw = atan2(normal.x(), normal.z()) and
			// roll = atan2(right.y(), up.y()). The old up.y()/up.x() solve was off by up to 90 deg
			// (notes/179 10, notes/200).
			this.pitch = Math.toDegrees(Math.asin(clamp(-normal.y())));
			this.yaw = Math.toDegrees(Math.atan2(normal.x(), normal.z()));
			this.roll = Math.toDegrees(Math.atan2(right.y(), up.y()));
		}

		@Nullable
		private static Plane of(Anchor anchor, double waterSide) {
			final Vector normal = toModelSpace(anchor.fileNormal).normalize();
			if (length(normal) < 1.0E-6) {
				return null;
			}
			final Vector up = orthonormalise(toModelSpace(anchor.fileUp).normalize(), normal);
			final Vector right = cross(up, normal).normalize();
			return new Plane(toModelSpace(anchor.filePosition), normal, up, right, anchor.sagGridM,
					anchor.sagUMinM, anchor.sagUMaxM, anchor.sagVMinM, anchor.sagVMaxM, waterSide);
		}

		/**
		 * How far the modelled surface sits off this plane at a point of the (right, up) domain, in metres
		 * along the normal. Bilinear over the grid, holding the edge value outside it.
		 *
		 * <p>The grid's own bounds are used, NOT {@code +-widthM/2} and {@code +-heightM/2}: the anchor
		 * origin is the group's vertex MEAN while widthM/heightM are its EXTENT, and the two only coincide
		 * while the group is a plain quad or a box. On BR101 V25's subdivided glass the mean sits 41.5 mm
		 * off the extent centre, which is exactly the error a {@code +-heightM/2} assumption would fold
		 * into every sample.</p>
		 */
		private double sagAt(double alongRight, double alongUp) {
			final int nx = Anchor.SAG_NX;
			final int ny = Anchor.SAG_NY;
			final double uSpan = sagUMaxM - sagUMinM;
			final double vSpan = sagVMaxM - sagVMinM;
			if (sagM.length != nx * ny || uSpan <= 1.0E-9 || vSpan <= 1.0E-9) {
				return 0;
			}
			// THE SAMPLES SIT ON THE NODES: the packager takes each node's value from the surface's own
			// vertices (inverse-distance weighted), so sample (0,0) is the face's corner and sample
			// (n-1,n-1) is the opposite one. Reading the grid as bin CENTRES instead puts every query half
			// a step off - measured, that alone left an 18.2 mm residual that no amount of resolution
			// improved, because it is a convention error and not a sampling one.
			final double fx = (alongRight - sagUMinM) / uSpan * (nx - 1);
			final double fy = (alongUp - sagVMinM) / vSpan * (ny - 1);
			final double cx = Math.max(0, Math.min(nx - 1, fx));
			final double cy = Math.max(0, Math.min(ny - 1, fy));
			final int x0 = (int) cx;
			final int y0 = (int) cy;
			final int x1 = Math.min(nx - 1, x0 + 1);
			final int y1 = Math.min(ny - 1, y0 + 1);
			final double tx = cx - x0;
			final double ty = cy - y0;
			final double bottom = sagM[y0 * nx + x0] + (sagM[y0 * nx + x1] - sagM[y0 * nx + x0]) * tx;
			final double top = sagM[y1 * nx + x0] + (sagM[y1 * nx + x1] - sagM[y1 * nx + x0]) * tx;
			return bottom + (top - bottom) * ty;
		}

		/** A point in the plane, {@code alongRight}/{@code alongUp} in metres from the face centre. */
		private Vector pointAt(double alongRight, double alongUp) {
			return pointAt(alongRight, alongUp, 0);
		}

		/** The same, lifted off the glass along its own normal - the single place that offset is applied. */
		private Vector pointAt(double alongRight, double alongUp, double liftM) {
			// THE SAG IS ADDED HERE AND NOWHERE ELSE. A point on the (right, up) domain lands on the
			// modelled surface only if the surface's own departure from this plane is added along the
			// normal; without it a curved windscreen's rain is drawn on the plane of whichever patch the
			// packager picked, measured at up to 112 mm off the glass.
			// THE WATER LAYER GOES ON THE DRIVER'S SIDE, WHATEVER THE MESH WINDING SAYS.
			//
			// waterOffsetM is a distance off the glass and its sign selects the side (negative = the
			// driver's side, positive = the weather side - the config's own contract). Which way along the
			// NORMAL that is used to be the modeller's winding, and that is a trap: BR101 V25 re-exported
			// the rain surface with the opposite winding, every pane's normal came out reversed (measured
			// dot(old, new) = -0.987 on all four) and the water that had been on the driver's side flipped
			// to the OUTSIDE. The cab can only see the driver's side, because the pane rasterises first
			// and writes depth (notes/205), so the windscreen went blank from inside.
			//
			// waterSide is +1 when +normal points at the driver and -1 when it points away, decided
			// geometrically from the cab's dashboard - whose normal points at the driver BY CONTRACT - so
			// the side no longer depends on the winding at all.
			final double offset = (liftM <= 0 ? waterSide : -waterSide) * Math.abs(liftM)
					+ sagAt(alongRight, alongUp);
			return new Vector(
					position.x() + right.x() * alongRight + up.x() * alongUp + normal.x() * offset,
					position.y() + right.y() * alongRight + up.y() * alongUp + normal.y() * offset,
					position.z() + right.z() * alongRight + up.z() * alongUp + normal.z() * offset
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
		/**
		 * Fixed personality (0..1): how big this bead is DRAWN, so the field is not all one size. It used
		 * to also scale the bead's own pinning threshold ("a fat bead runs sooner"), which the density
		 * model removed - whether water runs is a property of the patch, not of the bead.
		 */
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
		/**
		 * Whether this bead is ON the glass right now. There is no third state and no "how formed is it"
		 * fade: rain arrives the instant it lands, and a wiped bead is gone the instant the blade passes.
		 *
		 * <p>This replaces an {@code ink} 0..1 "formation" value that grew in after a wipe at 0.8/s. That
		 * fade was invented for the old IMAGE repaint, where a bead appearing or vanishing between two
		 * uploaded frames read as a pop - but the beads are now drawn as geometry every frame, so there is
		 * no inter-frame gap left to hide, and a raindrop that fades into existence is not what rain does.
		 * Removing it also removes a whole class of confusion: "how fast does the glass refill" was never
		 * the spawn rate (the population stayed full) and never the wipe (which only hid beads).</p>
		 */
		private boolean visible;
		/**
		 * WHICH wiper is HOLDING this bead (0 = nobody): the bead has been taken off the glass by that
		 * blade's line and is in that wiper's ridge.
		 *
		 * <p>This is the entire carry state, one field, and it exists so that the rest of the field knows
		 * the bead is spoken for: {@code spawnForWeather} and {@code pinchOff} both hand out beads that are
		 * merely invisible, and without this they would take the wiper's water and drop it somewhere else on
		 * the glass at a random size. A held bead is neither on the glass nor available to the rain.</p>
		 *
		 * <p>Nothing else is per-bead about the carry any more: what the blade is holding is the wiper's
		 * ridge (see {@code WiperState.ridge}), which is put back on the glass in one line when the blade
		 * stops or turns around.</p>
		 */
		private int heldByWiper;
		/**
		 * The water's own momentum, in METRES per second, left over from being thrown off a blade - see
		 * {@code RELEASE_FLING_M}. It decays to nothing over {@code RELEASE_FLING_DECAY_S}, and it is applied
		 * on top of whatever the runoff is doing, so it is not a second motion model: it is the shove the
		 * blade gave the water, still running out.
		 */
		private double flingX;
		private double flingY;
		/**
		 * How long THIS bead's flick takes to die, in seconds - fitted at release so that the bead travels
		 * {@code RELEASE_FLICK_M} whatever speed the blade gave it ({@code tau = 2 * distance / speed}), and so
		 * that a bead released near the frame edge stops short of it. See {@code RELEASE_FLICK_M}.
		 */
		private double flingDecayS = RELEASE_FLING_DECAY_S;

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

	/**
	 * ONE wiper on a glass: its blade, its pivot, its stroke, its speed and how it is driven.
	 *
	 * <p>A pack that gives a glass ONE wiper writes these fields FLAT in the glass's own block, and this
	 * object is then built straight from that block - which is what every pack made before multi-wiper
	 * existed contains, so those keep working field for field. A pack that gives a glass SEVERAL wipers
	 * writes one of these per entry of {@code "wipers"}, each carrying its own {@code wiperIndex}. Both
	 * paths produce the same object, so nothing downstream needs to know which one it came from.</p>
	 *
	 * <p>Everything here belongs to ONE blade. The weather and the water do NOT: there is one bead field
	 * per glass, and every wiper on it clears that same rain (see {@link WindshieldConfig}).</p>
	 */
	private static final class WiperConfig {

		/** Which wiper of the glass this is: its modelled parts are {@code wiper_<cab>_<pane>_<index>}. */
		private final int index;
		/** Whether this wiper runs at all (false = modelled, but never driven). */
		private final boolean wiper;
		/**
		 * Whether the CLIENT draws its own blade. False when the model carries a solid wiper part
		 * ({@code wiper_<cab>_<pane>[_<n>]}): the glass must still be wiped (that is {@link #wiper}), but
		 * the drawn blade would sit on top of the modelled one. The packager sets this from the model, so a
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
		/**
		 * Where the links are bolted to the BLADE. A real wiper pins its arm to the blade's MIDDLE and its
		 * rod near an end, so these are generally NOT the blade's ends - and using an end instead scales the
		 * blade's translation by |end-P1|/|pin-P1|. They default to the blade's ends, which is what a pack
		 * written before the pins existed contains and is exactly right for a single-axis or end-pinned
		 * mechanism.
		 */
		private final double pinAU;
		private final double pinAV;
		private final double pinBU;
		private final double pinBV;

		/**
		 * @param fallbackIndex the wiper's index when the block does not state one - the array position for
		 *                      a {@code "wipers"} entry, 1 for the flat single-wiper block
		 */
		private WiperConfig(JsonObject json, int fallbackIndex) {
			index = Math.max(1, (int) WindshieldConfig.getDouble(json, "wiperIndex", fallbackIndex));
			wiper = WindshieldConfig.getBoolean(json, "wiper", true);
			drawBlade = WindshieldConfig.getBoolean(json, "drawBlade", true);
			armMConfigured = WindshieldConfig.getDouble(json, "armM", 0);
			// The park direction is modelled, so 0 is the correct default: the blade lies along the
			// face's own "right" edge. These two only nudge it off that line.
			parkAngleDeg = WindshieldConfig.getDouble(json, "parkAngleDeg", 0);
			sweepDeg = Math.max(5, WindshieldConfig.getDouble(json, "sweepDeg", 88));
			sweepSign = WindshieldConfig.getDouble(json, "sweepSign", 1) < 0 ? -1 : 1;
			periodS = Math.max(0.2, WindshieldConfig.getDouble(json, "periodS", 1.6));
			bladeWidthM = Math.max(0.005, WindshieldConfig.getDouble(json, "bladeWidthM", 0.035));
			colour = WindshieldConfig.parseColor(WindshieldConfig.getString(json, "colour", "#FF14181C"), 0xFF14181C);
			armColour = WindshieldConfig.parseColor(WindshieldConfig.getString(json, "armColour", "#FF3A4148"), 0xFF3A4148);
			dualWiper = WindshieldConfig.getBoolean(json, "dualWiper", false);
			pivotU = WindshieldConfig.getDouble(json, "pivotU", 0.5);
			pivotV = WindshieldConfig.getDouble(json, "pivotV", 0.0);
			hasBlade = json.has("bladeAU") && json.has("bladeBU");
			bladeAU = WindshieldConfig.getDouble(json, "bladeAU", 0);
			bladeAV = WindshieldConfig.getDouble(json, "bladeAV", 0);
			bladeBU = WindshieldConfig.getDouble(json, "bladeBU", 0);
			bladeBV = WindshieldConfig.getDouble(json, "bladeBV", 0);
			// No second pivot means the two ends rotate about the SAME point, which is exactly a
			// single-axis wiper - so a missing pivot2 is not a special case anywhere in the maths.
			pivot2U = WindshieldConfig.getDouble(json, "pivot2U", pivotU);
			pivot2V = WindshieldConfig.getDouble(json, "pivot2V", pivotV);
			pinAU = WindshieldConfig.getDouble(json, "pinAU", bladeAU);
			pinAV = WindshieldConfig.getDouble(json, "pinAV", bladeAV);
			pinBU = WindshieldConfig.getDouble(json, "pinBU", bladeBU);
			pinBV = WindshieldConfig.getDouble(json, "pinBV", bladeBV);
		}

		/**
		 * The wiper's reach. Defaults to half the modelled quad's SHORTER side, which is exactly the
		 * largest arm that still fits inside the plane the author drew - so a correctly sized wiper face
		 * needs no {@code armM} at all.
		 */
		private double armM(Anchor anchor) {
			return armMConfigured > 0 ? armMConfigured : Math.max(0.05, Math.min(anchor.widthM, anchor.heightM) / 2);
		}

		/** The follower's pin at a given crank pin, for the part transform. See bladeSegmentM for the maths. */
		@Nullable
		double[] followerEndFor(double[] crankPin, double[] pivot, double spanM, double followerM, int mode) {
			return WindshieldConfig.followerEnd(crankPin, pivot, spanM, followerM, mode);
		}

		/** The assembly mode, read off the PARK configuration (its crank pin is m0, not br0). */
		int assemblyMode(double[] parkCrankPin, double[] pivot, double[] parkFollowerPin, double spanM, double followerM) {
			return WindshieldConfig.followMode(null, pivot, parkCrankPin, parkFollowerPin, spanM, followerM);
		}

		/**
		 * Whether this wiper has a modelled BLADE to test against, as opposed to only a fan (the region the
		 * arm sweeps): a blade gives a line segment, a fan-only pack gives a ray at the wiper's own angle.
		 * See {@code onBlade}, which asks the same two questions - is the line on this bead, and did it
		 * cross it since the last repaint - of whichever of the two the pack provides.
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
		private boolean usesBandWipe(Anchor anchor) {
			// GENERAL RULE (2026-09-24). The BAND test is the one that assumes nothing about the linkage: it
			// is the area between the blade's previous and current position - exact for a translating blade,
			// chord-exact over one repaint for a rotating one - and it is the path the windrow/carry
			// behaviour was built and measured on (notes 204-209), i.e. what puts a water line on the blade.
			// It covers a blade that runs THROUGH the pivot (a radial bus blade) as well: the two blade
			// positions share the pivot, so the quad between them IS the angular wedge.
			//
			// The SECTOR test is therefore only the fallback for a pack that ships a fan but no blade
			// geometry - nothing to measure a band from. The old rule here ("band only with two pivots, i.e.
			// a parallelogram") sent every car-style single-pivot wiper down that wedge path, which had
			// never run in game until SAF420 and turned out to carry three separate latent bugs (negative
			// swept extent, bearing side, and a push direction that assumed parkAngleDeg = 0). Measured
			// symptom on the wedge path: "no water line on the blade, and the whole region's water moves
			// along with it".
			return hasBlade && bladeLengthM(anchor) >= MIN_BLADE_LENGTH_M;
		}

		/** The modelled blade's length in metres - 0 when the pack carries no usable blade segment. */
		private double bladeLengthM(Anchor anchor) {
			return Math.hypot((bladeBU - bladeAU) * anchor.widthM, (bladeBV - bladeAV) * anchor.heightM);
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
		private double[][] bladeSegmentM(double absoluteAngleDeg, Anchor anchor) {
			if (!hasBlade) {
				return null;
			}
			// parkAngleDeg is the direction the blade is MODELLED at, so it is the ORIGIN of the rotation:
			// the linkage is driven by "how far from park", not by an absolute bearing. Without this the
			// blade would be rotated by the phase angle plus the park bearing - i.e. 110 degrees out.
			final double theta = (absoluteAngleDeg - parkAngleDeg) * sweepSign;
			final double widthM = anchor.widthM;
			final double heightM = anchor.heightM;
			final double[] p1 = {pivotU * widthM, pivotV * heightM};
			final double[] p2 = {pivot2U * widthM, pivot2V * heightM};
			// The PINS are where the links are bolted to the BLADE - a real arm is pinned to the blade's
			// middle, so these are not the blade's ends. A pack without them falls back to the ends, which
			// is what every fixture models and what the parallelogram case needs anyway.
			final double[] m0 = {pinAU * widthM, pinAV * heightM};
			final double[] br0 = {pinBU * widthM, pinBV * heightM};
			final double[] a0 = {bladeAU * widthM, bladeAV * heightM};
			final double[] b0 = {bladeBU * widthM, bladeBV * heightM};
			final double radians = Math.toRadians(theta);
			final double cos = Math.cos(radians);
			final double sin = Math.sin(radians);
			final boolean coaxial = Math.abs(p2[0] - p1[0]) < 1.0E-9 && Math.abs(p2[1] - p1[1]) < 1.0E-9;
			final double[] m = WindshieldConfig.rotateAbout(p1[0], p1[1], m0[0], m0[1], cos, sin);
			// ONE pivot: the blade rides the arm, so a rigid rotation about the spindle is exact - and the
			// loop closure degenerates to exactly this, because a pin pair turning about a common centre
			// turns by the crank angle.
			if (coaxial) {
				return new double[][]{WindshieldConfig.rotateAbout(p1[0], p1[1], a0[0], a0[1], cos, sin), WindshieldConfig.rotateAbout(p2[0], p2[1], b0[0], b0[1], cos, sin)};
			}
			// TWO pivots: the FOUR-BAR LOOP CLOSURE. The blade is RIGID, so the distance between its two pins
			// cannot change - that is what fixes the follower's angle:
			//   M(theta)  = P1 + R(theta)(M0 - P1)                                the crank (driven)
			//   Br(theta) = circle(P2, |Br0-P2|) n circle(M(theta), |Br0-M0|)      the follower (SOLVED)
			// "Both pins turn by theta" - what an ideal parallelogram does - contradicts the rigidity as soon
			// as the two link vectors differ (measured: 4.5 mm on the fixture). NOTE the argument order of
			// followerEnd: its first radius is the one about its first point.
			final double spanM = Math.hypot(br0[0] - m0[0], br0[1] - m0[1]);
			final double followerM = Math.hypot(br0[0] - p2[0], br0[1] - p2[1]);
			final double[] br = WindshieldConfig.followerEnd(m, p2, spanM, followerM, WindshieldConfig.followMode(p1, p2, m0, br0, spanM, followerM));
			if (br == null) {
				// The linkage cannot reach that angle: keep the blade drawn and moving rather than making it
				// vanish. The offline verifier is what reports the real problem.
				return new double[][]{WindshieldConfig.rotateAbout(p1[0], p1[1], a0[0], a0[1], cos, sin), WindshieldConfig.rotateAbout(p2[0], p2[1], b0[0], b0[1], cos, sin)};
			}
			// The blade is the rigid body through its two pins, so its ends follow from the rigid motion that
			// takes the park pin pair onto the current one. The two distances agree BY CONSTRUCTION now.
			final double turn = Math.atan2(br[1] - m[1], br[0] - m[0]) - Math.atan2(br0[1] - m0[1], br0[0] - m0[0]);
			final double turnCos = Math.cos(turn);
			final double turnSin = Math.sin(turn);
			return new double[][]{
					WindshieldConfig.carried(m, m0, a0, turnCos, turnSin),
					WindshieldConfig.carried(m, m0, b0, turnCos, turnSin)
			};
		}
	}

	private static final class WindshieldConfig {

		private static final Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, WindshieldConfig>> CACHE = new Object2ObjectOpenHashMap<>();
		private static final WindshieldConfig DEFAULT = new WindshieldConfig(new JsonObject());

		private final int raindrops;
		private final double fallMps;
		private final double maxStreakM;
		/**
		 * <b>The wipers on this glass, in ascending wiper order</b> (index i = the wiper whose modelled
		 * parts are named {@code wiper_<cab>_<pane>_<i+1>}).
		 *
		 * <p>A glass may carry MORE THAN ONE: there is exactly one bead field per glass - one rain, one
		 * puddle - and each wiper on it has its own pivot, park direction, stroke, speed and mechanism, all
		 * clearing that same water. Almost every model has one, and then this holds a single entry built
		 * from the flat fields a pack has always written, so a single-wiper model behaves exactly as it did
		 * before. See docs §1.4②/④.</p>
		 */
		private final WiperConfig[] wipers;
		private final boolean snow;
		/**
		 * How far off the modelled face the precipitation layer sits, in metres along the glass normal
		 * when positive. See {@link #WATER_OFFSET_M} for why the side matters: whichever side the modelled
		 * pane is NOT on is the side whose view survives the pane's depth write.
		 */
		private final double waterOffsetM;
		/**
		 * Draw a wall-clock-driven test pattern on the glass (a sweeping bar and a falling disc). Off in
		 * every real pack; it exists so "the rain does not move" can be split into "the droplet field does
		 * not move" versus "the image never reaches the screen", which look identical on the glass.
		 */
		/**
		 * Smallest radius a bead is DRAWN at, in metres. The physical range is smaller than a pixel at cab
		 * distance, so this floor is what makes the rain visible at all - and lowering it is how the drops
		 * are made finer. 0.004 m is about 0.45 px on a 128 px canvas over a 1.15 m glass.
		 */
		private final double minVisibleRadiusM;
		/**
		 * Largest radius a bead is DRAWN at, in metres. Without a ceiling the widest beads are big enough
		 * that half of one sticks out past the windscreen edge before the clip above removes it.
		 */
		private final double maxVisibleRadiusM;
		/**
		 * Edge of one density probe cell, in metres. The bead's neighbourhood is the 3x3 block of these,
		 * so {@code 3 * densityCellM} is the width of the patch a runoff decision is made on: 0.05 m gives
		 * a 15 cm square, which is about a hand's width of windscreen - the scale at which "water gathers
		 * here" is something a driver can see.
		 */
		private final double densityCellM;
		/**
		 * Beads per square metre past which a patch of glass stops holding its water and starts to run.
		 *
		 * <p>THE parameter of the runoff regime, and the replacement for the old per-bead
		 * {@code staticThresholdM}. Read against the probe: at {@code densityCellM = 0.05} one bead alone
		 * in a patch reads 44/m2, two read 89, three read 133 - so a threshold of 130 means "three beads
		 * within a 15 cm square". On BR101 (1.1516 x 1.06 m, 140 beads) the whole glass averages 115/m2 in
		 * a downpour, so the downpour's denser patches run and its average patch does not; at half
		 * intensity the mean is 58/m2 and almost nothing does. That is the gradient wanted, and it is
		 * expressed in a unit that carries to any other windscreen.</p>
		 */
		private final double densityThresholdPerM2;
		/**
		 * Downhill speed once a patch of glass has let go of its water, in m/s - at FULL crowding. A patch
		 * right at the threshold runs at {@code RUNOFF_MARGINAL_FACTOR} of this, and the windrow the blade
		 * leaves runs at all of it (see {@code RUNOFF_MARGINAL_FACTOR} for the measurements).
		 *
		 * <p>0.45 m/s crosses BR101's 1.06 m glass in about 2.5 s, which is the visible-but-not-frantic
		 * speed a raindrop on a windscreen actually has. It is a game number, not a measurement: the real
		 * figure for a rivulet on glass is nearer 0.05 m/s, which at cab distance is a centimetre per
		 * second and reads as "the rain still does not move".</p>
		 */
		private final double runoffMps;

		// --- droplet physics (all optional; the defaults are tuned for a raked main windscreen) -------
		/** Ceiling on the upward creep the airflow produces at line speed (m/s). */
		private final double creepMps;
		/** Random ride-quality jitter applied to every bead (m/s). */
		private final double jitterMps;
		private final double minBeadRadiusM;
		private final double maxBeadRadiusM;
		/** How fast a bead swells while it sits in the rain (m/s). */
		private final double growthMps;
		/**
		 * What FRACTION of the glass's bead capacity lands per second, at full intensity. Expressed as a
		 * fraction rather than a bead count so it means the same thing on any model - see
		 * {@code spawnRatePerSecond} for why the old absolute count was both too slow and, above 4/s,
		 * silently ignored.
		 *
		 * <p>BACK TO 0.25 (2026-09-24). It was raised to 0.6 earlier the same day, to out-supply a wiper that
		 * had turned into a PUMP: that model pushed the water it took to the bottom edge, where it ran off, so
		 * the glass settled at 66-92 visible beads out of a cap of 220 and the rain looked broken. The wiper
		 * no longer does that - it holds what it takes and gives ALL of it back (measured over a full cycle:
		 * 967 beads taken, 967 released, nothing lost) - so the extra supply has nothing left to compensate
		 * for, and leaving it in just makes the glass re-wet twice as fast as the pack asks for. Reported as
		 * "the rain looks much heavier than it should".</p>
		 */
		private final double spawnPopulationPerSecond;
		/**
		 * A bead STARTING size ceiling, in metres, and nothing else - it is <b>no longer the runoff gate</b>
		 * (that is {@code densityThresholdPerM2}). It survives because it still says something true and
		 * useful: a bead that lands on the glass arrives small enough to be held by surface tension, and
		 * grows from there. Writing the arrival cap as {@code min(maxBeadRadius, threshold)} in
		 * {@code newBeadRadiusM} means raising it in a config can never make new beads spawn already
		 * running.
		 *
		 * <p>It is also the reference size for shedding: a bead past {@code 1.4 x} this is heavy enough to
		 * be worth pinching a child off. See {@code advanceDrop}.</p>
		 */
		private final double staticThresholdM;
		/** Bead separation below which two beads merge (metres); also the spatial hash's cell size. */
		private final double mergeDistanceM;

		private WindshieldConfig(JsonObject json) {
			raindrops = clampInt(getDouble(json, "raindrops", 90), 0, 400);
			fallMps = Math.max(0.01, getDouble(json, "fallMps", 0.55));
			maxStreakM = Math.max(0, getDouble(json, "maxStreakM", 0.10));
			// THE WIPERS. "wipers" is written by the packager ONLY when a glass carries two or more; without
			// it the flat fields ARE the single wiper, which is what every pack made before multi-wiper
			// contains - so an old pack parses to the same one-entry array it always behaved as, field for
			// field. Each array entry states its own wiperIndex, so which entry drives which
			// wiper_<cab>_<pane>_<n> part never depends on array position alone.
			final JsonArray wiperArray = json.has("wipers") && json.get("wipers").isJsonArray() ? json.getAsJsonArray("wipers") : null;
			if (wiperArray != null && wiperArray.size() > 0) {
				final WiperConfig[] parsed = new WiperConfig[wiperArray.size()];
				for (int index = 0; index < parsed.length; index++) {
					final JsonElement element = wiperArray.get(index);
					parsed[index] = new WiperConfig(element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject(), index + 1);
				}
				wipers = parsed;
			} else {
				wipers = new WiperConfig[]{new WiperConfig(json, 1)};
			}
			snow = getBoolean(json, "snow", false);
			// A NEGATIVE value puts the water on the driver's side of the glass, which is what makes it
			// visible from the cab (see WATER_OFFSET_M: the modelled pane is rasterised first and writes
			// depth, so anything behind it is rejected). The bound keeps a typo from throwing the layer
			// across the car.
			waterOffsetM = Math.max(-0.5, Math.min(0.5, getDouble(json, "waterOffsetM", WATER_OFFSET_M)));
			minVisibleRadiusM = Math.max(0.0005, getDouble(json, "minVisibleRadiusM", 0.004));
			maxVisibleRadiusM = Math.max(minVisibleRadiusM, getDouble(json, "maxVisibleRadiusM", 0.009));
			creepMps = Math.max(0, getDouble(json, "creepMps", 0.09));
			jitterMps = Math.max(0, getDouble(json, "jitterMps", 0.008));
			minBeadRadiusM = Math.max(0.0005, getDouble(json, "minBeadRadiusM", 0.0035));
			maxBeadRadiusM = Math.max(minBeadRadiusM * 1.2, getDouble(json, "maxBeadRadiusM", 0.014));
			// Crossing time = (staticThreshold x band - minBeadRadiusM) / growthMps. At 4.5 mm base and a
			// 1.1x..1.8x band that is 4.95..8.1 mm, so at 0.8 mm/s a bead stays PINNED for about 2.4 s to
			// 7.7 s. See the field docs on staticThresholdM: these two numbers are only meaningful together,
			// and the pair was calibrated with the offline harness (mmtr/tools/wiper-preview).
			growthMps = Math.max(0, getDouble(json, "growthMps", 0.0008));
			// How much of the glass re-wets per second in a downpour. 0.25 fills a stripped screen in about
			// four seconds; the old absolute value of 2 beads/s took over a minute and was reported as too
			// slow in game.
			spawnPopulationPerSecond = Math.max(0, getDouble(json, "spawnPopulationPerSecond", 0.25));
			staticThresholdM = Math.max(0.0005, getDouble(json, "staticThresholdM", 0.0045));
			mergeDistanceM = Math.max(0.001, getDouble(json, "mergeDistanceM", 0.012));
			densityCellM = Math.max(0.005, getDouble(json, "densityCellM", 0.05));
			densityThresholdPerM2 = Math.max(1, getDouble(json, "densityThresholdPerM2", 130));
			runoffMps = Math.max(0, getDouble(json, "runoffMps", 0.45));
		}

		/** Whether ANY wiper on this glass is switched on. False = rain only (e.g. a rear screen). */
		private boolean hasWiper() {
			for (final WiperConfig wiper : wipers) {
				if (wiper.wiper) {
					return true;
				}
			}
			return false;
		}

		/**
		 * The wiper whose modelled parts are named {@code wiper_<cab>_<pane>_<index>}, or null when this
		 * glass has no such wiper.
		 *
		 * <p>Matched on the wiper's OWN declared index rather than on its position in the array, so a model
		 * that numbers its wipers 1 and 3 (or ships a config with only the second one) still drives the
		 * right blade - a silent off-by-one here would rotate the wrong part.</p>
		 */
		@Nullable
		private WiperConfig wiperAt(int index) {
			for (final WiperConfig wiper : wipers) {
				if (wiper.index == index) {
					return wiper;
				}
			}
			return null;
		}

		/** A point of the blade under the rigid motion that puts the park pin M0 onto the current pin M. */
		private static double[] carried(double[] m, double[] m0, double[] point, double cos, double sin) {
			final double dx = point[0] - m0[0];
			final double dy = point[1] - m0[1];
			return new double[]{m[0] + dx * cos - dy * sin, m[1] + dx * sin + dy * cos};
		}

		/**
		 * Which of the follower's two solution circles is the real one - the mechanism's ASSEMBLY MODE.
		 *
		 * <p>It never changes while the linkage moves, so it is read off the PARKED configuration rather
		 * than decided per frame: deciding per frame (nearest to the last position, say) lets the blade
		 * flip to its mirror position at a toggle point part way through the stroke. That flip is not
		 * hypothetical - it is exactly what made the fixture's fan and the packager's solve disagree.</p>
		 *
		 * <p>It is decided by <b>handedness</b> - the sign of the parked triangle's area - and not by
		 * "which candidate sits nearer the parked pin". The two candidates are mirror images across the
		 * {@code p2 -> m0} line, so their handedness signs always differ while their distances can be all
		 * but equal: near a toggle position the circles are nearly tangent and "nearer" turns on a
		 * sub-micron difference. The packager recovers these pins from mesh geometry (PCA) rather than
		 * reading constants, so small input error is the normal case, and a proximity test then silently
		 * selects the MIRROR branch and moves the blade by centimetres. The handedness of the parked
		 * configuration cannot flip that way, so packager, fixture and client all resolve the same branch
		 * from the same geometry. Note the sign: for this {@link #followerEnd} parametrisation a candidate
		 * of mode {@code m} has handedness {@code -d*h*m}, hence "same sign as park" means "same mode".</p>
		 */
		private static int followMode(double[] p1, double[] p2, double[] m0, double[] br0, double spanM, double followerM) {
			final double[] plus = followerEnd(m0, p2, spanM, followerM, 1);
			final double[] minus = followerEnd(m0, p2, spanM, followerM, -1);
			if (plus == null || minus == null) {
				return 1;
			}
			final double parkSign = handedness(p2, m0, br0);
			if (parkSign == 0.0D) {
				return 1;
			}
			return handedness(p2, m0, plus) == 0.0D || (handedness(p2, m0, plus) > 0.0D) == (parkSign > 0.0D) ? 1 : -1;
		}

		/** Twice the signed area of the triangle {@code (a, b, c)} - the branch test of {@link #followMode}. */
		private static double handedness(double[] a, double[] b, double[] c) {
			return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
		}

		/**
		 * The follower's end: where a circle of radius {@code radiusAboutA} about {@code a} meets a circle of
		 * radius {@code radiusAboutPivot} about {@code pivot}, on the side {@code mode} says.
		 *
		 * <p>This IS the loop closure of the four-bar. A parallelogram satisfies those two circle
		 * conditions with {@code pivot + R(theta)*(Br0-pivot)}, so it comes out of the same expression -
		 * the parallel double link is the degenerate case, not a special case.</p>
		 *
		 * <p><b>The parameter names matter:</b> the FIRST radius belongs to the circle about the FIRST
		 * point. Passing them the other way round returns the two intersections of the wrong pair of
		 * circles - plausible-looking points that are metres away from the real ones.</p>
		 *
		 * @return null when the circles do not meet (the linkage cannot reach that angle)
		 */
		@Nullable
		private static double[] followerEnd(double[] a, double[] pivot, double radiusAboutA, double radiusAboutPivot, int mode) {
			final double dx = pivot[0] - a[0];
			final double dy = pivot[1] - a[1];
			final double distance = Math.hypot(dx, dy);
			if (distance < 1.0E-9 || distance > radiusAboutA + radiusAboutPivot || distance < Math.abs(radiusAboutA - radiusAboutPivot)) {
				return null;
			}
			final double along = (distance * distance + radiusAboutA * radiusAboutA - radiusAboutPivot * radiusAboutPivot) / (2 * distance);
			final double height = Math.sqrt(Math.max(0, radiusAboutA * radiusAboutA - along * along));
			final double ux = dx / distance;
			final double uy = dy / distance;
			return new double[]{
					a[0] + ux * along - mode * uy * height,
					a[1] + uy * along + mode * ux * height
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

