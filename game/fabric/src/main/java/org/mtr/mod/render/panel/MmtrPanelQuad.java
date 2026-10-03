package org.mtr.mod.render.panel;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.client.MmtrVehicleAnchors.Facet;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * Draws a finished panel texture as a quad on a modelled anchor face.
 *
 * <p>The anchor carries an orthonormal frame in the OBJ file space ({@code right}/{@code up}/
 * {@code normal}), which is the same space MTR's model geometry is parsed into after the loader's
 * {@code (x, -y, -z)} flip. The quad's four corners are placed STRAIGHT FROM THAT BASIS -
 * {@code centre + right*a + up*b + normal*lift} - so the panel is in the modelled face's plane by
 * construction, with its "right" and "up" exactly the modeller's face edges and no hard-coded fudge.</p>
 *
 * <p>An earlier version instead solved yaw/pitch/roll for {@link GraphicsHolder}'s Euler rotations and
 * let those place the quad. That decomposition is only correct for an upright panel whose "up" is
 * vertical (it takes the vertical part of {@code up} as the whole pitch), so a surface whose authored
 * up runs partly ALONG the surface came out rotated about the car's length axis: the windshield was a
 * full 90 degrees off that way (notes/179 §10) and the BR101 dashboard measured 68 degrees off
 * ({@code sandbox/panel_euler_error.js}). Panel placement must not go through Euler angles.</p>
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

	/**
	 * **这一块面板画在哪一侧**（notes/357 加的这一维）。
	 *
	 * <p>原来只有一条规则（{@link #DRIVER}）：面板的法线朝司机（仪表盘就是这样），于是"哪一侧"
	 * 由"车心在法线的哪一边"推出来。水牌（{@code mmtr_pid_*}）**法线朝车外**，照这条规则推出来的
	 * 是"朝车里"那一侧 —— 站台上看到的是牌背，而背面剔除一开，那块牌**整个看不见**
	 * （打包成功、进游戏什么都没有）。所以"哪一侧"必须是调用方说得清的一件事，不能从法线方向猜。</p>
	 */
	public enum Side {
		/** 按"司机在哪一侧"判（仪表面板：法线朝司机）。 */
		DRIVER,
		/** 锚点法线那一侧（水牌 / 下一站牌：法线朝着**读它的人**）。 */
		NORMAL,
		/** 两侧都画（诊断，见 {@code panelTwoSided}）。 */
		BOTH
	}

	public static void draw(Identifier texture, Anchor anchor, StoredMatrixTransformations carTransform, double widthM, double heightM) {
		drawFrame(texture, anchor.name, null, anchor.filePosition, anchor.fileNormal, anchor.fileUp, anchor.fileRight, widthM, heightM, 0, 0, 1, 1, anchor.panelFlipU, anchor.panelTwoSided ? Side.BOTH : Side.DRIVER, carTransform);
	}

	/**
	 * 画在**锚点法线那一侧**：读它的人就站在法线指的那一边（notes/357 的水牌 / 下一站牌）。
	 *
	 * <p>与 {@link #draw} 的差别只有"选哪一侧"这一件事，其余（抬升、绕序、UV、uv 矩形）逐字相同。</p>
	 */
	public static void drawAtNormalSide(Identifier texture, Anchor anchor, StoredMatrixTransformations carTransform, double widthM, double heightM) {
		drawFrame(texture, anchor.name, null, anchor.filePosition, anchor.fileNormal, anchor.fileUp, anchor.fileRight, widthM, heightM, 0, 0, 1, 1, anchor.panelFlipU, Side.NORMAL, carTransform);
	}

	/**
	 * 画在**指定**的一侧（notes/359：面文档的 {@code side} 直接说这件事）。
	 *
	 * <p>{@link Side#DRIVER} = 司机那一侧（仪表面板的口径）、{@link Side#NORMAL} = 锚点法线那一侧
	 * （水牌 / 朝着读它的人）、{@link Side#BOTH} = 两侧都画（诊断）。与另外两个入口共用同一条
	 * {@link #drawFrame}，所以抬升、绕序、UV、uv 矩形逐字相同。</p>
	 */
	public static void drawSide(Identifier texture, Anchor anchor, StoredMatrixTransformations carTransform, double widthM, double heightM, Side side) {
		drawFrame(texture, anchor.name, null, anchor.filePosition, anchor.fileNormal, anchor.fileUp, anchor.fileRight, widthM, heightM, 0, 0, 1, 1, anchor.panelFlipU, side, carTransform);
	}

	/**
	 * 按**姿态**画（notes/359 · F3）：整面滚转 {@code roll}、仰角 {@code tilt}、以及翻牌机的
	 * 翻转角 {@code angle} + 离轴心的距离 {@code radiusM}。
	 *
	 * <p>顺序是"先滚转、再绕滚转后的水平轴俯仰"，因为滚转是安装方式、俯仰是动态的那一维
	 * （翻牌机就是一直绕水平轴转）。两步都只动基向量，四边形的四个角仍然由
	 * {@code 中心 + right·a + up·b + normal·lift} 算出来 —— 还是那一条"没有欧拉角"的老规矩。</p>
	 *
	 * <ul>
	 *   <li>{@code roll} **顺时针为正**（读者视角），与元素自己的 {@code rotate} 同向；</li>
	 *   <li>{@code tilt} 正 = 法线向上抬（牌子向后仰，站着的人从斜上方读）；</li>
	 *   <li>{@code angle} + {@code radiusM} = 翻牌机：绕水平轴转 {@code angle} 度，并把这一面推到
	 *       半径 {@code radiusM} 处（= 棱柱的第 i 个侧面）。{@code radiusM = 0} 时退化成纯旋转
	 *       （{@code tilt} 就是这么用的）。</li>
	 * </ul>
	 *
	 * <p>背朝观众的那些面不用自己剔：渲染层开的是背面剔除，转过去的那几面自然不可见 ——
	 * 这也是"一个棱柱画 N 个四边形"能成立的原因。</p>
	 */
	public static void drawOriented(Identifier texture, Anchor anchor, StoredMatrixTransformations carTransform, double widthM, double heightM, Side side, double roll, double tilt, double angle, double radiusM) {
		if (texture == null || widthM <= 0 || heightM <= 0) {
			return;
		}
		final Vector baseNormal = toModelSpace(anchor.fileNormal).normalize();
		final Vector baseUp = orthonormalise(toModelSpace(anchor.fileUp).normalize(), baseNormal);
		final Vector baseRight = cross(baseUp, baseNormal).normalize();

		// ① 整面滚转（绕法线）：顺时针为正 = 基向量逆着转
		final double rollRadians = Math.toRadians(roll);
		final Vector right = sub(scale(baseRight, Math.cos(rollRadians)), scale(baseUp, Math.sin(rollRadians)));
		final Vector rolledUp = add(scale(baseUp, Math.cos(rollRadians)), scale(baseRight, Math.sin(rollRadians)));

		// ② 绕水平轴俯仰/翻转（翻牌机一直在这条路上）
		final double pitch = Math.toRadians(tilt + angle);
		final Vector normal = add(scale(baseNormal, Math.cos(pitch)), scale(rolledUp, Math.sin(pitch)));
		final Vector up = sub(scale(rolledUp, Math.cos(pitch)), scale(baseNormal, Math.sin(pitch)));

		// ③ 棱柱的侧面：中心沿**自己这一面的法线**推到半径处
		final Vector position = add(toModelSpace(anchor.filePosition), scale(normal, radiusM));
		drawQuad(texture, anchor.name, null, position, normal, up, cross(up, normal).normalize(), widthM, heightM, 0, 0, 1, 1, anchor.panelFlipU, side, carTransform);
	}

	/**
	 * Draws ONE facet of a folded dashboard: its own frame and size, sampling only its rectangle of the
	 * shared unfolded canvas.
	 *
	 * <p>{@code u0,v0,u1,v1} is that rectangle, and {@code v0} is the TOP edge - the same edge the
	 * single-quad path puts texture row 0 on - so the image stays the right way up across every facet
	 * and meets exactly at the creases.</p>
	 *
	 * <p>The driver's-side test runs PER FACET here: a bent dashboard's facets point in different
	 * directions, and testing the group's average normal against the group's average position would
	 * pick one side for the whole surface.</p>
	 */
	public static void drawFacet(Identifier texture, Anchor anchor, Facet facet, StoredMatrixTransformations carTransform) {
		drawFrame(texture, anchor.name, facet.corners, facet.position, facet.normal, facet.up, facet.right, facet.widthM, facet.heightM, facet.u0, facet.v0, facet.u1, facet.v1, anchor.panelFlipU, anchor.panelTwoSided ? Side.BOTH : Side.DRIVER, carTransform);
	}

	private static void drawFrame(Identifier texture, String name, double[][] facetCorners, Vector filePosition, Vector fileNormal, Vector fileUp, Vector fileRight, double widthM, double heightM, double u0, double v0, double u1, double v1, boolean flipU, Side sideRule, StoredMatrixTransformations carTransform) {
		if (texture == null || widthM <= 0 || heightM <= 0) {
			return;
		}

		final Vector position = toModelSpace(filePosition);
		final Vector normal = toModelSpace(fileNormal).normalize();
		final Vector up = orthonormalise(toModelSpace(fileUp).normalize(), normal);
		final Vector right = cross(up, normal).normalize();
		final int chosenSide = switch (sideRule) {
			case DRIVER -> facingSide(position, normal);
			case NORMAL -> 1;
			case BOTH -> 0;
		};

		if (DEBUG_LOGGED.add(texture + "#" + name + "#" + u0 + "," + v0)) {
			Init.LOGGER.info("[MMTR-DBG] panel {} anchor={} uv=({},{})-({},{}) modelPos={} modelNormal={} modelUp={} modelRight={} chosenSide={} (model space = OBJ file space (x, -y, -z))",
					texture, name, u0, v0, u1, v1, format(position), format(normal), format(up), format(right), chosenSide);
			for (final int side : new int[]{1, -1}) {
				final Vector sideNormal = scale(normal, side);
				Init.LOGGER.info("[MMTR-DBG]   side {} yaw={} pitch={} roll={} flipU={} offset={}",
						side,
						round(Math.toDegrees(Math.atan2(sideNormal.x(), sideNormal.z()))),
						round(Math.toDegrees(Math.asin(clamp(-up.y())))),
						round(Math.toDegrees(Math.atan2(up.x(), up.y()))),
						flipU,
						SURFACE_OFFSET_M);
			}
		}

		drawQuad(texture, name, facetCorners, position, normal, up, right, widthM, heightM, u0, v0, u1, v1, flipU, sideRule, carTransform);
	}

	/**
	 * 画**一个四边形**：面板空间的一套正交基已经算好。
	 *
	 * <p>{@link #drawFrame} 从锚点算基（老路），{@link #drawOriented} 从姿态算（notes/359 · F3 的
	 * {@code roll}/{@code tilt}/{@code drum}）—— 两条路在这里汇合，于是"抬升多少、往哪一侧、
	 * UV 怎么摆"只有一份实现，姿态那一路不可能悄悄少了抬升而和车体贴面 z-fighting。</p>
	 */
	private static void drawQuad(Identifier texture, String name, double[][] facetCorners, Vector position, Vector normal, Vector up, Vector right, double widthM, double heightM, double u0, double v0, double u1, double v1, boolean flipU, Side sideRule, StoredMatrixTransformations carTransform) {
		final double halfWidthM = widthM / 2;
		final double halfHeightM = heightM / 2;
		final int chosenSide = switch (sideRule) {
			case DRIVER -> facingSide(position, normal);
			case NORMAL -> 1;
			case BOTH -> 0;
		};

		for (final int side : chosenSide == 0 ? new int[]{1, -1} : new int[]{chosenSide}) {
			// The back copy is the same frame rotated 180 degrees about its own up axis: right and
			// normal are negated, up stays. The frame stays right-handed, so it is still a rotation.
			final Vector sideNormal = scale(normal, side);
			final Vector sideRight = scale(right, side);

			// The four corners are placed STRAIGHT FROM THE ANCHOR'S OWN BASIS - no Euler angles.
			//
			// With `corners` present the facet states its own quad, which matters for a SHEARED facet (a
			// parallelogram - the desk's flowing shape, which is what BR101's dashboard wings are): the
			// old widthM x heightM rectangle covered it with its BOUNDING box and overhung the desk by the
			// shear. Each corner is classified into its uv slot by which half of `right`/`up` it falls in,
			// so the emitted order does not matter and the artwork's mapping is unchanged.
			//
			// Without `corners` (older packs) the rectangle is reconstructed as before.
			// centre + sideRight*a + up*b + sideNormal*lift is in the face's plane BY CONSTRUCTION, for
			// any winding, any tilt and any up. The anchor already hands over a complete orthonormal
			// basis, so there is nothing left to solve.
			final Vector[] corners = cornersOf(facetCorners, position, sideRight, up, sideNormal, halfWidthM, halfHeightM);

			// A facet's uv rectangle mirrors about the canvas centre when panelFlipU is set: u -> 1 - u.
			final float uLeft = (float) (flipU ? 1 - u0 : u0);
			final float uRight = (float) (flipU ? 1 - u1 : u1);

			MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.LIGHT_2, (graphicsHolder, offset) -> {
				carTransform.transform(graphicsHolder, offset);
				// Corners go bottom-left, bottom-right, top-right, top-left. That order is the OPPOSITE
				// of what IDrawing.drawTexture's rectangle overload produces, and it is deliberate: the
				// rectangle overload winds the quad so its front face points at local -Z, while this
				// panel lifts the quad along the drawn face's own normal. With the default winding the
				// visible copy would be the one pushed INTO the dashboard (Minecraft culls back faces and
				// RenderLayer.getText keeps culling enabled), which is exactly how the panel ends up
				// hidden behind its own dashboard.
				//
				// MTR's four-corner overload assigns the UV pairs as (u1,v2) (u2,v2) (u2,v1) (u1,v1) -
				// v1 is the TOP edge and v2 the bottom, the opposite of the rectangle overload's naming.
				// So v0 (the facet's top edge) belongs to the top corners: pass v0 for v1, v1 for v2.
				IDrawing.drawTexture(
						graphicsHolder,
						(float) corners[0].x(), (float) corners[0].y(), (float) corners[0].z(),
						(float) corners[1].x(), (float) corners[1].y(), (float) corners[1].z(),
						(float) corners[2].x(), (float) corners[2].y(), (float) corners[2].z(),
						(float) corners[3].x(), (float) corners[3].y(), (float) corners[3].z(),
						uLeft, (float) v0, uRight, (float) v1,
						Direction.UP, IGui.ARGB_WHITE, GraphicsHolder.getDefaultLight()
				);
				// carTransform.transform() pushes; every caller pops (see MmtrWindshield.drawRain).
				graphicsHolder.pop();
			});
		}
	}

	/** {@code centre + right*a + up*b + normal*lift} - one quad corner, in car-local space. */
	private static Vector corner(Vector centre, Vector right, double a, Vector up, double b, Vector normal, double lift) {
		return new Vector(
				centre.x() + right.x() * a + up.x() * b + normal.x() * lift,
				centre.y() + right.y() * a + up.y() * b + normal.y() * lift,
				centre.z() + right.z() * a + up.z() * b + normal.z() * lift
		);
	}

	/**
	 * The quad's four corners, bottom-left / bottom-right / top-right / top-left.
	 *
	 * <p>When the facet carries its own {@code corners} (each a {@code (right, up)} offset from the
	 * facet's centre) those are used verbatim, which is what lets a SHEARED facet - a parallelogram - be
	 * covered exactly. Classifying by which half of {@code right}/{@code up} a corner falls in also means
	 * the order the packager emitted them in does not matter.</p>
	 *
	 * <p>Otherwise the rectangle {@code widthM x heightM} is reconstructed, so packs written before the
	 * corners existed keep drawing exactly as they did.</p>
	 */
	private static Vector[] cornersOf(double[][] facetCorners, Vector position, Vector sideRight, Vector up, Vector sideNormal, double halfWidthM, double halfHeightM) {
		if (facetCorners == null || facetCorners.length != 4) {
			return new Vector[]{
					corner(position, sideRight, -halfWidthM, up, -halfHeightM, sideNormal, SURFACE_OFFSET_M),
					corner(position, sideRight, halfWidthM, up, -halfHeightM, sideNormal, SURFACE_OFFSET_M),
					corner(position, sideRight, halfWidthM, up, halfHeightM, sideNormal, SURFACE_OFFSET_M),
					corner(position, sideRight, -halfWidthM, up, halfHeightM, sideNormal, SURFACE_OFFSET_M)
			};
		}
		final Vector[] result = new Vector[4];
		for (final double[] c : facetCorners) {
			final int slot = (c[1] > 0 ? 2 : 0) + (c[0] > 0 ? 1 : 0);   // 0=BL 1=BR 2=TL 3=TR
			final int index = slot == 2 ? 3 : slot == 3 ? 2 : slot;      // order is BL, BR, TR, TL
			result[index] = corner(position, sideRight, c[0], up, c[1], sideNormal, SURFACE_OFFSET_M);
		}
		for (final Vector v : result) {
			if (v == null) {
				return cornersOf(null, position, sideRight, up, sideNormal, halfWidthM, halfHeightM);
			}
		}
		return result;
	}

	/**
	 * @return {@code 1} or {@code -1} for the side of the face the driver sits on, {@code 0} when it
	 * cannot be decided. In model space the car is centred on the origin, so the car's interior is the
	 * direction from the face towards the origin; the driver's side is the one whose normal points
	 * that way. This keeps a single panel instead of two back-to-back copies.
	 *
	 * <p>Decided per FRAME (anchor or facet): a bent dashboard's facets point different ways, so the
	 * group's average normal would pick one side for the whole surface.</p>
	 */
	private static int facingSide(Vector position, Vector normal) {
		final double horizontal = normal.x() * -position.x() + normal.z() * -position.z();
		if (Math.abs(horizontal) < 1.0E-4) {
			return 0;
		}
		return horizontal > 0 ? 1 : -1;
	}

	/**
	 * Converts an anchor coordinate from the OBJ file space into the space MTR renders the model in.
	 * Verified against MTR's own loader: a part at file {@code z = +1.09} is parsed at {@code z = -1.09}.
	 */
	public static Vector toModelSpace(Vector fileVector) {
		return new Vector(fileVector.x(), -fileVector.y(), -fileVector.z());
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

	private static Vector scale(Vector vector, double factor) {
		return new Vector(vector.x() * factor, vector.y() * factor, vector.z() * factor);
	}

	private static Vector add(Vector a, Vector b) {
		return new Vector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
	}

	private static Vector sub(Vector a, Vector b) {
		return new Vector(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
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
