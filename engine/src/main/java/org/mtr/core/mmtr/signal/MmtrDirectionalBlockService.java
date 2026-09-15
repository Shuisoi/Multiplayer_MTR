package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.tool.Vector;

import java.util.Map;

/**
 * 闭塞区间 v2（S1，纯数据）: <strong>灯到灯的跨轨有向区间</strong>.
 *
 * <p>The v1 service ({@link MmtrBlockService}) cut sections <em>inside one rail</em> and treated every
 * rail end node as a boundary. That model cannot represent how signals are actually placed on a real
 * map: a lamp stands next to a node, so its projection arc is 0 or the rail length, and the v1 guard
 * "sitting at an end node -&gt; skip" therefore discarded <strong>every</strong> lamp. The live world
 * ended up partitioned into "one rail = one section" with no lamp involved at all
 * ({@code blocks all}: 134 rails = 134 sections, 0 rails cut by a lamp).</p>
 *
 * <p>v2 restores the real meaning:</p>
 * <blockquote>
 *   A section is what one lamp protects: starting at the lamp, walking in the direction the lamp faces,
 *   up to the next lamp facing the same way (exclusive) - or until the walk cannot continue.
 * </blockquote>
 *
 * <p>Consequences, all deliberate:</p>
 * <ul>
 *   <li>a section <strong>spans rail boundaries</strong>: an ordered list of
 *       {@code (railHex, arcFrom, arcTo)} spans, not a single-rail arc interval;</li>
 *   <li>sections are <strong>directed</strong>: the same arc of the same rail belongs to different
 *       sections in the two travel directions;</li>
 *   <li><strong>only lamps create boundaries</strong> - rail end nodes no longer do.</li>
 * </ul>
 *
 * <p>Arc space is the ordered-position-1 space the shared occupancy trees already use
 * ({@link MmtrBlockService#projectArc} documents the mapping), so occupancy projection and S1 stops can
 * consume these sections without conversion.</p>
 */
public final class MmtrDirectionalBlockService {

	/** How far from a rail a lamp may stand and still be taken as protecting it. */
	public static final double SIGNAL_BIND_TOLERANCE_M = 8.0;

	/** Sampling step for projecting a lamp and for reading a rail's heading. */
	private static final double SAMPLE_STEP_M = 0.25;

	/**
	 * 量"这条轨往哪走"时用的基线长度（米）。
	 *
	 * <p>不能太小：轨的采样是整数格，{@code ±0.25 m} 的窗口量到的是采样噪声（见 {@link #headingAhead}）。
	 * 不能太大：基线跨过整条曲线时，量到的是弦向而不是灯前方那一小段的方向。
	 * 6 m 约等于两三个信号灯位间距，实测既躲开了噪声，也没跨过任何真实的弯。</p>
	 */
	private static final double HEADING_BASELINE_M = 6;

	/**
	 * 绑定一盏灯时，它前方至少要留下这么长的可走轨（米）。
	 *
	 * <p>小于这个长度的"区间"没有意义：它保护不了任何东西，而显示层读到它只会给出绿灯 ——
	 * 一个**看起来正常**的错答案。宁可判定"这盏灯不参与闭塞"（显示层会明确显示未知），
	 * 也不要用一个 0 米区间冒充答案（实测 {@code -38,-60,-235} 绑到轨端后区间只有 3 m）。</p>
	 */
	private static final double MIN_BINDABLE_AHEAD_M = 1.0;

	/**
	 * 灯离节点多近就当作"站在节点上"（米）。
	 *
	 * <p>取 4 格（原来 3 格，实测被差 0.16 格卡在门外）：信号机立在线路旁约一格，横向算 3 格，
	 * 再加**竖直方向的一格** —— MTR 的信号灯方块是**两格高**的，登记时用的那一格常常比轨道所在
	 * 的节点高一格，于是水平"贴着节点"的灯算出来是 3.16 格。实测锚点 {@code -70,-59,-139}
	 * （角 0、对着 north、应守进库 stub）就正好卡在这个边界上：它掉进了兜底分支，
	 * 于是"节点上按朝向选腿"那套规则**根本没被应用**，这就是它颜色不对的直接原因。</p>
	 *
	 * <p>为什么不怕副作用：竖直方向本来就由 {@code nearestNode} 的 {@link #SIGNAL_BIND_TOLERANCE_M}
	 * （8 格）把关，立体交叉的上下层线路不会被彼此的节点认领；这里放宽的只是"节点旁多远算旁边"，
	 * 从 3 格到 4 格不会把区间中段的灯误判成道岔灯。</p>
	 */
	private static final double NODE_BIND_RADIUS_M = 4.0;

	/**
	 * 候选轨的"扇区"判据：出射方向与灯面朝方向的点积大于 {@code -CONE_COS} 就算落在扇区内。
	 *
	 * <p>原版 MTR 用"夹角差 90° 以内"（{@code circularDifference(...) < 90}），也就是点积 > 0；
	 * 这里放宽到 {@code > -0.5}（约 120°）：候选名单是**给人看的**，宽一点让人能选到
	 * "看起来就在灯正前方、但几何算出来偏一点"的那根轨；真正生效的绑定是人工点选的结果，
	 * 不会因为名单宽而误绑。</p>
	 */
	private static final double CONE_COS = 0.5;

	/**
	 * 占用判据里允许的松弛（米）：重叠下限是 {@code min(区间, 足迹) / 2}，再减掉这一点。
	 *
	 * <p>轨的弧长与足迹都带整数格取整的误差（实测同一根轨上灯的弧坐标就有 1~2 m 的偏），
	 * 不留松弛会让"正好停满一段"的车因为差半米而被判成不占用 —— 那比误报占用更危险。</p>
	 */
	private static final double MIN_OCCUPANCY_SLACK_M = 1.0;

	/** Safety caps: a malformed graph must never spin forever. */
	private static final int MAX_RAILS_PER_SECTION = 256;
	private static final double MAX_SECTION_LENGTH_M = 4000;
	/** Two block boundaries closer than this on one rail are the same boundary (sampled arcs wobble). */
	private static final double MIN_BLOCK_PIECE_M = 0.05;
	/** A track-layer node this close to a block boundary is that boundary (its cell starts there). */
	private static final double NODE_OWNER_TOLERANCE_M = 1.0;

	/*
	 * ---------------------------------------------------------------- 绑定关系挂在「节点」上
	 *
	 * 一盏灯守什么 = **灯先归到一个节点，再取该节点上朝我面朝方向的那条（或那几条）轨**。
	 * 这与原版 MTR 一致：{@code RenderSignalBase.getAspectState} 用 {@code getNodePos} 在灯周围找
	 * 一个 {@code BlockNode}，再取该节点上"出射方向与灯面朝角相差 90° 以内"的轨。
	 * 也就是说：灯**不**绑在某条轨上，而是绑在节点上，由**朝向**在节点的几条腿里挑（可以多条 = 一灯多腿）。
	 *
	 * 本类同此：{@link #nearestNode} 找最近节点，{@link #resolveProtectedRailsInternal} 在该节点上按朝向选轨。
	 *
	 * 与"纯节点绑定"只差两处，都是实测逼出来的：
	 *   ① 人工显式绑定（{@code SignalEntry.rails}，点选/物品绑定写入）：人工指定时**只认列出的轨**，
	 *      方向**仍由灯的朝向定**（列车迎着灯进来，区间从灯背后走向灯面朝的那一端）。它不改变
	 *      "绑定挂在节点上"，只是允许越过那套几何推断——密集站场里灯的朝向未必指得准。
	 *   ② 区间中段的灯（离任何节点都 > {@link #NODE_BIND_RADIUS_M}）退回"投影到旁边那条轨"。
	 *      原版没有这一步（找不到节点块就不给这盏灯算状态），这里保留是因为实测世界里就有这种灯。
	 */

	/** One rail's slice of a section, in ordered-position-1 arc space, with its travel direction. */
	public static final class RailSpan {
		public final String railHex;
		public final double arcFromM;
		public final double arcToM;
		/** Unit travel direction (x, z) over this span. */
		public final double headingX;
		public final double headingZ;
		/**
		 * **这条腿是这次运行走得到的吗**（notes/132 §5 的遗留项）。
		 *
		 * <p>区间的 span **照旧收全部腿**（保住用户 2026-09-10 的"一盏灯守整个咽喉"：岔股上可能停着
		 * 扳岔之前就进来的车），但道岔当前位置切掉的那条腿这次运行**进不去** —— 它上面的车不是
		 * 这条进路的前方障碍。所以占用判定只认 {@code reachable} 的 span，而"守着"照旧。</p>
		 */
		public final boolean reachable;

		RailSpan(String railHex, double arcFromM, double arcToM, double headingX, double headingZ) {
			this(railHex, arcFromM, arcToM, headingX, headingZ, true);
		}

		RailSpan(String railHex, double arcFromM, double arcToM, double headingX, double headingZ, boolean reachable) {
			this.railHex = railHex;
			this.arcFromM = Math.min(arcFromM, arcToM);
			this.arcToM = Math.max(arcFromM, arcToM);
			this.headingX = headingX;
			this.headingZ = headingZ;
			this.reachable = reachable;
		}

		public double lengthM() {
			return arcToM - arcFromM;
		}

		/** Whether {@code arc} lies in this span (half-open). */
		public boolean containsArc(double arc) {
			return arc >= arcFromM - 1e-6 && arc < arcToM - 1e-6;
		}

		/** Whether a movement heading {@code (x, z)} travels this span the same way. */
		public boolean matchesHeading(double x, double z) {
			return headingX * x + headingZ * z > 0.1;
		}

		@Override
		public String toString() {
			return shortHex(railHex) + "[" + round(arcFromM) + ".." + round(arcToM) + "]" + (reachable ? "" : "(走不到)");
		}
	}

	/** One directed block section: the movement a single lamp authorises. */
	public static final class Section {
		/** Stable id: the protecting lamp's {@code x,y,z} key (a lamp has one outgoing section). */
		public final String id;
		/** The lamp that starts this section. */
		public final String entrySignalKey;
		/** The lamp that ends it, or empty when the walk ran out (dead end / no further lamp). */
		public @Nullable String exitSignalKey;
		/**
		 * **全部**当界的灯（岔口多腿时每个分叉各有一架），{@link #exitSignalKey} 是其中的第一架。
		 *
		 * <h3>为什么必须是一张表，不能只留一架</h3>
		 * <p>用户 2026-09-10 对岔口的裁定是"整个咽喉都是闭塞"：走行在岔口会**跟着每一条朝前的腿**
		 * 一起走（见 {@link #walk}），所以一段区间的尽头可能同时有几架灯 —— 每架灯各开一段后续区间。</p>
		 *
		 * <p>把"后续区间"按**单值**记（原实现：只记最后一个分叉撞上的那架灯）会让链在岔口被砍成一支：
		 * 实测 depot 咽喉 {@code (-67,-139)} 是度=3 的岔口，从 {@code -10,-59,-154} 一路走过来的链
		 * 只接上了**朝北**那支（{@code -64,-59,-139} 的区间，空，最后到尽头），而**朝南**那支
		 * {@code -70,-59,-139} 的区间上正停着车 —— 于是那盏灯读绿，正确答案是**双黄**
		 * （本段空 / 下一段空 / 第三段占用）。多支取最不利之后它才读对。</p>
		 */
		public final ObjectArrayList<String> exitSignalKeys = new ObjectArrayList<>();
		/** Ordered spans, in the direction of travel. */
		public final ObjectArrayList<RailSpan> spans = new ObjectArrayList<>();
		/** Whether the walk stopped because it could not continue rather than at another lamp. */
		public boolean endsAtDeadEnd;
		/**
		 * 本段**结束在哪个节点**（{@code x,y,z} 键）。
		 *
		 * <p>区间链接的判据：一段结束的节点，就是下一段起步的节点。用节点而不是"出口灯的名字"来连，
		 * 是因为走行在节点上找到的灯未必就是某段的起始灯（两格高的灯、灯在隔壁一格），
		 * 名字对不上时链会断，黄灯就永远读不到（见 {@code rebuild} 的说明）。</p>
		 */
		public @Nullable String endNodeKey;
		/**
		 * 走行为什么停在这里（诊断用）。
		 *
		 * <p>"区间太短 / 后继区间不存在"这一类现象，从外面看都只是"灯显示得不对"，但原因只有几种：
		 * 停在了一架面向本区间的灯上（正常）、走到了死胡同（线路到头 / 没有后续轨）、
		 * 或者是被长度上限截断。把这些原因记下来，`signal why` 才能说清"链为什么断"。</p>
		 */
		public String endReason = "（未记录）";
		/**
		 * 这一段**走到头撞在道岔的禁行侧**了（到达那条轨是道岔当前位置切掉的那一侧）。
		 *
		 * <p>物理含义：这条进路走不出去 —— 车即使被放行，也只能开到岔前停住。所以它是一条
		 * **没有进路的区间**，显示层必须给**红**（用户 2026-09-13 现场问的那盏
		 * {@code -70,-59,-167} 就是这种：道岔设 1 时正线北侧被切断，它却按"下一段有车"给了单黄）。</p>
		 */
		public boolean blockedAtArrival;
		/**
		 * 这一段**在起点就被切断**了：这条腿本身就是道岔当前禁行的那一侧（列车根本走不进去）。
		 *
		 * <p>与 {@link #blockedAtArrival} 的区别：那种是"进路走不出去⇒红"，这种是"这条腿压根不是一条路"，
		 * 所以它**不参与灯色计算**（多腿灯里别的腿还开着，例如节点灯朝北时：正线那腿被切、
		 * 岔股那腿开着 —— 这盏灯该按岔股那条给出颜色，而不是因为被切的那条腿变红）。
		 * 它仍旧算作这盏灯**守的轨**（用户要的"保护向北的所有股道"）。</p>
		 */
		public boolean blockedAtDeparture;

		Section(String id, String entrySignalKey) {
			this.id = id;
			this.entrySignalKey = entrySignalKey;
		}

		/**
		 * 记一架当界的灯（走行停下时调用）：{@code exitSignalKey} 保留**第一架**（既有调用方与诊断都读它），
		 * 链则用 {@link #exitSignalKeys} 里的**全部**（岔口每个分叉各一架）。
		 */
		void addExitLamp(String lampKey) {
			if (exitSignalKey == null || exitSignalKey.isEmpty()) {
				exitSignalKey = lampKey;
			}
			if (!exitSignalKeys.contains(lampKey)) {
				exitSignalKeys.add(lampKey);
			}
		}

		public double lengthM() {
			double length = 0;
			for (final RailSpan span : spans) {
				length += span.lengthM();
			}
			return length;
		}

		/** The arc at which this section begins, on the rail it begins on. */
		public double entryArcM() {
			return spans.isEmpty() ? 0 : spans.get(0).arcFromM;
		}

		/** The rail the movement enters this section on. */
		public @Nullable String entryRailHex() {
			return spans.isEmpty() ? null : spans.get(0).railHex;
		}

		/** 本段从哪个节点起步（诊断用；null = 起点在轨的中间）。 */
		public @Nullable String entryNodeKeyOrNull() {
			return entryNodeKey;
		}

		/** 本段起步的节点键（{@code rebuild} 链接时填）。 */
		@org.jspecify.annotations.Nullable String entryNodeKey;

		@Override
		public String toString() {
			return id + " -> " + (exitSignalKey == null || exitSignalKey.isEmpty() ? "DEAD_END" : exitSignalKey)
				+ " spans=" + spans.size() + " len=" + round(lengthM());
		}
	}
	/** One lamp resolved to the rail it protects and the direction it authorises. */
	public static final class ProtectedRail {
		public final Rail rail;
		public final double arcM;
		/**
		 * **区间走行方向**：沿这根轨从 {@link #arcM} 往前走的那一侧。
		 *
		 * <p>节点旁的灯守的是它朝向的**对面**那一侧（用户 2026-09-13 的规则），所以这个方向与
		 * {@link #lampX}/{@link #lampZ} 给的灯朝向**相反** —— 两者都要留着：走行要前者，
		 * 而占用/中段灯的判据要用后者（"迎着灯开过来的车"在灯的哪一侧）。</p>
		 */
		public final double headingX;
		public final double headingZ;
		/** 灯面朝的方向（单位向量）。中段灯这条路径下与走行方向一致，节点灯则相反。 */
		public final double lampX;
		public final double lampZ;
		/**
		 * 这条腿被道岔切断了（它正是灯所在节点上**当前位置禁行**的那一侧）⇒ 不是一条进路。
		 *
		 * <p>由选腿那一层判定（那里才知道灯挂在哪个节点上）。它仍旧算这盏灯**守的轨**
		 * （见 {@link Section#blockedAtDeparture}）。</p>
		 */
		public boolean blockedAtDeparture;

		ProtectedRail(Rail rail, double arcM, double headingX, double headingZ) {
			this(rail, arcM, headingX, headingZ, headingX, headingZ);
		}

		ProtectedRail(Rail rail, double arcM, double headingX, double headingZ, double lampX, double lampZ) {
			this.rail = rail;
			this.arcM = arcM;
			this.headingX = headingX;
			this.headingZ = headingZ;
			this.lampX = lampX;
			this.lampZ = lampZ;
		}
	}

	/**
	 * 最近一次节点分支的选腿明细（诊断用；见 {@link #lastLegTrace()}）。
	 *
	 * <p>按**灯键**记录、放在线程内：{@code signal why} 会重建全部灯，一个"最后写进去的那条"会被
	 * 别的灯覆盖，甚至串到另一盏灯的结论上 —— 那种诊断比没有诊断更坏（会把人引到错的灯上）。</p>
	 */
	private static final ThreadLocal<String> LEG_TRACE_TARGET = new ThreadLocal<>();
	private static final ThreadLocal<String> LAST_LEG_TRACE = new ThreadLocal<>();
	/** 人工绑定里被忽略的条目（诊断用；见 {@link #lastBindReject()}）。 */
	private static final ThreadLocal<String> LAST_BIND_REJECT = new ThreadLocal<>();

	/** 下一次 {@link #refresh()} 时记录哪盏灯的选腿明细（传空串 = 不记录）。 */
	public static void recordLegTraceFor(@Nullable String lampKey) {
		LEG_TRACE_TARGET.set(lampKey == null ? "" : lampKey);
		LAST_LEG_TRACE.set("");
		LAST_BIND_REJECT.set("");
	}

	/** 上一次刷新时为目标灯记录的人工绑定忽略情况（无则空串）。 */
	public static String lastBindReject() {
		final String reject = LAST_BIND_REJECT.get();
		return reject == null ? "" : reject;
	}

	/** 只在目标灯上记录（见 {@link #recordLegTraceFor}）——不按灯记就会串到别的灯头上。 */
	private static void noteBindReject(SignalEntry entry, String message) {
		if (MmtrSignalRegistry.key(entry.x, entry.y, entry.z).equals(LEG_TRACE_TARGET.get())) {
			LAST_BIND_REJECT.set(message);
		}
	}

	/** 上一次刷新时为目标灯记录的选腿明细（未记录时为空串）。 */
	public static String lastLegTrace() {
		final String trace = LAST_LEG_TRACE.get();
		return trace == null ? "" : trace;
	}

	private final Simulator simulator;
	private final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();	/** Lamp key -> the section(s) it starts. A lamp guarding several legs (a junction) has one per leg. */
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<Section>> sectionsBySignal = new Object2ObjectOpenHashMap<>();
	/** Rail hex -> every section covering it (a rail belongs to sections in both directions). */
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<Section>> sectionsByRail = new Object2ObjectOpenHashMap<>();
	/** Section id -> the section that continues it (the exit lamp's section), when the walk ended at a lamp. */
	/** Section id -> the section(s) that continue it (one per branch at a junction). */
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<Section>> followingBySection = new Object2ObjectOpenHashMap<>();
	/**
	 * 灯键 → 这盏灯**自己守护的走行方向**（一灯多腿有多个），{@link #rebuild()} 时清空。
	 *
	 * <p>走行判"下一架灯"要问的就是它（见 {@link #facesInto}）；每次现算一遍几何太贵
	 * （{@code nearestLampOnSpan} 会对每根轨遍历全部灯）。</p>
	 */
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<double[]>> guardedHeadingsByLamp = new Object2ObjectOpenHashMap<>();
	private String cachedSignature = "";
	/** 上次 rebuild 连成了多少条链接（诊断用：链的规模是"黄灯可不可达"的第一个指标）。 */
	private int linkedSections;

	/** 区间总数与其中连成链的数量（诊断用）。 */
	public String describeChainStats() {
		refresh();
		return "区间 " + sectionsBySignal.size() + " 段，连成链 " + linkedSections + " 条"
			+ "（链条数远少于段数时，多数灯的前方没有「下一段」，单黄/双黄到不了）";
	}

	public MmtrDirectionalBlockService(Simulator simulator) {
		this.simulator = simulator;
	}

	// ---------------------------------------------------------------- queries

	/** The section a lamp starts, or null when the lamp protects nothing. */
	public @Nullable Section sectionOfSignal(String signalKey) {
		refresh();
		final ObjectArrayList<Section> group = sectionsBySignal.get(signalKey);
		return group == null || group.isEmpty() ? null : group.get(0);
	}

	/** 一盏灯守的**全部**区间（一灯多腿：每根受保护的轨一段）；没有则空表。 */
	public ObjectArrayList<Section> sectionsOfSignal(String signalKey) {
		refresh();
		final ObjectArrayList<Section> out = sectionsBySignal.get(signalKey);
		return out == null ? new ObjectArrayList<>() : out;
	}

