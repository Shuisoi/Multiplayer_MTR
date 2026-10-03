import java.util.Random;

/**
 * Offline harness for the DENSITY-DRIVEN droplet motion: "the water only runs once there is enough of
 * it, the blade pushes what it crosses, and the pushed pile is what runs down".
 *
 * <p>It mirrors the arithmetic of {@code MmtrWindshield} - {@code measureDensity}, the pinning latch in
 * {@code advanceDrop}, the shove in {@code applyWipe} and the band wipe test - on the REAL BR101 cab
 * glass and the REAL packed wiper geometry, read out of
 * {@code mmtr_anchors_br101.json}. It renders nothing and has no wetness field: the point is numbers,
 * because every claim this model makes is a number (how crowded is a patch, how fast does it run, how
 * far does the blade move the water) and "it looks about right" cannot be checked in a screenshot.</p>
 *
 * <p><b>What is deliberately NOT here.</b> The wiper's four-bar loop closure is only summarised
 * ({@code bladeSegmentM} in the client solves it exactly); the drawing, the canvas, the texture and the
 * sheet-to-face mapping are all absent. If a check here fails, the client is wrong; if every check here
 * passes, the client is still not proven - {@link WiperPreview} is the harness that speaks about the
 * picture, and it mirrors the OLD pre-density model (see its header).</p>
 *
 * <p>Run (from the workspace root):</p>
 * <pre>
 *   &amp; "$env:JAVA_HOME\bin\javac.exe" -d sandbox\dropletflow mmtr\tools\wiper-preview\DropletFlow.java
 *   &amp; "$env:JAVA_HOME\bin\java.exe" -cp sandbox\dropletflow DropletFlow
 * </pre>
 */
public final class DropletFlow {

	// ---- mirrored from MmtrWindshield -------------------------------------------------------------
	private static final double BALANCE_SPEED_MPS = 8.33;
	private static final double REFERENCE_GRAVITY_MPS2 = 0.55 * (0.6 + 0.9 * 1.0);
	private static final double AIRFLOW_COEFFICIENT = REFERENCE_GRAVITY_MPS2 / (BALANCE_SPEED_MPS * BALANCE_SPEED_MPS);
	/** panelDown() returns a UNIT vector, so the airflow term's slope factor is 1 on any raked glass. */
	private static final double SLOPE = 1.0;
	private static final double MAX_LATERAL_ACCELERATION = 1.2;
	private static final double MAX_LATERAL_VELOCITY = 0.45;
	private static final double TRAIL_DRY_MPS = 0.02;
	private static final double PINCH_OFF_DISTANCE_M = 0.035;
	private static final double RUNOFF_MARGINAL_FACTOR = 0.4;
	private static final double BOW_WAVE_FRACTION = 0.5;
	private static final double BAND_INFLATE_M = 0.003;
	private static final double WIPE_FADE_M = 0.03;
	private static final double SLOW_PERIOD_S = 1.55;
	private static final double SLOW_DWELL_S = 1.0;

	// ---- the BR101 cab windscreen, from assets/mtr/mmtr_anchors_br101.json -------------------------
	private static final double WIDTH_M = 1.1516;
	private static final double HEIGHT_M = 1.06;
	private static final int RAINDROPS = 140;
	private static final double FALL_MPS = 0.75;
	private static final double CREEP_MPS = 0.09;
	private static final double JITTER_MPS = 0.008;
	private static final double MIN_BEAD_RADIUS_M = 0.0035;
	private static final double MAX_BEAD_RADIUS_M = 0.014;
	private static final double GROWTH_MPS = 0.0012;
	private static final double STATIC_THRESHOLD_M = 0.0038;
	private static final double SPAWN_POPULATION_PER_SECOND = 0.25;
	private static final double MERGE_DISTANCE_M = 0.012;
	private static final double DENSITY_CELL_M = 0.05;
	private static final double DENSITY_THRESHOLD_PER_M2 = 130;
	private static final double RUNOFF_MPS = 0.45;
	private static final double PUSH_M = 0.02;
	/** Mirrors WindshieldConfig.collectZoneM: how deep the collection zone at each end of the stroke is. */
	private static final double COLLECT_ZONE_M = 0.15;

	private static final double PIVOT_U = 0.5326;
	private static final double PIVOT_V = -0.1915;
	private static final double PIVOT2_U = 0.4641;
	private static final double PIVOT2_V = -0.1915;
	private static final double BLADE_AU = 0.9649;
	private static final double BLADE_AV = 0.1128;
	private static final double BLADE_BU = 0.9761;
	private static final double BLADE_BV = 0.7781;
	private static final double PIN_AU = 0.9855;
	private static final double PIN_AV = 0.3949;
	private static final double PIN_BU = 0.9169;
	private static final double PIN_BV = 0.3943;
	private static final double PARK_ANGLE_DEG = 88.944;
	private static final double SWEEP_DEG = 80;
	private static final double SWEEP_SIGN = 1;
	/** The sector (one-pivot) variant's synthetic wiper, for the path BR101 does not use. */
	private static final double SECTOR_PARK_DEG = 0;
	private static final double SECTOR_SWEEP_DEG = 88;
	private static final double SECTOR_PIVOT_U = 0.5;
	private static final double SECTOR_PIVOT_V = 0.0;
	private static final double SECTOR_ARM_M = Math.min(WIDTH_M, HEIGHT_M) / 2;
	private static final double WIPE_FADE_DEG = 14;

