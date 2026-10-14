package org.mtr.core.mmtr.stopmark;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.simulation.Simulator;

/**
 * **自动车按牌子停**（设计《停车对标》§5.2）：把"这个站台 + 这个辆数 + 这个方向"的牌子换算成
 * {@code MmtrRunPlanner} 要的 {@code stopFraction}。
 *
 * <h3>两套 fraction 口径必须在这里换算（否则车会停在站台另一端）</h3>
 * <ul>
 *   <li><b>牌子</b>的 fraction（{@link MmtrStopMarkerGeometry.Projection#fraction}）：
 *       <b>从 {@code rail.mmtrOrderedPositions()[0]} 那一端量起</b>（轨自己的弧长空间，
 *       与牌子立在哪、车从哪头来都无关）。</li>
 *   <li><b>计划</b>的 {@code stopFraction}（{@code MmtrRunPlanner.planToRail} /
 *       {@code mission.setTargetRail}）：**从进站端量起、1.0 = 远端**（{@code Vehicle.mmtrTravelledOnRailM}
 *       的注释里写死了这条口径，锚点判据 {@code fraction × 本轨长} 用的就是它）。</li>
 * </ul>
 * <p>所以：车从 {@code ends[0]} 进站 ⇒ 两个口径同向，直接取牌子 fraction；车从 {@code ends[1]} 进站
 * ⇒ 要取 {@code 1 − fraction}。这一步错了的症状是"停车点被镜像到站台另一头"。</p>
 *
 * <h3>方向怎么知道</h3>
 * <p>牌子把方向编码在**位置**里（前半段 / 后半段，见 {@link MmtrStopMarkerGeometry#deriveTowardEnd0}），
 * 而"这次自臂会从哪一端进站台"只有进路规划知道。所以这里先用**既有口径 1.0**（远端端点）探一次路 ——
 * 探路结果的最后一个节点就是列车要开向的那一端。方向不会因为停车比例差几米而改变，
 * 因此探路用的 1.0 与最终用的牌子比例得到的是同一条进路。</p>
 *
 * <h3>没有牌子时零成本</h3>
 * <p>登记表里没有这个 (站台, 辆数) 的任何牌子时直接返回 -1，**连探路都不做** ——
 * 老世界/没铺牌子的线路走的就是这条路，行为与今天逐位相同（§9 的兼容口径）。</p>
 */
public final class MmtrStopMarkerStops {

	private MmtrStopMarkerStops() {
	}

	/**
	 * 这次自臂要不要按牌子停。
	 *
	 * @param platformId 目标站台（{@code mission} 的 targetSidingId —— 站台与股道共用这一个 id）
	 * @param targetRail 目标站台的图轨（调用方已经解析出来了）
	 * @param viaNodes   作业单给的经由点（探路必须与最终规划用同一组，否则探出来的进站端可能不是最终那一端）
	 * @return **从进站端算起**的停车比例（0…1）；没有可用牌子、或探不出进路时返回 {@code -1}（= 行为不变）
	 */
	public static double stopFractionFor(Simulator simulator, Vehicle vehicle, long platformId, Rail targetRail, @Nullable ObjectArrayList<String> viaNodes) {
		final int cars = vehicle.vehicleExtraData.immutableVehicleCars.size();
		if (cars < 2 || platformId == 0) {
			return -1;
		}
		if (!hasMarkerFor(simulator, platformId, cars)) {
			return -1;
		}
		final MmtrRunPlanner.Plan probe = MmtrRunPlanner.planToRail(simulator, vehicle, targetRail.getHexId(), 1.0, viaNodes);
		if (!probe.feasible || probe.nodes.isEmpty()) {
			return -1;
		}
		final Position[] ends = targetRail.mmtrOrderedPositions();
		final Position headingTo = probe.nodes.get(probe.nodes.size() - 1);
		final boolean towardEnd0 = headingTo.equals(ends[0]);
		final double[] mid = platformMid(simulator, platformId);
		final MmtrStopMarkerRegistry.StopMarkerEntry marker = simulator.mmtrStopMarkers.find(platformId, cars, towardEnd0, mid[0], mid[1]);
		if (marker == null) {
			return -1;
		}
		final double entryBased = towardEnd0 ? 1.0 - marker.fraction : marker.fraction;
		System.out.println("[MMTR-STOP] 按牌子停 " + cars + " 辆 " + (towardEnd0 ? "朝轨端0" : "朝轨端1")
			+ "：牌子 " + marker.x + "," + marker.y + "," + marker.z + " → 车头停点比例 " + Math.round(marker.fraction * 1000.0) / 1000.0
			+ "（从进站端算 " + Math.round(entryBased * 1000.0) / 1000.0 + "）");
		return Math.max(0.0, Math.min(1.0, entryBased));
	}

	/** 登记表里有没有这个 (站台, 辆数) 的牌子 —— 决定要不要为它探一次路。 */
	private static boolean hasMarkerFor(Simulator simulator, long platformId, int cars) {
		for (final MmtrStopMarkerRegistry.StopMarkerEntry entry : simulator.mmtrStopMarkers.markers.values()) {
			if (entry.platformId == platformId && entry.cars == cars) {
				return true;
			}
		}
		return false;
	}

	/** 站台中点（冲突时"取最近"要用）；站台不存在时 NaN（登记表那边按"第一条为准"处理）。 */
	private static double[] platformMid(Simulator simulator, long platformId) {
		for (final Platform platform : simulator.platforms) {
			if (platform.getId() == platformId) {
				final Position mid = platform.getMidPosition();
				return new double[]{mid.getX(), mid.getZ()};
			}
		}
		return new double[]{Double.NaN, Double.NaN};
	}
}
