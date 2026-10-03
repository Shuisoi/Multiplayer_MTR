package org.mtr.core.path;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.net.MmtrMotionFrame;

import java.util.List;
import java.util.Map;

/**
 * **① 的 {@code LEGS} 增量接到客户端镜像的腿阴影上**（notes/369 §4.4 / S3b）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>客户端摆车走的是 {@code vehicleExtraData.immutablePath} —— 一份**腿阴影**：车尾 → 车头、
 * 车头正好落在快照那一刻的 {@code railProgress} 上。它在旧实现里只在 ② 的整份快照里重建，
 * 而 ② 只在"走行器新踏上一根轨"时带来新阴影 —— 60 km/h 下每 25–100 m 一次，也就是 1.5–6 秒。
 * 于是"车开起来"被切成一段一段：车头走到阴影末端就停住（摆车只能把它夹回去），等下一拍再跳。
 * 这就是用户实机报的**「车移动依旧是卡的」**。</p>
 *
 * <p>修法不是在客户端硬猜位置，而是**把服务端已经有的那条腿列表重放出来**：服务端的
 * {@code LEGS} 记录说"从车尾端丢 n 根、车头端新加这几根"（{@code MmtrMotionFrame#legDelta}），
 * 这里就把它落到路径上，并**按上一根的 {@code endDistance} 续算累计里程** —— 于是新腿与旧腿在
 * 同一个坐标空间里接上，{@code railProgress}（客户端本地积分出来的那个值）自然走进新腿。</p>
 *
 * <p>notes/375 补上的那一半：增量只能表达"旧表是新的前缀"，而**换端**（同一批轨、相反顺序）
 * 不是增量。那时记录换成"整表 + 锚点 + 逐腿方向"（{@link #replaceWholeTable}）——
 * 客户端重建出来的表与服务端逐字段一致，于是换端之后车照样在阴影里开，不再"不动 + 瞬移"。</p>
 *
 * <h2>三条"不猜"的规矩（错一条的表现都是"某一节车摆到别的轨上"，很贵）</h2>
 * <ol>
 *   <li><b>没有基准就不接</b>：路径为空（镜像刚建起来、② 的整份还在路上）⇒ 返回 {@link Reason#NO_BASE}，
 *       等 ② 把完整路径与锚点带来（≤1 秒）。凭空的锚点会让整列车错位。</li>
 *   <li><b>整表替换就整表重建</b>：服务端说"整张表换掉"（换端/换向：同一批轨、相反顺序）时，
 *       按<b>锚点</b>（{@code anchorM}，表的起点里程）与<b>逐腿方向位</b>重建整张表 ——
 *       两者都来自服务端，客户端一个数都不猜。</li>
 *   <li><b>接不上就一根都不动</b>：hex 查不到轨、或新腿的接入端不等于当前游标（列表不同源）⇒
 *       <b>原子地</b>放弃这一次增量（连"该丢的尾巴"也不丢），把原因报给调用方记账。
 *       **绝不按"声明顺序"猜方向** —— 轨的 hex id 是声明顺序，与行驶方向无关。</li>
 * </ol>
 *
 * <p><b>为什么"不接"必须是原子的</b>（notes/375 实机）：原来的写法是"先丢车尾端、再试着接车头端"，
 * 接不上时**已经丢掉了**。换端那一刻服务端发的是反序表，客户端丢了几根、接一根都接不上 ⇒
 * 手上剩下一张**残表**（甚至只剩车头那一根），而残表的末端里程远在车后面 ⇒
 * 渲染侧每帧把车夹回阴影末端（车**不动**），直到镜像被整份快照重建（几十秒后）才**瞬移**过去。
 * 现在的顺序是"先验证、后落表"，验证不过就整张表原样不动。</p>
 *
 * <p>另外还有一条**只与丢有关**的保护：车尾端的腿只有整根都在车尾之后
 * （{@code endDistance <= tailDistanceM}）才允许丢。否则客户端本地积分稍落后于服务端时，
 * 会把车底下那一根丢掉 —— 那时 {@code Utilities#getIndexFromConditionalList} 找不到落点，
 * 整车会按"最后一条腿"画（瞬移）。丢不动就多留一根，代价只是列表长一点。</p>
 */
public final class MmtrLegAppender {

	private MmtrLegAppender() {
	}