	/**
	 * 一盏灯现在守的轨（hex）；一灯多腿时有多个。人工点选绑定的优先，否则是推断出来的。
	 *
	 * <p>点选绑定之后，界面要靠这个把"它现在守哪几根"画出来，所以它与推算是同一份数据，
	 * 不另存一份"界面用的绑定" —— 两处记录早晚会对不上（这几轮已经吃过一次这个亏）。</p>
	 */
	public ObjectArrayList<String> protectedRailsOf(SignalEntry entry) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final ObjectOpenHashSet<String> seen = new ObjectOpenHashSet<>();
		for (final ProtectedRail protectedRail : resolveProtectedRailsInternal(entry)) {
			// 对外统一用**规范 hex**（见 canonicalHex）：人工绑定列表里存的可能是逆序写法，
			// 而网页拿到的轨 hex 是规范写法 —— 不统一，界面就认不出"这条就是它在守的那条"。
			final String hex = canonicalHex(protectedRail.rail.getHexId());
			if (seen.add(hex)) {
				out.add(hex);
			}
		}
		return out;
	}

	/** Every section covering {@code railHex} (both directions); empty when unknown. */
	public ObjectArrayList<Section> sectionsOfRail(@Nullable String railHex) {
		refresh();
		if (railHex == null || railHex.isEmpty()) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<Section> sections = sectionsByRail.get(railHex);
		return sections == null ? new ObjectArrayList<>() : sections;
	}

	/**
	 * The section containing {@code arcM} of {@code railHex} for a movement heading {@code (headingX,
	 * headingZ)}, or null. The heading filter is what makes this directional: the same point belongs to
	 * different sections in opposite directions.
	 */
	public @Nullable Section sectionAt(@Nullable String railHex, double arcM, double headingX, double headingZ) {
		if (railHex == null || railHex.isEmpty()) {
			return null;
		}
		for (final Section section : sectionsOfRail(railHex)) {
			for (final RailSpan span : section.spans) {
				if (span.railHex.equals(railHex) && span.containsArc(arcM) && span.matchesHeading(headingX, headingZ)) {
					return section;
				}
			}
		}
		return null;
	}

	public int sectionCount() {
		refresh();
		return sectionsBySignal.size();
	}

	/** Whether any directional section covers {@code railHex} (the caller's "is v2 my business here"). */
	public boolean hasSection(@Nullable String railHex) {
		return !sectionsOfRail(railHex).isEmpty();
	}

	/** Every rail hex that carries at least one directional section. */
	public ObjectOpenHashSet<String> railsWithSections() {
		refresh();
		return new ObjectOpenHashSet<>(sectionsByRail.keySet());
	}

	/** How many rails carry at least one directional section (diagnostics). */
	public int railsWithSectionsCount() {
		refresh();
		return sectionsByRail.size();
	}

	/**
	 * The section that continues {@code section} in its travel direction, or null at the end of the line.
	 *
	 * <p>岔口上有多支（见 {@link #followings}），这里只回**第一支** —— 单支调用方（诊断、既有测试）用；
	 * 显示层要"最不利"必须遍历 {@link #followings}。</p>
	 */
	public @Nullable Section following(@Nullable Section section) {
		final ObjectArrayList<Section> all = followings(section);
		return all.isEmpty() ? null : all.get(0);
	}

	/** **全部**接在 {@code section} 之后的段（岔口每个分叉一段）：显示层按它们取最不利。 */
	public ObjectArrayList<Section> followings(@Nullable Section section) {
		if (section == null) {
			return new ObjectArrayList<>();
		}
		/*
		 * 走到头撞在**道岔禁行侧**的段没有后续：这条进路本身就走不出去（车只能停在岔前），
		 * 车永远不会进入岔口另一侧的那一段 —— 所以那段既不是"下一段区间"，也不该影响这盏灯的颜色。
		 */
		if (section.blockedAtArrival) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<Section> all = followingBySection.get(section.id);
		return all == null ? new ObjectArrayList<>() : all;
	}

	/**
	 * 占用投影: whether any vehicle's footprint overlaps {@code section}.
	 *
	 * <p>Section occupancy is a plain interval test per span against the shared occupancy trees - the
	 * span is already an arc window on one rail in the same ordered-position-1 space the trees use, so
	 * no conversion and no write-side change is needed. A section spanning several rails is occupied if
	 * ANY of its spans is.</p>
	 *
	 * <p><b>只认走得到的 span</b>（notes/132 §5）：区间把道岔切掉的那条腿也收进来（守住整个咽喉），
	 * 但那条腿这次运行**进不去** —— 它上面停着车不是这条进路的前方障碍。把"守卫范围"与"占用范围"
	 * 分开之后，"某列车在扳岔之前进了岔股、之后一直停在走不到的那条腿上，于是这盏灯永远红"
	 * 这件事消失了；而它只要还在**走得到**的范围里，判定与从前逐位相同。</p>
	 *
	 * @param trees the occupancy trees to test (null = the simulator's live train trees)
	 */
	public boolean isOccupied(Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return isOccupied(section, trees, 0);
	}

	/**
	 * 占用树里这根轨的足迹 —— **两个端点顺序都认**。
	 *
	 * <h3>为什么不能只按 {@code mmtrOrderedPositions()} 的顺序取</h3>
	 * <p>占用树是两级映射：第一级是轨的一端，第二级是另一端，取值时必须给**同一对**坐标，
	 * 但方向无所谓（轨是无向的）。而 {@code mmtrOrderedPositions()} 的顺序来自轨的声明顺序，
	 * 与车辆写入足迹时用的顺序**可以相反**。</p>
	 *
	 * <p>实测（2026-09-13，锚点 {@code -70,-59,-139} 那盏"游戏里红、web 上绿"的灯）：</p>
	 * <pre>
	 * query occupancy  →  (-67,-139)→(-67,-103)  车=[-4282003469230822656]  足迹=[10..26]
	 * </pre>
	 * <p>车明明就停在进库 stub 上（足迹 10..26 正对着车头 z=-128.5），可那条腿的区间一直报
	 * "占用=否"，灯永远绿 —— 因为引擎按 {@code ordered[0], ordered[1]} 取，而树里存的是反向的那一对。
	 * 这一处不留神就完全看不出来：树里有数据、区间也建对了，只是**取错了键**。</p>
	 */
	public static @Nullable VehiclePosition footprintOn(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tree, Position[] ordered) {
		if (tree == null || ordered == null || ordered.length < 2) {
			return null;
		}
		final VehiclePosition direct = Data.tryGet(tree, ordered[0], ordered[1]);
		return direct != null ? direct : Data.tryGet(tree, ordered[1], ordered[0]);
	}

	/**
	 * As above, but ignoring footprints owned by {@code excludeVehicleId} (pass 0 to count every vehicle).
	 *
	 * <p>A vehicle asking about the signal it is about to pass must not read ITS OWN body shadow as the
	 * obstruction: with a shadow whose anchor sits ahead of the head, that paints the block ahead red and
	 * the AWS horn sounds the moment the driver touches the throttle (notes/112 §4).</p>
	 */
	public boolean isOccupied(Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return false;
		}
		for (final RailSpan span : section.spans) {
			/*
			 * 走不到的腿：**守着、但不判断路**（notes/132 §5）。它这段弧这次运行进不去，
			 * 上面的车不是本进路的前方障碍 —— 按"占用"处理会让一盏看着直通正线的灯永远红
			 * （现场：车在道岔扳动之前进了岔股、之后一直停在那条腿上）。
			 */
			if (!span.reachable) {
				continue;
			}
			final Rail rail = railByHex.get(span.railHex);
			OCC_TRACE.set(" [占用诊断] span=" + shortHex(span.railHex) + " 弧" + round(span.arcFromM) + ".." + round(span.arcToM)
				+ " railByHex=" + (rail == null ? "**null**" : "有")
				+ " 树层数=" + occupancyTrees.size());
			if (rail == null || span.lengthM() <= 1e-9) {
				OCC_TRACE.set(OCC_TRACE.get() + " → 跳过（轨查不到或段长为零）");
				continue;
			}
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered == null || ordered.length < 2) {
				continue;
			}
			OCC_TRACE.set(OCC_TRACE.get() + " 端点=(" + ordered[0].getX() + "," + ordered[0].getY() + "," + ordered[0].getZ()
				+ ")→(" + ordered[1].getX() + "," + ordered[1].getY() + "," + ordered[1].getZ() + ")");
			for (int i = 0; i < occupancyTrees.size(); i++) {
				final VehiclePosition vehiclePosition = footprintOn(occupancyTrees.get(i), ordered);
				if (vehiclePosition != null && overlapsEnough(vehiclePosition, span, excludeVehicleId)) {
					return true;
				}
				OCC_TRACE.set(OCC_TRACE.get() + " 层" + i + ":足迹=" + (vehiclePosition == null ? "**取不到**" : "有")
					+ (vehiclePosition == null ? "" : " 够重叠=" + overlapsEnough(vehiclePosition, span, excludeVehicleId)));
			}
		}
		LAST_OCC_TRACE.set(OCC_TRACE.get());
		return false;
	}

	/**
	 * **谁压在这个区间里**（notes/152）：逐段看足迹，返回覆盖"够算占用"的车辆 id。
	 *
	 * <p>为什么需要这条只读出口：现场"车停在出发信号前"的排查里，最关键的一句话是"这个区间被谁占着"
	 * —— 原来只有 {@code occupied=true/false} 这一个布尔量，于是操作者分不清是
	 * **前面真有车**、**停着的邻车车体压进运行区间**、还是**问话的车自己**（自己的影子）。
	 * 三者的修法完全不同，看不见就只能猜。</p>
	 *
	 * @param excludeVehicleId 不把它算进去（0 = 全都算）
	 */
	public ObjectArrayList<Long> occupantsOf(Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final ObjectArrayList<Long> out = new ObjectArrayList<>();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return out;
		}
		for (final RailSpan span : section.spans) {
			if (!span.reachable) {
				continue;
			}
			final Rail rail = railByHex.get(span.railHex);
			if (rail == null || span.lengthM() <= 1e-9) {
				continue;
			}
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered == null || ordered.length < 2) {
				continue;
			}
			for (int i = 0; i < occupancyTrees.size(); i++) {
				final VehiclePosition vehiclePosition = footprintOn(occupancyTrees.get(i), ordered);
				if (vehiclePosition != null && overlapsEnough(vehiclePosition, span, excludeVehicleId)) {
					for (final long id : vehiclePosition.footprintIds()) {
						if (id != excludeVehicleId && !out.contains(id)) {
							out.add(id);
						}
					}
				}
			}
		}
		return out;
	}

	/** 最近一次 {@code isOccupied} 的逐步账（诊断用）。 */	private static final ThreadLocal<String> OCC_TRACE = ThreadLocal.withInitial(() -> "");
	private static final ThreadLocal<String> LAST_OCC_TRACE = ThreadLocal.withInitial(() -> "");

	/** 供 {@code signal why} / 测试取诊断串。 */
	public static String occupancyTrace() {
		final String value = LAST_OCC_TRACE.get();
		return value == null ? "" : value;
	}

	/**
	 * 足迹与区间的重叠是否"够算占用"（而不是仅仅擦到边）。
	 *
	 * <h3>为什么不能只判"有没有重叠"</h3>
	 * <p>车是**实体**：它跨过区间分界时必然同时压住前后两段 —— 前一段只被压到几米，
	 * 后一段才是它真正停的地方。若把"重叠 ≥ 0"当成占用，前后两段都会报占用，
	 * 那盏灯于是读到"自己面前的第一段就占了" → **红**；而正确答案是"再往前一段占了" → **单黄**。</p>
	 *
	 * <h3>为什么阈值必须随区间长度缩放，不能是一个固定米数</h3>
	 * <p>实测数据：车的足迹占弧 {@code 8..24}（16 m 长），本灯区间是 {@code 0..10}（重叠 <b>2 m</b>），
	 * 下一段是 {@code 10..32}（重叠 <b>14 m</b>）。先用一个固定的 1.5 m 下限去滤，2 m ≥ 1.5 m，
	 * 于是**照样算占用** —— 改了个"看起来合理"的常数，结论一点没变（实测被这一步挡了一轮）。</p>
	 *
	 * <p>正确的量纲是"比例"而不是"米"：车既然停在这一段里，它与这一段的重叠就该是两者中**较短的那个**
	 * 的一大部分。所以下限取 {@code min(区间长度, 足迹长度)} 的一半：
	 * 短区间被整车压住时（重叠≈区间长）算占用；只是骑在边界上（重叠几米）不算。</p>
	 *
	 * <p>不取"车中心落在哪一段"：中心要从足迹两端推算，而足迹可能有多段、方向还可能相反，
	 * 反而更容易错。</p>
	 */
	private static boolean overlapsEnough(VehiclePosition vehiclePosition, RailSpan span, long excludeVehicleId) {
		for (final double[] segment : vehiclePosition.segmentsExcluding(excludeVehicleId)) {
			final double footprintFrom = Math.min(segment[0], segment[1]);
			final double footprintTo = Math.max(segment[0], segment[1]);
			final double low = Math.max(span.arcFromM, footprintFrom);
			final double high = Math.min(span.arcToM, footprintTo);
			final double overlap = high - low;
			if (overlap <= 0) {
				continue;
			}
			/*
			 * 判据：重叠要占到**车长的一半**（区间更短时按区间长算，但不低于车长的一半这个上限）。
			 *
			 * 为什么下限不能按"较短的那个"取（试过，不行）：车停在短区间里时，较短的是区间本身，
			 * 下限只有区间长的一半 —— 一个 10 m 的区间只被压 5 m 就报占用。而车长 16 m、
			 * 停在紧邻的下一段里，骑在分界上压回来的正是这个量级，于是**两段都被报占用**，
			 * 灯读本段占用 → 红（实测：重叠 2.0 m、下限 5.0 m，照样过线）。
			 *
			 * 按车长取才是对的量纲：车（16 m）真停在 10 m 的区间里，重叠必然接近 10 m；
			 * 只是骑在分界上压到隔壁的，只有几米。上限封在车长的一半，于是很长的区间（100 m）
			 * 被车压住 8 m 也算"车在这一段里"，符合直觉。
			 */
			final double footprintLength = footprintTo - footprintFrom;
			final double required = Math.max(0.5,
				0.5 * Math.min(footprintLength, Math.max(span.lengthM(), footprintLength)) - MIN_OCCUPANCY_SLACK_M);
			if (overlap >= required) {
				return true;
			}
		}
		return false;
	}

	/**
	 * How far the movement may still run before its section ends, or {@link Double#MAX_VALUE} when it is
	 * not in any section of {@code railHex} for that heading (an unsignalled stretch: nothing to hold at).
	 *
	 * <p>This is the S2 building block for the S1 stop rule ("a movement stops at its section boundary"),
	 * not yet wired: callers decide whether to hold at the boundary or only when the section beyond is
	 * occupied.</p>
	 */
	public double sectionEndAheadM(@Nullable String railHex, double arcM, double headingX, double headingZ) {
		final Section section = sectionAt(railHex, arcM, headingX, headingZ);
		if (section == null) {
			return Double.MAX_VALUE;
		}
		double remaining = 0;
		boolean reached = false;
		for (final RailSpan span : section.spans) {
			if (!reached) {
				if (!span.railHex.equals(railHex) || !span.containsArc(arcM)) {
					continue;
				}
				reached = true;
				// The span is stored low..high; the travel direction decides which end we are heading for.
				remaining += span.matchesHeading(headingX, headingZ) ? span.arcToM - arcM : arcM - span.arcFromM;
				continue;
			}
			remaining += span.lengthM();
		}
		// The arc can sit exactly on the section's far boundary (half-open spans): not in it any more.
		return reached ? Math.max(0, remaining) : Double.MAX_VALUE;
	}

	/**
	 * The section a movement at {@code (railHex, arcM)} heading {@code (headingX, headingZ)} must be
	 * cleared to enter next: its own section while that is clear, otherwise the section beyond it.
	 * Null when the movement is not in a section at all.
	 */
	public @Nullable Section sectionAhead(@Nullable String railHex, double arcM, double headingX, double headingZ, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		final Section current = sectionAt(railHex, arcM, headingX, headingZ);
		if (current == null) {
			return null;
		}
		return isOccupied(current, trees) ? following(current) : current;
	}

	/**
	 * S3: how far ahead (metres) the movement may run before it would ENTER an occupied part of its own
	 * section - or {@link Double#MAX_VALUE} when there is nothing to hold it.
	 *
	 * <p>The rule mirrors v1's structure, only the unit is now the lamp-to-lamp section instead of a
	 * single rail: a movement is held at the checkpoint in front of the blocked stretch it is about to
	 * need. Concretely, walking the section's spans from the one the movement is on:</p>
	 * <ul>
	 *   <li>the span it is on is occupied ahead of it → hold at that occupancy face (that is the
	 *       same-rail rule the caller already applies, so this returns the boundary instead);</li>
	 *   <li>the NEXT span of the section is occupied → hold at the end of the current span, i.e. at the
	 *       lamp/node boundary between them. <strong>This is the case v1 could not express</strong>: the
	 *       boundary is a lamp, which may be several rails ahead of where the movement was stopped
	 *       before;</li>
	 *   <li>nothing occupied → no hold, the movement may run its section out.</li>
	 * </ul>
	 *
	 * @param trees occupancy trees to test (null = the simulator's live train trees)
	 */
	public double sectionBoundaryAheadM(@Nullable String railHex, double arcM, double headingX, double headingZ, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return sectionBoundaryAheadM(railHex, arcM, headingX, headingZ, trees, 0);
	}

	/**
	 * As above, but <strong>excluding one vehicle's own footprints</strong> ({@code excludeVehicleId}).
	 *
	 * <p>A vehicle's body shadow is stored under its own id, and a train may be asking about a stretch its
	 * own shadow already covers - either because its body is genuinely long or because the shadow's anchor
	 * sits ahead of its head. Counting that as "occupied ahead" makes the train stop at its own feet: with
	 * the stop point at the head, the throttle does nothing and the train can never move far enough to
	 * rewrite the shadow. <strong>Measured on the dev world</strong> (notes/112 §4): a train parked at
	 * offset 5.46 on a 43 m rail wrote its own footprint as [5.5, 37.5), so S1 read a 0.04 m block stop and
	 * the AWS rule read its own shadow as a RED signal ahead. Excluding self is the fix.</p>
	 */
	public double sectionBoundaryAheadM(@Nullable String railHex, double arcM, double headingX, double headingZ, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final Section section = sectionAt(railHex, arcM, headingX, headingZ);
		if (section == null) {
			return Double.MAX_VALUE;
		}
		for (int i = 0; i < section.spans.size(); i++) {
			final RailSpan span = section.spans.get(i);
			if (!span.railHex.equals(railHex) || !span.containsArc(arcM)) {
				continue;
			}
			final boolean forward = span.matchesHeading(headingX, headingZ);
			final double toSpanEndM = forward ? span.arcToM - arcM : arcM - span.arcFromM;
			// The stretch of this span the movement still has to cross (from the head to the span's end).
			final double from = forward ? arcM : span.arcFromM;
			final double to = forward ? span.arcToM : arcM;
			if (isSpanOccupied(span.railHex, from, to, trees, excludeVehicleId)) {
				// Someone is inside what we are about to cross: hold where it starts.
				final double toOccupancyM = distanceToOccupancyM(span.railHex, from, to, trees, forward, excludeVehicleId);
				return Math.max(0, toOccupancyM);
			}
			final RailSpan nextSpan = i + 1 < section.spans.size() ? section.spans.get(i + 1) : null;
			if (nextSpan != null && isSpanOccupied(nextSpan.railHex, nextSpan.arcFromM, nextSpan.arcToM, trees, excludeVehicleId)) {
				// The next stretch of our own section is taken: hold at the boundary between them.
				return Math.max(0, toSpanEndM);
			}
			return Double.MAX_VALUE;
		}
		return Double.MAX_VALUE;
	}

	/** Whether any vehicle footprint overlaps the arc window on {@code railHex}. */
	private boolean isSpanOccupied(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return isSpanOccupied(railHex, fromM, toM, trees, 0);
	}

	/**
	 * Whether any footprint OTHER than {@code excludeVehicleId}'s overlaps the arc window (pass 0 to count
	 * every footprint).
	 */
	private boolean isSpanOccupied(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		if (toM - fromM <= 1e-9) {
			return false;
		}
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return false;
		}
		final Rail rail = railByHex.get(railHex);
		if (rail == null) {
			return false;
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return false;
		}
		for (int i = 0; i < occupancyTrees.size(); i++) {
			final VehiclePosition vehiclePosition = footprintOn(occupancyTrees.get(i), ordered);
			if (vehiclePosition != null && vehiclePosition.getClosestOverlap(fromM, toM, false, excludeVehicleId) >= 0) {
				return true;
			}
		}
		return false;
	}

	/** How far from {@code fromM} the nearest external occupancy inside the window begins. */
	private double distanceToOccupancyM(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, boolean forward, long excludeVehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		final Rail rail = railByHex.get(railHex);
		if (occupancyTrees == null || rail == null) {
			return 0;
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return 0;
		}
		double best = Double.MAX_VALUE;
		for (int i = 0; i < occupancyTrees.size(); i++) {
			final VehiclePosition vehiclePosition = footprintOn(occupancyTrees.get(i), ordered);
			if (vehiclePosition == null) {
				continue;
			}
			for (final double[] segment : vehiclePosition.segmentsExcluding(excludeVehicleId)) {
				final double start = Math.max(fromM, segment[0]);
				final double end = Math.min(toM, segment[1]);
				if (end - start <= 1e-9) {
					continue;
				}
				best = Math.min(best, forward ? start - fromM : toM - end);
			}
		}
		return best == Double.MAX_VALUE ? 0 : Math.max(0, best);
	}

	/**
	 * S4 (显示层): the section that protects {@code railHex} for a movement entering it from {@code
	 * entryNode} - i.e. the section whose FIRST span starts at that node, which is exactly the movement a
	 * lamp standing there authorises. Null on rails no lamp reaches (the caller then keeps the v1 per-rail
	 * reading).
	 *
	 * <p>The entry node matters: a rail in the middle of a section is protected by it, but the same rail
	 * entering from the other end belongs to the opposite direction's section, which is a different block
	 * and must not be reported as this one.</p>
	 */
	public @Nullable Section sectionProtecting(@Nullable String railHex, @Nullable Position entryNode) {
		if (railHex == null || railHex.isEmpty() || entryNode == null) {
			return null;
		}
		final Rail rail = railByHex.get(railHex);
		if (rail == null) {
			return null;
		}
		final double entryArc = MmtrBlockService.arcOfNode(rail, entryNode);
		if (Double.isNaN(entryArc)) {
			return null;
		}
		for (final Section section : sectionsOfRail(railHex)) {
			for (final RailSpan span : section.spans) {
				if (!span.railHex.equals(railHex)) {
					continue;
				}
				if (Math.abs(span.arcFromM - entryArc) <= 0.5) {
					return section;
				}
				break; // this section covers the rail once; move on to the next candidate section
			}
		}
		return null;
	}

	/**
	 * S4: how far ahead (in SECTIONS, the protected one included) the nearest occupied section sits for a
	 * movement entering {@code railHex} from {@code entryNode}: 1 = the protected section itself, 2 = the
	 * section beyond it, 3 = the one after that, 0 = clear (or no directional section here at all, in
	 * which case the caller falls back to the v1 per-rail chain).
	 *
	 * <p>This is the v2 counterpart of {@code MmtrSignalAspect.chainDepth}: the unit of the walk is the
	 * lamp-to-lamp section instead of a rail, so a train standing three rails ahead inside the same
	 * section now reads as depth 1 (red) rather than as "three blocks away".</p>
	 *
	 * @param restrictedNodes    {@code x,y,z} keys of nodes that cannot be cleared (④: fouled clearance
	 *                           zone or undecided points) - stepping through one counts as occupied
	 * @param maxDepth           chain depth to model (3 = red / single / double yellow / green)
	 */
	public int chainDepth(@Nullable String railHex, @Nullable Position entryNode, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes, int maxDepth) {
		return chainDepth(railHex, entryNode, trees, restrictedNodes, maxDepth, 0);
	}

	/** As above, ignoring {@code excludeVehicleId}'s own footprints (the asking vehicle's body shadow). */
	public int chainDepth(@Nullable String railHex, @Nullable Position entryNode, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes, int maxDepth, long excludeVehicleId) {
		Section section = sectionProtecting(railHex, entryNode);
		if (section == null) {
			return 0;
		}
		for (int depth = 1; depth <= maxDepth; depth++) {
			if (isOccupied(section, trees, excludeVehicleId)) {
				return depth;
			}
			for (final String nodeKey : boundaryNodeKeys(section)) {
				if (restrictedNodes.test(nodeKey)) {
					return depth;
				}
			}
			if (depth == maxDepth) {
				break;
			}
			final Section next = following(section);
			if (next == null) {
				break;
			}
			section = next;
		}
		return 0;
	}

	/**
	 * The {@code x,y,z} keys of the node a section is entered through and the one it leaves through (④:
	 * the caller tests these against its restricted-junction set). Both are rail endpoints, so they key
	 * straight into the same node space the rest of the signal layer uses.
	 */
	public ObjectArrayList<String> boundaryNodeKeys(Section section) {
		final ObjectArrayList<String> keys = new ObjectArrayList<>();
		if (section.spans.isEmpty()) {
			return keys;
		}
		final RailSpan first = section.spans.get(0);
		final RailSpan last = section.spans.get(section.spans.size() - 1);
		final Rail firstRail = railByHex.get(first.railHex);
		final Rail lastRail = railByHex.get(last.railHex);
		if (firstRail != null) {
			// The span runs low..high arc; the movement travels from the arcFrom side when its heading
			// agrees with the increasing-arc direction, else from the arcTo side.
			final boolean travelsUpward = first.matchesHeading(1, 0) || first.matchesHeading(-1, 0)
				? first.headingX * thisRailHeadingX(firstRail, first.arcFromM) + first.headingZ * thisRailHeadingZ(firstRail, first.arcFromM) > 0
				: true;
			final Position entry = nodeAtEndpoint(firstRail, travelsUpward ? first.arcFromM : first.arcToM);
			final Position exitOfFirst = nodeAtEndpoint(firstRail, travelsUpward ? first.arcToM : first.arcFromM);
			if (entry != null) {
				keys.add(MmtrJunctionState.nodeKey(entry));
			}
			if (first == last && exitOfFirst != null) {
				keys.add(MmtrJunctionState.nodeKey(exitOfFirst));
			}
		}
		if (last != first && lastRail != null) {
			final boolean travelsUpward = last.headingX * thisRailHeadingX(lastRail, last.arcFromM) + last.headingZ * thisRailHeadingZ(lastRail, last.arcFromM) > 0;
			final Position exit = nodeAtEndpoint(lastRail, travelsUpward ? last.arcToM : last.arcFromM);
			if (exit != null) {
				keys.add(MmtrJunctionState.nodeKey(exit));
			}
		}
		return keys;
	}

	private static double thisRailHeadingX(Rail rail, double arcM) {
		return headingAt(rail, arcM)[0];
	}

	private static double thisRailHeadingZ(Rail rail, double arcM) {
		return headingAt(rail, arcM)[1];
	}

	/** The end node of {@code rail} nearest to {@code arcM} (arc space is ordered-position-1). */
	private static @Nullable Position nodeAtEndpoint(Rail rail, double arcM) {
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return null;
		}
		return arcM <= rail.railMath.getLength() / 2 ? ordered[0] : ordered[1];
	}

	/**
	 * S4 (显示层, observable before it is wired): what every lamp would show under the v2 rule.
	 *
	 * <p>Each lamp owns exactly one section, so its display is the occupancy depth of the chain that
	 * STOPS AT IT: 1 = its own section is occupied (red), 2 = the next section is (single yellow), 3 = the
	 * one after that (double yellow), 0 = clear (green). This is the v2 counterpart of
	 * {@code MmtrSignalAspect}: the same red/single/double convention, but the unit is the lamp-to-lamp
	 * section, so a train standing three rails ahead inside the same section reads red here instead of
	 * "three blocks away".</p>
	 */
	public ObjectArrayList<String> describeLampAspects(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final ObjectArrayList<String> keys = new ObjectArrayList<>(sectionsBySignal.keySet());
		keys.sort(String::compareTo);
		for (final String key : keys) {
			for (final Section section : sectionsBySignal.get(key)) {
				final String aspect = aspectName(depthAt(section, trees, restrictedNodes));
				final Section next = following(section);
				out.add("[blocks-v2] 灯 " + key + " → " + aspect + "（区间 " + section.spans.size() + " 段/长="
					+ round(section.lengthM()) + "，后继=" + (next == null ? "无" : next.id) + "）");
			}
		}
		return out;
	}

	/**
	 * S4 (客户端镜像): every lamp's v2 display, keyed by the lamp's {@code x,y,z} registry key.
	 *
	 * <p>The ENGINE hands the client its conclusion rather than the raw walk: the client renders per lamp
	 * block and can look its own key up, so the two sides cannot disagree and the client needs no copy of
	 * the section walk. Values are the aspect names the engine uses everywhere else
	 * ({@code RED} / {@code SINGLE_YELLOW} / {@code DOUBLE_YELLOW} / {@code GREEN}).</p>
	 */
	public Object2ObjectOpenHashMap<String, String> lampAspectNames(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		refresh();
		final Object2ObjectOpenHashMap<String, String> out = new Object2ObjectOpenHashMap<>();
		for (final Map.Entry<String, ObjectArrayList<Section>> entry : sectionsBySignal.entrySet()) {
			// 一灯多腿：取**最不利**的那条（红 > 双黄 > 单黄 > 绿）—— 信号不能因为另一条腿通畅就放行
			int worst = 0;
			boolean anyRoute = false;
			for (final Section section : entry.getValue()) {
				if (section.blockedAtDeparture) {
					// 这条腿在灯所在节点就被道岔切断了 —— 它压根不是一条进路（但仍是这盏灯守的轨）
					continue;
				}
				anyRoute = true;
				worst = Math.max(worst, aspectSeverity(depthAt(section, trees, restrictedNodes)));
			}
			// 一条能走的进路都没有（腿全被切断，或只有被切断的腿）⇒ 红：没有进路就是危险
			out.put(entry.getKey(), aspectName(severityToDepth(anyRoute ? worst : 4)));
		}
		return out;
	}

	/** 显示层公开的深度：{@code signal why} 用它，免得诊断自己再写一份（那份会漏掉岔口多支）。 */
	public int aspectDepthFor(@Nullable Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		return depthAt(section, trees, restrictedNodes);
	}

	/** 显示层的严重度序：红(4) > 双黄(3) > 单黄(2) > 绿(1)；depth 0（全清）当作绿。 */
	private static int aspectSeverity(int depth) {
		return depth == 1 ? 4 : depth == 2 ? 2 : depth == 3 ? 3 : 1;
	}

	/** 严重度还原成 depth（给 {@link #aspectName} 用）。 */
	private static int severityToDepth(int severity) {
		return severity == 4 ? 1 : severity == 3 ? 3 : severity == 2 ? 2 : 0;
	}

	/**
	 * The chain depth of the lamp owning {@code section}: 1 red / 2 single / 3 double / 0 clear.
	 *
	 * <h3>按层推进，岔口取最不利</h3>
	 * <p>从本段出发，逐层看"这一段里有没有占用"：第 1 层占用 = 红，第 2 层 = 单黄，第 3 层 = 双黄，
	 * 三层都空 = 绿。岔口上第 N 层可能是**多段**（{@link #followings}），只要其中**任何一段**占用，
	 * 这一层就算命中 —— 信号不能因为另一条岔路通畅就放行（用户 2026-09-10 对岔口的裁定：
	 * "整个咽喉都是闭塞"，显示层取最不利）。</p>
	 *
	 * <p>原实现只沿**单条**链走（follow 取第一支），于是从支线走过来的灯看不到岔口另一支上的车：
	 * 实测 {@code -10,-59,-154} 的链在咽喉 (-67,-139) 只接了朝北那支（空、尽头），
	 * 漏掉朝南那支（{@code -70,-59,-139} 的区间，停着车）—— 该双黄却读绿。</p>
	 */
	private int depthAt(@Nullable Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		if (section == null) {
			return 0;
		}
		ObjectArrayList<Section> frontier = ObjectArrayList.of(section);
		final ObjectOpenHashSet<String> seen = new ObjectOpenHashSet<>();
		for (int level = 1; level <= 3 && !frontier.isEmpty(); level++) {
			boolean hit = false;
			for (final Section walk : frontier) {
				// 撞在道岔禁行侧的区间 = 没有进路 ⇒ 这一层就命中（红）。它比"占用"更硬：
				// 占用会走掉，禁行侧要人来扳道岔（用户 2026-09-13 的规格）。
				if (walk.blockedAtArrival || isOccupied(walk, trees)) {
					hit = true;
					break;
				}
				for (final String nodeKey : boundaryNodeKeys(walk)) {
					if (restrictedNodes.test(nodeKey)) {
						hit = true;
						break;
					}
				}
				if (hit) {
					break;
				}
			}
			if (hit) {
				return level;
			}
			final ObjectArrayList<Section> next = new ObjectArrayList<>();
			for (final Section walk : frontier) {
				for (final Section followed : followings(walk)) {
					// 同一段可能在两条岔路上都出现（菱形），按 id 去重，免得把层数算错
					if (seen.add(followed.id)) {
						next.add(followed);
					}
				}
			}
			frontier = next;
		}
		return 0;
	}

	private static String aspectName(int depth) {
		return depth == 1 ? "RED" : depth == 2 ? "SINGLE_YELLOW" : depth == 3 ? "DOUBLE_YELLOW" : "GREEN";
	}

	/**
	 * 占用转储 (operator diagnostic): every vehicle footprint recorded on {@code railHex} in the live
	 * occupancy trees, as {@code [vehicleId] arcFrom..arcTo}. This is what a "blocked ahead" hold is
	 * actually reading, so it is the first thing to look at when a train refuses to move - and it shows
	 * whose id owns each footprint, which is how a train being held by ITS OWN shadow is spotted.
	 */
	public ObjectArrayList<String> describeOccupancy(String railHex) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final Rail rail = railByHex.get(railHex);
		if (rail == null) {
			out.add("[occ] 找不到轨 " + railHex);
			return out;
		}
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		if (trees == null) {
			out.add("[occ] 当前没有占用树（simulator 未 sync？）");
			return out;
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		out.add("[occ] 轨 " + shortHex(railHex) + " 长=" + round(rail.railMath.getLength()) + " 树=" + trees.size());
		for (int i = 0; i < trees.size(); i++) {
			final VehiclePosition vehiclePosition = footprintOn(trees.get(i), ordered);
			if (vehiclePosition == null) {
				continue;
			}
			for (final double[] segment : vehiclePosition.segmentsExcluding(Long.MIN_VALUE)) {
				out.add("[occ]   树" + i + " 区间 [" + round(segment[0]) + ", " + round(segment[1]) + ")");
			}
			// The per-footprint ids: this is how a train held by ITS OWN shadow is told apart from one
			// held by a genuinely different vehicle.
			for (final long footprintId : vehiclePosition.footprintIds()) {
				out.add("[occ]   树" + i + " 占用者 id=" + footprintId);
			}
		}
		if (out.size() == 1) {
			out.add("[occ]   该轨上没有外部占用（占用树里没有它）");
		}
		return out;
	}

	/**
	 * WEB 区间图层: one entry per directional section, shaped for the management console's map.
	 *
	 * <p>Each section is what one lamp protects, walked lamp to lamp, so the console can draw the block
	 * boundaries the engine actually uses - including the ones that cross rail ends, which no per-rail
	 * view can show. Spans carry the rail and the arc window so the front end can project them onto the
	 * drawn map (a span may be a PART of a rail when a lamp splits it mid-rail).</p>
	 *
	 * @param trees occupancy trees the "occupied" flag is computed against (null = the live trees)
	 */
	public ObjectArrayList<SectionView> sectionViews(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		refresh();
		final ObjectArrayList<SectionView> out = new ObjectArrayList<>();
		final ObjectArrayList<String> keys = new ObjectArrayList<>(sectionsBySignal.keySet());
		keys.sort(String::compareTo);
		for (final String key : keys) {
			for (final Section section : sectionsBySignal.get(key)) {
				final Section next = following(section);
				out.add(new SectionView(
					section.id,
					section.entrySignalKey == null ? "" : section.entrySignalKey,
					section.exitSignalKey == null ? "" : section.exitSignalKey,
					next == null ? "" : next.id,
					aspectName(depthAt(section, trees, restrictedNodes)),
					isOccupied(section, trees),
					section.lengthM(),
					directionOf(section),
					section.spans
				));
			}
		}
		return out;
	}

	/**
	 * The travel direction a section belongs to, as a labelled heading.
	 *
	 * <p>Every section <em>is</em> a direction: it is the stretch of line between one lamp and the next lamp
	 * facing the same way, so "which direction does this section serve" is a property of the section, not
	 * something a reader should infer from the rails it happens to cover. The web layer draws one band per
	 * direction, so it needs this as data.</p>
	 */
	public static final class Direction {
		/** MTR facing degrees: 0 = south (+z), 90 = west (−x), 180 = north (−z), 270 = east (+x). */
		public final double angle;
		public final double dx;
		public final double dz;

		Direction(double angle, double dx, double dz) {
			this.angle = angle;
			this.dx = dx;
			this.dz = dz;
		}

		/** 中文方向名（南北东西按 MTR 的角约定）。 */
		public String label() {
			if (Math.abs(dz) >= Math.abs(dx)) {
				return dz > 0 ? "南行" : "北行";
			}
			return dx > 0 ? "东行" : "西行";
		}
	}

	/**
	 * 把"行进单位向量"换回 MTR 的 Facing 角度 —— 与读灯那份 {@link #headingOf} 严格互逆。
	 *
	 * <p>{@code headingOf(a) = (-sin a, cos a)}，所以 {@code a = atan2(-dx, dz)}；归一化到
	 * {@code [0,360)} 之后与 {@code SignalEntry.angle} 同一套数（现场实测：0 = 南行、180 = 北行，
	 * 与 {@code mmtr-signals} 报的角度逐盏对得上）。</p>
	 */
	public static double angleOfHeading(double headingX, double headingZ) {
		double degrees = Math.toDegrees(Math.atan2(-headingX, headingZ));
		if (degrees < 0) {
			degrees += 360;
		}
		return Math.round(degrees * 10) / 10.0;
	}

	/** One section as the web console consumes it (see {@link #sectionViews}). */
	public static final class SectionView {
		public final String id;
		/**
		 * 本区间的**入口灯**（开这个区间的那盏灯）。区间 id 在"一灯多腿"时会带 {@code #n} 后缀，
		 * 所以入口灯要单独给一份：前端画分界点靠它，不该去拆 id 的字符串。
		 */
		public final String entrySignalKey;
		public final String exitSignalKey;
		public final String nextSectionId;
		public final String aspect;
		public final boolean occupied;
		public final double lengthM;
		/** 本区间服务哪个行车方向（区间 = 某方向的一段路，见 {@link Direction}）。 */
		public final Direction direction;
		public final ObjectArrayList<RailSpan> spans;

		SectionView(String id, String entrySignalKey, String exitSignalKey, String nextSectionId, String aspect, boolean occupied, double lengthM, Direction direction, ObjectArrayList<RailSpan> spans) {
			this.id = id;
			this.entrySignalKey = entrySignalKey;
			this.exitSignalKey = exitSignalKey;
			this.nextSectionId = nextSectionId;
			this.aspect = aspect;
			this.occupied = occupied;
			this.lengthM = lengthM;
			this.direction = direction;
			this.spans = spans;
		}
	}

	/**
	 * 本区间的行车方向：取**第一段**的行进朝向（区间内所有 span 必须同向，那是"走行不许掉头"的要求；
	 * 这里是读出来给显示层用，不是去猜）。
	 */
	private static Direction directionOf(Section section) {
		for (final RailSpan span : section.spans) {
			if (Math.abs(span.headingX) > 1e-9 || Math.abs(span.headingZ) > 1e-9) {
				return new Direction(angleOfHeading(span.headingX, span.headingZ), span.headingX, span.headingZ);
			}
		}
		return new Direction(0, 0, 1);
	}

	// ---------------------------------------------------------------- 水闸区间 (S6)

	/** One 水闸区间: a stretch of line bounded by the lamps that face INTO it. */
	public static final class GateBlock {
		/**
		 * The lamp that opens this block (the one facing into it); empty for a block that no lamp guards
		 * (the end of the line, a plain siding).
		 */
		public final String entryLampKey;
		/**
		 * A stable id unique across the layer: the entry lamp's key, or {@code 无灯#<rail>|<arc>} for an
		 * unguarded block. Names must be unique because the node assignment and the map colours key on them -
		 * "no lamp" is a property of several blocks at once, not one shared name for all of them.
		 */
		public final String id;
		/** True when the walk ran out (dead end) instead of closing on the next lamp. */
		public final boolean endsOpen;
		public final ObjectArrayList<RailSpan> spans = new ObjectArrayList<>();

		GateBlock(String id, String entryLampKey, boolean endsOpen) {
			this.id = id;
			this.entryLampKey = entryLampKey;
			this.endsOpen = endsOpen;
		}

		GateBlock(String id, String entryLampKey, boolean endsOpen, ObjectArrayList<RailSpan> spans) {
			this(id, entryLampKey, endsOpen);
			this.spans.addAll(spans);
		}

		public double lengthM() {
			double length = 0;
			for (final RailSpan span : spans) {
				length += span.lengthM();
			}
			return length;
		}

		@Override
		public String toString() {
			return (entryLampKey.isEmpty() ? "（无灯 " + id + "）" : entryLampKey) + (endsOpen ? " → 开放端" : " → 下一盏灯")
				+ " 跨 " + spans.size() + " 段 长=" + Math.round(lengthM() * 10) / 10.0;
		}
	}

	/** How far a lamp's walk reaches in one travel direction, in arc order along that direction. */
	private static final class DirectionalReach {
		final String lampKey;
		/** The travel direction every arc in {@code spans} is measured along. */
		final double headingX;
		final double headingZ;
		final boolean endsOpen;
		/** Rail hex -> the arc window of this rail the reach covers, normalised to increasing arc. */
		final Object2ObjectOpenHashMap<String, double[]> windows = new Object2ObjectOpenHashMap<>();

		DirectionalReach(String lampKey, double headingX, double headingZ, boolean endsOpen) {
			this.lampKey = lampKey;
			this.headingX = headingX;
			this.headingZ = headingZ;
			this.endsOpen = endsOpen;
		}

		/** Widen the window this reach covers on a rail (a reach passes a rail once per direction). */
		void add(RailSpan span) {
			final double low = Math.min(span.arcFromM, span.arcToM);
			final double high = Math.max(span.arcFromM, span.arcToM);
			final double[] existing = windows.get(span.railHex);
			if (existing == null) {
				windows.put(span.railHex, new double[]{low, high});
			} else {
				existing[0] = Math.min(existing[0], low);
				existing[1] = Math.max(existing[1], high);
			}
		}
	}

	/**
	 * 水闸区间 (user definition, 2026-09-10; **unique assignment added 2026-09-11**): the block layer is a
	 * clean division of the line - every piece of track AND every track-layer node belongs to exactly ONE
	 * block.
	 *
	 * <p>The rule, in the user's words: a signal is a <em>water gate</em>. A block is closed when every one
	 * of its exits has a lamp facing INTO it; a lamp facing out of it belongs to the NEXT block; a stretch
	 * with no lamp (the end of the line, a plain siding) forms a block by itself. Nodes and turnouts do NOT
	 * cut blocks - they belong to the TRACK layer, which the map's rail/turnout layers already show.</p>
	 *
	 * <p>This matches real practice: a block section is a track-circuit section and the signal stands at its
	 * ENTRANCE, so block boundaries are signal positions and "one section, one train" is what the section is
	 * FOR (see 参考-英铁AWS与TPWS机制 §4).</p>
	 *
	 * <h2>Why the assignment needs a rule, and which one</h2>
	 *
	 * <p>Each lamp's own walk is only its REACH: at a ladder throat several lamps face into the same shared
	 * rails, so those reaches contain each other (measured on the dev world: 287 overlapping pairs over 134
	 * rails). To make the layer an actual division, every position is assigned to the <strong>nearest lamp
	 * UPSTREAM of it</strong> - the first lamp a movement standing there would have had to pass - which is
	 * exactly what the driver sees as "this is my block". Blocks are therefore the reaches CLIPPED where a
	 * nearer lamp takes over, and the clipped pieces still tile the line end to end.</p>
	 *
	 * <p>Facing the opposite way is a different movement, so it may well be a different block; that is the
	 * "directed" half of the model and not an overlap.</p>
	 */
	public ObjectArrayList<GateBlock> gateBlocks() {
		refresh();
		return computeGateBlocks();
	}

	private ObjectArrayList<GateBlock> computeGateBlocks() {
		// Lamp -> the block id the console uses (a lamp-less block is numbered by its order of appearance).
		final ObjectArrayList<GateBlock> out = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<String, GateBlock> blocksByLamp = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, DirectionalReach> increasing = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, DirectionalReach> decreasing = new Object2ObjectOpenHashMap<>();
		for (final Map.Entry<String, ObjectArrayList<Section>> entry : sectionsBySignal.entrySet()) {
			for (final Section walk : entry.getValue()) {
				if (walk.spans.isEmpty()) {
					continue;
				}
				addReaches(entry.getKey(), walk, increasing, decreasing);
			}
		}

		final ReachIndex upIndex = new ReachIndex(true);
		final ReachIndex downIndex = new ReachIndex(false);
		increasing.values().forEach(upIndex::add);
		decreasing.values().forEach(downIndex::add);
		final Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>>[] byDirection = newDirectionArrays(upIndex, downIndex);
		// One pass per rail HEX: the world may hold two rail entities with the same endpoints (the hex id IS
		// the endpoints), and walking both would emit the same track twice - the overlap the layer must not
		// have. Every other part of the engine keys rails by hex too, so one pass is the consistent reading.
		final ObjectOpenHashSet<String> emitted = new ObjectOpenHashSet<>();
		for (final Rail rail : simulator.rails) {
			final String hex = rail.getHexId();
			final double length = rail.railMath.getLength();
			if (length <= 1e-6 || !emitted.add(hex)) {
				continue;
			}
			emitRail(rail, hex, length, byDirection, blocksByLamp, out);
		}
		return out;
	}

	// ---------------------------------------------------------------- the unique assignment

	/**
	 * The rails of one travel direction with every lamp reach that covers them: rail hex -> the reaches on
	 * it, in no particular order (the governing rule picks between them per arc).
	 */
	private static final class ReachIndex {
		final Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>> byRail = new Object2ObjectOpenHashMap<>();
		/** Which way the arc index runs for this direction, so "upstream" and "downstream" mean something. */
		final boolean upward;

		ReachIndex(boolean upward) {
			this.upward = upward;
		}

		void add(DirectionalReach reach) {
			reach.windows.forEach((railHex, window) -> byRail.computeIfAbsent(railHex, ignored -> new ObjectArrayList<>()).add(reach));
		}
	}

	/** Both directions as one pair: [0] = arc-increasing, [1] = arc-decreasing. */
	@SuppressWarnings("unchecked")
	private static Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>>[] newDirectionArrays(ReachIndex upIndex, ReachIndex downIndex) {
		return new Object2ObjectOpenHashMap[]{upIndex.byRail, downIndex.byRail};
	}

	/** The reaches covering one rail, per direction; an empty list when no lamp reaches it from that side. */
	@SuppressWarnings("unchecked")
	private static ObjectArrayList<DirectionalReach>[] reachesOn(@Nullable ObjectArrayList<DirectionalReach> up, @Nullable ObjectArrayList<DirectionalReach> down) {
		return new ObjectArrayList[]{
			up == null ? new ObjectArrayList<DirectionalReach>() : up,
			down == null ? new ObjectArrayList<DirectionalReach>() : down,
		};
	}

	/**
	 * Split one lamp's walk into its per-direction reaches.
	 *
	 * <p><strong>One reach per lamp:</strong> the map is keyed by LAMP, not by rail. Keying by rail made the
	 * second lamp on a rail overwrite the first, so the whole rail silently fell to whichever lamp was
	 * rebuilt last - a two-headed 200 m rail came out as one 200 m block instead of one cell per head.</p>
	 *
	 * <p>A block is walked along ONE travel direction (the lamp either faces up the arc or down it), so
	 * comparing two reaches is only meaningful when they run the same way; the two directions are kept
	 * apart here and the uniqueness rule is applied within each of them.</p>
	 */
	private void addReaches(String lampKey, Section walk, Object2ObjectOpenHashMap<String, DirectionalReach> increasing, Object2ObjectOpenHashMap<String, DirectionalReach> decreasing) {
		final boolean endsOpen = walk.exitSignalKey == null || walk.exitSignalKey.isEmpty();
		for (final RailSpan span : walk.spans) {
			final boolean up = span.arcToM > span.arcFromM;
			final Object2ObjectOpenHashMap<String, DirectionalReach> side = up ? increasing : decreasing;
			DirectionalReach reach = side.get(lampKey);
			if (reach == null) {
				reach = new DirectionalReach(lampKey, span.headingX, span.headingZ, endsOpen);
				side.put(lampKey, reach);
			}
			if (span.lengthM() > 1e-9) {
				reach.add(span);
			}
		}
	}

	/** Cut every rail into the pieces owned by their nearest upstream lamp, in arc order. */
	private void emitRail(Rail rail, String hex, double length, Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>>[] byDirection, Object2ObjectOpenHashMap<String, GateBlock> blocksByLamp, ObjectArrayList<GateBlock> out) {
		final ObjectArrayList<DirectionalReach>[] reaches = reachesOn(byDirection[0].get(hex), byDirection[1].get(hex));
		if (reaches[0].isEmpty() && reaches[1].isEmpty()) {
			// 无信号灯的自己成一个区间: no lamp faces into this rail at all, so it is a block nobody guards.
			// It is NOT merged with its neighbours - merging would need a node, and nodes are not a boundary
			// in this layer - so the whole rail is emitted as one unguarded block.
			final double[] heading = headingAt(rail, 0);
			final ObjectArrayList<RailSpan> spans = new ObjectArrayList<>();
			spans.add(new RailSpan(hex, 0, length, heading[0], heading[1]));
			out.add(new GateBlock(unguardedId(hex, 0), "", true, spans));
			return;
		}

		final java.util.TreeSet<Double> ordered = new java.util.TreeSet<>();
		for (final ObjectArrayList<DirectionalReach> list : reaches) {
			for (final DirectionalReach reach : list) {
				final double[] window = reach.windows.get(hex);
				ordered.add(clamp(window[0], 0, length));
				ordered.add(clamp(window[1], 0, length));
			}
		}
		final ObjectArrayList<Double> boundaries = new ObjectArrayList<>();
		for (final double cut : ordered) {
			if (boundaries.isEmpty() || cut - boundaries.get(boundaries.size() - 1) > MIN_BLOCK_PIECE_M) {
				boundaries.add(cut);
			}
		}
		if (boundaries.isEmpty() || boundaries.get(0) > MIN_BLOCK_PIECE_M) {
			boundaries.add(0, 0.0);
		}
		if (boundaries.get(boundaries.size() - 1) < length - MIN_BLOCK_PIECE_M) {
			boundaries.add(length);
		}

		// Per arc interval, per direction: who owns it. The owner is the nearest lamp upstream that still
		// reaches this far (see governing()), so the cells tile the rail instead of containing each other.
		// A run of intervals with one owner becomes ONE span, so a lamp whose cell is cut in half by a
		// mid-rail head still gets one clean span per piece of track.
		final DirectionalReach[] runningOwner = new DirectionalReach[]{null, null};
		final GateBlock[] runningBlock = new GateBlock[]{null, null};
		final double[] runningFrom = new double[]{0, 0};
		final double[] runningHeadingX = new double[]{0, 0};
		final double[] runningHeadingZ = new double[]{0, 0};
		for (int i = 0; i + 1 < boundaries.size(); i++) {
			final double from = boundaries.get(i);
			final double to = boundaries.get(i + 1);
			final double middle = (from + to) / 2;
			for (int direction = 0; direction < 2; direction++) {
				final DirectionalReach reach = governing(reaches[direction], hex, middle, direction == 0);
				if (reach == runningOwner[direction]) {
					continue; // nobody reaches here, or the same lamp still owns the run
				}
				if (runningOwner[direction] != null) {
					addSpan(runningBlock[direction], new RailSpan(hex, runningFrom[direction], from, runningHeadingX[direction], runningHeadingZ[direction]));
				}
				if (reach == null) {
					runningOwner[direction] = null;
					runningBlock[direction] = null;
					continue;
				}
				runningOwner[direction] = reach;
				runningBlock[direction] = blockFor(reach, blocksByLamp, out);
				runningFrom[direction] = from;
				runningHeadingX[direction] = direction == 0 ? headingAt(rail, middle)[0] : -headingAt(rail, middle)[0];
				runningHeadingZ[direction] = direction == 0 ? headingAt(rail, middle)[1] : -headingAt(rail, middle)[1];
			}
		}
		for (int direction = 0; direction < 2; direction++) {
			if (runningOwner[direction] != null) {
				addSpan(runningBlock[direction], new RailSpan(hex, runningFrom[direction], length, runningHeadingX[direction], runningHeadingZ[direction]));
			}
		}

		// 没有灯照到的弧段自成无灯区间: the tiling can leave a stretch owned by nobody where a lamp's cell
		// begins inside the rail and the rail's far end lies past every window (a stabling road whose entry
		// lamp stands mid-rail, and no other head reaches the tail). The layer must still cover the whole
		// line - a gap is a place where a train would belong to no block at all - so every arc no reach
		// covers gets its own unguarded block.
		final ObjectArrayList<RailSpan> unowned = new ObjectArrayList<>();
		for (int i = 0; i + 1 < boundaries.size(); i++) {
			final double from = boundaries.get(i);
			final double to = boundaries.get(i + 1);
			final double middle = (from + to) / 2;
			final boolean covered = governing(reaches[0], hex, middle, true) != null || governing(reaches[1], hex, middle, false) != null;
			if (covered) {
				continue;
			}
			if (!unowned.isEmpty() && Math.abs(unowned.get(unowned.size() - 1).arcToM - from) < 1e-6
				&& unowned.get(unowned.size() - 1).matchesHeading(headingAt(rail, middle)[0], headingAt(rail, middle)[1])) {
				// Extend the run rather than open a new block: one stretch, one cell.
				final RailSpan last = unowned.remove(unowned.size() - 1);
				unowned.add(new RailSpan(hex, last.arcFromM, to, last.headingX, last.headingZ));
			} else {
				final double[] heading = headingAt(rail, middle);
				unowned.add(new RailSpan(hex, from, to, heading[0], heading[1]));
			}
		}
		if (!unowned.isEmpty()) {
			for (final RailSpan span : unowned) {
				out.add(new GateBlock(unguardedId(hex, span.arcFromM), "", true, ObjectArrayList.of(span)));
			}
		}
	}

	/** The block that a reach belongs to, created on first use and keyed by its entry lamp. */
	private static GateBlock blockFor(DirectionalReach reach, Object2ObjectOpenHashMap<String, GateBlock> blocksByLamp, ObjectArrayList<GateBlock> out) {
		GateBlock block = blocksByLamp.get(reach.lampKey);
		if (block == null) {
			block = new GateBlock(reach.lampKey, reach.lampKey, reach.endsOpen);
			blocksByLamp.put(reach.lampKey, block);
			out.add(block);
		}
		return block;
	}

	/** The unique name of an unguarded block: the rail it starts on, so no two of them share one. */
	private static String unguardedId(String railHex, double arcM) {
		return "无灯#" + railHex + "@" + Math.round(arcM * 10) / 10.0;
	}

	/**
	 * The reach that owns arc {@code arcM} of its rail, or null when no lamp reaches it.
	 *
	 * <p>The owner is the nearest lamp UPSTREAM <em>that actually reaches this far</em>: of the reaches
	 * covering {@code arcM}, the one whose own start is closest to it, i.e. the innermost one. This single
	 * rule settles every case the layer has:</p>
	 *
	 * <ul>
	 * <li>a nearer lamp behind you takes the cell from the one further back, which is what makes the cells
	 * tile the ladder instead of containing each other;</li>
	 * <li>a reach that ENDS before {@code arcM} is ignored rather than winning the stretch past its own end,
	 * which keeps one lamp's cells contiguous (closest-start would otherwise alternate and shred a block);</li>
	 * <li>two lamps meeting nose to nose - one facing east at the rail's middle, one facing west at its far
	 * end - each own their own side, so the inner lamp keeps the cell AHEAD of itself (the outer lamp's
	 * reach stops being the answer there) exactly as the model says it should;</li>
	 * <li>two lamps standing at the same node with the same reach (the four lamps on one 43 m stabling road
	 * in the dev world) are one cell in this layer, not four copies of it.</li>
	 * </ul>
	 *
	 * <p>{@code forward} says which way the arc index runs, so "start" means the end the movement comes
	 * from rather than the smaller index. Ties are broken by the reach's own end and then by the lamp's key,
	 * so the answer never depends on map iteration order.</p>
	 */
	private static @Nullable DirectionalReach governing(ObjectArrayList<DirectionalReach> reaches, @Nullable String railHex, double arcM, boolean forward) {
		if (railHex == null) {
			return null;
		}
		DirectionalReach best = null;
		double bestStart = forward ? -Double.MAX_VALUE : Double.MAX_VALUE;
		double bestEnd = 0;
		String bestLampKey = "";
		for (final DirectionalReach reach : reaches) {
			final double[] window = reach.windows.get(railHex);
			if (window == null || arcM < window[0] - 1e-6 || arcM > window[1] + 1e-6) {
				continue;
			}
			final double start = forward ? window[0] : -window[1];
			final double end = forward ? window[1] : -window[0];
			if (best == null || start > bestStart
				|| (start == bestStart && (end > bestEnd || (end == bestEnd && reach.lampKey.compareTo(bestLampKey) < 0)))) {
				best = reach;
				bestStart = start;
				bestEnd = end;
				bestLampKey = reach.lampKey;
			}
		}
		return best;
	}

	/**
	 * Every TRACK-layer node with the block it belongs to - the user's requirement that each node has
	 * exactly one block (2026-09-10): "每个轨道层每个节点都有且只有一个区间层所属".
	 *
	 * <p>The node is a single physical point, so it needs one canonical direction to be read in: the block
	 * of the movement LEAVING the node along the lexicographically first rail at it. That is deterministic
	 * and independent of which rail the caller happens to be looking at, which is what makes the assignment
	 * checkable ("every node appears exactly once").</p>
	 *
	 * <p>A key mapping to an EMPTY string is one no lamp reaches (an unguarded stretch): the node still
	 * belongs to exactly one block, that block just has no entry lamp.</p>
	 */
	public Object2ObjectOpenHashMap<String, String> nodeOwners() {
		refresh();
		return computeNodeOwners();
	}

	private Object2ObjectOpenHashMap<String, String> computeNodeOwners() {
		final Object2ObjectOpenHashMap<String, String> owners = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<GateBlock> blocks = gateBlocks();
		for (final Map.Entry<Position, Object2ObjectOpenHashMap<Position, Rail>> entry : simulator.positionsToRail.entrySet()) {
			final Position node = entry.getKey();
			final String nodeKey = node.getX() + "," + node.getY() + "," + node.getZ();
			// Canonical reading of the node: the movement leaving it along the rail whose hex sorts first.
			// Deterministic, and independent of which rail the caller happens to be looking at.
			//
			// The node is located by COMPARING the rail's two ends with it rather than by asking the rail for
			// an arc: the dev world holds two rail entities for one endpoint pair, so the instance held by
			// railByHex (and by the section walks) can be a different object from the one in positionsToRail,
			// and the arc lookup then answers NaN for a node that is plainly on it. One node came out with no
			// block at all that way.
			String bestHex = null;
			String bestOwner = "";
			for (final Rail rail : entry.getValue().values()) {
				if (bestHex != null && rail.getHexId().compareTo(bestHex) >= 0) {
					continue;
				}
				final double length = rail.railMath.getLength();
				// Which end of the rail this node is, read from the rail's own ordered ends (the same ordering
				// Rail uses to build its arc space), rather than by identity against a possibly stale instance.
				final Position[] ordered = rail.mmtrOrderedPositions();
				final int comparison = node.compareTo(ordered[0]);
				if (comparison != 0 && node.compareTo(ordered[1]) != 0) {
					continue;
				}
				final boolean nodeAtLowArc = comparison == 0;
				final double nodeArc = nodeAtLowArc ? 0 : length;
				// Leaving the node means walking AWAY from it: up the arc when the node is the low end, down
				// the arc when it is the high end.
				final double sampleArc = clamp(nodeAtLowArc ? 1 : length - 1, 0, length);
				final double[] heading = headingAt(rail, sampleArc);
				final double[] outgoing = nodeAtLowArc ? heading : negate(heading);
				bestHex = rail.getHexId();
				bestOwner = ownerKey(blocks, bestHex, nodeArc, outgoing);
			}
			owners.put(nodeKey, bestOwner);
		}
		return owners;
	}

	/**
	 * The id of the block owning {@code arcM} of {@code railHex} in the given travel direction ("" = none).
	 *
	 * <p>A node sits ON a cell boundary - spans are half-open, so the arc at a cell's far end is not
	 * "inside" that cell by the strict test - and it must still come out with exactly one owner. The order
	 * of preference is: the cell the movement is inside; else the cell that STARTS at the node (the node is
	 * its entrance, so it is the node the driver reads the lamp from); else the cell that ENDS there (the
	 * node is where that cell's movement runs out). The lowest such arc wins the ties, so the answer does
	 * not depend on which direction the neighbouring cells happen to be walked in.</p>
	 */
	private static String ownerKey(ObjectArrayList<GateBlock> blocks, String railHex, double arcM, double[] heading) {
		GateBlock starting = null;
		double startingArc = 0;
		GateBlock ending = null;
		double endingArc = 0;
		for (final GateBlock block : blocks) {
			for (final RailSpan span : block.spans) {
				if (!span.railHex.equals(railHex)) {
					continue;
				}
				if (span.containsArc(arcM) && span.matchesHeading(heading[0], heading[1])) {
					return block.id;
				}
				if (Math.abs(arcM - span.arcFromM) <= NODE_OWNER_TOLERANCE_M && (starting == null || span.arcFromM < startingArc)) {
					starting = block;
					startingArc = span.arcFromM;
				}
				if (Math.abs(arcM - span.arcToM) <= NODE_OWNER_TOLERANCE_M && (ending == null || span.arcToM < endingArc)) {
					ending = block;
					endingArc = span.arcToM;
				}
			}
		}
		if (starting != null) {
			return starting.id;
		}
		return ending == null ? "" : ending.id;
	}

	private static double[] negate(double[] heading) {
		return new double[]{-heading[0], -heading[1]};
	}

	/**
	 * Whether any vehicle footprint stands inside {@code block} (the occupancy half of the layer, so the
	 * map can show which cells are taken right now - 一区段一车).
	 */
	public boolean isOccupied(GateBlock block, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return isOccupied(block, trees, 0);
	}

	/** As above, ignoring the footprints of {@code excludeVehicleId} (a train does not occupy itself). */
	public boolean isOccupied(GateBlock block, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return false;
		}
		for (final RailSpan span : block.spans) {
			final Rail rail = railByHex.get(span.railHex);
			if (rail == null || span.lengthM() <= 1e-9) {
				continue;
			}
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered == null || ordered.length < 2) {
				continue;
			}
			for (int i = 0; i < occupancyTrees.size(); i++) {
				final VehiclePosition vehiclePosition = footprintOn(occupancyTrees.get(i), ordered);
				if (vehiclePosition != null && vehiclePosition.getClosestOverlap(span.arcFromM, span.arcToM, false, excludeVehicleId) >= 0) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The aspect the block's OWN entry lamp shows: depth 0 (clear through the whole block) is green, the
	 * next block occupied is a caution, and the block being occupied is red. Empty when the block has no
	 * entry lamp - nobody guards it, so there is no light to read.
	 */
	public String blockAspect(GateBlock block, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		if (block.entryLampKey.isEmpty()) {
			return "";
		}
		final ObjectArrayList<Section> group = sectionsBySignal.get(block.entryLampKey);
		if (group == null || group.isEmpty()) {
			return "";
		}
		// 一灯多腿：取最不利的一条（与 lampAspectNames 同一条规则）
		int worst = 0;
		boolean anyRoute = false;
		for (final Section section : group) {
			if (section.blockedAtDeparture) {
				continue; // 这条腿被道岔切断 = 不是一条进路（仍旧是守的轨）
			}
			anyRoute = true;
			worst = Math.max(worst, aspectSeverity(depthAt(section, trees, restrictedNodes)));
		}
		return aspectName(severityToDepth(anyRoute ? worst : 4));
	}

	/**
	 * Everything that decides one node's block, as text (diagnostics): the rail the node is read on, where on
	 * it, the direction a movement leaves it, and every span that could claim it.
	 */
	public String describeNodeResolution(Position node) {
		refresh();
		final Object2ObjectOpenHashMap<Position, Rail> rails = simulator.positionsToRail.get(node);
		if (rails == null) {
			return "节点不在 positionsToRail 里（没有轨接在它上面）";
		}
		final ObjectArrayList<GateBlock> blocks = gateBlocks();
		final StringBuilder out = new StringBuilder();
		for (final Rail rail : rails.values()) {
			final Position[] ordered = rail.mmtrOrderedPositions();
			final int low = node.compareTo(ordered[0]);
			final int high = node.compareTo(ordered[1]);
			out.append("\n  候选轨 ").append(rail.getHexId().substring(0, 20)).append(".. len=").append(Math.round(rail.railMath.getLength() * 10) / 10.0)
				.append(" 是低端=").append(low == 0).append(" 是高端=").append(high == 0);
			if (low != 0 && high != 0) {
				out.append("（这个节点不在这根轨的两端）");
				continue;
			}
			final boolean nodeAtLowArc = low == 0;
			final double nodeArc = nodeAtLowArc ? 0 : rail.railMath.getLength();
			final double[] heading = headingAt(rail, clamp(nodeAtLowArc ? 1 : rail.railMath.getLength() - 1, 0, rail.railMath.getLength()));
			final double[] outgoing = nodeAtLowArc ? heading : negate(heading);
			out.append(" 节点弧=").append(Math.round(nodeArc * 10) / 10.0).append(" 离开方向=(")
				.append(Math.round(outgoing[0] * 100) / 100.0).append(",").append(Math.round(outgoing[1] * 100) / 100.0).append(")");
			for (final GateBlock block : blocks) {
				for (final RailSpan span : block.spans) {
					if (span.railHex.equals(rail.getHexId()) && (span.containsArc(nodeArc) || Math.abs(nodeArc - span.arcFromM) <= NODE_OWNER_TOLERANCE_M || Math.abs(nodeArc - span.arcToM) <= NODE_OWNER_TOLERANCE_M)) {
						out.append("\n    区间 ").append(block.id).append(" 弧[").append(Math.round(span.arcFromM * 10) / 10.0).append(",")
							.append(Math.round(span.arcToM * 10) / 10.0).append(") 同向=").append(span.matchesHeading(outgoing[0], outgoing[1]));
					}
				}
			}
		}
		return out.toString();
	}

	/** The rail onto which {@code section} continues after {@code railHex} (the next span), or null. */
	public @Nullable String nextRailOf(@Nullable Section section, String railHex) {
		if (section == null) {
			return null;
		}
		for (int i = 0; i < section.spans.size() - 1; i++) {
			if (section.spans.get(i).railHex.equals(railHex)) {
				return section.spans.get(i + 1).railHex;
			}
		}
		return null;
	}

	/**
	 * 灯键 → 它守的区间（诊断/测试用）。
	 *
	 * <p>一灯多腿时值是**多条**。返回的仍是同一份内部表（不拷贝），调用方只读。</p>
	 */
	public Map<String, ObjectArrayList<Section>> allSections() {
		refresh();
		return sectionsBySignal;
	}

	// ---------------------------------------------------------------- build

	/**
	 * Record one walked span.
	 *
	 * <p>A block may name the same rail more than once - a rail that the cell passes twice in the same
	 * direction (a ladder that doubles back), or the same track walked both ways - so the spans are kept as
	 * walked and the feed ships them in order. Dropping "duplicates" here would silently delete the real
	 * track between two runs and open a gap in the layer.</p>
	 */
	private static void addSpan(Section section, RailSpan span) {
		section.spans.add(span);
	}

	/** The same for the block layer: keep every run the tiling produced, in arc order. */
	private static void addSpan(GateBlock block, RailSpan span) {
		block.spans.add(span);
	}

	private void refresh() {
		final String signature = signature();
		if (signature.equals(cachedSignature)) {
			return;
		}
		rebuild();
		cachedSignature = signature;
	}

	private void rebuild() {
		railByHex.clear();
		sectionsBySignal.clear();
		sectionsByRail.clear();
		followingBySection.clear();
		guardedHeadingsByLamp.clear();
		simulator.rails.forEach(rail -> railByHex.put(rail.getHexId(), rail));

		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final String key = MmtrSignalRegistry.key(entry.x, entry.y, entry.z);
			if (sectionsBySignal.containsKey(key)) {
				continue;
			}
			/*
			 * 一盏灯**可以守多条腿**（原版 MTR 的口径：凡是"出射方向落在我面朝方向的 90° 扇区里"的轨
			 * 我都守；点选绑定则是人工指定若干根）。每根受保护的轨各成一段区间，
			 * 显示层再取其中最不利的一条 —— 这样"一灯多腿"在下游（区间、链、占用）里不需要特殊分支。
			 */
			final ObjectArrayList<Section> built = new ObjectArrayList<>();
			final ObjectArrayList<ProtectedRail> protectedRails = resolveProtectedRailsInternal(entry);
			for (final ProtectedRail protectedRail : protectedRails) {
				/*
				 * 多腿时每段 id 必须**唯一**，单腿保持就是灯键（日志与现有调用方都不变）。
				 *
				 * <p><b>这里曾用"轨 hex 的前 6 位"做后缀，结果在这个世界里撞车了</b>：dev 世界的轨 hex
				 * 前十几位几乎全是 F（坐标是负数），两段腿的后缀都成了 {@code FFFFFF} ⇒ 同一个
				 * {@code 灯键#FFFFFF} ⇒ {@link #followingBySection} 按 id 索引时把两条链**并成一条**：
				 * 朝正线那条腿于是看到岔股那条腿的占用。实测现象就是用户报的"道岔扳回 0、岔股已被切断，
				 * 这盏灯却还是双黄"（岔股上停着一列车，level 3 占用被算到正线那条腿上）。
				 * 顺序号既唯一又与"第几条腿"一一对应，比截 hex 更可靠。</p>
				 */
				final String sectionId = protectedRails.size() <= 1
					? key
					: key + "#" + (built.size() + 1);
				final Section section = buildSection(sectionId, key, protectedRail);
				if (section.spans.isEmpty()) {
					continue;
				}
				built.add(section);
			}
			if (built.isEmpty()) {
				continue;
			}
			sectionsBySignal.put(key, built);
			for (final Section section : built) {
				for (final RailSpan span : section.spans) {
					sectionsByRail.computeIfAbsent(span.railHex, ignored -> new ObjectArrayList<>()).add(section);
				}
			}
		}

		/*
		 * 把区间连成链：一段区间结束的地方，就是下一段区间开始的地方。
		 *
		 * **岔口是多值的**：走行在岔口跟着每一条朝前的腿走（用户 2026-09-10 的裁定"整个咽喉都是闭塞"），
		 * 所以一段区间的尽头可能同时有几架界灯（`exitSignalKeys`），每架灯各开一段后续区间 —— 全部都要接上。
		 * 只接"最后一架"会让链在岔口被砍成一支：实测 depot 咽喉 (-67,-139)（度=3）上，
		 * 从 -10,-59,-154 走过来的链只接上朝北那支（空，尽头），漏掉朝南那支（停着车）—— 该双黄却读绿。
		 *
		 * 出口灯可能守多条腿，只接它**朝本走行方向**起步的那一段（同一盏灯的其余腿是别的方向），
		 * 那一支由 `guardedHeadings` 保证：能被选为界灯的，守的方向必然与我们一致。
		 */
		// Chain the sections: a section that ended at a lamp is continued by the sections those lamps start.
		for (final ObjectArrayList<Section> group : sectionsBySignal.values()) {
			for (final Section section : group) {
				for (final String exitKey : section.exitSignalKeys) {
					final ObjectArrayList<Section> nextGroup = sectionsBySignal.get(exitKey);
					if (nextGroup == null) {
						continue;
					}
					for (final Section next : nextGroup) {
						if (next != section) {
							followingBySection.computeIfAbsent(section.id, ignored -> new ObjectArrayList<>()).add(next);
						}
					}
				}
			}
		}
		linkedSections = followingBySection.size();
	}

	/** 所有区间（诊断/统计用）：把"一盏灯多段"摊平。 */
	private ObjectArrayList<Section> allSectionsFlat() {
		final ObjectArrayList<Section> out = new ObjectArrayList<>();
		for (final ObjectArrayList<Section> group : sectionsBySignal.values()) {
			out.addAll(group);
		}
		return out;
	}

	/**
	 * 接在 {@code section} 之后的那一段，或者 null。
	 *
	 * <h3>为什么不用"按出口灯的名字查表"</h3>
	 * <p>原来的连法是 {@code sectionsBySignal.get(section.exitSignalKey)} —— 直接、也在简单情形下对，
	 * 但它把"两个事实恰好同名"当成了"两段相邻"。走行在节点上找到的那盏灯，未必就是下一段的起始灯
	 * （同一处可能登记着两格高的灯、灯在隔壁一格），名字对不上链就断了：那盏灯的前方"没有下一个区间"，
	 * 三段链里的单黄/双黄永远读不到。实测 {@code -65,-60,-203} 正是如此 —— 它守的一段确实空着，
	 * 占用的车停在第二段（应当单黄），却因为链断在第一段与第二段之间而报红。</p>
	 *
	 * <h3>为什么也不用"按结束节点查表"</h3>
	 * <p>试过：把"以某节点起步的段"建成一张表再按 {@code endNodeKey} 查。问题是**一个节点上可以起步多段**
	 * （同一处不同轨、不同方向），表里只能留一个；实测就因此把一段毫不相干的区间（另一根轨上的
	 * {@code -36,-60,-157}）接到了本段之后。同一个节点不是同一个方向。</p>
	 *
	 * <h3>现在的判据：沿着**轨**往前走一步，问那根轨上是哪一段</h3>
	 * <p>这就是走行自己在用的判据（{@link #nextLegs} + {@link #sectionAt}）：本段最后一根轨沿行进方向
	 * 到远端节点，从该节点按同一行进方向找下一根轨，再问"这根轨上、从我进入的那一点开始、朝我这个方向，
	 * 是哪一段"。轨 + 弧位置 + 方向三者都一致，才不会接到隔壁轨上去。</p>
	 */
	private @Nullable Section sectionAfter(Section section) {
		if (section.spans.isEmpty()) {
			return null;
		}
		final RailSpan last = section.spans.get(section.spans.size() - 1);
		final Rail lastRail = railByHex.get(last.railHex);
		if (lastRail == null) {
			return null;
		}
		// ① 同一根轨上被下一盏灯切出来的那一段（两段共享一根轨，中间相接）
		final Section midRail = midRailFollower(section);
		if (midRail != null) {
			return midRail;
		}
		// ② 跨到下一根轨：本段的行进方向决定从哪个端点出去
		final double length = lastRail.railMath.getLength();
		final boolean travelsUpward = last.arcFromM <= last.arcToM;
		final double exitArc = travelsUpward ? length : 0;
		if (Math.abs(exitArc - last.arcToM) > 0.5) {
			// 走行没到轨的端点（例如被轨中间的灯截断），没有"下一根轨"可接
			return null;
		}
		final Position node = nodeAtEndpoint(lastRail, last.arcToM);
		if (node == null) {
			return null;
		}
		Section best = null;
		double bestDot = -1;
		for (final Leg leg : nextLegs(node, last.railHex, last.headingX, last.headingZ)) {
			final double arcOfNode = MmtrBlockService.arcOfNode(leg.rail, node);
			if (Double.isNaN(arcOfNode)) {
				continue;
			}
			for (final Section candidate : sectionsOfRail(leg.rail.getHexId())) {
				if (candidate == section || candidate.spans.isEmpty()) {
					continue;
				}
				final RailSpan first = candidate.spans.get(0);
				if (!first.railHex.equals(leg.rail.getHexId())) {
					continue;
				}
				// 进入点必须就是这根轨的节点端（不是在轨中间起步）
				if (Math.abs(first.arcFromM - arcOfNode) > 0.5) {
					continue;
				}
				final double dot = last.headingX * first.headingX + last.headingZ * first.headingZ;
				if (dot > bestDot) {
					bestDot = dot;
					best = candidate;
				}
			}
		}
		return best;
	}

	/** 在同一根轨的中间接在本段之后的段（{@code null} = 没有）。 */
	private @Nullable Section midRailFollower(Section section) {
		if (section.spans.isEmpty()) {
			return null;
		}
		final RailSpan last = section.spans.get(section.spans.size() - 1);
		for (final Section candidate : sectionsByRail.get(last.railHex)) {
			if (candidate == section || candidate.spans.isEmpty()) {
				continue;
			}
			final RailSpan first = candidate.spans.get(0);
			if (!first.railHex.equals(last.railHex)) {
				continue;
			}
			if (Math.abs(first.arcFromM - last.arcToM) > 0.5) {
				continue;
			}
			// 行进方向必须一致：同一根轨上的反方向区间弧坐标是镜像的，只比弧会把反向段接上来
			if (last.headingX * first.headingX + last.headingZ * first.headingZ <= 0.5) {
				continue;
			}
			return candidate;
		}
		return null;
	}

	/** 段的第一根轨从哪个节点起步（诊断与链接都用它）。 */
	private @Nullable String entryNodeKeyOf(Section section) {
		if (section.spans.isEmpty()) {
			return null;
		}
		final RailSpan first = section.spans.get(0);
		final Rail rail = railByHex.get(first.railHex);
		if (rail == null) {
			return null;
		}
		final Position node = nodeAtEndpoint(rail, first.arcFromM);
		return node == null ? null : MmtrJunctionState.nodeKey(node);
	}

	/**
	 * The rail a lamp protects and the direction it authorises - the public entry point, which refreshes
	 * the rail index first so a caller running before any section query (the game-side bind tool) does not
	 * read a stale index.
	 *
	 */
	public @Nullable ProtectedRail resolveProtectedRail(SignalEntry entry) {
		refresh();
		final ObjectArrayList<ProtectedRail> all = resolveProtectedRailInternal(entry);
		return all.isEmpty() ? null : all.get(0);
	}

	/**
	 * **这盏灯守的全部轨**（公开入口，先刷新索引）。
	 *
	 * <p>站在道岔旁的灯，它面朝的那个半平面里有几条腿就守几条 —— 列车无论走哪条岔，闯的都是这盏灯。
	 * 只返回第一条的 {@link #resolveProtectedRail} 是给"只关心一根轨"的老调用方留的兼容入口，
	 * 新代码请用这个。</p>
	 */
	public ObjectArrayList<ProtectedRail> resolveProtectedRails(SignalEntry entry) {
		refresh();
		return resolveProtectedRailInternal(entry);
	}

	/**
	 * 按 hex 找一条轨，**两种端点朝向都认**。
	 *
	 * <h3>为什么必须两种都认</h3>
	 * <p>一条轨的 hex 是「端点1-端点2」拼出来的，而**哪个端点写在前**取决于这条 Rail 是怎么被声明/读出来的：
	 * 同一根实体轨，从 A 到 B 画与从 B 到 A 画会得到两个互为逆序的字符串。</p>
	 *
	 * <p>索引 {@code railByHex} 用的是 {@code rail.getHexId()}（声明顺序），而接口发给网页的是
	 * {@code Geometry.hexId}（原始顺序）—— 两者对同一根轨可能不同。实测：页面点了一根轨、
	 * 把它的 hex 发回来绑定，引擎按 {@code getHexId()} 查表查不到（两个字符串是逆序的），
	 * 于是**绑定静默失败**：接口回 ok、绑定列表却没变（检查脚本正是靠"点中的那条是不是被加进去了"
	 * 抓到了它）。</p>
	 */
	private @Nullable Rail railByEitherOrientation(String hex) {
		if (hex == null || hex.isEmpty()) {
			return null;
		}
		final Rail direct = railByHex.get(hex);
		if (direct != null) {
			return direct;
		}
		final String[] parts = hex.split("-");
		if (parts.length == 6) {
			return railByHex.get(parts[3] + "-" + parts[4] + "-" + parts[5] + "-" + parts[0] + "-" + parts[1] + "-" + parts[2]);
		}
		return null;
	}

	/** 两条轨是不是同一根：两种端点朝向都算同一根（见 {@link #railByEitherOrientation}）。 */
	private static boolean sameRail(@Nullable String a, @Nullable String b) {
		if (a == null || b == null) {
			return false;
		}
		return canonicalHex(a).equals(canonicalHex(b));
	}

	/**
	 * 轨 hex 的**规范形式**：两个端点表示里取字典序小的那个。
	 *
	 * <p>一条轨的 hex 是「端点1-端点2」，而哪个端点写在前取决于这条 Rail 怎么被声明；
	 * 同一根实体轨因此有两个互为逆序的字符串。所有对外的地方（拓扑接口、绑定列表、候选列表）
	 * 都走这个函数，网页拿到的 hex 就永远和引擎存的是同一个 —— 否则"页面点的轨"与
	 * "引擎绑的轨"是两个字符串，绑定会静默失败（实测踩过）。</p>
	 */
	public static String canonicalHex(String hex) {
		if (hex == null) {
			return "";
		}
		final String[] p = hex.split("-");
		if (p.length != 6) {
			return hex;
		}
		final String forward = hex;
		final String backward = p[3] + "-" + p[4] + "-" + p[5] + "-" + p[0] + "-" + p[1] + "-" + p[2];
		return forward.compareTo(backward) <= 0 ? forward : backward;
	}

	/**
	 * 这盏灯**可绑**的候选轨（点选绑定用）：离灯足够近、且"从灯这边出发"的轨。
	 *
	 * <p>给网页点击用的就是这份名单 —— 界面不该自己再算一遍几何（那必然与引擎分叉），
	 * 它只负责把这份名单高亮出来、把你点中的那根回传给 {@code signal bind --rail}。</p>
	 *
	 * <p>在节点旁的灯用原版的 90° 扇区口径（可能多条，道岔上一灯多腿）；不在节点旁的灯
	 * 用"投影 + 前方一段距离的方向"。两者都与推断绑定时一致，所以"高亮的就是能绑的"。</p>
	 */
	public ObjectArrayList<String> candidateRailsOf(SignalEntry entry) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		for (final ProtectedRail protectedRail : resolveCandidateRailsInternal(entry)) {
			// 规范 hex 去重：**必须在规范化之后**去重 —— 同一根轨的两种端点写法规范化后是同一个字符串，
			// 但原始写法不同（实测：候选里同一根轨出现两次，规范化前按 raw hex 去重根本去不掉）。
			final String hex = canonicalHex(protectedRail.rail.getHexId());
			if (!out.contains(hex)) {
				out.add(hex);
			}
		}
		return out;
	}

	/** 候选轨的推断（见 {@link #candidateRailsOf}）：与推断绑定同源，但把"勉强能算"的也列出来供人选择。 */
	private ObjectArrayList<ProtectedRail> resolveCandidateRailsInternal(SignalEntry entry) {
		final ObjectArrayList<ProtectedRail> out = new ObjectArrayList<>();
		/*
		 * 按 hex 在**加入时**去重。
		 *
		 * <p>世界上可以有**两个 Rail 实体共享同一个 hex**（hex 就是两个端点，端点相同即同一条轨）。
		 * 实测：一盏灯的候选里同一个 hex 出现了两次，于是页面上"候选 3 条"实际只画得出 2 条
		 * （两条同 hex 的线完全重叠）。下游（网页高亮、命中区）全是按 hex 索引的，
		 * 所以重复项必须在源头去掉，而不是等最后滤一遍。</p>
		 */
		final ObjectOpenHashSet<String> seen = new ObjectOpenHashSet<>();
		final double lampX = entry.x + 0.5;		final double lampY = entry.y + 0.5;
		final double lampZ = entry.z + 0.5;
		final double[] heading = headingOf(entry.angle);

		final Position node = nearestNode(lampX, lampY, lampZ);
		if (node != null) {
			final double nodeDistance = Math.sqrt(Math.pow(node.getX() + 0.5 - lampX, 2)
				+ Math.pow(node.getY() + 0.5 - lampY, 2) + Math.pow(node.getZ() + 0.5 - lampZ, 2));
			if (nodeDistance <= NODE_BIND_RADIUS_M) {
				// 原版口径：这条轨的出射方向落在我面朝方向的一个象限内
				for (final Leg leg : nextLegs(node, "", heading[0], heading[1])) {
					final double arcAtNode = MmtrBlockService.arcOfNode(leg.rail, node);
					if (Double.isNaN(arcAtNode)) {
						continue;
					}
					final double legLength = leg.rail.railMath.getLength();
					final double[] legHeading = leg.forward
						? headingOver(leg.rail, arcAtNode, legLength)
						: headingOver(leg.rail, arcAtNode, 0);
					if (legHeading[0] * heading[0] + legHeading[1] * heading[1] > -CONE_COS
						&& seen.add(canonicalHex(leg.rail.getHexId()))) {
						out.add(new ProtectedRail(leg.rail, arcAtNode, heading[0], heading[1]));
					}
				}
				if (!out.isEmpty()) {
					return out;
				}
			}
		}

		// 不在节点旁（或节点上一条都不合适）：列出它旁边 6 格内的轨，方向对不上也列 —— 人工指定时可以无视方向
		for (final Rail rail : simulator.rails) {
			final Double arc = MmtrBlockService.projectArc(rail, lampX, lampY, lampZ);
			if (arc == null || rail.railMath.getLength() <= 1e-6) {
				continue;
			}
			if (distanceSq(rail, arc, lampX, lampY, lampZ) <= 36 && seen.add(canonicalHex(rail.getHexId()))) {
				out.add(new ProtectedRail(rail, clamp(arc, 0, rail.railMath.getLength()), heading[0], heading[1]));
			}
		}
		return out;
	}

	/** 一盏灯守的**全部**轨：人工点选绑定优先，否则按几何推断（可能多条）。
	 *
	 * <h3>人工绑定</h3>
	 * <p>{@code entry.rails} 非空时只认它：一根轨一条 {@link ProtectedRail}。方向由**几何**定 ——
	 * 列车必然从"灯所站的那一端"进入，所以 {@code forward = 灯在轨的前半段}，不再拿灯的面朝角去点积。
	 * 这解决了一类死结：灯站在轨的尾端、面朝轨的离开方向时，"按朝向点积"永远判它不该守这条轨，
	 * 于是掉进兜底绑出一条零长区间（这几轮反复踩的就是它）。人工指定之后，方向不再需要猜。</p>
	 *
	 * <h3>推断（未绑定）</h3>
	 * <p>照原版 MTR 的口径（{@code RenderSignalBase.getAspectState}）：灯先归到最近的节点块，
	 * 再取该节点上**出射方向落在面朝方向的 90° 扇区内**的轨 —— 可以同时有多条（道岔上一灯守多腿）。
	 * 不在节点旁的灯才退回"投影到它旁边那条轨"，方向按前方一段距离量（见 {@link #headingAhead}）。</p>
	 */
	private ObjectArrayList<ProtectedRail> resolveProtectedRailsInternal(SignalEntry entry) {
		final ObjectArrayList<ProtectedRail> out = new ObjectArrayList<>();
		final double lampX = entry.x + 0.5;
		final double lampY = entry.y + 0.5;
		final double lampZ = entry.z + 0.5;
		final double[] heading = headingOf(entry.angle);

		// ① 人工点选绑定：只认列出的轨
		if (!entry.rails.isEmpty()) {
			/*
			 * 绑定列表里可能有"认不出来"或"离这盏灯太远"的轨（世界改画过、或木斧分配时点到了
			 * 隔壁那条轨）。这些条目**不能悄悄丢掉**：
			 *   ① 全丢了就等于把这盏灯变成"不参与闭塞"（没颜色），而用户只看到"分配之后颜色就不对了"；
			 *   ② 只丢一部分则更难察觉——灯还会显示颜色，但守的是别的轨。
			 * 所以：逐条记下丢它的原因（给 `signal why` 看），并且**一条都没认出来时退回自动推断**——
			 * 宁可显示一个由几何推出的答案，也比一盏死灯强。
			 */
			final StringBuilder rejected = new StringBuilder();
			for (final String hex : entry.rails) {
				final Rail rail = railByEitherOrientation(hex);
				if (rail == null) {
					rejected.append(" | ").append(shortHex(hex)).append(" 认不出这条轨（世界改画过？）");
					continue;
				}
				final Double arc = MmtrBlockService.projectArc(rail, lampX, lampY, lampZ);
				if (arc == null) {
					rejected.append(" | ").append(shortHex(hex)).append(" 离灯太远、投影不到这条轨上（")
						.append(describeRailEnds(rail)).append("）");
					continue;
				}
				final double length = rail.railMath.getLength();
				if (length <= 1e-6) {
					continue;
				}
				final double clamped = clamp(arc, 0, length);
				// 方向由**灯的朝向**定，不由几何定。
				//
				// 这里曾经写的是 `forward = clamped <= length / 2`——"灯在轨的哪半段，
				// 列车就从哪一端进来"。那是一句纯几何的话，把灯的朝向整个丢掉了。
				//
				// 后果：用木斧手工把一条轨分配给灯之后，区间会从这条轨的**另一头**开始走。
				// 灯于是守着反的那半段，颜色跟着全歪（对着库内的显示绿、对着库外的显示红）。
				// 手工绑定本来就是为了纠正自动推断，结果纠正动作自己被几何覆盖——所以
				// 这个 bug 只在"人工绑定"这条路径上才看得到，自动推断那条反而是对的。
				//
				// 判据用的是全模型唯一的铁律：**灯的朝向与区间走行方向同向**。
				// 现实里司机只有在正对灯面时才看得见这盏灯，所以"迎着灯开过来的列车"必须与灯
				// 朝同一个方向——于是弧是增着走还是减着走，就是朝向与"弧增方向"的点积符号。
				// 自动推断那条路径最终也落在同一条铁律上（它先用节点挑腿，再让腿的方向定 forward），
				// 所以这里不是另立一套，而是把同一句话直接用在"人工指定的那根轨"上。
				//
				// 不能写成"让灯的投影点出现在我前方"之类的话：那会让一辆**正对**灯的列车
				// 反而把灯算在身后（点积为 0 时会随几何乱跳）。
				final Vector arcNear = rail.railMath.getPosition(0, false);
				final Vector arcFar = rail.railMath.getPosition(length, false);
				final double facingDotArc = heading[0] * (arcFar.x() - arcNear.x()) + heading[1] * (arcFar.z() - arcNear.z());
				// 点积为 0（灯正对轨的横向，或轨太短量不出方向）时朝向无从判断，才退回几何：
				// 取长的那一侧——那时这盏灯本来就不该守这根轨，给一段长的总比给一段零长的诚实。
				final boolean forward = Math.abs(facingDotArc) > 1e-9 ? facingDotArc > 0 : clamped <= length / 2;
				out.add(new ProtectedRail(rail, clamped, forward ? 1 : -1, length));
			}
			if (!out.isEmpty()) {
				if (rejected.length() > 0) {
					noteBindReject(entry, "  [人工绑定] 已生效 " + out.size() + " 条；被忽略：" + rejected);
				}
				return out;
			}
			// 一条都没认出来：退回自动推断，别把这盏灯弄成"不参与闭塞"（那样它连颜色都没有）
			noteBindReject(entry, "  [人工绑定] **一条都没生效**（" + entry.rails.size() + " 条全被忽略）：" + rejected
				+ " → 已退回自动推断。多半是分配时点到了离这盏灯很远的另一条轨（或世界改画过），"
				+ "重新分配一次即可。若下面①仍然'没有保护的轨'，那这盏灯就完全不参与闭塞、连颜色都没有。");
			final ObjectArrayList<ProtectedRail> fallback = resolveProtectedRailInternal(entry);
			out.addAll(fallback);
			return out;
		}

		out.addAll(resolveProtectedRailInternal(entry));
		return out;
	}

	/** 轨的两端世界坐标（诊断用：一条轨的形状比它的名字重要得多）。 */	private static String describeRailEnds(Rail rail) {
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return "（无端点）";
		}
		final Position first = ordered[0];
		final Position last = ordered[ordered.length - 1];
		return "(" + first.getX() + "," + first.getY() + "," + first.getZ() + ")→("
			+ last.getX() + "," + last.getY() + "," + last.getZ() + ")";
	}

	/**
	 * 投影点附近**不同步长**量出的轨向（诊断用）。
	 *
	 * <p>存在的理由是一个真问题：轨的采样点是**整数格**，而游戏里一条平滑曲线在采样表里会先横移一格
	 * 再回头（实测 {@code (-67,-203)→(-38,-233)} 的采样是 {@code (-37,-232) → (-39,-232) → (-40,-232)}——
	 * 起点先"反向"了一格）。用相邻一两格算切线，量到的是采样噪声：一条向东的线会被读成向西。
	 * 把不同步长的结果摆在一起，就能一眼看出"这个方向是量出来的还是编出来的"。</p>
	 */
	private static String describeTangents(Rail rail, double arcM) {
		final double length = rail.railMath.getLength();
		final StringBuilder out = new StringBuilder();
		for (final double step : new double[]{1, 2, 4, 8, 16}) {
			final double to = Math.min(length, arcM + step);
			final double from = Math.max(0, arcM - step);
			final double[] ahead = headingOver(rail, arcM, to);
			final double[] behind = headingOver(rail, from, arcM);
			out.append(" 步长").append((int) step).append("m: 向后看=").append(round(behind[0])).append(",").append(round(behind[1]))
				.append(" 向前看=").append(round(ahead[0])).append(",").append(round(ahead[1])).append("；");
		}
		return out.toString();
	}

	/**
	 * 灯选轨的**打分明细**（诊断用）：把候选轨按同一套打分列出来，并标出实际选中的那条。
	 *
	 * <p>为什么需要它：选轨是"朝向 × 距离"的加权打分，权重差一点，选中的就是**另一条轨**，
	 * 而现象只有一句"灯显示得不对"。实测世界里 {@code -38,-60,-235} 那盏灯离它应该守的轨约 1.5 格，
	 * 却绑到了 26 格开外的另一条斜轨上（于是"保护的区间"只有 3 米长，永远读不到前方占用，
	 * 一直显示绿）。没有这张明细，只能靠反推打分公式去猜。</p>
	 *
	 * @return 每行一条候选轨：距离、朝向点积、前方余长、得分、是否选中
	 */
	public ObjectArrayList<String> describeProtectedRailCandidates(SignalEntry entry) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final double lampX = entry.x + 0.5;
		final double lampY = entry.y + 0.5;
		final double lampZ = entry.z + 0.5;
		final double[] heading = headingOf(entry.angle);
		out.add("[选轨] 灯 " + MmtrSignalRegistry.key(entry.x, entry.y, entry.z)
			+ " 朝向角=" + entry.angle + "° → 面朝方向单位向量 (" + round(heading[0]) + ", " + round(heading[1]) + ")"
			+ "（x 正=东，z 正=南；点积 >0.1 才算这条轨朝我要去的方向）");
		final ObjectArrayList<ProtectedRail> chosen = resolveProtectedRailInternal(entry);
		final String chosenHex = chosen.isEmpty() ? "" : chosen.get(0).rail.getHexId();

		// ① 最近的节点，以及从它出发的每一条轨：灯站在道岔上时，"往哪走"的答案全在这里
		final Position node = nearestNode(lampX, lampY, lampZ);
		if (node != null) {
			out.add("[选轨] 最近节点 " + MmtrJunctionState.nodeKey(node) + "（离灯 " + round(Math.sqrt(
				Math.pow(node.getX() + 0.5 - lampX, 2) + Math.pow(node.getY() + 0.5 - lampY, 2) + Math.pow(node.getZ() + 0.5 - lampZ, 2))) + " 格）");
			final Object2ObjectOpenHashMap<Position, Rail> atNode = simulator.positionsToRail.get(node);
			if (atNode != null) {
				for (final Rail rail : atNode.values()) {
					final double arcAtNode = MmtrBlockService.arcOfNode(rail, node);
					if (Double.isNaN(arcAtNode)) {
						continue;
					}
					final double length = rail.railMath.getLength();
					final double[] outgoing = outgoingHeading(rail, arcAtNode, length);
					final double dot = outgoing[0] * heading[0] + outgoing[1] * heading[1];
					out.add("[选轨]   从该节点出发的轨 " + shortHex(rail.getHexId()) + " " + describeRailEnds(rail)
						+ " 出发方向=(" + round(outgoing[0]) + "," + round(outgoing[1]) + ")"
						+ " 与灯的点积=" + round(dot)
						+ " 前方余长=" + round(length - arcAtNode) + " m");
				}
			}
		}

		// ② 所有 6 格内的轨（不管方向对不对都列出来——"为什么没选它"必须看得见）
		for (final Rail rail : simulator.rails) {
			final Double arc = MmtrBlockService.projectArc(rail, lampX, lampY, lampZ);
			if (arc == null) {
				continue;
			}
			final double length = rail.railMath.getLength();
			if (length <= 1e-6) {
				continue;
			}
			final double distanceSq = distanceSq(rail, arc, lampX, lampY, lampZ);
			if (distanceSq > 36) {
				continue;
			}
			final double[] railHeading = headingAhead(rail, arc);
			final double dot = railHeading[0] * heading[0] + railHeading[1] * heading[1];
			final double aheadM = length - arc;
			out.add("[选轨] 6 格内的轨 " + shortHex(rail.getHexId()) + " " + describeRailEnds(rail)
				+ " 长=" + round(length) + " 投影弧=" + round(arc)
				+ " 距离=" + round(Math.sqrt(distanceSq)) + " 格"
				+ " 前方余长=" + round(aheadM) + " m"
				+ " 前方方向=(" + round(railHeading[0]) + "," + round(railHeading[1]) + ") 点积=" + round(dot)
				+ (aheadM < 1.0 ? "（前方无轨，不能绑）" : dot <= 0.1 ? "（方向不对，不能绑）" : "")
				+ (rail.getHexId().equals(chosenHex) ? "  ← **选中**" : ""));
		}
		if (chosen.isEmpty()) {
			out.add("[选轨] 结论：没有选中任何轨 —— 这盏灯**不参与闭塞**：它附近没有一条「从它面前出发」的轨。");
		} else {
			final StringBuilder summary = new StringBuilder("[选轨] 结论：守 " + chosen.size() + " 条轨 —— ");
			for (int i = 0; i < chosen.size(); i++) {
				if (i > 0) {
					summary.append("、");
				}
				summary.append(shortHex(chosen.get(i).rail.getHexId())).append(" 从弧 ").append(round(chosen.get(i).arcM));
			}
			out.add(summary.toString());
		}
		return out;
	}

	/**
	 * 从 {@code nodeArc} 出发、按行向（{@code forward}）还能走多远（米）：余长为 0 表示这条腿
	 * 站在节点上只是"把节点当成了自己的端点"，沿行向一步都走不出去。
	 *
	 * <p>为什么不能用 {@code leg.forward ? length - arc : arc}：那个式子的含义是"沿**弧增**方向还剩多少"，
	 * 而 {@code forward} 说的是**行向**——两者只在"弧增方向恰好就是行向"时才一致。实测 depot {@code test1}
	 * 出库口那盏灯就撞在这上面：库内 stub 是一条弧增指向节点的轨（弧 0 在节点、弧 36 在库里），
	 * 车从节点进库沿**弧增**走，于是 {@code forward=true}，那个式子给出 {@code aheadM = length - arc = 36}…
	 * 而实测这一项给出的是 0 —— 说明"行向"这个标志在节点处根本不可靠（同一条 stub 的两个方向，
	 * 它把走向节点的那个也标成了 forward）。所以这里只把弧长当长度用，方向交给
	 * {@link #outwardFromNode} 用几何实测。</p>
	 */
	private static double remainingM(Rail rail, double nodeArc) {
		final double length = rail.railMath.getLength();
		final double arc = clamp(nodeArc, 0, length);
		// 节点落在弧空间哪一端，就从另一端算余长；正好在中点则取长的一侧
		return Math.max(length - arc, arc);
	}

	/** 从节点朝轨外走的方向与距离（几何实测，不用行向标志）。 */
	private static final class Outward {
		final double headingX;
		final double headingZ;
		final double distanceM;

		Outward(double headingX, double headingZ, double distanceM) {
			this.headingX = headingX;
			this.headingZ = headingZ;
			this.distanceM = distanceM;
		}
	}

	/**
	 * 一条腿**从节点朝外**的方向与可走距离（米），按 6 m 基线的实测位移量出（见 {@link #headingOver}）。
	 *
	 * <p>为什么必须实测、不能读 {@code Leg.forward}：那个标志是"行向"（列车沿弧增还是弧减走），
	 * 而选腿要问的是"这条轨从节点往哪边延伸"。实测 depot {@code test1} 的库内 stub 上两者正好相反，
	 * 于是"前方余长"被算成 0，这条有 36 m 的腿被整条丢掉，灯改去守一条主线腿 ——
	 * 库里停着车那盏灯却一直绿，从外面看只是"颜色不对"。</p>
	 */
	private static Outward outwardFromNode(Rail rail, double nodeArc) {
		final double length = rail.railMath.getLength();
		final double arc = clamp(nodeArc, 0, length);
		// headingOver 会把弧夹到 [0,length]，所以"点被夹住了"正好等价于"这个方向走不出去"
		final double[] up = headingOver(rail, arc, arc + HEADING_BASELINE_M);
		final double[] down = headingOver(rail, arc, arc - HEADING_BASELINE_M);
		final double upM = Math.hypot(up[0], up[1]) < 1e-9 ? 0 : 1;
		final double downM = Math.hypot(down[0], down[1]) < 1e-9 ? 0 : 1;
		if (upM == 0 && downM == 0) {
			return new Outward(0, 0, 0);
		}
		// 方向取能走出去的那一侧；两侧都能走（节点在轨中间）时取向长的一侧
		final boolean towardUpArc = upM > 0 && (downM == 0 || length - arc >= arc);
		final double[] heading = towardUpArc ? up : down;
		final double distanceM = towardUpArc ? length - arc : arc;
		return new Outward(heading[0], heading[1], distanceM);
	}

	/**
	 * 一盏灯在道岔处该守哪条腿：**在节点的几条腿里，选灯面朝得最正的那条**（既有判据，一直有效）。
	 *
	 * <p>2026-09-12 用户纠正了模型方向："正常信号显示不应该以道岔左右来定，是以道岔后面状态就行了吧"，
	 * 即颜色应当跟着**道岔当前开通方向**走。这个函数现在只在最后附上"这个道岔开通了 leg 几"的观察值，
	 * **还不驱动选腿** —— 因为"进向轨怎么定"在灯就站在节点旁时判不准（实测灯距节点 2 格，
	 * 位置上对每条腿几乎一样"朝"），而在没定准进向之前改选腿，一次就掀翻了 17 条既有测试。
	 * 先把观察值暴露给 signal way / signal why，定了进向判据再让它决策。</p>
	 */
	private ObjectArrayList<Leg> resolveLegByPointState(Position nodeOnLamp, double lampX, double lampZ, double[] heading, StringBuilder legTrace, int[] legCountOut) {
		/*
		 * 两轮候选：**先只要与灯朝向同向的腿**（灯守的就是它面朝的那一侧），
		 * 一条都没有时退回全部腿。
		 *
		 * <h3>为什么"同向优先，没有则退回"</h3>
		 * <p>实测锚点（用户 2026-09-13 给的基准，出库口 {@code -70,-59,-139}）：那盏灯**角 0、对着 north 亮**，
		 * 显示的是 depot 区间状态（红色有车）。角 0 经 {@code headingOf} 得 {@code (0,-1)} = −z = 北，
		 * 与"对着 north"完全一致 —— 所以**符号是对的**；而北边正是进库 stub
		 * {@code (-67,-139)→(-67,-103)}。可引擎原来挑中了向东北的环线：因为`nextLegs` 收的是
		 * **同向腿**（走行侧），但打分用的是"端点距离 + 点积"，而"前方余长"这一项把进库 stub 判成了
		 * 0（节点在它的远端）、整条丢掉。</p>
		 *
		 * <p>所以规则收敛成两句：**灯守它面朝那一侧的腿**（同向优先）；**一条同向腿都没有时，
		 * 才按原来的走行侧挑**（单腿节点上那条轨可能与灯朝向相反，硬要"守侧"就会把该守的丢掉 ——
		 * 实测过：那样会有 15 条测试变成"灯连区间都建不出来"）。</p>
		 */
		final ObjectArrayList<Leg> facingLegs = new ObjectArrayList<>();
		final ObjectArrayList<Leg> allLegs = nextLegs(nodeOnLamp, "", heading[0], heading[1], false);
		/*
		 * 灯的方位（节点 → 灯），以及"这条腿从节点朝哪边延伸"。
		 *
		 * <p><b>腿的方向用几何硬事实</b>：就是"从节点指向这条轨的**另一个端点**"（`Leg.farEnd`）。
		 * 不用弧空间量（`outwardFromNode`）：实测锚点那个节点上，弧空间量出来的进库 stub 外向是
		 * {@code (0,+1)}（朝南），而它物理上明明是朝北（节点的另一个端点在 z=-103，比节点大）。
		 * 几何硬事实不会骗人，弧空间的测量口径会。</p>
		 *
		 * <p>判据组合成两句，缺一不可：</p>
		 * <ol>
		 *   <li><b>背离灯</b>（{@code 腿方向 · 节点→灯 < 0}）：灯立在节点旁边时，节点上可能有两条
		 *       朝同一方向的腿 —— 一条从节点伸出去、另一条朝灯这边伸过来。只有**背离灯**的那条
		 *       才是"从灯面前延伸出去的路"（锚点：进库 stub 背离灯、主线朝灯）。</li>
		 *   <li><b>与灯朝向同向</b>（点积 &gt; 0.1）：灯守的是它**对着**的那条腿。</li>
		 * </ol>
		 */
		final double toLampX = lampX - (nodeOnLamp.getX() + 0.5);
		final double toLampZ = lampZ - (nodeOnLamp.getZ() + 0.5);
		final double toLampNorm = Math.hypot(toLampX, toLampZ);
		legTrace.append(" [几何] 节点=(").append(nodeOnLamp.getX()).append(",").append(nodeOnLamp.getY()).append(",").append(nodeOnLamp.getZ())
			.append(") 灯=(").append(round(lampX)).append(",").append(round(lampZ)).append(")")
			.append(" 节点→灯=(").append(round(toLampX)).append(",").append(round(toLampZ)).append(")")
			/*
			 * 把"节点的邻接轨"与"进来的腿"的**条数**都对一下。
			 *
			 * <p>这两个数一旦不等，缺的那条腿就永远不会被守到 —— 而现象只是"灯色不对"。
			 * 实测锚点：节点度=4（`query node` 直读）而腿只有 3 条，缺的正是车停着的那条 stub。</p>
			 */
			.append(" 节点邻轨=").append(neighboursOf(nodeOnLamp).size())
			.append(" 传入腿=").append(allLegs.size());
		for (final Leg leg : allLegs) {
			final double[] away = awayFromNode(leg, nodeOnLamp);
			if (away == null) {
				continue;
			}
			final double facingDot = away[0] * heading[0] + away[1] * heading[1];
			legTrace.append(" | ").append(shortHex(leg.rail.getHexId()))
				.append(" 指向=(").append(round(away[0])).append(",").append(round(away[1])).append(")")
				.append(" 远端=(").append(leg.farEnd == null ? "?" : String.valueOf(leg.farEnd.getX())).append(",")
				.append(leg.farEnd == null ? "?" : String.valueOf(leg.farEnd.getZ())).append(")")
				.append(" 与朝向点积=").append(round(facingDot));
			/*
			 * **灯管的是它 FACING 那一侧**（点积 > 0）——也就是司机迎着灯面开过来的那一侧。
			 *
			 * <p>用户 2026-09-13 的两条判据（原话）：</p>
			 * <ol>
			 *   <li>锚点 {@code -70,-59,-139}（角 0）"它发光面是朝北的，它接管的是 {@code -67,-139} 到
			 *       {@code -67,-103} 这段" —— 那段是从道岔**往南**出去的，而 FACING=南 ✓；</li>
			 *   <li>{@code -10,-59,-160}（角 90）"灯光朝向东，保护向西的铁轨" —— FACING=西 ✓。</li>
			 * </ol>
			 *
			 * <p>也就是说：{@code heading} 给的就是 **FACING 方向 = 它管的那一侧**（灯面在它的反面，
			 * 所以肉眼看是"灯朝北、管南"）。取腿部方向与它同向（点积 &gt; 0.1）即"这条腿延伸向灯管的那一侧"。
			 * 老实现曾按"与朝向相反"取（那是把 {@code heading} 当成了灯面），东西向的灯因此整片守反
			 * —— 见 {@link #headingOf} 的说明。</p>
			 */
			if (facingDot > 0.1) {
				facingLegs.add(leg);
			}
		}
		final ObjectArrayList<Leg> candidates = facingLegs.isEmpty() ? allLegs : facingLegs;
		legTrace.append(facingLegs.isEmpty()
			? "（没有落在 FACING 那一侧的腿 → 退回全部腿）"
			: "（灯管的那一侧有 " + facingLegs.size() + " 条腿，都要守）");

		/*
		 * **灯朝向的"对面"那半平面里的腿全都要守**，一条都不许丢。
		 *
		 * <p>两条判据都是用户 2026-09-13 给的，而且互相印证：</p>
		 * <ol>
		 *   <li>「发光面朝北，接管的是 {@code -67,-139} 到 {@code -67,-103} 这段」—— 那段是往**南**的；</li>
		 *   <li>「车**从北边**（z 更小）开过来」—— 司机迎着灯面来，过灯之后进入的正是**南边**那一段。</li>
		 * </ol>
		 *
		 * <p>所以"灯守一条腿"（原来按点积只留最贴的一条）是错一半的模型：**道岔后面有几条路，
		 * 这盏灯就管几条路** —— 列车无论走哪条岔，闯的都是这盏灯。锚点那个节点上灯朝北、
		 * 对面只有南段一条；换成三岔口就会有两条，全都要收。每一条腿各建一段区间
		 * （{@code buildSection} 负责），显示层再取其中最不利的一条。</p>
		 */
		final ObjectArrayList<Leg> guardedLegs = new ObjectArrayList<>();
		for (final Leg leg : candidates) {
			final double[] away = awayFromNode(leg, nodeOnLamp);
			if (away == null) {
				continue;
			}
			final double legLength = leg.rail.railMath.getLength();
			/*
			 * 「前方还有多少可走的路」= **这条腿从节点朝外的长度**（节点 → 轨的另一端）。
			 *
			 * <p>这里曾经按**行向**算（`leg.forward` 决定用 `弧长-节点弧` 还是 `节点弧`），
			 * 后果就是本次要修的 bug：进库 stub `(-67,-139)→(-67,-103)` 的弧增方向指向节点，
			 * 于是"前方余长"算出来是 0，整条腿被当成"前方无轨"丢掉 —— 灯于是守了旁边那条主线，
			 * 游戏里红、web 上绿。</p>
			 *
			 * <p>行向量的是"列车沿这根轨往哪边走"；而这里要问的是"灯面前有没有路"，那是纯几何的
			 * 事，与这条轨的弧是怎么存的无关。两者混用正是选腿一直不稳的根源。</p>
			 */
			final double aheadM = away[2];
			legTrace.append(" ｜").append(shortHex(leg.rail.getHexId())).append(" 余长=").append(round(aheadM));
			if (aheadM < MIN_BINDABLE_AHEAD_M) {
				legTrace.append(" 前方无轨→跳过");
				continue;
			}
			guardedLegs.add(leg);
			legTrace.append(" ←守");
		}
		if (guardedLegs.isEmpty()) {
			legTrace.append(" 半平面里的腿都太短→一条都不守");
		}
		/*
		 * 顺序定成确定的：**朝外更长的腿排前面**。
		 *
		 * <p>区间列表的头一条是这盏灯区间的"入口轨"（{@code entryRail}），遍历顺序不能决定它是谁 ——
		 * 否则同一盏灯在不同次重建里会给出不同的入口轨。按朝外长度降序还有一个语义上的好处：
		 * 直通方向通常比岔线长，于是"入口"落在贯通的那条腿上，这与人的直觉一致。</p>
		 */
		guardedLegs.sort((a, b) -> {
			final double[] awayA = awayFromNode(a, nodeOnLamp);
			final double[] awayB = awayFromNode(b, nodeOnLamp);
			return Double.compare(awayB == null ? 0 : awayB[2], awayA == null ? 0 : awayA[2]);
		});
		legCountOut[0] = allLegs.size();
		legTrace.append(describePointSetting(nodeOnLamp, heading));
		return guardedLegs;
	}

	/**
	 * 这个道岔（节点 + 盆轨）现在开通第几条腿 —— 纯观察，供 signal why 显示。
	 *
	 * <p>盆轨取"节点上灯面朝得最正的那条"：道岔以 (节点, 盆轨) 为键，而信号机本来就是面朝列车
	 * 开来方向立的。这条判据在**灯站在盆轨端点上**时是准的；灯立在节点旁（几何上与各条腿近乎等距）
	 * 时只能算个近似 —— 所以它现在只用于显示。</p>
	 */
	private String describePointSetting(Position node, double[] heading) {
		final String via = approachRailAt(node, heading);
		if (via == null) {
			return " 道岔=（盆轨判不出）";
		}
		final int setLeg = pointSetLeg(node, via);
		return " 盆轨=" + shortHex(via) + " 道岔开通=" + (setLeg < 0 ? "未设置" : ("leg " + setLeg));
	}

	/**
	 * 这个道岔（节点 + 进向轨）当前开通第几条腿：操作员手动的设置优先，其次是进路授权的 leg，
	 * 都没有则 -1（未设置）。
	 *
	 * <p>与走行层同一口径：{@code mmtrPointBranches} 是操作员的设置，{@code mmtrPointAuthority}
	 * 是进路/任务的授权。两处都读，灯才与列车实际走的那条腿一致。</p>
	 */
	private int pointSetLeg(Position node, @Nullable String viaRailHex) {
		if (node == null || viaRailHex == null || viaRailHex.isEmpty()) {
			return -1;
		}
		final long x = node.getX();
		final long y = node.getY();
		final long z = node.getZ();
		final int granted = simulator.mmtrPointAuthority.grantedLeg(x, y, z, viaRailHex);
		if (granted >= 0) {
			return granted;
		}
		return simulator.mmtrPointBranches.contains(x, y, z, viaRailHex)
			? simulator.mmtrPointBranches.get(x, y, z, viaRailHex) : -1;
	}


	/**
	 * 节点上**灯面朝得最正**的那条轨 —— 也就是道岔的盆轨（列车开来的那一侧）。
	 *
	 * <p>必须看节点的**全部邻接轨**：道岔以 (节点, 盆轨) 为键，而盆轨正是 nextLegs 排除掉的那条，
	 * 在候选腿里是找不到它的（以前就在这里找，于是道岔键永远对不上、状态永远读成"未设置"）。</p>
	 *
	 * @return 该轨的 hex；灯与所有轨都近乎垂直（点积 ≤ 0.1）时返回 null
	 */
	private @Nullable String approachRailAt(Position node, double[] heading) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null || neighbours.isEmpty()) {
			return null;
		}
		String best = null;
		double bestDot = 0.1;
		for (final Rail rail : neighbours.values()) {
			final double arc = MmtrBlockService.arcOfNode(rail, node);
			if (Double.isNaN(arc)) {
				continue;
			}
			final Outward outward = outwardFromNode(rail, arc);
			final double dot = outward.headingX * heading[0] + outward.headingZ * heading[1];
			if (dot > bestDot) {
				bestDot = dot;
				best = rail.getHexId();
			}
		}
		return best;
	}

	/**
	 * 一条腿**从节点朝外**的几何方向与长度 —— 纯几何，与这条轨怎么存无关。
	 *
	 * <h3>为什么不能用 {@code leg.farEnd}（边表里的那个邻居键）</h3>
	 * <p>{@code farEnd} 是 {@code positionsToRail} 里那一格的键，而它是按**行向**建的：
	 * 一条轨在本节点这一侧登记成"另一端"，在对面节点那一侧就会登记成本节点。实测锚点
	 * {@code -70,-59,-139} 的节点 {@code (-67,-139)} 上，朝北的进库 stub
	 * {@code (-67,-139)→(-67,-103)} 的 {@code farEnd} 是 {@code (-67,-167)}（**朝南**）——
	 * 方向整整翻了 180°。后果：灯朝北（点积应当 +1）被算成 −1，那条腿被"灯朝的那个半平面"筛掉，
	 * 而车正停在它上面 —— 于是游戏里红、web 上绿。</p>
	 *
	 * <h3>现在的定义</h3>
	 * <p>取这条轨的**两个端点**，按"端点所在的弧长"定出哪一端在节点这一侧、哪一端在远端，
	 * 再取**中点离节点更远的那一端**作为远端。这与端点怎么排序、轨朝哪边声明都无关。</p>
	 *
	 * @return {@code [方向x, 方向z, 长度]}；这条轨太短或取不到端点时返回 null
	 */
	private static double @Nullable [] awayFromNode(Leg leg, Position node) {
		final org.mtr.core.data.Position[] ends = leg.rail.mmtrOrderedPositions();
		if (ends == null || ends.length < 2) {
			return null;
		}
		final double nodeMidX = node.getX() + 0.5;
		final double nodeMidZ = node.getZ() + 0.5;
		final double d0 = Math.hypot(ends[0].getX() - nodeMidX, ends[0].getZ() - nodeMidZ);
		final double d1 = Math.hypot(ends[1].getX() - nodeMidX, ends[1].getZ() - nodeMidZ);
		final org.mtr.core.data.Position far = d0 >= d1 ? ends[0] : ends[1];
		final double dx = far.getX() - node.getX();
		final double dz = far.getZ() - node.getZ();
		final double norm = Math.hypot(dx, dz);
		if (norm < 1e-6) {
			return null;
		}
		// 诊断：把"选中的远端"原样带出来 —— 方向算错时，只有这一步能证明是选错了端点还是端点本身不对
		AWAY_TRACE.set(" [远端] " + shortHex(leg.rail.getHexId())
			+ " 端点0=(" + ends[0].getX() + "," + ends[0].getY() + "," + ends[0].getZ() + ") d0=" + round(d0)
			+ " 端点1=(" + ends[1].getX() + "," + ends[1].getY() + "," + ends[1].getZ() + ") d1=" + round(d1)
			+ " 选中=(" + far.getX() + "," + far.getY() + "," + far.getZ() + ")"
			+ " 节点=(" + node.getX() + "," + node.getY() + "," + node.getZ() + ")");
		return new double[]{dx / norm, dz / norm, norm};
	}

	/** 最近一次 {@code awayFromNode} 的原始账（诊断用；{@code signal why} 读它）。 */
	private static final ThreadLocal<String> AWAY_TRACE = ThreadLocal.withInitial(() -> "");

	/** 供 {@code signal why} 取诊断串。 */
	public static String awayTrace() {
		final String value = AWAY_TRACE.get();
		return value == null ? "" : value;
	}

	/** 节点上的邻接轨表（转发，省得每处都写一遍 fastutil 的泛型）。 */
	private Object2ObjectOpenHashMap<Position, Rail> neighboursOf(Position node) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		return neighbours == null ? new Object2ObjectOpenHashMap<>() : neighbours;
	}

	/**
	 * The rail a lamp protects and the direction it authorises.
	 *
	 * <p>An explicit {@code target} (a BOUND bind) wins. Otherwise the lamp is matched <strong>by
	 * direction</strong>: among the rails within tolerance take the one the lamp stands beside and looks
	 * along. The v1 inference used nearest-rail-only, so a lamp could bind to a rail behind it.</p>
	 *
	 * <p>A lamp standing at an END of a rail counts too, and has to: a wayside head is placed beside the
	 * track at the joint, a metre or two off the axis, so its projection lands a little way INSIDE the rail
	 * it is next to. Requiring the projection to be strictly interior (the first version) sent such a lamp
	 * to the node branch below, which measures the direction of the rail LEAVING the node - for a head
	 * beside a rail's far end that is the opposite way, so the lamp ended up protecting the whole rail
	 * while facing against its own position (found by the two-heads-facing-each-other test).</p>
	 *
	 * <p>Internal: no refresh - {@code rebuild()} calls this while it is itself the refresh.</p>
	 */
	private ObjectArrayList<ProtectedRail> resolveProtectedRailInternal(SignalEntry entry) {
		final double lampX = entry.x + 0.5;
		final double lampY = entry.y + 0.5;
		final double lampZ = entry.z + 0.5;
		final double[] heading = headingOf(entry.angle);

		if (entry.target != null && !entry.target.isEmpty()) {
			final Rail bound = railByHex.get(entry.target);
			if (bound != null) {
				final Double arc = MmtrBlockService.projectArc(bound, lampX, lampY, lampZ);
				if (arc != null) {
					final ObjectArrayList<ProtectedRail> single = new ObjectArrayList<>();
					single.add(new ProtectedRail(bound, arc, heading[0], heading[1]));
					return single;
				}
			}
			// A stale target falls through to directional inference (the rail was redrawn).
		}

		/*
		 * ① 先看灯是不是站在**节点上**（道岔/接头处，实车线路里信号机就在这儿）。
		 *
		 * 为什么必须在"按轨投影"之前判：MTR 的轨是**圆弧**，一条轨在两端附近的走向可以和它的整体
		 * 走向相反。实测这条轨从 (-38,-233) 出发向东、绕一圈后又从东侧回到 (-67,-203)，
		 * 于是"投影到最近点再量方向"会得出**向西**——正好和它出发时的方向相反。
		 * 站在接头处朝东的灯因此被判"两条轨都不对"，掉进兜底分支绑到了轨端，区间只剩几米。
		 *
		 * 站在节点上时，正确的问法是"从这个节点出发、朝我面朝方向的轨是哪条"——这才是灯守的那条轨。
		 * 距离阈值取 3 格：信号机立在线路旁一格左右，接头两侧各算一格。
		 */
		final Position nodeOnLamp = nearestNode(lampX, lampY, lampZ);
		final double nodeDistance = nodeOnLamp == null ? Double.MAX_VALUE : Math.sqrt(
			Math.pow(nodeOnLamp.getX() + 0.5 - lampX, 2) + Math.pow(nodeOnLamp.getY() + 0.5 - lampY, 2) + Math.pow(nodeOnLamp.getZ() + 0.5 - lampZ, 2));
		if (nodeOnLamp != null && nodeDistance <= NODE_BIND_RADIUS_M) {
			/*
			 * 站在节点旁的灯：只认"从该节点出发、且朝我面朝方向"的那条轨；都不匹配就**判定它不参与闭塞**。
			 *
			 * 为什么不像以前那样再掉进"按投影找最近轨"的兜底：那个兜底会绑上**从灯面前离开**的轨
			 * （它的远端正好在灯这儿），区间只剩"前方余长"那几米 —— 实测 {@code -38,-60,-235} 被绑到
			 * 一条由西向东的轨的**尾端**，区间 3 m、出口灯为空、永远绿。这种"看起来正常的错答案"
			 * 比"这盏灯没接入"坏得多：前者让人以为规则算过了，后者一眼能看出是位置/朝向要修。
			 *
			 * 原版 MTR 就是这个口径（{@code RenderSignalBase.getAspectState}）：先把灯归到最近的节点块，
			 * 再取该节点上"出射方向与灯面朝角相差 90° 以内"的轨，**没有匹配就不给这盏灯算状态**。
			 */
			/*
			 * 站在节点旁的灯：在"从该节点出发、方向说得通"的轨里，选**灯的投影离它端点最近**的那条。
			 *
			 * <p>为什么不能只按"方向对不对"挑（实测被它坑过）：节点上两条轨的走向常常**相反**
			 * （一条朝北、一条朝南），而灯的朝向只与其中一条相符 —— 但"相符"并不等于"该守它"。
			 * depot test1 出库口那盏就是：{@code (-65,-60,-139)} 面朝北，于是挑中了朝北的库内股道，
			 * 而它**人站在节点旁**、本该守从节点朝它那一侧延伸出去的那条轨 —— 结果"库里停着车"
			 * 让这盏朝库内看的灯变红（用户："朝那个环线的是绿灯、朝库内是红灯，现在是反的"）。</p>
			 *
			 * <p>判据改成几何事实：灯站在哪个端点旁，就守那条轨、并从那一端起步走。
			 * "方向对不对"退为**过滤条件**（只保留方向说得通的轨），不再决定选哪条。</p>
			 */
			final StringBuilder legTrace = new StringBuilder();
			final int[] legCount = {0};
			final ObjectArrayList<Leg> pickedRaw = resolveLegByPointState(nodeOnLamp, lampX, lampZ, heading, legTrace, legCount);
			/*
			 * **灯的保护范围不随道岔位置改变**（用户 2026-09-13 的最终裁定）。
			 *
			 * <p>原话："这个灯的灯光向南，保护向北的**所有**股道，那就是到 {@code -67,-60,-167} 和
			 * {@code -35,-60,-157} 的。" —— 灯守的是**它自己那一侧的整组腿**（此处 = 两条向北的轨：
			 * 正线远端 + 岔股），道岔扳到哪一位都不改变这一点。</p>
			 *
			 * <p>这里曾经按"位置 0 守北 / 位置 1 退守根部"把禁行那条腿从灯的视野里**删掉**，结果是
			 * 两个真问题：① 灯守的轨会随扳道岔跳来跳去；② 人工绑定（点选绑定）到"被禁行那一侧"的灯
			 * 会被静默改掉 —— 用户报的"现在不能手动设置某盏灯守某个道了"就是它。道岔的禁行由
			 * {@code MmtrForkElection} 的闸门负责（车停在岔前），不需要灯去替它表达。</p>
			 */
			final ObjectArrayList<Leg> picked = pickedRaw;
			if (LEG_TRACE_TARGET.get() != null && LEG_TRACE_TARGET.get().equals(MmtrSignalRegistry.key(entry.x, entry.y, entry.z))) {
				LAST_LEG_TRACE.set("  [选腿] 灯=" + MmtrSignalRegistry.key(entry.x, entry.y, entry.z)
					+ " 角=" + entry.angle + " 朝向=(" + round(heading[0]) + "," + round(heading[1]) + ")"
					+ " 节点=" + MmtrJunctionState.nodeKey(nodeOnLamp) + " 节点距=" + round(nodeDistance)
					+ " 腿数=" + legCount[0] + legTrace + awayTrace()
					+ " 结论=" + (picked.isEmpty() ? "无腿（判定不参与闭塞）" : picked.size() + " 条腿都守"));
			}
			if (picked.isEmpty()) {
				return new ObjectArrayList<>();
			}
			final ObjectArrayList<ProtectedRail> guarded = new ObjectArrayList<>();
			for (final Leg leg : picked) {
				final double arcAtNode = MmtrBlockService.arcOfNode(leg.rail, nodeOnLamp);
				/*
				 * 跨度方向用**这条腿从节点朝外的几何方向**，不是灯自己的朝向。
				 *
				 * <p>用户的规则是"灯管它朝向的**对面**那一侧"，所以这两个方向**必然相反**。以前这里
				 * 传的是灯朝向，于是朝北的灯去建"往北"的跨度 —— 而它守的那条腿是往南的，
				 * `buildSection` 按灯朝向选方向，就把跨度建到了**对面那条轨**上。</p>
				 *
				 * <p>实测（进给 4 条腿的合成世界，诊断串）：灯守着 40 m 的南腿，建出来的 span 却是
				 * {@code 弧0..30 端点=(0,0,-30)→(0,0,0)}（北边那条轨）—— 于是它自己的区间永远读不到
				 * 停在南腿上的车，灯永远绿。这条错误一路骗过了 528 条测试，因为既有用例里
				 * 灯的朝向与"它守的腿"恰好同向。</p>
				 */
				final double[] away = awayFromNode(leg, nodeOnLamp);
				final double dirX = away == null ? heading[0] : away[0];
				final double dirZ = away == null ? heading[1] : away[1];
				final ProtectedRail protectedRail = new ProtectedRail(leg.rail, arcAtNode, dirX, dirZ, heading[0], heading[1]);
				/*
				 * 这条腿是不是道岔**当前位置禁行**的那一侧：是 ⇒ 它不是一条进路（车根本走不进去），
				 * 但仍旧是这盏灯守的轨（用户要的"保护向北的所有股道"）。
				 */
				final org.mtr.core.mmtr.point.MmtrTurnout turnoutOnLampNode = simulator.mmtrTurnout(nodeOnLamp.getX(), nodeOnLamp.getY(), nodeOnLamp.getZ());
				if (turnoutOnLampNode != null && leg.rail.getHexId().equals(turnoutOnLampNode.prohibitedRailHex(
					simulator.mmtrTurnoutPosition(nodeOnLamp.getX(), nodeOnLamp.getY(), nodeOnLamp.getZ())))) {
					protectedRail.blockedAtDeparture = true;
				}
				guarded.add(protectedRail);
			}
			return guarded;
		}

		/*
		 * 不在节点旁（区间中段的灯，实测有一盏在弧 40 处、离两端各 40 m）：按"它站在哪条轨旁边、
		 * 面朝该轨的哪一段"来绑 —— 投影到最近点，量前方一段距离的方向，方向对不上就不绑。
		 */
		Rail best = null;
		double bestArc = 0;
		double bestScore = Double.MAX_VALUE;
		for (final Rail rail : simulator.rails) {
			final Double arc = MmtrBlockService.projectArc(rail, lampX, lampY, lampZ);
			if (arc == null) {
				continue;
			}
			final double length = rail.railMath.getLength();
			if (length <= 1e-6) {
				continue;
			}
			// 方向要按**一段距离**量（6 m），不用 0.25 m 的切线：整数格采样下切线读的是噪声，
			// 一条自西向东的曲线在接头处会被读成向西（实测）。headingAhead 在"投影点已在轨尾端"时
			// 返回零向量，于是"前方已无轨可走"这种情况自然被判掉。
			//
			// **两侧都要收**（2026-09-13）：中段灯管的可能是弧增那一侧，也可能是弧减那一侧 ——
			// 由灯的 FACING 定（见 headingOf：{@code heading} 就是它管的方向）。原来只认
			// {@code dot > 0.1}（弧增侧），于是"灯管弧减侧"的那种灯一条轨都绑不上、直接**退出闭塞**：
			// 实测 depot {@code -168,-60,-100}（角 180、站在南北向存车线旁）实测就掉成了"不参与闭塞"
			// （网页上表现为灰色未知色），而它旁边就停着车。
			final double[] railHeading = headingAhead(rail, arc);
			final double dot = railHeading[0] * heading[0] + railHeading[1] * heading[1];
			if (Math.abs(dot) <= 0.1) {
				continue;   // 与灯管的方向垂直：这条轨横在它面前，不是它管的那条
			}
			// 前方余长按**灯管的方向**量：弧增侧是 length-arc，弧减侧是 arc
			final double aheadM = dot > 0 ? length - arc : arc;
			if (aheadM < MIN_BINDABLE_AHEAD_M) {
				continue;
			}
			final double score = (1.0 - Math.abs(dot)) * 1000 + distanceSq(rail, arc, lampX, lampY, lampZ);
			if (score < bestScore) {
				bestScore = score;
				best = rail;
				bestArc = arc;
			}
		}
		if (best == null) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<ProtectedRail> single = new ObjectArrayList<>();
		single.add(new ProtectedRail(best, bestArc, heading[0], heading[1]));
		return single;
	}

	/**
	 * The node nearest to a world position (a lamp block sits on a node block). Only real graph nodes
	 * count: a rail endpoint with no connecting rails is not a node a signal would stand on, and binding
	 * to it would leave the lamp protecting nothing.
	 */
	private @Nullable Position nearestNode(double x, double y, double z) {
		Position best = null;
		double bestDistanceSq = Double.MAX_VALUE;
		for (final Position node : simulator.positionsToRail.keySet()) {
			final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
			if (neighbours == null || neighbours.isEmpty()) {
				continue;
			}
			final double dx = node.getX() - x;
			final double dy = node.getY() - y;
			final double dz = node.getZ() - z;
			final double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq < bestDistanceSq) {
				bestDistanceSq = distanceSq;
				best = node;
			}
		}
		return bestDistanceSq <= SIGNAL_BIND_TOLERANCE_M * SIGNAL_BIND_TOLERANCE_M ? best : null;
	}

	/**
	 * Unit heading (x, z) leaving {@code node} along {@code rail} (the node sits at {@code nodeArc}).
	 *
	 * <p>同样按**一段距离**量（见 {@link #headingAhead}），不用 {@link #headingAt} 的 0.25 m 切线：
	 * 道岔处"这条轨从节点往哪走"是选轨的关键判据，用采样噪声去量它，会把向东的出口读成向西
	 * （实测：节点 {@code -38,-60,-233} 的两个出口都被读成向西，于是两盏灯的方向判定全错）。</p>
	 */
	private static double[] outgoingHeading(Rail rail, double nodeArc, double length) {
		final boolean nodeAtLowArc = nodeArc <= length / 2;
		return headingOver(rail, nodeArc, nodeAtLowArc ? Math.min(length, nodeArc + HEADING_BASELINE_M) : Math.max(0, nodeArc - HEADING_BASELINE_M), nodeAtLowArc);
	}

	/**
	 * Walk from the lamp in the direction it faces until the next lamp (exclusive) or the end of the
	 * line, collecting the spans walked.
	 *
	 * <p>Exactly one continuation is followed at a node: the straightest one (highest dot product).
	 * Taking every branch would make one lamp authorise a whole junction fan, which is not what a
	 * wayside signal means; which branch is actually set is the point authority's business, and the S4
	 * display layer narrows by the set route on top of this.</p>
	 */
	private Section buildSection(String entrySignalKey, ProtectedRail protectedRail) {
		return buildSection(entrySignalKey, entrySignalKey, protectedRail);
	}

	/**
	 * 同 {@link #buildSection(String, ProtectedRail)}，但可指定区间 id。
	 *
	 * <p>id 默认为起始灯的键；一盏灯守多条腿时每段必须有自己的 id（否则 {@code followingBySection}
	 * 这种按 id 索引的表会把多条腿挤成一条，链就错了）。id 用 {@code 灯键#轨hex前若干位} 的形式，
	 * 既能一眼看出属于哪盏灯，又保证唯一。</p>
	 */
	/** 两点距离的平方（比较远近用，不开方）。 */
	private static double squaredDistance(Position a, Position b) {
		final double dx = a.getX() - b.getX();
		final double dy = a.getY() - b.getY();
		final double dz = a.getZ() - b.getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	private Section buildSection(String sectionId, String entrySignalKey, ProtectedRail protectedRail) {
		final Section section = new Section(sectionId, entrySignalKey);
		final Rail firstRail = protectedRail.rail;
		final String firstHex = firstRail.getHexId();
		/*
		 * **起点侧被道岔切断的腿不是一条进路** —— 在这里统一判定，而不是在各个选腿分支里各判一次。
		 *
		 * <p>为什么必须统一：选腿有几条分支（节点旁多腿 / 节点旁单腿 / 中段灯 / 人工绑定），
		 * 只在其中一条里标记，另一条走的就是"被切断的腿照样算进路"—— 实测就是这个后果：
		 * dev 世界 `-64,-59,-139`（节点灯，守向北两条：正线 + 岔股）在道岔设 0（岔股被切断）时，
		 * 岔股那条腿仍然算进颜色，而岔股那侧正停着一列车（level 3 占用）⇒ 读**双黄**，
		 * 用户要的是**绿**（能走的进路只剩正线那条，它是空的）。</p>
		 *
		 * <p>判据就是"灯所在节点上，这条腿是不是当前位置禁行的那一侧"。灯挂在节点旁边（实测 3 格），
		 * 所以灯所在节点 = 这条腿两个端点里离灯更近的那个。</p>
		 */
		final SignalEntry entrySignal = simulator.mmtrSignals.signals.get(entrySignalKey);
		if (entrySignal != null) {
			final Position[] ends = firstRail.mmtrOrderedPositions();
			if (ends != null && ends.length >= 2) {
				final Position lampPosition = new Position((long) Math.floor(entrySignal.x), (long) Math.floor(entrySignal.y), (long) Math.floor(entrySignal.z));
				final Position node = squaredDistance(ends[0], lampPosition) <= squaredDistance(ends[1], lampPosition) ? ends[0] : ends[1];
				final org.mtr.core.mmtr.point.MmtrTurnout turnout = simulator.mmtrTurnout(node.getX(), node.getY(), node.getZ());
				if (turnout != null && firstHex.equals(turnout.prohibitedRailHex(simulator.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ())))) {
					section.blockedAtDeparture = true;
				}
			}
		}
		final double length = firstRail.railMath.getLength();
		final double[] railHeading = headingAt(firstRail, protectedRail.arcM);
		final boolean forward = railHeading[0] * protectedRail.headingX + railHeading[1] * protectedRail.headingZ > 0;
		final double entryArc = clamp(protectedRail.arcM, 0, length);
		final double exitArc = forward ? length : 0;
		final double headingX = forward ? railHeading[0] : -railHeading[0];
		final double headingZ = forward ? railHeading[1] : -railHeading[1];

		if (Math.abs(exitArc - entryArc) > 1e-6) {
			// A lamp standing mid-rail is a boundary too (v2 keeps v1's geometric cut, not only node binds):
			// truncate the span at the nearest such lamp ahead and end the section there.
			final MidRailLamp midRail = nearestLampOnSpan(firstRail, entryArc, exitArc, forward, headingX, headingZ, entrySignalKey);
			if (midRail != null) {
				addSpan(section, new RailSpan(firstHex, entryArc, midRail.arcM, headingX, headingZ));
				section.addExitLamp(midRail.key);
				return section;
			}
			addSpan(section, new RailSpan(firstHex, entryArc, exitArc, headingX, headingZ));
		}

		// The movement leaves the rail straight into another lamp's cell: this lamp's reach starts at the
		// next lamp's position, so this lamp would show nothing but the light standing in front of it. That
		// section is dropped (an empty walk) and the cell it was aiming at belongs to the lamp ahead - the
		// nearest-upstream rule in gateBlocks() then assigns the track to the lamp that actually reaches it.
		if (section.exitSignalKey != null && section.exitSignalKey.equals(entrySignalKey) && section.spans.isEmpty()) {
			return section;
		}

		final Position exitNode = forward ? farNode(firstRail, true) : farNode(firstRail, false);
		final ObjectOpenHashSet<String> pathRails = new ObjectOpenHashSet<>();
		pathRails.add(firstHex);
		walk(section, firstHex, exitNode, headingX, headingZ, 1, pathRails);
		return section;
	}

	/** A lamp standing in the middle of a rail (not at a node): the arc it sits at and its registry key. */
	private static final class MidRailLamp {
		final double arcM;
		final String key;

		MidRailLamp(double arcM, String key) {
			this.arcM = arcM;
			this.key = key;
		}
	}

	/**
	 * The nearest lamp standing on {@code rail} strictly inside the arc range the movement is crossing
	 * <em>that faces into the block being walked</em> (heading {@code headingX, headingZ}), or null. A lamp
	 * on a node is the walk's business (it ends sections by node), so only a lamp whose projection is
	 * strictly inside the rail counts here - this keeps v1's geometric cut (a light beside the middle of a
	 * rail does split it) while the node case stays with the walk.
	 *
	 * <p>The facing test is what makes the cut correct in the OTHER direction (S6, notes/113): a head
	 * facing AWAY belongs to the block on the other side, and letting it truncate this walk is how a
	 * west-facing head at the east end ended up with a 50 m block instead of the whole 100 m rail.
	 * (The test itself lives in {@link #facesInto}, where the 水闸 rule is written down - a lamp cuts the
	 * walk only when it faces INTO it, i.e. when the train travelling this way sees its face.)</p>
	 */
	private @Nullable MidRailLamp nearestLampOnSpan(Rail rail, double fromArcM, double toArcM, boolean forward, double headingX, double headingZ, @Nullable String entrySignalKey) {
		final double low = Math.min(fromArcM, toArcM);
		final double high = Math.max(fromArcM, toArcM);
		final double length = rail.railMath.getLength();
		MidRailLamp best = null;
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			/*
			 * **自己不算自己的界**（2026-09-13 实测）。
			 *
			 * <p>节点旁的灯如果站位稍微**偏沿轨方向**（斜向节点、曲线上的节点很容易这样），它在这条轨上的
			 * 投影就落在弧 2~3 m 处，于是走行刚起步就"撞上"这盏灯：区间被截成节点→灯那一段 2.75 m 的残段，
			 * 出口灯还是它自己 —— 整条区间等于没了。实测 45° 走廊上的灯就这样掉成 2.75 m。</p>
			 *
			 * <p>界灯永远是**别的**灯，所以建这一段的那盏灯直接跳过。</p>
			 */
			if (entrySignalKey != null && entrySignalKey.equals(MmtrSignalRegistry.key(entry.x, entry.y, entry.z))) {
				continue;
			}
			final Double projected = MmtrBlockService.projectArc(rail, entry.x + 0.5, entry.y + 0.5, entry.z + 0.5);
			if (projected == null) {
				continue;
			}
			final double arc = clamp(projected, 0, length);
			if (arc <= 0.5 || arc >= length - 0.5) {
				continue; // on one of this rail's nodes: not a mid-rail cut
			}
			if (arc < low + 1e-6 || arc > high - 1e-6) {
				continue; // outside the stretch being crossed
			}
			if (!facesInto(entry, headingX, headingZ)) {
				continue; // faces out of this block: it bounds the NEXT one, not this one
			}
			if (best == null || (forward ? arc < best.arcM : arc > best.arcM)) {
				best = new MidRailLamp(arc, MmtrSignalRegistry.key(entry.x, entry.y, entry.z));
			}
		}
		return best;
	}

	/**
	 * True when {@code entry} is the lamp that opens the block a movement travelling {@code (headingX, headingZ)}
	 * is entering - i.e. when the stretch <em>this lamp itself guards</em> runs the same way we are walking.
	 *
	 * <h3>为什么判据是"它守的方向"而不是"它面朝的方向"</h3>
	 * <p>两盏相邻的灯守着**同一个走行方向**的相邻两段：我们走的那段走到头，正好是下一架灯那段的起点。
	 * 所以"下一架灯"的判据只有一条：**它自己守的走行方向与我们的走行方向一致**
	 * （点积 &gt; 0.1）。这一条对两种灯都成立，而"看它面朝哪边"不行 —— 因为灯的走行方向是怎么定出来的，
	 * 两种灯**本来就不一样**：</p>
	 * <ul>
	 *   <li><b>节点旁自动推断的灯</b>（{@code AUTO}，{@link #resolveLegByPointState}）：守它**面朝方向的
	 *       对面**那半平面（用户 2026-09-13 拍板：发光面朝北的灯管的是南边那段）。司机迎着灯面开过来，
	 *       所以下一架灯是**面朝我们**的：{@code 灯面 · 走行 &lt; 0}。</li>
	 *   <li><b>人工绑定的灯 / 轨中段的灯</b>（{@code rails} 非空，或离节点 &gt; {@link #NODE_BIND_RADIUS_M}）：
	 *       走行方向**就是灯面方向**（绑定工具的语义："列车必然从灯所站的那一端进入"，
	 *       见 {@code ItemMmtrRailBindingTool}；{@link #resolveProtectedRailInternal} 的中段分支同理）。
	 *       所以下一架灯与我们**同向**：{@code 灯面 · 走行 &gt; 0}。</li>
	 * </ul>
	 *
	 * <p>把两者折成一句"它守的方向 = 我们的方向"之后，走行不必知道对面那盏灯是怎么绑的 —— 直接问几何
	 * （{@link #guardedHeadings}），两种灯都判对。原来这里写死"灯面顺走行 ({@code &gt; 0.1})"，
	 * 于是对节点灯（本世界全部 depot 灯都是这种）恰好选反：它跳过真正该当界的下一架灯，
	 * 却把**管反方向**的那盏当界。实测 {@code -70,-59,-167}：它守 {@code (-67,-167)→(-67,-139)}（空），
	 * 下一架 {@code -70,-59,-139} 守的 {@code (-67,-139)→(-67,-103)} 上停着车 —— 正确答案是**单黄**，
	 * 因为界没认出来，走行穿过下一架灯把有车的轨吞进本区间，读成**红**。</p>
	 */
	private boolean facesInto(SignalEntry entry, double headingX, double headingZ) {
		for (final double[] guarded : guardedHeadings(entry)) {
			if (guarded[0] * headingX + guarded[1] * headingZ > 0.1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The travel direction(s) the lamp guards, from the same geometry that binds it
	 * ({@link #resolveProtectedRailsInternal} - the entry point {@code rebuild} uses, so an explicit
	 * {@code rails} list, a bound {@code target}, a node inference and a mid-rail projection all answer
	 * exactly as they do when the sections are built).
	 */
	private ObjectArrayList<double[]> guardedHeadings(SignalEntry entry) {
		final String key = MmtrSignalRegistry.key(entry.x, entry.y, entry.z);
		final ObjectArrayList<double[]> cached = guardedHeadingsByLamp.get(key);
		if (cached != null) {
			return cached;
		}
		final ObjectArrayList<double[]> headings = new ObjectArrayList<>();
		for (final ProtectedRail protectedRail : resolveProtectedRailsInternal(entry)) {
			headings.add(new double[]{protectedRail.headingX, protectedRail.headingZ});
		}
		guardedHeadingsByLamp.put(key, headings);
		return headings;
	}

	/**
	 * The lamp standing beside {@code node} that faces into a movement travelling {@code (headingX, headingZ)}.
	 *
	 * <p>Only a lamp facing into the block ends the walk (S6, notes/113): the boundary between two blocks
	 * carries <em>two</em> heads in the real world - one for each direction of travel - and the one facing
	 * BACK down the line that was just walked guards the opposite direction, so it must not cut this block.
	 * Counting it did exactly that on the dev world: opposite heads in the same block made the map show
	 * slivers nobody guards (notes/112 §3.1).</p>
	 *
	 * <h3>为什么按几何找"节点旁的灯"，不查"节点那一格"</h3>
	 * <p>登记表记的是**灯方块自己的位置**，而信号机立在线路旁边：实测本世界 depot 那几盏离轨 3 格、
	 * 比轨道节点高 1 格。于是灯几乎从不落在节点那一格上 —— 实测 dev 世界 73 盏灯里**没有一盏**落在
	 * 任何节点格（{@code x,y,z} 精确相等，或"往下一格"）上。原来这里用
	 * {@code simulator.mmtrSignals.getNear(节点格)} 精确查表，答案是"这个节点上没有灯"，
	 * 于是走行穿过一架又一架灯、一路吞到线路尽头：实测整条咽喉（4 根轨 119.3 m）被并成一个区间。</p>
	 *
	 * <p>现在与 ① 选腿同口径：以 {@link #NODE_BIND_RADIUS_M}（4 m）为半径找节点旁的灯 —— 那边也是
	 * "灯离最近节点 3.16 格"这条实测值定的。</p>
	 */
	private @Nullable SignalEntry lampAt(Position node, double headingX, double headingZ) {
		SignalEntry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final double distance = distanceToNode(entry, node);
			if (distance > NODE_BIND_RADIUS_M || distance >= bestDistance) {
				continue;
			}
			if (!facesInto(entry, headingX, headingZ)) {
				continue;
			}
			best = entry;
			bestDistance = distance;
		}
		return best;
	}

	/**
	 * The nearest lamp standing beside {@code node}, whatever way it faces - the walk uses this only to
	 * explain WHY it did not stop here ("节点上的灯 X 朝向 …°，是背向本方向的").
	 */
	private @Nullable SignalEntry lampAt(Position node) {
		SignalEntry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final double distance = distanceToNode(entry, node);
			if (distance <= NODE_BIND_RADIUS_M && distance < bestDistance) {
				best = entry;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** 灯方块中心到节点中心的距离（格）。 */
	private static double distanceToNode(SignalEntry entry, Position node) {
		return Math.sqrt(
			Math.pow(entry.x + 0.5 - node.getX() - 0.5, 2)
				+ Math.pow(entry.y + 0.5 - node.getY() - 0.5, 2)
				+ Math.pow(entry.z + 0.5 - node.getZ() - 0.5, 2));
	}

	/**
	 * 站在 {@code node}、**背向**本走行方向、但**绑定在本区间走过（或正要走）的那根轨上**的灯
	 * （notes/155 §15）。
	 *
	 * <p>为什么要它：面向本方向的灯才是"下一段区间的入口灯"，可当一条轨上只有**反方向**的灯时
	 * （单线区间、环线、尽头 U 弯），走行就一架边界都遇不到，一路吞成巨块 —— 现场实测 562 m / 24 段，
	 * 站在站台上的车把整条线按红。反向的灯在**它的那根轨上**同样是分界：走到它就该收口。</p>
	 *
	 * <p>限定"绑在本轨上"是关键：不然岔区旁另一条轨上的灯会被误当边界（notes/112/113 的碎片教训）。
	 * 同一节点上两架灯（各守一个方向）时，本判据与面向那架灯的边界收在同一处，不产生碎片。</p>
	 */
	private @Nullable SignalEntry backFacingLampBoundToWalkedRail(Position node, double headingX, double headingZ, String legHex, Section section) {
		SignalEntry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final double distance = distanceToNode(entry, node);
			if (distance > NODE_BIND_RADIUS_M || distance >= bestDistance) {
				continue;
			}
			if (facesInto(entry, headingX, headingZ)) {
				continue;   // 面向本方向的：调用方已经先处理过（不会走到这里）
			}
			if (!protectsWalkedRail(entry, legHex, section)) {
				continue;
			}
			best = entry;
			bestDistance = distance;
		}
		return best;
	}

	/** 这盏灯守的是不是本区间走过（或正要走）的那根轨。 */
	private boolean protectsWalkedRail(SignalEntry entry, String legHex, Section section) {
		for (final ProtectedRail protectedRail : resolveProtectedRailsInternal(entry)) {
			final String hex = protectedRail.rail.getHexId();
			if (hex.equals(legHex)) {
				return true;
			}
			for (final RailSpan span : section.spans) {
				if (span.railHex.equals(hex)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Continue the section from {@code node} (having just left {@code cameFromHex}) in {@code heading}.
	 *
	 * <p><strong>岔口多腿 (user ruling 2026-09-10, route B)</strong>: at a junction the walk follows
	 * <em>every</em> leg that continues in the travel direction, so one lamp protects the whole throat
	 * rather than the single leg it happens to face - which is what a real exit signal does. When a MAIN
	 * route is set through {@code cameFromHex} in this direction, the walk narrows to that route's own
	 * next rail instead (the same rule {@code MmtrSignalAspect} already used for the display).</p>
	 *
	 * <p>The first version followed only the straightest leg. That is wrong for a yard throat: the dev
	 * world's exit lamps then protected one of six parallel stabling roads (notes/107 §4).</p>
	 */
	private void walk(Section section, String cameFromHex, @Nullable Position node, double headingX, double headingZ, int railCount, ObjectOpenHashSet<String> pathRails) {
		walk(section, cameFromHex, node, headingX, headingZ, railCount, pathRails, true);
	}

	/**
	 * As above, with {@code reachableRoute} telling whether the branch being walked is one the movement
	 * could actually take (see the turnout gate below).
	 *
	 * <p>{@code false} = 这是"要守住、但这次运行走不到"的那条腿（模型道岔当前位置切掉的那一侧）。
	 * 它沿途撞到禁行侧**不许**把整段判成"没有进路"，否则一盏看着直通正线的灯会永远红
	 * （用户 2026-09-14 现场报的就是这个）。</p>
	 */
	private void walk(Section section, String cameFromHex, @Nullable Position node, double headingX, double headingZ, int railCount, ObjectOpenHashSet<String> pathRails, boolean reachableRoute) {
		if (node == null) {
			section.endsAtDeadEnd = true;
			section.endReason = "轨的远端找不到节点（拓扑缺端点）";
			return;
		}
		/*
		 * **道岔的禁行侧不能穿过去**（用户 2026-09-13 的规格：一处道岔两个位置、两条进路互斥）。
		 *
		 * <p>车沿着这一段的轨走到这个节点时，如果这条轨正是道岔当前位置切掉的那一侧，那这条进路
		 * 根本走不出去 —— 车只能停在岔前等道岔扳过来（决策 a）。所以：链在这里断，
		 * 并且这一段整体按**红**显示（"没有进路"就是危险，不是"前方有车"的黄）。</p>
		 *
		 * <p>但只对**这次运行走得到**的分支成立：模型道岔切掉的那条腿这次根本走不到，它撞墙是意料之中，
		 * 不能把整盏灯判红（见上面的说明）。</p>
		 */
		final org.mtr.core.mmtr.point.MmtrTurnout turnoutAtNode = simulator.mmtrTurnout(node.getX(), node.getY(), node.getZ());
		if (turnoutAtNode != null && cameFromHex != null && cameFromHex.equals(turnoutAtNode.prohibitedRailHex(simulator.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ())))) {
			if (reachableRoute) {
				section.endsAtDeadEnd = true;
				section.blockedAtArrival = true;
				section.endReason = "撞在道岔 " + MmtrJunctionState.nodeKey(node) + " 的**禁行侧**（道岔位置 "
					+ simulator.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ())
					+ "：这条轨禁止通行）→ 这条进路走不出去，按红显示";
			} else {
				section.endsAtDeadEnd = true;
				if (section.endReason == null || section.endReason.isEmpty()) {
					section.endReason = "岔股那一侧在道岔 " + MmtrJunctionState.nodeKey(node) + " 被切断（本进路走不到那条腿，不判断路）";
				}
			}
			return;
		}
		/*
		 * 走行停在这里 = 本段结束在这个节点；区间链接按它来连（不看出口灯的名字，见 rebuild）。
		 *
		 * <p>只有**这次运行走得到**的分支才决定"本段结束在哪"：走不到的腿（模型道岔切掉的那一侧）
		 * 也会被走一遍、把 span 收进本段（保住"一盏灯守整个咽喉"），但它不是这条进路的出口 ——
		 * 让它写结束节点/结束原因，链就会顺着它接到别的线上（实测用户 2026-09-14 追出来的
		 * 就是这一条：{@code -152,-60,-122} 那盏灯的单黄来自 100 m 外另一条线上的股道）。</p>
		 */
		if (reachableRoute) {
			section.endNodeKey = MmtrJunctionState.nodeKey(node);
		}

		// 这个节点上有没有灯（不论朝向）：背向的灯不切断走行，但要记下来，"为什么没停在这里"才有答案
		final SignalEntry lampOnNode = lampAt(node);

		final ObjectArrayList<Leg> legs = nextLegs(node, cameFromHex, headingX, headingZ);
		if (legs.isEmpty()) {
			if (reachableRoute) {
				section.endsAtDeadEnd = true;
				section.endReason = "走到死胡同：节点 " + MmtrJunctionState.nodeKey(node) + " 没有可继续的轨"
					+ (lampOnNode == null ? "（该节点上没有登记任何灯）"
						: "（该节点上的灯 " + MmtrSignalRegistry.key(lampOnNode.x, lampOnNode.y, lampOnNode.z)
							+ " 朝向 " + lampOnNode.angle + "°，是背向本方向的，所以不切断本区间）");
			}
			return;
		}
		if (railCount >= MAX_RAILS_PER_SECTION || section.lengthM() >= MAX_SECTION_LENGTH_M) {
			if (reachableRoute) {
				section.endsAtDeadEnd = true;
				section.endReason = "被长度/段数上限截断（段数 " + railCount + "，长 " + round(section.lengthM()) + " m）";
			}
			return;
		}

		boolean anyLegHandled = false;
		/*
		 * **模型道岔：哪些腿是这次运行真正走得到的。**
		 *
		 * <p>下面"每条腿都走"是给**没有模型**的老式咽喉用的（一盏灯守整个咽喉，用户 2026-09-10 的裁定）——
		 * 有模型时那几条腿仍然都要**守住**（岔股上可能停着扳岔之前就进来的车），但**只有模型开通的那一条
		 * 是这次运行走得出去的**。</p>
		 *
		 * <p>这个区分是用户 2026-09-14 现场报的：{@code -170,-60,-253} 旁朝北的灯本该绿（位置 0 =
		 * 正线贯通、直通那条空闲），却因为区间在 {@code -170,-60,-289} 拐上了那条 9.5° 斜线、
		 * 又在斜线另一端撞上 {@code -176,-60,-253} 的禁行侧，整段被判成"没有进路" ⇒ 红。
		 * 那条斜线在位置 0 是**禁止通行**的：这次运行根本走不到那里，它撞墙不该把整盏灯判红。</p>
		 */
		final String allowedNextHex = turnoutAtNode == null ? null
			: turnoutAtNode.continuationFrom(cameFromHex, simulator.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ()));
		for (final Leg leg : legs) {
			final Rail next = leg.rail;
			final String nextHex = next.getHexId();
			final boolean reachableLeg = allowedNextHex == null || nextHex.equals(allowedNextHex);
			// Cycle guard: a rail this branch has already walked cannot be entered twice (a diamond - the
			// same rail reachable two ways - is fine, because each branch carries its own path set).
			if (pathRails.contains(nextHex)) {
				continue;
			}
			final double arcOfNode = MmtrBlockService.arcOfNode(next, node);
			if (Double.isNaN(arcOfNode)) {
				continue;
			}
			final double nextLength = next.railMath.getLength();
			final double[] nextHeading = headingAt(next, arcOfNode);
			/*
			 * 「列车沿这条腿是**沿弧增**走还是沿弧减走」= 纯几何事实：列车从**本节点**进入这条腿、
			 * 朝远端走 —— 所以节点在这条轨的哪一端，就决定了弧的方向。
			 *
			 * <p>原实现用"这条腿在节点处的行进方向 · 进来的方向"的点积符号来判（`leg.forward`）。
			 * 缓和转向时等价，但**超过 90° 的转向会翻号**：实测咽喉那条 45° 斜线（C）进来时，
			 * 朝 -z 的那条腿点积为负 → 被判成"从远端进来"，于是它的跨度方向与"这条腿的入口灯"
			 * 都朝反了 —— 那盏灯的界灯收不到（该收 2 架只收到 1 架），双黄/单黄就少一层。</p>
			 */
			final boolean forward = arcOfNode <= nextLength / 2;
			final double toArc = forward ? nextLength : 0;
			final double spanHeadingX = forward ? nextHeading[0] : -nextHeading[0];
			final double spanHeadingZ = forward ? nextHeading[1] : -nextHeading[1];

			/*
			 * **每条腿各自判界**（岔口多腿：用户 2026-09-10 "整个咽喉都是闭塞"）。
			 *
			 * <p>这里的判据是"这条腿的入口灯"：站在本节点、守着**这条腿的走行方向**的那盏灯
			 * （`lampAt(node, 腿的方向)`）—— 它开的正是这条腿后面的那段区间，所以本区间在**这一支**上到此为止。</p>
			 *
			 * <p>原来这里是"只要节点上找到任何一架面朝本区间的灯，整段走行就停"。那在岔口上是错的：
			 * 咽喉 (-67,-139)（度=3）上，朝北那支的入口灯（{@code -64,-59,-139}，守北向）与朝南那支的
			 * 入口灯（{@code -70,-59,-139}，守南向、**其上停着车**）各管一支；按"任意一架就全停"，
			 * 走行只认了与到达切线更贴合的那支（朝北），朝南那支根本没被走 —— 从支线过来的灯于是
			 * 看不到咽喉另一侧的车（该双黄读绿）。</p>
			 *
			 * <p><b>但"走不到的腿"不许当出口</b>（用户 2026-09-14 追出来的）：模型道岔切掉的那一侧，
			 * 这次运行根本进不去，它后面的区间不是这条进路的前方 —— 把它当出口，链就会接到别的线上，
			 * 于是出现"这盏灯黄，原因是 100 m 外另一条线的股道里有车"。span 照样收（守住那条腿），
			 * 出口灯/结束原因不收。</p>
			 */
			final boolean reachable = reachableRoute && reachableLeg;
			final SignalEntry legBoundary = lampAt(node, spanHeadingX, spanHeadingZ);
			if (legBoundary != null) {
				anyLegHandled = true;
				if (reachable) {
					section.addExitLamp(MmtrSignalRegistry.key(legBoundary.x, legBoundary.y, legBoundary.z));
					section.endReason = "停在面向本区间的灯 " + MmtrSignalRegistry.key(legBoundary.x, legBoundary.y, legBoundary.z)
						+ "（正常：它是这条腿的入口灯，开的是下一段区间）";
				}
				continue;
			}

			/*
			 * **背向本方向、但绑在本轨上的灯，同样是边界**（notes/155 §15 现场）。
			 *
			 * <p>它守着的是同一条轨的**反方向** —— 走行经过它就说明已经走进"别人家的信号区"里去了。
			 * 不认它，走行就会一路吞下去：实测本世界被并成一个 **562 m / 24 段轨**的巨块
			 * （沿 x=-155 一路向下 → 尽头 U 弯 → 沿 x=-170 一路向上），于是**站在站台上的车
			 * 把整条线上同方向的车全按红** —— 六台车谁也动不了（现场读数：四台车全停、全是红灯）。</p>
			 *
			 * <p><b>但这与既有用例冲突，暂不启用</b>：{@code aLampGuardingTheOppositeDirectionDoesNotEndTheSection}
			 * 钉的是 notes/112 §3.1 的裁定 —— "管反方向的那盏灯不得当界"（否则本区间被切碎成没人守的碎片）。
			 * 现场那条巨块的真因要再查（见 notes/155 §15 的两条待验证路线），先把判据留在这里：
			 * 启用前必须先能说清"什么时候反方向的灯是边界、什么时候不是"。</p>
			 */
			final boolean oppositeFacingLampsAreBoundaries = false;
			final SignalEntry backBoundary = oppositeFacingLampsAreBoundaries
				? backFacingLampBoundToWalkedRail(node, spanHeadingX, spanHeadingZ, nextHex, section) : null;
			if (backBoundary != null) {
				anyLegHandled = true;
				if (reachable) {
					section.addExitLamp(MmtrSignalRegistry.key(backBoundary.x, backBoundary.y, backBoundary.z));
					section.endReason = "停在背向本方向、但绑在本轨上的灯 " + MmtrSignalRegistry.key(backBoundary.x, backBoundary.y, backBoundary.z)
						+ "（反方向的边界灯：本方向的走行到此收口，不再往反向信号区里走）";
				}
				continue;
			}

			if (Math.abs(toArc - arcOfNode) > 1e-6) {
				// A lamp standing MID-RAIL is a boundary too: end the section on it instead of walking past.
				final MidRailLamp midRail = nearestLampOnSpan(next, arcOfNode, toArc, forward, spanHeadingX, spanHeadingZ, section.entrySignalKey);
				if (midRail != null) {
					addSpan(section, new RailSpan(nextHex, arcOfNode, midRail.arcM, spanHeadingX, spanHeadingZ, reachable));
					anyLegHandled = true;
					if (reachable) {
						section.addExitLamp(midRail.key);
						section.endReason = "停在轨中的灯 " + midRail.key + "（正常：这是下一段区间的入口灯）";
					}
					continue;
				}
				addSpan(section, new RailSpan(nextHex, arcOfNode, toArc, spanHeadingX, spanHeadingZ, reachable));
			}
			anyLegHandled = true;
			final ObjectOpenHashSet<String> branchPath = new ObjectOpenHashSet<>(pathRails);
			branchPath.add(nextHex);
			walk(section, nextHex, forward ? farNode(next, true) : farNode(next, false), spanHeadingX, spanHeadingZ, railCount + 1, branchPath, reachable);
		}
		if (!anyLegHandled) {
			section.endsAtDeadEnd = true;
			section.endReason = "走到死胡同：从 " + MmtrJunctionState.nodeKey(node) + " 出发的可继续轨都已被走过（环）";
		}
	}

	/** One continuation at a node: the rail, and whether the travel direction runs up its arc. */
	private static final class Leg {
		final Rail rail;
		final boolean forward;
		/** Where the leg leaves the node: its far node, so callers can match a route. */
		final Position farEnd;

		Leg(Rail rail, boolean forward, Position farEnd) {
			this.rail = rail;
			this.forward = forward;
			this.farEnd = farEnd;
		}
	}

	/**
	 * Every rail the movement may continue onto at {@code node}: all legs that keep the travel direction,
	 * narrowed to the SET MAIN route's own next rail when a route runs through {@code cameFromHex} this
	 * way. Never the rail just left.
	 */
	private ObjectArrayList<Leg> nextLegs(Position node, String cameFromHex, double headingX, double headingZ) {
		return nextLegs(node, cameFromHex, headingX, headingZ, true);
	}

	/**
	 * 同上，但可关掉**进路收窄**（{@code narrowToRoute = false}）。
	 *
	 * <h3>为什么选腿必须关掉它</h3>
	 * <p>收窄是**区间走行**的语义：有 MAIN 进路从这个节点过去时，区间只该走进路那条腿
	 * （否则一个咽喉会被算成"整个都是闭塞"）。但它以前被选腿共用了，于是：</p>
	 * <pre>
	 * 锚点 -70,-59,-139（角 0，朝北）：节点 (-67,-139) 的度=3，stub (-67,-139)→(-67,-103)
	 *   就在邻接表里（query node 直读），可灯的腿表里只有 3 条、**且全不是 stub**
	 *   —— 因为 world 里有一条 MAIN 进路经过这个节点，收窄把候选砍成了进路那一条。
	 * </pre>
	 * <p>灯"守哪些腿"是**几何**问题（灯朝着哪边，那边的腿都归它管），与"此刻有没有进路"无关：
	 * 进路只是让列车走其中一条，不改变这盏灯管着几条路。所以选腿传 {@code false}。</p>
	 */
	private ObjectArrayList<Leg> nextLegs(Position node, String cameFromHex, double headingX, double headingZ, boolean narrowToRoute) {
		final ObjectArrayList<Leg> legs = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null) {
			return legs;
		}
		for (final Map.Entry<Position, Rail> entry : neighbours.entrySet()) {
			final Rail candidate = entry.getValue();
			if (candidate.getHexId().equals(cameFromHex)) {
				continue;
			}
			final double arc = MmtrBlockService.arcOfNode(candidate, node);
			if (Double.isNaN(arc)) {
				continue;
			}
			final double[] candidateHeading = headingAt(candidate, arc);
			final double dot = candidateHeading[0] * headingX + candidateHeading[1] * headingZ;
			// forward = 列车是否沿弧增走；与灯守哪条腿无关（那条腿可能是"弧增朝节点"的）
			legs.add(new Leg(candidate, dot > 0, entry.getKey()));
		}
		// 只有**区间走行**才收窄到进路：见上面的说明（选腿必须看到全部腿）
		if (!narrowToRoute || legs.size() <= 1) {
			return legs;
		}
		final String routeNext = routeNextRailOn(cameFromHex, node);
		if (routeNext == null) {
			return legs; // no route: the whole throat is one block (岔口多腿)
		}
		for (final Leg leg : legs) {
			if (leg.rail.getHexId().equals(routeNext)) {
				final ObjectArrayList<Leg> narrowed = new ObjectArrayList<>();
				narrowed.add(leg);
				return narrowed;
			}
		}
		return legs;
	}

	/**
	 * The rail a route runs onto after {@code curHex} when the route leaves {@code curHex} through
	 * {@code node} - i.e. the route covers this rail in THIS travel direction. Null when no route does (a
	 * shunt keeps the main head at danger and narrows nothing).
	 *
	 * <p>PENDING routes count: the train is committed to that movement even while it waits for the
	 * interlocking, so its own leg is the block it will occupy.</p>
	 */
	private @Nullable String routeNextRailOn(String curHex, Position node) {
		for (final org.mtr.core.mmtr.route.MmtrRoute route : simulator.mmtrRoutes.allRoutes()) {
			if (route.getKind() != org.mtr.core.mmtr.route.MmtrRoute.Kind.MAIN) {
				continue;
			}
			final ObjectArrayList<String> rails = route.getRailHexes();
			for (int i = 0; i + 1 < rails.size(); i++) {
				if (!rails.get(i).equals(curHex)) {
					continue;
				}
				final String nextHex = rails.get(i + 1);
				final Rail nextRail = railByHex.get(nextHex);
				if (nextRail != null && !Double.isNaN(MmtrBlockService.arcOfNode(nextRail, node))) {
					return nextHex;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- geometry helpers

	/** The rail's two end nodes as {@code [lowArcNode, highArcNode]} (arc 0 first). */
	private static Position @Nullable [] orderedNodes(Rail rail) {
		final Position[] positions = rail.mmtrOrderedPositions();
		if (positions == null || positions.length < 2 || positions[0] == null || positions[1] == null) {
			return null;
		}
		return positions;
	}

	/** The far end node of {@code rail} in the direction of increasing ({@code true}) or decreasing arc. */
	private static @Nullable Position farNode(Rail rail, boolean towardPositiveArc) {
		final Position[] nodes = orderedNodes(rail);
		if (nodes == null) {
			return null;
		}
		// mmtrOrderedPositions() returns the endpoints ordered by Position.compareTo, which is the same
		// ordering RailMath's arc space is built from: index 0 = arc 0.
		return towardPositiveArc ? nodes[1] : nodes[0];
	}

	/**
	 * 这盏灯**管的那一侧**的走行方向 {@code (x, z)}（= 它所在区间的走行方向），由登记表里的 MTR 朝向角换算。
	 *
	 * <h3>角度语义（2026-09-13 用户按实机灯位纠正，两盏灯互相印证）</h3>
	 * <p>方块状态里的角是 {@code FACING.asRotation()}（南 0 / 西 90 / 北 180 / 东 270），也就是**方块 FACING
	 * 属性**；而**模型上亮的那一面在 FACING 的反面**（渲染器把模型绕 Y 转 {@code -angle}，灯面是模型的 -z 侧）。
	 * 合起来一句话：</p>
	 *
	 * <pre>
	 *   FACING（= 角本身）= 这盏灯**管**的那一侧 = 司机迎着灯面开过来的方向
	 *   灯面（肉眼看到的亮面）= FACING 的反面
	 * </pre>
	 *
	 * <p>所以这个函数返回的正是 {@code FACING} 方向（不是灯面方向）：{@code (-sin, cos)}。两条实机判据：</p>
	 * <ul>
	 *   <li>锚点 {@code -70,-59,-139}（角 0）：FACING=南 → 管南边那段 ✓（用户："发光面朝北，接管的是
	 *       {@code -67,-139} 到 {@code -67,-103} 这段"，而灯面朝北正是 FACING 的反面 ✓）；</li>
	 *   <li>{@code -10,-59,-160}（角 90，东西向线路的节点上）：FACING=西 → 管西边那条 ✓
	 *       （用户原话："灯光朝向东，保护向西的铁轨" ✓）。</li>
	 * </ul>
	 *
	 * <h3>这里曾经错了一半（只错东西向）</h3>
	 * <p>原来返回 {@code (-sin, -cos)}：它在 0°/180°（南北）上与 FACING 一致（所以锚点那轮校正后南北向的灯
	 * 都对），在 90°/270°（东西）上却是 FACING 的**镜像**（east↔west 互换）。于是"南北向的灯看着都对、
	 * 东西向的灯全守反了"：实测 {@code -10,-59,-160} 守着东边那条 17 m 死头（永远绿），而它该管西边
	 * （读到咽喉里的车 → **双黄**）；同一条咽喉的 {@code -35,-59,-160} 同理（该单黄却读绿）。</p>
	 *
	 * <p>注意这与历史上试过的 {@code (sin, cos)} **不是**同一件事：那个是灯面方向（= FACING 的反面，
	 * 等于整体再转 180°），当年一改就掀翻 22 条测试；这次只动东西向那一半，南北向（角 0/180）
	 * 的行为**逐位不变**。</p>
	 */
	private static double[] headingOf(float angleDegrees) {
		final double radians = Math.toRadians(angleDegrees);
		return new double[]{-Math.sin(radians), Math.cos(radians)};
	}

	/**
	 * Unit heading (x, z) of a rail at {@code arcM}, in the direction of increasing arc.
	 *
	 * <p>Public so the 区间图 (schematic) builder reads a rail's direction the same way this model does,
	 * instead of re-deriving it and drifting from it.</p>
	 */
	public static double[] railHeadingAt(Rail rail, double arcM) {
		return headingAt(rail, arcM);
	}

	/** Unit heading (x, z) of a rail at {@code arcM}, in the direction of increasing arc. */
	private static double[] headingAt(Rail rail, double arcM) {
		final double length = rail.railMath.getLength();
		final double a = clamp(arcM - SAMPLE_STEP_M, 0, length);
		final double b = clamp(arcM + SAMPLE_STEP_M, 0, length);
		final Vector from = rail.railMath.getPosition(a, false);
		final Vector to = rail.railMath.getPosition(b, false);
		final double dx = to.x() - from.x();
		final double dz = to.z() - from.z();
		final double norm = Math.sqrt(dx * dx + dz * dz);
		return norm < 1e-9 ? new double[]{0, 0} : new double[]{dx / norm, dz / norm};
	}

	/**
	 * 前方**一整段可走距离**上量出来的方向（{@code arcM} → {@code arcToM}），而不是投影点附近的切线。
	 *
	 * <h3>为什么不能用切线来判断"这条轨是不是往我要去的方向"</h3>
	 * <p>{@link #headingAt} 只用 {@code ±0.25 m} 的两个采样点，在**整数格采样**的轨数据上量到的是噪声：
	 * 实测一条自西向东的连续曲线 {@code (-67,-203)→(-38,-233)→(-18,-203)}，在接头节点处的采样是
	 * {@code (-37,-232) → (-39,-232) → (-40,-232)} —— 起点先斜出去一格再回头，于是"向东的轨"在切线里
	 * 读成**向西**。后果不是差之毫厘：一盏朝东的灯因为点积为 −1 而被判"两条轨都不对"，
	 * 掉进兜底分支绑到了轨端，区间长度只剩 3 m，永远显示绿灯。</p>
	 *
	 * <p>所以判断"往哪走"要用**一段距离**上的位移，而不是一个点上的导数：曲线在这一段里转过的角度
	 * 才是它真正走过的方向。这一段同时给一个下限（短轨也要有足够基线）与上限（长直轨不必量一整条）。</p>
	 */
	private static double[] headingOver(Rail rail, double arcFromM, double arcToM) {
		final double length = rail.railMath.getLength();
		final Vector from = rail.railMath.getPosition(clamp(arcFromM, 0, length), false);
		final Vector to = rail.railMath.getPosition(clamp(arcToM, 0, length), false);
		final double dx = to.x() - from.x();
		final double dz = to.z() - from.z();
		final double norm = Math.sqrt(dx * dx + dz * dz);
		return norm < 1e-9 ? new double[]{0, 0} : new double[]{dx / norm, dz / norm};
	}

	/** 同上，但可指定方向（{@code increasingArc=false} 时沿弧减少的方向量）。 */
	private static double[] headingOver(Rail rail, double arcFromM, double arcToM, boolean increasingArc) {
		final double[] heading = headingOver(rail, increasingArc ? arcFromM : arcToM, increasingArc ? arcToM : arcFromM);
		return increasingArc ? heading : new double[]{-heading[0], -heading[1]};
	}

	/**
	 * 从 {@code arcM} 出发、沿增加弧方向走 {@code min(BASELINE, 余长)} 得到的方向。
	 *
	 * <p>余长为 0（灯正好站在轨的尾端）时返回零向量，调用方据此判断"这条轨在前方已经没有可走的路"——
	 * 这比随便给一个方向诚实：那种情况下这条轨本来就不该是"向前看"的那一条。</p>
	 */
	private static double[] headingAhead(Rail rail, double arcM) {
		final double length = rail.railMath.getLength();
		final double remaining = length - clamp(arcM, 0, length);
		final double baseline = Math.min(HEADING_BASELINE_M, remaining);
		return baseline < 1e-6 ? new double[]{0, 0} : headingOver(rail, arcM, arcM + baseline);
	}

	private static double distanceSq(Rail rail, double arcM, double x, double y, double z) {
		final Vector position = rail.railMath.getPosition(clamp(arcM, 0, rail.railMath.getLength()), false);
		final double dx = position.x() - x;
		final double dy = position.y() - y;
		final double dz = position.z() - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private String signature() {
		int hash = simulator.rails.size() * 31 + 1;
		for (final Rail rail : simulator.rails) {
			hash = hash * 31 + rail.getHexId().hashCode();
		}
		/*
		 * **道岔位置必须进签名**：走行到哪里为止、哪一段"撞在禁行侧"（{@link Section#blockedAtArrival}）
		 * 都取决于道岔位置，所以扳一次道岔就要重建区间。注意这与"灯守哪几条轨"无关 ——
		 * 守的轨不随位置变（用户裁定），变的是**这些轨上的进路通不通**。
		 *
		 * <p>实测后果（这条漏了就会被绕进去）：位置 0 建好的区间带到了位置 1，`blockedAtArrival` 还是
		 * 位置 0 的 false，被切断的那盏灯于是继续读绿/黄 —— 用户问的 {@code -70,-59,-167} 正是这样。</p>
		 */
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : simulator.mmtrAllTurnouts()) {
			hash = hash * 31 + turnout.key().hashCode();
			hash = hash * 31 + simulator.mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		}
		hash = hash * 31 + simulator.mmtrSignals.signals.size();
		for (final Map.Entry<String, SignalEntry> entry : simulator.mmtrSignals.signals.entrySet()) {
			hash = hash * 31 + entry.getKey().hashCode();
			hash = hash * 31 + Float.floatToIntBits(entry.getValue().angle);
			hash = hash * 31 + (entry.getValue().target == null ? 0 : entry.getValue().target.hashCode());
			/*
			 * **显式绑定（点选绑定）必须进签名**。
			 *
			 * <p>这份签名决定"要不要重建区间"；绑定改的正是"这盏灯守哪几根轨"，漏掉它，重建就不会发生：
			 * 指令回了"已绑上"、存档也写了，但区间与灯态还是旧的（实测：绑定后 boundRails 不变，
			 * 于是解绑又被当成添加，同一条轨越点越多）。这类"改了个看不见的字段"的缓存失效问题
			 * 极难从现象反推，所以在签名里把整份绑定列表都算进去。</p>
			 */
			for (final String railHex : entry.getValue().rails) {
				hash = hash * 31 + railHex.hashCode();
			}
		}
		return Integer.toHexString(hash);
	}

	private static double clamp(double value, double min, double max) {
		return value < min ? min : Math.min(value, max);
	}

	private static String shortHex(String hex) {
		return hex.length() <= 6 ? hex : hex.substring(0, 6);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