	public static void main(String[] args) {
		int failures = 0;
		System.out.println("=== DropletFlow: density-gated runoff and the wiper's shove ===");
		System.out.printf("glass %.4f x %.4f m = %.4f m2, %d beads, threshold %.0f/m2, runoff %.2f m/s, push %.3f m%n",
				WIDTH_M, HEIGHT_M, WIDTH_M * HEIGHT_M, RAINDROPS, DENSITY_THRESHOLD_PER_M2, RUNOFF_MPS, PUSH_M);
		System.out.printf("probe: 3x3 cells of %.3f m = %.5f m2, so 1/2/3 beads in a patch read %.0f / %.0f / %.0f per m2%n",
				DENSITY_CELL_M, 9 * DENSITY_CELL_M * DENSITY_CELL_M,
				1 / (9 * DENSITY_CELL_M * DENSITY_CELL_M), 2 / (9 * DENSITY_CELL_M * DENSITY_CELL_M),
				3 / (9 * DENSITY_CELL_M * DENSITY_CELL_M));
		System.out.println();

		// ---- 0. the probe itself -----------------------------------------------------------------
		// Before any claim about "crowded", the measurement has to agree with a layout whose answer is
		// known by hand: a uniform field's bead should see 1 + 9 * (beads/cell) beads in its 3x3 block.
		{
			final Glass uniform = new Glass(new Random(4321));
			for (final Drop drop : uniform.drops) {
				drop.visible = true;
			}
			uniform.measureDensity();
			final double globalDensity = RAINDROPS / (WIDTH_M * HEIGHT_M);
			final double cells = Math.ceil(1 / (DENSITY_CELL_M / WIDTH_M)) * Math.ceil(1 / (DENSITY_CELL_M / HEIGHT_M));
			final double expected = (1 + 9 * RAINDROPS / cells) / (9 * DENSITY_CELL_M * DENSITY_CELL_M);
			System.out.printf("  probe check: uniform %d beads = %.0f/m2 globally; expected %.0f/m2, measured %.0f/m2%n",
					RAINDROPS, globalDensity, expected, uniform.meanDensity());
			System.out.println();
			failures += expect("密度探针与手算一致（均匀场 ±15%）",
					Math.abs(uniform.meanDensity() - expected) / expected < 0.15,
					String.format("expected=%.0f measured=%.0f", expected, uniform.meanDensity()));
		}
		// ---- 1. the density ladder: drizzle pins, a downpour runs --------------------------------
		final Glass drizzle = settled(0.3F);
		final Glass shower = settled(0.6F);
		final Glass downpour = settled(1.0F);
		System.out.printf("  downpour patch occupancy (beads per 15 cm square): %s offGlass=%d%n",
				downpour.blockHistogram(), downpour.countOffGlass());
		System.out.printf("  intensity 0.30: alive=%3d mean=%5.0f/m2 max=%5.0f/m2 flowing=%3d (%4.1f%%) speed(flowing)=%.3f m/s%n",
				drizzle.alive(), drizzle.meanDensity(), drizzle.maxDensity(), drizzle.flowing(),
				100.0 * drizzle.flowing() / Math.max(1, drizzle.alive()), drizzle.meanSpeedOfFlowing());
		System.out.printf("  intensity 0.60: alive=%3d mean=%5.0f/m2 max=%5.0f/m2 flowing=%3d (%4.1f%%) speed(flowing)=%.3f m/s%n",
				shower.alive(), shower.meanDensity(), shower.maxDensity(), shower.flowing(),
				100.0 * shower.flowing() / Math.max(1, shower.alive()), shower.meanSpeedOfFlowing());
		System.out.printf("  intensity 1.00: alive=%3d mean=%5.0f/m2 max=%5.0f/m2 flowing=%3d (%4.1f%%) speed(flowing)=%.3f m/s%n",
				downpour.alive(), downpour.meanDensity(), downpour.maxDensity(), downpour.flowing(),
				100.0 * downpour.flowing() / Math.max(1, downpour.alive()), downpour.meanSpeedOfFlowing());
		System.out.println();

		failures += expect("小雨（0.3）几乎没有水流下来（< 12% 的珠子所在的小块达标）",
				drizzle.flowingFraction() < 0.12,
				String.format("flowing=%.3f of %d beads, max density %.0f/m2 (threshold %.0f)",
						drizzle.flowingFraction(), drizzle.alive(), drizzle.maxDensity(), DENSITY_THRESHOLD_PER_M2));
		failures += expect("暴雨（1.0）有成形的水流（> 15% 且在跑）",
				downpour.flowingFraction() > 0.15 && downpour.movers() > 0,
				String.format("flowing=%.3f movers=%d meanSpeed=%.3f m/s",
						downpour.flowingFraction(), downpour.movers(), downpour.meanSpeedOfFlowing()));
		failures += expect("雨越大，流动的水只多不少（0.3 ≤ 0.6 ≤ 1.0）",
				drizzle.flowingFraction() <= shower.flowingFraction() && shower.flowingFraction() <= downpour.flowingFraction()
						&& downpour.flowingFraction() > 0.15,
				String.format("%.3f <= %.3f <= %.3f", drizzle.flowingFraction(), shower.flowingFraction(), downpour.flowingFraction()));
		failures += expect("不论雨多大，总有一批珠子被钉住不动（不是整屏一起流）",
				downpour.flowingFraction() < 0.90,
				String.format("flowing=%.3f", downpour.flowingFraction()));

		// A LONE BEAD must never move: this is the single-bead limit of the density rule, and the reason
		// the rule replaced the old per-bead radius gate (which pinned everything in light rain). Run at
		// the rain intensity the spawner treats as dry, so the glass really does stay at one bead.
		//
		// This is not hypothetical: the first version of the latch multiplied the speed by the excess over
		// the threshold WITHOUT checking the sign, and this check failed with "a lone bead drifts 0.13 m/s"
		// - the latch was dead code and every bead on the glass was running.
		final Glass lonely = new Glass(new Random(5));
		lonely.keepOnlyOneForTest();
		lonely.simulate(10, 0, 0.02F, Mode.OFF);
		failures += expect("孤立的一滴雨在停车时纹丝不动（密度规则的单珠极限）",
				lonely.alive() == 1 && lonely.movers() == 0,
				String.format("alive=%d movers=%d density=%.0f/m2", lonely.alive(), lonely.movers(), lonely.meanDensity()));

		// ---- 2. the shove ------------------------------------------------------------------------
		// Measured INSIDE the wipe, where the push happens, so the displacement cannot be confused with
		// the runoff that the same frame's advance already applied.
		final Glass push = settled(1.0F);
		push.mode = Mode.SLOW;
		final PushReport report = push.strokeAndMeasurePush(SLOW_PERIOD_S / 2);
		System.out.printf("  one out-stroke (%.2f s, %.0f deg): %d carry events on %d distinct beads, "
						+ "carried %.1f mm each, %d off the glass%n",
				SLOW_PERIOD_S / 2, SWEEP_DEG, report.eventCount, report.pushed,
				report.meanPushMm(), report.pushedOff);
		System.out.printf("  glass mean density %.0f/m2 before, %.0f/m2 after; the gathered line: mean %.0f/m2 "
						+ "(max %.0f), running at %.3f m/s%n",
				report.fieldMeanDensityBefore, report.fieldMeanDensityAfter,
				report.windrowMeanDensity, report.windrowMaxDensity, report.windrowSpeed);
		System.out.println();

		failures += expect("雨刷确实把雨滴推走并留住（不是删掉，也不是原地不动）",
				report.pushed > 0 && report.pushedOff < report.pushed && report.meanPushMm() > 5,
				String.format("carried=%d keptOff=%d offGlass=%d mean=%.1f mm",
						report.pushed, report.pushed + report.pushedOff, report.pushedOff, report.meanPushMm()));
		// THE WINDROW: gathering has to raise the local density above the glass's own average, and past the
		// threshold, or the third link of the chain ("the pile is too much water to stay put") cannot fire.
		failures += expect("刀推出来的水挤成一条，密度明显高于刮之前整块玻璃",
				report.windrowMeanDensity > report.fieldMeanDensityBefore * 1.4,
				String.format("windrow=%.0f/m2 vs glass-before=%.0f/m2 (glass after %.0f, max windrow %.0f)",
						report.windrowMeanDensity, report.fieldMeanDensityBefore, report.fieldMeanDensityAfter,
						report.windrowMaxDensity));
		failures += expect("推出来的水带已经越过密度阈值并开始流动（密度 > 阈值、速度 > 0.15 m/s）",
				report.windrowMeanDensity > DENSITY_THRESHOLD_PER_M2 && report.windrowSpeed > 0.15,
				String.format("density=%.0f/m2 (threshold %.0f) speed=%.3f m/s",
						report.windrowMeanDensity, DENSITY_THRESHOLD_PER_M2, report.windrowSpeed));

		// ---- 3. the windrow runs -----------------------------------------------------------------
		// The third link of the chain, and the only one that cannot be read off a single frame: with the
		// blade parked again, the pushed beads must travel DOWN while the beads outside the windrow stay
		// where they were. This is what "pushed water is too much water to stay put" means numerically.
		final RunReport run = push.measureWindrowRun(1.0);
		System.out.printf("  1.0 s with the blade parked: %d windrow beads travelled %.1f mm at %.3f m/s mean; "
						+ "%d untouched beads %.1f mm at %.3f m/s%n",
				run.windrowCount, run.windrowTravelMm, run.windrowSpeed,
				run.otherCount, run.otherTravelMm, run.otherSpeed);
		System.out.println();
		failures += expect("刀推出来的水带在刀停下后继续往下流（> 15 mm/s）",
				run.windrowSpeed > 0.15,
				String.format("windrow speed=%.3f m/s", run.windrowSpeed));
		failures += expect("水带比周围没被推到的玻璃流得快（至少 2 倍）",
				run.windrowSpeed > 2 * Math.max(1.0E-6, run.otherSpeed),
				String.format("windrow=%.3f m/s vs other=%.3f m/s", run.windrowSpeed, run.otherSpeed));

		// ---- 4. a MODERATE shower: the glass alone is static, the blade's water is not -------------
		// The sharpest statement of the whole chain, and the one that separates this model from "the rain
		// runs when it is heavy": at 0.6 intensity the field never reaches the threshold anywhere (measured
		// above: 0% flowing, max patch 89/m2), yet the water the blade GATHERS still does. So the wiper is
		// not a decoration on a rain effect - it is what makes water run in moderate weather.
		final Glass showerWipe = settled(0.6F);
		final double showerFieldFlowing = showerWipe.flowingFraction();
		showerWipe.mode = Mode.SLOW;
		final PushReport showerReport = showerWipe.strokeAndMeasurePush(SLOW_PERIOD_S / 2);
		final RunReport showerRun = showerWipe.measureWindrowRun(1.0);
		System.out.printf("  0.60 shower + one pass: glass alone flowing %.1f%%; the gathered water %.0f/m2 "
						+ "travelled %.1f mm in 1 s, the glass the blade never touched %.1f mm (speed at the end "
						+ "of the second: %.3f vs %.3f m/s)%n",
				100 * showerFieldFlowing, showerReport.windrowMeanDensity, showerRun.windrowTravelMm,
				showerRun.otherTravelMm, showerRun.windrowSpeed, showerRun.otherSpeed);
		System.out.println();
		// The criterion is DISTANCE RUN, not the instantaneous speed at the end of the window. The pile is
		// self-regulating (see note 206 §2): once it starts moving it drains, its local density falls back
		// to the threshold and it stops - so the end-of-window speed of a pile that has just run 95 mm is
		// small, and requiring a large one would be measuring the wrong thing. What the claim needs is that
		// the gathered water RUNS (distance) and that it runs faster than the glass the blade never worked.
		//
		// MEASURED AFTER THE SWEPT-BAND FIX (see section 9): 94.5 mm against 64.8 mm, i.e. 1.46x. Before the
		// fix the same probe read 0.192 m/s and 2.2x, because the transposed band test was feeding the
		// "windrow" set beads the blade was nowhere near - integers worth having, but they were the bug's
		// numbers, not the model's. 1.3x is what the causal claim actually needs (gathering must make the
		// water run faster than the glass it was gathered from); the strong version of the claim, 2.9x at
		// full intensity, is asserted in section 3.
		failures += expect("中雨时玻璃本身不动，但雨刷推出来的水会流（1 s 内 ≥ 50 mm，且快于没被刮到的玻璃）",
				showerFieldFlowing < 0.05 && showerReport.windrowMeanDensity > 100
						&& showerRun.windrowTravelMm > 50
						&& showerRun.windrowTravelMm > 1.3 * Math.max(1.0E-6, showerRun.otherTravelMm),
				String.format("glass flowing=%.3f, windrow=%.0f/m2 travelled %.1f mm vs untouched %.1f mm",
						showerFieldFlowing, showerReport.windrowMeanDensity,
						showerRun.windrowTravelMm, showerRun.otherTravelMm));

		// ---- 5. the SECTOR path (one pivot, no modelled blade) ------------------------------------
		// BR101 is a parallel linkage, so nothing above exercises the sector branch of applyWipe - and that
		// branch is the one every other model in the pack uses. Its first version silently did NOTHING,
		// because the blade's advance was read from a modelled blade that does not exist there: the shove
		// came out as min(0, ...) = 0 for every bead.
		final Glass sector = Glass.sector(new Random(20260102));
		sector.simulate(20, 0, 1.0F, Mode.OFF);
		final double sectorFlowingBefore = sector.flowingFraction();
		sector.mode = Mode.SLOW;
		final PushReport sectorReport = sector.strokeAndMeasurePush(SLOW_PERIOD_S / 2);
		final RunReport sectorRun = sector.measureWindrowRun(1.0);
		System.out.printf("  sector (one pivot, no modelled blade): glass flowing %.1f%% -> one pass carried %d beads "
						+ "(%.1f mm each), gathered %.0f/m2, runs at %.3f m/s (%.1f mm in 1 s)%n",
				100 * sectorFlowingBefore, sectorReport.pushed, sectorReport.meanPushMm(),
				sectorReport.windrowMeanDensity, sectorRun.windrowSpeed, sectorRun.windrowTravelMm);
		System.out.println();
		failures += expect("单轴（扇形）雨刷同样把水推走并收成水带",
				sectorReport.pushed > 0 && sectorReport.windrowMeanDensity > sectorReport.fieldMeanDensityBefore * 1.2
						&& sectorRun.windrowTravelMm > 50,
				String.format("carried=%d (%.1f mm each), windrow=%.0f/m2 vs glass %.0f/m2, travelled %.1f mm in 1 s",
						sectorReport.pushed, sectorReport.meanPushMm(), sectorReport.windrowMeanDensity,
						sectorReport.fieldMeanDensityBefore, sectorRun.windrowTravelMm));

		// ---- 6. PLOUGHING: the blade CARRIES the water one way, it does not nudge it -----------------
		// The defect this section exists for: the blade used to cross each bead ONCE and shove it by
		// PUSH_M (20 mm). Two consequences, both measured below. A rigid one-shot shove preserves the
		// spacing of the beads it moves, so it does not gather anything into a pile; and because PUSH_M is
		// larger than the blade's own per-frame advance, the shove OVERTAKES the blade, leaving the water
		// in glass the blade has already cleared. In game that reads as "the blade pushes the water a
		// little to one side and then nothing".
		//
		// The metric is distance CARRIED, not displacement: the bead ridden by the blade accumulates the
		// blade's travel while the shove model accumulates one 20 mm nudge. Both models run on identical
		// settled glass with the same blade, which is what makes the comparison mean anything.
		final Glass nudgeModel = settled(1.0F);
		nudgeModel.noCarry = true;
		final PushReport nudge = nudgeModel.strokeAndMeasurePush(SLOW_PERIOD_S / 2, 0);

		final Glass plough = settled(1.0F);
		final PushReport out = plough.strokeAndMeasurePush(SLOW_PERIOD_S / 2, 0);
		System.out.printf("  PLOUGH out-stroke: blade travelled %.0f mm; %d beads riding the wave at the end; "
						+ "beads the blade touched were carried %.1f mm each (%.0f%% of the blade's travel)%n",
				out.bladeTravelM * 1000, out.waveCount, out.meanCarriedM * 1000, out.carryFraction() * 100);
		System.out.printf("  carry spread %s; %d touched beads were moved but never carried%n",
				out.carriedSpread(), out.noCarryCount);
		System.out.printf("  wave diagnostic: peak %d beads, %d released during the stroke, %d at the end%n",
				out.wavePeak, out.waveReleasedTotal, out.waveReleased);
		System.out.printf("  beads per third (park side / middle / far side): %s -> %s%n",
				PushReport.thirds(out.thirdsBefore), PushReport.thirds(out.thirdsAfter));
		System.out.printf("  the model this replaced (one-shot %.0f mm shove, no carry): carried %.1f mm each "
						+ "(%.1f%% of the blade's travel), thirds %s -> %s%n",
				PUSH_M * 1000, nudge.meanCarriedM * 1000, nudge.carryFraction() * 100,
				PushReport.thirds(nudge.thirdsBefore), PushReport.thirds(nudge.thirdsAfter));
		System.out.println();
		// The claim is "the blade CARRIES the water instead of nudging it", and the honest way to state it is
		// the CARRY SPREAD, not the mean. A bead picked up at the start of the stroke must be carried the
		// whole stroke; a bead picked up halfway can only be carried the remaining half, so a plough that
		// picks water up uniformly along its path MUST average about half the stroke - a mean near 100% is
		// not better, it is impossible. The mean fell from 54% to 47% when carried water stopped also
		// obeying gravity (see the carry probe): before that, a carried bead collected the field's own
		// down-slope motion on top of the blade's - real distance, but distance the BLADE did not carry.
		// So: the far end of the spread proves the full-length carry, and the mean only has to be
		// systematically more than a shove (the one-shot model scores 0.0 mm).
		failures += expect("刀片把水一路带着走：拾取最早的水被带到行程末端（最远 ≥ 行程的 90%）",
				out.carriedSamplesM.length > 0
						&& out.carriedSamplesM[out.carriedSamplesM.length - 1] > 0.9 * out.bladeTravelM,
				String.format("max carried %.0f mm of %.0f mm travel (%s)",
						out.carriedSamplesM.length == 0 ? 0 : out.carriedSamplesM[out.carriedSamplesM.length - 1] * 1000,
						out.bladeTravelM * 1000, out.carriedSpread()));
		failures += expect("平均携带 ≈ 行程的一半（均匀拾取的必然结果，≥ 35%）",
				out.carryFraction() > 0.35 && out.waveCount > 0,
				String.format("mean carried=%.1f mm of %.0f mm travel = %.0f%% (wave=%d beads)",
						out.meanCarriedM * 1000, out.bladeTravelM * 1000, out.carryFraction() * 100, out.waveCount));
		failures += expect("带水远多于旧版一次性推挤（≥ 5 倍）",
				nudge.meanCarriedM <= 0 || out.meanCarriedM > 5 * nudge.meanCarriedM,
				String.format("carry=%.1f mm vs one-shot=%.1f mm", out.meanCarriedM * 1000, nudge.meanCarriedM * 1000));
		// Where the water WENT. Only the beads that were already on the glass before the stroke are counted:
		// the spawner puts ~26 fresh beads on the glass during a 0.78 s stroke, and a raw count per third
		// cannot tell those from water the blade carried. Generation-tracking is what makes this a statement
		// about transport rather than about total population.
		// The blades in this pack travel from the PARK side toward the FAR side, and the thirds are counted
		// from low coordinate to high, so the water the blade carries ends up in the LOW-index third. Getting
		// this backwards is easy and was: the first version of these assertions read the park side as the
		// "far" one because it assumed the index order matched the direction of travel.
		failures += expect("刮过去之后玻璃中段的水被搬走（原有的珠子在中段变少）",
				out.oldThirdAfter[1] < out.oldThirdBefore[1],
				String.format("middle %d -> %d of the beads that were there before the stroke",
						out.oldThirdBefore[1], out.oldThirdAfter[1]));
		failures += expect("水被搬到刀停下的那一侧（远端三分之一里原有的珠子变多）",
				out.oldThirdAfter[0] > out.oldThirdBefore[0],
				String.format("far side %d -> %d", out.oldThirdBefore[0], out.oldThirdAfter[0]));
		failures += expect("一整条行程走完，水沿行程方向净移动到了一侧（不是原地来回）",
				out.oldThirdAfter[0] > out.oldThirdAfter[2] && out.oldThirdAfter[0] > out.oldThirdAfter[1],
				String.format("of the pre-stroke beads: far side %d, middle %d, park side %d",
						out.oldThirdAfter[0], out.oldThirdAfter[1], out.oldThirdAfter[2]));
		System.out.printf("  [probe] travel direction (%.2f, %.2f); old thirds before %s, after %s%n",
				out.travelAxisX, out.travelAxisY, PushReport.thirds(out.oldThirdBefore), PushReport.thirds(out.oldThirdAfter));

		// ---- 7. THE TURNAROUND: the pile is put down, not shaken in place ---------------------------
		// The other half of "carry it to one side": when the blade reverses it must LET GO of the water it
		// ploughed, or the return stroke drags the whole pile back and a full cycle nets out to nothing.
		final Glass turn = settled(1.0F);
		final Glass.BackReport cycle = turn.outAndBackProbe();
		System.out.printf("  out and back: %d released when the blade reversed; of the beads already on the glass, "
						+ "thirds %s -> %s at the turnaround, then %s after the return%n",
				cycle.released, PushReport.thirds(cycle.oldThirdBefore),
				PushReport.thirds(cycle.oldThirdAtTurnaround), PushReport.thirds(cycle.thirdsAfter));
		System.out.println();
		// The blade STOPS at the end of a stroke and then comes back. A blade that is not travelling, or
		// that has turned around, must let go - otherwise it holds the water against the glass and drags it
		// wherever it goes, which is what "it only pushes the water a little" looked like.
		failures += expect("刀反向时把水放下，不再拖着走",
				cycle.released > 0,
				String.format("released=%d beads at the turnaround", cycle.released));
		// THE PLOUGH ITSELF: at the end of the outward stroke the water the blade was carrying must be on
		// the FAR side, and there must be less of it in the middle than there was. (After the RETURN stroke
		// the pile is largely brought back - that is what a real wiper does - so the return is not the
		// measurement; the pile at the turnaround is.)
		failures += expect("行程末端时，被刮走的水已经集中在刀去的那一侧（远端多于中段和起始侧）",
				cycle.oldThirdAtTurnaround[0] > cycle.oldThirdAtTurnaround[1]
						&& cycle.oldThirdAtTurnaround[0] > cycle.oldThirdAtTurnaround[2],
				String.format("of the pre-stroke beads at the turnaround: far %d, middle %d, park %d",
						cycle.oldThirdAtTurnaround[0], cycle.oldThirdAtTurnaround[1], cycle.oldThirdAtTurnaround[2]));
		failures += expect("刮完之后玻璃中段被清空了一半以上（原有珠子）",
				cycle.oldThirdAtTurnaround[1] * 2 < cycle.oldThirdBefore[1],
				String.format("middle %d -> %d", cycle.oldThirdBefore[1], cycle.oldThirdAtTurnaround[1]));

		// ---- 8. THE SECOND RENDER PASS: a step with no travel is not a turnaround -------------------
		// The client reaches advance() once per RENDER PASS, and a cab drawn both from the outside and from
		// the inside gets two passes carrying ONE System.currentTimeMillis(): the second is handed a clock
		// that has not moved, so the arm does not move either and the whole step is a no-op. The release
		// rule used to read "the blade is not travelling" as "the blade has turned around" and put the
		// ENTIRE wave down on the spot. In game that is not a rare edge: logs/latest.log, BR101 cab 2, 2172
		// blade-moving steps, 561 of them (25.8%) were these repeats, 501 of those dumped water worth 2031
		// beads - 97.8% of every release in the whole session, against 11 genuine turnarounds. Whether a
		// given frame gets one pass or two depends only on whether the two fall inside the same
		// millisecond, which is exactly why the symptom came and went.
		//
		// Three runs of the SAME glass and the SAME stroke: as the client steps it now (one pass), with the
		// second pass interleaved under the OLD rule, and with it interleaved under this fix. The old rule
		// is the model this commit replaces, present in the harness verbatim, so the comparison is measured
		// rather than remembered.
		final Glass onePass = settled(1.0F, false);
		final PushReport onePassReport = onePass.strokeAndMeasurePush(SLOW_PERIOD_S / 2, 0);

		final Glass stallRule = settled(1.0F, false);
		stallRule.duplicatePass = true;
		stallRule.releaseOnStall = true;
				final PushReport stallReport = stallRule.strokeAndMeasurePush(SLOW_PERIOD_S / 2, 0);

		final Glass passRule = settled(1.0F, false);
		passRule.duplicatePass = true;
				final PushReport passReport = passRule.strokeAndMeasurePush(SLOW_PERIOD_S / 2, 0);

		System.out.println("  two render passes inside one frame (one stroke, identical glass):");
		System.out.printf("    one pass only:            carried %.1f mm each over %.0f mm of blade travel (%.0f%%); "
						+ "wave peak %d; released %d (%d mid-stroke)%n",
				onePassReport.meanCarriedM * 1000, onePassReport.bladeTravelM * 1000,
				onePassReport.carryFraction() * 100, onePassReport.wavePeak,
				onePass.strokeReleaseTotal, onePass.midStrokeReleaseCount);
		System.out.printf("    + duplicate, OLD rule:    carried %.1f mm each (%.0f%%); wave peak %d; "
						+ "released %d (%d mid-stroke)%n",
				stallReport.meanCarriedM * 1000, stallReport.carryFraction() * 100, stallReport.wavePeak,
				stallRule.strokeReleaseTotal, stallRule.midStrokeReleaseCount);
		System.out.printf("    + duplicate, THIS fix:    carried %.1f mm each (%.0f%%); wave peak %d; "
						+ "released %d (%d mid-stroke)%n",
				passReport.meanCarriedM * 1000, passReport.carryFraction() * 100, passReport.wavePeak,
				passRule.strokeReleaseTotal, passRule.midStrokeReleaseCount);
		// THE TRANSPORT CHECK, and the reason the carry figure above is not trusted on its own. A bead the
		// old rule puts down on a duplicate pass is still sitting on the blade's line, so the NEXT real step
		// can pick it up again through the "bead behind the leading line" branch - which moves it by the
		// blade's advance but scores it as a shove, not as carry. So "carried 0.0 mm" could be the metric
		// rather than the water. The thirds are generation-tracked bead COUNTS, which no metric can fake:
		// they say where the water that was on the glass before the stroke actually ended up.
		System.out.printf("    pre-stroke beads per third (park/middle/far): before %s | one pass %s | "
						+ "old rule+duplicate %s | fix+duplicate %s%n",
				PushReport.thirds(onePassReport.oldThirdBefore), PushReport.thirds(onePassReport.oldThirdAfter),
				PushReport.thirds(stallReport.oldThirdAfter), PushReport.thirds(passReport.oldThirdAfter));
		System.out.println();
		failures += expect("重复渲染通道不再把水浪丢在原地（新规则下中途释放为 0）",
				passRule.midStrokeReleaseCount == 0,
				String.format("mid-stroke releases=%d (old rule %d)",
						passRule.midStrokeReleaseCount, stallRule.midStrokeReleaseCount));
		failures += expect("有重复通道时携带量与单通道一致（差 < 2%）",
				onePassReport.meanCarriedM > 0 && Math.abs(passReport.meanCarriedM - onePassReport.meanCarriedM)
						< 0.02 * onePassReport.meanCarriedM,
				String.format("duplicate=%.1f mm vs single=%.1f mm",
						passReport.meanCarriedM * 1000, onePassReport.meanCarriedM * 1000));
		failures += expect("有重复通道时水去的地方和单通道逐档相同（重复通道是彻底的空操作）",
				java.util.Arrays.equals(passReport.oldThirdAfter, onePassReport.oldThirdAfter),
				String.format("duplicate %s vs single %s",
						PushReport.thirds(passReport.oldThirdAfter), PushReport.thirds(onePassReport.oldThirdAfter)));
		failures += expect("旧规则会被重复通道打断搬运：中段原有的水几乎不动（中段残留 ≥ 单通道的 2 倍）",
				stallReport.oldThirdAfter[1] > 2 * onePassReport.oldThirdAfter[1],
				String.format("middle left: old rule+duplicate %d vs single %d",
						stallReport.oldThirdAfter[1], onePassReport.oldThirdAfter[1]));
		failures += expect("旧规则确实会被重复通道破坏（本次修复的回归依据）",
				stallReport.meanCarriedM < 0.5 * onePassReport.meanCarriedM || stallRule.midStrokeReleaseCount > 0,
				String.format("old rule carried %.1f mm vs single %.1f mm, %d mid-stroke releases",
						stallReport.meanCarriedM * 1000, onePassReport.meanCarriedM * 1000,
						stallRule.midStrokeReleaseCount));

		// ---- 11. THE COLLECTION ZONES: water put down at the side stays there -----------------------
		// The user's design: "a collection zone at each side of the wiper; when it reaches the end and
		// turns back, release the drops there - that avoids carrying water back and forth." Measured over
		// three consecutive out-and-back cycles, against the same glass with the zones switched off (the
		// model this replaces, present in the harness verbatim).
		//
		// The claim is about the SECOND cycle, not the first: cycle 1 has to move the water, that is the
		// wiper working. If cycle 2 moves as much as cycle 1, the water was never put down anywhere.
		final Glass zoned = settled(1.0F, false);
		final Glass.ZoneProbe zonedCycles = zoned.zoneCycleProbe(3);
		final Glass unzoned = settled(1.0F, false);
		unzoned.zoneEnabled = false;
				final Glass.ZoneProbe unzonedCycles = unzoned.zoneCycleProbe(3);
		System.out.println("  three out-and-back cycles (identical glass):");
		for (int pass = 0; pass < 3; pass++) {
			System.out.printf("    cycle %d: with zones  touched %3d beads, mean move %5.1f mm, %3d parked (%3d total)%n",
					pass + 1, zonedCycles.touched[pass], zonedCycles.meanMoveMm[pass],
					zonedCycles.parked[pass], zonedCycles.parkedTotal[pass]);
			System.out.printf("             no zones   touched %3d beads, mean move %5.1f mm%n",
					unzonedCycles.touched[pass], unzonedCycles.meanMoveMm[pass]);
		}
		System.out.println();
		failures += expect("第一趟之后水被停在收集区里（第二趟碰到的珠子明显减少）",
				zonedCycles.touched[1] * 2 < zonedCycles.touched[0],
				String.format("touched %d then %d beads", zonedCycles.touched[0], zonedCycles.touched[1]));
		// The zone's actual contract, and the only one that has to hold every time: water put down in a
		// collection zone is never picked up again. (The parked COUNT is not asserted to grow: zone water
		// runs down and off the glass like any other water, so the strip reaches a steady population rather
		// than filling up for ever. Asserting growth there would be asserting that the water never drains.)
		failures += expect("收集区里的水不会被之后的行程再次捡起（0 颗）",
				zonedCycles.reworkedParked == 0 && zonedCycles.parkedTotal[0] > 0,
				String.format("%d parked beads were picked up again; %d beads parked after the first cycle",
						zonedCycles.reworkedParked, zonedCycles.parkedTotal[0]));
		failures += expect("对照：没有收集区时每一趟都在搬同一批水（第二趟碰到的珠子不减少）",
				unzonedCycles.touched[1] * 2 >= unzonedCycles.touched[0],
				String.format("no zones touched %d then %d beads",
						unzonedCycles.touched[0], unzonedCycles.touched[1]));

		// ---- 10. DOES THE WIPER MOVE THE WHOLE SCREEN? ---------------------------------------------
		// "When the wiper sweeps outward, it drags the whole windscreen's rain sideways." That is a claim
		// about the FIELD, not about the beads the blade touches, so it is measured on the field: two
		// identical settled glasses run for the same 0.78 s, one with the wiper sweeping outward and one
		// with it OFF. Whatever the OFF run does is rain behaving like rain; the difference is the blade.
		//
		// Split into ALONG and ACROSS the blade's own direction of travel, because the two are not the same
		// claim. Carrying the water it crosses a long way ALONG its own sweep is the whole point of a
		// plough; sliding the glass ACROSS its sweep is the bug.
		final Glass wiperRun = settled(1.0F);
		final Glass.ShiftProbe shifted = wiperRun.bulkShiftProbe(SLOW_PERIOD_S / 2);
		final Glass idleRun = settled(1.0F);
		final Glass.ShiftProbe idle = idleRun.bulkShiftProbe(0, true);
		System.out.printf("  the field as a whole over %.2f s (identical glass, blade sweeping vs parked):%n",
				SLOW_PERIOD_S / 2);
		System.out.printf("    blade sweeping: %d of %d survivors moved > 10 mm, %d touched by the blade; "
						+ "bulk shift %.1f mm along the sweep, %.1f mm across it (worst %.0f / %.0f mm)%n",
				shifted.moved, shifted.survivors, shifted.touched,
				shifted.meanAlongM * 1000, shifted.meanSideM * 1000,
				shifted.worstAlongM * 1000, shifted.worstSideM * 1000);
		System.out.printf("    raw vectors: mean displacement (%.1f, %.1f) mm; measured blade push (%.2f, %.2f) "
						+ "= the split axis; untouched beads' worst along-sweep move %.1f mm%n",
				shifted.meanDxM * 1000, shifted.meanDyM * 1000, shifted.pushX, shifted.pushY,
				shifted.untouchedWorstAlongM * 1000);
		System.out.printf("    blade parked:   %d of %d survivors moved > 10 mm, %d touched; "
						+ "bulk shift %.1f mm along, %.1f mm across (worst %.0f / %.0f mm)%n",
				idle.moved, idle.survivors, idle.touched,
				idle.meanAlongM * 1000, idle.meanSideM * 1000,
				idle.worstAlongM * 1000, idle.worstSideM * 1000);
		System.out.println();
		failures += expect("雨刷只搬它真的扫过的水（没被碰到的珠子沿行程方向不倒位）",
				shifted.untouchedWorstAlongM < 0.02,
				String.format("untouched %d beads, worst along-sweep move %.0f mm",
						shifted.untouched, shifted.untouchedWorstAlongM * 1000));
		failures += expect("雨刷只搬它真的扫过的水（被碰到的珠子不超过存活数的一半）",
				shifted.touched * 2 <= shifted.survivors,
				String.format("touched %d of %d survivors", shifted.touched, shifted.survivors));

		// ---- 9. THE SWEPT BAND: the four corners must be the four corners --------------------------
		// wipeFactorBand assembles the quad the blade swept between two samples out of
		//   xs = {A0.x, A0.y, A1.x, A1.y}, ys = {B0.x, B0.y, B1.x, B1.y}
		// where A/B are the blade's two ends and 0/1 the two samples. That is not a quad at all: it mixes
		// x and y of different corners, so the "band" is a thin sliver lying diagonally across the glass,
		// nowhere near the blade. Whatever rain happens to fall in that sliver is then declared to be
		// under the blade, shoved, and PICKED UP - so it rides the wiper for the rest of the stroke. On
		// screen that is "the whole windscreen's rain slides sideways when the wiper sweeps".
		//
		// The right cyclic order is A0 -> B0 -> B1 -> A1. The probe below steps the arm to mid-stroke and
		// asks both tests which beads are under the blade; the client's answer may only contain beads the
		// real quad holds, or beads within the leading-edge fade.
		final Glass bandGlass = settled(1.0F);
		final Glass.BandProbe band = bandGlass.bandTestProbe();
		System.out.printf("  swept band at mid-stroke (blade advance %.1f mm):%n", band.bladeAdvanceM * 1000);
		System.out.printf("    A0 (%+.3f, %+.3f)  B0 (%+.3f, %+.3f)   blade at the previous sample%n",
				band.from[0][0], band.from[0][1], band.from[1][0], band.from[1][1]);
		System.out.printf("    A1 (%+.3f, %+.3f)  B1 (%+.3f, %+.3f)   blade now%n",
				band.to[0][0], band.to[0][1], band.to[1][0], band.to[1][1]);
		System.out.printf("    asked where the blade is: client %d beads (farthest %.0f mm from the blade), "
						+ "real swept band %d beads (farthest %.0f mm)%n",
				band.clientCount, band.clientFarthestM * 1000, band.correctCount, band.correctFarthestM * 1000);
		System.out.println();
		failures += expect("扫掠带测试只认刀真的扫过的那块（不得把玻璃别处的雨滴当成刀下的）",
				band.falsePositiveCount == 0,
				String.format("client %d beads, %d of them outside the real band by more than the %.0f mm fade",
						band.clientCount, band.falsePositiveCount, WIPE_FADE_M * 1000));
		failures += expect("扫掠带测试不会漏掉刀扫过的珠子",
				band.correctCount == 0 || band.clientCount >= band.correctCount,
				String.format("client %d vs real band %d", band.clientCount, band.correctCount));

		final Glass coverageGlass = settled(1.0F);
		final Glass.BandCoverageProbe coverage = coverageGlass.bandCoverageProbe();
		System.out.printf("    over a WHOLE stroke (80x80 grid, %d cells = %.1f cm2 each): client claims %.3f m2, "
						+ "the independent ray-cast reference claims %.3f m2; they disagree on %.1f%% of the union "
						+ "(client-only %.3f m2, worst %.0f mm from the blade)%n",
				coverage.totalCells, coverage.glassM2 / coverage.totalCells * 10000,
				coverage.clientCells * coverage.glassM2 / coverage.totalCells,
				coverage.realCells * coverage.glassM2 / coverage.totalCells,
				100 * coverage.disagreement(),
				coverage.onlyClientCells * coverage.glassM2 / coverage.totalCells,
				coverage.onlyClientWorstM * 1000);
		System.out.println();
		failures += expect("整程下来，扫掠带测试和独立参照判定的是同一块地方（差异 < 15%）",
				coverage.disagreement() < 0.15,
				String.format("disagree on %.1f%% of the union (%d client-only, %d reference-only cells)",
						100 * coverage.disagreement(), coverage.onlyClientCells, coverage.onlyReferenceCells));

		// ---- 12. THE WAVE MUST BE ON THE BLADE -----------------------------------------------------
		// User report after the swept-band fix: "some of the small drops all over the screen still follow
		// the blade, only when sweeping right". A bead the blade is carrying must be ON the blade's leading
		// line - that is what carrying means - so this measures how far the carried set ever strays from the
		// blade, and how often a carried bead is moved by nothing at all.
		final Glass spreadGlass = settled(1.0F);
		final Glass.CarrySpreadProbe spread = spreadGlass.carrySpreadProbe();
		System.out.printf("  the wave against the blade, over one outward stroke: %d carried bead-frames on %d "
						+ "distinct beads; worst distance from the blade %.0f mm (%.0f mm already there at pickup, "
						+ "carrying added %.0f mm); offset from the blade's MID-POINT grew by %.1f mm; "
						+ "%d carried bead-frames moved by NOTHING (%.0f%%); biggest gap between a carried bead's "
						+ "own move and the blade's: %.1f mm (step %d)%n",
				spread.carriedFrames, spread.distinctCarried, spread.worstDistanceM * 1000,
				spread.pickupWorstM * 1000, spread.worstGrowthM * 1000, spread.midGrowthM * 1000,
				spread.frozenFrames, 100.0 * spread.frozenFrames / Math.max(1, spread.carriedFrames),
				spread.teleportWorstM * 1000, spread.teleportStep);
		System.out.println();
		// The carrying ITSELF must not let the wave drift off the blade. How deep the band is when the
		// bead joins is the pickup test's business (the fade band plus the bow-wave shove, which is what
		// makes the blade gather water rather than nudge it), so it is the GROWTH that is asserted here,
		// and it is measured from the MID-POINT, because that is the point the bead is moved by.
		failures += expect("携带过程本身不会让水漂离刀（相对刀中点的偏移增长 < 5 mm）",
				spread.midGrowthM < 0.005,
				String.format("mid-point offset grew %.1f mm (distance-to-segment grew %.0f mm, worst offset %.0f mm)",
						spread.midGrowthM * 1000, spread.worstGrowthM * 1000, spread.worstDistanceM * 1000));
		failures += expect("被携带的水不会卡住不动（没有任何一帧位移为 0）",
				spread.frozenFrames == 0,
				String.format("%d of %d carried bead-frames did not move",
						spread.frozenFrames, spread.carriedFrames));

		// ---- 13. MERGING: when two drops touch, they become one ------------------------------------
		// Two discs touch when the distance between their centres is their two radii ADDED, which on this
		// glass is anything from 7 mm (two fresh specks) to 28 mm (two grown beads) - yet the rule compares
		// that distance against a fixed mergeDistanceM. So merging works for small beads and is impossible
		// for large ones, which is backwards: a big drop rolling over the small ones in its path is exactly
		// the merge that matters.
		final Glass mergeGlass = settled(1.0F);
		mergeGlass.mergeEvents = 0;
		final Glass.MergeProbe merging = mergeGlass.mergeProbe();
		System.out.printf("  merging, on a settled field of %d beads (radii %.1f mm mean, %.1f mm max): "
						+ "%d pairs of discs actually OVERLAP; the rule merges %d of them and refuses %d, "
						+ "by up to %.1f mm of overlap (mean overlap %.1f mm); %d merges in the last window%n",
				merging.alive, merging.meanRadiusM * 1000, merging.maxRadiusM * 1000,
				merging.overlapping, merging.wouldMerge, merging.refused,
				merging.worstRefusedM * 1000, merging.meanOverlapM * 1000, merging.mergeEvents);
		System.out.println();
		failures += expect("两颗水滴接触就应该合并（重叠的珠子对不应被拒绝）",
				merging.refused == 0,
				String.format("%d of %d overlapping pairs refused, worst overlap %.1f mm",
						merging.refused, merging.overlapping, merging.worstRefusedM * 1000));

		// The rule itself, on exact cases rather than field statistics. This is the measurement that says
		// whether coalescence is right, and it includes the two grown beads (28 mm contact) that the fixed
		// 12 mm comparison could never have joined however long the sim ran.
		final Glass ruleGlass = settled(1.0F, false);
		final Glass.MergeRuleProbe rule = ruleGlass.mergeRuleProbe();
		System.out.printf("  the merge rule on %d exact cases (two specks / two mid beads / two grown beads, "
						+ "1 mm either side of touching): %d answered correctly, %d wrongly%n",
				rule.cases, rule.correct, rule.wrong);
		System.out.println();
		failures += expect("合并规则按「两圆接触」判定，而不是按固定毫米数",
				rule.wrong == 0,
				rule.wrong == 0 ? String.format("%d/%d cases correct", rule.correct, rule.cases) : rule.lastWrong);

		// 5 s of the same rain, from an identical field, under each rule. This is what the exact-case table
		// cannot say: how often coalescence actually fires, and what sizes come out of it.
		final Glass oldMergeGlass = settled(1.0F, false);
		oldMergeGlass.mergingEnabled = true;
		oldMergeGlass.oldMergeRule = true;
		final Glass.MergeRunProbe oldMergeRun = oldMergeGlass.mergeRunProbe(5.0);
		final Glass newMergeGlass = settled(1.0F, false);
		newMergeGlass.mergingEnabled = true;
		final Glass.MergeRunProbe newMergeRun = newMergeGlass.mergeRunProbe(5.0);
		System.out.printf("  5 s of the same rain, from an identical field:%n");
		System.out.printf("    fixed %.0f mm threshold (the old rule): %4d merges, %3d beads, radii %.1f mm mean / "
						+ "%.1f mm max, %d at the cap, %d over 10 mm%n",
				MERGE_DISTANCE_M * 1000, oldMergeRun.merges, oldMergeRun.alive, oldMergeRun.meanRadiusM * 1000,
				oldMergeRun.maxRadiusM * 1000, oldMergeRun.atCap, oldMergeRun.big);
		System.out.printf("    touching discs (this fix):             %4d merges, %3d beads, radii %.1f mm mean / "
						+ "%.1f mm max, %d at the cap, %d over 10 mm%n",
				newMergeRun.merges, newMergeRun.alive, newMergeRun.meanRadiusM * 1000,
				newMergeRun.maxRadiusM * 1000, newMergeRun.atCap, newMergeRun.big);
		System.out.println();
		failures += expect("雨场里真的在发生合并（5 s 内 > 0 次，且明显多于固定毫米数规则）",
				newMergeRun.merges > 0 && newMergeRun.merges > oldMergeRun.merges,
				String.format("%d merges vs %d under the old rule", newMergeRun.merges, oldMergeRun.merges));
		// What the fix actually buys, stated as measured. The old rule was NOT inert field-wide: freshly
		// spawned beads are 3.5 mm, so their 7 mm contact distance is well inside a 12 mm threshold and they
		// merged all along (788 in 5 s). What it could never do was join two beads whose contact distance
		// exceeds the threshold - i.e. anything past 6 mm radius, which is most of a settled field. So the
		// honest claim is that MORE beads get past 10 mm and more reach the cap, not that the mean radius
		// moves: the mean is set by what the spawner produces, and every merge retires a bead and is
		// replaced by a small one.
		failures += expect("合并让更多水珠长大（>10 mm 与到顶的珠子都不少于旧规则）",
				newMergeRun.big >= oldMergeRun.big && newMergeRun.atCap >= oldMergeRun.atCap,
				String.format("over 10 mm: %d vs %d; at the cap: %d vs %d; mean %.2f vs %.2f mm",
						newMergeRun.big, oldMergeRun.big, newMergeRun.atCap, oldMergeRun.atCap,
						newMergeRun.meanRadiusM * 1000, oldMergeRun.meanRadiusM * 1000));

		System.out.println();
		System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
		if (failures != 0) {
			System.exit(1);
		}
	}

