package org.mtr.mod.mmtr.crowd;

import org.mtr.core.Main;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.crowd.MmtrCrowd;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.*;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockCrowdMarker;
import org.mtr.mod.block.BlockCrowdVillager;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.block.PlatformHelper;
import org.mtr.mod.packet.PacketUpdateData;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * **站台客量的数据面**（方案 A，notes/416）：把引擎里的 {@code Platform.crowdLevel}（0–100%）
 * 同步到客户端镜像，并维护"站台 ↔ 标记格"这份几何账（{@code crowd scan} / {@code crowd dump} 的读数）。
 *
 * <h3>现在这个模块不再做什么（2026-10-12 方案 A）</h3>
 * <p>用户口径（原话）："<i>服务端只把站台客流量给客户端，渲染由客户端自由决定</i>"。于是
 * <b>"按客量选格 → 把标记方块标成 {@code occupied} → 客户端按格画人"这条链路整条停掉</b>：</p>
 * <ul>
 *   <li>不再有"目标格集合"：没有逐格挑选、没有 7:3 分配、没有单站台上限裁剪
 *       （原 {@code select()/pick()}，已删）；</li>
 *   <li>不再往世界里写方块状态：没有 {@code occupied} 写入、没有老座位格迁移、没有逐格收人
 *       （原 {@code enqueueDiff/applySeatOp/seatQueue}，已删）。
 *       {@code occupied} 作为方块属性**留着**（{@link BlockCrowdMarker} 的老存档兼容与手持显形照旧），
 *       只是没人再为渲染写它；</li>
 *   <li>不再养走动人流实体（原 {@code MmtrCrowdWalkers.reconcile}，已停；
 *       {@link MmtrCrowdWalkers} / {@link MmtrCrowdVillagers} 两个类原样保留，
 *       见 {@link #LEGACY_SPAWNING}）。</li>
 * </ul>
 * <p>客量到画面的整条路现在只剩两段：本模块把 {@code crowdLevel} **推**到客户端镜像
 * （{@link #syncCrowdLevelsToClients}，每 tick 比对、变了就推），客户端渲染器
 * （{@code MmtrCrowdStatues}）自己按 tile 批数决定画几个人 ——
 * 客量变化 = 只改绘制批数：0 重建、0 上传、0 方块写入。</p>
 *
 * <h3>这个模块还做什么</h3>
 * <ol>
 *   <li><b>客量同步</b>：{@link #syncCrowdLevelsToClients} —— 权威值只有 {@code crowdLevel}，
 *       客户端镜像靠它更新（这是"改了 ≤2 秒画面就变"的那条路）。</li>
 *   <li><b>几何账</b>：站台几何 + 标记方块（{@code scan/sweepMarkers/assignMarkers}），
 *       5 秒一拍、每拍 8 ms 预算。它不再驱动任何世界改动，只喂两个读数入口
 *       {@link #report}（{@code crowd scan}）与 {@link #dump}（{@code crowd dump}）——
 *       "我放的标记认到没有、算给谁了"这类问题仍然只有逐格扫才答得出。</li>
 *   <li><b>人数闸门的持久化</b>：{@link #loadConfig} / {@link #mirrorConfigIfChanged}。</li>
 *   <li><b>旧链路的收尾</b>：老存档里"上一版铺下去的客流方块/村民实体"仍然清掉
 *       （{@link #cleanUpLegacyCrowdVillagerBlocks} 与 {@link #discardLegacyCrowdEntities}），
 *       否则它们会和客户端新画的雕像重影。</li>
 * </ol>
 *
 * <h3>怎么找"人站的地方"</h3>
 * <p>站台实体只给了两个轨道端点，没有"站台面在哪、多宽"。两条来源：</p>
 * <ul>
 *   <li><b>自动区</b>：沿站台轨每 1 格取一点，在法线两侧从近到远找第一块 MTR 站台方块
 *       （{@link PlatformHelper}，含 full/slab/indented），再从它往站台里数 {@code autoRows} 格；
 *       每格都要求"脚下是同一高度的实心地面、这一格是空的"（墙、台阶、长椅自然被筛掉）。</li>
 *   <li><b>标记</b>：{@link BlockCrowdMarker} 的四个方块。沿站台轨逐格扫方块状态把它们找出来，
 *       每格按"最近的站台方块"归属到某条站台，**归属锚**再把半径内的整片标记区改判到它声明的站台
 *       （站厅、扶梯口那种离站台远的标记区就靠它挂上客量）。</li>
 * </ul>
 *
 * <h3>两件必须守住的事</h3>
 * <ul>
 *   <li><b>不加载区块</b>：读任何方块前先 {@link #isLoaded}。未加载的区块里
 *       {@code getBlockState} 会把区块从磁盘拉起来（甚至生成地形），一个 200 格站台能把服务端卡住。</li>
 *   <li><b>摊在多个 tick 里</b>：几何扫描照 {@code RailAction} 的做法给每 tick 8 ms 预算，
 *       做不完下一拍接着做。</li>
 * </ul>
 */
public final class MmtrCrowdModule {

	/** 每隔这么久核对一遍所有站台（自愈节拍）。 */
	private static final long PASS_INTERVAL_MILLIS = 5_000L;
	/**
	 * 站台几何（轨/端点/站台方块）与**标记**至少这么久重扫一次。
	 *
	 * <p>15 秒是"放一个标记方块，人多久站上去"的体感上限。扫描是一格一格读方块，成本随区块是否加载变化，
	 * 但只有加载着的站台才真的读 —— 一个 220 格站台（自动区 + 标记窗口）约 2 ms，摊在每拍 8 ms 的预算里。</p>
	 */
	private static final long RESCAN_INTERVAL_MILLIS = 15_000L;
	/** 一个 tick 最多重扫几何花多少毫秒（摊到多拍；见 {@link #scanNext}）。 */
	private static final long SCAN_BUDGET_MILLIS = 8L;
	/** 一个 tick 里清理遗留客流方块最多花多少毫秒。 */
	private static final long APPLY_BUDGET_MILLIS = 2L;
	/** 单 tick 遗留方块清理操作数的兜底上限。 */
	private static final int MAX_OPS_PER_TICK = 400;
	/** 沿站台轨取点的步长（格）。 */
	private static final double SAMPLE_STEP = 1.0;
	/** 从轨道往两侧找站台方块的最远距离（格）。 */
	private static final int SIDE_SEARCH_BLOCKS = 5;
	/** 找站台面时试的高度（相对轨面）：站台方块一般和轨节点同一层，上下各留一格余量。 */
	private static final int[] Y_OFFSETS = {0, -1, 1, -2, 2};
	/**
	 * 地面高度容差（格）：1/4 格多一点 —— 容得下"内缩站台"的 13/16（差 3/16），
	 * 挡得住台阶与半砖（差半格）。超出这个差值的格子说明那不是同一层站台面，不站人。
	 */
	private static final double FLOOR_TOLERANCE = 0.26;
	/** 归属锚的作用半径（格）：锚周围这个范围内的标记区归它声明的那条站台。 */
	private static final int ANCHOR_RADIUS = 8;
	/**
	 * 标记搜索深度（格）：从站台方块往站台里最多数这么多格找标记。
	 *
	 * <p>为什么是"沿站台轨逐格扫"而不是"枚举区块里的方块实体"：标记方块（{@link BlockCrowdMarker}）
	 * 确实是方块实体，但枚举方块实体要按区块去问（还得自己判归属）—— 实测那条路在这套映射/加载器组合下
	 * 一个标记都扫不到（报告里连"标记："那一行都不出现）。而沿轨扫方块状态是**已经在用**的读法
	 * （自动区那一段就是这么扫的），成本也低：220 格 × 12 列 × 3 层 ≈ 8 千次读，一个加载着的站台约 1 ms。
	 * 归属也是**确定**的：谁扫到的那一格，格就归谁；两条站台都扫到时按"离站台边更近"判（岛式站台）。</p>
	 */
	private static final int MARKER_WINDOW = 12;

	/**
	 * **旧链路开关**（默认关）：要不要继续跑"服务端养走动人流实体"那一半。
	 *
	 * <p>方案 A 之后走动人流也由客户端画（合同 §2：{@code FLOW} 格和站定格一样进 tile 批次），
	 * 服务端不再需要"本 tick 把走道上那几十个人各挪一格"。所以 {@link MmtrCrowdWalkers#tick}
	 * 这一句被停在这个开关后面：默认 {@code false} 时一行都不跑；{@code -Dmmtr.crowd.spawn=true}
	 * 只用于**对照排查**（"走动人流到底画成什么样"），要配合 {@code platform cap/radius} 看。</p>
	 *
	 * <p>为什么不是删掉那两个类：{@link MmtrCrowdVillagers} 还被实体 mixin（碰撞箱那一半）按
	 * {@code isCrowd} 判身份用着，走动人流的生成/驱动（{@link MmtrCrowdWalkers#reconcile}、
	 * {@code spawnFlow}）也留着 —— 参数、tag 口径、动画细节都是量出来的，删了下次要重来一遍。</p>
	 */
	private static final boolean LEGACY_SPAWNING = Boolean.parseBoolean(System.getProperty("mmtr.crowd.spawn", "false").trim());

	private static final Map<String, MmtrCrowdModule> MODULES = new HashMap<>();

	/** 服务端停机时清掉每世界状态（世界对象本身会在重开时重建）。 */
	public static void clear() {
		MODULES.clear();
		MmtrCrowdWalkers.clear();
	}

	/**
	 * 每个服务端世界 tick 调一次（挂在 {@code Init} 的 END_WORLD_TICK 上）。
	 */
	public static void tick(ServerWorld serverWorld) {
		if (serverWorld == null) {
			return;
		}
		MODULES.computeIfAbsent(dimensionOf(serverWorld), dimension -> new MmtrCrowdModule(serverWorld, dimension)).tickInternal();
	}

	/**
	 * 按**维度名**认世界，不按 {@code ServerWorld} 包装对象认。
	 *
	 * <p>为什么：映射层每次给的都是一个新的 {@code HolderBase} 包装（它没有自己的 equals/hashCode），
	 * 拿包装对象当 Map 键会每 tick 生成一个新模块，还永远查不到上一次那份状态。
	 * 维度名（{@code minecraft/overworld}）在整个服务端生命周期里是稳定且唯一的。</p>
	 */
	private static String dimensionOf(ServerWorld serverWorld) {
		return Init.getWorldId(new World(serverWorld.data));
	}

	// ------------------------------------------------------------------ 实例

	private final ServerWorld world;
	private final String dimension;
	private final Map<Long, Plan> plans = new HashMap<>();
	/** 这一拍标记的统计（报告用）：各级数量 / 两条站台抢同一格的数量。 */
	private final Map<BlockCrowdMarker.Kind, Integer> markerCounts = new LinkedHashMap<>();
	/**
	 * 上一次**推给客户端**的客量（站台 id → {@code crowdLevel}）。
	 *
	 * <p>这是"客量变了没有"的唯一账本：每 tick 拿服务端权威值比一遍，不一样才推。
	 * 记 {@code null}（没记过）与"记过但是 0"是两件事 —— 前者要推一次（客户端那一份可能是
	 * 服务端重启之前下发的旧值），见 {@link #syncCrowdLevelsToClients}。</p>
	 */
	private final Map<Long, Long> pushedLevels = new HashMap<>();
	private int sharedMarkers;
	private long lastPassMillis;
	private long lastRevision = Long.MIN_VALUE;
	private int scanCursor;

	private MmtrCrowdModule(ServerWorld world, String dimension) {
		this.world = world;
		this.dimension = dimension;
	}

	private void tickInternal() {
		mirrorConfigIfChanged();

		final long now = System.currentTimeMillis();
		/*
		 * 先把引擎的**重算版本号**读一遍（{@code AtomicLong}）：它是"客量刚被改过"的跨线程发布点 ——
		 * {@code Platform.setCrowdLevel} 先写 {@code crowdLevel}、再 {@code MmtrCrowd.bumpRevision()}，
		 * 所以读到新版本号就保证紧接着读到的客量是新值（JMM：volatile 写之前的写，对读到那个值的线程可见）。
		 * 这一句**必须在** {@link #syncCrowdLevelsToClients} 之前，否则客量要晚一拍才看得见。
		 */
		final long revision = MmtrCrowd.revision();
		final boolean revisionChanged = revision != lastRevision;

		/*
		 * 数据面（方案 A 的核心）：把客量推到客户端镜像。**每 tick** 都跑 ——
		 * 它只做"站台数"次 long 比较，代价可忽略，换来的是"platform crowd 改完 ≤1 tick 就到客户端"。
		 * 放在所有 5 秒/15 秒的闸门**之前**：客量是唯一跨端数值，它不能等几何扫描。
		 */
		syncCrowdLevelsToClients(simulator());

		/*
		 * 旧链路（走动人流实体）停在这个开关后面；默认不跑。
		 * 见 {@link #LEGACY_SPAWNING}：方案 A 之后走动人流由客户端画。
		 */
		if (LEGACY_SPAWNING) {
			MmtrCrowdWalkers.tick(world.data, dimension);
		}

		/*
		 * 为什么**不**按"有没有玩家在线"开闸：映射 API 这一层没有"列玩家"的方法，
		 * 而按玩家数开闸要拿原始 net.minecraft 类型，等于把一个纯服务端逻辑绑死在加载器上。
		 * 代价本来也只在"有东西要改"的时候才产生：核对一拍是几百次读方块 + 一次实体枚举，5 秒一次。
		 */
		if (!revisionChanged && now - lastPassMillis < PASS_INTERVAL_MILLIS) {
			return;
		}
		lastPassMillis = now;
		lastRevision = revision;

		final Simulator simulator = simulator();
		if (simulator == null) {
			return;
		}
		final List<Platform> platforms = snapshotPlatforms(simulator);
		prunePlans(platforms);
		scanNext(platforms, now, revisionChanged);
		assignMarkers(platforms);
		// 扫描顺手登记的"更早那版客流方块"在这里清掉（见 cleanUpLegacyCrowdVillagerBlocks）。
		cleanUpLegacyCrowdVillagerBlocks();

		/*
		 * 旧链路收尾（与客量无关，一拍一次）：老存档里我们铺过的客流村民实体（站定的与走动的）
		 * 现在没有任何人驱动了 —— 留着就是"停在原地的幽灵"，还会和客户端新画的雕像重影。
		 * 一次性收干净之后这一步是常数开销（枚举实体 + 空集合）。
		 */
		if (!LEGACY_SPAWNING) {
			discardLegacyCrowdEntities();
		}
	}

	private Simulator simulator() {
		final Main main = Init.getMain();
		return main == null ? null : main.getSimulator(dimension);
	}

	/** 引擎数据在另一个线程上可能正在变，取一份快照再遍历（照 MMTR 其它读引擎的地方的做法）。 */
	private List<Platform> snapshotPlatforms(Simulator simulator) {
		try {
			return new ArrayList<>(simulator.platforms);
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	// ------------------------------------------------------------------ 数据面：客量 → 客户端镜像

	/**
	 * **把变了的站台客量推到客户端**（方案 A 的跨端那一半）。
	 *
	 * <h3>为什么必须有这一步（取证结论，notes/416）</h3>
	 * <p>静态数据流对"已经下发过的对象"是**不回字段的**：客户端的 {@code DataRequest.writeExistingIds}
	 * 会把本地已有的 id 带上（{@code InitClient:457}），引擎那侧
	 * {@code DataRequest.getData → addPlatform}（{@code DataRequest.java:139-143}）对已知 id
	 * 只回 {@code id}、不回数据，客户端 {@code DataResponse.write()} 于是把它**原样留在镜像里**
	 * （{@code DataResponse.java:57-58}：不在 keep 集合里的才删、新数据只有新 id 才带字段）。
	 * 又因为客户端的拉取是**区块事件驱动**的（站着不动就不拉），所以"改客量"这件事
	 * 靠客户端主动拉是**永远不会到**的。</p>
	 *
	 * <p>所以这里由服务端推：{@code UpdateDataResponse} 里放**这条站台自己**（全部字段按当前值序列化），
	 * 客户端落地时是**原地替换镜像对象**（{@code UpdateDataResponse.write():63}），客量立刻生效。
	 * 走的包就是静态数据那条路（{@code PacketUpdateData}，与"玩家改了站台 → 广播给所有玩家"同一个包、
	 * 同一个载荷类型），不新开通道。</p>
	 *
	 * <p>触发条件：与上一次推出去的值不同（或从没推过）。客量只由 {@code Platform.setCrowdLevel} 改
	 * （引擎指令 {@code platform crowd} / 控制台滑块），所以这一比较就是完整的标脏集合。</p>
	 */
	private void syncCrowdLevelsToClients(Simulator simulator) {
		if (simulator == null) {
			return;
		}
		final List<Platform> platforms = snapshotPlatforms(simulator);
		if (platforms.isEmpty()) {
			return;
		}

		/*
		 * 只**读**账本、先不写：推送失败时账本要保持原样，下一拍才会重试
		 * （写早了就成了"推丢了但记成推过了"，客户端永远停在旧值上，而日志一声不吭）。
		 */
		final List<Platform> changed = new ArrayList<>();
		final Map<Long, Long> levelsToRecord = new LinkedHashMap<>();
		final List<String> changedText = new ArrayList<>();
		for (final Platform platform : platforms) {
			final long level = platform.getCrowdLevel();
			final Long previous = pushedLevels.get(platform.getId());
			if (previous != null && previous == level) {
				continue;
			}
			changed.add(platform);
			levelsToRecord.put(platform.getId(), level);
			changedText.add(platform.getHexId() + "=" + level + "%"
				+ (previous == null ? "（首次，客户端那一份可能是旧值）" : "（原 " + previous + "%）"));
		}

		if (changed.isEmpty()) {
			return;
		}

		/*
		 * 为什么是"自己拼一份 {platforms:[…]} 再用引擎的 reader 收成 UpdateDataResponse"，
		 * 而不是调一个 addPlatform：
		 *
		 * ★ 引擎对游戏侧是**一份预编译 jar**（game/libs/Transport-Simulation-Core-0.0.1.jar，
		 *   由 scripts/sync-engine.ps1 同步）。给 UpdateDataResponse 加一个 public 方法 = 游戏侧必须
		 *   重新同步引擎 jar **并重启服务端**，否则编译期找不到符号、运行期 NoSuchMethodError。
		 *   客量推送不该把一个"引擎 API 变更 + 重同步 + 重启"绑在验收上，所以这里只用
		 *   **旧 jar 里就已经有**的公开入口：
		 *     · Utilities.getJsonObjectFromData(platform) —— 引擎自己的序列化（字段口径不可能写错）；
		 *     · JsonReader + UpdateDataResponse(ReaderBase, Data) —— 引擎自己的反序列化，
		 *       于是交给 PacketUpdateData 的就是一个**真的 UpdateDataResponse**（不是手拼的裸 JSON）。
		 *   净效果与"引擎自己回的 UpdateDataResponse"逐字节同形。
		 *
		 * 整段包 try：这是服务端 tick 里的一段（END_WORLD_TICK），推送失败最多"画面没变"，
		 * 绝不能把 tick 打掉 —— 那会变成"改个客量把世界卡住"。
		 */
		try {
			final JsonObject payload = new JsonObject();
			final JsonArray platformArray = new JsonArray();
			changed.forEach(platform -> platformArray.add(Utilities.getJsonObjectFromData(platform)));
			payload.add("platforms", platformArray);

			final UpdateDataResponse response = new UpdateDataResponse(new JsonReader(payload), simulator);
			PacketUpdateData.sendDirectlyToClientStaticData(world, response);
		} catch (Exception e) {
			Init.LOGGER.warn("[MMTR-CROWD] 客量推送失败（下一拍会重试）：{}", e.toString());
			return;
		}

		pushedLevels.putAll(levelsToRecord);
		// 站台被删掉的那些别在账本里留垃圾（站台 id 不复用，留着只是内存）。
		if (pushedLevels.size() > platforms.size() * 2 + 64) {
			final Set<Long> alive = new HashSet<>();
			platforms.forEach(platform -> alive.add(platform.getId()));
			pushedLevels.keySet().removeIf(id -> !alive.contains(id));
		}

		/*
		 * 读数（只在真的推的时候打一条）：这是"客量到底出门没有"的唯一证据 ——
		 * 现场要说清"改了但画面没变"卡在哪一段，就得看得见这一行（推了没有、推的是哪几条、多少）。
		 */
		Init.LOGGER.info("[MMTR-CROWD] 客量已推送到客户端：{} 条站台 ｜ {}", changed.size(), String.join("，", changedText));
	}

	/**
	 * 收走老存档里我们铺的客流村民实体（站定的与走动的）。
	 *
	 * <p>判据是实体自己的命令 tag（{@link MmtrCrowdVillagers#TAG}），只认我们铺的人 ——
	 * 玩家自己放的普通村民没有这个 tag，不会被误收。为什么不逐个按站台 tag 判：
	 * 方案 A 之后"属于哪条站台"已经没有意义了，这批人一个都不该留。</p>
	 */
	private void discardLegacyCrowdEntities() {
		int removed = 0;
		for (final MmtrCrowdVillagers.Crowd crowd : MmtrCrowdVillagers.collect(world.data)) {
			MmtrCrowdVillagers.discard(crowd.entity);
			removed++;
		}
		if (removed > 0) {
			Init.LOGGER.info("[MMTR-CROWD] 收走旧链路的客流村民实体 {} 只（方案 A 之后这些人由客户端画，不再用实体）", removed);
		}
	}

	/** 站台没了（拆站台轨 / 删站台）⇒ 把它的几何账、客量账一起划掉。 */
	private void prunePlans(List<Platform> platforms) {
		final Set<Long> alive = new HashSet<>();
		for (final Platform platform : platforms) {
			alive.add(platform.getId());
		}
		final List<Long> removed = new ArrayList<>();
		for (final Long id : plans.keySet()) {
			if (!alive.contains(id)) {
				removed.add(id);
			}
		}
		removed.forEach(id -> {
			plans.remove(id);
			pushedLevels.remove(id);
		});
	}

	// ------------------------------------------------------------------ 几何扫描

	/**
	 * 把需要重扫的站台扫一遍（几何变了 / 从没扫过 / **上次扫的时候区块没加载** / 太久没扫）。
	 *
	 * <p>为什么要按**时间预算**而不是"每拍扫 N 个"：站台上的人只有在区块加载后才找得到站台方块，
	 * 而"哪些区块加载了"随时在变（玩家走动、forceload）。实测踩过：服务端刚起时上水村的区块还没加载，
	 * 那次扫描把 {@code 候选=0} 记进缓存；等区块加载后再看，同一个车站的另一条站台（几何一模一样）
	 * 有 207 个候选，而这一条要等 60 秒的重扫定时器才恢复。现在扫描摊在一个 8 ms 的预算里，
	 * 且"上次没看全"的站台每拍都排在被重扫的第一位 —— 区块一加载，下一拍就补齐。</p>
	 */
	private void scanNext(List<Platform> platforms, long now, boolean force) {
		if (platforms.isEmpty()) {
			return;
		}
		final long deadline = System.currentTimeMillis() + SCAN_BUDGET_MILLIS;
		int index = scanCursor;
		for (int examined = 0; examined < platforms.size(); examined++) {
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
			final Platform platform = platforms.get(index % platforms.size());
			index++;
			final String key = geometryKey(platform);
			final Plan existing = plans.get(platform.getId());
			if (force || existing == null || !key.equals(existing.geometryKey) || !existing.complete
				|| now - existing.scannedMillis >= RESCAN_INTERVAL_MILLIS) {
				plans.put(platform.getId(), scan(platform, key, now));
			}
		}
		scanCursor = index % platforms.size();
	}

	/**
	 * 站台几何的指纹：轨 hex + 两个端点。
	 *
	 * <p>为什么不用"站台 id 没变就复用"：站台轨被拆了重铺（端点相同但轨换了）或站台被整体挪走时，
	 * id 不变而位置变了 —— 那是最需要重扫的情况。</p>
	 */
	private static String geometryKey(Platform platform) {
		final Rail rail = platform.mmtrGraphRail();
		final Position[] ends = platform.mmtrOrderedPositions();
		return (rail == null ? "-" : rail.getHexId())
			+ "|" + ends[0].getX() + "," + ends[0].getY() + "," + ends[0].getZ()
			+ "|" + ends[1].getX() + "," + ends[1].getY() + "," + ends[1].getZ();
	}

	/**
	 * 扫一条站台的**自动区**：站台方块后面那 {@link MmtrCrowd#autoRows()} 格。
	 *
	 * <p>用户口径（2026-10）："站台方块本身不能站人，所以外扩二格即可"。现场量出来的站台剖面是
	 * 「轨道 → 站台方块（贴轨的安全线块）→ 站台面地面」；人站的是**地面上面那一格**，
	 * 所以候选格 = 站台方块后面第 1 格、第 2 格的地面之上。</p>
	 */
	private Plan scan(Platform platform, String key, long now) {
		final Plan plan = new Plan(platform.getId(), key, now);
		final Rail rail = platform.mmtrGraphRail();
		if (rail == null) {
			// 站台还没有对应的图轨（刚建、或在别的维度）：没有可走的路径，等下一轮。
			return plan;
		}
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return plan;
		}
		final Map<BlockPos, Spot> spots = new LinkedHashMap<>();
		final Set<BlockPos> legacyBlocks = new LinkedHashSet<>();
		boolean complete = true;
		final int autoRows = MmtrCrowd.autoRows();
		for (double distance = 0; distance <= length; distance += SAMPLE_STEP) {
			final Vector point = rail.railMath.getPosition(distance, false);
			final Vector ahead = rail.railMath.getPosition(Math.min(length, distance + 0.5), false);
			if (!isLoaded(Init.newBlockPos(point.x(), point.y(), point.z()))) {
				// 这一格所在的区块没加载：这次扫描看不到它，整份几何只能算"没看全"。
				complete = false;
				continue;
			}
			final double tangentX = ahead.x() - point.x();
			final double tangentZ = ahead.z() - point.z();
			final double tangentLength = Math.hypot(tangentX, tangentZ);
			if (tangentLength < 1.0E-6) {
				continue;
			}
			// 轨的切向转 90° = 站台方向（谁在左谁在右由下面的两侧搜索一起试）
			final double normalX = -tangentZ / tangentLength;
			final double normalZ = tangentX / tangentLength;
			final Edge edge = findEdge(platform, point, normalX, normalZ, legacyBlocks);
			if (edge == null) {
				continue;
			}
			plan.edgeBlocks.add(edge.blockPos);
			// 顺手把这一段里的标记方块扫出来（同一趟法线、同一个地面判断，见 sweepMarkers）
			sweepMarkers(plan, point, normalX, normalZ, edge);
			if (autoRows > 0) {
				addAutoCells(plan, spots, point, normalX, normalZ, edge, autoRows);
			}
		}
		plan.autoSpots = new ArrayList<>(spots.values());
		plan.legacyBlocks = new ArrayList<>(legacyBlocks);
		plan.complete = complete;
		return plan;
	}

	/** 在轨道两侧从近到远找第一块站台方块；两侧都有（岛式站台）时按位置哈希定一侧。 */
	private Edge findEdge(Platform platform, Vector point, double normalX, double normalZ, Set<BlockPos> legacyBlocks) {
		final int railY = (int) Math.floor(point.y());
		for (int k = 1; k <= SIDE_SEARCH_BLOCKS; k++) {
			final int positiveX = (int) Math.floor(point.x() + normalX * k);
			final int positiveZ = (int) Math.floor(point.z() + normalZ * k);
			final int negativeX = (int) Math.floor(point.x() - normalX * k);
			final int negativeZ = (int) Math.floor(point.z() - normalZ * k);

			final Edge positive = probeColumn(positiveX, railY, positiveZ, 1, k, legacyBlocks);
			final Edge negative = probeColumn(negativeX, railY, negativeZ, -1, k, legacyBlocks);
			if (positive != null && negative != null) {
				return hash01(platform.getId(), positive.blockPos) < 0.5 ? positive : negative;
			}
			if (positive != null) {
				return positive;
			}
			if (negative != null) {
				return negative;
			}
		}
		return null;
	}

	/**
	 * 一根柱子上（几个高度里）找站台方块，顺带登记**遗留的客流方块**。
	 *
	 * <p>遗留方块是上一版"客流=铺方块"方案留下的（换了实体之后没人认它们）：扫到就登记，当拍清掉，
	 * 不留下"上一版的幽灵"。</p>
	 */
	private Edge probeColumn(int x, int railY, int z, int sign, int lateral, Set<BlockPos> legacyBlocks) {
		for (final int yOffset : Y_OFFSETS) {
			final BlockPos below = Init.newBlockPos(x, railY + yOffset, z);
			if (!isLoaded(below)) {
				continue;
			}
			final BlockPos villagerPos = below.up();
			if (!isLoaded(villagerPos)) {
				continue;
			}
			if (world.getBlockState(below).getBlock().data instanceof PlatformHelper) {
				if (world.getBlockState(villagerPos).getBlock().data instanceof BlockCrowdVillager) {
					legacyBlocks.add(villagerPos);
				}
				return new Edge(below, sign, lateral);
			}
			// 上一步方块方案留下的客流方块：登记待清。
			if (world.getBlockState(villagerPos).getBlock().data instanceof BlockCrowdVillager) {
				legacyBlocks.add(villagerPos);
			}
		}
		return null;
	}

	/**
	 * 站台方块后面第 1..{@code rows} 格：每格找"和站台面同一层、且这一格是空的"地面，站到它上面。
	 *
	 * <p>为什么以**第一格的实际地面**作后面的基准：站台方块可能比后面的地面高一点点
	 * （内缩站台 13/16），用它的顶面当基准会让整排都被筛掉；而第一格地面才是人真正站的那层。</p>
	 */
	private void addAutoCells(Plan plan, Map<BlockPos, Spot> spots, Vector point, double normalX, double normalZ, Edge edge, int rows) {
		final int railY = edge.blockPos.getY();
		double referenceTop = floorTop(edge.blockPos);
		for (int extra = 1; extra <= rows; extra++) {
			final int k = edge.lateral + extra;
			final int x = (int) Math.floor(point.x() + normalX * edge.sign * k);
			final int z = (int) Math.floor(point.z() + normalZ * edge.sign * k);
			final Standing standing = findStandingCell(x, z, railY, referenceTop);
			if (standing == null) {
				continue;
			}
			referenceTop = standing.floorTop;
			final Direction facing = facingOf((int) Math.floor(point.x()) - x, (int) Math.floor(point.z()) - z);
			final int typeIndex = MmtrCrowdVillagers.typeIndexFor(hash01(plan.platformId * 31L + 7L, standing.cell));
			spots.putIfAbsent(standing.cell, new Spot(standing.cell, facing, typeIndex, Grade.GRADE_1));
		}
	}

	/**
	 * 在某一根柱子上找"能站人的那一格"：地面顶面要和 {@code referenceTop} 同一层（容差
	 * {@link #FLOOR_TOLERANCE}），且那一格本身没有碰撞箱。
	 */
	private Standing findStandingCell(int x, int z, int aroundY, double referenceTop) {
		for (int offset = 1; offset >= -1; offset--) {
			final BlockPos floor = Init.newBlockPos(x, aroundY + offset, z);
			final BlockPos cell = floor.up();
			if (!isLoaded(floor) || !isLoaded(cell)) {
				continue;
			}
			final double top = floorTop(floor);
			if (top < floor.getY() + 0.4) {
				// 这一层没有像样的地面（空气、草、火把）
				continue;
			}
			if (Math.abs(top - referenceTop) > FLOOR_TOLERANCE) {
				// 不是同一层站台面（台阶、半砖、掉到轨道层）
				continue;
			}
			if (!isFree(cell)) {
				// 这一格被占了（墙、长椅、柱子、售货机）
				continue;
			}
			return new Standing(cell, top);
		}
		return null;
	}

	/** 这一格地面的顶面高度（方块自己的 y + 碰撞箱最高点）。 */
	private double floorTop(BlockPos pos) {
		final BlockState state = world.getBlockState(pos);
		// 走原始 MC 的 VoxelShape：映射层的 VoxelShape 是包装类，没有 getMax(原始 Axis) 这一层。
		final net.minecraft.util.shape.VoxelShape shape = state.data.getCollisionShape(world.data, pos.data);
		return pos.getY() + (shape.isEmpty() ? 0 : shape.getMax(net.minecraft.util.math.Direction.Axis.Y));
	}

	/** 这一格是不是空的（没有碰撞箱就算空 —— 我们自己那些不挡路的标记方块也算空，人就站在格子里）。 */
	private boolean isFree(BlockPos pos) {
		return world.getBlockState(pos).data.getCollisionShape(world.data, pos.data).isEmpty();
	}

	private static Direction facingOf(int dx, int dz) {
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx >= 0 ? Direction.EAST : Direction.WEST;
		}
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	// ------------------------------------------------------------------ 标记 → 归属 → 站位
	/**
	 * 把各站台扫到的标记汇总成"每格归谁"，再写回各站台的 {@link Plan#markerSpots}。
	 *
	 * <p>三层口径：</p>
	 * <ol>
	 *   <li><b>谁扫到归谁</b>：扫描时记着这一格离站台边几格（{@code lateral}）；</li>
	 *   <li><b>两条站台都扫到</b>（岛式站台两面都往中间那片站台面里扫）：按 {@code lateral} 小的判 ——
	 *       离谁近归谁，报告里单独计数（"抢格"）；</li>
	 *   <li><b>归属锚覆盖</b>：锚周围 {@link #ANCHOR_RADIUS} 格内的标记（连同连通的整片）改成锚所在的那条站台
	 *       —— 岛式站台"中间那一片到底算哪条站台的客流"就靠它拍板（用户口径："放在站台方块上以确认归属站台的方块"）。</li>
	 * </ol>
	 */
	private void assignMarkers(List<Platform> platforms) {
		markerCounts.clear();
		sharedMarkers = 0;
		for (final BlockCrowdMarker.Kind kind : BlockCrowdMarker.Kind.values()) {
			markerCounts.put(kind, 0);
		}

		// 1) 汇总：每格留"扫到它的那几条站台里 lateral 最小"的那一条。
		final Map<BlockPos, Claim> claimed = new LinkedHashMap<>();
		for (final Platform platform : platforms) {
			final Plan plan = plans.get(platform.getId());
			if (plan == null) {
				continue;
			}
			// 每拍重算：标记被敲掉、客量改了、站台面被改，下一拍就反映出来。
			plan.markerSpots = new ArrayList<>();
			for (final SweptMarker marker : plan.sweptMarkers.values()) {
				final Claim existing = claimed.get(marker.cell());
				if (existing == null) {
					claimed.put(marker.cell(), new Claim(marker, platform.getId()));
				} else {
					sharedMarkers++;
					if (marker.lateral() < existing.swept().lateral()) {
						claimed.put(marker.cell(), new Claim(marker, platform.getId()));
					}
				}
			}
		}

		// 2) 归属锚：把它周围那一片（连同连通的邻居）改判到锚所在的那条站台。
		//    为什么要"连同连通的邻居"：锚一般放在站台方块上（贴轨道那一排），标记铺在站台面里，
		//    两者不一定相邻；只要有一格落在半径内，整片区域就都跟着锚走。
		for (final Claim claim : new ArrayList<>(claimed.values())) {
			if (claim.swept().kind() == BlockCrowdMarker.Kind.ANCHOR) {
				reassignRegion(claim.swept().cell(), claim.platformId(), claimed);
			}
		}

		// 3) 写回：锚只定归属、不产生站位。
		for (final Map.Entry<BlockPos, Claim> entry : claimed.entrySet()) {
			final Claim claim = entry.getValue();
			final BlockCrowdMarker.Kind kind = claim.swept().kind();
			markerCounts.merge(kind, 1, Integer::sum);
			final Plan plan = plans.get(claim.platformId());
			if (plan == null || kind == BlockCrowdMarker.Kind.ANCHOR) {
				continue;
			}
			plan.markerSpots.add(new Spot(entry.getKey(), claim.swept().facing(),
				MmtrCrowdVillagers.typeIndexFor(hash01(claim.platformId() * 31L + 7L, entry.getKey())), gradeOf(kind)));
		}
	}

	/** 一片连通的标记改判到某条站台：从锚附近那些格出发，沿 6 邻域走遍整片。 */
	private void reassignRegion(BlockPos anchorCell, long owner, Map<BlockPos, Claim> claimed) {
		final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		final Set<BlockPos> visited = new HashSet<>();
		for (final BlockPos cell : claimed.keySet()) {
			if (Math.abs(cell.getX() - anchorCell.getX()) <= ANCHOR_RADIUS && Math.abs(cell.getZ() - anchorCell.getZ()) <= ANCHOR_RADIUS
				&& Math.abs(cell.getY() - anchorCell.getY()) <= 2 && visited.add(cell)) {
				queue.add(cell);
			}
		}
		while (!queue.isEmpty()) {
			final BlockPos cell = queue.poll();
			final Claim claim = claimed.get(cell);
			if (claim != null && claim.platformId() != owner) {
				claimed.put(cell, new Claim(claim.swept(), owner));
			}
			for (final BlockPos neighbour : new BlockPos[]{cell.north(), cell.south(), cell.east(), cell.west(), cell.up(), cell.down()}) {
				if (claimed.containsKey(neighbour) && visited.add(neighbour)) {
					queue.add(neighbour);
				}
			}
		}
	}

	/**
	 * 沿站台轨**扫标记方块**：站台方块那一排（归属锚放这儿）+ 往站台里 {@link #MARKER_WINDOW} 格。
	 *
	 * <p>与自动区共用同一条法线：从站台方块往站台里数，**地面高度一变就停**（掉到轨道层、台阶、断口）——
	 * 于是"两条站台之间隔着轨道"时，往轨道那侧扫会自然停住，不会把邻台的人流区抢过来。</p>
	 */
	private void sweepMarkers(Plan plan, Vector point, double normalX, double normalZ, Edge edge) {
		final int platformY = edge.blockPos.getY();
		final double referenceTop = floorTop(edge.blockPos);
		// 站台方块那一排：归属锚就放在它上面（往上一层、上两层都试，容忍半砖/内缩站台）
		for (int offset = 1; offset <= 2; offset++) {
			recordMarker(plan, Init.newBlockPos(edge.blockPos.getX(), platformY + offset, edge.blockPos.getZ()), 0, point);
		}
		for (int extra = 1; extra <= MARKER_WINDOW; extra++) {
			final int lateral = edge.lateral + extra;
			final int x = (int) Math.floor(point.x() + normalX * edge.sign * lateral);
			final int z = (int) Math.floor(point.z() + normalZ * edge.sign * lateral);
			final BlockPos floor = Init.newBlockPos(x, platformY, z);
			if (!isLoaded(floor)) {
				continue;
			}
			final double top = floorTop(floor);
			if (top < platformY + 0.4 || Math.abs(top - referenceTop) > FLOOR_TOLERANCE) {
				// 站台面到这儿断了：再往里不是这条站台的站台面
				break;
			}
			// 人站的那一格是 platformY+1；上下各留一格余量（放歪一格、放在半砖上都能认出来）
			for (int offset = 0; offset <= 2; offset++) {
				recordMarker(plan, Init.newBlockPos(x, platformY + offset, z), extra, point);
			}
		}
	}

	/** 这一格是标记方块就登记下来（同一格只留第一次 = 离站台边最近的那次）。 */
	private void recordMarker(Plan plan, BlockPos pos, int lateral, Vector point) {
		if (!isLoaded(pos) || plan.sweptMarkers.containsKey(pos)) {
			return;
		}
		final BlockCrowdMarker.Kind kind = BlockCrowdMarker.kindOf(world.getBlockState(pos));
		if (kind == null) {
			return;
		}
		// 流动标记用放方块时的朝向当走道轴向；其余三种朝轨道（等车的人都看着车来的方向）。
		final Direction facing = kind == BlockCrowdMarker.Kind.FLOW
			? facingOfMarker(pos)
			: facingOf((int) Math.floor(point.x()) - pos.getX(), (int) Math.floor(point.z()) - pos.getZ());
		plan.sweptMarkers.put(pos, new SweptMarker(pos, kind, lateral, facing));
	}

	private static Grade gradeOf(BlockCrowdMarker.Kind kind) {
		switch (kind) {
			case GRADE_2:
				return Grade.GRADE_2;
			case FLOW:
				return Grade.FLOW;
			default:
				return Grade.GRADE_1;
		}
	}

	/** 流动标记的轴向：放方块时玩家的朝向（{@code /setblock … [facing=…]} 也能指定）。 */
	private Direction facingOfMarker(BlockPos pos) {
		final Direction facing = IBlock.getStatePropertySafe(world.getBlockState(pos), BlockCrowdMarker.FACING);
		return facing == null || facing.getAxis() == Axis.Y ? Direction.NORTH : facing;
	}

	// ------------------------------------------------------------------ 旧链路：这些段已删

	/*
	 * 这里原来是"客量 → 目标集合 → 生成/回收"整段（方案 A 之前的口径）：
	 *
	 *   select()      按 客量% × 单站台上限 选出这一拍"该有谁"（一级:二级 7:3、流动 ≤走道格数/10、
	 *                 逐格玩家半径闸门、自动区兜底），返回 Selection；
	 *   pick()        按位置哈希排序取前 N 个（阈值筛会变成二项分布，人数不受控）；
	 *   toFlowCells()/flowAxes()  走道格的轴向（形状优先、朝向兜底）；
	 *   hasPlayerNear()           逐格玩家半径闸门；
	 *   enqueueDiff() 把目标格与世界的差做成方块写入队列（occupied=true/false）；
	 *   drainQueues()/applySeatOp() 逐格 world.setBlockState(... withOccupied ...)；
	 *   hasStatue()/isLegacySeatCell()/isCrowdCell() 读回"这一格现在到底有没有人"。
	 *
	 * 整段随方案 A 删除（notes/416 §2/§3）：客量不再折算成"哪些格子站人"，
	 * 客户端只拿一个 0–100 的数、自己决定画几批（见 {@code MmtrCrowdStatues}）。
	 * 服务端因此**一次方块状态都不写**：0 区块网格重建、0 逐格记账。
	 *
	 * 留下的只有两件与客量无关的事：几何/标记账（scan/assignMarkers，喂 report 与 dump）
	 * 与旧版本遗留物的清理（下面的 cleanUpLegacyCrowdVillagerBlocks、discardLegacyCrowdEntities）。
	 */

	/**
	 * 清掉**更早那版**"客流 = 铺方块"方案留下的 {@code crowd_villager} 方块。
	 *
	 * <p>与客量无关，也不参与方案 A 的任何口径：那批方块是**实心**的，留在站台面上就是"看不见的墙"，
	 * 所以顺手清一遍。清单来自几何扫描（{@code probeColumn} 里"站台方块上方 / 站台面上是
	 * {@code crowd_villager}"就登记进 {@code Plan.legacyBlocks}），清完置 null ——
	 * 一个站台只清一次，之后这一步是常数开销。</p>
	 *
	 * <p>预算用完就提前收手（剩下的下一拍接着清）：这是**世界改动**，不能把 8 ms 的扫描预算吃穿。
	 * 提前收手的那个站台列表原样留着，下一拍重读一遍（已经清掉的格读出来不是那个方块，直接跳过）。</p>
	 */
	private void cleanUpLegacyCrowdVillagerBlocks() {
		final long deadline = System.currentTimeMillis() + APPLY_BUDGET_MILLIS;
		int ops = 0;
		for (final Plan plan : plans.values()) {
			if (plan.legacyBlocks == null) {
				continue;
			}
			for (final BlockPos pos : plan.legacyBlocks) {
				if (ops >= MAX_OPS_PER_TICK || System.currentTimeMillis() >= deadline) {
					return;
				}
				ops++;
				if (isLoaded(pos) && world.getBlockState(pos).getBlock().data instanceof BlockCrowdVillager) {
					world.setBlockState(pos, Blocks.getAirMapped().getDefaultState());
				}
			}
			plan.legacyBlocks = null;
		}
	}

	// ------------------------------------------------------------------ 人数闸门的持久化

	/** 闸门镜像文件名（放在世界存档的 mtr 目录下，与引擎的 mmtr-*.json 同级）。 */
	private static final String CONFIG_FILE = "mmtr-crowd.properties";

	private static Path configPath;
	private static int mirroredCap = -1;
	private static int mirroredRadius = -1;
	private static int mirroredRows = -1;
	private static boolean mirroredForce;

	/**
	 * 服务端启动时调一次：把上次调好的闸门读回来。
	 *
	 * <p>为什么需要它：闸门的权威值在引擎侧（操作员要能从中控指令栏改、改完立刻生效），
	 * 而引擎的静态值在服务端重启后会回默认。所以游戏侧把**当前值镜像一份**到存档目录里，
	 * 启动时读回来再灌回引擎 —— 单一权威、又活得比进程长。</p>
	 */
	public static void loadConfig(Path mtrRoot) {
		configPath = mtrRoot == null ? null : mtrRoot.resolve(CONFIG_FILE);
		if (configPath == null || !Files.isRegularFile(configPath)) {
			return;
		}
		final Properties properties = new Properties();
		try (InputStream inputStream = Files.newInputStream(configPath)) {
			properties.load(inputStream);
			MmtrCrowd.setMaxPerPlatform(Integer.parseInt(properties.getProperty("maxPerPlatform", String.valueOf(MmtrCrowd.DEFAULT_MAX_PER_PLATFORM))));
			MmtrCrowd.setPlayerRadius(Integer.parseInt(properties.getProperty("playerRadius", String.valueOf(MmtrCrowd.DEFAULT_PLAYER_RADIUS))));
			MmtrCrowd.setAutoRows(Integer.parseInt(properties.getProperty("autoRows", String.valueOf(MmtrCrowd.DEFAULT_AUTO_ROWS))));
			MmtrCrowd.setForcePopulate(Boolean.parseBoolean(properties.getProperty("force", "false")));
			mirroredCap = MmtrCrowd.maxPerPlatform();
			mirroredRadius = MmtrCrowd.playerRadius();
			mirroredRows = MmtrCrowd.autoRows();
			mirroredForce = MmtrCrowd.forcePopulate();
			Init.LOGGER.info("[crowd] 闸门读回：单站台上限 {} 人、玩家半径 {} 格、自动区 {} 格、勘测模式 {}（{}）",
				mirroredCap, mirroredRadius, mirroredRows, mirroredForce ? "开" : "关", configPath);
		} catch (Exception e) {
			Init.LOGGER.warn("[crowd] 读不回人数闸门 {}：{}", configPath, e);
		}
	}

	/** 引擎侧的闸门被改过就写回镜像（每拍比一次几个数，代价可忽略）。 */
	private static void mirrorConfigIfChanged() {
		if (configPath == null) {
			return;
		}
		final int cap = MmtrCrowd.maxPerPlatform();
		final int radius = MmtrCrowd.playerRadius();
		final int rows = MmtrCrowd.autoRows();
		final boolean force = MmtrCrowd.forcePopulate();
		if (cap == mirroredCap && radius == mirroredRadius && rows == mirroredRows && force == mirroredForce) {
			return;
		}
		mirroredCap = cap;
		mirroredRadius = radius;
		mirroredRows = rows;
		mirroredForce = force;
		final Properties properties = new Properties();
		properties.setProperty("maxPerPlatform", String.valueOf(cap));
		properties.setProperty("playerRadius", String.valueOf(radius));
		properties.setProperty("autoRows", String.valueOf(rows));
		properties.setProperty("force", String.valueOf(force));
		try {
			Files.createDirectories(configPath.getParent());
			try (OutputStream outputStream = Files.newOutputStream(configPath)) {
				properties.store(outputStream, "MMTR platform crowd gates (platform cap / player radius / auto rows / survey force)");
			}
			Init.LOGGER.info("[crowd] 人数闸门已存：单站台上限 {} 人、玩家半径 {} 格、自动区 {} 格、勘测模式 {}", cap, radius, rows, force ? "开" : "关");
		} catch (Exception e) {
			Init.LOGGER.warn("[crowd] 写不回人数闸门 {}：{}", configPath, e);
		}
	}

	// ------------------------------------------------------------------ 核对读数

	/**
	 * {@code crowd scan}（引擎指令 {@code crowd scan} 下发）的读数：逐站台报
	 * **客量 / 标记格数 / 按客量客户端大约会画几个人**。
	 *
	 * <p>方案 A 之后不再有"实有"这一栏 —— 服务端**不铺任何逐格状态**了，问"实有几个人"只有客户端答得出
	 * （{@code [MMTR-CROWD] 本帧画 k/K 批}）。这一份报告回答的是服务端这一侧能负全责的三件事：</p>
	 * <ul>
	 *   <li>{@code 客量} —— 权威值 {@code crowdLevel}（0–100%）；</li>
	 *   <li>{@code 扫到标记} —— 这条站台扫到的标记格（按一级/二级/流动/归属锚分开；@N 口径见 {@code dump}）；</li>
	 *   <li>{@code 归本平台站位} —— 汇总/锚改判之后真正算给这条站台的站位格（锚只定归属、不产生站位）；</li>
	 *   <li>{@code 客户端约画} —— 按合同 §2 的口径折算：{@code round(站位格数 × 客量 / 100)}。
	 *       用来和客户端那一行读数**对账**：两个数差得多 ⇒ 要么客户端镜像的客量没跟上
	 *       （看有没有 {@code [MMTR-CROWD] 客量已推送到客户端} 那一行），
	 *       要么渲染端认到的标记格和这里不一样（那就是归属口径的问题，不是画错）。</li>
	 * </ul>
	 * <p>{@code 遗留实体} 单独报一条：老存档里我们铺过的客流村民（方案 A 之前那套）由
	 * {@link #discardLegacyCrowdEntities} 逐拍收走，正常应该恒为 0。</p>
	 */
	public static List<String> report(ServerWorld serverWorld, Simulator simulator) {
		final List<String> lines = new ArrayList<>();
		final MmtrCrowdModule module = MODULES.get(dimensionOf(serverWorld));
		if (module == null) {
			lines.add("[crowd] 这个维度还没跑过客流模块（服务端每 tick 会跑一次，稍后再试）");
			return lines;
		}
		final int leftovers = MmtrCrowdVillagers.collect(serverWorld.data).size();
		final List<Platform> platforms = module.snapshotPlatforms(simulator);
		int totalStandingCells = 0;
		int totalExpectedPeople = 0;
		int scanned = 0;
		int unscanned = 0;
		for (final Platform platform : platforms) {
			final String stationName = platform.area == null ? "（未归站）" : Utilities.formatName(platform.area.getName());
			final long level = platform.getCrowdLevel();
			final Plan plan = module.plans.get(platform.getId());
			if (plan == null) {
				unscanned++;
				lines.add(String.format(Locale.ENGLISH, "[crowd] %s 站名=%s 站台=%s 长=%d格 客量=%d%% —— 还没扫（等下一拍）",
					platform.getHexId(), stationName, platform.getName(), approximateLengthBlocks(platform), level));
				continue;
			}

			// 扫到的标记（按种类；这是"我放的标记认到没有"的答案）
			final Map<BlockCrowdMarker.Kind, Integer> swept = new LinkedHashMap<>();
			plan.sweptMarkers.values().forEach(marker -> swept.merge(marker.kind(), 1, Integer::sum));
			// 归本平台的站位（锚除外）：客户端画人的候选格就是这些（渲染端自己扫世界，口径见合同 §2）
			final Map<Grade, Integer> standingByGrade = countByGrade(plan.markerSpots);
			final int standingCells = plan.markerSpots.size();
			final int expectedPeople = level <= 0 ? 0 : (int) Math.round(standingCells * level / 100.0);

			scanned++;
			totalStandingCells += standingCells;
			totalExpectedPeople += expectedPeople;
			lines.add(String.format(Locale.ENGLISH,
				"[crowd] %s 站名=%s 站台=%s 长=%d格 客量=%d%% ｜ 扫到标记 一级=%d 二级=%d 流动=%d 锚=%d ｜ 归本平台站位=%d 格（一级=%d 二级=%d 流动=%d）｜ 客户端约画=%d 人 ｜ 自动区候选=%d 格",
				platform.getHexId(), stationName, platform.getName(), approximateLengthBlocks(platform), level,
				swept.getOrDefault(BlockCrowdMarker.Kind.GRADE_1, 0),
				swept.getOrDefault(BlockCrowdMarker.Kind.GRADE_2, 0),
				swept.getOrDefault(BlockCrowdMarker.Kind.FLOW, 0),
				swept.getOrDefault(BlockCrowdMarker.Kind.ANCHOR, 0),
				standingCells,
				standingByGrade.getOrDefault(Grade.GRADE_1, 0),
				standingByGrade.getOrDefault(Grade.GRADE_2, 0),
				standingByGrade.getOrDefault(Grade.FLOW, 0),
				expectedPeople, plan.autoSpots.size()));
		}
		lines.add(String.format(Locale.ENGLISH,
			"[crowd] 合计：已扫站台 %d / 未扫 %d ｜ 归本平台站位 %d 格 ｜ 按现在客量客户端约画 %d 人 ｜ 遗留实体 %d 只（老存档的村民实体，下一拍收走）",
			scanned, unscanned, totalStandingCells, totalExpectedPeople, leftovers));
		lines.add(String.format(Locale.ENGLISH,
			"[crowd] 配置（读自 %s）：单站台上限 %d 人 ｜ 玩家半径 %d 格%s ｜ 自动区 %d 格 ｜ 旧链路村民开关=%s",
			CONFIG_FILE, MmtrCrowd.maxPerPlatform(), MmtrCrowd.playerRadius(),
			MmtrCrowd.forcePopulate() ? "（勘测模式 on）" : "",
			MmtrCrowd.autoRows(), LEGACY_SPAWNING ? "on（-Dmmtr.crowd.spawn=true）" : "off（默认）"));
		lines.add(String.format(Locale.ENGLISH,
			"[crowd] 标记总数（全维度，去重后）：一级 %d 个 ｜ 二级 %d 个 ｜ 流动 %d 个 ｜ 归属锚 %d 个"
				+ "（扫描深度：站台方块起 %d 格；两条站台抢同一格 %d 次，按离站台边近的判）",
			module.markerCounts.getOrDefault(BlockCrowdMarker.Kind.GRADE_1, 0),
			module.markerCounts.getOrDefault(BlockCrowdMarker.Kind.GRADE_2, 0),
			module.markerCounts.getOrDefault(BlockCrowdMarker.Kind.FLOW, 0),
			module.markerCounts.getOrDefault(BlockCrowdMarker.Kind.ANCHOR, 0),
			MARKER_WINDOW, module.sharedMarkers));
		lines.add("[crowd] 口径（方案 A，notes/416）：权威值只有 Platform.crowdLevel（0–100%），"
			+ "服务端把它推给客户端镜像（客量一改就打 [MMTR-CROWD] 客量已推送到客户端），"
			+ "客户端按 tile 批数决定画几个人 —— 服务端**不写任何逐格 occupied**，"
			+ "所以这份报告里没有「实有」：那个数只有客户端 [MMTR-CROWD] 那一行答得出。"
			+ "单站台上限/玩家半径/勘测模式这三条闸门现在**不再影响画面**（保留供旧链路与配置文件兼容）。");
		lines.add("[crowd] 放标记：手持「客流标记·一级/二级/流动」铺在站台面上（人站的那一格），"
			+ "「客流归属锚」放在站台方块上（岛式站台用它确认中间那片算哪条站台）；"
			+ "手里拿着任意一种标记方块才看得见标记。放完 15 秒内生效（急的话 platform refresh）；"
			+ "标记认到之后，人数完全由客量决定（platform crowd <hex> 0/50/100）。");
		return lines;
	}

	/**
	 * {@code crowd dump <站台hex|id>}：把**这一条站台**这一拍认到的东西**逐格**打出来。
	 *
	 * <p>为什么需要它：{@code crowd scan} 只给总数，而玩家真正会问的问题是逐格的 ——
	 * "我放的标记它到底认到哪些格"、"这些格算给哪条站台了"、"哪几格会站人"。
	 * 这条指令把这几样一次打全：站台方块那一排的范围、扫到的标记逐格（带离站台边几格）、
	 * 归本平台的站位格逐格；另外附上**旧链路**的走道/走动人流现场
	 * （{@link #LEGACY_SPAWNING} 默认关，所以那一段正常恒为 0 只）。</p>
	 *
	 * <p><b>方案 A 之后这条指令不再读世界里的 {@code occupied}</b>：那一位服务端已经不写了，
	 * 剩下的老值没有诊断意义（"这一格站不站人"现在由客量决定，只看客户端那一行读数）。</p>
	 */
	public static List<String> dump(ServerWorld serverWorld, Simulator simulator, String platformToken) {
		final List<String> lines = new ArrayList<>();
		final MmtrCrowdModule module = MODULES.get(dimensionOf(serverWorld));
		if (module == null) {
			lines.add("[crowd-dump] 这个维度还没跑过客流模块（服务端每 tick 会跑一次，稍后再试）");
			return lines;
		}
		Platform platform = null;
		for (final Platform candidate : module.snapshotPlatforms(simulator)) {
			if (candidate.getHexId().equalsIgnoreCase(platformToken) || String.valueOf(candidate.getId()).equals(platformToken)) {
				platform = candidate;
				break;
			}
		}
		if (platform == null) {
			lines.add("[crowd-dump] 找不到站台「" + platformToken + "」（用 platform list 取 16 位 hex）");
			return lines;
		}

		final String stationName = platform.area == null ? "（未归站）" : Utilities.formatName(platform.area.getName());
		lines.add(String.format(Locale.ENGLISH, "[crowd-dump] %s 站名=%s 站台=%s 客量=%d%%",
			platform.getHexId(), stationName, platform.getName(), platform.getEffectiveCrowdLevel()));
		final Plan plan = module.plans.get(platform.getId());
		if (plan == null) {
			lines.add("[crowd-dump] 还没扫过（等下一拍）");
			return lines;
		}

		// 1) 站台方块那一排：范围打出来，玩家一眼能看出"我摆的位置在不在它后面"
		if (plan.edgeBlocks.isEmpty()) {
			lines.add("[crowd-dump] 站台方块那一排：**一格都没找到**（这一条站台的区块加载了吗？站台轨还在吗？）");
		} else {
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxZ = Integer.MIN_VALUE;
			int y = 0;
			for (final BlockPos pos : plan.edgeBlocks) {
				minX = Math.min(minX, pos.getX());
				maxX = Math.max(maxX, pos.getX());
				minZ = Math.min(minZ, pos.getZ());
				maxZ = Math.max(maxZ, pos.getZ());
				y = pos.getY();
			}
			lines.add(String.format(Locale.ENGLISH, "[crowd-dump] 站台方块 %d 格：x %d..%d，z %d..%d，y=%d（人站它**后面**那几格）",
				plan.edgeBlocks.size(), minX, maxX, minZ, maxZ, y));
		}
		lines.add("[crowd-dump] 自动区候选 " + plan.autoSpots.size() + " 格（platform rows = " + MmtrCrowd.autoRows() + "）");

		// 2) 标记逐格（带"离站台边几格"）：这一条站台**扫到**的全部
		final Map<BlockCrowdMarker.Kind, List<String>> markersByKind = new LinkedHashMap<>();
		for (final SweptMarker marker : plan.sweptMarkers.values()) {
			markersByKind.computeIfAbsent(marker.kind(), kind -> new ArrayList<>())
				.add(marker.cell().getX() + "," + marker.cell().getY() + "," + marker.cell().getZ() + "@" + marker.lateral());
		}
		lines.add("[crowd-dump] 扫到标记 " + plan.sweptMarkers.size() + " 格；其中归本平台当站位的 " + plan.markerSpots.size() + " 格（@N = 离站台方块 N 格）");
		for (final Map.Entry<BlockCrowdMarker.Kind, List<String>> entry : markersByKind.entrySet()) {
			lines.add("[crowd-dump]   " + entry.getKey() + " " + entry.getValue().size() + " 格：" + String.join(" ", entry.getValue()));
		}

		// 2b) 归本平台的站位格逐格：客户端画人的候选格（渲染端自己扫世界，这份是服务端认到的）
		final List<String> standingCells = new ArrayList<>();
		for (final Spot spot : plan.markerSpots) {
			standingCells.add(spot.villagerPos.getX() + "," + spot.villagerPos.getY() + "," + spot.villagerPos.getZ());
		}
		lines.add("[crowd-dump] 归本平台站位 " + standingCells.size() + " 格（不含归属锚；客户端画人的候选格）");
		if (!standingCells.isEmpty()) {
			lines.add("[crowd-dump]   " + String.join(" ", standingCells));
		}

		// 3) 走动现场：走道逐格 + 每只人（**旧链路**，默认不跑 —— 见 LEGACY_SPAWNING）
		final List<String> corridor = MmtrCrowdWalkers.corridor(module.dimension, platform.getId());
		lines.add("[crowd-dump] 走道 " + corridor.size() + " 格（旧链路：默认不跑，-Dmmtr.crowd.spawn=true 才驱动）：" + String.join(" ", corridor));
		final List<MmtrCrowdWalkers.WalkerInfo> walkers = MmtrCrowdWalkers.walkers(module.dimension, platform.getId());
		lines.add("[crowd-dump] 走动人流登记 " + walkers.size() + " 只");
		for (final MmtrCrowdWalkers.WalkerInfo walker : walkers) {
			lines.add(String.format(Locale.ENGLISH, "[crowd-dump]   %s 活着=%s 位置=%s 驱动=%d tick 位移=%.1f 格 掉头=%d 拉回=%d",
				walker.uuid(), walker.alive(), walker.position(), walker.ticks(), walker.moved(), walker.turns(), walker.pulls()));
		}
		return lines;
	}

	/** 站位按级数个数（报告用）。 */
	private static Map<Grade, Integer> countByGrade(List<Spot> spots) {
		final Map<Grade, Integer> counts = new LinkedHashMap<>();
		for (final Spot spot : spots) {
			counts.merge(spot.grade, 1, Integer::sum);
		}
		return counts;
	}

	private static long approximateLengthBlocks(Platform platform) {
		final Position[] ends = platform.mmtrOrderedPositions();
		return Math.round(Math.hypot(ends[1].getX() - ends[0].getX(), ends[1].getZ() - ends[0].getZ()));
	}

	// ------------------------------------------------------------------ 小工具

	/**
	 * 位置所在区块是否已加载。
	 *
	 * <p>为什么必须问：{@code ServerWorld.getBlockState} 对未加载的区块会**从磁盘加载它**
	 * （甚至生成地形）。200 格站台一条扫下来就是十几个区块 —— 一次扫描能把服务端卡住几秒。</p>
	 */
	private boolean isLoaded(BlockPos pos) {
		return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/**
	 * 位置哈希 → [0,1)：**站位外观档**（群系/职业那几档）用它与盐算，保证同一个格每次结果一致。
	 *
	 * <p>用 splitmix64 的收尾函数（两次乘法 + 三次 xor-shift）。为什么不用"一次乘法 + 位移"那种更短的写法：
	 * 实测（用真实站台 id 与 220 格连续 x 试）短写法的均值偏到 0.478，于是"设 60%"会铺出 62–64% 的人 ——
	 * 而"客量 60% 就是六成格子有人"正是这个功能对外承诺的口径，偏差会直接在读数里露出来。
	 * 换 splitmix64 后均值为 0.500、各档偏差回到采样噪声内。
	 * （方案 A 之后服务端不再用它挑"哪些格有人"；{@link MmtrCrowdWalkers} 与
	 * {@code assignMarkers} 的外观档仍然用它 —— 同一个人每次重算都得穿同一件衣服。）</p>
	 */
	static double hash01(long salt, BlockPos pos) {
		long h = salt
			^ pos.getX() * 0x9E3779B97F4A7C15L
			^ pos.getY() * 0xC2B2AE3D27D4EB4FL
			^ pos.getZ() * 0x165667B19E3779F9L;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (h >>> 11) * 0x1.0p-53;
	}

	/** 一个站位属于哪一级（标记的三种，报告里按它分开数）。 */
	private enum Grade {
		GRADE_1, GRADE_2, FLOW
	}

	/**
	 * 一个站位格：在哪、朝哪（＝朝轨道）、外观档、属于哪一级。
	 *
	 * <p>方案 A 之后它只是**读数**用的账（{@code assignMarkers} 写、{@code report/dump} 读）：
	 * 没有逐格落位，客户端的"哪几格有人"由它自己的哈希与客量算。</p>
	 */
	private record Spot(BlockPos villagerPos, Direction facing, int typeIndex, Grade grade) {
	}

	/** 站台边缘：站台方块那一格 + 它在轨道的哪一侧（{@code sign}）× 第几格（{@code lateral}）。 */
	private record Edge(BlockPos blockPos, int sign, int lateral) {
	}

	/** 一根柱子上找到的"能站人的那一格" + 那一格地面的顶面高度。 */
	private record Standing(BlockPos cell, double floorTop) {
	}

	/** 扫到的一格标记：位置 + 种类 + 离站台边几格（两条站台抢同一格时比这个）+ 朝向。 */
	private record SweptMarker(BlockPos cell, BlockCrowdMarker.Kind kind, int lateral, Direction facing) {
	}

	/** 一格标记归了谁（汇总、改判、写回都用这一对）。 */
	private record Claim(SweptMarker swept, long platformId) {
	}

	/** 一个站台缓存下来的几何与标记账（**只喂读数**：report / dump；不驱动任何世界改动）。 */
	private static final class Plan {

		private final long platformId;
		private final String geometryKey;
		private final long scannedMillis;
		/** 自动区候选（站台方块后面那几格）；扫的时候定下来，15 秒或几何变了才重扫。 */
		private List<Spot> autoSpots = new ArrayList<>();
		/** 本次扫描在这一条站台周围扫到的标记（格 → 标记）；重扫时整份换掉。 */
		private final Map<BlockPos, SweptMarker> sweptMarkers = new LinkedHashMap<>();
		/**
		 * 归本平台的**站位**格（每拍由 {@code assignMarkers} 汇总/改判后写回；不含归属锚）。
		 *
		 * <p>它就是"这条站台的候选落点"，也是报告里"客户端约画 N 人"的分母；
		 * 服务端不再拿它去铺任何方块（方案 A，notes/416）。</p>
		 */
		private List<Spot> markerSpots = new ArrayList<>();
		/** 站台方块那一排（报告里报"站台边几格"，也是"这条站台的站台面在哪"的旁证）。 */
		private final Set<BlockPos> edgeBlocks = new LinkedHashSet<>();
		/** 这次扫描有没有"没看全"（有样本落在未加载区块里）—— 没看全的下一拍重扫。 */
		private boolean complete;
		/** 扫描时发现的遗留客流方块（更早那版方块方案铺的）：清完置 null。 */
		private List<BlockPos> legacyBlocks;

		private Plan(long platformId, String geometryKey, long scannedMillis) {
			this.platformId = platformId;
			this.geometryKey = geometryKey;
			this.scannedMillis = scannedMillis;
		}
	}
}
