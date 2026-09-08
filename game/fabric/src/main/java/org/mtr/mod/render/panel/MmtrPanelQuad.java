package org.mtr.mod.render.panel;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * Draws a finished panel texture as a quad on a modelled anchor face.
 *
 * <p>The anchor carries an orthonormal frame in the OBJ file space ({@code right}/{@code up}/
 * {@code normal}), which is the same space MTR's model geometry is parsed into after the loader's
 * {@code (x, -y, -z)} flip. The panel plane is placed by solving the rotation matrix that maps the
 * panel's local axes onto that frame, then decomposing it back into the three Euler rotations
 * {@link GraphicsHolder} exposes. Doing it this way means the panel's "right" and "up" are exactly the
 * modeller's face edges, with no hard-coded roll fudge.</p>
 *
 * <p>The panel is drawn on BOTH sides of the face, each pushed out along its own normal. A face's
 * authored normal direction is easy to get wrong, and a one-sided panel then ends up buried inside the
 * dashboard; the back copy uses the same texture with its U coordinate flipped so the text still reads
 * correctly from that side. Both quads go into one render layer under one identifier, so they share a
 * single texture and no ordering rule between them is needed.</p>
 */
public final class MmtrPanelQuad {

	private MmtrPanelQuad() {
	}

	/**
	 * Lift the plane off the modelled face so it does not z-fight with the dashboard geometry. This is
	 * deliberately generous: the modelled dashboard can sit slightly proud of the anchor quad's plane,
	 * and a panel buried inside it shows up as a speckled mix of panel and body colours.
	 */
	private static final float SURFACE_OFFSET_M = 0.03F;
	/** Panels already described in the log, so the geometry is reported once per texture. */
	private static final ObjectOpenHashSet<String> DEBUG_LOGGED = new ObjectOpenHashSet<>();

