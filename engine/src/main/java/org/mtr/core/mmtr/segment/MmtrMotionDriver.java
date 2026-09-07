package org.mtr.core.mmtr.segment;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;

/**
 * Longitudinal driver on top of {@link MmtrMotionWalker} — the piece that actually <b>runs</b> a
 * consist forward tick by tick under Motion Core (M2-Core slice 4: the running train is driven by
 * segment+offset + node authority, not by a pre-baked MTR path). Each tick advances the walker by
 * {@code speed * dt}; the walker consumes only what the rail graph allows and stops advancing when
 * it reaches an unset fork (awaiting authority), the end of the line, or the commanded target rail —
 * at which point this driver brings the consist to rest. Speed/door logic of a full cab sits on top;
 * this is the deterministic core a Vehicle backend calls.
 */
public final class MmtrMotionDriver {

	public final MmtrMotionWalker walker;
	/** Current forward speed, m/ms (engine internal units). */
	private double speed;

	private MmtrMotionDriver(MmtrMotionWalker walker) {
		this.walker = walker;
	}

	public static MmtrMotionDriver start(Data data, Rail startRail, Position startAt, BranchStore branches, @Nullable String targetRailHex) {
		return new MmtrMotionDriver(MmtrMotionWalker.start(data, startRail, startAt, branches, targetRailHex));
	}

	public double speed() {
		return speed;
	}

	public boolean atTarget() {
		return walker.atTarget();
	}

	public boolean haltedAtAuthority() {
		return walker.haltedAtAuthority();
	}

	public boolean endOfLine() {
		return walker.endOfLine();
	}

	/** True once the consist has come to rest because it can no longer advance (target / authority / end). */
	public boolean stopped() {
		return speed <= 0 && (walker.atTarget() || walker.haltedAtAuthority() || walker.endOfLine());
	}

	/**
	 * Advance one tick at a commanded cruise (m/ms). The walker moves as far as the rail graph
	 * actually allows; the moment it can no longer advance (authority halt / end of line / target
	 * boarded) the driver brakes to rest.
	 */
	public void tick(long dtMs, double cruiseMetersPerMillisecond) {
		// Terminal conditions only stop the consist. A halt at an unset fork (自由开) is re-attempted
		// every tick so that once the operator/任务 sets the branch the same train simply continues.
		if (walker.atTarget() || walker.endOfLine()) {
			speed = 0;
			return;
		}
		final double cruise = Math.max(0, cruiseMetersPerMillisecond);
		speed = cruise;
		walker.advance(cruise * dtMs);
		if (walker.atTarget() || walker.haltedAtAuthority() || walker.endOfLine()) {
			speed = 0;
		}
	}


	/**
	 * Manual driving (手动开): a human controls throttle/brake; the train accelerates/coasts/brakes and
	 * advances by Motion Core (deciding each fork by the current turnout). It still halts at an unset
	 * fork until the driver/任务 sets it. Speeds are m/ms; accelerations m/ms^2.
	 */
	public void manualTick(long dtMs, boolean throttle, boolean brake, double accelMps2, double decelMps2, double maxMetersPerMs) {
		if (walker.atTarget() || walker.endOfLine()) {
			speed = 0;
			return;
		}
		if (brake) {
			speed = Math.max(0, speed - decelMps2 * dtMs);
		} else if (throttle) {
			speed = Math.min(maxMetersPerMs, speed + accelMps2 * dtMs);
		}
		if (speed > 0) {
			walker.advance(speed * dtMs);
			if (walker.atTarget() || walker.haltedAtAuthority() || walker.endOfLine()) {
				speed = 0;
			}
		}
	}

	/**
	 * Drives this consist with the engine's EXISTING drive control ({@link ControlState} — the same
	 * object MmtrDriveControl already sends to a Vehicle): throttle/brake notches become Motion Core
	 * accel/coast/brake, moving the train by (segment + offset) with forks decided live by authority.
	 * Motion Core is the motion UNDERNEATH the existing cab control, not a new control system.
	 */
	public void applyControl(ControlState control, long dtMs, double accelMps2, double decelMps2, double maxMetersPerMs) {
		if (control == null) {
			return;
		}
		final boolean brake = control.getBrakeNotch() > 0 || control.isEmergency();
		final boolean throttle = !brake && control.getThrottleNotch() > 0;
		manualTick(dtMs, throttle, brake, accelMps2, decelMps2, maxMetersPerMs);
	}

	/** Drive until rest or {@code maxTicks} elapsed at the given cruise; returns whether it came to rest. */
	public boolean driveToRest(long dtMs, double cruiseMetersPerMillisecond, int maxTicks) {
		for (int i = 0; i < maxTicks; i++) {
			tick(dtMs, cruiseMetersPerMillisecond);
			if (stopped()) {
				return true;
			}
		}
		return stopped();
	}
}