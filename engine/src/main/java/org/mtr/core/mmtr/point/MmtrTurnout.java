package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
 *   <li><b>直股对</b>：互为"最直续行"（两个进向的 cos 都 ≈ +1）的那两根轨 = 正线的两端；</li>
 *   <li><b>岔股</b>：剩下的那根（它从两个正线进向看都是 LEFT/RIGHT）；</li>
 *   <li><b>根部</b>：从哪个正线进向看，岔股的 cos **为正**（"迎着开过去能看到它分出去"）——
 *       实测本节点：南进向看岔股 cos=+0.49（左前方）→ 根部 = 南；北进向看 cos=-0.49（在身后）
 *       → 北是"被断开的那一侧"，与用户说的 1 时北侧禁止通行完全一致。</li>
 * </ol>
 */
public final class MmtrTurnout {

	/** 位置：0 = 正线贯通（岔股禁止通行）；1 = 岔股开放（正线被断开的那一侧禁止通行）。 */
	public static final int NORMAL = 0;
	public static final int REVERSE = 1;

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
	 */
	public static @Nullable MmtrTurnout resolve(Position node, @Nullable Object2ObjectOpenHashMap<Position, Rail> neighbours) {
		if (neighbours == null || neighbours.size() != 3) {
			return null;   // 只认"一进两出"的单开道岔；度 4（交叉）留给以后
		}
		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		neighbours.values().forEach(rail -> {
			if (!rails.contains(rail.getHexId())) {
				rails.add(rail.getHexId());
			}
		});
		if (rails.size() != 3) {
			return null;
		}

		// 对每个进向算出有序腿，用来找"直股对 / 岔股"
		final Object2ObjectOpenHashMap<String, ObjectArrayList<MmtrPointLeg>> legsByApproach = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, Position> farByRail = new Object2ObjectOpenHashMap<>();
		neighbours.forEach((farEnd, rail) -> farByRail.put(rail.getHexId(), farEnd));
		neighbours.forEach((entryEnd, via) -> legsByApproach.put(via.getHexId(),
			MmtrPoint.computeOrderedLegs(node, entryEnd, via, neighbours, null)));

		String straightA = null;
		String straightB = null;
		for (final var entry : legsByApproach.entrySet()) {
			for (final MmtrPointLeg leg : entry.getValue()) {
				if (leg.kind == MmtrPoint.LegKind.STRAIGHT) {
					// 互为直股的成对：A 的直续行是 B，且 B 的直续行是 A
					final ObjectArrayList<MmtrPointLeg> other = legsByApproach.get(leg.railHex);
					if (other == null) {
						continue;
					}
					for (final MmtrPointLeg back : other) {
						if (back.kind == MmtrPoint.LegKind.STRAIGHT && back.railHex.equals(entry.getKey())) {
							straightA = entry.getKey();
							straightB = leg.railHex;
						}
					}
				}
			}
		}
		if (straightA == null || straightB == null) {
			return null;   // 没有直股对：不是单开道岔（例如三岔口 / 交叉）
		}
		final ObjectOpenHashSet<String> throughPair = new ObjectOpenHashSet<>();
		throughPair.add(straightA);
		throughPair.add(straightB);
		String branchHex = null;
		for (final String hex : rails) {
			if (!throughPair.contains(hex)) {
				branchHex = hex;
			}
		}
		if (branchHex == null) {
			return null;
		}

		// 根部 = 从它看岔股 cos > 0（迎着开过去看得到岔股分出去）的那个正线进向；另一侧就是"被断开的一侧"
		//
		// 注意 |cos| 必须**明显非零**：T 形（三岔口，两条臂与正线成直角）里岔股的 cos ≈ 0 —— 那不是一处
		// 单开道岔（现实里是两组道岔背靠背），没有"根部/被切断的一侧"可言，所以直接不认（返回 null），
		// 让它继续走原有的几何规则。
		String stemHex = null;
		String farHex = null;
		for (final MmtrPointLeg leg : legsByApproach.getOrDefault(straightA, new ObjectArrayList<>())) {
			if (!leg.railHex.equals(branchHex)) {
				continue;
			}
			if (Math.abs(leg.cos) <= 0.1) {
				return null;   // 直角三岔口：不是单开道岔
			}
			if (leg.cos > 0) {
				stemHex = straightA;
				farHex = straightB;
			} else {
				stemHex = straightB;
				farHex = straightA;
			}
		}
		if (stemHex == null) {
			return null;   // 岔股不在这个进向的续行里（几何异常）：宁可不动，也不要猜
		}

		final MmtrTurnout turnout = new MmtrTurnout(node.getX(), node.getY(), node.getZ(), stemHex, farHex, branchHex);
		fillLegIndex(turnout, legsByApproach, stemHex);
		fillLegIndex(turnout, legsByApproach, farHex);
		fillLegIndex(turnout, legsByApproach, branchHex);
		return turnout;
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
