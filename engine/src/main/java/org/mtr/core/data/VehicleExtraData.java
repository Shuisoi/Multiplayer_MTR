package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.booleans.BooleanBooleanImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.VehicleExtraDataSchema;
import org.mtr.core.path.MmtrLegAppender;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class VehicleExtraData extends VehicleExtraDataSchema {

	@Getter
	private int stopIndex = -1;
	private double oldStoppingPoint;
	private boolean oldDoorTarget;
	private boolean oldMmtrDoorLeft;
	private boolean oldMmtrDoorRight;
	private boolean oldMmtrDoorManual;
	private long oldPowerLevel;
	private double oldSpeedTarget;
	private boolean oldIsCurrentlyManual;
	private boolean hasRidingEntityUpdate;

	/**
	 * {@code path} 的只读快照。**不是 final**：客户端侧的 ①（运动流）会按 {@code LEGS} 增量把新腿接到
	 * 路径末尾（{@link #mmtrApplyLegDelta}），而下游（摆车、占用、站位）全都读这个快照 ——
	 * 不重建的话它们看不见新腿，车头走出旧阴影末端就会重叠/横过来（notes/369 §4.4）。
	 */
	public ObjectImmutableList<PathData> immutablePath;
	public final ObjectImmutableList<VehicleCar> immutableVehicleCars;
	/**
	 * Per-car passenger occupancy sets (runtime only, rebuilt on load). Indexed by car number.
	 */
	public final ObjectImmutableList<ObjectArraySet<Passenger>> passengers;

	private VehicleExtraData(long depotId, long sidingId, double railLength, double totalVehicleLength, long repeatIndex1, long repeatIndex2, boolean isManualAllowed, double maxManualSpeed, long manualToAutomaticTime, double totalDistance, double defaultPosition, ObjectArrayList<VehicleCar> vehicleCars, ObjectArrayList<PathData> path) {
		super(depotId, sidingId, railLength, totalVehicleLength, repeatIndex1, repeatIndex2, isManualAllowed, maxManualSpeed, manualToAutomaticTime, totalDistance, defaultPosition);
		this.path.clear();
		this.path.addAll(path);
		immutablePath = new ObjectImmutableList<>(path);
		this.vehicleCars.clear();
		this.vehicleCars.addAll(vehicleCars);
		immutableVehicleCars = new ObjectImmutableList<>(vehicleCars);
		passengers = new ObjectImmutableList<>(vehicleCars.stream().map(vehicleCar -> new ObjectArraySet<Passenger>()).toList());
	}

	public VehicleExtraData(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
		immutablePath = new ObjectImmutableList<>(path);
		immutableVehicleCars = new ObjectImmutableList<>(vehicleCars);
		passengers = new ObjectImmutableList<>(vehicleCars.stream().map(vehicleCar -> new ObjectArraySet<Passenger>()).toList());
	}

	public VehicleExtraData copy(int pathUpdateIndex) {
		final VehicleExtraData newVehicleExtraData = new VehicleExtraData(new JsonReader(Utilities.getJsonObjectFromData(this)));
		newVehicleExtraData.path.clear();

		for (int i = pathUpdateIndex; i <= path.size(); i++) {
			/*
			 * **path 为空时不许取 path.get(i)**（2026-09-16 用例抓到）：原来的条件是
			 * `i == path.size() && !path.isEmpty()`，而 path 为空时 i=0 == size=0 走 else 分支
			 * ⇒ `path.get(0)` 抛 IndexOutOfBounds。这个异常发生在**同步路径**上，
			 * 会被 tick 外层那个 `catch (Throwable)` 接住并**中断本 tick 剩下的全部工作** ——
			 * 也就是"一辆没有 path 的车（比如还没排班的车底）一旦变脏，整个 tick 就废掉"。
			 */
			if (i >= path.size()) {
				if (!path.isEmpty()) {
					newVehicleExtraData.path.add(0, path.getFirst());
				}
			} else {
				final PathData pathData = path.get(i);
				if (i == pathUpdateIndex || pathData.getStartDistance() <= stoppingPoint) {
					newVehicleExtraData.path.add(pathData);
				} else {
					break;
				}
			}
		}

		return newVehicleExtraData;
	}

	public long getDepotId() {
		return depotId;
	}

	public long getSidingId() {
		return sidingId;
	}

	public long getPreviousRouteId() {
		return previousRouteId;
	}

	public long getPreviousPlatformId() {
		return previousPlatformId;
	}

	public long getPreviousStationId() {
		return previousStationId;
	}

	public int getPreviousRouteColor() {
		return (int) (previousRouteColor & 0xFFFFFF);
	}

	public String getPreviousRouteName() {
		return previousRouteName;
	}

	public String getPreviousRouteNumber() {
		return previousRouteNumber;
	}

	public RouteType getPreviousRouteType() {
		return previousRouteType;
	}

	public Route.CircularState getPreviousRouteCircularState() {
		return previousRouteCircularState;
	}

	public String getPreviousStationName() {
		return previousStationName;
	}

	public String getPreviousRouteDestination() {
		return previousRouteDestination;
	}

	public long getThisRouteId() {
		return thisRouteId;
	}

	public long getThisPlatformId() {
		return thisPlatformId;
	}

	public long getThisStationId() {
		return thisStationId;
	}

	public int getThisRouteColor() {
		return (int) (thisRouteColor & 0xFFFFFF);
	}

	public String getThisRouteName() {
		return thisRouteName;
	}

	public String getThisRouteNumber() {
		return thisRouteNumber;
	}

	public RouteType getThisRouteType() {
		return thisRouteType;
	}

	public Route.CircularState getThisRouteCircularState() {
		return thisRouteCircularState;
	}

	public String getThisStationName() {
		return thisStationName;
	}

	public String getThisRouteDestination() {
		return thisRouteDestination;
	}

	public long getNextRouteId() {
		return nextRouteId;
	}

	public long getNextPlatformId() {
		return nextPlatformId;
	}

	public long getNextStationId() {
		return nextStationId;
	}

	public int getNextRouteColor() {
		return (int) (nextRouteColor & 0xFFFFFF);
	}

	public String getNextRouteName() {
		return nextRouteName;
	}

	public String getNextRouteNumber() {
		return nextRouteNumber;
	}

	public RouteType getNextRouteType() {
		return nextRouteType;
	}

	public Route.CircularState getNextRouteCircularState() {
		return nextRouteCircularState;
	}

	public String getNextStationName() {
		return nextStationName;
	}

	public String getNextRouteDestination() {
		return nextRouteDestination;
	}

	public boolean getIsTerminating() {
		return isTerminating;
	}

	public double getAcceleration() {
		return acceleration;
	}

	public double getDeceleration() {
		return deceleration;
	}

	public void iterateInterchanges(BiConsumer<String, InterchangeColorsForStationName> consumer) {
		interchangeColorsForStationNameList.forEach(interchangeColorsForStationName -> consumer.accept(interchangeColorsForStationName.getStationName(), interchangeColorsForStationName));
	}

	public void iterateRidingEntities(Consumer<VehicleRidingEntity> consumer) {
		ridingEntities.forEach(consumer);
	}

	public int getDoorMultiplier() {
		return doorTarget ? 1 : -1;
	}

	public double getStoppingPoint() {
		return stoppingPoint;
	}

	public int getPowerLevel() {
		return (int) powerLevel;
	}

	public double getSpeedTarget() {
		return speedTarget;
	}

	public boolean getIsCurrentlyManual() {
		return isCurrentlyManual;
	}

	public double getTotalVehicleLength() {
		return totalVehicleLength;
	}

	/**
	 * MMTR (L3): replace the synced path list (serialized into client VehicleUpdates) with the live
	 * Motion-Core leg shadow, so a motion vehicle's client mirror receives the rails it is running on.
	 */
	public void mmtrSetSyncPath(ObjectArrayList<PathData> legs) {
		path.clear();
		path.addAll(legs);
		/*
		 * 刻意**不**在这里重建 {@link #immutablePath}：这一支在**服务端**每个走行 tick 都会被调用
		 * （{@code Vehicle#refreshMmtrMotionLegs}），而服务端读 {@code immutablePath} 的地方
		 * （legacy 摆车/占用/停车点）读的一直是**装车时那份烘焙路径** —— 在这里顺手换掉它是另一件事，
		 * 不做就不会有"顺手改坏了服务端"的现场。客户端那一半在 {@link #mmtrApplyLegDelta} 里重建。
		 */
	}

	/**
	 * **① 的 {@code LEGS} 记录落到客户端镜像的路径上**（notes/369 §4.4 / S3b；notes/375 加整表）。
	 *
	 * <p>几何与里程的规矩全在 {@link MmtrLegAppender} 里（"没有基准就不接 / 整表就按锚点重建 /
	 * 接不上就一根都不动"），这里只做两件本地的事：把 {@code path} 换过之后**重建 {@link #immutablePath}**
	 * （否则下游读到的还是旧快照），以及把结果原样交给调用方记账。</p>
	 *
	 * @param droppedFromTrainTail 从车尾端丢掉的条数；{@code MmtrMotionFrame#LEGS_FULL_REPLACE} = 整表替换
	 * @param anchorM              整表替换时那张表的起点里程（增量时只是随行自描述）
	 * @param tailDistanceM        车尾在本路径坐标空间里的位置（{@code railProgress - 车长}）：只用来判断
	 *                              车尾端那几根腿能不能丢（丢早了会把车底下那一根丢没，表现是整车瞬移）
	 */
	public MmtrLegAppender.Applied mmtrApplyLegDelta(int droppedFromTrainTail, double anchorM, java.util.List<org.mtr.core.mmtr.net.MmtrMotionFrame.Leg> newLegs, java.util.Map<String, Rail> railIdMap, double tailDistanceM) {
		final MmtrLegAppender.Applied applied = MmtrLegAppender.apply(path, droppedFromTrainTail, anchorM, newLegs, railIdMap, tailDistanceM);
		if (!applied.isEmpty()) {
			mmtrRebuildImmutablePath();
		}
		return applied;
	}

	private void mmtrRebuildImmutablePath() {
		immutablePath = new ObjectImmutableList<>(path);
	}

	/** MMTR (L3): mark the vehicle dirty so the next tick pushes a client update (mirror refresh). */
	public void mmtrMarkSyncDirty() {
		hasRidingEntityUpdate = true;
	}

	public double getMaxManualSpeed() {
		return maxManualSpeed;
	}

	public boolean getIsManualAllowed() {
		return isManualAllowed;
	}

	protected double getRailLength() {
		return railLength;
	}

	protected int getRepeatIndex1() {
		return (int) repeatIndex1;
	}

	protected int getRepeatIndex2() {
		return (int) repeatIndex2;
	}

	protected long getManualToAutomaticTime() {
		return manualToAutomaticTime;
	}

	protected double getTotalDistance() {
		return totalDistance;
	}

	protected double getDefaultPosition() {
		return defaultPosition;
	}

	protected void setStoppingPoint(double stoppingPoint) {
		this.stoppingPoint = stoppingPoint;
	}

	protected void setPowerLevel(int powerLevel) {
		this.powerLevel = powerLevel;
	}

	protected void setSpeedTarget(double speedTarget) {
		this.speedTarget = speedTarget;
	}

	protected void setIsCurrentlyManual(boolean isCurrentlyManual) {
		this.isCurrentlyManual = isCurrentlyManual;
	}

	protected void toggleDoors() {
		// MTR's own manual driver key: both sides, automatic (platform) side selection stays in charge.
		final boolean open = !doorTarget;
		mmtrDoorLeft = open;
		mmtrDoorRight = open;
		mmtrDoorManual = false;
		doorTarget = open;
	}

	protected void openDoors() {
		doorTarget = true;
		mmtrDoorLeft = true;
		mmtrDoorRight = true;
		mmtrDoorManual = false;
	}

	protected void closeDoors() {
		doorTarget = false;
		mmtrDoorLeft = false;
		mmtrDoorRight = false;
		mmtrDoorManual = false;
	}

	/** Which doors a crew door command addresses. */
	public enum MmtrDoorSide {
		LEFT,
		RIGHT,
		BOTH;

		public static MmtrDoorSide parse(@Nullable String value) {
			if (value == null) {
				return BOTH;
			}
			return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
				case "left", "l" -> LEFT;
				case "right", "r" -> RIGHT;
				default -> BOTH;
			};
		}
	}

	/** The crew-commanded left door target (see {@link #mmtrSetDoors(String, String)}). */
	public boolean getMmtrDoorLeft() {
		return mmtrDoorLeft;
	}

	/** The crew-commanded right door target (see {@link #mmtrSetDoors(String, String)}). */
	public boolean getMmtrDoorRight() {
		return mmtrDoorRight;
	}

	/**
	 * Whether a crew member is working the doors by hand. While true the client opens exactly the
	 * commanded sides and ignores the platform-proximity rule MTR uses for automatic door opening -
	 * that rule is why "the HUD says the doors are open but nothing moves" off a platform.
	 */
	public boolean isMmtrDoorManual() {
		return mmtrDoorManual;
	}

	/** Whether the given side is currently commanded open. */
	public boolean getMmtrDoorOpen(MmtrDoorSide side) {
		return switch (side) {
			case LEFT -> mmtrDoorLeft;
			case RIGHT -> mmtrDoorRight;
			case BOTH -> mmtrDoorLeft || mmtrDoorRight;
		};
	}

	/**
	 * MMTR B7.6g: crew door control from the game side (the interact key aimed at a door), so a train
	 * standing with its doors closed can be opened from the platform without boarding it first.
	 *
	 * @param action {@code "open"}, {@code "close"} or anything else for a toggle
	 * @return the door target after the change, {@code true} when the doors are now open
	 */
	public boolean mmtrSetDoors(String action) {
		return mmtrSetDoors(action, "both");
	}

	/**
	 * MMTR: crew door control per side ({@code "left"} / {@code "right"} / {@code "both"}).
	 *
	 * <p>Rail practice is per-side: the driver opens only the platform side. The engine keeps the two
	 * sides independently and the aggregate {@code doorTarget} (what MTR's passenger logic and the HUD
	 * read) is simply "either side open". A crew command also marks the doors <em>manual</em>, which
	 * tells the client to honour these flags instead of its platform-proximity rule - so the doors
	 * move even when the train stands in a siding.</p>
	 *
	 * @param action {@code "open"}, {@code "close"} or anything else for a toggle
	 * @param side   {@code "left"}, {@code "right"} or {@code "both"}
	 * @return the aggregate door target after the change
	 */
	public boolean mmtrSetDoors(String action, @Nullable String side) {
		final MmtrDoorSide doorSide = MmtrDoorSide.parse(side);
		final boolean open = switch (action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "open" -> true;
			case "close" -> false;
			default -> !getMmtrDoorOpen(doorSide);
		};
		switch (doorSide) {
			case LEFT -> mmtrDoorLeft = open;
			case RIGHT -> mmtrDoorRight = open;
			case BOTH -> {
				mmtrDoorLeft = open;
				mmtrDoorRight = open;
			}
		}
		mmtrDoorManual = true;
		doorTarget = mmtrDoorLeft || mmtrDoorRight;
		return doorTarget;
	}

	public boolean mmtrDoorsOpen() {
		return doorTarget;
	}

	protected boolean checkForUpdate() {
		// The per-side door flags and the manual flag are mirrored too: closing one side while the
		// other stays open leaves doorTarget unchanged, and the client still has to see it.
		final boolean needsUpdate = Math.abs(stoppingPoint - oldStoppingPoint) > 0.01 || doorTarget != oldDoorTarget || powerLevel != oldPowerLevel || Math.abs(speedTarget - oldSpeedTarget) > 0.01 || isCurrentlyManual != oldIsCurrentlyManual || mmtrDoorLeft != oldMmtrDoorLeft || mmtrDoorRight != oldMmtrDoorRight || mmtrDoorManual != oldMmtrDoorManual || hasRidingEntityUpdate;
		oldStoppingPoint = stoppingPoint;
		oldDoorTarget = doorTarget;
		oldPowerLevel = powerLevel;
		oldSpeedTarget = speedTarget;
		oldIsCurrentlyManual = isCurrentlyManual;
		oldMmtrDoorLeft = mmtrDoorLeft;
		oldMmtrDoorRight = mmtrDoorRight;
		oldMmtrDoorManual = mmtrDoorManual;
		hasRidingEntityUpdate = false;
		return needsUpdate;
	}

	protected void setRoutePlatformInfo(@Nullable Depot depot, int currentIndex) {
		// MTR route/timetable info removed with the auto service (auto rebuilt on Motion/tasks):
		// trains no longer report previous/this/next timetable stops; fields stay cleared.
	}

	BooleanBooleanImmutablePair containsDriverAndDoorOverride() {
		boolean containsDriver = false;
		boolean doorOverride = false;
		for (final VehicleRidingEntity vehicleRidingEntity : ridingEntities) {
			if (vehicleRidingEntity.isDriver()) {
				containsDriver = true;
			}
			if (vehicleRidingEntity.getDoorOverride()) {
				doorOverride = true;
			}
			if (containsDriver && doorOverride) {
				break;
			}
		}
		return new BooleanBooleanImmutablePair(containsDriver, doorOverride);
	}

	void removeRidingEntitiesIf(Predicate<VehicleRidingEntity> predicate) {
		if (ridingEntities.removeIf(predicate)) {
			hasRidingEntityUpdate = true;
		}
	}

	void addRidingEntities(ObjectOpenHashSet<VehicleRidingEntity> vehicleRidingEntitiesToAdd) {
		if (ridingEntities.addAll(vehicleRidingEntitiesToAdd)) {
			hasRidingEntityUpdate = true;
		}
	}

	boolean hasRidingEntity(UUID uuid) {
		return ridingEntities.stream().anyMatch(vehicleRidingEntity -> vehicleRidingEntity.uuid.equals(uuid));
	}

	/**
	 * T2 (Motion Core adoption): builds a {@link VehicleExtraData} whose running path is supplied
	 * directly as an ordered, cumulative leg list (e.g. {@code MmtrMotionWalker.buildLegs()} — a route
	 * Motion Core decided segment by segment by turnout authority) instead of assembling the path from
	 * the pre-baked per-siding caches ({@code pathSidingToMainRoute/pathMainRoute/pathMainRouteToSiding}).
	 * A real {@code Vehicle} constructed with this runs exactly the Motion-Core-chosen rails.
	 */
	public static VehicleExtraData createWithLegs(
		long depotId, long sidingId, double railLength, ObjectArrayList<VehicleCar> vehicleCars, ObjectArrayList<PathData> legs,
		boolean isManualAllowed, double maxManualSpeed, long manualToAutomaticTime
	) {
		final ObjectArrayList<PathData> path = legs == null ? new ObjectArrayList<>() : new ObjectArrayList<>(legs);
		final double newRailLength = Siding.getRailLength(railLength);
		final double newTotalVehicleLength = Siding.getTotalVehicleLength(vehicleCars);
		final double totalDistance = path.isEmpty() ? 0 : org.mtr.core.tool.Utilities.getElement(path, -1).getEndDistance();
		final double defaultPosition = (newRailLength + newTotalVehicleLength) / 2;
		/*
		 * notes/235：镜像里的 acceleration / deceleration 不再是"车场配置的原版加减速常数"（那套已删除），
		 * 而是**本车力模型当前的能力值**，由 {@code Vehicle#updateMmtrSyncFields()} 每 tick 写进来 ——
		 * 客户端的信号预留足迹（padding）与电机音调用的是真数，不再是所有车一个常数。
		 */
		return new VehicleExtraData(depotId, sidingId, newRailLength, newTotalVehicleLength, 0, 0,
			isManualAllowed,
			Math.max(org.mtr.core.tool.Utilities.kilometersPerHourToMetersPerMillisecond(1), maxManualSpeed),
			manualToAutomaticTime, totalDistance, defaultPosition, vehicleCars, path);
	}

	/** notes/235：把本车**当前**的纵向加速度能力（SI，m/s²）写进镜像（供客户端预测/音调）。 */
	void setMmtrAccelerationSi(double accelerationSi) {
		acceleration = Math.max(0, accelerationSi);
	}

	/** notes/235：把本车**当前**的常用制动减速度（SI，m/s²）写进镜像（供客户端信号预留足迹）。 */
	void setMmtrDecelerationSi(double decelerationSi) {
		deceleration = Math.max(1e-6, decelerationSi);
	}

	/**
	 * notes/250：把**电机当前出力**（N，牵引为正、电阻制动为负）写进镜像 —— 右上角 HUD 的
	 * "电机做功"读数读的就是它。
	 *
	 * <p>为什么必须走镜像而不是让客户端自己算：客户端镜像对象**每帧重建（实测 ~17 次/秒）**，
	 * 控制器里的累积状态（实际牵引比）每次都被清零 ⇒ 客户端自算会得到锯齿读数，
	 * 现场表现就是"数字超高速乱跳"。走快照值则与服务端权威同源、逐帧稳定。</p>
	 */
	void setMmtrMotorForceN(double motorForceN) {
		mmtrMotorForceN = motorForceN;
	}

	/** 电机当前出力（N，带符号）—— 客户端 HUD 读这一份（服务端写的镜像值）。 */
	public double getMmtrMotorForceN() {
		return mmtrMotorForceN;
	}

	/**
	 * notes/269：把**整列气制动力**（N，正值 = 在刹车）写进镜像 —— HUD 的「制动力（气）」读它。
	 *
	 * <p>为什么必须由服务端写：逐车管压（notes/268）＋电空混合（notes/267）之后，"车头缸压 × 制动锚"
	 * 不再等于整列气制动力（电制动会把机车自己那份缸压削到 0，而拖车还在刹），
	 * 客户端靠车头缸压反算就会显示成"只有电制动"。</p>
	 */
	void setMmtrPneumaticBrakeForceN(double pneumaticBrakeForceN) {
		mmtrPneumaticBrakeForceN = pneumaticBrakeForceN;
	}

	/** 整列气制动力（N，正值 = 在刹车）—— 客户端 HUD 读这一份（服务端写的镜像值）。 */
	public double getMmtrPneumaticBrakeForceN() {
		return mmtrPneumaticBrakeForceN;
	}



	private static long getId(@Nullable NameColorDataBase data) {
		return data == null ? 0 : data.getId();
	}

	private static String getName(@Nullable NameColorDataBase data) {
		return data == null ? "" : data.getName();
	}

	private static int getColor(@Nullable NameColorDataBase data) {
		return data == null ? 0 : data.getColor();
	}

	private static long getStationId(@Nullable Platform platform) {
		return platform == null ? 0 : getId(platform.area);
	}

	private static String getStationName(@Nullable Platform platform) {
		return platform == null ? "" : getName(platform.area);
	}

	private static String getRouteNumber(@Nullable Route route) {
		return route == null ? "" : route.getRouteNumber();
	}

	private static RouteType getRouteType(@Nullable Route route) {
		return route == null ? RouteType.NORMAL : route.getRouteType();
	}

	private static Route.CircularState getRouteCircularState(@Nullable Route route) {
		return route == null ? Route.CircularState.NONE : route.getCircularState();
	}

	private static String getRouteDestination(@Nullable Route route, int stopIndex) {
		return route == null ? "" : route.getDestination(stopIndex);
	}

	public static class VehiclePlatformRouteInfo {

		@Nullable
		private final Platform previousPlatform;
		@Nullable
		private final Platform thisPlatform;
		@Nullable
		private final Platform nextPlatform;
		@Nullable
		private final Route previousRoute;
		@Nullable
		private final Route thisRoute;
		@Nullable
		private final Route nextRoute;
		private final int platformIndexInRoute;

		public VehiclePlatformRouteInfo(@Nullable Platform previousPlatform, @Nullable Platform thisPlatform, @Nullable Platform nextPlatform, @Nullable Route previousRoute, @Nullable Route thisRoute, @Nullable Route nextRoute, int platformIndexInRoute) {
			this.previousPlatform = previousPlatform;
			this.thisPlatform = thisPlatform;
			this.nextPlatform = nextPlatform;
			this.previousRoute = previousRoute;
			this.thisRoute = thisRoute;
			this.nextRoute = nextRoute;
			this.platformIndexInRoute = platformIndexInRoute;
		}
	}
}