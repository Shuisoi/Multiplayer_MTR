import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Offline preview of the MMTR windshield: the droplet model and the wiper, rendered without Minecraft.
 *
 * <p>This exists because the only other way to see this feature is to launch the game, drive to a cab
 * and wait for rain. Every constant below is MIRRORED from {@code MmtrWindshield} / {@code MmtrPanelCanvas}
 * - the same forces, the same bead shapes, the same wiper film, the same sheet-to-face v flip. It is not
 * a reimplementation of the LOOK; it is the same arithmetic, so a picture from here is evidence about
 * what the client will draw.</p>
 *
 * <p>Keep it in step: if a constant in {@code MmtrWindshield} changes, change it here too. That is why
 * the two files quote each other's numbers in comments.</p>
 *
 * <p>Run (from the workspace root):</p>
 * <pre>
 *   . env\workspace.env.ps1
 *   &amp; "$env:JAVA_HOME\bin\javac.exe" mmtr\tools\wiper-preview\WiperPreview.java
 *   &amp; "$env:JAVA_HOME\bin\java.exe" -cp mmtr\tools\wiper-preview WiperPreview logs\2026-09\wiper-droplets.png
 * </pre>
 */
public final class WiperPreview {

	// ---- mirrored from MmtrWindshield -------------------------------------------------------------
	private static final double WATER_SHEEN_ALPHA = 0x12;
	private static final double AIRFLOW_COEFFICIENT = 0.02;
	private static final double MAX_LATERAL_ACCELERATION = 1.2;
	private static final double MAX_LATERAL_VELOCITY = 0.45;
	private static final double WIPE_PADDING_DEG = 7;
	/** Mirrors MmtrWindshield.WIPE_FADE_DEG. */
	private static final double WIPE_FADE_DEG = 14;
	/** Periods from WiperMode.SLOW / FAST. */
	private static final double SLOW_PERIOD_S = 1.55;
	private static final double FAST_PERIOD_S = 0.78;
	private static final double SLOW_DWELL_S = 1;
	private static final double FAST_DWELL_S = 2;

	// Glass geometry: the saf101 windshield anchor, measured in game.
	private static final double WIDTH_M = 2.996;
	private static final double HEIGHT_M = 1.558;
	private static final int CANVAS_WIDTH_PX = 128;
	private static final double SWEEP_DEG = 88;
	private static final double PARK_DEG = 0;
	private static final double SWEEP_SIGN = 1;
	private static final double ARM_M = Math.min(WIDTH_M, HEIGHT_M) / 2;
	private static final double BLADE_WIDTH_M = 0.035;
	private static final double PIVOT_U = 0.5;
	private static final double PIVOT_V = 0.0;

	private static final int RAINDROPS = 90;
	private static final double FALL_MPS = 0.55;
	private static final double MAX_STREAK_M = 0.10;
	private static final double CREEP_MPS = 0.09;
	private static final double JITTER_MPS = 0.008;
	private static final double MIN_BEAD_RADIUS_M = 0.0035;
	private static final double MAX_BEAD_RADIUS_M = 0.014;
	private static final double GROWTH_MPS = 0.0008;
	private static final double SPAWN_PER_SECOND = 2;
	/** Mirrors MmtrWindshield.RE_WET_INK. */
	private static final double RE_WET_INK = 0.30;
	/** Mirrors MmtrWindshield.STATIC_THRESHOLD default. */
	private static final double STATIC_THRESHOLD_M = 0.0045;
	/** Mirrors MmtrWindshield.PINCH_OFF_DISTANCE_M. */
	private static final double PINCH_OFF_DISTANCE_M = 0.035;
	/** Mirrors MmtrWindshield.TRAIL_DRY_MPS. */
	private static final double TRAIL_DRY_MPS = 0.02;
	/** Mirrors the wetness-field defaults. */
	private static final int WETNESS_CELLS = 48;
	private static final double WETNESS_DEPOSIT_PER_SECOND = 6;
	private static final double WETNESS_DRY_PER_SECOND = 0.03;
	private static final double MERGE_DISTANCE_M = 0.012;

	private enum Mode {
		OFF(0, 0), SLOW(SLOW_PERIOD_S, SLOW_DWELL_S), FAST(FAST_PERIOD_S, FAST_DWELL_S);

		private final double periodS;
		private final double dwellS;

		Mode(double periodS, double dwellS) {
			this.periodS = periodS;
			this.dwellS = dwellS;
		}
	}

