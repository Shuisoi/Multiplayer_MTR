package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg;

/**
 * **一处物理道岔**：一个节点上一个可动件、一个位置，两条互斥的进路。
 *
 * <h3>为什么要有这个类（用户 2026-09-13 的规格）</h3>
 * <p>引擎原来把道岔记成"每个进向各一份 0/1"（{@code BranchStore} 键 = 节点 + 进向轨）。
 * 那是**方向视图**，不是物理事实：一处道岔只有一个位置，三份视图不可能各说各话 —— 可实测世界里
 * dev 节点 {@code -67,-60,-139} 的三行**全都是 0**，而其中"从侧线进来"那一行的 0 表示
 * "侧线通往北轨"，与"0 = 正线贯通"直接矛盾。物理上这两条进路**互斥**：同时开放会让列车在尖轨处脱轨。</p>
 *
 * <h3>本节点（{@code -67,-60,-139}）的形态（实测）</h3>
 * <pre>
 *   正线（直股）：(-67,-139) &lt;-&gt; (-67,-167)   ┐ 两者互斥
 *   侧线（岔股）：(-67,-139) &lt;-&gt; (-35,-157)   ┘
 *   根部（始终连通）：(-67,-139) &lt;-&gt; (-67,-103)（南侧，用户指定的"与之相连"那一侧）
 * </pre>
 * <p>用户原话："道岔为 0 时，逻辑上相当于 {@code -67,-60,-167} 节点与之相连；为 1 时
 * {@code -35,-60,-157} 与之相连，同时这二者是互斥的会引发列车脱轨。所以在为 0 时，
 * {@code -67,-60,-139} 到 {@code -35,-60,-157} 这段路就应该以**禁止通行**为其属性，另一侧同理。"</p>
 *
 * <h3>怎么从几何认出这三条</h3>
 * <ol>
 *   <li><b>直股对</b>：互为"最直续行"（两个进向看对方都是直股，cos 都 ≥ {@link MmtrPoint#COS_STRAIGHT}）
 *       的那两根轨 = 正线的两端。浅岔口上**不止一对**够格（岔股只偏 10° 时它自己也够"直"），所以
 *       取**互直度最大**的一对 —— 真共线的那一对互直度 1.00，含岔股的那些只有 cos(偏角) ≈ 0.99。
 *       判据是几何本身，因此与哈希表的遍历顺序无关（见 {@link #resolve}）；</li>
 *   <li><b>岔股</b>：剩下的那根（它从两个正线进向看都是 LEFT/RIGHT，或者干脆在其中一个身后被排除）；</li>
 *   <li><b>根部</b>：从哪个正线进向看，岔股的 cos **为正且明显非零**（"迎着开过去能看到它分出去"）——
 *       实测本节点：南进向看岔股 cos=+0.49（左前方）→ 根部 = 南；北进向看岔股 cos=-0.49（在身后）
 *       → 北是"被断开的那一侧"，与用户说的 1 时北侧禁止通行完全一致。
 *       <b>两个正线进向都要看</b>：浅岔口上"另一个正线进向"会因为岔股几乎在正后方（cos ≈ -0.99）
 *       而根本看不见它，只判一个进向就会把整处道岔判成"不存在"。</li>
 * </ol>
 */
public final class MmtrTurnout {

	/** 位置：0 = 正线贯通（岔股禁止通行）；1 = 岔股开放（正线被断开的那一侧禁止通行）。 */
	public static final int NORMAL = 0;
	public static final int REVERSE = 1;

	/** 岔股至少要比正线方向偏出这么多（cos ≤ 0.1 ≈ 偏 84° 以上）才算"看得见分出去"，否则是直角三岔口。 */
	private static final double MIN_STEM_COS = 0.1;

	public final long nodeX, nodeY, nodeZ;
	/** 根部轨（两个位置都连通的那一侧）。 */
	public final String stemRailHex;
	/** 正线的另一端（位置 0 时与根部连通；位置 1 时**禁止通行**）。 */
	public final String farRailHex;
	/** 岔股（位置 1 时与根部连通；位置 0 时**禁止通行**）。 */
	public final String branchRailHex;
	/**
	 * 各进向轨上"选它"对应的腿序号（{@code MmtrPoint} 的有序腿），键 = 进向轨 hex。
	 *
	 * <p>用它把"节点级的位置"翻译回"每行的 0/1"：任何调用方（网页、指令、旧测试）拿到的
	 * 每行腿号都与物理位置一致，不可能再互相矛盾。</p>
	 */
	public final Object2ObjectOpenHashMap<String, Integer> stemLeg = new Object2ObjectOpenHashMap<>();
	public final Object2ObjectOpenHashMap<String, Integer> farLeg = new Object2ObjectOpenHashMap<>();
	public final Object2ObjectOpenHashMap<String, Integer> branchLeg = new Object2ObjectOpenHashMap<>();

