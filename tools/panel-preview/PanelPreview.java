package mmtr.panelpreview;

import org.mtr.mod.render.panel.MmtrHudLayout;
import org.mtr.mod.render.panel.MmtrPanelCanvas;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paints a vehicle's {@code hud} layout to a PNG WITHOUT launching the game.
 *
 * <p>A dashboard is judged by looking at it, and iterating on one through a full client launch is how a
 * "move this line 2 cm" change turns into an afternoon. This runs the REAL code path
 * ({@code MmtrHudLayout.parse} + {@code MmtrPanelCanvas}, the same classes the client paints with), so
 * what comes out is what the panel will look like - not an approximation that can disagree with it.</p>
 *
 * <p>Usage (through {@code preview.ps1}, which sorts out the classpath):</p>
 * <pre>
 *   PanelPreview &lt;anchorFile.json&gt; &lt;out.png&gt; [speedKmh] [limitKmh] [widthM] [heightM] [pxPerMetre]
 * </pre>
 *
 * <p>{@code widthM}/{@code heightM} default to the model's own {@code mmtr_hud} canvas size as written in
 * the anchor file ({@code canvasWidthM}/{@code canvasHeightM}), so the preview is the right shape without
 * anyone typing the numbers in - a panel drawn at the wrong aspect ratio is the first way a layout
 * preview lies.</p>
 */
public final class PanelPreview {

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: PanelPreview <anchorFile.json> <out.png> [speedKmh] [limitKmh] [widthM] [heightM] [pxPerMetre]");
			System.exit(2);
		}
		final Path anchorFile = Path.of(args[0]);
		if (!Files.exists(anchorFile)) {
			System.err.println("anchor file not found: " + anchorFile);
			System.exit(2);
		}
		final String text = Files.readString(anchorFile, StandardCharsets.UTF_8);
		final int speedKmh = args.length > 2 ? Integer.parseInt(args[2]) : 72;
		final long limitKmh = args.length > 3 ? Long.parseLong(args[3]) : 80;

		// The canvas size: the model's own, unless the caller overrides it.
		final double widthM = args.length > 4 ? Double.parseDouble(args[4]) : doubleFromJson(text, "canvasWidthM", 3.0);
		final double heightM = args.length > 5 ? Double.parseDouble(args[5]) : doubleFromJson(text, "canvasHeightM", 0.46);
		final int pxPerMetre = args.length > 6 ? Integer.parseInt(args[6]) : 256;

		final MmtrHudLayout layout = MmtrHudLayout.parse("preview", text);
		final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(widthM, heightM, pxPerMetre);
		layout.paint(canvas, speedKmh, limitKmh);

		final File out = new File(args[1]);
		if (!canvas.writePng(out)) {
			System.err.println("could not write " + out);
			System.exit(1);
		}
		System.out.println("[panel-preview] " + out.getPath() + "  panel " + widthM + " x " + heightM + " m"
				+ "  speed=" + speedKmh + " limit=" + limitKmh + "  layout=" + layout.id());
		canvas.dispose();
	}

	/**
	 * First occurrence of {@code "key": number} in the text.
	 *
	 * <p>Deliberately not a JSON parser: this only has to read two numbers out of a file the packager
	 * wrote, and the authoritative parse is {@code MmtrHudLayout.parse}. A missing key returns the
	 * fallback, which the caller prints, so a silent 3.0 x 0.46 canvas is visible in the output.</p>
	 */
	private static double doubleFromJson(String text, String key, double fallback) {
		final int at = text.indexOf("\"" + key + "\"");
		if (at < 0) {
			return fallback;
		}
		final int colon = text.indexOf(':', at);
		if (colon < 0) {
			return fallback;
		}
		int end = colon + 1;
		while (end < text.length() && " \t".indexOf(text.charAt(end)) >= 0) {
			end++;
		}
		int stop = end;
		while (stop < text.length() && (Character.isDigit(text.charAt(stop)) || text.charAt(stop) == '.' || text.charAt(stop) == '-')) {
			stop++;
		}
		try {
			return Double.parseDouble(text.substring(end, stop));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