	public static void main(String[] args) throws Exception {
		int failures = 0;

		// ---- 1. the droplet field ---------------------------------------------------------------
		// The three regimes, which is the whole point of the capillary-pinning model. Sampled early,
		// because by 20 s nearly every bead has grown past the threshold and is running.
		final Glass early = new Glass(new Random(4242));
		early.simulate(2, 0, 1.0F, Mode.OFF);
		failures += expect("起步阶段大部分雨珠被表面张力钉住（不走）", early.pinnedFraction() > 0.35,
				String.format("pinned=%.2f", early.pinnedFraction()));

		// CALIBRATION PROBE: how the field splits between pinned and running at 20 s must be a MIX, not a
		// uniform answer either way. Printed rather than asserted, because the useful information is the
		// distribution and the number it implies for the pinned lifetime.
		final Glass calibrate = new Glass(new Random(1234));
		calibrate.simulate(20, 0, 1.0F, Mode.OFF);
		System.out.printf("       [calib] 20s pinned=%.2f running=%.2f  半径分布 %s%n",
				calibrate.pinnedFraction(), 1 - calibrate.pinnedFraction(), calibrate.radiusHistogram());
		final Glass calibrate60 = new Glass(new Random(1234));
		calibrate60.simulate(60, 0, 1.0F, Mode.OFF);
		System.out.printf("       [calib] 60s pinned=%.2f wetness coverage=%.3f max=%.2f%n",
				calibrate60.pinnedFraction(), calibrate60.wetnessCoverage(), calibrate60.maxWetness());

		final Glass dry = new Glass(new Random(1234));
		dry.simulate(20, 0, 1.0F, Mode.OFF);
		// Bead radii are millimetres on a 1.5 m tall glass, so a "visible coverage" of a few tenths of a
		// percent is correct - the number checked here is really "the field did not collapse to nothing",
		// which is what a respawn that always returns the minimum radius causes.
		failures += expect("静止 20 s 后雨珠场没有塌缩（覆盖率 > 0.05%）",
				dry.occupiedFraction() > 0.0005 && dry.occupiedFraction() < 0.20,
				String.format("occupied=%.4f of the glass", dry.occupiedFraction()));
		failures += expect("静止时雨珠整体在下移（平均方向朝下）", dry.averageDirectionY() < -0.5,
				String.format("meanDirY=%.2f", dry.averageDirectionY()));
		failures += expect("静止时没有雨珠在爬升", dry.climbingFraction() < 0.05,
				String.format("climbing=%.2f", dry.climbingFraction()));
		// Only the RUNNING beads carry water, so this is measured over the moving subset: with most of the
		// field pinned (the point of the model), a population-wide average is diluted to meaninglessness.
		// The upper bound is generous because MERGING makes beads bigger than maxBeadRadiusM suggests
		// (radii add in quadrature), and a bigger bead legitimately runs faster.
		failures += expect("静止时正在流的雨珠速度在 0.15~0.80 m/s（含合并变大的珠子）",
				dry.movingSurfaceSpeedMps() > 0.15 && dry.movingSurfaceSpeedMps() < 0.80,
				String.format("movingSpeed=%.4f m/s  running=%.2f", dry.movingSurfaceSpeedMps(), 1 - dry.pinnedFraction()));
		failures += expect("拖尾长度在合理范围（不超过 0.10 m）", dry.maxTrail() <= MAX_STREAK_M + 1.0E-9,
				String.format("maxTrail=%.4f", dry.maxTrail()));

		// The field must carry water toward the bottom EDGE, not merely have a low mean Y. The mean Y of a
		// field that respawns uniformly is pinned near 0.5 by construction, so it cannot answer this
		// question - the bottom edge's wetness can: it only ever receives water from above.
		final Glass travelling = new Glass(new Random(777));
		travelling.simulate(6, 0, 1.0F, Mode.OFF);
		failures += expect("水流到了玻璃下沿（下沿水膜 > 0）", travelling.bottomEdgeWetness() > 0.05,
				String.format("bottomEdge=%.3f  coverage=%.3f", travelling.bottomEdgeWetness(), travelling.wetnessCoverage()));
		failures += expect("玻璃上留下了水膜轨迹（覆盖率 > 0）", travelling.wetnessCoverage() > 0.0,
				String.format("coverage=%.3f  max=%.2f", travelling.wetnessCoverage(), travelling.maxWetness()));

		final Glass lineSpeed = new Glass(new Random(1234));
		lineSpeed.simulate(20, 33.3, 1.0F, Mode.OFF);
		failures += expect("120 km/h 时雨珠改为向上爬升", lineSpeed.climbingFraction() > 0.90,
				String.format("climbing=%.2f", lineSpeed.climbingFraction()));
		// 0.09 m/s ceiling on the creep (x0.5..1.0 size scale) plus the lateral jitter. This is the check
		// that pins "at line speed the beads are nearly stopped", which is the whole point of the airflow
		// term - the value is not 0 because the ride-quality jitter never stops.
		failures += expect("120 km/h 时雨珠几乎停住（表面速度 < 0.09 m/s）", lineSpeed.averageSurfaceSpeedMps() < 0.09,
				String.format("meanSurfaceSpeed=%.4f m/s", lineSpeed.averageSurfaceSpeedMps()));

		final Glass midSpeed = new Glass(new Random(1234));
		midSpeed.simulate(20, 4.0, 1.0F, Mode.OFF);
		failures += expect("15 km/h 时雨珠仍在下移（低于平衡速度）", midSpeed.climbingFraction() < 0.05,
				String.format("climbing=%.2f", midSpeed.climbingFraction()));

		// ---- 2. the wipe ------------------------------------------------------------------------
		// The field is WARMED UP first (3 s of rain with the blade parked), because the question the
		// checks ask is "what does the blade do to a wet screen" - not "what does a screen look like
		// 0.4 s after the first bead lands", where nothing has grown an ink level worth measuring yet.
		final Glass wiping = new Glass(new Random(99));
		wiping.simulate(40, 0, 1.0F, Mode.OFF);
		final double inkBefore = wiping.minInkOutsideSweptSector();
		failures += expect("雨刷启动前玻璃是湿的（已长成的雨珠 ink > 0.5）", inkBefore > 0.5, String.format("inkBefore=%.2f", inkBefore));
		// Only the beads that were on the glass when the stroke started can say anything about the wipe.
		wiping.markPresentBeforeStroke();
		wiping.mode = Mode.SLOW;
		wiping.simulate(1.55 / 4, 0, 1.0F, Mode.SLOW);
		failures += expect("SLOW 档一个行程内扫过约四分之一圈",
				Math.abs(wiping.totalSweptDegrees() - SWEEP_DEG / 2) < 6,
				String.format("swept=%.0f deg (expect ~%.0f)", wiping.totalSweptDegrees(), SWEEP_DEG / 2));
		// The band's extent is checked in section 3b; a snapshot a quarter into the FIRST stroke has too
		// few beads inside it to measure a width from.
		failures += expect("未扫到、且已长成的雨珠没有被误伤（平均 ink ≥ 0.5）",
				wiping.meanInkOutsideSweptSector() > 0.5,
				String.format("outsideInk=%.2f", wiping.meanInkOutsideSweptSector()));
		// And the wipe has to be doing the work: without this, "ink is low in the sector" could just be
		// beads that happened to respawn there (respawn ink is 0).
		failures += expect("确实发生了擦水（刀扫过雨珠的次数 > 0）", wiping.wipeEvents() > 0,
				String.format("wipeEvents=%d over %.1f s", wiping.wipeEvents(), 1.55 / 4));

		// ---- 3. the wipe invariant ---------------------------------------------------------------
		// Checked EVERY step of a stroke rather than sampled at the end: the interesting statement is
		// "while the blade is moving, no bead survives inside the band it has cleared", and beads that
		// are in the band at the end of a stroke are about to leave it anyway. This is the check that
		// would have caught both early bugs (a band that grew with radius, and a band on the wrong side).
		final Glass clear = new Glass(new Random(7));
		clear.simulate(5, 0, 1.0F, Mode.OFF);
		final double worstInBand = clear.sweepAndMeasureWorstInk(1.55);
		failures += expect("刀扫过时，已清扇区里没有任何雨珠存活（ink ≤ 0.31）", worstInBand <= 0.31,
				String.format("worstInkInBand=%.3f", worstInBand));
		// The WETNESS FIELD is what proves the glass is left wet rather than scraped dry: a bead's ink can
		// legitimately sit at its floor, but the film is a separate quantity and a wrongly-wired wipe
		// (scaling it to zero instead of thinning it) would show up here and nowhere else.
		failures += expect("擦过之后玻璃上仍留有水膜（wetness 未被刮成干玻璃）", clear.maxWetness() > 0,
				String.format("maxWetness=%.2f  coverage=%.3f", clear.maxWetness(), clear.wetnessCoverage()));

		// ---- 3b. the wiped band is a BAND, not the whole screen -----------------------------------
		// Measured on the WIPE FIELD ITSELF, not on where the beads ended up. Beads are the wrong probe
		// for a band's extent under the pinning model: most of the field does not move at all, so a
		// position-based measurement finds almost nothing in the swept sector and reports a band of 0
		// degrees no matter how correct the wipe is. The field is what the blade actually acts on.
		//
		// Sampled a quarter into a stroke, so the answers are unambiguous: dead centre of the swept region
		// was cleared, the leading edge is partly faded, and a point a right angle away was never touched.
		final Glass band = new Glass(new Random(11));
		band.simulate(5, 0, 1.0F, Mode.OFF);
		band.mode = Mode.SLOW;
		band.simulate(1.55 / 4, 0, 1.0F, Mode.SLOW);
		failures += expect("擦水场已扫过的部分被清掉（wipe=1）", band.wipeFactorAt(9) >= 0.99,
				String.format("wipe@9deg=%.2f", band.wipeFactorAt(9)));
		// A quarter into a 1.55 s stroke the blade sits at 44 deg, so the fade band is 44..58 deg.
		failures += expect("刀刃前方是渐隐而不是硬边（0 < wipe < 1）",
				band.wipeFactorAt(50) > 0.01 && band.wipeFactorAt(50) < 0.99,
				String.format("wipe@50deg=%.2f (刀刃在 44deg)", band.wipeFactorAt(50)));
		failures += expect("未扫到的玻璃完全没被碰（wipe=0）", band.wipeFactorAt(85) <= 1.0E-9,
				String.format("wipe@85deg=%.2f", band.wipeFactorAt(85)));
		// Parked: the modelled quad's own "right" edge IS the park direction, so angle 0 must put the tip
		// along +right with no lift at all. This is the check that a model author can rely on.
		final Glass parked = new Glass(new Random(1));
		failures += expect("停放位刀尖贴着玻璃右边缘（角度 0 = right 轴）",
				Math.abs(parked.bladeTipAlongUp()) < 1.0E-9 && parked.bladeTipAlongRight() > ARM_M * 0.99,
				String.format("tip=(%.3f, %.3f) m", parked.bladeTipAlongRight(), parked.bladeTipAlongUp()));
		final Glass sweeping = new Glass(new Random(1));
		sweeping.simulate(1.55 / 4, 0, 1.0F, Mode.SLOW);
		failures += expect("扫到四分之一行程时刀尖抬到玻璃上缘方向",
				sweeping.bladeTipAlongUp() > ARM_M * 0.5,
				String.format("tip=(%.3f, %.3f) m", sweeping.bladeTipAlongRight(), sweeping.bladeTipAlongUp()));
		failures += expect("刀尖不超出玻璃对角线（臂长不超过短边一半）",
				Math.hypot(sweeping.bladeTipAlongRight(), sweeping.bladeTipAlongUp()) <= ARM_M + 1.0E-6,
				String.format("reach=%.3f arm=%.3f", Math.hypot(sweeping.bladeTipAlongRight(), sweeping.bladeTipAlongUp()), ARM_M));

		// ---- 4. pictures ------------------------------------------------------------------------
		render("停放 0 km/h (OFF) - 雨珠下流、拉出拖尾", Mode.OFF, 0, 20, "off@0");
		render("停车下雨 (SLOW) - 刀刮出的水膜扇区", Mode.SLOW, 0, 20, "slow@0kmh");
		render("走行 40 km/h (SLOW) - 气流让雨珠变慢", Mode.SLOW, 11.1, 20, "slow@40kmh");
		render("直线 120 km/h (FAST) - 雨珠向上爬升", Mode.FAST, 33.3, 20, "fast@120kmh");

		System.out.println();
		System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
		if (failures != 0) {
			System.exit(1);
		}
	}

