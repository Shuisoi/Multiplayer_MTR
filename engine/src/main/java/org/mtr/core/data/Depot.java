package org.mtr.core.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.DepotSchema;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.legacy.data.DataFixer;

import java.util.Collections;
import java.util.function.IntConsumer;

/**
 * A bundle of {@link Siding}s plus the platform-by-platform route they all serve.
 *
 * <p>Each {@code Depot} owns the path-generation pipeline for its sidings — building the chain
 * of {@link SidingPathFinder}s, the resulting {@link PathData} list, and the per-day departure
 * timetable that feeds individual sidings via {@link Siding#addDeparture(long)}. The depot's
 * configured frequencies + the simulator's in-game day length feed
 * {@link #generatePlatformDirectionsAndWriteDeparturesToSidings()} which is the heart of the
 * timetable computation.</p>
 */
@Log4j2
public final class Depot extends DepotSchema implements Utilities {

	@Nullable
	private OnGenerationComplete onGenerationComplete;
	private long repeatDepartures;

	public final ObjectArrayList<Route> routes = new ObjectArrayList<>();

	@Getter
	private final ObjectArrayList<PathData> path = new ObjectArrayList<>();
	/**
	 * A temporary list to store all platforms of the vehicle instructions as well as the route used to get to each platform. Repeated platforms are ignored.
	 */
	private final ObjectArrayList<PlatformRouteDetails> platformsInRoute = new ObjectArrayList<>();
	private final ObjectArrayList<SidingPathFinder<Station, Platform, Station, Platform>> sidingPathFinders = new ObjectArrayList<>();
	private final LongAVLTreeSet generatingSidingIds = new LongAVLTreeSet();

	/**
	 * Continuous-movement vehicles (e.g. cable cars) depart at fixed-period intervals.
	 */
	public static final int CONTINUOUS_MOVEMENT_FREQUENCY = 8000;
	/**
	 * MTR depot frequencies are expressed in trains-per-hour scaled by this constant so the
	 * timetable maths can stay in integer arithmetic. Equivalent to four hours of milliseconds —
	 * picked so the smallest non-zero frequency (1 train per 4h) maps to {@code intervalMillis = MILLIS_PER_HOUR}.
	 */
	private static final long FREQUENCY_BASE_MILLIS = 4L * MILLIS_PER_HOUR;
	private static final String KEY_PATH = "path";

	/**
	 * Create a new depot for the given transport mode in the specified simulation or client context.
	 */
	public Depot(TransportMode transportMode, Data data) {
		super(transportMode, data);
	}

	/**
	 * Deserialisation constructor used by the wire / on-disk layer.
	 *
	 * @param readerBase source to read persisted data from
	 * @param data       the simulation engine or client data container
	 */
	public Depot(ReaderBase readerBase, Data data) {
		super(readerBase, data);
		readerBase.iterateReaderArray(KEY_PATH, path::clear, readerBaseChild -> path.add(new PathData(readerBaseChild)));
		super.updateData(readerBase);
		DataFixer.unpackDepotDepartures(readerBase, realTimeDepartures);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		// If this is serverside, don't update these values from an incoming update packet
		final long tempLastGeneratedMillis = lastGeneratedMillis;
		final GeneratedStatus tempLastGeneratedStatus = lastGeneratedStatus;
		final long tempLastGeneratedFailedStartId = lastGeneratedFailedStartId;
		final long tempLastGeneratedFailedEndId = lastGeneratedFailedEndId;
		final long tempLastGeneratedFailedSidingCount = lastGeneratedFailedSidingCount;
		super.updateData(readerBase);
		// Only the simulator-side `Data` is authoritative for generation status; on the client the
		// schema-supplied fields are kept as-is.
		if (data instanceof Simulator) {
			lastGeneratedMillis = tempLastGeneratedMillis;
			lastGeneratedStatus = tempLastGeneratedStatus;
			lastGeneratedFailedStartId = tempLastGeneratedFailedStartId;
			lastGeneratedFailedEndId = tempLastGeneratedFailedEndId;
			lastGeneratedFailedSidingCount = tempLastGeneratedFailedSidingCount;
		}
	}

	@Override
	public void serializeFullData(WriterBase writerBase) {
		super.serializeFullData(writerBase);
		writerBase.writeDataset(path, KEY_PATH);
	}

	/**
	 * 把一条股道**收编**到这个车辆段下（`siding.area = this`）。
	 *
	 * <p>为什么需要这个方法：股道归属车辆段平时由 {@code Data.mapAreasAndSavedRails} 按**几何**判定
	 * （股道中点落在车辆段范围内），而 `data.sync()` 会顺手清空所有车辆 —— 运行中的服务端不能用它来
	 * 给一条**临时**股道接线。这里直接建立归属，供"在任意轨上落车"那种工具路径使用
	 * （见 {@code Simulator.mmtrSpawnOnRail}）。</p>
	 */
	public void adoptSiding(Siding siding) {
		siding.area = this;
		savedRails.add(siding);
	}

	/**
	 * Initialise the depot: write path caches, initialise all sidings, and generate the
	 * platform directions and departure timetable.
	 */
	public void init() {
		writePathCache();
		savedRails.forEach(Siding::init); // Sidings not under a depot will be ignored, but it doesn't matter
		// MTR timetable auto-departure generation at load removed (auto rebuilt on Motion/tasks):
		// nothing auto-dispatches anymore; explicit Depot.generateDepots stays for tools/tests.
	}

	/**
	 * Write path-finding caches for this depot and all its sidings.
	 */
	public void writePathCache() {
		PathData.writePathCache(path, data, transportMode);
		savedRails.forEach(Siding::writePathCache);
	}

