package org.mtr.mod.render.panel;

import org.mtr.mapping.holder.NativeImage;
import org.mtr.mapping.holder.NativeImageFormat;
import org.mtr.mod.data.IGui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.AlphaComposite;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/**
 * A 2D drawing surface for the in-world cab panels, measured in metres.
 *
 * <p>Coordinates are the panel's own plane: the origin is the bottom left corner of the modelled
 * face, +X runs along the face's "right" and +Y runs along its "up", so a widget is laid out relative
 * to the dashboard instead of in pixels. The surface is rasterised with Java2D, which means every
 * primitive is anti-aliased and arbitrary curves, rotations and text are available; the result is
 * uploaded as ONE texture and drawn as ONE quad, so no draw-order or z-fighting rules are involved.</p>
 *
 * <p>Panel-local sizes are metres; {@code heightM} on {@link #text} is the height of the text box,
 * not the font size, so a 0.18 m readout stays 0.18 m tall whatever the pixel density is.</p>
 */
public final class MmtrPanelCanvas {

	private BufferedImage image;
	private Graphics2D graphics;
	private final double widthM;
	private final double heightM;
	private double scaleX;
	private double scaleY;
	/** {@link #pushClip} 的栈（每个元素画完要还回去 —— 走马灯的视口就是靠它裁的）。 */
	private final List<Shape> clipStack = new ArrayList<>();
	/** {@link #pushRotate} 的栈。 */
	private final List<AffineTransform> transformStack = new ArrayList<>();

	/** Panels are small; anything bigger than this is a modelling mistake, so clamp instead of exploding. */
	private static final int MAX_PIXELS = 512;
	private static final int MIN_PIXELS = 8;
	/**
	 * 屏幕 HUD 用的上限（notes/227）：HUD 要按**窗口缩放率**栅格化（高 GUI 缩放下远超 512），
	 * 所以走 {@link #createExactPixels} 这条入口 —— 面板那条 512 的防呆上限保持不动。
	 */
	private static final int MAX_HUD_PIXELS = 4096;

	private MmtrPanelCanvas(double widthM, double heightM, int pxPerMetre) {
		this.widthM = Math.max(widthM, 1.0E-3);
		this.heightM = Math.max(heightM, 1.0E-3);

		final double maxDimensionM = Math.max(this.widthM, this.heightM);
		final int requestedPixels = (int) Math.round(maxDimensionM * Math.max(pxPerMetre, 1));
		final int clampedPixels = Math.min(MAX_PIXELS, Math.max(MIN_PIXELS, requestedPixels));
		final double pixelScale = clampedPixels / maxDimensionM;

		final int widthPx = Math.max(1, (int) Math.round(this.widthM * pixelScale));
		final int heightPx = Math.max(1, (int) Math.round(this.heightM * pixelScale));
		scaleX = widthPx / this.widthM;
		scaleY = heightPx / this.heightM;

		image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
		graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
	}

	public static MmtrPanelCanvas create(double widthM, double heightM, int pxPerMetre) {
		return new MmtrPanelCanvas(widthM, heightM, pxPerMetre);
	}

	/**
	 * A canvas whose backing image is exactly the requested pixel size, measured in metres. Used by the
	 * MMTR windshield: the precipitation layer is a full-surface image that the client regenerates every
	 * few frames, and it wants a predictable pixel count at the glass's own aspect ratio rather than the
	 * square-ish density {@link #create} derives from the longer side.
	 */
	public static MmtrPanelCanvas createPixels(double widthM, double heightM, int widthPx) {
		final double safeWidthM = Math.max(widthM, 1.0E-3);
		final double safeHeightM = Math.max(heightM, 1.0E-3);
		final double aspect = safeWidthM / safeHeightM;
		final int clampedWidthPx = Math.min(MAX_PIXELS, Math.max(MIN_PIXELS, widthPx));
		final int heightPx = Math.max(1, (int) Math.round(clampedWidthPx / aspect));
		final MmtrPanelCanvas canvas = new MmtrPanelCanvas(safeWidthM, safeHeightM);
		canvas.scaleX = clampedWidthPx / safeWidthM;
		canvas.scaleY = heightPx / safeHeightM;
		canvas.replaceImage(clampedWidthPx, heightPx);
		return canvas;
	}