	private MmtrTurnout(long nodeX, long nodeY, long nodeZ, String stemRailHex, String farRailHex, String branchRailHex) {
		this.nodeX = nodeX;
		this.nodeY = nodeY;
		this.nodeZ = nodeZ;
		this.stemRailHex = stemRailHex;
		this.farRailHex = farRailHex;
		this.branchRailHex = branchRailHex;
	}

	public String key() {
		return nodeX + "," + nodeY + "," + nodeZ;
	}

	/** 位置 {@code position} 下，从 {@code viaRailHex} 出发**允许**去的那根轨；禁止通行/无续行时 null。 */
	public @Nullable String continuationFrom(String viaRailHex, int position) {
		final String selected = position == REVERSE ? branchRailHex : farRailHex;
		if (viaRailHex.equals(stemRailHex)) {
			return selected;                       // 根部：去选中的那条进路
		}
		if (viaRailHex.equals(selected)) {
			return stemRailHex;                    // 从选中的进路来：只能回根部
		}
		return null;                               // 未选中的进路 / 岔股：禁止通行
	}

	/** 位置 {@code position} 下**禁止通行**的轨（用户要的"该段路应以禁止通行为其属性"）。 */
	public String prohibitedRailHex(int position) {
		return position == REVERSE ? farRailHex : branchRailHex;
	}

	/** 从某个进向轨看，位置 0/1 分别对应它自己的第几条腿（有序腿序号）。 */
	public int legIndexFor(String viaRailHex, int position) {
		final Integer index = position == REVERSE ? branchLeg.get(viaRailHex) : farLeg.get(viaRailHex);
		return index == null ? -1 : index;
	}