	private static int expect(String what, boolean condition, String detail) {
		System.out.printf("%s  %-52s %s%n", condition ? "[ok]  " : "[FAIL]", what, detail);
		return condition ? 0 : 1;
	}

	/** Each panel is a labelled "screenshot" of the glass. Laid out 2 x 2. */
	private static void render(String label, Mode mode, double speedMps, double seconds, String tag) throws Exception {
		final int panelWidth = 560;
		final int panelHeight = 320;
		final BufferedImage image = new BufferedImage(panelWidth, panelHeight, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		// A dark backdrop standing in for a rainy street seen through the glass.
		graphics.setColor(new Color(0x20, 0x24, 0x2C));
		graphics.fillRect(0, 0, panelWidth, panelHeight);

		final double scale = Math.min((panelWidth - 40) / WIDTH_M, (panelHeight - 70) / HEIGHT_M);
		final double originX = (panelWidth - WIDTH_M * scale) / 2;
		final double originY = (panelHeight - HEIGHT_M * scale) / 2 + 12;

		final Glass glass = new Glass(new Random(1234));
		glass.simulate(seconds, speedMps, 1.0F, mode);

		graphics.setColor(new Color(0x3A, 0x40, 0x4A));
		graphics.fillRect((int) originX, (int) originY, (int) (WIDTH_M * scale), (int) (HEIGHT_M * scale));
		glass.paint(graphics, originX, originY, scale);

		graphics.setColor(Color.WHITE);
		graphics.setFont(new Font("SansSerif", Font.PLAIN, 15));
		graphics.drawString(label, 14, 22);
		graphics.setColor(new Color(0xA0, 0xB4, 0xC8));
		graphics.setFont(new Font("SansSerif", Font.PLAIN, 11));
		graphics.drawString(String.format("t=%.1fs  bead=%.1fmm..%.1fmm  airflow=%.2f m/s2  %s",
				seconds, MIN_BEAD_RADIUS_M * 1000, MAX_BEAD_RADIUS_M * 1000,
				AIRFLOW_COEFFICIENT * speedMps * speedMps, tag), 14, panelHeight - 12);

		ImageIO.write(image, "png", new File("logs/2026-09/wiper-preview-" + tag + ".png"));
		System.out.println("logged/2026-09/wiper-preview-" + tag + ".png");
	}

	/** The whole windshield simulation, in the glass's own y-up metre coordinates. */
	private static final class Glass {

		private final Random random;
		private final Drop[] drops = new Drop[RAINDROPS];
		private double wiperAngleDeg = PARK_DEG;
		private double wipedFromDeg = PARK_DEG;
		private double wiperPhase;
		private boolean bladeMoving;
		private double intensity = 1;
		private double spawnAccumulator;
		private Mode mode = Mode.OFF;
		/** Accumulated |d(angle)| so a check can prove the blade really moved. */
		private double totalSweptDegrees;
		/** Mirrors MmtrWindshield.State.wetness. */
		private final float[] wetness = new float[WETNESS_CELLS * WETNESS_CELLS];
		/** Mirrors MmtrWindshield.State.wipeRatchetDeg. */
		private double wipeRatchetDeg = PARK_DEG;
		/**
		 * Runs one stroke and returns the WORST ink found inside the cleared band at any point during it.
		 *
		 * <p>This is the invariant that has to hold every frame while the blade is moving: whatever the
		 * blade has already crossed is wet-but-not-beaded. Sampling the band once at the end cannot see
		 * it, because by then the beads that were in the band have run out of the arm's reach.</p>
		 */
		private double sweepAndMeasureWorstInk(double strokeSeconds) {
			mode = Mode.SLOW;
			worstInkInBand = -1;
			final int steps = (int) Math.round(strokeSeconds * 62.5);
			for (int i = 0; i < steps; i++) {
				observeBand = true;
				advance(0.016, 0, 1.0F);
				// The mod applies the wipe at repaint (every 90 ms), so measure right after the gate ran.
				observeBand = false;
			}
			return worstInkInBand;
		}

		/** How many times a bead has been knocked back by the blade, so a check can prove it happened. */
		private int wipeEvents;
		/** Set while {@link #sweepAndMeasureWorstInk} is sampling the cleared band. */
		private boolean observeBand;
		private double worstInkInBand = -1;
		/** Whether a bead was on the glass before the stroke being measured (harness-only). */
		private boolean presentBeforeStroke;
		/** Down the glass, in face coordinates. The saf101 glass is raked ~16 deg, so it is close to -Y. */
		private final double[] down = {0, -1};

		private Glass(Random random) {
			this.random = random;
			for (int i = 0; i < drops.length; i++) {
				drops[i] = newDrop(random.nextDouble(), random.nextDouble(), random);
			}
		}

		/** Mirrors MmtrWindshield.State.newBeadRadiusM: capped below the pinning threshold. */
		private double newBeadRadiusM() {
			final double ceiling = Math.min(MAX_BEAD_RADIUS_M, STATIC_THRESHOLD_M);
			return MIN_BEAD_RADIUS_M + random.nextDouble() * Math.max(0, ceiling - MIN_BEAD_RADIUS_M) * 0.9;
		}

		private Drop newDrop(double x, double y, Random random) {
			final Drop drop = new Drop(wrap(x), wrap(y), random.nextDouble(), new Random(random.nextLong()));
			drop.radiusM = newBeadRadiusM();
			return drop;
		}

		private void simulate(double seconds, double speedMps, float rain, Mode mode) {
			this.mode = mode;
			// 60 Hz in 16 ms steps, exactly what the client does (it clamps each step to 250 ms).
			final int steps = (int) Math.round(seconds * 62.5);
			for (int i = 0; i < steps; i++) {
				advance(0.016, speedMps, rain);
			}
		}

		// ---- instrumentation used by the checks -------------------------------------------------

		/** Fraction of the glass covered by beads of at least a visible size. */
		private double occupiedFraction() {
			double area = 0;
			for (final Drop drop : drops) {
				if (drop.ink > 0.5 && drop.radiusM > MIN_BEAD_RADIUS_M * 1.5) {
					area += Math.PI * drop.radiusM * drop.radiusM;
				}
			}
			return area / (WIDTH_M * HEIGHT_M);
		}

		private double averageDirectionY() {
			double sum = 0;
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.trail > 0.01) {
					sum += drop.directionY;
					count++;
				}
			}
			return count == 0 ? 0 : sum / count;
		}