	private MmtrPanelCanvas(double widthM, double heightM) {
		this.widthM = widthM;
		this.heightM = heightM;
		this.scaleX = 1;
		this.scaleY = 1;
		image = null;
		graphics = null;
	}

	/**
	 * 画布按**给定像素数**建成（长边不再被 512 夹住，只留 {@link #MAX_HUD_PIXELS} 的防爆上限）。
	 *
	 * <p>给屏幕 HUD 用（notes/227）：HUD 要按窗口缩放率栅格化才够锐 —— 例如 208×104 GUI 单位、
	 * 缩放率 3 时要 624×312 像素，而面板那条 {@link #createPixels} 会把它夹到 512 长边。</p>
	 */
	public static MmtrPanelCanvas createExactPixels(double widthM, double heightM, int widthPx, int heightPx) {
		final double safeWidthM = Math.max(widthM, 1.0E-3);
		final double safeHeightM = Math.max(heightM, 1.0E-3);
		final int clampedWidthPx = Math.max(1, Math.min(MAX_HUD_PIXELS, widthPx));
		final int clampedHeightPx = Math.max(1, Math.min(MAX_HUD_PIXELS, heightPx));
		final MmtrPanelCanvas canvas = new MmtrPanelCanvas(safeWidthM, safeHeightM);
		canvas.scaleX = clampedWidthPx / safeWidthM;
		canvas.scaleY = clampedHeightPx / safeHeightM;
		canvas.replaceImage(clampedWidthPx, clampedHeightPx);
		return canvas;
	}