	/**
	 * 把一次 {@code LEGS} 记录落到 {@code path} 上（**原地**修改）。
	 *
	 * @param path                 客户端镜像的路径（车尾 → 车头；会被就地修改）
	 * @param droppedFromTrainTail 要从**开头**（车尾端）丢掉的条数；{@code MmtrMotionFrame#LEGS_FULL_REPLACE}
	 *                             或 {@code >= path.size()} = 整表替换
	 * @param anchorM              表的起点里程（整表替换时用它生成累计里程）
	 * @param newLegs              车头端的新腿（增量）或整张表（替换），**按行驶顺序**，带方向位
	 * @param railIdMap            客户端本地轨表（{@code Data#railIdMap}）
	 * @param tailDistanceM        车尾在**本路径坐标空间**里的位置（= {@code railProgress - 车长}），
	 *                             只用来判断"这根腿还能不能丢"
	 */
	public static Applied apply(ObjectArrayList<PathData> path, int droppedFromTrainTail, double anchorM, List<MmtrMotionFrame.Leg> newLegs, Map<String, Rail> railIdMap, double tailDistanceM) {
		/*
		 * **整表替换只认服务端明说的那一个值**（{@link MmtrMotionFrame#LEGS_FULL_REPLACE}），
		 * 不从"要丢的比手上还多"这种数上**猜**：那个数只说明"我手上这张表与服务的不同源"
		 * （镜像刚重建、上一张表是坏的），而那种时候服务端给的 {@code newLegs} 只是它那张表的**后半段** ——
		 * 拿它配整张表的锚点重建，里程会整体偏掉（表现是整列车沿轨错位）。
		 * 这种情形一律**拒绝并保留旧表**，等服务端下一拍那张整表（20 秒兜底，或表形一变就有）。
		 */
		if (droppedFromTrainTail == MmtrMotionFrame.LEGS_FULL_REPLACE) {
			return replaceWholeTable(path, anchorM, newLegs, railIdMap);
		}
		if (path.isEmpty()) {
			// 没有基准、也不是整表：等 ② 的整份（或下一拍那张整表）。这条路上"猜"的代价是整列车错位。
			return new Applied(0, 0, Reason.NO_BASE);
		}

		/*
		 * 先**验证**整段新腿能不能接上（一根接不上这一次就什么都不做，见类注释"原子"那一段）。
		 * 方向位是服务端给的：接入端就是它，不再由 hex 的书写方向或"上一根"去猜。
		 */
		Position cursor = endPosition(path.get(path.size() - 1));
		final ObjectArrayList<PathData> appended = new ObjectArrayList<>();
		Reason reason = Reason.OK;

		for (final MmtrMotionFrame.Leg leg : newLegs) {
			final Rail rail = railIdMap.get(leg.hexId());
			if (rail == null) {
				reason = Reason.UNKNOWN_RAIL;
				break;
			}
			final Position[] ends = rail.mmtrOrderedPositions();
			final Position entry = leg.entryIsOrdered1() ? ends[0] : ends[1];
			final Position exit = leg.entryIsOrdered1() ? ends[1] : ends[0];
			if (!entry.equals(cursor)) {
				// 新腿与现有表不连着：列表不同源。不猜方向、不动表，停在这里等一张整表。
				reason = Reason.DISCONTINUOUS;
				break;
			}
			// 整根轨、从接入端进：与引擎 {@code MmtrConsistWalker#addLeg} 的构造约定一致。
			appended.add(new PathData(rail, 0L, 0L, 0, entry, exit));
			cursor = exit;
		}

		if (reason != Reason.OK) {
			return new Applied(0, 0, reason);
		}

		int dropped = 0;
		// 车头那一根永远留着（它是"车头在哪根轨上"的唯一判据）。
		while (dropped < droppedFromTrainTail && path.size() > 1) {
			final PathData front = path.get(0);
			if (front.getEndDistance() > tailDistanceM) {
				// 这一根还在车身底下：留着（多留一根不会错位，丢掉会）。
				break;
			}
			path.remove(0);
			dropped++;
		}

		if (!appended.isEmpty()) {
			// 累计里程从"旧表最后一条的末端"续算 —— 这一步就是"重新锚定"，缺了它新腿会从 0 开始，整车瞬移。
			SidingPathFinder.generatePathDataDistances(appended, path.get(path.size() - 1).getEndDistance());
			path.addAll(appended);
		}
		return new Applied(dropped, appended.size(), reason);
	}