	private static int expect(String what, boolean condition, String detail) {
		System.out.printf("%s  %-56s %s%n", condition ? "[ok]  " : "[FAIL]", what, detail);
		return condition ? 0 : 1;
	}

	/** A field that has settled for 20 s of rain with the blade parked: the state a driver looks at. */
	private static Glass settled(float intensity) {
		return settled(intensity, true);
	}

	/**
	 * The same, with coalescence either on (what the client does) or OFF FROM THE FIRST FRAME.
	 *
	 * <p>Switching it off only after settling is not isolation: the settled field has already been through
	 * 20 s of merging and is a different field (measured: mean radius 11.3 mm and mean density 90/m2 with
	 * merging, against 10.6 mm and 119/m2 without). A probe that compares two rules under a field that one
	 * of them shaped is measuring both at once.</p>
	 */
	private static Glass settled(float intensity, boolean merging) {
		final Glass glass = new Glass(new Random(20260101));
		glass.mergingEnabled = merging;
		glass.simulate(20, 0, intensity, Mode.OFF);
		return glass;
	}

	private enum Mode {
		OFF(0, 0), SLOW(SLOW_PERIOD_S, SLOW_DWELL_S);

		private final double periodS;
		private final double dwellS;

		Mode(double periodS, double dwellS) {
			this.periodS = periodS;
			this.dwellS = dwellS;
		}
	}