	private void replaceImage(int widthPx, int heightPx) {
		if (graphics != null) {
			graphics.dispose();
		}
		image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
		graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	public double widthM() {
		return widthM;
	}

	public double heightM() {
		return heightM;
	}

	// ---- primitives ------------------------------------------------------------------------------

	/** A filled axis aligned rectangle, {@code (x, y)} being its bottom left corner. */
	public MmtrPanelCanvas fill(double x, double y, double width, double height, int color) {
		if (width <= 0 || height <= 0) {
			return this;
		}
		graphics.setColor(colorOf(color));
		graphics.fill(new Rectangle2D.Double(px(x), py(y + height), px(x + width) - px(x), py(y) - py(y + height)));
		return this;
	}

	/** A filled rectangle with rounded corners, {@code radiusM} clamped to half the shorter side. */
	public MmtrPanelCanvas fillRoundRect(double x, double y, double width, double height, double radiusM, int color) {
		if (width <= 0 || height <= 0) {
			return this;
		}
		final double radius = Math.max(0, Math.min(radiusM, Math.min(width, height) / 2));
		final double left = px(x);
		final double top = py(y + height);
		final double right = px(x + width);
		final double bottom = py(y);
		final double r = radius * Math.min(scaleX, scaleY);
		graphics.setColor(colorOf(color));
		graphics.fill(new java.awt.geom.RoundRectangle2D.Double(left, top, right - left, bottom - top, r * 2, r * 2));
		return this;
	}

	/** A straight line with round caps. */
	public MmtrPanelCanvas line(double x1, double y1, double x2, double y2, double widthM, int color) {
		return polyline(new double[]{x1, x2}, new double[]{y1, y2}, widthM, color);
	}

	/** A polyline; the two arrays are x/y pairs in metres and must have the same length. */
	public MmtrPanelCanvas polyline(double[] xs, double[] ys, double widthM, int color) {
		final int count = Math.min(xs.length, ys.length);
		if (count < 2) {
			return this;
		}
		final Path2D.Double path = new Path2D.Double();
		for (int i = 0; i < count; i++) {
			final double x = px(xs[i]);
			final double y = py(ys[i]);
			if (i == 0) {
				path.moveTo(x, y);
			} else {
				path.lineTo(x, y);
			}
		}
		graphics.setColor(colorOf(color));
		graphics.setStroke(new BasicStroke((float) Math.max(pxWidth(widthM), 1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.draw(path);
		return this;
	}

	/**
	 * An arc centred on {@code (cx, cy)}, drawn between {@code startDegrees} and {@code endDegrees}
	 * (degrees, counter clockwise from +X in the panel's own y-up space).
	 */
	public MmtrPanelCanvas arc(double cx, double cy, double radius, double widthM, double startDegrees, double endDegrees, int color) {
		final int steps = arcSteps(startDegrees, endDegrees);
		final double[] xs = new double[steps + 1];
		final double[] ys = new double[steps + 1];
		for (int i = 0; i <= steps; i++) {
			final double angle = Math.toRadians(startDegrees + (endDegrees - startDegrees) * i / steps);
			xs[i] = cx + radius * Math.cos(angle);
			ys[i] = cy + radius * Math.sin(angle);
		}
		return polyline(xs, ys, widthM, color);
	}

	/** A filled circle, useful for gauge pivots and status lamps. */
	public MmtrPanelCanvas circle(double cx, double cy, double radius, int color) {
		graphics.setColor(colorOf(color));
		final double r = radius * Math.min(scaleX, scaleY);
		graphics.fill(new java.awt.geom.Ellipse2D.Double(px(cx) - r, py(cy) - r, r * 2, r * 2));
		return this;
	}

	/**
	 * A filled pie slice centred on {@code (cx, cy)}, from {@code startDegrees} to {@code endDegrees}
	 * (degrees, counter clockwise from +X in the panel's own y-up space).
	 *
	 * <p>Used by the windshield for the film the wiper blade drags: the swept sector IS the trail, so
	 * there is nothing to age or expire - it is rebuilt from the arm's own two angles every frame.</p>
	 */
	public MmtrPanelCanvas fillSector(double cx, double cy, double radius, double startDegrees, double endDegrees, int color) {
		if (radius <= 0 || Math.abs(endDegrees - startDegrees) < 1.0E-6) {
			return this;
		}
		final int steps = arcSteps(startDegrees, endDegrees);
		final double[] xs = new double[steps + 2];
		final double[] ys = new double[steps + 2];
		xs[0] = cx;
		ys[0] = cy;
		for (int i = 0; i <= steps; i++) {
			final double angle = Math.toRadians(startDegrees + (endDegrees - startDegrees) * i / steps);
			xs[i + 1] = cx + radius * Math.cos(angle);
			ys[i + 1] = cy + radius * Math.sin(angle);
		}
		// fillPolygon would close the path straight from the last arc point back to the centre, which is
		// exactly the two radii - so the fan is a filled sector, not a chord cut off the arc.
		return fillPolygon(xs, ys, color);
	}

	/**
	 * A gauge needle: a rectangle of {@code widthM} pointing away from the pivot at
	 * {@code angleDegrees} (counter clockwise from +X).
	 */
	public MmtrPanelCanvas needle(double cx, double cy, double lengthM, double widthM, double angleDegrees, int color) {
		final double angle = Math.toRadians(angleDegrees);
		final double dirX = Math.cos(angle);
		final double dirY = Math.sin(angle);
		final double halfWidth = widthM / 2;
		final double perpX = -dirY * halfWidth;
		final double perpY = dirX * halfWidth;
		return fillPolygon(
				new double[]{cx + perpX, cx - perpX, cx - perpX + dirX * lengthM, cx + perpX + dirX * lengthM},
				new double[]{cy + perpY, cy - perpY, cy - perpY + dirY * lengthM, cy + perpY + dirY * lengthM},
				color
		);
	}

	/**
	 * A filled polygon; the two arrays are x/y pairs in metres and must have the same length.
	 *
	 * <p>Implemented with {@code Path2D} + a non-zero winding fill, so a simple NON-convex outline
	 * (such as the speed HUD's wedge-plus-plate) fills correctly too - it does not have to be convex.</p>
	 */
	public MmtrPanelCanvas fillPolygon(double[] xs, double[] ys, int color) {
		final int count = Math.min(xs.length, ys.length);
		if (count < 3) {
			return this;
		}
		final Path2D.Double path = new Path2D.Double();
		for (int i = 0; i < count; i++) {
			final double x = px(xs[i]);
			final double y = py(ys[i]);
			if (i == 0) {
				path.moveTo(x, y);
			} else {
				path.lineTo(x, y);
			}
		}
		path.closePath();
		graphics.setColor(colorOf(color));
		graphics.fill(path);
		return this;
	}

	/**
	 * Draws text with its box anchored at {@code (x, y)}. {@code heightM} is the height of the text's
	 * INK box in metres (the visible glyphs, not the font's line height), so a 0.2 m readout really
	 * measures 0.2 m on the dashboard whatever font is used.
	 */
	public MmtrPanelCanvas text(String text, double x, double y, double heightM, int color, IGui.HorizontalAlignment horizontalAlignment, IGui.VerticalAlignment verticalAlignment) {
		if (text == null || text.isEmpty() || heightM <= 0) {
			return this;
		}

		final double targetHeightPx = heightM * scaleY;
		final Font baseFont = MmtrPanelFont.get(text);
		final FontRenderContext fontRenderContext = graphics.getFontRenderContext();

		// Measure at a reference size, then derive the size whose ink box is targetHeightPx tall.
		final GlyphVector probeGlyphs = baseFont.deriveFont(Font.PLAIN, 100F).createGlyphVector(fontRenderContext, text);
		final Rectangle2D probeInk = probeGlyphs.getVisualBounds();
		if (probeInk.getHeight() <= 0) {
			return this;
		}

		final Font font = baseFont.deriveFont(Font.PLAIN, (float) (100 * targetHeightPx / probeInk.getHeight()));
		final GlyphVector glyphs = font.createGlyphVector(fontRenderContext, text);
		final Rectangle2D ink = glyphs.getVisualBounds();
		final double boxLeft = px(x) + horizontalAlignment.getOffset(0, (float) ink.getWidth());
		final double boxTop = py(y) + verticalAlignment.getOffset(0, (float) ink.getHeight());

		graphics.setColor(colorOf(color));
		graphics.drawGlyphVector(glyphs, (float) (boxLeft - ink.getX()), (float) (boxTop - ink.getY()));
		return this;
	}

	/**
	 * {@link #text} 的**斜体**版：把字形按 {@code shear} 水平错切（Java2D 仿射变换），
	 * 正数 = **向右倾**（屏幕坐标 y 向下，所以内部取 {@code -shear}）。
	 *
	 * <p>错切中心取**墨迹框的水平中点**：这样居中对齐的读数（如速度的每一位）倾斜后视觉中心不动 ——
	 * 与"逐位中点固定"那条要求同一目的（notes/223/227）。</p>
	 *
	 * <p>为什么不用 MC 的 {@code Style.withItalic}：那是给 MC 字体系统的位图字形加偏移，而 HUD 现在整幅
	 * 由 Java2D 栅格化（要抗锯齿与大字号下的锐度），字形来源不同，斜体也就得在这一层做。</p>
	 */
	public MmtrPanelCanvas textItalic(String text, double x, double y, double heightM, double shear, int color, IGui.HorizontalAlignment horizontalAlignment, IGui.VerticalAlignment verticalAlignment) {
		if (text == null || text.isEmpty() || heightM <= 0) {
			return this;
		}

		final double targetHeightPx = heightM * scaleY;
		final Font baseFont = MmtrPanelFont.get(text);
		final FontRenderContext fontRenderContext = graphics.getFontRenderContext();
		final GlyphVector probeGlyphs = baseFont.deriveFont(Font.PLAIN, 100F).createGlyphVector(fontRenderContext, text);
		final Rectangle2D probeInk = probeGlyphs.getVisualBounds();
		if (probeInk.getHeight() <= 0) {
			return this;
		}

		final Font font = baseFont.deriveFont(Font.PLAIN, (float) (100 * targetHeightPx / probeInk.getHeight()));
		final GlyphVector glyphs = font.createGlyphVector(fontRenderContext, text);
		final Rectangle2D ink = glyphs.getVisualBounds();
		final double boxLeft = px(x) + horizontalAlignment.getOffset(0, (float) ink.getWidth());
		final double boxTop = py(y) + verticalAlignment.getOffset(0, (float) ink.getHeight());
		final double anchorX = boxLeft - ink.getX();
		final double anchorY = boxTop - ink.getY();

		graphics.setColor(colorOf(color));
		if (shear == 0) {
			graphics.drawGlyphVector(glyphs, (float) anchorX, (float) anchorY);
			return this;
		}
		final java.awt.geom.AffineTransform saved = graphics.getTransform();
		final java.awt.geom.AffineTransform sheared = new java.awt.geom.AffineTransform(saved);
		final double centreX = anchorX + ink.getWidth() / 2;
		sheared.translate(centreX, anchorY);
		sheared.shear(-shear, 0);
		sheared.translate(-centreX, -anchorY);
		graphics.setTransform(sheared);
		graphics.drawGlyphVector(glyphs, (float) anchorX, (float) anchorY);
		graphics.setTransform(saved);
		return this;
	}

	/**
	 * 量一段文字在给定**墨迹高度**下的墨迹宽度（米）—— 与 {@link #text} 用同一套字号推导，
	 * 所以"先量宽度、再排版"不会与画出来的不一致（notes/227：逐位格宽就是靠它量的）。
	 */
	public double textWidth(String text, double heightM) {
		if (text == null || text.isEmpty() || heightM <= 0) {
			return 0;
		}
		final double targetHeightPx = heightM * scaleY;
		final Font baseFont = MmtrPanelFont.get(text);
		final FontRenderContext fontRenderContext = graphics.getFontRenderContext();
		final GlyphVector probeGlyphs = baseFont.deriveFont(Font.PLAIN, 100F).createGlyphVector(fontRenderContext, text);
		final Rectangle2D probeInk = probeGlyphs.getVisualBounds();
		if (probeInk.getHeight() <= 0) {
			return 0;
		}
		final Font font = baseFont.deriveFont(Font.PLAIN, (float) (100 * targetHeightPx / probeInk.getHeight()));
		return font.createGlyphVector(fontRenderContext, text).getVisualBounds().getWidth() / scaleX;
	}

	// ---- 作用域：裁剪与旋转（notes/359 · F3） ---------------------------------------------------------

	/**
	 * 把之后的绘制**裁**在 {@code (x, y, w, h)} 里（{@code (x,y)} 是左下角，与 {@link #fill} 同一条口径）。
	 *
	 * <p>要成对调用 {@link #popClip()}。走马灯必须裁：文本比视口长，不裁就会跑到牌子外面去，
	 * 而"跑出去"在贴图上表现为压在相邻元素上（不是看不见）。</p>
	 */
	public MmtrPanelCanvas pushClip(double x, double y, double width, double height) {
		clipStack.add(graphics.getClip());
		final double left = px(x);
		final double right = px(x + width);
		final double top = py(y + height);
		final double bottom = py(y);
		graphics.clip(new Rectangle2D.Double(Math.min(left, right), Math.min(top, bottom), Math.abs(right - left), Math.abs(bottom - top)));
		return this;
	}

	/** 还原到上一次 {@link #pushClip} 之前的裁剪区。 */
	public MmtrPanelCanvas popClip() {
		graphics.setClip(clipStack.isEmpty() ? null : clipStack.remove(clipStack.size() - 1));
		return this;
	}

	/**
	 * 把之后的绘制绕**米制的点** {@code (cx, cy)} 转 {@code degrees} 度（**顺时针为正**，
	 * 也就是读者看着牌面时的顺时针），要成对调用 {@link #popTransform()}。
	 *
	 * <p>为什么顺时针为正：AWT 的正角是从 +X 转向 +Y，而屏幕上的 +Y 朝下 ⇒ 视觉上就是顺时针。
	 * 我们的米制空间 y 朝上、但 {@link #py} 已经把它翻过去了，所以直接把角度交给 AWT 即可 ——
	 * 这条在注释里写清楚，是因为"作者的 rotate: 15 到底往哪边转"只能有一个答案。</p>
	 */
	public MmtrPanelCanvas pushRotate(double cx, double cy, double degrees) {
		transformStack.add(graphics.getTransform());
		graphics.rotate(Math.toRadians(degrees), px(cx), py(cy));
		return this;
	}

	/** 还原到上一次 {@link #pushRotate} 之前的变换。 */
	public MmtrPanelCanvas popTransform() {
		if (!transformStack.isEmpty()) {
			graphics.setTransform(transformStack.remove(transformStack.size() - 1));
		}
		return this;
	}

	/**
	 * 画一张图（{@code (x, y)} 是左下角，{@code w × h} 是它占的矩形，与 {@link #fill} 同一条口径）。
	 *
	 * <p>色调（tint）不在这里做：它是对**像素**的乘法，做一次就该缓存起来（见 {@code MmtrFaceImages}），
	 * 不该每帧对每个像素重算。这里只管"贴上去"和整图透明度。</p>
	 */
	public MmtrPanelCanvas drawImage(BufferedImage source, double x, double y, double width, double height, double alpha) {
		if (source == null || width <= 0 || height <= 0 || alpha <= 0) {
			return this;
		}
		final Composite previous = graphics.getComposite();
		if (alpha < 1) {
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, alpha))));
		}
		graphics.drawImage(source,
			(int) Math.round(px(x)), (int) Math.round(py(y + height)),
			(int) Math.round(px(x + width) - px(x)), (int) Math.round(py(y) - py(y + height)), null);
		graphics.setComposite(previous);
		return this;
	}