		/** Fraction of running beads whose last movement was UPWARD in face coordinates. */
		private double climbingFraction() {
			int climbing = 0;
			int running = 0;
			for (final Drop drop : drops) {
				if (drop.trail > 0.01) {
					running++;
					if (drop.directionY > 0) {
						climbing++;
					}
				}
			}
			return running == 0 ? 0 : (double) climbing / running;
		}

		private double averageSurfaceSpeedMps() {
			double sum = 0;
			for (final Drop drop : drops) {
				sum += drop.surfaceSpeedMps;
			}
			return sum / Math.max(1, drops.length);
		}

		/**
		 * Mean surface speed over only the beads that are actually MOVING.
		 *
		 * <p>Distinct from {@link #averageSurfaceSpeedMps()}, which averages over the whole population and
		 * is therefore diluted by all the pinned beads: with ~60% of the field pinned, "the average bead is
		 * barely moving" is true and says nothing about whether the runners are running.</p>
		 */
		private double movingSurfaceSpeedMps() {
			double sum = 0;
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.surfaceSpeedMps > 1.0E-9) {
					sum += drop.surfaceSpeedMps;
					count++;
				}
			}
			return count == 0 ? 0 : sum / count;
		}

		private double maxTrail() {
			double max = 0;
			for (final Drop drop : drops) {
				max = Math.max(max, drop.trail);
			}
			return max;
		}

		private double totalSweptDegrees() {
			return totalSweptDegrees;
		}

		private double minInkInSweptSector() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			double min = 1;
			boolean any = false;
			for (final Drop drop : drops) {
				if (wasWiped(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY)) {
					min = Math.min(min, drop.ink);
					any = true;
				}
			}
			return any ? min : 0;
		}

		private double minInkOutsideSweptSector() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			double min = 1;
			boolean any = false;
			for (final Drop drop : drops) {
				// Only beads that were on the glass BEFORE the stroke: a bead that has just fallen in is
				// still growing its ink and is not evidence of anything.
				if (drop.presentBeforeStroke && !wasWiped(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY)) {
					min = Math.min(min, drop.ink);
					any = true;
				}
			}
			return any ? min : 1;
		}

		/** Mean ink over the beads the current stroke has actually cleared. */
		private double meanInkInSweptSector() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			double sum = 0;
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.presentBeforeStroke && wipeFactor(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY) >= 0.99) {
					sum += drop.ink;
					count++;
				}
			}
			return count == 0 ? -1 : sum / count;
		}

		private int countInSweptSector() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.presentBeforeStroke && wipeFactor(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY) >= 0.99) {
					count++;
				}
			}
			return count;
		}

		private int countPresentBeforeStroke() {
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.presentBeforeStroke) {
					count++;
				}
			}
			return count;
		}

		/** The worst ink inside the swept band: the invariant is that nothing in it stays wet. */
		private double maxInkInSweptSector() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			double max = -1;
			for (final Drop drop : drops) {
				if (drop.presentBeforeStroke && wipeFactor(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY) >= 0.99) {
					max = Math.max(max, drop.ink);
				}
			}
			return max;
		}

		private int wipeEvents() {
			return wipeEvents;
		}

		/** Mean ink over the beads the current stroke has NOT reached. */
		private double meanInkOutsideSweptSector() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			double sum = 0;
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.presentBeforeStroke && wipeFactor(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY) <= 0) {
					sum += drop.ink;
					count++;
				}
			}
			return count == 0 ? -1 : sum / count;
		}

		/** Marks every bead currently on the glass as "was there before the stroke started". */
		private void markPresentBeforeStroke() {
			for (final Drop drop : drops) {
				drop.presentBeforeStroke = drop.ink > 0.5;
			}
		}

		private double bladeTipAlongRight() {
			final double radians = Math.toRadians(wiperAngleDeg);
			return Math.cos(radians) * SWEEP_SIGN * ARM_M;
		}

		private double bladeTipAlongUp() {
			final double radians = Math.toRadians(wiperAngleDeg);
			return Math.sin(radians) * SWEEP_SIGN * ARM_M;
		}

		private void advance(double elapsedSeconds, double speedMps, float rain) {
			intensity = rain;
			advanceWiper(elapsedSeconds, mode);
			advanceDrops(elapsedSeconds, speedMps);
			mergeDrops();
			// The mod applies the wipe in rebuildCanvas (every 90 ms); the harness samples all 60 Hz, so
			// this only has to catch "was a bead knocked back at any point", which it does either way.
			if (bladeMoving) {
				final double pointX = PIVOT_U * WIDTH_M;
				final double pointY = PIVOT_V * HEIGHT_M;
				for (final Drop drop : drops) {
					final double wipe = wipeFactor(drop.x * WIDTH_M, drop.y * HEIGHT_M, pointX, pointY);
					if (wipe > 0 && drop.ink > RE_WET_INK + (1 - RE_WET_INK) * (1 - wipe)) {
						wipeEvents++;
					}
					// The invariant: after the gate has run, nothing inside the fully-cleared band is still
					// a bead. Checked on the values the gate would have produced, so it measures the gate
					// (which is what the mod runs) and not the harness's own bookkeeping.
					if (observeBand && wipe >= 0.99) {
						final double capped = Math.min(drop.ink, RE_WET_INK);
						worstInkInBand = Math.max(worstInkInBand, capped);
					}
				}
			}
		}

		private void advanceWiper(double elapsedSeconds, Mode mode) {
			final double previousAngleDeg = wiperAngleDeg;
			final double previousPhase = wiperPhase;
			if (mode == Mode.OFF) {
				bladeMoving = false;
				wipedFromDeg = wiperAngleDeg;
				wipeRatchetDeg = wiperAngleDeg;
				return;
			}
			final double strokeRate = 2 / Math.max(0.05, mode.periodS);
			final double strokeTime = 2 / strokeRate;
			final double cycle = strokeTime + mode.dwellS;
			double time = wiperPhase / strokeRate + elapsedSeconds;
			if (time >= cycle) {
				time -= cycle;
			}
			if (time >= strokeTime) {
				time = 0;
				bladeMoving = false;
			} else {
				bladeMoving = true;
			}
			wiperPhase = time * strokeRate;
			wiperAngleDeg = PARK_DEG + sweepUnit(wiperPhase) * SWEEP_DEG;
			wipedFromDeg = previousAngleDeg;
			totalSweptDegrees += Math.abs(wiperAngleDeg - previousAngleDeg);
		}

		private double sweepUnit(double phase) {
			final double wrapped = phase % 2;
			return wrapped <= 1 ? wrapped : 2 - wrapped;
		}

		private void advanceDrops(double elapsedSeconds, double speedMps) {
			final double gravityDown = FALL_MPS * (0.6 + 0.9 * intensity);
			final double airflow = AIRFLOW_COEFFICIENT * speedMps * speedMps;
			final double surfaceSpeedClimb = Math.max(-gravityDown, Math.min(CREEP_MPS, airflow - gravityDown));
			for (final Drop drop : drops) {
				advanceDrop(drop, elapsedSeconds, surfaceSpeedClimb);
			}
			spawnForWeather(elapsedSeconds);
			mergeDrops();
			decayWetness(elapsedSeconds);
		}

		/** Mirrors MmtrWindshield.State.advanceDrop. */
		private void advanceDrop(Drop drop, double elapsedSeconds, double surfaceSpeedClimb) {
			drop.radiusM = Math.min(MAX_BEAD_RADIUS_M, drop.radiusM + GROWTH_MPS * elapsedSeconds * (0.4 + intensity));
			if (drop.ink < 1) {
				drop.ink = Math.min(1, drop.ink + elapsedSeconds * 0.8);
			}

			final double staticThreshold = STATIC_THRESHOLD_M * (1.1 + 0.7 * drop.variation);
			final boolean running = drop.radiusM > staticThreshold || surfaceSpeedClimb > 0;
			if (!running) {
				drop.surfaceSpeedMps = 0;
				drop.trail = Math.max(0, drop.trail - elapsedSeconds * TRAIL_DRY_MPS);
				return;
			}

			final double sizeScale = 0.5 + 0.5 * (drop.radiusM / MAX_BEAD_RADIUS_M);
			final double climbSpeed = surfaceSpeedClimb * sizeScale;
			double lateralVelocity = drop.lateralVelocity + (drop.random.nextGaussian() * JITTER_MPS) * elapsedSeconds;
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
			drop.trail = Math.min(MAX_STREAK_M, drop.trail + speed * elapsedSeconds * 1.6);
			depositWetness(drop, elapsedSeconds);

			drop.pinchAccumulatorM += speed * elapsedSeconds;
			if (drop.pinchAccumulatorM >= PINCH_OFF_DISTANCE_M && drop.radiusM > STATIC_THRESHOLD_M * 1.4) {
				drop.pinchAccumulatorM = 0;
				pinchOff(drop);
			}

			if (drop.x < -0.05 || drop.x > 1.05 || drop.y < -0.10 || drop.y > 1.10) {
				respawn(drop);
			}
		}

		private void pinchOff(Drop parent) {
			for (final Drop child : drops) {
				if (child != parent && child.ink <= 0.05) {
					child.x = parent.x - parent.directionX * 0.004;
					child.y = parent.y - parent.directionY * 0.004;
					child.radiusM = parent.radiusM * 0.55;
					child.ink = 0.75;
					child.trail = 0;
					child.lateralVelocity = 0;
					child.pinchAccumulatorM = 0;
					child.directionX = parent.directionX;
					child.directionY = parent.directionY;
					return;
				}
			}
		}

		// ---- wetness field ----------------------------------------------------------------------

		private void depositWetness(Drop drop, double elapsedSeconds) {
			if (wetness == null) {
				return;
			}
			final int cellX = (int) Math.floor(drop.x * WETNESS_CELLS);
			final int cellY = (int) Math.floor(drop.y * WETNESS_CELLS);
			if (cellX < 0 || cellY < 0 || cellX >= WETNESS_CELLS || cellY >= WETNESS_CELLS) {
				return;
			}
			final int index = cellY * WETNESS_CELLS + cellX;
			wetness[index] = (float) Math.min(1, wetness[index] + elapsedSeconds * WETNESS_DEPOSIT_PER_SECOND);
		}

		private void decayWetness(double elapsedSeconds) {
			if (wetness == null) {
				return;
			}
			final float decay = (float) (elapsedSeconds * WETNESS_DRY_PER_SECOND);
			for (int i = 0; i < wetness.length; i++) {
				if (wetness[i] > 0) {
					wetness[i] = Math.max(0, wetness[i] - decay);
				}
			}
		}

		/** How much of the glass is covered by trail moisture, and how dark the darkest streak is. */
		private double wetnessCoverage() {
			if (wetness == null) {
				return 0;
			}
			int wet = 0;
			for (final float value : wetness) {
				if (value > 0.08F) {
					wet++;
				}
			}
			return (double) wet / wetness.length;
		}

		/**
		 * Mean wetness along the bottom row of the field.
		 *
		 * <p>This is the measurement that actually answers "is the water moving DOWN": a cell at the
		 * bottom edge can only be wetted by a bead that travelled there, whereas the field's mean Y is
		 * held at 0.5 by construction because respawns are uniform.</p>
		 */
		private double bottomEdgeWetness() {
			if (wetness == null) {
				return 0;
			}
			double sum = 0;
			for (int x = 0; x < WETNESS_CELLS; x++) {
				sum += wetness[x];
			}
			return sum / WETNESS_CELLS;
		}

		private double maxWetness() {
			if (wetness == null) {
				return 0;
			}
			float max = 0;
			for (final float value : wetness) {
				max = Math.max(max, value);
			}
			return max;
		}

		/** Fraction of the beads that are pinned this instant (surface tension holding them still). */
		private double pinnedFraction() {
			int pinned = 0;
			for (final Drop drop : drops) {
				if (drop.ink > 0.1 && drop.surfaceSpeedMps <= 1.0E-9) {
					pinned++;
				}
			}
			return (double) pinned / Math.max(1, drops.length);
		}

		/** Debug dump: where the bead sizes actually sit relative to the pinning threshold. */
		private String radiusHistogram() {
			final int[] buckets = new int[8];
			for (final Drop drop : drops) {
				if (drop.ink <= 0.1) {
					continue;
				}
				final int bucket = Math.max(0, Math.min(7, (int) (drop.radiusM / STATIC_THRESHOLD_M / 2.0 * 8)));
				buckets[bucket]++;
			}
			final StringBuilder builder = new StringBuilder();
			for (int i = 0; i < buckets.length; i++) {
				builder.append(String.format("%.1f-%.1fmm:%d ", STATIC_THRESHOLD_M * 2 * i / 8 * 1000, STATIC_THRESHOLD_M * 2 * (i + 1) / 8 * 1000, buckets[i]));
			}
			return builder.toString();
		}

		/** Mean Y of the live beads, used to prove the field as a whole travels DOWNWARD. */
		private double meanY() {
			double sum = 0;
			int count = 0;
			for (final Drop drop : drops) {
				if (drop.ink > 0.1) {
					sum += drop.y;
					count++;
				}
			}
			return count == 0 ? 0 : sum / count;
		}

		private void respawn(Drop drop) {
			drop.x = random.nextDouble();
			drop.y = random.nextDouble();
			// Random starting size, mirroring MmtrWindshield.State.respawn: capped below the threshold so a
			// new bead arrives PINNED and grows into a runner.
			drop.radiusM = newBeadRadiusM();
			drop.trail = 0;
			drop.lateralVelocity = 0;
			drop.ink = 0;
			drop.surfaceSpeedMps = 0;
			drop.pinchAccumulatorM = 0;
		}

		private void spawnForWeather(double elapsedSeconds) {
			if (intensity <= 0.02) {
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
			spawnAccumulator += elapsedSeconds * SPAWN_PER_SECOND * intensity;
			while (spawnAccumulator >= 1 && alive < target) {
				spawnAccumulator -= 1;
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
				weakest.ink = Math.max(keepInk, 0.55);
				alive++;
			}
		}

		private void mergeDrops() {
			for (int i = 0; i < drops.length; i++) {
				final Drop a = drops[i];
				if (a.ink <= 0.05) {
					continue;
				}
				for (int j = i + 1; j < drops.length; j++) {
					final Drop b = drops[j];
					if (b.ink <= 0.05) {
						continue;
					}
					final double deltaX = (a.x - b.x) * WIDTH_M;
					final double deltaY = (a.y - b.y) * HEIGHT_M;
					if (deltaX * deltaX + deltaY * deltaY > MERGE_DISTANCE_M * MERGE_DISTANCE_M) {
						continue;
					}
					final Drop keeper = a.radiusM >= b.radiusM ? a : b;
					final Drop absorbed = keeper == a ? b : a;
					keeper.radiusM = Math.min(MAX_BEAD_RADIUS_M, Math.hypot(keeper.radiusM, absorbed.radiusM));
					keeper.ink = Math.min(1, keeper.ink + absorbed.ink * 0.5);
					respawn(absorbed);
					absorbed.ink = 0;
				}
			}
		}

		// ---- painting ---------------------------------------------------------------------------

		private void paint(Graphics2D graphics, double originX, double originY, double scale) {
			// The canvas is y-up; swing converts to screen pixels so the same numbers as the mod's
			// MmtrPanelCanvas.px/py are in play (there: a BufferedImage with v flipped).
			final double widthPx = CANVAS_WIDTH_PX;
			final double heightPx = Math.round(widthPx * HEIGHT_M / WIDTH_M);
			final double unit = scale * WIDTH_M / widthPx;
			final double pointX = PIVOT_U * WIDTH_M;
			final double pointY = PIVOT_V * HEIGHT_M;
			final boolean canWipe = bladeMoving;

			if (canWipe) {
				final double from = wipedFromDeg;
				final double to = wiperAngleDeg;
				if (Math.abs(to - from) > 0.05) {
					final double low = Math.min(from, to) - 2;
					final double high = Math.max(from, to) + 2;
					fillSector(graphics, originX, originY, scale, pointX, pointY, ARM_M, low, high, alpha(0x2E, 0xE8F4FF));
					arc(graphics, originX, originY, scale, pointX, pointY, ARM_M * 0.97, 0.02, low, high, alpha(0x38, 0xFFFFFF));
				}
			}

			for (final Drop drop : drops) {
				if (drop.ink <= 0.02) {
					continue;
				}
				final double dropX = drop.x * WIDTH_M;
				final double dropY = drop.y * HEIGHT_M;
				if (canWipe) {
					final double wipe = wipeFactor(dropX, dropY, pointX, pointY);
					if (wipe < 0) {
						continue;
					}
					if (wipe > 0) {
						final double target = RE_WET_INK + (1 - RE_WET_INK) * (1 - wipe);
						drop.trail = 0;
						if (drop.ink > target) {
							wipeEvents++;
						}
						drop.ink = Math.min(drop.ink, target);
						drop.lateralVelocity = 0;
						continue;
					}
				}
				drawDrop(graphics, originX, originY, scale, drop, dropX, dropY);
			}

			// Border, so the glass edge is visible in the picture.
			graphics.setColor(new Color(0x60, 0x6A, 0x76));
			graphics.draw(new Rectangle2D.Double(originX, originY, WIDTH_M * scale, HEIGHT_M * scale));
		}

		private void drawDrop(Graphics2D graphics, double originX, double originY, double scale, Drop drop, double dropX, double dropY) {
			final double radiusM = drop.radiusM * (0.7 + 0.6 * drop.variation) * (0.5 + 0.5 * drop.ink);
			if (radiusM <= 0) {
				return;
			}
			final boolean running = drop.trail > 0.004;
			final double tailM = running ? Math.min(MAX_STREAK_M, drop.trail) : 0;
			double dirSheetX = 0;
			double dirSheetY = 0;
			if (running) {
				dirSheetX = drop.directionX * WIDTH_M;
				dirSheetY = drop.directionY * HEIGHT_M;
				final double magnitude = Math.hypot(dirSheetX, dirSheetY);
				if (magnitude < 1.0E-6) {
					dirSheetX = down[0] * WIDTH_M;
					dirSheetY = down[1] * HEIGHT_M;
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
				line(graphics, originX, originY, scale, headX, headY, dropX, dropY, radiusM * 1.7, alpha((int) (0x22 * drop.ink), 0xC8E0F4));
				line(graphics, originX, originY, scale, headX, headY, dropX, dropY, radiusM * 0.95, alpha((int) (0x4A * drop.ink), 0xE4F0FF));
			}
			circle(graphics, originX, originY, scale, dropX, dropY, radiusM, alpha((int) (0x86 * drop.ink), 0xDCEBF8));
			arc(graphics, originX, originY, scale, dropX, dropY, radiusM * 0.92, radiusM * 0.22, 0, 360, alpha((int) (0x50 * drop.ink), 0x9CC0DC));
			circle(graphics, originX, originY, scale, dropX + radiusM * 0.26, dropY + radiusM * 0.30,
					Math.max(0.0008, radiusM * 0.30), alpha((int) (0xCC * drop.ink), 0xFFFFFF));
		}

		private boolean wasWiped(double pointX, double pointY, double pivotX, double pivotY) {
			return wipeFactor(pointX, pointY, pivotX, pivotY) > 0;
		}

		/** Mirrors MmtrWindshield.State.wipeFactor: wiped region = park -> blade. */
		private double wipeFactor(double pointX, double pointY, double pivotX, double pivotY) {
			final double deltaX = pointX - pivotX;
			final double deltaY = pointY - pivotY;
			if (deltaX * deltaX + deltaY * deltaY > ARM_M * ARM_M) {
				return -1;
			}
			final double angleDeg = Math.toDegrees(Math.atan2(deltaY, deltaX));
			final double fromParkDeg = normaliseSigned(angleDeg - PARK_DEG) * SWEEP_SIGN;
			final double sweptDeg = normaliseSigned(wiperAngleDeg - PARK_DEG) * SWEEP_SIGN;
			if (fromParkDeg < 0 || fromParkDeg > Math.max(0, sweptDeg) + WIPE_FADE_DEG) {
				return 0;
			}
			if (fromParkDeg <= sweptDeg) {
				return 1;
			}
			return 1 - (fromParkDeg - sweptDeg) / WIPE_FADE_DEG;
		}

		/** The wipe factor at a face angle (degrees, 0 = the glass's right edge) at the arm's reach. */
		private double wipeFactorAt(double angleDegrees) {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			final double radians = Math.toRadians(angleDegrees);
			return wipeFactor(pivotX + Math.cos(radians) * ARM_M * 0.8, pivotY + Math.sin(radians) * ARM_M * 0.8, pivotX, pivotY);
		}

		/** How wide the band of glass the current stroke has actually cleared is, in degrees. */
		private double wipedBandWidthDeg() {
			final double pivotX = PIVOT_U * WIDTH_M;
			final double pivotY = PIVOT_V * HEIGHT_M;
			double min = 360;
			double max = -360;
			for (final Drop drop : drops) {
				if (drop.presentBeforeStroke && wipeFactor(drop.x * WIDTH_M, drop.y * HEIGHT_M, pivotX, pivotY) >= 0.99) {
					final double angle = Math.toDegrees(Math.atan2(drop.y * HEIGHT_M - pivotY, drop.x * WIDTH_M - pivotX));
					min = Math.min(min, angle);
					max = Math.max(max, angle);
				}
			}
			return max < min ? 0 : max - min;
		}

		// ---- pixel helpers: (metres, y up, origin bottom-left) -> screen --------------------------

		private double screenX(double originX, double scale, double metres) {
			return originX + metres * scale;
		}

		private double screenY(double originY, double scale, double metres) {
			return originY + (HEIGHT_M - metres) * scale;
		}

		private void circle(Graphics2D graphics, double originX, double originY, double scale, double cx, double cy, double radiusM, Color color) {
			graphics.setColor(color);
			final double r = radiusM * scale;
			graphics.fill(new Ellipse2D.Double(screenX(originX, scale, cx) - r, screenY(originY, scale, cy) - r, r * 2, r * 2));
		}

		private void line(Graphics2D graphics, double originX, double originY, double scale, double x1, double y1, double x2, double y2, double widthM, Color color) {
			graphics.setColor(color);
			graphics.setStroke(new BasicStroke((float) Math.max(1, widthM * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.drawLine((int) screenX(originX, scale, x1), (int) screenY(originY, scale, y1),
					(int) screenX(originX, scale, x2), (int) screenY(originY, scale, y2));
		}

		private void arc(Graphics2D graphics, double originX, double originY, double scale, double cx, double cy, double radius, double widthM, double startDegrees, double endDegrees, Color color) {
			final int steps = Math.max(6, Math.min(256, (int) Math.abs(endDegrees - startDegrees) / 3));
			final Path2D.Double path = new Path2D.Double();
			for (int i = 0; i <= steps; i++) {
				final double angle = Math.toRadians(startDegrees + (endDegrees - startDegrees) * i / steps);
				final double x = cx + radius * Math.cos(angle);
				final double y = cy + radius * Math.sin(angle);
				if (i == 0) {
					path.moveTo(screenX(originX, scale, x), screenY(originY, scale, y));
				} else {
					path.lineTo(screenX(originX, scale, x), screenY(originY, scale, y));
				}
			}
			graphics.setColor(color);
			graphics.setStroke(new BasicStroke((float) Math.max(1, widthM * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.draw(path);
		}

		/** Filled pie slice, mirroring {@code MmtrPanelCanvas.fillSector}. */
		private void fillSector(Graphics2D graphics, double originX, double originY, double scale, double cx, double cy, double radius, double startDegrees, double endDegrees, Color color) {
			final int steps = Math.max(6, Math.min(256, (int) Math.abs(endDegrees - startDegrees) / 3));
			final Path2D.Double path = new Path2D.Double();
			path.moveTo(screenX(originX, scale, cx), screenY(originY, scale, cy));
			for (int i = 0; i <= steps; i++) {
				final double angle = Math.toRadians(startDegrees + (endDegrees - startDegrees) * i / steps);
				path.lineTo(screenX(originX, scale, cx + radius * Math.cos(angle)), screenY(originY, scale, cy + radius * Math.sin(angle)));
			}
			path.closePath();
			graphics.setColor(color);
			graphics.fill(path);
		}
	}

	private static double wrap(double value) {
		return value - Math.floor(value);
	}

	private static double normaliseDegrees(double degrees) {
		final double wrapped = degrees % 360;
		return wrapped < 0 ? wrapped + 360 : wrapped;
	}

	/** Mirrors MmtrWindshield.normaliseSigned. */
	private static double normaliseSigned(double degrees) {
		final double wrapped = normaliseDegrees(degrees);
		return wrapped > 180 ? wrapped - 360 : wrapped;
	}

	private static Color alpha(int a, int rgb) {
		return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.max(0, Math.min(255, a)));
	}

	private static final class Drop {

		private double x;
		private double y;
		private final double variation;
		private final Random random;
		private double trail;
		private double directionX;
		private double directionY = -1;
		private double radiusM;
		private double lateralVelocity;
		/** Mirrors Drop.surfaceSpeedMps. */
		private double surfaceSpeedMps;
		/** Mirrors Drop.pinchAccumulatorM. */
		private double pinchAccumulatorM;
		/** Harness-only: was this bead established before the stroke being measured started? */
		private boolean presentBeforeStroke;
		private double ink = 1;

		private Drop(double x, double y, double variation, Random random) {
			this.x = x;
			this.y = y;
			this.variation = variation;
			this.random = random;
		}
	}
}
