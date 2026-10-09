package org.mtr.core.data;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.PlatformSchema;
import org.mtr.core.mmtr.crowd.MmtrCrowd;
import org.mtr.core.oba.Stop;
import org.mtr.core.oba.StopDirection;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.EnumHelper;
import org.mtr.core.tool.LatLon;
import org.mtr.core.tool.Utilities;
import org.mtr.legacy.data.DataFixer;

/**
 * A boarding location inside a {@link Station} where vehicles dwell to pick up and drop off
 * passengers.
 *
 * <p>Each platform sits on exactly one {@link Rail} segment (its track) and is referenced by
 * the {@link Route}s that stop at it through {@link #routes}; {@link #routeColors} caches the
 * union of route colours for fast UI rendering, and {@link #anglesFromDepot} memoises the
 * approach angle from each depot so the OBA stop-direction lookup does not have to recompute
 * it.</p>
 */
public final class Platform extends PlatformSchema {

	public final ObjectAVLTreeSet<Route> routes = new ObjectAVLTreeSet<>();
	public final IntAVLTreeSet routeColors = new IntAVLTreeSet();
	private final Long2ObjectOpenHashMap<@Nullable Angle> anglesFromDepot = new Long2ObjectOpenHashMap<>();

	public Platform(Position position1, Position position2, TransportMode transportMode, Data data) {
		super(position1, position2, transportMode, data);
	}

	public Platform(ReaderBase readerBase, Data data) {
		super(readerBase, data);
		updateData(readerBase);
		DataFixer.unpackPlatformDwellTime(readerBase, value -> dwellTime = value);
	}

	public void setDwellTime(long dwellTime) {
		this.dwellTime = dwellTime;
	}

	public long getDwellTime() {
		return transportMode.continuousMovement ? 1 : Math.max(1, dwellTime);
	}

	/**
	 * 站台**客量**（0–100，%）：这个站台"有多少人"的权威数值，落盘在
	 * {@code platforms/<末两位hex>/<hex>} 的 {@code crowdLevel} 里，并随
	 * {@link org.mtr.core.operation.DataResponse} 一起下发客户端。
	 *
	 * <p>读的时候夹到 0–100：存档可能被手改过，或者以后调制器写进了越界值，
	 * 而下游（游戏侧铺方块）用它算人数，越界会直接变成负数/无限循环。</p>
	 */
	public long getCrowdLevel() {
		return MmtrCrowd.clamp(crowdLevel);
	}

	/**
	 * 设置客量（0–100）。越界会被夹住；同时把客流的**重算版本号** +1，
	 * 让游戏侧不必等到下一个节拍就能重铺站台上的人（见 {@link MmtrCrowd}）。
	 */
	public void setCrowdLevel(long crowdLevel) {
		this.crowdLevel = MmtrCrowd.clamp(crowdLevel);
		MmtrCrowd.bumpRevision();
	}

	/**
	 * **有效客量**：把 {@link #getCrowdLevel()} 交给调制器跑一遍的结果（现在没有调制器，
	 * 就等于 {@link #getCrowdLevel()}）。想接"临时高峰事件 / 早晚高峰曲线 / 真实候车人数"
	 * 时，用 {@link MmtrCrowd#setModulator} 注册一个调制器即可，不必改这里。
	 *
	 * <p>游戏侧铺方块一律读这个，不读 {@link #getCrowdLevel()}——否则以后接了调制器，
	 * 属性会变而站台上的人不变，看起来就是"设置没生效"。</p>
	 */
	public long getEffectiveCrowdLevel() {
		return MmtrCrowd.modulate(this, getCrowdLevel(), data);
	}

	public void setAngles(long depotId, @Nullable Angle angle) {
		anglesFromDepot.put(depotId, angle);
	}

	public String getStationName() {
		return area == null ? "" : area.getName();
	}

	public Stop getOBAStopElement(IntAVLTreeSet routesUsed) {
		Angle angle = null;
		for (final Angle checkAngle : anglesFromDepot.values()) {
			if (angle == null) {
				angle = checkAngle;
			} else if (angle != checkAngle) {
				angle = null;
				break;
			}
		}

		final LatLon latLon = new LatLon(getMidPosition());
		final String stationName = area == null ? "" : Utilities.formatName(area.getName());
		final Stop stop = new Stop(
			getHexId(),
			getHexId(),
			String.format("%s%s%s%s", stationName, !stationName.isEmpty() && !name.isEmpty() ? " - " : "", name.isEmpty() ? "" : "Platform ", name),
			latLon.lat(),
			latLon.lon(),
			EnumHelper.valueOf(StopDirection.NONE, angle == null ? "" : angle.getClosest45().toString())
		);

		routeColors.forEach(color -> {
			stop.addRouteId(Utilities.numberToPaddedHexString(color, 6));
			routesUsed.add(color);
		});

		return stop;
	}
}