	/**
	 * **整表重建**（换端/换向，或客户端手上那张表已经不可信时由服务端发的整表）。
	 *
	 * <p>合法性全靠服务端给的三个数：每根轨的 hex（查本地轨表）、每根腿的**接入端方向位**、
	 * 以及整张表的**起点里程 {@code anchorM}**。三者齐了，客户端重建出来的表与服务端逐字段一致
	 * —— 于是"车头在阴影里"这件事重新成立，渲染不再夹车。</p>
	 *
	 * <p>任何一根轨查不到、或相邻两腿接不上（== 这张表不是一条连续的链）⇒ 整张表原样不动并把原因报出
	 * （宁可继续用旧表画，也不摆一列错位的车）。</p>
	 */
	private static Applied replaceWholeTable(ObjectArrayList<PathData> path, double anchorM, List<MmtrMotionFrame.Leg> newLegs, Map<String, Rail> railIdMap) {
		if (newLegs.isEmpty()) {
			// 空表：服务端说不出"这辆车在哪根轨上"。留着旧表比清空好（清空 = 每节车都找不到落点）。
			return new Applied(0, 0, Reason.EMPTY_TABLE);
		}

		final ObjectArrayList<PathData> rebuilt = new ObjectArrayList<>();
		Position previousExit = null;
		for (final MmtrMotionFrame.Leg leg : newLegs) {
			final Rail rail = railIdMap.get(leg.hexId());
			if (rail == null) {
				return new Applied(0, 0, Reason.UNKNOWN_RAIL);
			}
			final Position[] ends = rail.mmtrOrderedPositions();
			final Position entry = leg.entryIsOrdered1() ? ends[0] : ends[1];
			final Position exit = leg.entryIsOrdered1() ? ends[1] : ends[0];
			if (previousExit != null && !entry.equals(previousExit)) {
				return new Applied(0, 0, Reason.DISCONTINUOUS);
			}
			rebuilt.add(new PathData(rail, 0L, 0L, 0, entry, exit));
			previousExit = exit;
		}

		// 里程从服务端给的锚点起算 —— 这就是"客户端那张表与服务端镜像 railProgress 同坐标系"的全部秘密。
		SidingPathFinder.generatePathDataDistances(rebuilt, anchorM);
		final int previousSize = path.size();
		path.clear();
		path.addAll(rebuilt);
		return new Applied(previousSize, rebuilt.size(), Reason.REPLACED);
	}

	/**
	 * 一根腿**沿行驶方向**的末端（= 下一根腿的接入端）。
	 *
	 * <p>{@code PathData#getOrderedPosition1/2} 是"按坐标大小排序"的两端，方向信息在
	 * {@code reversePositions} 里：它为真时 {@code ordered1} 其实是**终点**。这里把这个换算收在一处，
	 * 免得每个调用点各推算一次。</p>
	 */
	public static Position endPosition(PathData pathData) {
		return pathData.reversePositions ? pathData.getOrderedPosition1() : pathData.getOrderedPosition2();
	}

	/**
	 * 一根腿**沿行驶方向**的起点（= 上一根腿的末端）。与 {@link #endPosition} 互为反面，
	 * 用例与诊断读"这张表到底朝哪边走"时用它。
	 */
	public static Position entryPosition(PathData pathData) {
		return pathData.reversePositions ? pathData.getOrderedPosition2() : pathData.getOrderedPosition1();
	}

	/** 一次增量/整表落地的结果：丢了几根、接了几根、终止原因。 */
	public record Applied(int dropped, int appended, Reason reason) {

		public boolean isEmpty() {
			return dropped == 0 && appended == 0;
		}
	}

	/** 落地为什么停在这里（{@link #OK} / {@link #REPLACED} = 请求的腿全部落地了）。 */
	public enum Reason {
		/** 增量全部接上。 */
		OK,
		/** 整表替换成功（换端/换向，或服务端的定时整表兜底）。 */
		REPLACED,
		/** 没有基准路径（等 ② 的整份，或等下一拍那张整表）。 */
		NO_BASE,
		/** 服务端发来的是一张空表（说不出车在哪根轨上）—— 不动旧表。 */
		EMPTY_TABLE,
		/** hex 在本地轨表里查不到。 */
		UNKNOWN_RAIL,
		/** 新腿与现有腿不连着（列表不同源）。 */
		DISCONTINUOUS
	}
}