	/** What one stroke did to the water it crossed. */
	private static final class PushReport {
		private int pushed;
		private int pushedOff;
		private int eventCount;
		private double pushDistanceM;
		private double windrowMeanDensity;
		private double windrowMaxDensity;
		private double windrowSpeed;
		private double fieldMeanDensityBefore;
		private double fieldMeanDensityAfter;
		/** How far the blade's leading line travelled over the pass, in metres - the CARRY yardstick. */
		private double bladeTravelM;
		/**
		 * Mean DISTANCE THE BLADE CARRIED EACH BEAD IT TOUCHED, in metres, over the whole pass. This is
		 * cumulative path length, not displacement: a bead picked up halfway through a 1.1 m stroke should
		 * read about 0.55 m, and a bead a rigid one-shot shove nudged once reads about 0.02 m.
		 */
		private double meanCarriedM;
		private int carryCount;
		private int noCarryCount;
		/** Sorted sample of carried distances, for the min/median/max line. */
		private double[] carriedSamplesM = new double[0];
		private int waveCount;
		private int waveReleased;
		/** Cumulative releases over the stroke and the largest wave seen - the carry diagnostic. */
		private int waveReleasedTotal;
		private int wavePeak;
		/** The same mean for the OLD one-shot shove model, run side by side for comparison. */
		private double nudgedMeanCarriedM;
		/** Visible beads in each third of the glass, ordered from the park side to the far side. */
		private int[] thirdsBefore = new int[3];
		private int[] thirdsAfter = new int[3];
		private int[] thirdsAfterPark = new int[3];
		/**
		 * The same thirds, counting ONLY the beads that were on the glass before the stroke (matched by
		 * generation). This is what isolates transport: ~26 fresh beads land during a 0.78 s stroke, and
		 * they land uniformly, so a raw count per third is a statement about the spawner as much as about
		 * the blade.
		 */
		private int[] oldThirdBefore = new int[3];
		private int[] oldThirdAfter = new int[3];
		/** The direction of travel the thirds are ordered along, in glass coordinates. */
		private double travelAxisX;
		private double travelAxisY;
		/** Angle range the blade covered during the pass: proof the stroke really ran. */
		private double strokeMinAngleDeg = Double.MAX_VALUE;
		private double strokeMaxAngleDeg = -Double.MAX_VALUE;
		/** Where the blade was left when the parked frames finished; the next stroke starts from there. */
		private double parkedAngleDeg;

		private double meanPushMm() {
			return pushed == 0 ? 0 : pushDistanceM / pushed * 1000;
		}

		/** Carried distance as a fraction of the blade's own travel, for the assertion. */
		private double carryFraction() {
			return bladeTravelM <= 0 ? 0 : meanCarriedM / bladeTravelM;
		}

		/** min/median/max of the carried distances, in mm, for diagnosing where the carry is lost. */
		private String carriedSpread() {
			if (carriedSamplesM.length == 0) {
				return "none";
			}
			final double low = carriedSamplesM[0] * 1000;
			final double mid = carriedSamplesM[carriedSamplesM.length / 2] * 1000;
			final double high = carriedSamplesM[carriedSamplesM.length - 1] * 1000;
			return String.format("min %.0f / median %.0f / max %.0f mm", low, mid, high);
		}

		private static String thirds(int[] counts) {
			return String.format("%d/%d/%d", counts[0], counts[1], counts[2]);
		}
	}

	/** How the windrow behaved once the blade stopped shoving it. */
	private static final class RunReport {
		private int windrowCount;
		private int otherCount;
		private double windrowTravelMm;
		private double windrowSpeed;
		private double otherTravelMm;
		private double otherSpeed;
	}

	private static final class Glass {

		private final Random random;
		private final Drop[] drops = new Drop[RAINDROPS];
		private final double[] densityPerM2 = new double[RAINDROPS];
		private final int[] cellCounts = new int[4096];
		/** Indexed by bead, set by the wipe and consumed by {@link #measureWindrowRun}. */
		private final boolean[] pushedThisStroke = new boolean[RAINDROPS];
		private double angleDeg = PARK_ANGLE_DEG;
		private double previousAngleDeg = PARK_ANGLE_DEG;
		private double phase;
		private double intensity = 1;
		private double spawnAccumulator;
		private Mode mode = Mode.OFF;
		private final double[] down = {0, -1};
		/** Which wiper family this glass mirrors: the modelled two-pivot BAND, or the one-pivot SECTOR. */
		private boolean sectorWipe;
		/**
		 * REGRESSION PROBE ONLY: switch the carry off, leaving the old one-shot shove. Set on a glass that
		 * exists purely so the same measurement can be run against the model this one replaced, which is
		 * the only honest way to claim "the blade now carries the water": both models, same glass, same
		 * blade, same metric, side by side.
		 */
		private boolean noCarry;
		/**
		 * REGRESSION PROBE ONLY: mirror the render thread's SECOND pass over the same millisecond. The
		 * client reaches {@code advance()} once per RENDER PASS, and a cab drawn both from the outside and
		 * from the inside gets two passes carrying one {@code System.currentTimeMillis()}; the second is
		 * handed a clock that has not moved, so the arm does not move either. This flag makes the harness
		 * perform that call verbatim, which is the only way the offline numbers can be honest about it.
		 */
		private boolean duplicatePass;
		/**
		 * REGRESSION PROBE ONLY: restore the release rule that was in the client, which read a step with
		 * no travel as "the blade has turned around" and put the whole wave down on the spot. Set on a
		 * glass that exists purely so the two rules can be measured against each other.
		 */
		private boolean releaseOnStall;
		/** REGRESSION PROBE ONLY: switch the collection zones off, restoring "the pile is picked back up". */
		private boolean zoneEnabled = true;
		/**
		 * Whether coalescence runs at all. Switched OFF inside probes that measure the WIPER's rules
		 * (the release rule, the collection zone): merging retires a bead to a random spot, so it changes
		 * which pre-stroke beads survive, and a probe that counts those would otherwise be measuring two
		 * things at once. Merging is tested on its own, in the full model, by section 13.
		 */
		private boolean mergingEnabled = true;
		/** REGRESSION PROBE ONLY: restore the fixed-millimetre merge test, for a side-by-side run. */
		private boolean oldMergeRule;
		/** Mirrors {@code State.bladeMoving}: false while the wiper is off or dwelling at park. */
		private boolean wiperRunning;
		/** Releases during the current stroke, and how many of them happened AWAY from the two ends. */
		private int strokeReleaseTotal;
		private int midStrokeReleaseCount;
		private double parkDeg = PARK_ANGLE_DEG;
		private double sweepDeg = SWEEP_DEG;
		private double sweepSign = SWEEP_SIGN;
		private double pivotU = PIVOT_U;
		private double pivotV = PIVOT_V;
		private double armM = Math.min(WIDTH_M, HEIGHT_M) / 2;

		/** A one-pivot wiper: no modelled blade at all, so the sector path is the only geometry there is. */
		private static Glass sector(Random random) {
			final Glass glass = new Glass(random);
			glass.sectorWipe = true;
			glass.parkDeg = SECTOR_PARK_DEG;
			glass.sweepDeg = SECTOR_SWEEP_DEG;
			glass.sweepSign = 1;
			glass.pivotU = SECTOR_PIVOT_U;
			glass.pivotV = SECTOR_PIVOT_V;
			glass.armM = SECTOR_ARM_M;
			glass.angleDeg = SECTOR_PARK_DEG;
			glass.previousAngleDeg = SECTOR_PARK_DEG;
			return glass;
		}

		private Glass(Random random) {
			this.random = random;
			for (int i = 0; i < drops.length; i++) {
				drops[i] = new Drop(wrap(random.nextDouble()), wrap(random.nextDouble()), random.nextDouble(), new Random(random.nextLong()));
				drops[i].radiusM = newBeadRadiusM();
			}
		}

		private void simulate(double seconds, double speedMps, float rain, Mode mode) {
			this.mode = mode;
			final int steps = (int) Math.round(seconds * 62.5);
			for (int i = 0; i < steps; i++) {
				advance(0.016, speedMps, rain);
			}
		}

		private void advance(double dt, double speedMps, float rain) {
			intensity = rain;
			previousAngleDeg = angleDeg;
			advanceWiper(dt);
			advanceDrops(dt, speedMps);
			applyWipe();
			if (duplicatePass) {
				// The second render pass, verbatim: same clock, so advanceWiper(0) does not move the arm
				// and advanceDrops(0) moves nothing. Only the wipe runs, because only the wipe has an
				// effect at zero elapsed time - and it is where the damage was. (The field step is skipped
				// rather than called with dt = 0 on purpose: it draws from the shared RNG, so calling it
				// would send the spawner down a different sequence and the two runs would stop being
				// comparable for a reason that has nothing to do with the wiper.)
				previousAngleDeg = angleDeg;
				applyWipe();
			}
		}

		// ---- wiper ------------------------------------------------------------------------------

		private void advanceWiper(double dt) {
			if (mode == Mode.OFF) {
				wiperRunning = false;
				return;
			}
			final double strokeRate = 2 / Math.max(0.05, mode.periodS);
			final double strokeTime = 2 / strokeRate;
			final double cycle = strokeTime + mode.dwellS;
			double time = phase / strokeRate + dt;
			if (time >= cycle) {
				time -= cycle;
			}
			if (time >= strokeTime) {
				time = 0;
				wiperRunning = false;
			} else {
				wiperRunning = true;
			}
			phase = time * strokeRate;
			final double wrapped = phase % 2;
			angleDeg = parkDeg + (wrapped <= 1 ? wrapped : 2 - wrapped) * sweepDeg;
		}

		/** Mirrors WindshieldConfig.bladeSegmentM for a two-pivot (parallel linkage) wiper. */
		private double[][] bladeSegmentM(double absoluteAngleDeg) {
			final double theta = (absoluteAngleDeg - parkDeg) * sweepSign;
			final double[] p1 = {PIVOT_U * WIDTH_M, PIVOT_V * HEIGHT_M};
			final double[] p2 = {PIVOT2_U * WIDTH_M, PIVOT2_V * HEIGHT_M};
			final double[] m0 = {PIN_AU * WIDTH_M, PIN_AV * HEIGHT_M};
			final double[] br0 = {PIN_BU * WIDTH_M, PIN_BV * HEIGHT_M};
			final double[] a0 = {BLADE_AU * WIDTH_M, BLADE_AV * HEIGHT_M};
			final double[] b0 = {BLADE_BU * WIDTH_M, BLADE_BV * HEIGHT_M};
			final double radians = Math.toRadians(theta);
			final double cos = Math.cos(radians), sin = Math.sin(radians);
			final double[] m = rotateAbout(p1[0], p1[1], m0[0], m0[1], cos, sin);
			final double spanM = Math.hypot(br0[0] - m0[0], br0[1] - m0[1]);
			final double followerM = Math.hypot(br0[0] - p2[0], br0[1] - p2[1]);
			final double[] br = followerEnd(m, p2, spanM, followerM, followMode(p1, p2, m0, br0, spanM, followerM));
			if (br == null) {
				return new double[][]{rotateAbout(p1[0], p1[1], a0[0], a0[1], cos, sin), rotateAbout(p2[0], p2[1], b0[0], b0[1], cos, sin)};
			}
			final double turn = Math.atan2(br[1] - m[1], br[0] - m[0]) - Math.atan2(br0[1] - m0[1], br0[0] - m0[0]);
			return new double[][]{
					carried(m, m0, a0, Math.cos(turn), Math.sin(turn)),
					carried(m, m0, b0, Math.cos(turn), Math.sin(turn))
			};
		}

		private static double[] carried(double[] m, double[] m0, double[] point, double cos, double sin) {
			final double dx = point[0] - m0[0];
			final double dy = point[1] - m0[1];
			return new double[]{m[0] + dx * cos - dy * sin, m[1] + dx * sin + dy * cos};
		}

		private static int followMode(double[] p1, double[] p2, double[] m0, double[] br0, double spanM, double followerM) {
			final double[] plus = followerEnd(m0, p2, spanM, followerM, 1);
			final double[] minus = followerEnd(m0, p2, spanM, followerM, -1);
			if (plus == null || minus == null) {
				return 1;
			}
			final double parkSign = handedness(p2, m0, br0);
			if (parkSign == 0) {
				return 1;
			}
			return handedness(p2, m0, plus) == 0 || (handedness(p2, m0, plus) > 0) == (parkSign > 0) ? 1 : -1;
		}

		private static double handedness(double[] a, double[] b, double[] c) {
			return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
		}

		private static double[] followerEnd(double[] a, double[] pivot, double radiusAboutA, double radiusAboutPivot, int mode) {
			final double dx = pivot[0] - a[0];
			final double dy = pivot[1] - a[1];
			final double distance = Math.hypot(dx, dy);
			if (distance < 1.0E-9 || distance > radiusAboutA + radiusAboutPivot || distance < Math.abs(radiusAboutA - radiusAboutPivot)) {
				return null;
			}
			final double along = (distance * distance + radiusAboutA * radiusAboutA - radiusAboutPivot * radiusAboutPivot) / (2 * distance);
			final double height = Math.sqrt(Math.max(0, radiusAboutA * radiusAboutA - along * along));
			final double ux = dx / distance, uy = dy / distance;
			return new double[]{a[0] + ux * along - mode * uy * height, a[1] + uy * along + mode * ux * height};
		}

		private static double[] rotateAbout(double px, double py, double x, double y, double cos, double sin) {
			final double dx = x - px, dy = y - py;
			return new double[]{px + dx * cos - dy * sin, py + dx * sin + dy * cos};
		}

		// ---- the droplet field -------------------------------------------------------------------

		private void advanceDrops(double dt, double speedMps) {
			final double gravityDown = FALL_MPS * (0.6 + 0.9 * intensity);
			final double airflow = AIRFLOW_COEFFICIENT * SLOPE * speedMps * speedMps;
			final double liftSpeed = Math.max(0, Math.min(CREEP_MPS, airflow - gravityDown));
			measureDensity();
			for (int index = 0; index < drops.length; index++) {
				if (drops[index].carriedByBlade) {
					// Mirrors the client: water the blade is holding does not also obey gravity. Leaving
					// this out let a carried bead drift ~0.3 mm a frame away from the blade, 258 mm over a
					// stroke, which is what turned the wave into drops scattered around the swept area.
					continue;
				}
				advanceDrop(drops[index], densityPerM2[index], dt, liftSpeed);
			}
			spawnForWeather(dt);
			mergeDrops();
		}

		/** Mirrors MmtrWindshield.State.advanceDrop: growth, the density latch, then the step. */
		private void advanceDrop(Drop drop, double density, double dt, double liftSpeed) {
			drop.radiusM = Math.min(MAX_BEAD_RADIUS_M, drop.radiusM + GROWTH_MPS * dt * (0.4 + intensity));
			final double pressure = Math.max(0, density / DENSITY_THRESHOLD_PER_M2 - 1);
			final double sizeScale = 0.5 + 0.5 * (drop.radiusM / MAX_BEAD_RADIUS_M);
			final double mobility = pressure <= 0 ? 0 : Math.min(1, RUNOFF_MARGINAL_FACTOR + pressure);
			final double runoffSpeed = RUNOFF_MPS * mobility * sizeScale;
			if (liftSpeed <= 0 && runoffSpeed <= 0) {
				drop.surfaceSpeedMps = 0;
				return;
			}
			final double climbSpeed = (liftSpeed > 0 ? liftSpeed : -runoffSpeed) * sizeScale;
			double lateralVelocity = drop.lateralVelocity + (drop.random.nextGaussian() * JITTER_MPS) * dt;
			lateralVelocity = Math.max(-MAX_LATERAL_VELOCITY, Math.min(MAX_LATERAL_VELOCITY, lateralVelocity));
			drop.lateralVelocity = lateralVelocity * Math.max(0, 1 - dt * 2.5);
			final double stepX = -down[0] * climbSpeed - down[1] * lateralVelocity;
			final double stepY = -down[1] * climbSpeed + down[0] * lateralVelocity;
			final double speed = Math.hypot(stepX, stepY);
			drop.x += stepX * dt;
			drop.y += stepY * dt;
			drop.surfaceSpeedMps = speed;
			drop.pinchAccumulatorM += speed * dt;
			if (drop.pinchAccumulatorM >= PINCH_OFF_DISTANCE_M && drop.radiusM > STATIC_THRESHOLD_M * 1.4) {
				drop.pinchAccumulatorM = 0;
				pinchOff(drop);
			}
			if (drop.x < -0.05 || drop.x > 1.05 || drop.y < -0.10 || drop.y > 1.10) {
				respawn(drop);
			}
		}