	// ---- output ----------------------------------------------------------------------------------

	/** Pixel width of the surface, for callers that keep a texture of matching size. */
	public int widthPx() {
		return image.getWidth();
	}

	/** Pixel height of the surface, for callers that keep a texture of matching size. */
	public int heightPx() {
		return image.getHeight();
	}

	/**
	 * Writes the finished surface INTO an existing (already GPU-allocated) {@link NativeImage}, applying
	 * the AWT-ARGB to memory-ABGR swap described on {@link #toNativeImage()}.
	 *
	 * <p>This is the repaint path: the texture keeps ONE {@code NativeImage} for its whole life, so its GL
	 * id never changes and no cached render layer ever has to be rebuilt. Reading pixels back out of a
	 * {@code NativeImage} is not possible through this mapping (there is no {@code getPixelColor}), so the
	 * canvas has to be pushed rather than pulled.</p>
	 *
	 * @return false when the target cannot hold this canvas, in which case the caller must rebuild
	 */
	public boolean writeInto(NativeImage target) {
		if (target == null || target.getWidth() != image.getWidth() || target.getHeight() != image.getHeight()) {
			return false;
		}
		final int widthPx = image.getWidth();
		final int heightPx = image.getHeight();
		final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (int y = 0; y < heightPx; y++) {
			final int row = y * widthPx;
			for (int x = 0; x < widthPx; x++) {
				final int argb = pixels[row + x];
				// 0xAARRGGBB -> 0xAABBGGRR
				target.setPixelColor(x, y, (argb & 0xFF00FF00)
						| ((argb & 0x00FF0000) >>> 16)
						| ((argb & 0x000000FF) << 16));
			}
		}
		return true;
	}

