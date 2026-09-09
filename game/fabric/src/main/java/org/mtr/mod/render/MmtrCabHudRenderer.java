package org.mtr.mod.render;

import javax.annotation.Nullable;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mod.client.MmtrCabPermissions;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;

/**
 * MMTR HUD-2 cab dashboard (科技玻璃驾驶台): glass-tech driving console shown while riding a
 * consist and holding the driver key. Built around the server-authoritative mirror fields:
 * speed, current rail speed limit + regime (AWS &lt;=100 / LZB &gt;=101), LZB ceiling / cab
 * target / distance (HUD-1/37 data plane), AWS point-warning lamp, occupancy hold, protection,
 * doors and power/brake notches. Pure vector drawing - no textures, monochrome glass + neon
 * accents so it reads on any theme.
 *
 * <p>Layout: right side = speed module (digital readout inside a glass dial with the limit /
 * LZB ceiling arc and the needle); left column = mode chips (ATS/ATO, AWS/LZB + limit), the LZB
 * supervision box (target + distance bar), AWS lamp, hold/protection lamps, door + notch row.
 * Everything is anchored to the scaled window so the console survives resolution changes.</p>
 */
public final class MmtrCabHudRenderer {

	@Nullable
	private static VehicleExtension vehicle;

	// Palette (glass tech, neon accents).
	private static final int GLASS_BG = 0xB80A0E14;      // deep translucent panel
	private static final int GLASS_BG_DARK = 0xCC05070B;
	private static final int EDGE_CYAN = 0xFF3FE0FF;
	private static final int TEXT_MAIN = 0xFFE8F4FF;
	private static final int TEXT_DIM = 0xFF8FA6B8;
	private static final int NEON_GREEN = 0xFF3DF59A;
	private static final int NEON_AMBER = 0xFFFFB03A;
	private static final int NEON_RED = 0xFFFF4D55;
	private static final int NEON_BLUE = 0xFF4DA3FF;
	private static final int LZB_COLOR = 0xFF35C4F2;      // LZB band accent
	private static final int AWS_COLOR = 0xFFFFB03A;      // AWS band accent

	private static final int PAD = 10;
	private static final int DIAL_RADIUS = 96;
	private static final int DIAL_START = -120;           // gauge sweep: -120..+120 deg (240 deg)
	private static final int MAX_DIAL_KMH = 300;
	private static final int GAP = 6;

	public static void render(GraphicsHolder graphicsHolder) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (vehicle == null || minecraftClient.getCurrentScreenMapped() != null && !minecraftClient.getCurrentScreenMapped().getTitle().data.toString().contains("chat_screen.title")) {
			return;
		}
		// MMTR: the console is no longer tied to holding a driver key; the permission seam decides.
		if (!MmtrCabPermissions.canViewConsole(minecraftClient.getPlayerMapped(), vehicle.getId())) {
			return;
		}
		final VehicleExtraData extraData = vehicle.vehicleExtraData;
		final Window window = minecraftClient.getWindow();
		final int width = window.getScaledWidth();
		final int height = window.getScaledHeight();
		final GuiDrawing gui = new GuiDrawing(graphicsHolder);
		graphicsHolder.push();

		// ---- Right: speed module ------------------------------------------------
		final int dialCX = width - PAD - DIAL_RADIUS - 8;
		final int dialCY = height - PAD - DIAL_RADIUS - 8;
		drawGlassCard(gui, dialCX - DIAL_RADIUS - 14, dialCY - DIAL_RADIUS - 14, DIAL_RADIUS * 2 + 28, DIAL_RADIUS * 2 + 28, 8);
		drawSpeedDial(graphicsHolder, gui, dialCX, dialCY, extraData);
		drawNotchRow(graphicsHolder, dialCX, dialCY + DIAL_RADIUS + 30);

