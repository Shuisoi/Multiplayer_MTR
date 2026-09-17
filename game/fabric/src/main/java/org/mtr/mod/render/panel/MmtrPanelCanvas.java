package org.mtr.mod.render.panel;

import org.mtr.mapping.holder.NativeImage;
import org.mtr.mapping.holder.NativeImageFormat;
import org.mtr.mod.data.IGui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

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

	/** Panels are small; anything bigger than this is a modelling mistake, so clamp instead of exploding. */
	private static final int MAX_PIXELS = 512;
	private static final int MIN_PIXELS = 8;

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

	/** A filled convex polygon; the two arrays are x/y pairs in metres and must have the same length. */
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

	// ---- output ----------------------------------------------------------------------------------

	/** The finished surface as an ABGR image ready for a {@code NativeImageBackedTexture}. */
	public NativeImage toNativeImage() {
		final int widthPx = image.getWidth();
		final int heightPx = image.getHeight();
		final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		final NativeImage nativeImage = new NativeImage(NativeImageFormat.getAbgrMapped(), widthPx, heightPx, false);
		for (int y = 0; y < heightPx; y++) {
			final int row = y * widthPx;
			for (int x = 0; x < widthPx; x++) {
				nativeImage.setPixelColor(x, y, pixels[row + x]);
			}
		}
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
