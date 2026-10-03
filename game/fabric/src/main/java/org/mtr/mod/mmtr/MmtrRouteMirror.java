package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketMmtrRoutes;

import java.util.HashMap;
import java.util.Map;

/**
 * MMTR route-mirror pusher (A2): once per server tick, compare the engine's derived route views
 * (locked path + PENDING entry rails) against the last push and broadcast only on change. An idle
 * world with no route set sends nothing, and a route going SET/PENDING reaches every client in the
 * same tick the interlocking changes - so the signal heads never disagree with the engine.
 */
public final class MmtrRouteMirror {

	/** Last pushed signature per world id (a server can host several worlds/simulators). */
	private static final Map<String, String> LAST_SIGNATURE = new HashMap<>();

	private MmtrRouteMirror() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(MmtrRouteMirror::tick);
	}

	private static void tick(MinecraftServer minecraftServer) {
		/*
		 * 本方法逐段计时（notes/337）。它是**已知的头号热点**：notes/335 §4 的看门狗栈就是
		 * `MmtrRouteMirror.tick → lampBindings → protectedRailsOf → resolveProtectedRail → project`，
		 * 而 notes/336 §3 量到"每 ~1 分钟落后 40 s"。而这一切发生在 `vehicles=0`、没有网页构建的时候 ——
		 * 也就是说纯属每-tick 全量重算。
		 *
		 * 计时点刻意按"引擎算结论"的那五步分段：谁贵、贵在几盏灯×几根轨上，一行就能读出来。
		 * 探针关着时 `begin()` 返回 0，`end()` 直接返回 —— 不改变任何行为（notes/328 §R：只许逐位相同的加速）。
		 */
		final long probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
		try {
			tickMeasured(minecraftServer);
		} finally {
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.routeMirror", probeT);
			// 三个 MMTR 每-tick 钩子里本类最先注册、却在这里最后收尾：见 MmtrTickProbe 的说明。
			MmtrTickProbe.onHooksFinished(minecraftServer);
		}
	}

	private static void tickMeasured(MinecraftServer minecraftServer) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			final String worldId = Init.getWorldId(new World(serverWorld));
			final Simulator simulator = main.getSimulator(worldId);
			if (simulator == null) {
				continue;
			}
			long step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final Object2ObjectOpenHashMap<String, ObjectArrayList<String>> nextRails = simulator.mmtrRoutes.setMainRouteNextRails();
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.nextRails", step);

			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final ObjectOpenHashSet<String> pendingEntries = simulator.mmtrRoutes.pendingEntryRails();
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.pendingEntries", step);

			// ④: junctions the engine cannot clear (undecided points or a fouled clearance zone) - the
			// client's chain counts a step through them as occupied, so the lights agree with the holds.
			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final ObjectOpenHashSet<String> restrictedNodes = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, simulator.mmtrOccupancyTrees());
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.restrictedNodes", step);

			// S4: every lamp's v2 display. The engine owns the 闭塞区间 v2 walk, so it ships its conclusion
			// per lamp; the renderer (which works per rail block) looks its own lamp key up.
			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final Object2ObjectOpenHashMap<String, String> lampAspects = simulator.mmtrSections.lampAspectNames(simulator.mmtrOccupancyTrees(), restrictedNodes::contains);
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.lampAspects", step);
			/*
			 * 守轨（绑定工具的叠加层用）：每盏灯守哪几根轨，由**引擎**算。
			 *
			 * <p>客户端自己按几何推一遍必然与引擎分叉 —— 而"灯到底守哪根轨"正是拿绑定工具时要看的东西，
			 * 看错就等于白看。所以照 S4 的老规矩：引擎算结论，客户端只显示。</p>
			 */
			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final Object2ObjectOpenHashMap<String, ObjectArrayList<String>> lampRails = new Object2ObjectOpenHashMap<>();
			simulator.mmtrSignals.signals.forEach((key, entry) -> {
				final ObjectArrayList<String> rails = simulator.mmtrSections.protectedRailsOf(entry);
				if (!rails.isEmpty()) {
					lampRails.put(key, rails);
				}
			});
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.lampRails", step);
			/*
			 * 区间叠加层（拿信号灯建轨时画带，notes/291）：每条区间一行、每段弧窗一条带。
			 *
			 * <p>颜色由**引擎**贪心分配（相连的两段异色，见 MmtrSectionOverlay）——"哪两段算相连"
			 * 是拓扑结论，客户端自己推必然分叉。这里只把它拍成扁平字符串。</p>
			 */
			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final ObjectArrayList<String> sectionBands = sectionBandWire(simulator);
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.sectionBands", step);

			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			final String signature = nextRails.toString() + "|" + pendingEntries.toString() + "|" + restrictedNodes.toString() + "|" + lampAspects.toString() + "|" + lampRails.toString() + "|" + sectionBands.toString();
			final boolean unchanged = signature.equals(LAST_SIGNATURE.get(worldId));
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.signature", step);
			/*
			 * "变了没"必须也数出来（notes/337）：`server.mirror.changed` ÷ 服务端 tick 数 = 真的推了几次。
			 *
			 * <p>这是判断"能不能改脏标记"的**前置读数** —— 全都变（比值≈1）说明世界每 tick 都在动，缓存没用；
			 * 比值≈0 说明绝大部分 tick 算完之后发现没变，那一次全量重算就是纯浪费。先量，再谈改不改。</p>
			 */
			org.mtr.core.mmtr.probe.MmtrProbe.hit(unchanged ? "server.mirror.unchanged" : "server.mirror.changed");
			if (unchanged) {
				continue;
			}
			LAST_SIGNATURE.put(worldId, signature);
			final String content = PacketMmtrRoutes.contentOf(nextRails, pendingEntries, restrictedNodes, lampAspects, lampRails, sectionBands);
			final org.mtr.mapping.holder.ServerWorld mappedWorld = new org.mtr.mapping.holder.ServerWorld(serverWorld);
			step = org.mtr.core.mmtr.probe.MmtrProbe.begin();
			MinecraftServerHelper.iteratePlayers(mappedWorld, serverPlayerEntity -> Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketMmtrRoutes(content)));
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.mirror.broadcast", step);
		}
	}

	/**
	 * 区间叠加层的扁平载荷：{@code [轨hex, 区间id, 色号, 方向x‰, 方向z‰, 弧起cm, 弧止cm]} × n。
	 *
	 * <p>方向与弧长都取整：区域设置若用逗号作小数点，客户端的 {@code Double.parseDouble} 会静默失败，
	 * 而弧长算错的表现是"带子画到别的轨上"。</p>
	 */
	private static ObjectArrayList<String> sectionBandWire(Simulator simulator) {
		final ObjectArrayList<String> wire = new ObjectArrayList<>();
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.OverlaySection section : simulator.mmtrSections.sectionOverlay()) {
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.RailSpan span : section.spans) {
				wire.add(org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(span.railHex));
				wire.add(section.id);
				wire.add(Integer.toString(section.colorIndex));
				wire.add(Integer.toString((int) Math.round(section.headingX * 1000)));
				wire.add(Integer.toString((int) Math.round(section.headingZ * 1000)));
				wire.add(Integer.toString((int) Math.round(span.arcFromM * 100)));
				wire.add(Integer.toString((int) Math.round(span.arcToM * 100)));
			}
		}
		return wire;
	}

	/** Forget the cached signature (world unload / tests) so the next tick re-pushes. */
	public static void reset() {
		LAST_SIGNATURE.clear();
	}
}
