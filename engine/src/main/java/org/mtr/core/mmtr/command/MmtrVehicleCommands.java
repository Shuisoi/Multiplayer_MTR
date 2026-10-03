package org.mtr.core.mmtr.command;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code vehicle …}：车辆的生成、删除、查看。
 *
 * <h3>生成是怎么做到的</h3>
 * <p>引擎里"生成一辆车"原本只有一条路：股道上放着编组模板（{@code setVehicleCars}）+
 * {@code mmtrManualSpawn} 标记，然后等 {@code Simulator} 每 tick 走 {@code Siding.simulateVehicles} 时生成。
 * 那条路是**异步**的（"下次 tick 才出现"），而且对调用方没有即时反馈。</p>
 *
 * <p>这里走的是引擎自己已有的即时路径：{@code Simulator.instantDeployDepots} 把 depot 快进一整天来立刻
 * 生成车辆。{@code vehicle spawn} 对**一条股道**做同样的事（{@code Simulator.mmtrDeploySiding}），
 * 所以命令返回时车已经在世界上，可以直接用返回的车辆 id 去核对。</p>
 */
final class MmtrVehicleCommands {

	private MmtrVehicleCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "spawn":
				return spawn(simulator, positional, options);
			case "remove":
			case "delete":
				return remove(simulator, positional, options);
			case "list":
				return list(simulator, options);
			default:
				return MmtrCommandDispatcher.usage("vehicle 支持 spawn / remove / list");
		}
	}

	/**
	 * {@code vehicle spawn <车型>... [--siding=<id|名> | --depot=<id|名> [--index=n]] [--count=n] [--dry-run]}
	 *
	 * <p>车型是游戏里已定义的车辆 id（见 {@code mmtr-rolling-stock} 里各股道模板的车，例如 {@code saf101}）。
	 * 多写几个就是多节编组，按书写顺序连挂。</p>
	 *
	 * <p><b>逐车参数</b>（notes/271 片 1）见 {@link #carsFor}：{@code --powered=} / {@code --consist-type=} /
	 * {@code --load=} / {@code --coupler-after=} / {@code --manual-coupler=}，都是**逗号列表**。</p>
	 */
	private static MmtrCommandDispatcher.Result spawn(Simulator simulator, List<String> positional, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "vehicle", "spawn");
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("vehicle spawn 需要车型，例如：vehicle spawn saf101 --depot=test1");
		}

		final Depot depotHint = MmtrCommandDispatcher.findDepot(simulator, options.get("depot"));
		if (options.containsKey("depot") && depotHint == null) {
			result.line("找不到车辆段「" + options.get("depot") + "」");
			return new MmtrCommandDispatcher.Result(false, "vehicle", "spawn");
		}
		/*
		 * `--rail=<轨hex>`：**在任意一根轨上落车**（用户 2026-09-13 要的能力）。
		 *
		 * <p>引擎的车辆挂在股道上，所以要给那根轨临时建一条股道 —— 见 {@code Simulator.mmtrSpawnOnRail}。
		 * 这列车只存在于引擎（网页/闭塞/占用树看得到，游戏里看不到），适合"摆一列车看灯色与闭塞"的验证。</p>
		 */
		final String railHex = options.get("rail");
		if (railHex != null && !railHex.isEmpty()) {
			org.mtr.core.data.Rail rail = null;
			for (final org.mtr.core.data.Rail candidate : simulator.rails) {
				if (candidate.getHexId().equalsIgnoreCase(railHex.trim())) {
					rail = candidate;
					break;
				}
			}
			if (rail == null) {
				result.line("轨图里没有这条轨：" + railHex);
				return new MmtrCommandDispatcher.Result(false, "vehicle", "spawn");
			}
			final double carLengthOnRail = MmtrCommandDispatcher.longOption(options, "length", 16);
			final var railCars = carsFor(positional, options, carLengthOnRail);
			final double railTotal = Siding.getTotalVehicleLength(railCars);
			final java.util.List<org.mtr.core.data.Position> ends = new java.util.ArrayList<>();
			final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
			ends.add(ordered[0]);
			ends.add(ordered[1]);
			final Vehicle onRail = simulator.mmtrSpawnOnRail(rail, railCars);
			if (onRail == null) {
				final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "vehicle", "spawn");
				failure.line("在这条轨上生成失败：编组 " + Math.round(railTotal) + " m，轨长 " + Math.round(rail.railMath.getLength())
					+ " m（放不下，或这条轨走不出站场）。");
				return failure;
			}
			final String railShort = rail.getHexId().length() <= 8 ? rail.getHexId() : rail.getHexId().substring(0, 8) + "…";
			result.add(String.valueOf(onRail.getId()));
			result.line("已生成：车辆 id " + onRail.getId() + "，编组 " + String.join("+", positional)
				+ "，放在轨 " + railShort + "（" + ends.get(0).getX() + "," + ends.get(0).getZ() + "）→("
				+ ends.get(1).getX() + "," + ends.get(1).getZ() + "），长 " + Math.round(rail.railMath.getLength()) + " m");
			result.line("注意：这是**引擎里**的车（临时股道），游戏世界看不到它；要游戏里也有，只能在游戏里放。");
			return result;
		}
		final Siding siding = MmtrCommandDispatcher.resolveSiding(simulator, options, depotHint);
		if (siding == null) {
			// 把可选目标列出来，比只报"找不到"有用得多
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "vehicle", "spawn");
			failure.line("没有指定要生成在哪条股道，或指定的股道不存在。");
			failure.line("可用的车辆段与股道：");
			for (final String line : MmtrQueryCommands.describeDepots(simulator, true)) {
				failure.line("  " + line);
			}
			failure.line("用法：vehicle spawn " + String.join(" ", positional) + " --depot=<车辆段名|id> [--index=n]");
			return failure;
		}

		// 编组：按用户写的车型逐个建车卡；车身长度取一个合理值（MTR 的模板车多为 16 m）。
		// 默认给动力（管理员要的是一列能开的车），--unpowered / --powered=… 用来挂无动力的挂车。
		final double carLength = MmtrCommandDispatcher.longOption(options, "length", 16);
		final var cars = carsFor(positional, options, carLength);

		final double totalLength = Siding.getTotalVehicleLength(cars);
		if (totalLength > siding.getRailLength() + 1e-6) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "vehicle", "spawn");
			failure.line("编组放不下：需要 " + Math.round(totalLength) + " m，股道只有 " + Math.round(siding.getRailLength()) + " m（" + siding.getDepotName() + " / " + siding.getId() + "）");
			return failure;
		}

		final int count = Math.max(1, MmtrCommandDispatcher.intOption(options, "count", 1));
		if (count > 1) {
			result.line("（--count=" + count + " 只对第一条生效：一条股道同时只停一组车）");
		}

		if (options.containsKey("dry-run")) {
			result.line("试算（未生成）：股道 " + siding.getDepotName() + " / " + siding.getId()
				+ "，编组 " + String.join("+", positional) + "，总长 " + Math.round(totalLength) + " m / 股道 " + Math.round(siding.getRailLength()) + " m");
			return result;
		}

		final Vehicle vehicle = simulator.mmtrSpawnOnSiding(siding, cars);
		if (vehicle == null) {
			final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "vehicle", "spawn");
			failure.line("生成失败：股道 " + siding.getDepotName() + " / " + siding.getId()
				+ " 上已有在途车辆，或该股道无法走出站场（yard busy/unwalkable）。");
			failure.line("可以先用 vehicle list --depot=" + siding.getDepotName() + " 看看上面有什么。");
			return failure;
		}

		result.add(String.valueOf(vehicle.getId()));
		result.line("已生成：车辆 id " + vehicle.getId() + "，编组 " + String.join("+", positional)
			+ "，停在 " + siding.getDepotName() + " / 股道 " + siding.getId()
			+ "（长 " + Math.round(siding.getRailLength()) + " m）");
		return result;
	}

	/**
	 * 按**逐车参数**建一列车的车卡（notes/271 片 1，用户口径 2026-09-26：逐车参数用与位置对齐的逗号列表）。
	 *
	 * <p>支持的键（都可以只给一项，那一项对全列生效；空项 = "这一节不说"）：</p>
	 *
	 * <ul>
	 *   <li>{@code --powered=false,false,false} —— 这一节出不出牵引（**显式**声明；给了就永不走"借车底"兜底）</li>
	 *   <li>{@code --consist-type=,p1_trailer,p1_trailer} —— 这一节自己的车底 id（空 = 用车型映射/编组缺省）</li>
	 *   <li>{@code --load=0,0.8,0.8} —— 载重比例 0..1（逐车质量 = 整备 + 比例 × 车底载重能力）</li>
	 *   <li>{@code --coupler-after=true,…} —— 这一节之后有车钩（能不能在那儿解挂）</li>
	 *   <li>{@code --manual-coupler=true,…} —— 螺旋车钩（true = 要司机按 K 才挂）</li>
	 * </ul>
	 *
	 * <p>{@code --unpowered} 仍是"全列无动力"的简写，等价于 {@code --powered=false} 且算**显式声明**。</p>
	 */
	static it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleCar> carsFor(List<String> vehicleIds, Map<String, String> options, double carLength) {
		final int carCount = vehicleIds.size();
		final boolean unpoweredAll = options.containsKey("unpowered");
		final var cars = new it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleCar>();
		for (int i = 0; i < carCount; i++) {
			final Boolean poweredOption = MmtrCommandDispatcher.commaListBoolean(options, "powered", i, carCount);
			final boolean powered = poweredOption != null ? poweredOption : !unpoweredAll;
			// "显式"只有两种来源：写了 --powered=… 或写了 --unpowered；否则就是没表态。
			final boolean poweredDeclared = poweredOption != null || unpoweredAll;
			final String consistTypeId = MmtrCommandDispatcher.commaList(options, "consist-type", i, carCount);
			final double loadRatio = MmtrCommandDispatcher.commaListDouble(options, "load", i, carCount, 0);
			final VehicleCar car = MmtrCommandDispatcher.carOf(vehicleIds.get(i), carLength, powered, poweredDeclared,
				consistTypeId == null ? "" : consistTypeId, loadRatio);
			final Boolean couplerAfter = MmtrCommandDispatcher.commaListBoolean(options, "coupler-after", i, carCount);
			if (couplerAfter != null) {
				car.setMmtrCouplerAfter(couplerAfter);
			}
			final Boolean manualCoupler = MmtrCommandDispatcher.commaListBoolean(options, "manual-coupler", i, carCount);
			if (manualCoupler != null) {
				car.setMmtrAutoCoupler(!manualCoupler);
			}
			cars.add(car);
		}
		return cars;
	}

	/** {@code vehicle remove <车辆id|all|--siding=<id>|--depot=<id|名>>} */
	private static MmtrCommandDispatcher.Result remove(Simulator simulator, List<String> positional, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "vehicle", "remove");
		final List<Vehicle> targets = new ArrayList<>();
		int sidingsTouched = 0;

		if (!positional.isEmpty() && positional.get(0).equalsIgnoreCase("all") && !options.containsKey("depot") && !options.containsKey("siding")) {
			// 引擎没有全局车辆集合：按股道收集（与 mmtrFindVehicle 同一手法）
			simulator.sidings.forEach(siding -> siding.iterateVehicles(targets::add));
		} else if (options.containsKey("siding")) {
			final Depot depotHint = MmtrCommandDispatcher.findDepot(simulator, options.get("depot"));
			final Siding siding = MmtrCommandDispatcher.resolveSiding(simulator, options, depotHint);
			if (siding == null) {
				// 注意：**要写在返回的那个 Result 上**。原来这里先往 result 里写、再 `new Result(false,…)` 返回，
				// 于是这句话永远到不了用户（新对象是空的）——失败原因只剩一句"命令失败了"。
				final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "vehicle", "remove");
				failure.line("找不到目标股道（--siding / --depot）");
				return failure;
			}
			sidingsTouched = 1;
			siding.iterateVehicles(targets::add);
		} else if (options.containsKey("depot")) {
			/*
			 * **按车辆段删 = 该车辆段的每一条股道都要遍历**（notes/136 §2 的指令陷阱）。
			 *
			 * <p>修前这里走 {@code resolveSiding}，而它给 {@code --depot} 的语义是"这个段里的第 index 条
			 * 股道"（那是给 {@code vehicle spawn} 用的：一条股道同时只停一组车）。于是
			 * {@code vehicle remove --depot=aassdd} **只清了一条股道**，而用法文字写的是"按车辆段删"、
			 * 结果行也只报了一句"已删除车辆…"—— 看的人会以为删干净了（实测：那段有两辆车，
			 * 第一次只删掉一辆，剩下的只能再按 {@code --siding} 删一次）。</p>
			 *
			 * <p>现在：遍历该段的**全部**股道，并把"扫了几条股道、删了几辆车、哪几条股道本来就有车"
			 * 一并说清楚，免得再出现"以为删干净了"。</p>
			 */
			final Depot depot = MmtrCommandDispatcher.findDepot(simulator, options.get("depot"));
			if (depot == null) {
				final MmtrCommandDispatcher.Result failure = new MmtrCommandDispatcher.Result(false, "vehicle", "remove");
				failure.line("找不到车辆段「" + options.get("depot") + "」");
				return failure;
			}
			final List<Siding> sidings = new ArrayList<>(depot.savedRails);
			sidings.sort(java.util.Comparator.comparingLong(Siding::getId));
			for (final Siding siding : sidings) {
				sidingsTouched++;
				final List<Vehicle> onSiding = new ArrayList<>();
				siding.iterateVehicles(onSiding::add);
				if (!onSiding.isEmpty()) {
					result.line("股道 " + siding.getId() + " 上有 " + onSiding.size() + " 辆车");
				}
				targets.addAll(onSiding);
			}
			result.line("车辆段「" + depot.getName() + "」共 " + sidings.size() + " 条股道，扫到 " + targets.size() + " 辆车");
		} else if (!positional.isEmpty()) {
			for (final String raw : positional) {
				try {
					final Vehicle vehicle = simulator.mmtrFindVehicle(Long.parseLong(raw.trim()));
					if (vehicle != null) {
						targets.add(vehicle);
					} else {
						result.line("找不到车辆 id " + raw);
					}
				} catch (NumberFormatException e) {
					result.line("车辆 id 必须是数字：" + raw);
				}
			}
		} else {
			return MmtrCommandDispatcher.usage("vehicle remove 需要 <车辆id|all|--siding=<id>|--depot=<名>>");
		}

		int removed = 0;
		for (final Vehicle vehicle : targets) {
			final long id = vehicle.getId();
			if (simulator.deleteMmtrVehicle(id)) {
				removed++;
				result.add(String.valueOf(id));
				result.line("已删除车辆 " + id);
			} else {
				result.line("删除失败（可能不在场/已在别处注销）：" + id);
			}
		}
		if (targets.isEmpty()) {
			result.line("没有匹配到任何车辆"
				+ (sidingsTouched > 0 ? "（已扫过 " + sidingsTouched + " 条股道）" : ""));
		} else if (sidingsTouched > 0) {
			result.line("按股道清理完成：扫 " + sidingsTouched + " 条股道，删掉 " + removed + " 辆车");
		}
		return result;
	}

	/** {@code vehicle list [--depot=<id|名>]} */
	private static MmtrCommandDispatcher.Result list(Simulator simulator, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "vehicle", "list");
		final Depot depotHint = MmtrCommandDispatcher.findDepot(simulator, options.get("depot"));
		final int[] shown = {0};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (depotHint != null && (siding.area == null || siding.area.getId() != depotHint.getId())) {
				return;
			}
			shown[0]++;
			result.add(String.valueOf(vehicle.getId()));
			result.line("车辆 " + vehicle.getId()
				+ "  股道=" + siding.getDepotName() + "/" + siding.getId()
				+ "  在途=" + vehicle.getIsOnRoute()
				+ "  车内=" + vehicle.vehicleExtraData.immutableVehicleCars.size() + " 节"
				// notes/354 水牌（PID）：三项都是作业调度器从作业单算好写进镜像的，这里读的正是
				// 客户端那份镜像字段（同一份数据）—— 于是"水牌对不对"在游戏里有一条能查的读数。
				+ "  水牌=" + (vehicle.getMmtrPidServiceFromSync().isEmpty()
					? "（无：不挂在在跑的作业单上）"
					: vehicle.getMmtrPidServiceFromSync()
						+ " 终点 " + (vehicle.getMmtrPidTerminusFromSync().isEmpty() ? "—" : vehicle.getMmtrPidTerminusFromSync())
						+ " 下一站 " + (vehicle.getMmtrPidNextFromSync().isEmpty() ? "—" : vehicle.getMmtrPidNextFromSync())));
		}));
		if (shown[0] == 0) {
			result.line(depotHint == null ? "（世界上没有车辆）" : "（该车辆段没有车辆）");
		}
		return result;
	}

	/** 股道排序（给 --index 用）：按 id 排，保证"第一条"是稳定的。 */
	static List<Siding> sortedSidings(Depot depot) {
		final List<Siding> sidings = new ArrayList<>(depot.savedRails);
		sidings.sort(Comparator.comparingLong(Siding::getId));
		return sidings;
	}
}