	/**
	 * @param useRealTime whether to use wall-clock time ({@code true}) or in-game time ({@code false})
	 *                    for departure scheduling
	 */
	public void setUseRealTime(boolean useRealTime) {
		this.useRealTime = useRealTime;
	}

	/**
	 * Set the departure frequency for a given hour of the day.
	 *
	 * @param hour      hour index (0-23)
	 * @param frequency trains per hour
	 */
	public void setFrequency(int hour, int frequency) {
		if (hour >= 0 && hour < HOURS_PER_DAY) {
			while (frequencies.size() < HOURS_PER_DAY) {
				frequencies.add(0);
			}
			frequencies.set(hour, Math.max(0, frequency));
		}
	}

	/**
	 * @param repeatInfinitely whether the departure schedule should loop forever ({@code true})
	 *                         or respect the in-game day length ({@code false})
	 */
	public void setRepeatInfinitely(boolean repeatInfinitely) {
		this.repeatInfinitely = repeatInfinitely;
	}

	/**
	 * @param cruisingAltitude the altitude at which vehicles in this depot travel
	 */
	public void setCruisingAltitude(long cruisingAltitude) {
		this.cruisingAltitude = cruisingAltitude;
	}

	/**
	 * @return the list of route IDs associated with this depot
	 */
	public LongArrayList getRouteIds() {
		return routeIds;
	}

	/**
	 * @return the timestamp of the last path generation attempt
	 */
	public long getLastGeneratedMillis() {
		return lastGeneratedMillis;
	}

	/**
	 * @return the status of the last path generation attempt
	 */
	public GeneratedStatus getLastGeneratedStatus() {
		return lastGeneratedStatus;
	}

	/**
	 * @param generationStatusConsumer               if path generation failed between two saved rails, this consumer will be called with the saved rail IDs that path generation failed at
	 * @param lastGeneratedFailedSidingCountConsumer if path generation failed between the siding and the main path, this consumer will be called with the number of sidings that couldn't connect to the main path
	 */
	public void getFailedPlatformIds(GenerationStatusConsumer generationStatusConsumer, IntConsumer lastGeneratedFailedSidingCountConsumer) {
		if (lastGeneratedFailedStartId != 0 && lastGeneratedFailedEndId != 0) {
			generationStatusConsumer.accept(lastGeneratedFailedStartId, lastGeneratedFailedEndId);
		}
		if (lastGeneratedFailedSidingCount > 0) {
			lastGeneratedFailedSidingCountConsumer.accept((int) lastGeneratedFailedSidingCount);
		}
	}

	public boolean getRepeatInfinitely() {
		return repeatInfinitely;
	}

	public long getCruisingAltitude() {
		return cruisingAltitude;
	}

	public boolean getUseRealTime() {
		return useRealTime;
	}

	public long getFrequency(int hour) {
		return hour >= 0 && hour < Math.min(HOURS_PER_DAY, frequencies.size()) ? frequencies.getLong(hour) : 0;
	}

	public LongArrayList getRealTimeDepartures() {
		return realTimeDepartures;
	}

	/**
	 * Rebuild the platform-in-route list from the current route data and register this depot
	 * on each referenced route.
	 *
	 * @param routeIdMap map of route ID to Route, used to resolve route references
	 */
	public void writeRouteCache(Long2ObjectOpenHashMap<Route> routeIdMap) {
		routes.clear();
		routeIds.forEach(id -> routes.add(routeIdMap.get(id)));
		for (int i = routes.size() - 1; i >= 0; i--) {
			if (routes.get(i) == null) {
				routeIds.removeLong(i);
				routes.remove(i);
			} else {
				routes.get(i).depots.add(this);
			}
		}

		platformsInRoute.clear();
		long previousPlatformId = 0;
		for (final Route route : routes) {
			for (int i = 0; i < route.getRoutePlatforms().size(); i++) {
				final Platform platform = route.getRoutePlatforms().get(i).platform;
				if (platform != null && platform.getId() != previousPlatformId) {
					// To deal with edge cases (whether the next route starts from the same platform or not), we will always use the "next" data
					// If i == 0, it's the first index of this route, but since we are looking for the "next" data, it's okay to set it as null as the "next" data of the previous route
					platformsInRoute.add(new PlatformRouteDetails(platform, i == 0 ? null : route, i == 0 ? Integer.MAX_VALUE : i - 1));
					previousPlatformId = platform.getId();
				}
			}
		}
	}


	/**
	 * Compute how many in-game day cycles are needed to cover the longest siding journey time,
	 * so that departures repeat enough times to serve the full timetable. Returns 1 when the
	 * game-day length is zero or the highest journey time is zero.
	 */
	private long getRepeatDeparturesForJourneyTime(Simulator simulator) {
		final long gameMillisPerDay = simulator.getGameMillisPerDay();
		if (gameMillisPerDay <= 0) {
			return 1;
		}

		final long highestJourneyTime = savedRails.stream().mapToLong(Siding::getJourneyTime).reduce(0, Math::max);
		return highestJourneyTime == 0 ? 1 : (long) Math.ceil((double) highestJourneyTime / gameMillisPerDay);
	}


	long getRepeatDepartures() {
		return repeatDepartures;
	}




	private record PlatformRouteDetails(Platform platform, @Nullable Route route, int platformIndex) {
	}

	@FunctionalInterface
	public interface GenerationStatusConsumer {
		void accept(long lastGeneratedFailedStartId, long lastGeneratedFailedEndId);
	}

	@FunctionalInterface
	private interface OnGenerationComplete {
		void accept(boolean forceComplete);
	}

	public enum GeneratedStatus {
		NONE, SUCCESSFUL, NO_SIDINGS, TWO_PLATFORMS_REQUIRED, PATH_NOT_FOUND
	}
}