		/** Mirrors MmtrWindshield.State.measureDensity: 3x3 cells of densityCellM, in beads per m2. */
		private void measureDensity() {
			final double cellWidth = Math.max(1.0E-3, DENSITY_CELL_M / WIDTH_M);
			final double cellHeight = Math.max(1.0E-3, DENSITY_CELL_M / HEIGHT_M);
			final int columns = Math.max(1, (int) Math.ceil(1 / cellWidth));
			final int rows = Math.max(1, (int) Math.ceil(1 / cellHeight));
			java.util.Arrays.fill(cellCounts, 0, columns * rows, 0);
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				cellCounts[cellY(drop, rows, cellHeight) * columns + cellX(drop, columns, cellWidth)]++;
			}
			final double blockAreaM2 = 9 * DENSITY_CELL_M * DENSITY_CELL_M;
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible) {
					densityPerM2[index] = 0;
					continue;
				}
				final int cx = cellX(drop, columns, cellWidth);
				final int cy = cellY(drop, rows, cellHeight);
				int neighbours = 0;
				for (int oy = -1; oy <= 1; oy++) {
					if (cy + oy < 0 || cy + oy >= rows) {
						continue;
					}
					for (int ox = -1; ox <= 1; ox++) {
						if (cx + ox < 0 || cx + ox >= columns) {
							continue;
						}
						neighbours += cellCounts[(cy + oy) * columns + (cx + ox)];
					}
				}
				densityPerM2[index] = neighbours / blockAreaM2;
			}
		}

		private static int cellX(Drop drop, int columns, double cellWidth) {
			return Math.max(0, Math.min(columns - 1, (int) (drop.x / cellWidth)));
		}

		private static int cellY(Drop drop, int rows, double cellHeight) {
			return Math.max(0, Math.min(rows - 1, (int) (drop.y / cellHeight)));
		}

		/** Mirrors State.applyWipe: the blade CARRIES what it crossed and records the probe numbers. */
		private void applyWipe() {
			final double[][] from = sectorWipe ? null : bladeSegmentM(previousAngleDeg);
			final double[][] to = sectorWipe ? null : bladeSegmentM(angleDeg);
			final double[] push;
			final double fromMidX, fromMidY, toMidX, toMidY;
			if (sectorWipe) {
				final double theta = Math.toRadians((angleDeg - parkDeg) * sweepSign);
				final double deltaDeg = (angleDeg - previousAngleDeg) * sweepSign;
				push = Math.abs(deltaDeg) < 1.0E-9 ? new double[]{0, 0}
						: unitOrZero(-Math.sin(theta) * (deltaDeg > 0 ? 1 : -1), Math.cos(theta) * (deltaDeg > 0 ? 1 : -1));
				fromMidX = fromMidY = toMidX = toMidY = 0;
			} else {
				push = unitOrZero((to[0][0] + to[1][0] - from[0][0] - from[1][0]) / 2,
						(to[0][1] + to[1][1] - from[0][1] - from[1][1]) / 2);
				fromMidX = (from[0][0] + from[1][0]) / 2;
				fromMidY = (from[0][1] + from[1][1]) / 2;
				toMidX = (to[0][0] + to[1][0]) / 2;
				toMidY = (to[0][1] + to[1][1]) / 2;
			}
			final double bladeAdvanceM = sectorWipe
					? Math.abs(Math.toRadians(angleDeg - previousAngleDeg)) * armM
					: Math.hypot(toMidX - fromMidX, toMidY - fromMidY);
			lastBladeAdvanceM = bladeAdvanceM;
			final double bowWaveM = Math.min(PUSH_M, BOW_WAVE_FRACTION * bladeAdvanceM);
			final double sweptDeg = normaliseSigned(angleDeg - parkDeg) * sweepSign;
			final double pivotX = pivotU * WIDTH_M;
			final double pivotY = pivotV * HEIGHT_M;
			framePushedCount = 0;
			waveCount = 0;
			waveReleasedThisFrame = 0;
			waveReleasedTotal = 0;
			wavePeak = 0;
			strokeMinAngleDeg = Double.MAX_VALUE;
			strokeMaxAngleDeg = -Double.MAX_VALUE;
			final boolean bladeTravelling = (push[0] != 0 || push[1] != 0) && bladeAdvanceM > 1.0E-9;
			lastPushX = push[0];
			lastPushY = push[1];
			// THE STROKE PARAMETER, and the reason it is not the blade's position projected on the direction
			// of travel: the parallelogram is 0.79 m long and the sweep is 80 deg, so the blade does not
			// translate - it ROTATES through the sweep. Its direction of travel therefore turns by most of a
			// right angle between park and full sweep, and a fixed axis measured against it is NOT monotone:
			// a bead sitting still would read as going backwards, and would be "released" mid-stroke. What
			// the wave actually tracks is DISTANCE ALONG THE PATH, which is what is accumulated here, and the
			// REVERSAL is read off the sign of the angle step, which is what the wiper actually does: the
			// animation wraps `time` back to 0 at the end of a stroke, so the angle can jump, and the sign of
			// that jump is not travel. Holding the last non-zero sign through the jump keeps the direction
			// honest until the blade genuinely starts moving the other way.
			final double angleStep = (angleDeg - previousAngleDeg) * sweepSign;
			if (Math.abs(angleStep) > 1.0E-9) {
				travelDirection = angleStep > 0 ? 1 : -1;
			}
			strokeBladeTravelM += bladeAdvanceM;
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible) {
					continue;
				}
				if (drop.carriedByBlade) {
					// THE TURNAROUND TEST. A carried bead rides the blade's advance until the blade stops
					// travelling or REVERSES. Which of those is the case is read from the wiper ANGLE, not
					// from a position: this linkage rotates through the sweep, so the blade's own direction
					// of travel turns by most of a right angle between park and full sweep, and any fixed
					// axis is therefore not monotone along the stroke. The sign of the angle step is.
					//
					// "The blade did not move this step" is deliberately NOT a stop. A step with no travel
					// is either the wiper switched off or dwelling (wiperRunning false, and then the water
					// must stay where the blade stopped) or a duplicate pass over the same millisecond,
					// which means nothing happened at all. Reading the second as a stop is what the old
					// rule did, and the two rules are measured against each other in main().
					final boolean reversed = bladeTravelling && travelDirection != drop.directionAtPickup;
					final boolean putDown = releaseOnStall
							? (!bladeTravelling || travelDirection != drop.directionAtPickup)
							: (!wiperRunning || reversed);
					if (putDown) {
						if (reversed) {
							parkInCollectZone(drop, push);
						}
						drop.carriedByBlade = false;
						waveReleasedThisFrame++;
						strokeReleaseTotal++;
						// The stroke is 80 deg, so "within 5 deg of either end" is a turnaround and
						// anything else is water dropped in the middle of the glass.
						if (angleDeg > parkDeg + 5 && angleDeg < parkDeg + sweepDeg - 5) {
							midStrokeReleaseCount++;
						}
						continue;
					}
					// Ride the blade's advance, exactly. No clamp: the blade's mid-point moves by precisely
					// this much along push every step, so a bead put on the leading line stays on it. The
					// clamp that used to be here was min(bladeAdvanceM, progress - progressAtPickup), a
					// projection onto a direction that ROTATES through the sweep - not frame-independent, so
					// it could under-move a carried bead, and the shortfall accumulated (measured: the wave
					// strayed up to 73 mm from the blade where the pickup offset allows 42 mm).
					final double carriedM = bladeAdvanceM;
					drop.x += push[0] * carriedM / WIDTH_M;
					drop.y += push[1] * carriedM / HEIGHT_M;
					pushAccumM[index] += carriedM;
					drop.carriedPathM += carriedM;
					waveCount++;
					framePushed[framePushedCount++] = index;
				} else {
					if (drop.inCollectZone) {
						// Mirrors the client: zone water is the wiper's finished business.
						continue;
					}
					final double pointX = drop.x * WIDTH_M;
					final double pointY = drop.y * HEIGHT_M;
					final double wipe = sectorWipe ? wipeFactorSector(pointX, pointY, pivotX, pivotY)
							: wipeFactorBand(pointX, pointY, from, to);
					if (wipe <= 0) {
						continue;
					}
					final double shoveM;
					if (sectorWipe) {
						final double fromParkDeg = normaliseSigned(Math.toDegrees(Math.atan2(pointY - pivotY, pointX - pivotX))
								- parkDeg) * sweepSign;
						final double behindDeg = sweptDeg - fromParkDeg;
						final double arcDeg = Math.abs(angleDeg - previousAngleDeg);
						if (behindDeg > WIPE_FADE_DEG || behindDeg < -(arcDeg + WIPE_FADE_DEG)) {
							continue;
						}
						shoveM = behindDeg >= 0 ? Math.min(bladeAdvanceM, Math.toRadians(behindDeg) * armM) : bowWaveM * wipe;
					} else {
						final double aheadM = (pointX - toMidX) * push[0] + (pointY - toMidY) * push[1];
						// With the carry switched off (the regression probe), the ORIGINAL rule is restored
						// verbatim: a shove no larger than the blade's own advance, capped so the bead lands
						// on the leading line instead of being thrown in front of it. That model's whole
						// failure is visible in this one line - the shove happens ONCE per bead, because a
						// bead it moves out of the band is never looked at again.
						shoveM = aheadM >= 0 ? bowWaveM * wipe : (noCarry ? Math.min(bladeAdvanceM, -aheadM) : 0);
					}
					if (shoveM <= 0) {
						continue;
					}
					drop.x += push[0] * shoveM / WIDTH_M;
					drop.y += push[1] * shoveM / HEIGHT_M;
					// PICK UP. Eligibility is measured where the bead NOW sits relative to the blade's
					// leading line: on it or ahead of it means the blade is working this bead, so it joins
					// the wave and is carried for the rest of the stroke.
					final double aheadAfterM = (drop.x * WIDTH_M - (sectorWipe ? 0 : toMidX)) * push[0]
							+ (drop.y * HEIGHT_M - (sectorWipe ? 0 : toMidY)) * push[1];
					if (aheadAfterM >= -MERGE_DISTANCE_M && !noCarry) {
						drop.carriedByBlade = true;
						drop.directionAtPickup = travelDirection;
					}
				}
				if (drop.x < -0.05 || drop.x > 1.05 || drop.y < -0.10 || drop.y > 1.10) {
					respawn(drop);
					pushedOffTotal++;
					continue;
				}
				pushedThisStroke[index] = true;
			}
			waveReleasedTotal += waveReleasedThisFrame;
			wavePeak = Math.max(wavePeak, waveCount);
			strokeMinAngleDeg = Math.min(strokeMinAngleDeg, angleDeg);
			strokeMaxAngleDeg = Math.max(strokeMaxAngleDeg, angleDeg);
		}

		/** Mirrors MmtrWindshield.State.wipeFactor: the one-pivot sector test, park-to-blade. */
		private double wipeFactorSector(double pointX, double pointY, double pivotX, double pivotY) {
			final double deltaX = pointX - pivotX;
			final double deltaY = pointY - pivotY;
			if (deltaX * deltaX + deltaY * deltaY > armM * armM) {
				return 0;
			}
			final double angle = Math.toDegrees(Math.atan2(deltaY, deltaX));
			final double fromParkDeg = normaliseSigned(angle - parkDeg) * sweepSign;
			if (fromParkDeg < 0 || fromParkDeg > Math.max(0, sweptDegNow()) + WIPE_FADE_DEG) {
				return 0;
			}
			if (fromParkDeg <= sweptDegNow()) {
				return 1;
			}
			return 1 - (fromParkDeg - sweptDegNow()) / WIPE_FADE_DEG;
		}

		private double sweptDegNow() {
			return normaliseSigned(angleDeg - parkDeg) * sweepSign;
		}

		private static double normaliseSigned(double degrees) {
			double value = degrees % 360;
			if (value > 180) {
				value -= 360;
			}
			if (value <= -180) {
				value += 360;
			}
			return value;
		}

		/** Per-bead carry accumulated over the current stroke, and who was carried at all. */
		private final double[] pushAccumM = new double[RAINDROPS];
		private final int[] framePushed = new int[RAINDROPS];
		private int framePushedCount;
		private int pushedOffTotal;
		/** Beads the blade is PLOUGHING right now, and how many the turnaround released on this frame. */
		private int waveCount;
		private int waveReleasedThisFrame;
		private int waveReleasedTotal;
		private int wavePeak;
		/** Angle range the blade covered in the current pass: proof the stroke really ran. */
		private double strokeMinAngleDeg = Double.MAX_VALUE;
		private double strokeMaxAngleDeg = -Double.MAX_VALUE;
		/** Net displacement of the blade's leading line over the current stroke, in metres. */
		private double strokeBladeTravelM;
		/** The direction the blade was travelling on the last wipe frame, for the carry probe. */
		private double lastPushX;
		private double lastPushY;
		/**
		 * The axis the CURRENT STROKE travels along, fixed when the stroke starts. {@code lastPushX/Y} is
		 * per-wipe-frame and is zero on a frame that does not move the blade, so it cannot be what the
		 * thirds are bucketed on - see {@link #thirds(boolean)}.
		 */
		private double strokeAxisX = 1;
		private double strokeAxisY = 0;
		/** +1 while the sweep runs outward, -1 while it returns; the turnaround test. */
		private int travelDirection = 1;
		/** The blade's mid-point advance from the last wipe, for the carry probes. */
		private double lastBladeAdvanceM;

		private double wipeFactorBand(double pointX, double pointY, double[][] from, double[][] to) {
			final double[] xs = bandXs(from, to);
			final double[] ys = bandYs(from, to);
			if (insideConvexQuad(pointX, pointY, xs, ys)) {
				return 1;
			}
			final double distance = distanceToSegmentM(pointX, pointY, to[0], to[1]);
			return distance >= WIPE_FADE_M ? 0 : 1 - distance / WIPE_FADE_M;
		}

		/** The swept quad's X coordinates, in cyclic corner order A0 -> B0 -> B1 -> A1. */
		private static double[] bandXs(double[][] from, double[][] to) {
			return new double[]{from[0][0], from[1][0], to[1][0], to[0][0]};
		}

		/** The same for Y. Kept in one place so the corner order cannot drift between callers. */
		private static double[] bandYs(double[][] from, double[][] to) {
			return new double[]{from[0][1], from[1][1], to[1][1], to[0][1]};
		}

		/**
		 * The INDEPENDENT reference: is this point inside the swept quad, by ray casting over the four
		 * corners listed explicitly? A different algorithm from {@code insideConvexQuad}'s cross-product
		 * walk and no shared array, so if the corner order in {@link #bandXs}/{@link #bandYs} is ever
		 * transposed again the two disagree and {@link #bandCoverageProbe} says so.
		 */
		private static boolean bandQuadReference(double px, double py, double[][] from, double[][] to) {
			final double[][] corner = {from[0], from[1], to[1], to[0]};
			boolean inside = false;
			for (int i = 0, j = 3; i < 4; j = i++) {
				final double xi = corner[i][0], yi = corner[i][1];
				final double xj = corner[j][0], yj = corner[j][1];
				if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
					inside = !inside;
				}
			}
			return inside;
		}

		/**
		 * The swept band the way it is MEANT to be built: the four corners of the region the blade sweeps
		 * between two adjacent samples, in their real cyclic order - A0, B0, B1, A1 (along the blade at the
		 * first sample, across to the second, and back). {@link #wipeFactorBand} above assembles those four
		 * corners out of the wrong coordinates; this is the same test with them in the right places, and
		 * {@link #bandTestProbe} measures the two against each other on the real geometry.
		 */
		private static double wipeFactorBandCorrect(double pointX, double pointY, double[][] from, double[][] to) {
			final double[] xs = {from[0][0], from[1][0], to[1][0], to[0][0]};
			final double[] ys = {from[0][1], from[1][1], to[1][1], to[0][1]};
			if (insideConvexQuad(pointX, pointY, xs, ys)) {
				return 1;
			}
			final double distance = distanceToSegmentM(pointX, pointY, to[0], to[1]);
			return distance >= WIPE_FADE_M ? 0 : 1 - distance / WIPE_FADE_M;
		}

		/**
		 * THE BAND TEST AGAINST THE BAND. Steps the arm to the middle of an outward stroke and asks both
		 * tests which beads are under the blade. The client's test is only allowed to answer "yes" for
		 * beads the real swept quad contains, or beads within the leading-edge fade - anything else is
		 * rain the blade is nowhere near being dragged along with it, which is what "the whole screen's
		 * rain slides sideways when the wiper sweeps" is.
		 */
		private BandProbe bandTestProbe() {
			final BandProbe probe = new BandProbe();
			mode = Mode.SLOW;
			phase = 0;
			angleDeg = parkDeg;
			previousAngleDeg = parkDeg;
			while (phase < 0.5) {
				previousAngleDeg = angleDeg;
				advanceWiper(0.016);
			}
			final double[][] from = bladeSegmentM(previousAngleDeg);
			final double[][] to = bladeSegmentM(angleDeg);
			probe.from = from;
			probe.to = to;
			probe.bladeAdvanceM = Math.hypot((to[0][0] + to[1][0] - from[0][0] - from[1][0]) / 2,
					(to[0][1] + to[1][1] - from[0][1] - from[1][1]) / 2);
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				final double px = drop.x * WIDTH_M;
				final double py = drop.y * HEIGHT_M;
				final boolean correct = wipeFactorBandCorrect(px, py, from, to) > 0;
				final double distance = distanceToSegmentM(px, py, to[0], to[1]);
				if (wipeFactorBand(px, py, from, to) > 0) {
					probe.clientCount++;
					probe.clientFarthestM = Math.max(probe.clientFarthestM, distance);
					// A bead the right quad does not contain AND that is further from the leading edge than
					// the fade band can explain is one this test invented out of empty glass.
					if (!correct && distance > WIPE_FADE_M) {
						probe.falsePositiveCount++;
					}
				}
				if (correct) {
					probe.correctCount++;
					probe.correctFarthestM = Math.max(probe.correctFarthestM, distance);
				}
			}
			return probe;
		}

		/** What one mid-stroke band test did, for the probe and its assertion. */
		private static final class BandProbe {
			private double[][] from;
			private double[][] to;
			private double bladeAdvanceM;
			private int clientCount;
			private int correctCount;
			private int falsePositiveCount;
			private double clientFarthestM;
			private double correctFarthestM;
		}

		/**
		 * WHAT THE COLLECTOR IS FOR, measured over consecutive cycles. Each cycle is a full out-and-back.
		 * On the first cycle the blade crosses most of the glass and carries that water to one end. The
		 * question is the SECOND cycle: with a collection zone, the water from the first is parked at the
		 * sides and the blade sweeps over it without touching it, so there is nothing left to drag; without
		 * one, the pile is picked straight back up and carried home, and every cycle shifts the same water
		 * again - which is what "the whole screen's rain slides sideways with every sweep" looks like.
		 */
		private ZoneProbe zoneCycleProbe(int cycles) {
			final ZoneProbe probe = new ZoneProbe();
			probe.touched = new int[cycles];
			probe.meanMoveMm = new double[cycles];
			probe.parked = new int[cycles];
			probe.parkedTotal = new int[cycles];
			mode = Mode.SLOW;
			phase = 0;
			final int stepsPerCycle = (int) Math.round(SLOW_PERIOD_S * 62.5);
			final double[] x0 = new double[drops.length];
			final double[] y0 = new double[drops.length];
			final int[] gen0 = new int[drops.length];
			final boolean[] wasParked = new boolean[drops.length];
			final boolean[] pushedBefore = new boolean[drops.length];
			int parkedReworked = 0;
			for (int cycle = 0; cycle < cycles; cycle++) {
				for (int index = 0; index < drops.length; index++) {
					x0[index] = drops[index].x;
					y0[index] = drops[index].y;
					gen0[index] = drops[index].generation;
					wasParked[index] = drops[index].inCollectZone;
					pushedThisStroke[index] = false;
				}
				for (int i = 0; i < stepsPerCycle; i++) {
					System.arraycopy(pushedThisStroke, 0, pushedBefore, 0, drops.length);
					advance(0.016, 0, 1.0F);
					// THE INVARIANT THE ZONE EXISTS FOR, checked every frame: a bead that is parked in a
					// collection zone must never be picked up by a stroke. Checking it per CYCLE would count
					// beads that were legitimately pushed earlier - as part of the wave, before they were
					// parked, or after they drained off the glass and came back as new rain.
					for (int index = 0; index < drops.length; index++) {
						if (drops[index].inCollectZone && pushedThisStroke[index] && !pushedBefore[index]) {
							parkedReworked++;
						}
					}
				}
				double moveSum = 0;
				int survivors = 0, touched = 0, parkedNow = 0;
				for (int index = 0; index < drops.length; index++) {
					final Drop drop = drops[index];
					if (!drop.visible) {
						continue;
					}
					if (drop.inCollectZone) {
						parkedNow++;
					}
					if (drop.generation != gen0[index]) {
						continue;
					}
					moveSum += Math.hypot((drop.x - x0[index]) * WIDTH_M, (drop.y - y0[index]) * HEIGHT_M);
					survivors++;
					if (pushedThisStroke[index]) {
						touched++;
					}
				}
				probe.reworkedParked = parkedReworked;
				probe.touched[cycle] = touched;
				probe.meanMoveMm[cycle] = survivors == 0 ? 0 : moveSum / survivors * 1000;
				probe.parkedTotal[cycle] = parkedNow;
				probe.parked[cycle] = cycle == 0 ? parkedNow : parkedNow - probe.parkedTotal[cycle - 1];
			}
			return probe;
		}

		/** Per-cycle movement and parking, for the collection-zone assertion. */
		private static final class ZoneProbe {
			private int[] touched;
			private double[] meanMoveMm;
			private int[] parked;
			private int[] parkedTotal;
			/** The most parked beads a later stroke picked up again. Must be 0 - that is the whole point. */
			private int reworkedParked;
		}

		/**
		 * HOW MUCH OF THE GLASS THE BAND TEST WRONGLY CLAIMS, over a whole stroke.
		 *
		 * <p>{@link #bandTestProbe} samples one instant, which is not enough: the quad
		 * {@link #wipeFactorBand} builds out of mismatched coordinates is a THIN SLIVER, and a thin sliver
		 * contains almost nothing at any one moment. What it does is SWEEP - it is rebuilt from the blade's
		 * two ends at every angle, so as the blade crosses the glass the sliver does too, and the union of
		 * all those instants is a broad diagonal corridor. Every bead that happens to lie in that corridor
		 * is declared to be under the blade, shoved, and PICKED UP, so it rides the wiper for the rest of
		 * the stroke. That is "the whole screen's rain slides sideways when the wiper sweeps", and a
		 * single-instant probe cannot see it.</p>
		 *
		 * <p>Measured on a grid over the glass at every sample of a full outward stroke: how many cells the
		 * client's test claims, how many of those the real swept quad also claims, and how far from the
		 * blade the wrongly-claimed ones are.</p>
		 */
		private BandCoverageProbe bandCoverageProbe() {
			final BandCoverageProbe probe = new BandCoverageProbe();
			mode = Mode.SLOW;
			phase = 0;
			angleDeg = parkDeg;
			previousAngleDeg = parkDeg;
			final int cells = 80;
			final boolean[] clientClaim = new boolean[cells * cells];
			final boolean[] realClaim = new boolean[cells * cells];
			final double[] clientDistance = new double[cells * cells];
			final int samples = 400;
			for (int step = 0; step < samples; step++) {
				previousAngleDeg = angleDeg;
				advanceWiper(SLOW_PERIOD_S / 2 / samples);
				final double[][] from = bladeSegmentM(previousAngleDeg);
				final double[][] to = bladeSegmentM(angleDeg);
				final double[] xs = bandXs(from, to);
				final double[] ys = bandYs(from, to);
				for (int iy = 0; iy < cells; iy++) {
					for (int ix = 0; ix < cells; ix++) {
						final double px = (ix + 0.5) / cells * WIDTH_M;
						final double py = (iy + 0.5) / cells * HEIGHT_M;
						final int index = iy * cells + ix;
						if (insideConvexQuad(px, py, xs, ys)) {
							clientClaim[index] = true;
							clientDistance[index] = Math.max(clientDistance[index],
									distanceToSegmentM(px, py, to[0], to[1]));
						}
						if (bandQuadReference(px, py, from, to)) {
							realClaim[index] = true;
						}
					}
				}
			}
			for (int index = 0; index < cells * cells; index++) {
				if (clientClaim[index]) {
					probe.clientCells++;
					if (!realClaim[index]) {
						probe.onlyClientCells++;
						probe.onlyClientWorstM = Math.max(probe.onlyClientWorstM, clientDistance[index]);
					}
				}
				if (realClaim[index]) {
					probe.realCells++;
					if (!clientClaim[index]) {
						probe.onlyReferenceCells++;
					}
				}
				if (clientClaim[index] || realClaim[index]) {
					probe.unionCells++;
				}
			}
			probe.totalCells = cells * cells;
			probe.glassM2 = WIDTH_M * HEIGHT_M;
			return probe;
		}

		/** How much of the glass each version of the band test claims over one outward stroke. */
		private static final class BandCoverageProbe {
			private int totalCells;
			private int clientCells;
			private int realCells;
			private int unionCells;
			private int onlyClientCells;
			private int onlyReferenceCells;
			private double onlyClientWorstM;
			private double glassM2;

			/** Cells the two tests disagree about, as a fraction of the cells either claims. */
			private double disagreement() {
				return unionCells == 0 ? 0 : (double) (onlyClientCells + onlyReferenceCells) / unionCells;
			}
		}

		/**
		 * WHERE THE WAVE ACTUALLY IS. A bead the blade is carrying is by definition ON the blade's leading
		 * line - it was put there when it was picked up, and it rides the blade's own advance afterwards. So
		 * every carried bead must stay within the fade band of the blade, always. If any of them is further
		 * away than that, it is water being dragged around the glass with the blade nowhere near it, which is
		 * "some of the drops all over the screen slide along with the wiper".
		 *
		 * <p>This is the probe for a specific suspect: {@code carriedM} is clamped by a projection of the
		 * blade's midpoint onto the direction of travel, and that projection is not frame-independent - it
		 * is taken against a direction that ROTATES through the stroke, so the term can shrink or go
		 * negative and the clamp then moves a carried bead by less than the blade moved, or not at all. A
		 * bead that falls behind never catches up, because nothing ever pushes it forward again, so the
		 * deficit accumulates over the stroke and the wave is left smeared across the glass behind the
		 * blade while still being marked as carried.</p>
		 */
		private CarrySpreadProbe carrySpreadProbe() {
			final CarrySpreadProbe probe = new CarrySpreadProbe();
			mode = Mode.SLOW;
			phase = 0;
			final boolean[] everCarried = new boolean[drops.length];
			final boolean[] offsetKnown = new boolean[drops.length];
			final double[] offsetAtPickup = new double[drops.length];
			final double[] midAtPickup = new double[drops.length];
			final double[] beforeX = new double[drops.length];
			final double[] beforeY = new double[drops.length];
			final double[][] parkBlade = bladeSegmentM(angleDeg);
			double previousMidX = (parkBlade[0][0] + parkBlade[1][0]) / 2;
			double previousMidY = (parkBlade[0][1] + parkBlade[1][1]) / 2;
			final int steps = (int) Math.round(SLOW_PERIOD_S / 2 * 62.5);
			for (int step = 0; step < steps; step++) {
				for (int i = 0; i < drops.length; i++) {
					beforeX[i] = drops[i].x;
					beforeY[i] = drops[i].y;
				}
				advance(0.016, 0, 1.0F);
				final double[][] blade = bladeSegmentM(angleDeg);
				// What the blade's mid-point moved by this step: the exact displacement every carried bead
				// is supposed to have had.
				final double[] pushNow = unitOrZero((blade[0][0] + blade[1][0]) / 2 - previousMidX,
						(blade[0][1] + blade[1][1]) / 2 - previousMidY);
				final double pushXMove = pushNow[0] * lastBladeAdvanceM;
				final double pushYMove = pushNow[1] * lastBladeAdvanceM;
				previousMidX = (blade[0][0] + blade[1][0]) / 2;
				previousMidY = (blade[0][1] + blade[1][1]) / 2;
				for (int i = 0; i < drops.length; i++) {
					final Drop drop = drops[i];
					if (!drop.carriedByBlade) {
						offsetKnown[i] = false;
						continue;
					}
					everCarried[i] = true;
					probe.carriedFrames++;
					final double distance = distanceToSegmentM(drop.x * WIDTH_M, drop.y * HEIGHT_M,
							blade[0], blade[1]);
					// The offset from the blade's MID-POINT, which is the point whose displacement the bead
					// is moved by. This is the quantity carrying is actually responsible for: if it is
					// preserved, the bead tracks the blade exactly and any growth in the distance to the
					// SEGMENT is about where along the blade the bead sits, not about drift.
					final double midDistance = Math.hypot(drop.x * WIDTH_M - (blade[0][0] + blade[1][0]) / 2,
							drop.y * HEIGHT_M - (blade[0][1] + blade[1][1]) / 2);
					probe.worstDistanceM = Math.max(probe.worstDistanceM, distance);
					if (!offsetKnown[i]) {
						offsetKnown[i] = true;
						offsetAtPickup[i] = distance;
						midAtPickup[i] = midDistance;
						probe.pickupWorstM = Math.max(probe.pickupWorstM, distance);
					} else {
						// How much the offset GREW while the bead was being carried. This is the number
						// that decides whether "the wave strays from the blade" is a carrying bug or is
						// simply inherited from where the bead was when it was picked up.
						probe.worstGrowthM = Math.max(probe.worstGrowthM, distance - offsetAtPickup[i]);
						probe.midGrowthM = Math.max(probe.midGrowthM, midDistance - midAtPickup[i]);
					}
					if (Math.abs(drop.x - beforeX[i]) < 1.0E-12 && Math.abs(drop.y - beforeY[i]) < 1.0E-12) {
						probe.frozenFrames++;
					}
					// THE TELEPORT CHECK. A carried bead is moved by exactly the blade's mid-point
					// displacement, so its own displacement must equal that to the last bit. Anything else
					// is a second code path moving it - a respawn, a merge, a zone drop - and the biggest
					// such jump is what actually smears the wave across the glass.
					final double movedX = (drop.x - beforeX[i]) * WIDTH_M;
					final double movedY = (drop.y - beforeY[i]) * HEIGHT_M;
					final double discrepancy = Math.hypot(movedX - pushXMove, movedY - pushYMove);
					if (discrepancy > probe.teleportWorstM) {
						probe.teleportWorstM = discrepancy;
						probe.teleportStep = step;
					}
				}
			}
			final double[][] blade = bladeSegmentM(angleDeg);
			for (int i = 0; i < drops.length; i++) {
				if (everCarried[i]) {
					probe.distinctCarried++;
				}
				if (drops[i].carriedByBlade) {
					probe.stillCarried++;
					probe.endWorstM = Math.max(probe.endWorstM,
							distanceToSegmentM(drops[i].x * WIDTH_M, drops[i].y * HEIGHT_M, blade[0], blade[1]));
				}
			}
			return probe;
		}

		/** How far the carried water strays from the blade it is supposedly riding. */
		private static final class CarrySpreadProbe {
			private int carriedFrames;
			private int frozenFrames;
			private int distinctCarried;
			private int stillCarried;
			private double worstDistanceM;
			private double endWorstM;
			/** The offset a bead had the first frame it was carried - inherited from the pickup test. */
			private double pickupWorstM;
			/** The most that offset GREW afterwards - what carrying itself is responsible for. */
			private double worstGrowthM;
			/** The same growth measured from the blade's MID-POINT, the point the bead is moved by. */
			private double midGrowthM;
			/** Biggest gap between a carried bead's own displacement and the blade's - a teleport. */
			private double teleportWorstM;
			private int teleportStep = -1;
		}

		private static boolean insideConvexQuad(double px, double py, double[] xs, double[] ys) {
			boolean positive = false, negative = false;
			for (int i = 0; i < 4; i++) {
				final int j = (i + 1) % 4;
				final double cross = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i]);
				final double margin = Math.hypot(xs[j] - xs[i], ys[j] - ys[i]) * BAND_INFLATE_M;
				if (cross > margin) positive = true;
				if (cross < -margin) negative = true;
			}
			return !(positive && negative);
		}

		private static double distanceToSegmentM(double px, double py, double[] a, double[] b) {
			final double dx = b[0] - a[0], dy = b[1] - a[1];
			final double lengthSquared = dx * dx + dy * dy;
			final double t = lengthSquared < 1.0E-12 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (py - a[1]) * dy) / lengthSquared));
			return Math.hypot(px - (a[0] + dx * t), py - (a[1] + dy * t));
		}

		/**
		 * Mirrors State.parkInCollectZone: puts a ploughed bead down in the collection zone at the end of
		 * the stroke the blade has just reached, at its own random depth, capped so it stays on the glass.
		 */
		private void parkInCollectZone(Drop drop, double[] push) {
			drop.inCollectZone = true;
			if (!zoneEnabled || COLLECT_ZONE_M <= 0) {
				return;
			}
			double roomM = COLLECT_ZONE_M;
			if (push[0] < -1.0E-6) {
				roomM = Math.min(roomM, (0.99 - drop.x) * WIDTH_M / -push[0]);
			} else if (push[0] > 1.0E-6) {
				roomM = Math.min(roomM, (drop.x - 0.01) * WIDTH_M / push[0]);
			}
			if (push[1] < -1.0E-6) {
				roomM = Math.min(roomM, (0.99 - drop.y) * HEIGHT_M / -push[1]);
			} else if (push[1] > 1.0E-6) {
				roomM = Math.min(roomM, (drop.y - 0.01) * HEIGHT_M / push[1]);
			}
			final double depthM = Math.max(0, roomM) * (0.15 + 0.7 * random.nextDouble());
			drop.x -= push[0] * depthM / WIDTH_M;
			drop.y -= push[1] * depthM / HEIGHT_M;
		}

		private static double[] unitOrZero(double dx, double dy) {
			final double length = Math.hypot(dx, dy);
			return length < 1.0E-6 ? new double[]{0, 0} : new double[]{dx / length, dy / length};
		}

		private void pinchOff(Drop parent) {
			for (final Drop child : drops) {
				if (child != parent && !child.visible) {
					child.x = parent.x;
					child.y = parent.y;
					child.radiusM = parent.radiusM * 0.55;
					child.visible = true;
					child.lateralVelocity = 0;
					child.pinchAccumulatorM = 0;
					child.generation++;
					return;
				}
			}
		}

		private String blockHistogram() {
			final int[] buckets = new int[12];
			for (int index = 0; index < drops.length; index++) {
				if (!drops[index].visible) {
					continue;
				}
				final int count = (int) Math.round(densityPerM2[index] * 9 * DENSITY_CELL_M * DENSITY_CELL_M);
				buckets[Math.max(0, Math.min(buckets.length - 1, count))]++;
			}
			final StringBuilder builder = new StringBuilder();
			for (int i = 0; i < buckets.length; i++) {
				if (buckets[i] > 0) {
					builder.append(i).append(":").append(buckets[i]).append(' ');
				}
			}
			return builder.toString();
		}

		private int countOffGlass() {
			int off = 0;
			for (final Drop drop : drops) {
				if (drop.visible && (drop.x < 0 || drop.x > 1 || drop.y < 0 || drop.y > 1)) {
					off++;
				}
			}
			return off;
		}

		/** Debug: what the probe sees over a settled field, including the zero-density entries. */
		private String debugDensityAfter(double seconds, float rain) {
			simulate(seconds, 0, rain, Mode.OFF);
			int occupiedCells = 0, zero = 0, positive = 0, visible = 0;
			double positiveSum = 0;
			for (int i = 0; i < cellCounts.length; i++) {
				if (cellCounts[i] > 0) {
					occupiedCells++;
				}
			}
			for (int index = 0; index < drops.length; index++) {
				if (!drops[index].visible) {
					continue;
				}
				visible++;
				if (densityPerM2[index] <= 0) {
					zero++;
				} else {
					positive++;
					positiveSum += densityPerM2[index];
				}
			}
			return String.format("  [debug] occupied=%d alive=%d densityZero=%d positive=%d meanPositive=%.0f",
					occupiedCells, visible, zero, positive, positiveSum / Math.max(1, positive));
		}

		private double newBeadRadiusM() {			final double ceiling = Math.min(MAX_BEAD_RADIUS_M, STATIC_THRESHOLD_M);
			return MIN_BEAD_RADIUS_M + random.nextDouble() * Math.max(0, ceiling - MIN_BEAD_RADIUS_M) * 0.9;
		}

		private void respawn(Drop drop) {
			drop.x = random.nextDouble();
			drop.y = random.nextDouble();
			drop.radiusM = newBeadRadiusM();
			drop.lateralVelocity = 0;
			drop.surfaceSpeedMps = 0;
			drop.pinchAccumulatorM = 0;
			drop.visible = false;
			drop.carriedByBlade = false;
			drop.inCollectZone = false;
			drop.generation++;
		}

		/** Mirrors State.spawnForWeather: the RATE follows the rain, the count is only a ceiling. */
		private void spawnForWeather(double dt) {
			if (intensity <= 0.02) {
				return;
			}
			final int cap = (int) Math.round(drops.length * (0.25 + 0.75 * intensity));
			int alive = 0;
			for (final Drop drop : drops) {
				if (drop.visible) {
					alive++;
				}
			}
			final double ratePerSecond = SPAWN_POPULATION_PER_SECOND * drops.length * intensity;
			spawnAccumulator = Math.min(Math.max(4, ratePerSecond * 0.25), spawnAccumulator + dt * ratePerSecond);
			while (spawnAccumulator >= 1 && alive < cap) {
				spawnAccumulator -= 1;
				Drop weakest = null;
				for (final Drop drop : drops) {
					if (!drop.visible) {
						weakest = drop;
						break;
					}
				}
				if (weakest == null) {
					break;
				}
				respawn(weakest);
				weakest.visible = true;
				alive++;
			}
		}

		/** Mirrors State.mergeDrops (spatial hash over the merge distance). */
		private void mergeDrops() {
			if (!mergingEnabled) {
				return;
			}
			// THE CELL SIZE IS THE LARGEST DISTANCE A MERGE CAN REACH, not the merge threshold: a 3x3
			// neighbourhood search only finds every pair within one cell, so a criterion that reaches
			// 2*maxBeadRadiusM needs cells at least that wide or pairs are silently missed.
			final double reachM = Math.max(MERGE_DISTANCE_M, 2 * MAX_BEAD_RADIUS_M);
			final double cellWidth = Math.max(1.0E-3, reachM / WIDTH_M);
			final double cellHeight = Math.max(1.0E-3, reachM / HEIGHT_M);
			final int columns = Math.max(1, (int) Math.ceil(1 / cellWidth));
			final int rows = Math.max(1, (int) Math.ceil(1 / cellHeight));
			java.util.List<java.util.List<Integer>> cells = new java.util.ArrayList<>();
			for (int i = 0; i < columns * rows; i++) {
				cells.add(new java.util.ArrayList<>());
			}
			for (int index = 0; index < drops.length; index++) {
				if (!drops[index].visible) {
					continue;
				}
				cells.get(cellY(drops[index], rows, cellHeight) * columns + cellX(drops[index], columns, cellWidth)).add(index);
			}
			for (int index = 0; index < drops.length; index++) {
				final Drop a = drops[index];
				if (!a.visible) {
					continue;
				}
				final int cx = cellX(a, columns, cellWidth), cy = cellY(a, rows, cellHeight);
				for (int oy = -1; oy <= 1; oy++) {
					for (int ox = -1; ox <= 1; ox++) {
						final int nx = cx + ox, ny = cy + oy;
						if (nx < 0 || ny < 0 || nx >= columns || ny >= rows) {
							continue;
						}
						for (final int other : cells.get(ny * columns + nx)) {
							mergePair(a, drops[other]);
						}
					}
				}
			}
		}

		private void mergePair(Drop a, Drop b) {
			if (a == b || !a.visible || !b.visible) {
				return;
			}
			// COALESCENCE IS FOR FREE RAIN, not for the water the wiper has gathered. A bead the blade is
			// holding is part of a body of water being pushed along, and water parked in a collection zone
			// is a sheet at the side of the screen; neither is a drop sweeping up its neighbours. Letting
			// them merge retires one of them to a random spot on the glass, which is a density SINK in
			// exactly the place the model is trying to build density: measured with merging switched on for
			// everything, the windrow fell from 215 to 153 per m2 and its 1 s run from 270 mm to 69 mm, and
			// "the water gathers on the side the blade went" stopped being true.
			if (a.carriedByBlade || b.carriedByBlade || a.inCollectZone || b.inCollectZone) {
				return;
			}
			final double deltaX = (a.x - b.x) * WIDTH_M, deltaY = (a.y - b.y) * HEIGHT_M;
			// THEY TOUCH. Two discs are in contact when the distance between their centres is their two
			// radii added - not when it is under some fixed number of millimetres. The fixed comparison
			// this replaces (against MERGE_DISTANCE_M) meant merging worked only while a bead was small:
			// measured on the settled field, 6 pairs of discs overlapped and the rule refused ALL 6, by up
			// to 14.5 mm of overlap, because a grown bead's contact distance (2 x 10.6 mm and up) is nearly
			// twice the threshold. A big drop rolling over the small ones in its path - the merge that
			// actually matters - could never happen at all.
			final double contactM = oldMergeRule ? MERGE_DISTANCE_M : a.radiusM + b.radiusM;
			if (deltaX * deltaX + deltaY * deltaY > contactM * contactM) {
				return;
			}
			final Drop keeper = a.radiusM >= b.radiusM ? a : b;
			final Drop absorbed = keeper == a ? b : a;
			keeper.radiusM = Math.min(MAX_BEAD_RADIUS_M, Math.hypot(keeper.radiusM, absorbed.radiusM));
			respawn(absorbed);
			absorbed.visible = false;
			mergeEvents++;
			mergedRadiusM = keeper.radiusM;
		}

		private double mergedRadiusM;
		/** How many merges have happened since the last reset - the merge RATE. */
		private int mergeEvents;

		/**
		 * THE MERGE RULE AGAINST THE BEADS IT GOVERNS.
		 *
		 * <p>Two drops of water merge when they TOUCH, and two discs touch when the distance between their
		 * centres is their two radii added together. The rule compares that distance against a FIXED
		 * {@code mergeDistanceM} instead, so how well merging works depends entirely on how big the beads
		 * are: it is generous for two freshly-spawned specks (2 x 3.5 mm = 7 mm apart, against a 12 mm
		 * threshold) and impossible for two grown ones (2 x 14 mm = 28 mm apart, nearly two and a half times
		 * the threshold). A large bead therefore cannot absorb anything, which is exactly the case merging
		 * is for - a fat drop rolling over the small ones in its path.</p>
		 *
		 * <p>This counts, over the settled field: how many pairs of discs actually overlap, how many of
		 * those the rule merges, and how deep the worst REFUSED overlap is.</p>
		 */
		private MergeProbe mergeProbe() {
			final MergeProbe probe = new MergeProbe();
			double radiusSum = 0, overlapSum = 0;
			int radiusCount = 0;
			for (int i = 0; i < drops.length; i++) {
				if (!drops[i].visible) {
					continue;
				}
				radiusSum += drops[i].radiusM;
				radiusCount++;
				probe.maxRadiusM = Math.max(probe.maxRadiusM, drops[i].radiusM);
				for (int j = i + 1; j < drops.length; j++) {
					if (!drops[j].visible) {
						continue;
					}
					final double delta = Math.hypot((drops[i].x - drops[j].x) * WIDTH_M,
							(drops[i].y - drops[j].y) * HEIGHT_M);
					final double contact = drops[i].radiusM + drops[j].radiusM;
					if (delta >= contact) {
						continue;
					}
					probe.overlapping++;
					overlapSum += contact - delta;
					if (delta <= MERGE_DISTANCE_M) {
						probe.wouldMerge++;
					} else {
						probe.refused++;
						probe.worstRefusedM = Math.max(probe.worstRefusedM, contact - delta);
					}
				}
			}
			probe.alive = radiusCount;
			probe.meanRadiusM = radiusCount == 0 ? 0 : radiusSum / radiusCount;
			probe.meanOverlapM = probe.overlapping == 0 ? 0 : overlapSum / probe.overlapping;
			probe.mergeEvents = mergeEvents;
			probe.mergedRadiusM = mergedRadiusM;
			return probe;
		}

		/** What the merge rule does with the overlaps that are actually present. */
		private static final class MergeProbe {
			private int alive;
			private int overlapping;
			private int wouldMerge;
			private int refused;
			private double worstRefusedM;
			private double meanOverlapM;
			private double meanRadiusM;
			private double maxRadiusM;
			private int mergeEvents;
			private double mergedRadiusM;
		}

		/**
		 * THE MERGE RULE, ASKED DIRECTLY. Field statistics cannot say whether the rule is right - they only
		 * say what the rule did to one field - so this puts two beads at a known separation with known radii
		 * and asks the rule itself. The cases are chosen at the two ends of the size range: two specks, and
		 * two grown beads, which is the pair the old fixed-millimetre comparison could never join.
		 */
		private MergeRuleProbe mergeRuleProbe() {
			final MergeRuleProbe probe = new MergeRuleProbe();
			// radii in mm, centre separation in mm, whether they are TOUCHING (and so must merge)
			final double[][] cases = {
					{3.5, 3.5, 6.0, 1},   // contact 7.0 mm: overlap 1.0 mm - merge
					{3.5, 3.5, 8.0, 0},   // contact 7.0 mm: 1.0 mm apart - do NOT merge
					{8.0, 8.0, 15.0, 1},  // contact 16.0 mm: overlap 1.0 mm - merge
					{8.0, 8.0, 17.0, 0},  // contact 16.0 mm: 1.0 mm apart - do NOT merge
					{14.0, 14.0, 26.0, 1},// contact 28.0 mm: the pair the old 12 mm rule could NEVER join
					{14.0, 3.5, 16.0, 1}, // contact 17.5 mm: a grown bead absorbing a speck
					{14.0, 3.5, 19.0, 0}  // contact 17.5 mm: 1.5 mm apart - do NOT merge
			};
			for (final double[] test : cases) {
				for (int i = 0; i < drops.length; i++) {
					drops[i].visible = false;
				}
				final Drop a = drops[0];
				final Drop b = drops[1];
				a.visible = true;
				b.visible = true;
				a.carriedByBlade = false;
				b.carriedByBlade = false;
				a.inCollectZone = false;
				b.inCollectZone = false;
				a.radiusM = test[0] / 1000.0;
				b.radiusM = test[1] / 1000.0;
				a.x = 0.5;
				a.y = 0.5;
				b.x = 0.5;
				b.y = 0.5 + (test[2] / 1000.0) / HEIGHT_M;
				final int before = mergeEvents;
				mergePair(a, b);
				final boolean merged = mergeEvents > before;
				probe.cases++;
				if (merged == (test[3] > 0.5)) {
					probe.correct++;
				} else {
					probe.wrong++;
					probe.lastWrong = String.format("r=%.1f/%.1f mm at %.1f mm apart: %s, expected %s",
							test[0], test[1], test[2], merged ? "merged" : "did not merge",
							test[3] > 0.5 ? "merged" : "not merged");
				}
			}
			return probe;
		}

		/** How the merge rule answers a table of exact, known cases. */
		private static final class MergeRuleProbe {
			private int cases;
			private int correct;
			private int wrong;
			private String lastWrong = "";
		}

		/**
		 * WHAT COALESCENCE DOES TO A FIELD OVER TIME, which the exact-case table cannot say: run the same
		 * rain for the same time under one rule and count the merges that actually happen and the sizes that
		 * come out. Both runs start from an identical field (settled with merging off), so the only
		 * difference between them is the rule.
		 */
		private MergeRunProbe mergeRunProbe(double seconds) {
			final MergeRunProbe probe = new MergeRunProbe();
			final int base = mergeEvents;
			simulate(seconds, 0, 1.0F, Mode.OFF);
			probe.merges = mergeEvents - base;
			double radiusSum = 0;
			int count = 0, atCap = 0, big = 0;
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				count++;
				radiusSum += drop.radiusM;
				probe.maxRadiusM = Math.max(probe.maxRadiusM, drop.radiusM);
				if (drop.radiusM >= MAX_BEAD_RADIUS_M - 1.0E-9) {
					atCap++;
				}
				if (drop.radiusM >= 0.010) {
					big++;
				}
			}
			probe.alive = count;
			probe.meanRadiusM = count == 0 ? 0 : radiusSum / count;
			probe.atCap = atCap;
			probe.big = big;
			return probe;
		}

		/** Merges and sizes over a window of rain. */
		private static final class MergeRunProbe {
			private int merges;
			private int alive;
			private int atCap;
			private int big;
			private double meanRadiusM;
			private double maxRadiusM;
		}

		// ---- probes ------------------------------------------------------------------------------

		private int alive() {
			int alive = 0;
			for (final Drop drop : drops) {
				if (drop.visible) {
					alive++;
				}
			}
			return alive;
		}

		private int flowing() {
			int flowing = 0;
			for (int index = 0; index < drops.length; index++) {
				if (drops[index].visible && densityPerM2[index] > DENSITY_THRESHOLD_PER_M2) {
					flowing++;
				}
			}
			return flowing;
		}

		private double flowingFraction() {
			return (double) flowing() / Math.max(1, alive());
		}

		private double meanDensity() {
			double sum = 0;
			int count = 0;
			for (int index = 0; index < drops.length; index++) {
				if (drops[index].visible) {
					sum += densityPerM2[index];
					count++;
				}
			}
			return count == 0 ? 0 : sum / count;
		}

		private double maxDensity() {
			double max = 0;
			for (int index = 0; index < drops.length; index++) {
				if (drops[index].visible) {
					max = Math.max(max, densityPerM2[index]);
				}
			}
			return max;
		}

		private int movers() {
			int movers = 0;
			for (final Drop drop : drops) {
				if (drop.visible && drop.surfaceSpeedMps > 1.0E-9) {
					movers++;
				}
			}
			return movers;
		}

		private double meanSpeedOfFlowing() {
			double sum = 0;
			int count = 0;
			for (int index = 0; index < drops.length; index++) {
				if (drops[index].visible && densityPerM2[index] > DENSITY_THRESHOLD_PER_M2) {
					sum += drops[index].surfaceSpeedMps;
					count++;
				}
			}
			return count == 0 ? 0 : sum / count;
		}

		/** Leaves exactly one bead on the glass, so the single-bead limit of the density rule can be read. */
		private void keepOnlyOneForTest() {
			for (int index = 1; index < drops.length; index++) {
				drops[index].visible = false;
			}
			drops[0].visible = true;
			drops[0].x = 0.5;
			drops[0].y = 0.5;
			measureDensity();
		}

		/** Runs one blade pass, accumulating the shove report over it. */
		private PushReport strokeAndMeasurePush(double seconds) {
			return strokeAndMeasurePush(seconds, 0);
		}

		/**
		 * The same, with the ability to stop at the end of the pass and let the pile sit.
		 *
		 * <p>Each call runs ONE half-stroke. {@code halfPeriodsDone} restarts the wiper animation at that
		 * half, so call 0 is an outward stroke from park and call 1 is an outward stroke from the far end
		 * (the animation stores 0 at the end of every half, which makes the two halves congruent under the
		 * linkage mirror). To exercise a genuine REVERSAL across the two halves, see
		 * {@link #outAndBackProbe}.</p>
		 *
		 * <p>{@code parkedFrames} holds the blade STILL at the end of the pass (wiper switched off, not
		 * merely left to run) while the pile it built runs.</p>
		 */
		private PushReport strokeAndMeasurePush(double seconds, int halfPeriodsDone) {
			return strokeAndMeasurePush(seconds, halfPeriodsDone, 0);
		}

		private PushReport strokeAndMeasurePush(double seconds, int halfPeriodsDone, int parkedFrames) {
			final PushReport report = new PushReport();
			java.util.Arrays.fill(pushedThisStroke, false);
			java.util.Arrays.fill(pushAccumM, 0);
			pushedOffTotal = 0;
			strokeBladeTravelM = 0;
			strokeReleaseTotal = 0;
			midStrokeReleaseCount = 0;
			for (final Drop drop : drops) {
				drop.carriedPathM = 0;
				drop.survivedFromGeneration = drop.generation;
			}
			report.fieldMeanDensityBefore = meanDensity();
			mode = Mode.SLOW;
			phase = halfPeriodsDone;
			// The thirds are ordered ALONG THE DIRECTION OF TRAVEL, so which axis they are counted on has
			// to be established before the "before" reading, not after the stroke. Reading it afterwards
			// used to leave the before-count on the previous glass's travel axis.
			final double[] direction = strokeDirection(halfPeriodsDone % 2 == 0);
			lastPushX = direction[0];
			lastPushY = direction[1];
			strokeAxisX = direction[0];
			strokeAxisY = direction[1];
			report.travelAxisX = lastPushX;
			report.travelAxisY = lastPushY;
			report.thirdsBefore = thirds();
			report.oldThirdBefore = thirds(true);
			final int steps = (int) Math.round(seconds * 62.5);
			strokeMinAngleDeg = Double.MAX_VALUE;
			strokeMaxAngleDeg = -Double.MAX_VALUE;
			for (int i = 0; i < steps; i++) {
				advance(0.016, 0, 1.0F);
				report.pushedOff += pushedOffTotal;
				pushedOffTotal = 0;
				report.eventCount += framePushedCount;
			}
			// THE WINDROW, measured at the END of the pass over the beads the blade actually gathered: the
			// claim being tested is that gathering raises their local density above the glass's own, which
			// is the only way the pile can then run. Its speed is the belt-and-braces half of the same
			// claim - a dense pile that is still pinned has not run.
			measureDensity();
			report.thirdsAfter = thirds();
			report.oldThirdAfter = thirds(true);
			double densitySum = 0, speedSum = 0;
			int counted = 0;
			double carrySum = 0;
			int carryCount = 0;
			int noCarryCount = 0;
			final java.util.List<Double> samples = new java.util.ArrayList<>();
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				// The CARRY probe: how far did the blade actually CARRY this bead over the whole pass? A
				// bead the blade merely nudged once scores PUSH_M; a bead the blade ploughed scores however
				// far the blade travelled while holding it.
				if (drop.visible && drop.carriedPathM > 0) {
					carrySum += drop.carriedPathM;
					carryCount++;
					samples.add(drop.carriedPathM);
				} else if (drop.visible && pushedThisStroke[index]) {
					noCarryCount++;
				}
				if (!pushedThisStroke[index] || !drop.visible) {
					continue;
				}
				report.pushed++;
				report.pushDistanceM += pushAccumM[index];
				densitySum += densityPerM2[index];
				speedSum += drop.surfaceSpeedMps;
				report.windrowMaxDensity = Math.max(report.windrowMaxDensity, densityPerM2[index]);
				counted++;
			}
			report.carryCount = carryCount;
			report.noCarryCount = noCarryCount;
			java.util.Collections.sort(samples);
			report.carriedSamplesM = new double[samples.size()];
			for (int i = 0; i < samples.size(); i++) {
				report.carriedSamplesM[i] = samples.get(i);
			}
			report.bladeTravelM = strokeBladeTravelM;
			report.meanCarriedM = carryCount == 0 ? 0 : carrySum / carryCount;
			report.windrowMeanDensity = counted == 0 ? 0 : densitySum / counted;
			report.windrowSpeed = counted == 0 ? 0 : speedSum / counted;
			report.fieldMeanDensityAfter = meanDensity();
			// The pile, left alone: the blade stops travelling and the water it ploughed up is free to run
			// or to stay. `waveCount` right after the pass is the size of the pile the blade built.
			report.waveCount = waveCount;
			report.waveReleased = waveReleasedThisFrame;
			report.waveReleasedTotal = waveReleasedTotal;
			report.wavePeak = wavePeak;
			report.strokeMinAngleDeg = strokeMinAngleDeg;
			report.strokeMaxAngleDeg = strokeMaxAngleDeg;
			// PARKED means PARKED - the wiper is switched off, not merely left to run on. The first version
			// of this left Mode.SLOW set, so the "parked" frames ran the animation through the whole return
			// half and the next measured stroke was another outward one.
			mode = Mode.OFF;
			for (int i = 0; i < parkedFrames; i++) {
				advance(0.016, 0, 1.0F);
			}
			report.parkedAngleDeg = angleDeg;
			report.thirdsAfterPark = thirds();
			return report;
		}

		/**
		 * DOES THE WIPER MOVE THE WHOLE SCREEN? The controlled version of "when the wiper sweeps outward the
		 * whole windscreen's rain slides sideways": two identical settled glasses, the same duration, one
		 * with the wiper running an outward half-stroke and one with it OFF. The OFF run is the same field
		 * doing whatever rain does on its own (gravity, runoff, spawning), so the DIFFERENCE between the two
		 * is what the blade did - to the field as a whole, not to the beads it happened to touch.
		 *
		 * <p>The two numbers that matter are the bulk shift of every surviving bead, split into the
		 * component ALONG the blade's direction of travel and the component ACROSS it. A wiper is allowed to
		 * move the water it crosses a long way along its own direction; it is not allowed to slide the glass
		 * sideways, and it is not allowed to move more water than it crosses.</p>
		 */
		private ShiftProbe bulkShiftProbe(double seconds) {
			return bulkShiftProbe(seconds, false);
		}

		private ShiftProbe bulkShiftProbe(double seconds, boolean parked) {
			final ShiftProbe probe = new ShiftProbe();
			// The blade either runs an outward half-stroke or is left parked; the field around it is the
			// same field either way, which is what makes the two runs subtractable.
			if (parked) {
				mode = Mode.OFF;
			} else {
				mode = Mode.SLOW;
				phase = 0;
			}
			java.util.Arrays.fill(pushedThisStroke, false);
			final double[] x0 = new double[drops.length];
			final double[] y0 = new double[drops.length];
			final int[] gen0 = new int[drops.length];
			for (int index = 0; index < drops.length; index++) {
				x0[index] = drops[index].x;
				y0[index] = drops[index].y;
				gen0[index] = drops[index].generation;
			}
			final double[] direction = strokeDirection(true);
			strokeAxisX = direction[0];
			strokeAxisY = direction[1];
			final int steps = (int) Math.round(seconds * 62.5);
			double pushSumX = 0, pushSumY = 0;
			int pushFrames = 0;
			for (int i = 0; i < steps; i++) {
				advance(0.016, 0, 1.0F);
				if (lastPushX != 0 || lastPushY != 0) {
					pushSumX += lastPushX;
					pushSumY += lastPushY;
					pushFrames++;
				}
			}
			probe.directionX = direction[0];
			probe.directionY = direction[1];
			probe.pushX = pushFrames == 0 ? 0 : pushSumX / pushFrames;
			probe.pushY = pushFrames == 0 ? 0 : pushSumY / pushFrames;
			// THE SPLIT AXIS IS THE BLADE'S OWN PUSH, measured, not strokeDirection()'s synthetic outward
			// bearing. They are not the same vector on this linkage (measured -0.92, 0.01 against -0.77,
			// 0.64), and splitting on the wrong one dumps the whole gravity/runoff component into the
			// "across" number - which reads as a lateral slide that is not there.
			final double[] axis = unitOrZero(probe.pushX, probe.pushY);
			probe.axisX = axis[0];
			probe.axisY = axis[1];
			double alongSum = 0, sideSum = 0, alongWorst = 0, sideWorst = 0, dxSum = 0, dySum = 0;
			double untouchedWorst = 0;
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible || drop.generation != gen0[index]) {
					continue;
				}
				final double dx = (drop.x - x0[index]) * WIDTH_M;
				final double dy = (drop.y - y0[index]) * HEIGHT_M;
				dxSum += dx;
				dySum += dy;
				final double along = dx * axis[0] + dy * axis[1];
				final double side = -dx * axis[1] + dy * axis[0];
				alongSum += along;
				sideSum += side;
				alongWorst = Math.max(alongWorst, Math.abs(along));
				sideWorst = Math.max(sideWorst, Math.abs(side));
				if (Math.hypot(dx, dy) > 0.01) {
					probe.moved++;
				}
				if (drop.carriedPathM > 0 || pushedThisStroke[index]) {
					probe.touched++;
				} else {
					// A bead the blade never worked. Rain on its own may still run DOWN (that is the density
					// gate doing its job) but it has no business moving ALONG the sweep, and that is the
					// difference between "the wiper moved the rain it crossed" and "the wiper moved the
					// whole screen".
					probe.untouched++;
					untouchedWorst = Math.max(untouchedWorst, Math.abs(along));
				}
				probe.survivors++;
			}
			probe.untouchedWorstAlongM = untouchedWorst;
			probe.meanAlongM = probe.survivors == 0 ? 0 : alongSum / probe.survivors;
			probe.meanSideM = probe.survivors == 0 ? 0 : sideSum / probe.survivors;
			probe.meanDxM = probe.survivors == 0 ? 0 : dxSum / probe.survivors;
			probe.meanDyM = probe.survivors == 0 ? 0 : dySum / probe.survivors;
			probe.worstAlongM = alongWorst;
			probe.worstSideM = sideWorst;
			return probe;
		}

		/** What one wiper stroke did to the field AS A WHOLE, versus the same field left alone. */
		private static final class ShiftProbe {
			private double directionX;
			private double directionY;
			/** The mean of the blade's own push direction over the frames that had one. */
			private double pushX;
			private double pushY;
			/** The unit vector the split actually used: the measured push. */
			private double axisX;
			private double axisY;
			private int survivors;
			private int moved;
			private int touched;
			private int untouched;
			/** Worst along-sweep displacement among the beads the blade never worked. */
			private double untouchedWorstAlongM;
			private double meanAlongM;
			private double meanSideM;
			/** The mean DISPLACEMENT of a surviving bead, in glass metres - the raw vector. */
			private double meanDxM;
			private double meanDyM;
			private double worstAlongM;
			private double worstSideM;
		}

		/** Visible beads per third of the glass, ordered along the blade's travel axis. */
		private int[] thirds() {
			return thirds(false);
		}

		/**
		 * OUT AND BACK on one continuous animation, which is the only way to get a real TURNAROUND.
		 *
		 * <p>{@link #strokeAndMeasurePush} restarts the wiper phase at the half it is asked for, so two
		 * calls of it are two separate outward sweeps (the animation stores 0 at the end of every half).
		 * Here the phase is left to run: the blade goes out, dwells, and comes back, and what is measured
		 * is (a) how many carried beads the reversal let go of, and (b) where the water the outward stroke
		 * piled up was left. A blade that keeps hold of its wave through the reversal drags the whole pile
		 * back and the second number collapses back to the first.</p>
		 */
		private BackReport outAndBackProbe() {
			final BackReport report = new BackReport();
			mode = Mode.SLOW;
			phase = 0;
			// The travel axis is fixed up front for the same reason strokeAndMeasurePush does it: the
			// "before" counts are read before the blade has moved once, so there is no wipe frame yet to
			// take a direction from.
			final double[] outward = strokeDirection(true);
			strokeAxisX = outward[0];
			strokeAxisY = outward[1];
			// The beads present before the blade moved are marked, so the turnaround reading can say how
			// many of THEM the outward stroke moved - the spawner keeps adding fresh water throughout.
			for (final Drop drop : drops) {
				drop.survivedFromGeneration = drop.generation;
			}
			report.thirdsBefore = thirds();
			report.oldThirdBefore = thirds(true);
			final int steps = (int) Math.round(SLOW_PERIOD_S * 62.5);
			for (int i = 0; i < steps; i++) {
				advance(0.016, 0, 1.0F);
				// The pile the outward stroke built, read at the moment the animation turns it around: the
				// phase reaches the end of the outward half, which is exactly when the blade reverses.
				if (!report.capturedTurnaround && phase >= 1) {
					report.capturedTurnaround = true;
					report.thirdsAtTurnaround = thirds();
					report.oldThirdAtTurnaround = thirds(true);
				}
				report.released += waveReleasedThisFrame;
			}
			report.thirdsAfter = thirds();
			return report;
		}

		/** What one full out-and-back cycle did, for the turnaround assertions. */
		private static final class BackReport {
			private boolean capturedTurnaround;
			private int released;
			private int[] thirdsBefore = new int[3];
			private int[] oldThirdBefore = new int[3];
			private int[] thirdsAtTurnaround = new int[3];
			private int[] oldThirdAtTurnaround = new int[3];
			private int[] thirdsAfter = new int[3];
		}

		/** The direction the blade will travel on an outward (or the mirrored inward) stroke. */
		private double[] strokeDirection(boolean outward) {
			if (sectorWipe) {
				final double theta = Math.toRadians((outward ? 0 : sweepDeg) * sweepSign);
				return unitOrZero(-Math.sin(theta), Math.cos(theta));
			}
			final double startAngle = parkDeg + (outward ? 0 : sweepDeg);
			final double[][] from = bladeSegmentM(startAngle);
			final double[][] to = bladeSegmentM(startAngle + 1.0E-3 * sweepSign);
			return unitOrZero((to[0][0] + to[1][0] - from[0][0] - from[1][0]) / 2,
					(to[0][1] + to[1][1] - from[0][1] - from[1][1]) / 2);
		}

		/**
		 * The same count, optionally restricted to the beads that were on the glass at the start of the
		 * stroke (matched by the generation they were on - see {@code applyWipe}). Restricting the sample
		 * is what turns "how many beads are in this third" into "did the blade MOVE water into this third",
		 * because the spawner keeps adding fresh beads uniformly while the blade works.
		 */
		private int[] thirds(boolean onlyOld) {
			final int[] counts = new int[3];
			// THE STROKE'S OWN AXIS, not "whatever the last applyWipe happened to push along". Those are
			// the same thing in a run where every step moves the blade, and they are NOT the same in the
			// duplicate-pass probe below: a step that does not move the blade has no direction of travel at
			// all, so it sets lastPushX/lastPushY to zero and silently switches this bucket from the X axis
			// to the Y one. That reads as the water having gone somewhere else when nothing moved - which is
			// precisely the mistake this file exists to avoid.
			final boolean useX = Math.abs(strokeAxisX) > Math.abs(strokeAxisY);
			for (final Drop drop : drops) {
				if (!drop.visible) {
					continue;
				}
				if (onlyOld && drop.generation != drop.survivedFromGeneration) {
					continue;
				}
				final double value = useX ? drop.x : drop.y;
				counts[Math.max(0, Math.min(2, (int) (value * 3)))]++;
			}
			return counts;
		}

		/**
		 * Parks the blade and measures how the windrow moved, against the beads the blade never touched.
		 *
		 * <p>Each bead's own travel is measured, not the field's mean position: the field's mean Y is held
		 * near 0.5 by construction (respawns are uniform), so it cannot answer "did the pushed water run".
		 * A bead whose GENERATION changed was respawned during the window - i.e. it either ran off the
		 * bottom or was shoved off the glass - and is dropped from the sample, because its displacement is
		 * a teleport rather than motion.</p>
		 */
		private RunReport measureWindrowRun(double seconds) {
			mode = Mode.OFF;
			final double[] startX = new double[drops.length];
			final double[] startY = new double[drops.length];
			final int[] generation = new int[drops.length];
			final boolean[] sampled = new boolean[drops.length];
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!drop.visible) {
					continue;
				}
				sampled[index] = true;
				startX[index] = drop.x;
				startY[index] = drop.y;
				generation[index] = drop.generation;
			}
			simulate(seconds, 0, 1.0F, Mode.OFF);
			final RunReport report = new RunReport();
			int windrowCount = 0, otherCount = 0;
			double windrowTravel = 0, otherTravel = 0, windrowSpeed = 0, otherSpeed = 0;
			for (int index = 0; index < drops.length; index++) {
				final Drop drop = drops[index];
				if (!sampled[index] || !drop.visible || drop.generation != generation[index]) {
					continue;
				}
				final double travel = Math.hypot(drop.x - startX[index], drop.y - startY[index]) * HEIGHT_M;
				if (pushedThisStroke[index]) {
					windrowCount++;
					windrowTravel += travel;
					windrowSpeed += drop.surfaceSpeedMps;
				} else {
					otherCount++;
					otherTravel += travel;
					otherSpeed += drop.surfaceSpeedMps;
				}
			}
			report.windrowTravelMm = windrowCount == 0 ? 0 : windrowTravel / windrowCount * 1000;
			report.windrowSpeed = windrowCount == 0 ? 0 : windrowSpeed / windrowCount;
			report.windrowCount = windrowCount;
			report.otherTravelMm = otherCount == 0 ? 0 : otherTravel / otherCount * 1000;
			report.otherSpeed = otherCount == 0 ? 0 : otherSpeed / otherCount;
			report.otherCount = otherCount;
			return report;
		}
	}

	private static final class Drop {
		private double x;
		private double y;
		private final double variation;
		private final Random random;
		private double radiusM;
		private double lateralVelocity;
		private double surfaceSpeedMps;
		private double pinchAccumulatorM;
		private boolean visible;
		/** Mirrors Drop.carriedByBlade: the blade is PLOUGHING this bead along in front of its edge. */
		private boolean carriedByBlade;
		/**
		 * Mirrors Drop.inCollectZone: this bead was put down in the collection zone at one end of the
		 * stroke, and no later stroke picks it up again.
		 */
		private boolean inCollectZone;
		/** The sweep direction the blade was travelling in when this bead joined the wave. */
		private int directionAtPickup = 1;
		/**
		 * Total distance the blade has CARRIED this bead over the current stroke, in metres. This is the
		 * honest yardstick for "did the blade carry the water or just nudge it": the blade's mid-point
		 * travels a CURVE (the parallelogram is 0.79 m long and the sweep is 80 deg, so the blade rotates
		 * through the whole sweep), which makes the straight-line displacement to the end point 10% shorter
		 * than the path actually travelled and would score a perfect carry at 0.90 for no reason.
		 */
		private double carriedPathM;
		/** Bumped on every respawn, so a probe can tell "moved" from "was replaced by a new bead". */
		private int generation;
		/** The generation this bead was on when the current stroke began; a mismatch means it respawned. */
		private int survivedFromGeneration = -1;

		private Drop(double x, double y, double variation, Random random) {
			this.x = x;
			this.y = y;
			this.variation = variation;
			this.random = random;
		}
	}

	private static double wrap(double value) {
		return value - Math.floor(value);
	}
}