	public static void draw(Identifier texture, Anchor anchor, StoredMatrixTransformations carTransform, double widthM, double heightM) {
		if (texture == null || widthM <= 0 || heightM <= 0) {
			return;
		}

		final Vector position = toModelSpace(anchor.filePosition);
		final Vector normal = toModelSpace(anchor.fileNormal).normalize();
		final Vector up = orthonormalise(toModelSpace(anchor.fileUp).normalize(), normal);
		final Vector right = cross(up, normal).normalize();
		final double halfWidthM = widthM / 2;
		final double halfHeightM = heightM / 2;
		final int chosenSide = anchor.panelTwoSided ? 0 : facingSide(anchor);

		if (DEBUG_LOGGED.add(texture.toString())) {
			Init.LOGGER.info("[MMTR-DBG] panel {} anchor={} modelPos={} modelNormal={} modelUp={} modelRight={} chosenSide={} (model space = OBJ file space (x, -y, -z))",
					texture, anchor.name, format(position), format(normal), format(up), format(right), chosenSide);
			for (final int side : new int[]{1, -1}) {
				final Vector sideNormal = scale(normal, side);
				Init.LOGGER.info("[MMTR-DBG]   side {} yaw={} pitch={} roll={} flipU={} offset={}",
						side,
						round(Math.toDegrees(Math.atan2(sideNormal.x(), sideNormal.z()))),
						round(Math.toDegrees(Math.asin(clamp(-up.y())))),
						round(Math.toDegrees(Math.atan2(up.x(), up.y()))),
						anchor.panelFlipU != (side > 0),
						SURFACE_OFFSET_M);
			}
		}

		for (final int side : chosenSide == 0 ? new int[]{1, -1} : new int[]{chosenSide}) {
			// The back copy is the same frame rotated 180 degrees about its own up axis: right and
			// normal are negated, up stays. The frame stays right-handed, so it is still a rotation.
			final Vector sideNormal = scale(normal, side);
			final Vector sideRight = scale(right, side);

			// Solve rotateYDegrees(yaw) * rotateXDegrees(pitch) * rotateZDegrees(roll) =
			// [sideRight, up, sideNormal] (as columns). See the class comment for the derivation.
			final double pitch = Math.toDegrees(Math.asin(clamp(-up.y())));
			final double yaw = Math.toDegrees(Math.atan2(sideNormal.x(), sideNormal.z()));
			final double roll = Math.toDegrees(Math.atan2(up.x(), up.y()));
			// The panel's local +X must run towards the driver's right so the readout is not mirrored.
			// The driver looks along the car's -Z (the dashboard is ahead of them), so their right is
			// the car's +X. The frame above maps local +X onto sideRight = side * anchor.right, and for
			// the side the driver sits on (side = -1) that lands on the car's +X, i.e. already correct;
			// the opposite copy (side = +1) ends up mirrored and needs the U flip. panelFlipU inverts
			// this for faces authored the other way round.
			final boolean flipU = anchor.panelFlipU != (side > 0);

			final StoredMatrixTransformations transformations = carTransform.copy();
			transformations.add(graphicsHolder -> graphicsHolder.translate(position.x(), position.y(), position.z()));
			transformations.add(graphicsHolder -> {
				graphicsHolder.rotateYDegrees((float) yaw);
				graphicsHolder.rotateXDegrees((float) pitch);
				graphicsHolder.rotateZDegrees((float) roll);
			});
			transformations.add(graphicsHolder -> graphicsHolder.translate(-halfWidthM, -halfHeightM, SURFACE_OFFSET_M));

			MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.LIGHT_2, (graphicsHolder, offset) -> {
				transformations.transform(graphicsHolder, offset);
				// The corners are given bottom-left, bottom-right, top-right, top-left. That order is
				// the OPPOSITE of what IDrawing.drawTexture's rectangle overload produces, and it is
				// deliberate: MTR's rectangle overload winds the quad so that its front face points at
				// local -Z, while this panel offsets the quad along local +Z to lift it off the modelled
				// face. With the default winding the visible copy would be the one pushed INTO the
				// dashboard (Minecraft culls back faces and RenderLayer.getText keeps culling enabled),
				// which is exactly how the panel ends up hidden behind its own dashboard.
				// Corner order is bottom-left, bottom-right, top-right, top-left, and MTR's 12-float
				// drawTexture assigns the four UV pairs as (u1,v2) (u2,v2) (u2,v1) (u1,v1) - i.e. v2 is
				// the TOP edge and v1 the bottom, the opposite of the rectangle overload's naming. So
				// v1=0 (image top) belongs to the top corners: pass 0 for v1 and 1 for v2 to keep the
				// panel upright.
				final float uLeft = flipU ? 1 : 0;
				final float uRight = flipU ? 0 : 1;
				IDrawing.drawTexture(
						graphicsHolder,
						0, 0, 0,
						(float) widthM, 0, 0,
						(float) widthM, (float) heightM, 0,
						0, (float) heightM, 0,
						uLeft, 0, uRight, 1,
						Direction.UP, IGui.ARGB_WHITE, GraphicsHolder.getDefaultLight()
				);
				graphicsHolder.pop();
			});
		}
	}

	/**
	 * Converts an anchor coordinate from the OBJ file space into the space MTR renders the model in.
	 * Verified against MTR's own loader: a part at file {@code z = +1.09} is parsed at {@code z = -1.09}.
	 */
	public static Vector toModelSpace(Vector fileVector) {
		return new Vector(fileVector.x(), -fileVector.y(), -fileVector.z());
	}

	/**
	 * @return {@code 1} or {@code -1} for the side of the face the driver sits on, {@code 0} when it
	 * cannot be decided. In model space the car is centred on the origin, so the car's interior is the
	 * direction from the anchor towards the origin; the driver's side is the one whose normal points
	 * that way. This keeps a single panel instead of two back-to-back copies.
	 */
	private static int facingSide(Anchor anchor) {
		final Vector position = toModelSpace(anchor.filePosition);
		final Vector normal = toModelSpace(anchor.fileNormal).normalize();
		final double horizontal = normal.x() * -position.x() + normal.z() * -position.z();
		if (Math.abs(horizontal) < 1.0E-4) {
			return 0;
		}
		return horizontal > 0 ? 1 : -1;
	}

	private static Vector orthonormalise(Vector vector, Vector normal) {
		final double dot = vector.x() * normal.x() + vector.y() * normal.y() + vector.z() * normal.z();
		final Vector result = new Vector(vector.x() - normal.x() * dot, vector.y() - normal.y() * dot, vector.z() - normal.z() * dot);
		return length(result) < 1.0E-4 ? new Vector(0, 1, 0) : result.normalize();
	}

	private static Vector cross(Vector a, Vector b) {
		return new Vector(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
	}

	private static Vector scale(Vector vector, int sign) {
		return sign < 0 ? new Vector(-vector.x(), -vector.y(), -vector.z()) : vector;
	}

	private static double length(Vector vector) {
		return Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
	}

	private static double clamp(double value) {
		return Math.max(-1, Math.min(1, value));
	}

	private static String format(Vector vector) {
		return String.format("(%.4f, %.4f, %.4f)", vector.x(), vector.y(), vector.z());
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}
}
