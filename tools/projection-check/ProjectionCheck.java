/**
 * Offline check of the interaction prompt's world-to-screen projection.
 *
 * <p>Settles one question with arithmetic instead of by eye: which cross-product order gives the camera's
 * screen axes. Both orders have been shipped in {@code MmtrInteractPrompt} at different times and each
 * one silently mirrored an axis, so this file pins all three cases:</p>
 *
 * <ol>
 *   <li>unit checks at yaw 0 - the camera faces +Z, so right must be (-1,0,0) and up (0,1,0);</li>
 *   <li>turning the view left must move a fixed target RIGHT;</li>
 *   <li>looking up must move a fixed target DOWN.</li>
 * </ol>
 *
 * <p>Only the "right = forward x worldUp, up = right x forward" basis passes all three.</p>
 *
 * <p>Run from the workspace root:</p>
 * <pre>
 *   . env\workspace.env.ps1
 *   &amp; "$env:JAVA_HOME\bin\java.exe" mmtr\tools\projection-check\ProjectionCheck.java
 * </pre>
 */
public final class ProjectionCheck {

	// From a real log line:
	// [MMTR-PROMPT] [G] 进入驾驶室1 屏幕=(483,354) 中心=(480,280) 窗口=960x561
	//   投影=FOV 重建 scaleX=1.1982 scaleY=0.7002
	//   相机=(-71.0,-55.89,-132.56) yaw=-87.75 pitch=17.55 目标=(-68.91,-57.0,-132.49)
	private static final double CAMERA_X = -71.0;
	private static final double CAMERA_Y = -55.89;
	private static final double CAMERA_Z = -132.56;
	private static final double TARGET_X = -68.91;
	private static final double TARGET_Y = -57.0;
	private static final double TARGET_Z = -132.49;
	private static final int WIDTH = 960;
	private static final int HEIGHT = 561;
	/** The FOV that was in force for the logged frame, derived from its scale (0.7002 = tan(35)). */
	private static final double LOGGED_FOV = 70;

	/**
	 * Projection scale for a given FOV: scaleY = tan(fov/2), scaleX = scaleY * aspect.
	 *
	 * <p>Kept as a parameter rather than a constant because "does it adapt when the FOV changes" is a real
	 * failure mode with a real cause: an FOV that is wrong by a FACTOR looks perfect at exactly one setting
	 * and drifts everywhere else. The in-game report was "FOV 60 is perfect, turning it up does not adapt",
	 * and the cause was an extra x0.1 on the option value (see MmtrInteractPrompt.fovDegrees).</p>
	 */
	private static double[] scaleFor(double fovDegrees) {
		final double scaleY = Math.tan(Math.toRadians(fovDegrees) / 2);
		return new double[] {scaleY * WIDTH / HEIGHT, scaleY};
	}

	/** Sanity table: the scale must move with the FOV, and match the logged line at one setting. */
	private static void printScaleTable() {
		System.out.println("== 5. Projection scale must follow the FOV setting ==");
		for (final double fov : new double[] {30, 60, 70, 90, 110}) {
			final double[] scale = scaleFor(fov);
			System.out.printf("  FOV %5.1f  scaleY=%.4f  scaleX=%.4f%n", fov, scale[1], scale[0]);
		}
		System.out.println("  logged line was FOV 70: scaleY=0.7002 scaleX=1.1982  (matches the FOV 70 row)");
		System.out.println("  a WRONG-BY-A-FACTOR fov matches at one row only, which is the reported symptom");
	}

	public static void main(String[] args) {
		System.out.println("== 1. Unit checks (yaw 0, pitch 0): camera faces +Z, so right=(-1,0,0), up=(0,1,0) ==");
		for (final Order order : Order.values()) {
			final double[] basis = basisAt(order, 0, 0);
			System.out.printf("  %-18s right=(%+.3f, %+.3f, %+.3f)  up=(%+.3f, %+.3f, %+.3f)  %s%n",
					order, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5],
					Math.abs(basis[0] + 1) < 1.0E-6 && Math.abs(basis[4] - 1) < 1.0E-6 ? "PASS" : "FAIL");
		}
		System.out.println();

		System.out.println("== 2. Turn the view LEFT (yaw 272.25 -> 262.25): target must move RIGHT ==");
		for (final Order order : Order.values()) {
			final double before = project(order, -87.75, 0, TARGET_X, TARGET_Y, TARGET_Z)[0];
			final double after = project(order, -97.75, 0, TARGET_X, TARGET_Y, TARGET_Z)[0];
			System.out.printf("  %-18s x %.1f -> %.1f  moved %+.1f  %s%n",
					order, before, after, after - before, after > before ? "PASS (right)" : "FAIL (left)");
		}
		System.out.println();