	/**
	 * The finished surface as an ABGR image ready for a {@code NativeImageBackedTexture}.
	 *
	 * <p><b>ABGR means the bytes are A,B,G,R - so the AWT int (which is 0xAARRGGBB) has to have its red
	 * and blue fields swapped.</b> This used to hand the AWT value straight to
	 * {@code setPixelColor(x, y, argb)}, and the only visible symptom was colour: a test pattern painted
	 * {@code #FF3B30} (red) arrived on the glass as BLUE. That is worth spelling out because a swapped
	 * channel is nearly invisible on this feature - the rain, the sheen and the water film are all
	 * desaturated blue-grey, so only a saturated authored colour exposes it, and the wiper's own colours
	 * are dark greys.</p>
	 */
	public NativeImage toNativeImage() {
		final NativeImage nativeImage = new NativeImage(NativeImageFormat.getAbgrMapped(),
				image.getWidth(), image.getHeight(), false);
		writeInto(nativeImage);
		return nativeImage;
	}

	/** Frees the AWT surface; call after {@link #toNativeImage()}. */
	public void dispose() {
		graphics.dispose();
		image.flush();
	}

	/**
	 * Debug helper: writes the surface as a PNG so a panel can be inspected outside the game (and
	 * compared against what the client actually uploads). Returns false when the write failed.
	 */
	public boolean writePng(java.io.File file) {
		try {
			final java.io.File parent = file.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			return javax.imageio.ImageIO.write(image, "png", file);
		} catch (Exception e) {
			return false;
		}
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private double px(double metres) {
		return metres * scaleX;
	}

	private double py(double metres) {
		return (heightM - metres) * scaleY;
	}

	private double pxWidth(double metres) {
		return metres * Math.min(scaleX, scaleY);
	}

	private static Color colorOf(int argb) {
		return new Color(argb, true);
	}

	private static int arcSteps(double startDegrees, double endDegrees) {
		return Math.max(6, Math.min(256, (int) Math.abs(endDegrees - startDegrees) / 3));
	}
}
