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
 *   <li><b>岔尖（toe）</b>：存在一根轨，从它开进去**另外两根都在前方**（cos &gt; {@link #MIN_STEM_COS}）
 *       —— 那一侧永远连通，另外两根则是二选一。几何上至多只有一个岔尖；找不到就是三条线在一个点上
 *       交汇（直角三岔口 / 三角线），不是一进两出；</li>
 *   <li><b>正线远端 / 岔股</b>：岔尖那两根里**更直的一根**叫正线远端（位置 0 开向它），另一根叫岔股；
 *       更直的那根也必须在轴线的 45° 以内（{@link #MIN_THROUGH_COS}），否则是三条线交汇
 *       （三开道岔现实里要两组可动件，本模型一个节点只有一个）；</li>
 *   <li><b>对称人字岔</b>：两根进路一样斜时没有"直通"可言（cos 都 &lt; {@link MmtrPoint#COS_STRAIGHT}），
 *       位置 0/1 只表示"先开哪一条" —— 判据**不因此拒绝**它（用户 2026-09-14：正人字形逻辑上与单开道岔无异）。
 *       实测本节点 {@code -67,-60,-139}：南进向看岔股 cos=+0.49（左前方）→ 根部 = 南；北进向看岔股
 *       cos=-0.49（在身后）→ 北是"被断开的那一侧"，与用户说的 1 时北侧禁止通行完全一致。</li>
 * </ol>
 */
public final class MmtrTurnout {

	/** 位置：0 = 正线贯通（岔股禁止通行）；1 = 岔股开放（正线被断开的那一侧禁止通行）。 */
	public static final int NORMAL = 0;
	public static final int REVERSE = 1;

	/** 岔股至少要比正线方向偏出这么多（cos ≤ 0.1 ≈ 偏 84° 以上）才算"看得见分出去"，否则是直角三岔口。 */
	private static final double MIN_STEM_COS = 0.1;

	/**
	 * 岔尖的更直那一根进路至少要有这么直（cos 0.707 ≈ 45° 以内），否则是三条线在一个点上交汇。
	 *
	 * <p>为什么要有这道门槛：{@code -19,-60,51} 那种**对称人字岔**（两根进路各偏 29°/37°）是道岔，
	 * 而三条线互成 120° 的三角线不是 —— 现实里后者要用**两组可动件串起来**做（三开道岔），
	 * 而本模型一个节点只有一个可动件，表达不了"三个状态"，所以宁可不建模也不猜。</p>
	 */
	private static final double MIN_THROUGH_COS = 0.707;

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
	 * <h3>判据是"岔尖测试"，不是"有没有一段直线正线"（两轮现场修正）</h3>
	 * <p><b>第一轮（浅岔口）</b>：老实现找直股对的办法是"遍历腿表，遇到互为直股的一对就记下来"——
	 * **最后遇到的那一对获胜**，而外层是 {@code Object2ObjectOpenHashMap}（键 = 轨 hex），遍历顺序与
	 * 几何无关。当岔股只偏十几度（两条平行正线之间的渡线/汇入：横移 6 格要 23~36 格才走完，
	 * 偏角必然只有 9.5°~14.6°）时，三根轨里有**三对**都够格"直股"：真共线的那一对互直度 1.00，
	 * 含岔股的两对是 cos(9.5°) ≈ 0.986。于是：</p>
	 * <ul>
	 *   <li>若含岔股的一对获胜 → 把**岔股当成正线**、把正线的一端当成岔股（模型倒置）；</li>
	 *   <li>若获胜的那一对恰好是"岔股看不见正线另一端"的组合 → 找不到根部 → 返回 null，
	 *       网页退化成 legacy 卡片，**T1 的物理互斥在这一处完全不生效**。</li>
	 * </ul>
	 * <p>实测证据：{@code -147,-60,-169} 与 {@code -155,-60,-189} 的方位角多重集完全相同
	 * （21.8°/158.2°/180°），却一个被认出一个没有；{@code -170,-60,-289}（渡线的一端）根本没有模型。
	 * 第一轮的修法是改成"取互直度最大的一对"，答案因此只由几何决定。</p>
	 *
	 * <p><b>第二轮（对称人字岔）</b>：用户 2026-09-14 指出 {@code -19,-60,51} 是**正人字形**、
	 * 逻辑上与单开道岔无异。上一轮那个"必须存在一对近乎共线的直股（cos ≥
	 * {@link MmtrPoint#COS_STRAIGHT}）"就是**代理失真**：它描述的是单开道岔的<em>样子</em>，
	 * 而对称人字岔两根进路一样斜（实测偏 29.4°/36.6°），根本没有"直线正线"，于是整处被误判成
	 * "不是道岔"。真正的物理事实只有一条：**一个可动件、两根互斥进路、一个岔尖** —— 判据因此改成
	 * 岔尖测试（见类注释），"更直的那根"只用来决定位置 0 开向谁、以及是否值得叫它"正线"。</p>
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
		// 轨的"名字"用**远端坐标**：hex 对负坐标全是 FFFFFFFFFFFFFF…（实测：一条指令读出来三个一模一样的
		// 名字，等于没写）。操作者一直是按坐标说话的，诊断就该按坐标说。
		final Object2ObjectOpenHashMap<String, Position> labels = new Object2ObjectOpenHashMap<>();
		if (neighbours != null) {
			neighbours.forEach((farEnd, rail) -> labels.put(rail.getHexId(), farEnd));
		}
		final String verdict = turnout == null
			? "结论：**不是**一处可建模的单开道岔（网页会退化成 legacy 卡片，物理互斥不生效）\n"
			: "结论：认成 1 处单开道岔 —— 根部 " + label(labels, turnout.stemRailHex) + " / 正线远端 " + label(labels, turnout.farRailHex)
				+ " / 岔股 " + label(labels, turnout.branchRailHex) + "（位置 0 = 根部↔正线远端，位置 1 = 根部↔岔股）\n";
		return verdict + out;
	}

	/**
	 * 这个节点**为什么不是**一处单开道岔（一行字，给网页与指令用）；是道岔时返回空串。
	 *
	 * <p>为什么要有它：用户 2026-09-14 要求"道岔的呈现要统一" —— 不是单开道岔的节点不该换一套卡片，
	 * 而应该在**同一张卡片**上说明为什么它不是。这一行与 {@link #resolve} 走同一段判定代码：
	 * 拿的就是判定时写下的最后一句（每条否定路径都只写一句，成功路径的最后一句是岔尖/对称说明，
	 * 所以只有返回 null 时这段文字才是"原因"）。</p>
	 */
	public static String rejectionReason(Position node, @Nullable Object2ObjectOpenHashMap<Position, Rail> neighbours) {
		final StringBuilder out = new StringBuilder();
		if (analyse(node, neighbours, out) != null) {
			return "";
		}
		final String[] lines = out.toString().split("\n");
		return lines.length == 0 ? "" : lines[lines.length - 1].trim();
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
				out.append("  进向 ").append(label(farByRail, hex)).append(" 能续行到：");
				final ObjectArrayList<MmtrPointLeg> legs = legsByApproach.get(hex);
				if (legs == null || legs.isEmpty()) {
					out.append("（一根都没有 —— 别的轨都在它身后被排除）\n");
					continue;
				}
				for (final MmtrPointLeg leg : legs) {
					out.append(leg.kind).append(' ').append(label(farByRail, leg.railHex)).append("(cos ").append(num(leg.cos)).append(") ");
				}
				out.append('\n');
			}
		}

		/*
		 * **判据：先找岔尖（toe），再定两条进路。**
		 *
		 * <p>一处道岔的物理事实是"一个可动件 + 两条互斥进路 + 一个岔尖"：岔尖那一侧永远连通，
		 * 另外两根则是二选一。所以从几何上认它的判据就是**岔尖测试**：存在一根轨，从它开进去
		 * 另外两根都在前方（cos &gt; {@link #MIN_STEM_COS}）。</p>
		 *
		 * <p>用户 2026-09-14 指出这里原来用错了代理判据："存在一对近乎共线的直股"只是**单开道岔**
		 * 的样子；<b>对称人字岔（正人字形）</b>两根进路一样斜，没有哪一根是直线正线，于是被误判成
		 * "不是道岔"。实测 {@code -19,-60,51}：西轨是岔尖（到 (-35,52)），另两根到 (1,38)/(1,64)
		 * 分别在前方 cos 0.87 / 0.80 —— 结构与单开道岔**完全同构**，只是没有"正线"可言。</p>
		 *
		 * <p>保留一道角度门槛：岔尖的**更直那一根**进路必须在轴线的 45° 以内
		 * （{@link #MIN_THROUGH_COS}）。否则就是三条线在一个点上交汇（三开道岔 / 三角线），
		 * 现实里那是**两组可动件串起来**做的，而本模型一个节点只有一个可动件 —— 宁可不建模，也不猜。</p>
		 */
		String stemHex = null;
		double stemCosFar = Double.NaN;
		double stemCosBranch = Double.NaN;
		String farHex = null;
		String branchHex = null;
		if (out != null) {
			out.append("  岔尖候选（从它开进去，另外两根都在前方 = cos > ").append(num(MIN_STEM_COS)).append("）：\n");
		}
		for (final String candidate : rails) {
			double firstCos = Double.NaN;
			double secondCos = Double.NaN;
			String firstHex = null;
			String secondHex = null;
			for (final String other : rails) {
				if (other.equals(candidate)) {
					continue;
				}
				final double cos = legCos(legsByApproach.get(candidate), other);
				if (Double.isNaN(cos) || cos <= MIN_STEM_COS) {
					continue;
				}
				// 两根里更直的那根当"正线远端"，另一根当"岔股"
				if (firstHex == null || cos > firstCos + 1.0e-9) {
					secondHex = firstHex;
					secondCos = firstCos;
					firstHex = other;
					firstCos = cos;
				} else {
					secondHex = other;
					secondCos = cos;
				}
			}
			if (out != null) {
				out.append("    ").append(label(farByRail, candidate)).append("：")
					.append(firstHex == null ? "（没有前方续行）" : label(farByRail, firstHex) + " cos " + num(firstCos)
						+ (secondHex == null ? "（只有一根在前方）" : "、" + label(farByRail, secondHex) + " cos " + num(secondCos)))
					.append('\n');
			}
			if (firstHex == null || secondHex == null) {
				continue; // 不是岔尖：只有一根（或没有）在前方
			}
			stemHex = candidate;
			farHex = firstHex;
			branchHex = secondHex;
			stemCosFar = firstCos;
			stemCosBranch = secondCos;
			break; // rails 已按 hex 定序 ⇒ 结果确定；几何上至多一个岔尖
		}
		if (stemHex == null) {
			note(out, "找不到岔尖（没有任何一根轨能同时「迎着」开出另外两根）：三条线在一个点上交汇 / 直角三岔口 —— 不是一进两出，不建模");
			return null;
		}
		if (stemCosFar < MIN_THROUGH_COS) {
			note(out, "更直的那根进路 " + label(farByRail, farHex) + " 偏离岔尖轴线太多（cos " + num(stemCosFar)
				+ " < " + num(MIN_THROUGH_COS) + "）：三条线在此交汇（三开道岔 / 三角线，现实里要两组可动件），本模型一个节点只有一个可动件 —— 不建模");
			return null;
		}
		if (out != null) {
			out.append("  岔尖 = ").append(label(farByRail, stemHex)).append("（两侧都迎着开得出去）\n");
			out.append("  正线远端 = ").append(label(farByRail, farHex)).append("（cos ").append(num(stemCosFar)).append("，更直的那根）")
				.append("；岔股 = ").append(label(farByRail, branchHex)).append("（cos ").append(num(stemCosBranch)).append("）\n");
			if (stemCosFar < MmtrPoint.COS_STRAIGHT || stemCosBranch < MmtrPoint.COS_STRAIGHT) {
				out.append("  注意：这根「正线」并不近似直线（cos ").append(num(stemCosFar)).append(" < ").append(num(MmtrPoint.COS_STRAIGHT))
					.append("）= **对称人字岔**：两条进路都算分岔，位置 0/1 只是「先开哪一条」，没有「直通」的含义\n");
			}
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

	/**
	 * 一根轨在诊断里的"名字"：**它在这个节点上的远端坐标**。
	 *
	 * <p>为什么不用 hex：负坐标的 hex 全是 {@code FFFFFFFFFFFFFF…}，实测一条 {@code point why} 打出来
	 * 三个一模一样的名字，等于没写。操作者一直是按坐标说话的，诊断就按坐标说。</p>
	 */
	private static String label(@Nullable Object2ObjectOpenHashMap<String, Position> farByRail, @Nullable String hex) {
		if (hex == null) {
			return "（无）";
		}
		final Position far = farByRail == null ? null : farByRail.get(hex);
		return far == null ? shortHex(hex) : "(" + far.getX() + "," + far.getZ() + ")";
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
