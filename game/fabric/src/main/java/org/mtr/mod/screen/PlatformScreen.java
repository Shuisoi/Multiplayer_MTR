package org.mtr.mod.screen;

import org.mtr.core.data.*;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.PacketUpdateData;

public class PlatformScreen extends SavedRailScreenBase<Platform, Station> {

	private static final MutableText DWELL_TIME_TEXT = TranslationProvider.GUI_MTR_DWELL_TIME.getMutableText();
	private static final MutableText ROUTES_AT_PLATFORM_TEXT = TranslationProvider.GUI_MTR_ROUTES_AT_PLATFORM.getMutableText();
	/**
	 * 客量标签。**不走 {@code TranslationProvider}**：那套常量是构建期从 en_us.json 生成的，
	 * 而这个键是这次新加的 —— 用运行期翻译（{@code TextHelper.translatable}）可以让"加一个键"
	 * 不依赖"先跑一次 setupFiles 重新生成常量"，代价只是这里少一层编译期检查。
	 */
	private static final MutableText CROWD_LEVEL_TEXT = TextHelper.translatable("gui.mtr.crowd_level");
	private final ObjectOpenHashSet<Route> routes = new ObjectOpenHashSet<>();
	private final WidgetShorterSlider sliderCrowdLevel;

	public PlatformScreen(Platform savedRailBase, TransportMode transportMode, ScreenExtension previousScreenExtension) {
		super(savedRailBase, transportMode, previousScreenExtension, DWELL_TIME_TEXT, ROUTES_AT_PLATFORM_TEXT);

		// 客量：0–100%，口径 = "站台边缘那一排方块的占用率"（1 格一人 = 100%）。
		sliderCrowdLevel = new WidgetShorterSlider(0, 0, 100, 10, 10, value -> value + "%", null);

		for (Route route : MinecraftClientData.getDashboardInstance().routes) {
			for (RoutePlatformData routePlat : route.getRoutePlatforms()) {
				if (routePlat.getPlatform().getId() == savedRailBase.getId()) {
					routes.add(route);
				}
			}
		}
	}

	@Override
	protected void init2() {
		super.init2();
		sliderDwellTimeMin.setY2(SQUARE_SIZE * 2 + TEXT_FIELD_PADDING);
		sliderDwellTimeMin.setValue((int) Math.floor(savedRailBase.getDwellTime() / 1000F / SECONDS_PER_MINUTE));
		sliderDwellTimeSec.setY2(SQUARE_SIZE * 5 / 2 + TEXT_FIELD_PADDING);
		sliderDwellTimeSec.setValue((int) ((savedRailBase.getDwellTime() / 500) % (SECONDS_PER_MINUTE * 2)));

		/*
		 * 客量那一行放在停留时间下面、线路列表上面（3*SQUARE_SIZE 这一行本来是空的）。
		 * 标签画在左边（与"停留时间"同一个 x），滑杆与两个停留滑杆同宽同左边界 —— 三行看起来是一列控件。
		 * 左边界直接复用基类算好的 textWidth：它已经取了所有标签里最宽的那个（含"停留时间"与"站台线路"），
		 * 而"客量"这个标签比它们都窄，所以不需要再加宽。
		 */
		final int sliderTextWidth = Math.max(GraphicsHolder.getTextWidth(TranslationProvider.GUI_MTR_ARRIVAL_MIN.getMutableText("88")), GraphicsHolder.getTextWidth(TranslationProvider.GUI_MTR_ARRIVAL_SEC.getString("88.8"))) + TEXT_PADDING;
		sliderCrowdLevel.setX2(SQUARE_SIZE + textWidth);
		sliderCrowdLevel.setY2(SQUARE_SIZE * 3 + TEXT_FIELD_PADDING);
		sliderCrowdLevel.setHeight(SQUARE_SIZE / 2);
		sliderCrowdLevel.setWidth2(width - textWidth - SQUARE_SIZE * 2 - sliderTextWidth);
		sliderCrowdLevel.setValue((int) savedRailBase.getCrowdLevel());
		addChild(new ClickableWidget(sliderCrowdLevel));
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		super.render(graphicsHolder, mouseX, mouseY, delta);
		if (showScheduleControls) {
			graphicsHolder.drawText(DWELL_TIME_TEXT, SQUARE_SIZE, SQUARE_SIZE * 2 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		}

		graphicsHolder.drawText(CROWD_LEVEL_TEXT, SQUARE_SIZE, SQUARE_SIZE * 3 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());

		// Draw route lists
		graphicsHolder.drawText(ROUTES_AT_PLATFORM_TEXT, SQUARE_SIZE, SQUARE_SIZE * 4 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		int i = 0;
		for (Route rts : routes) {
			graphicsHolder.drawText(IGui.formatStationName(rts.getName()), SQUARE_SIZE + textWidth, (SQUARE_SIZE * 4) + (10 * i) + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_BLACK + rts.getColor(), false, GraphicsHolder.getDefaultLight());
			i++;
		}
	}

	@Override
	public void onClose2() {
		final int minutes = sliderDwellTimeMin.getIntValue();
		final float second = sliderDwellTimeSec.getIntValue() / 2F;
		savedRailBase.setDwellTime((long) ((second + (long) minutes * SECONDS_PER_MINUTE) * 1000));
		// 客量：滑杆只改本地这一份，关屏时和停留时间一起发回服务器（同一个 UpdateDataRequest）。
		savedRailBase.setCrowdLevel(sliderCrowdLevel.getIntValue());

		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getDashboardInstance()).addPlatform(savedRailBase)));

		super.onClose2();
	}

	@Override
	protected TranslationProvider.TranslationHolder getNumberStringKey() {
		return TranslationProvider.GUI_MTR_PLATFORM_NUMBER;
	}
}