		// ---- Left column: state chips + LZB/AWS --------------------------------
		int x = PAD;
		int y = PAD + 4;
		drawStatusChips(graphicsHolder, gui, x, y, extraData);
		y += 78;
		drawLzbBox(graphicsHolder, gui, x, y, width, height);
		y += 120;
		drawAwsLamp(graphicsHolder, gui, x, y);
		y += 56;
		drawLampsAndDoors(graphicsHolder, gui, x, y, extraData);

		drawMmtrDebugOverlay(graphicsHolder, gui);
		graphicsHolder.pop();
		vehicle = null;
	}

	public static void setVehicle(VehicleExtension newVehicle) {
		vehicle = newVehicle;
	}

	// ---------------------------------------------------------------------------
	// Speed dial: glass ring with tick marks, rail-limit / LZB ceiling arc, needle.
	// ---------------------------------------------------------------------------
	private static void drawSpeedDial(GraphicsHolder graphicsHolder, GuiDrawing gui, int cx, int cy, VehicleExtraData extraData) {
		final double speedKmh = vehicle.getSpeed() * 3600;
		final long limitKmh = vehicle.getMmtrSpeedLimitKmhFromSync();
		final long lzbCeiling = vehicle.getMmtrLzbCeilingKmhFromSync();
		final boolean lzb = vehicle.isMmtrLzbSupervisingFromSync();
		final long arcTarget = lzb && lzbCeiling > 0 ? lzbCeiling : limitKmh;
		final boolean limitExceeded = arcTarget > 0 && speedKmh > arcTarget;

		// Ring background (rounded look via two stacked arcs of thin segments).
		graphicsHolder.push();
		graphicsHolder.translate(cx, cy, 0);
		graphicsHolder.rotateZDegrees(DIAL_START);
		final int ringSegments = 90;
		for (int i = 0; i < ringSegments; i++) {
			gui.beginDrawingRectangle();
			gui.drawRectangle(-DIAL_RADIUS, -2F, -DIAL_RADIUS + 4, 2F, i % 6 == 0 ? 0xFF26333F : 0xFF141C24);
			gui.finishDrawingRectangle();
			graphicsHolder.rotateZDegrees(240F / ringSegments);
		}
		graphicsHolder.pop();

		// Tick labels (every 60 km/h).
		graphicsHolder.push();
		graphicsHolder.translate(cx, cy, 0);
		graphicsHolder.rotateZDegrees(DIAL_START);
		for (int i = 0; i <= MAX_DIAL_KMH; i += 60) {
			graphicsHolder.push();
			graphicsHolder.translate(-DIAL_RADIUS + 20, 0, 0);
			graphicsHolder.rotateZDegrees(-DIAL_START - i * 240F / MAX_DIAL_KMH);
			drawTextCentered(graphicsHolder, String.valueOf(i), 0.6F, TEXT_DIM);
			graphicsHolder.pop();
			graphicsHolder.rotateZDegrees(60F * 240F / MAX_DIAL_KMH);
		}
		graphicsHolder.pop();

		// Limit / ceiling arc: amber for a rail limit, cyan for an LZB ceiling.
		if (arcTarget > 0) {
			graphicsHolder.push();
			graphicsHolder.translate(cx, cy, 0);
			graphicsHolder.rotateZDegrees(DIAL_START);
			graphicsHolder.rotateZDegrees(Math.min(arcTarget, MAX_DIAL_KMH) * 240F / MAX_DIAL_KMH);
			final int arcColor = lzb ? LZB_COLOR : NEON_AMBER;
			for (int i = 0; i < 5; i++) {
				gui.beginDrawingRectangle();
				gui.drawRectangle(-DIAL_RADIUS + 1, -5F, -DIAL_RADIUS + 9, 5F, arcColor);
				gui.finishDrawingRectangle();
				graphicsHolder.rotateZDegrees(1.2F);
			}
			graphicsHolder.pop();
		}

		// Needle.
		graphicsHolder.push();
		graphicsHolder.translate(cx, cy, 0);
		graphicsHolder.rotateZDegrees(DIAL_START + (float) Math.min(speedKmh, MAX_DIAL_KMH) * 240F / MAX_DIAL_KMH);
		gui.beginDrawingRectangle();
		gui.drawRectangle(-DIAL_RADIUS + 8, -1.5F, -14, 1.5F, limitExceeded ? NEON_RED : TEXT_MAIN);
		gui.finishDrawingRectangle();
		graphicsHolder.pop();

		// Digital readout.
		drawTextCentered(graphicsHolder, String.valueOf((int) Math.round(speedKmh)), 1.8F, limitExceeded ? NEON_RED : TEXT_MAIN);
		drawTextCentered(graphicsHolder, "km/h", 0.6F, TEXT_DIM);
	}

	private static void drawNotchRow(GraphicsHolder graphicsHolder, int cx, int cy) {
		final int power = vehicle.vehicleExtraData.getPowerLevel();
		final String pwrText;
		final int pwrColor;
		if (power < -org.mtr.core.data.Vehicle.MAX_POWER_LEVEL) {
			pwrText = "E";
			pwrColor = NEON_RED;
		} else if (power < 0) {
			pwrText = "B" + -power;
			pwrColor = NEON_AMBER;
		} else if (power > 0) {
			pwrText = "P" + power;
			pwrColor = NEON_BLUE;
		} else {
			pwrText = "N";
			pwrColor = TEXT_DIM;
		}
		drawTextCentered(graphicsHolder, pwrText, 1.2F, pwrColor);
	}

	// ---------------------------------------------------------------------------
	// Status chips row: ATS/ATO, regime (AWS/LZB) + limit.
	// ---------------------------------------------------------------------------
	private static void drawStatusChips(GraphicsHolder graphicsHolder, GuiDrawing gui, int x, int y, VehicleExtraData extraData) {
		final long limitKmh = vehicle.getMmtrSpeedLimitKmhFromSync();
		final boolean lzb = limitKmh >= 101;
		final int regimeColor = lzb ? LZB_COLOR : AWS_COLOR;
		final int xEnd = drawChip(gui, graphicsHolder, x, y, extraData.getIsCurrentlyManual() ? "MANUAL" : "ATO", extraData.getIsCurrentlyManual() ? NEON_GREEN : TEXT_DIM);
		final int xEnd2 = drawChip(gui, graphicsHolder, xEnd + GAP, y, lzb ? "LZB" : "AWS", regimeColor);
		drawChip(gui, graphicsHolder, xEnd2 + GAP, y, (limitKmh > 0 ? String.valueOf(limitKmh) : "--") + " km/h", limitKmh > 0 && vehicle.getSpeed() * 3600 > limitKmh ? NEON_RED : regimeColor);
	}

	private static int drawChip(GuiDrawing gui, GraphicsHolder graphicsHolder, int x, int y, String text, int color) {
		final int textWidth = GraphicsHolder.getTextWidth(text);
		final int chipWidth = textWidth + 16;
		gui.beginDrawingRectangle();
		gui.drawRectangle(x, y, x + chipWidth, y + 18, GLASS_BG);
		gui.drawRectangle(x, y, x + chipWidth, y + 1, color);
		gui.drawRectangle(x, y + 17, x + chipWidth, y + 18, color);
		gui.drawRectangle(x, y, x + 1, y + 18, color);
		gui.drawRectangle(x + chipWidth - 1, y, x + chipWidth, y + 18, color);
		gui.finishDrawingRectangle();
		drawText(graphicsHolder, text, x + 8, y + 5, 0.7F, color);
		return x + chipWidth;
	}

	// ---------------------------------------------------------------------------
	// LZB supervision box: cab target speed + distance bar + ceiling.
	// ---------------------------------------------------------------------------
	private static void drawLzbBox(GraphicsHolder graphicsHolder, GuiDrawing gui, int x, int y, int width, int height) {
		final boolean supervising = vehicle.isMmtrLzbSupervisingFromSync();
		final long ceiling = vehicle.getMmtrLzbCeilingKmhFromSync();
		final long target = vehicle.getMmtrLzbTargetKmhFromSync();
		final double distance = vehicle.getMmtrLzbTargetDistanceMFromSync();

		final int boxWidth = Math.min(width - PAD * 2, 300);
		gui.beginDrawingRectangle();
		gui.drawRectangle(x, y, x + boxWidth, y + 96, GLASS_BG);
		gui.finishDrawingRectangle();
		drawBoxBorder(gui, x, y, boxWidth, 96, supervising ? LZB_COLOR : 0xFF20303C);

		// Title.
		drawText(graphicsHolder, "LZB " + (supervising ? "列控" : "---"), x + 10, y + 6, 0.7F, supervising ? LZB_COLOR : TEXT_DIM);
		if (!supervising) {
			drawText(graphicsHolder, "LZB 连续式列车控制（≥101 km/h 轨段）", x + 10, y + 40, 0.7F, TEXT_DIM);
			return;
		}

		// Target speed (0 = stop target).
		final boolean stopTarget = target == 0;
		final String targetText = stopTarget ? "0" : String.valueOf(target);
		drawTextCenteredAt(graphicsHolder, targetText, x + boxWidth / 2F, y + 46, 2.4F, stopTarget ? NEON_RED : NEON_GREEN);
		drawTextCenteredAt(graphicsHolder, "目标 km/h", x + boxWidth / 2F, y + 72, 0.6F, TEXT_DIM);

		// Distance + ceiling strip.
		drawText(graphicsHolder, "顶棚 " + ceiling + " km/h", x + 10, y + 30, 0.6F, TEXT_DIM);
		if (distance >= 0) {
			drawText(graphicsHolder, "距目标 " + (long) distance + " m", x + boxWidth - 110, y + 30, 0.6F, stopTarget ? NEON_RED : TEXT_MAIN);
			// Distance bar: full = 800 m, decays as the target nears.
			final double ratio = Math.max(0, Math.min(1, distance / 800.0));
			final int barWidth = boxWidth - 20;
			gui.beginDrawingRectangle();
			gui.drawRectangle(x + 10, y + 84, x + 10 + barWidth, y + 86, 0xFF22303C);
			gui.drawRectangle(x + 10, y + 84, x + 10 + (int) (barWidth * ratio), y + 86, stopTarget ? NEON_RED : NEON_GREEN);
			gui.finishDrawingRectangle();
		}
	}

	// ---------------------------------------------------------------------------
	// AWS lamp + occupancy / protection lamps + doors row.
	// ---------------------------------------------------------------------------
	private static void drawAwsLamp(GraphicsHolder graphicsHolder, GuiDrawing gui, int x, int y) {
		final boolean pending = vehicle.isMmtrAwsWarningPendingFromSync();
		final boolean acknowledged = vehicle.isMmtrAwsWarningAcknowledgedFromSync();
		final int stateColor;
		if (pending) {
			// Blink amber while unacknowledged.
			stateColor = System.currentTimeMillis() % 600 < 300 ? NEON_AMBER : 0xFF6B4A10;
		} else if (acknowledged) {
			stateColor = NEON_GREEN;
		} else {
			stateColor = 0xFF22303C;
		}
		final int xEnd = drawLampChip(gui, graphicsHolder, x, y, "AWS", stateColor, pending ? "警示-需确认" : acknowledged ? "已确认" : "");
		// Occupancy hold lamp.
		drawLampChip(gui, graphicsHolder, xEnd + GAP, y, "等待", vehicle.isMmtrBlockHeldFromSync() ? NEON_AMBER : 0xFF22303C, vehicle.isMmtrBlockHeldFromSync() ? "占用" : "");
	}

	private static int drawLampChip(GuiDrawing gui, GraphicsHolder graphicsHolder, int x, int y, String label, int lampColor, String note) {
		final int textWidth = GraphicsHolder.getTextWidth(label);
		final int noteWidth = note.isEmpty() ? 0 : GraphicsHolder.getTextWidth(note);
		final int chipWidth = 40 + textWidth + noteWidth;
		gui.beginDrawingRectangle();
		gui.drawRectangle(x, y, x + chipWidth, y + 22, GLASS_BG);
		gui.finishDrawingRectangle();
		// Lamp.
		gui.beginDrawingRectangle();
		gui.drawRectangle(x + 5, y + 7, x + 15, y + 17, lampColor == 0xFF22303C ? 0xFF12202C : lampColor);
		gui.finishDrawingRectangle();
		drawText(graphicsHolder, label, x + 20, y + 6, 0.7F, TEXT_MAIN);
		if (!note.isEmpty()) {
			drawText(graphicsHolder, note, x + 20 + textWidth + 4, y + 6, 0.6F, lampColor);
		}
		return x + chipWidth;
	}

	private static void drawLampsAndDoors(GraphicsHolder graphicsHolder, GuiDrawing gui, int x, int y, VehicleExtraData extraData) {
		final int xEnd = drawLampChip(gui, graphicsHolder, x, y, "保护", vehicle.isMmtrProtectionFromSync() ? NEON_RED : 0xFF22303C, vehicle.isMmtrProtectionFromSync() ? "SPAD" : "");
		final boolean doorsOpen = extraData.getDoorMultiplier() > 0;
		int xNext = drawChip(gui, graphicsHolder, xEnd + GAP, y, doorsOpen ? "DO" : "DC", doorsOpen ? NEON_GREEN : TEXT_DIM);
		// B7.6h: which side is open (Y = left, U = right). Only shown once the crew works the doors
		// by hand; automatic door opening still reports the plain DO/DC aggregate.
		if (extraData.isMmtrDoorManual()) {
			xNext = drawChip(gui, graphicsHolder, xNext + GAP, y, "L", extraData.getMmtrDoorLeft() ? NEON_GREEN : TEXT_DIM);
			xNext = drawChip(gui, graphicsHolder, xNext + GAP, y, "R", extraData.getMmtrDoorRight() ? NEON_GREEN : TEXT_DIM);
		}
		// C3a 调车授权: the main head stays red, the subsidiary display authorises the movement. Amber,
		// because it is a caution aspect, and it carries the movement's speed limit.
		final String shunt = vehicle.getMmtrShuntAuthorityFromSync();
		if (!shunt.isEmpty()) {
			xNext = drawChip(gui, graphicsHolder, xNext + GAP, y, "副显示 " + Math.round(vehicle.getMmtrShuntSpeedLimitKmhFromSync()), NEON_AMBER);
		}
		// C6 cab naming: car index (1-based) + end, so a double-ended locomotive's two cabs in one car
		// read as "3A" / "3B" and a coupled formation's interior cab is named the same way.
		final String cabName = vehicle.getMmtrCabNameFromSync();
		if (!cabName.isEmpty()) {
			drawChip(gui, graphicsHolder, xNext + GAP, y, "驾驶室 " + cabName, NEON_BLUE);
		}
	}

	// ---------------------------------------------------------------------------
	// Small helpers.
	// ---------------------------------------------------------------------------
	private static void drawGlassCard(GuiDrawing gui, int x, int y, int w, int h, int cornerRadius) {
		gui.beginDrawingRectangle();
		// Main glass fill + inner shadow strip (simple flat glass look).
		gui.drawRectangle(x, y, x + w, y + h, GLASS_BG);
		gui.finishDrawingRectangle();
		drawBoxBorder(gui, x, y, w, h, EDGE_CYAN);
	}

	private static void drawBoxBorder(GuiDrawing gui, int x, int y, int w, int h, int color) {
		gui.beginDrawingRectangle();
		gui.drawRectangle(x, y, x + w, y + 1, color);
		gui.drawRectangle(x, y + h - 1, x + w, y + h, color);
		gui.drawRectangle(x, y, x + 1, y + h, color);
		gui.drawRectangle(x + w - 1, y, x + w, y + h, color);
		gui.finishDrawingRectangle();
	}

	private static void drawText(GraphicsHolder graphicsHolder, String text, float x, float y, float scale, int color) {
		graphicsHolder.push();
		graphicsHolder.translate(x, y, 0);
		graphicsHolder.scale(scale, scale, 1);
		graphicsHolder.drawText(text, 0, -IGui.TEXT_HEIGHT / 2, color, false, GraphicsHolder.getDefaultLight());
		graphicsHolder.pop();
	}

	private static void drawTextCentered(GraphicsHolder graphicsHolder, String text, float scale, int color) {
		drawTextCenteredAt(graphicsHolder, text, 0, 0, scale, color);
	}

	private static void drawTextCenteredAt(GraphicsHolder graphicsHolder, String text, float x, float y, float scale, int color) {
		graphicsHolder.push();
		graphicsHolder.translate(x, y, 0);
		graphicsHolder.scale(scale, scale, 1);
		graphicsHolder.drawText(text, -GraphicsHolder.getTextWidth(text) / 2, -IGui.TEXT_HEIGHT / 2, color, false, GraphicsHolder.getDefaultLight());
		graphicsHolder.pop();
	}

	/**
	 * MMTR state echo (kept from the previous console): server vs rendered speed difference,
	 * authoritative notches/reverser and cab occupation - useful while verifying the HUD data.
	 */
	private static void drawMmtrDebugOverlay(GraphicsHolder graphicsHolder, GuiDrawing gui) {
		final int labelColor = 0xFF8FA6B8;
		final int dimColor = 0xFF5A6B78;
		final int cyanColor = 0xFF35C4F2;
		final int greenColor = 0xFF3DF59A;
		final int redColor = 0xFFFF4D55;
		final double serverSpeed = vehicle.getServerSpeedKilometersPerHour();
		final boolean hasServerSpeed = Double.isFinite(serverSpeed);
		final double speedDifference = vehicle.getSpeed() * 3600 - serverSpeed;
		final int diffColor = !hasServerSpeed ? dimColor : Math.abs(speedDifference) < 1 ? greenColor : redColor;

		final int textLeft = PAD / 2;
		final int textTop = PAD / 2 + 220;
		final String[] lines = {
			"MMTR SRV " + (hasServerSpeed ? Utilities.round(serverSpeed, 1) + " km/h" : "--"),
			"MMTR RND " + Utilities.round(vehicle.getSpeed() * 3600, 1) + " km/h  " + String.format("%+.1f", speedDifference),
			"CTRL P" + vehicle.getMmtrThrottleFromSync() + " B" + vehicle.getMmtrBrakeFromSync() + " R" + (vehicle.getMmtrReverserFromSync() > 0 ? "F" : vehicle.getMmtrReverserFromSync() < 0 ? "R" : "N"),
			"DRV " + (vehicle.getMmtrDriverFromSync().isEmpty() ? "NONE" : vehicle.getMmtrDriverFromSync().equals(minecraftPlayerUuid()) ? "YOU" : "OTHER"),
		};
		int maxWidth = 0;
		for (final String line : lines) {
			maxWidth = Math.max(maxWidth, GraphicsHolder.getTextWidth(line));
		}
		gui.beginDrawingRectangle();
		gui.drawRectangle(textLeft - 6, textTop - 6, textLeft + maxWidth + 6, textTop + lines.length * IGui.LINE_HEIGHT, GLASS_BG_DARK);
		gui.finishDrawingRectangle();
		for (int i = 0; i < lines.length; i++) {
			drawText(graphicsHolder, lines[i], textLeft, textTop + i * IGui.LINE_HEIGHT + IGui.TEXT_HEIGHT / 2F, 0.7F, i == 1 ? diffColor : i == 2 ? cyanColor : labelColor);
		}
	}

	private static String minecraftPlayerUuid() {
		return MinecraftClient.getInstance().getPlayerMapped() == null ? "" : MinecraftClient.getInstance().getPlayerMapped().getUuid().toString();
	}
}