		System.out.println("== 3. Look UP (pitch 0 -> -10): target must move DOWN ==");
		for (final Order order : Order.values()) {
			final double before = project(order, -87.75, 0, TARGET_X, TARGET_Y, TARGET_Z)[1];
			final double after = project(order, -87.75, -10, TARGET_X, TARGET_Y, TARGET_Z)[1];
			System.out.printf("  %-18s y %.1f -> %.1f  moved %+.1f  %s%n",
					order, before, after, after - before, after > before ? "PASS (down)" : "FAIL (up)");
		}
		System.out.println();

		System.out.println("== 4. Reproduce the logged frame (yaw -87.75, pitch 17.55) ==");
		for (final Order order : Order.values()) {
			final double[] screen = project(order, -87.75, 17.55, TARGET_X, TARGET_Y, TARGET_Z);
			System.out.printf("  %-18s screen=(%.1f, %.1f)%n", order, screen[0], screen[1]);
		}
		System.out.println("  log = (483, 354)   <- the basis that ALSO passes 1-3 is the one to ship");
		System.out.println();

		printScaleTable();
	}

	private enum Order {
		/** right = normalize(worldUp x forward), up = forward x right. Mirrors both axes. */
		WORLDUP_CROSS_FORWARD,
		/** right = normalize(forward x worldUp), up = right x forward. Correct. */
		FORWARD_CROSS_WORLDUP
	}

	/** @return {rightX, rightY, rightZ, upX, upY, upZ} */
	private static double[] basisAt(Order order, double yawDegrees, double pitchDegrees) {
		final double yaw = Math.toRadians(yawDegrees);
		final double pitch = Math.toRadians(pitchDegrees);
		final double cosPitch = Math.cos(pitch);
		final double forwardX = -Math.sin(yaw) * cosPitch;
		final double forwardY = -Math.sin(pitch);
		final double forwardZ = Math.cos(yaw) * cosPitch;

		// right = normalize(forward x worldUp) with worldUp = (0,1,0), or the opposite order.
		final double sign = order == Order.FORWARD_CROSS_WORLDUP ? 1 : -1;
		double rightX = sign * -forwardZ;
		double rightZ = sign * forwardX;
		final double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
		rightX /= rightLength;
		rightZ /= rightLength;

		// up = right x forward. NOTE: always this order, for BOTH candidates.
		//
		// An earlier version of this file flipped the up sign together with the right sign, which made the
		// vertical test pass for a basis whose horizontal test failed - the two axes were being tested as a
		// package instead of independently, and that hid the real answer. The up order is a separate choice
		// and only one of them is correct, so it is held fixed here while `right` is what varies.
		final double upX = -rightZ * forwardY;
		final double upY = rightZ * forwardX - rightX * forwardZ;
		final double upZ = rightX * forwardY;
		return new double[] {rightX, 0, rightZ, upX, upY, upZ};
	}

	/** @return {screenX, screenY} */
	private static double[] project(Order order, double yawDegrees, double pitchDegrees, double x, double y, double z) {
		final double[] basis = basisAt(order, yawDegrees, pitchDegrees);
		final double rightX = basis[0];
		final double rightZ = basis[2];
		final double upX = basis[3];
		final double upY = basis[4];
		final double upZ = basis[5];

		final double yaw = Math.toRadians(yawDegrees);
		final double pitch = Math.toRadians(pitchDegrees);
		final double cosPitch = Math.cos(pitch);
		final double forwardX = -Math.sin(yaw) * cosPitch;
		final double forwardY = -Math.sin(pitch);
		final double forwardZ = Math.cos(yaw) * cosPitch;

		final double deltaX = x - CAMERA_X;
		final double deltaY = y - CAMERA_Y;
		final double deltaZ = z - CAMERA_Z;
		final double depth = deltaX * forwardX + deltaY * forwardY + deltaZ * forwardZ;
		final double cameraX = deltaX * rightX + deltaZ * rightZ;
		final double cameraY = deltaX * upX + deltaY * upY + deltaZ * upZ;
		final double[] scale = scaleFor(LOGGED_FOV);
		return new double[] {
				WIDTH / 2.0 + cameraX / (depth * scale[0]) * (WIDTH / 2.0),
				HEIGHT / 2.0 - cameraY / (depth * scale[1]) * (HEIGHT / 2.0)
		};
	}
}