	/**
	 * 反过来：某个进向上的第 {@code leg} 条腿，表示道岔在哪个位置？
	 *
	 * <ul>
	 *   <li>选中"正线远端" → 位置 0；选中"岔股" → 位置 1；</li>
	 *   <li>选中"回根部" → **由进向自己决定**：从岔股回根部必须岔股开放（位置 1），从正线远端回根部
	 *       必须正线贯通（位置 0），从根部出发去别的轨则不带位置意见（{@link Integer#MIN_VALUE}）。
	 *       这一条是实测逼出来的（见 {@code MmtrRouteConflictTests}）：车尾在岔股上时"我要回根部"
	 *       恰恰就是"把岔股扳通"，把它当成"不表态"会让车永远停在岔前；</li>
	 *   <li><b>两根轨任何位置都不相连的组合 → {@link Integer#MIN_VALUE}</b>（调用方要拒绝，不能猜）。
	 *       实测用户就是这样指出来的：从岔股 {@code -35,-157} 那一头**开不到**正线远端 {@code -67,-167}，
	 *       那是背向穿过尖轨。可接口按"第几条腿"给的是**几何腿号**，"从岔股看正线远端"也是一条腿，
	 *       于是老实现把它翻译成位置 0 并**受理**了 —— 网页/指令于是看起来像"这条进路存在"。</li>
	 * </ul>
	 */
	public int positionForLeg(String viaRailHex, int leg) {
		final Integer far = farLeg.get(viaRailHex);
		if (far != null && far == leg) {
			return viaRailHex.equals(branchRailHex) ? Integer.MIN_VALUE : NORMAL;
		}
		final Integer branch = branchLeg.get(viaRailHex);
		if (branch != null && branch == leg) {
			return viaRailHex.equals(farRailHex) ? Integer.MIN_VALUE : REVERSE;
		}
		final Integer stem = stemLeg.get(viaRailHex);
		if (stem != null && stem == leg) {
			if (viaRailHex.equals(branchRailHex)) {
				return REVERSE;
			}
			if (viaRailHex.equals(farRailHex)) {
				return NORMAL;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** 某根轨在这个道岔里是不是"可被选中的进路"（正线远端 / 岔股）。 */
	public boolean isSelectableRoute(String railHex) {
		return railHex.equals(farRailHex) || railHex.equals(branchRailHex);
	}

	/** 某根轨是不是"正线一侧"（根部或正线远端）—— 灯的保护在它被切断时要退守根部。 */
	public boolean isThroughRail(String railHex) {
		return railHex.equals(stemRailHex) || railHex.equals(farRailHex);
	}

	/**
	 * 从**行视图**折出位置：任何一条进向上"显式设成某条进路的腿"都表示那个位置。
	 *
	 * <p>这样写入路径不同也不会分裂：网页/指令写某一行、或者直接改 {@code mmtr-points.json}，
	 * 都能被读成同一个物理位置。行里表示"回根部"的腿（{@code stemLeg}）不带位置意见，跳过。</p>
	 */
	public int positionFromRows(MmtrPointRegistry.BranchStore store, long x, long y, long z) {
		for (final String via : new String[]{stemRailHex, farRailHex, branchRailHex}) {
			if (store == null || !store.contains(x, y, z, via)) {
				continue;
			}
			final int position = positionForLeg(via, store.get(x, y, z, via));
			if (position != Integer.MIN_VALUE) {
				return position;
			}
		}
		return store == null ? NORMAL : store.nodePosition(x, y, z);
	}

	/**
	 * 认出节点 {@code node} 上的一处物理道岔；该节点不是"两进路"岔口（端点、直通、纯交叉）时返回 null。
	 *
	 * <p>只用几何 + 有序腿：不依赖任何人工声明，因此新画的道岔也能立刻被认出来。</p>
	 *
	 * <h3>修前为什么"有的道岔认不出来"（用户 2026-09-14 实测提出）</h3>
	 * <p>老实现找直股对的办法是"遍历腿表，遇到互为直股的一对就记下来"—— **最后遇到的那一对获胜**，
	 * 而外层是 {@code Object2ObjectOpenHashMap}（键 = 轨 hex），遍历顺序与几何无关。当岔股只偏十几度
	 * （两条平行正线之间的渡线/汇入：横移 6 格要 23~36 格才走完，偏角必然只有 9.5°~14.6°）时，
	 * 三根轨里有**三对**都够格"直股"：真共线的那一对互直度 1.00，含岔股的两对是 cos(9.5°) ≈ 0.986。
	 * 于是：</p>
	 * <ul>
	 *   <li>若含岔股的一对获胜 → 把**岔股当成正线**、把正线的一端当成岔股（模型倒置）；</li>
	 *   <li>若获胜的那一对恰好是"岔股看不见正线另一端"的组合 → 找不到根部 → 返回 null，
	 *       网页退化成 legacy 卡片，**T1 的物理互斥在这一处完全不生效**。</li>
	 * </ul>
	 * <p>实测证据：{@code -147,-60,-169} 与 {@code -155,-60,-189} 的方位角多重集完全相同
	 * （21.8°/158.2°/180°），却一个被认出一个没有；{@code -170,-60,-289}（渡线的一端）根本没有模型。
	 * 判据改成"取互直度最大的一对"之后，答案只由几何决定，与遍历顺序无关。</p>
	 */
	public static @Nullable MmtrTurnout resolve(Position node, @Nullable Object2ObjectOpenHashMap<Position, Rail> neighbours) {
		return analyse(node, neighbours, null);
	}

	/**
	 * 把"这个节点为什么是 / 为什么不是一处单开道岔"逐条写出来（{@code point why <x> <y> <z>} 的输出）。
	 *
	 * <p>与 {@link #resolve} 是**同一段判定代码**（{@link #analyse} 的可选输出口），所以这份解释
	 * 不会和真正生效的判定走偏。</p>
	 */
	public static String describeResolution(Position node, @Nullable Object2ObjectOpenHashMap<Position, Rail> neighbours) {
		final StringBuilder out = new StringBuilder();
		final MmtrTurnout turnout = analyse(node, neighbours, out);
		final String verdict = turnout == null
			? "结论：**不是**一处可建模的单开道岔（网页会退化成 legacy 卡片，物理互斥不生效）\n"
			: "结论：认成 1 处单开道岔 —— 根部 " + shortHex(turnout.stemRailHex) + " / 正线远端 " + shortHex(turnout.farRailHex)
				+ " / 岔股 " + shortHex(turnout.branchRailHex) + "（位置 0 = 根部↔正线远端，位置 1 = 根部↔岔股）\n";
		return verdict + out;
	}

	/** 判定主体；{@code out} 非空时把每一步写进去（诊断），为空时是纯计算的热路径。 */
	private static @Nullable MmtrTurnout analyse(Position node, @Nullable Object2ObjectOpenHashMap<Position, Rail> neighbours, @Nullable StringBuilder out) {
		final String at = node.getX() + "," + node.getY() + "," + node.getZ();
		if (neighbours == null || neighbours.size() != 3) {
			note(out, "节点 " + at + " 上相邻轨 " + (neighbours == null ? 0 : neighbours.size()) + " 根：只认「一进两出」的度 3 节点（度 4 的交叉留给以后）");
			return null;
		}

		// 去重（同一根轨可能在邻表里出现两次）并按 hex 定序：下面所有"取哪一个"的选择都不再看哈希顺序
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<String, Position> farByRail = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();
		neighbours.forEach((farEnd, rail) -> {
			if (!railByHex.containsKey(rail.getHexId())) {
				railByHex.put(rail.getHexId(), rail);
				farByRail.put(rail.getHexId(), farEnd);
				rails.add(rail.getHexId());
			}
		});
		if (rails.size() != 3) {
			note(out, "节点 " + at + " 的相邻轨去重后只有 " + rails.size() + " 根：不是单开道岔");
			return null;
		}
		rails.sort(null);

		// 每个进向的有序腿表：从这里开过去，能看到另外哪几根轨、各自多"直"
		final Object2ObjectOpenHashMap<String, ObjectArrayList<MmtrPointLeg>> legsByApproach = new Object2ObjectOpenHashMap<>();
		for (final String hex : rails) {
			legsByApproach.put(hex, MmtrPoint.computeOrderedLegs(node, farByRail.get(hex), railByHex.get(hex), neighbours));
		}

		if (out != null) {
			for (final String hex : rails) {
				final Position far = farByRail.get(hex);
				final double dx = far.getX() - node.getX();
				final double dz = far.getZ() - node.getZ();
				final double length = Math.sqrt(dx * dx + dz * dz);
				out.append("  轨 ").append(shortHex(hex)).append(" → (").append(far.getX()).append(",").append(far.getY()).append(",").append(far.getZ())
					.append(") 距 ").append(num(length)).append(" 格，离开方向 (")
					.append(num(length == 0 ? 0 : dx / length)).append(",").append(num(length == 0 ? 0 : dz / length)).append(")\n");
			}
			for (final String hex : rails) {
				out.append("  进向 ").append(shortHex(hex)).append(" 能续行到：");
				final ObjectArrayList<MmtrPointLeg> legs = legsByApproach.get(hex);
				if (legs == null || legs.isEmpty()) {
					out.append("（一根都没有 —— 别的轨都在它身后被排除）\n");
					continue;
				}
				for (final MmtrPointLeg leg : legs) {
					out.append(leg.kind).append(" ").append(shortHex(leg.railHex)).append("(cos ").append(num(leg.cos)).append(") ");
				}
				out.append('\n');
			}
		}

		// 直股对 = 两根**互为最直续行**的轨；多对够格时取互直度（两个方向 cos 的较小者）最大的那一对。
		// 互直度必须两边都看得见对方：真道岔的岔股从对面看是在身后（cos ≈ -1），那种组合不成对。
		String throughA = null;
		String throughB = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < rails.size(); i++) {
			for (int j = i + 1; j < rails.size(); j++) {
				final double forward = legCos(legsByApproach.get(rails.get(i)), rails.get(j));
				final double backward = legCos(legsByApproach.get(rails.get(j)), rails.get(i));
				final double score = Math.min(forward, backward);
				if (out != null) {
					out.append("  候选直股对 (").append(shortHex(rails.get(i))).append(", ").append(shortHex(rails.get(j))).append(")：互直度 ")
						.append(Double.isNaN(score) ? "—（互相看不见，不成对）" : num(score)).append('\n');
				}
				if (!Double.isNaN(score) && score > bestScore + 1.0e-9) {
					// 严格更大才替换：并列时保持 hex 序在前的那个，结果因此是确定的
					bestScore = score;
					throughA = rails.get(i);
					throughB = rails.get(j);
				}
			}
		}
		if (throughA == null || bestScore < MmtrPoint.COS_STRAIGHT) {
			note(out, "没有互为直股的成对（最直的一对" + (throughA == null ? "不存在" : "只有互直度 " + num(bestScore) + "，低于 " + num(MmtrPoint.COS_STRAIGHT))
				+ "）：三岔口 / 交叉，不是单开道岔");
			return null;
		}

		String branchHex = null;
		for (final String hex : rails) {
			if (!hex.equals(throughA) && !hex.equals(throughB)) {
				branchHex = hex;
			}
		}
		if (branchHex == null) {
			note(out, "直股对之外找不到第三根轨：几何异常，不猜");
			return null;
		}

		// 根部 = 两个正线进向里"看岔股在前面"的那一个。**两个都要看**：浅岔口上另一个进向会因为岔股
		// 几乎在正后方（cos ≈ -0.99 < -0.9）而根本看不见它，只看一个就会把整处道岔判成不存在。
		String stemHex = null;
		double stemCos = Double.NaN;
		double seenCos = Double.NaN;
		for (final String through : new String[]{throughA, throughB}) {
			final double cos = legCos(legsByApproach.get(through), branchHex);
			if (Double.isNaN(cos)) {
				continue;
			}
			if (Double.isNaN(seenCos) || cos > seenCos) {
				seenCos = cos;
			}
			if (cos > MIN_STEM_COS && (stemHex == null || cos > stemCos)) {
				stemCos = cos;
				stemHex = through;
			}
		}
		if (stemHex == null) {
			note(out, "岔股 " + shortHex(branchHex) + " 从两个正线进向都看不见"
				+ (Double.isNaN(seenCos) ? "（都被当成长度近乎相反的回头轨排除）" : "（cos 最大只有 " + num(seenCos) + " ≤ " + num(MIN_STEM_COS) + "）")
				+ "：直角三岔口，现实里是两组道岔背靠背，没有「根部」可言");
			return null;
		}
		final String farHex = stemHex.equals(throughA) ? throughB : throughA;
		if (out != null) {
			out.append("  直股对 = (").append(shortHex(throughA)).append(", ").append(shortHex(throughB)).append(")，互直度 ").append(num(bestScore))
				.append("（最直，所以是正线）\n");
			out.append("  岔股 = ").append(shortHex(branchHex)).append("；根部 = ").append(shortHex(stemHex))
				.append("（它看岔股 cos ").append(num(stemCos)).append(" > 0 = 迎着开过去看得到岔股分出去），正线远端 = ").append(shortHex(farHex)).append('\n');
		}

		final MmtrTurnout turnout = new MmtrTurnout(node.getX(), node.getY(), node.getZ(), stemHex, farHex, branchHex);
		fillLegIndex(turnout, legsByApproach, stemHex);
		fillLegIndex(turnout, legsByApproach, farHex);
		fillLegIndex(turnout, legsByApproach, branchHex);
		return turnout;
	}

	/** 某个进向的腿表里，{@code railHex} 那条腿的 cos；看不见它时 NaN（= 不成对）。 */
	private static double legCos(@Nullable ObjectArrayList<MmtrPointLeg> legs, String railHex) {
		if (legs == null) {
			return Double.NaN;
		}
		for (final MmtrPointLeg leg : legs) {
			if (leg.railHex.equals(railHex)) {
				return leg.cos;
			}
		}
		return Double.NaN;
	}

	private static void note(@Nullable StringBuilder out, String text) {
		if (out != null) {
			out.append(text).append('\n');
		}
	}

	private static String num(double value) {
		return Double.isNaN(value) ? "—" : String.valueOf(Math.round(value * 100.0) / 100.0);
	}

	private static String shortHex(@Nullable String hex) {
		return hex == null || hex.length() <= 12 ? String.valueOf(hex) : hex.substring(0, 12) + "…";
	}

	/** 把"这个进向上三条轨各自是第几条腿"记下来（节点级位置 &lt;-&gt; 每行腿号的翻译表）。 */
	private static void fillLegIndex(MmtrTurnout turnout, Object2ObjectOpenHashMap<String, ObjectArrayList<MmtrPointLeg>> legsByApproach, String viaHex) {
		final ObjectArrayList<MmtrPointLeg> legs = legsByApproach.get(viaHex);
		if (legs == null) {
			return;
		}
		for (int i = 0; i < legs.size(); i++) {
			final String railHex = legs.get(i).railHex;
			if (railHex.equals(turnout.stemRailHex)) {
				turnout.stemLeg.put(viaHex, i);
			} else if (railHex.equals(turnout.farRailHex)) {
				turnout.farLeg.put(viaHex, i);
			} else if (railHex.equals(turnout.branchRailHex)) {
				turnout.branchLeg.put(viaHex, i);
			}
		}
	}
}
