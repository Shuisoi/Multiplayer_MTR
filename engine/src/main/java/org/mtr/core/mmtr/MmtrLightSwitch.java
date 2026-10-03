package org.mtr.core.mmtr;

/**
 * MMTR 列车灯光开关（三档 / 机车四档）与"这一端的灯该亮什么"的**唯一判据**。
 *
 * <h2>用户口径（2026-09-29，灯种口径 2026-10-03 收紧）</h2>
 *
 * <ul>
 *   <li>动车组：<b>每个驾驶室各一个三档开关</b>（编组里两个驾驶室就是 A / B 端，各自一个）；
 *       "在哪个驾驶室驾驶，设置的就是当前驾驶室前的灯光"；<b>默认 = 尾灯红色暗光</b>
 *       （无人照看的车两端都是红标志灯）；</li>
 *   <li>★ <b>一档 = 一种灯</b>（用户 2026-10-03：「实际上动车组只有近光，远光，尾灯（红光）三种。
 *       所以这并不难设置，仅需单独赋予属性，然后识别渲染」）：档位 = 尾灯（红）/ 近光（白，低）/
 *       远光（白，高）。旧的"日间 / 夜间"只是同一盏白灯的强弱，现在直接叫灯的**种类**，
 *       数值不变（2 / 3）以免动到协议；</li>
 *   <li>灯的**颜色**由档位决定（近光/远光 = 白灯罩、尾灯 = 红灯罩），在客户端按端 + 档位
 *       逐 draw 给灯罩那组几何染色（顶点色，见 {@code MmtrHeadlights.lampColor}）；</li>
 *   <li>换向器为 <b>N</b> 时"固定红色"，其余遵循各端自己的开关档位；</li>
 *   <li>机车多一档<b>关闭</b>（循环 关→尾→近→远），留给"连挂时把被挂那一端的尾灯灭掉"那条逻辑 ——
 *       本轮只把档位与判据做出来，自动挂接那一步的接口就是 {@link #OFF} 本身：
 *       将来连挂逻辑只需把被挂那一端的开关写成 {@link #OFF}，灯就真的灭了（不必再改这里）。</li>
 *   <li><b>无人那趟车由引擎自己拨灯</b>（用户口径 2026-10-03「AI 驾驶员也需要控制车灯开关，并且尾部
 *       车灯需要变红」）：自动运行的车头 = 世界时决定的近光/远光前照灯、车尾 = 红尾灯，
 *       见 {@link #autoHeadlightState} / {@link #autoLeadingEnd} / {@link #autoReverser}。</li>
 * </ul>
 *
 * <h2>判据顺序（{@link #lampState}，自上而下第一条命中）</h2>
 *
 * <ol>
 *   <li>本端开关在<b>关闭</b> ⇒ 灭。<b>它压过换向器 N</b> —— 否则"机车挂车时尾灯灭掉"这条永远做不到
 *       （挂车时换向器多半就是 N）。动车组没有关闭档，所以这条对动车组不可达；</li>
 *   <li>换向器 <b>N</b> ⇒ 固定红尾灯（无视档位）；</li>
 *   <li>否则按本端档位（尾灯 / 近光 / 远光）。</li>
 * </ol>
 *
 * <p>为什么这些必须是**纯函数**：真值表可以直接测（{@code MmtrLightSwitchTests}），
 * 而"哪一端是 A / 哪一端是 B"只由几何决定（{@code MmtrVehicleAnchors.engineEndOfSeat}：
 * 车体局部 +Z = 引擎的 B 端），两端状态则来自镜像。渲染侧因此只剩"查表 + 画"。</p>
 */
public final class MmtrLightSwitch {

	/**
	 * 关闭（只有写了 {@code lightSwitch: "LOCO"} 的车底能选到它）：这一端不亮任何灯。
	 *
	 * <p>★ 2026-10-03 口径（用户：「动车组只有近光、远光、尾灯（红光）三种」）：**一个档位 = 一种灯**，
	 * 数值与旧版一致（2 / 3 仍是两个白灯档），所以旧客户端只会把名字读成"日间/夜间"，灯色与亮灭不受影响。</p>
	 */
	public static final int OFF = 0;
	/** 尾灯（红、暗、短射程）：标志灯，**不参与世界照明**，灯罩走红色。 */
	public static final int TAIL = 1;
	/** 近光（白、低亮度、射程近）：自动档在白天用它。 */
	public static final int LOW = 2;
	/** 远光（白、高亮度、射程远）：自动档在夜间用它。 */
	public static final int HIGH = 3;
	/** 出厂默认 = 尾灯红色暗光（"默认为尾灯红色暗光"）。 */
	public static final int DEFAULT = TAIL;

	/** 引擎的 A 端（与 {@code MmtrVehicleAnchors.engineEndOfSeat} 同一套：1 = A，2 = B）。 */
	public static final int END_A = 1;
	/** 引擎的 B 端。 */
	public static final int END_B = 2;

	/**
	 * 自动运行（"AI 驾驶员"，见 {@link #autoHeadlightState}）算作**白天**（用近光）的第一个小时。
	 * 与 MC 自己的白天同口径：raw 0..12000 = 06:00..18:00。
	 */
	public static final int AUTO_DAY_FIRST_HOUR = 6;
	/** 自动运行算作**夜**（用远光）的第一个小时（{@code [06, 18)} = 白天，其余 = 夜）。 */
	public static final int AUTO_NIGHT_FIRST_HOUR = 18;

	/** 车底配置（{@code consist-types.json}）里声明灯光开关档数的键。 */
	public static final String JSON_KEY = "lightSwitch";
	/** 机车：多一档"关闭"。 */
	public static final String VALUE_LOCO = "LOCO";
	/** 动车组：三档（尾 / 近 / 远）。也是**缺省**。 */
	public static final String VALUE_MU = "MU";

	private MmtrLightSwitch() {
	}

	/** 这个整数是不是一个已知档位（不做机车/动车组的可用性判断，见 {@link #sanitize}）。 */
	public static boolean isKnown(int state) {
		return state >= OFF && state <= HIGH;
	}

	/**
	 * 把任意输入钳成一个**这列车底真能用**的档位：动车组收到"关闭"⇒ 退回默认（尾灯）。
	 *
	 * <p>服务端守卫用它（客户端可能推上来任何整数：恶意、旧版本、或者在一列动车组上按了机车的四档循环）。</p>
	 */
	public static int sanitize(int state, boolean hasOff) {
		if (state == OFF) {
			return hasOff ? OFF : DEFAULT;
		}
		return isKnown(state) ? state : DEFAULT;
	}

	/** 按一下开关（用户口径：机车 关→尾→近→远→关；动车组 尾→近→远→尾）。 */
	public static int cycle(int current, boolean hasOff) {
		switch (sanitize(current, hasOff)) {
			case OFF:
				return TAIL;
			case TAIL:
				return LOW;
			case LOW:
				return HIGH;
			default:
				return hasOff ? OFF : TAIL;
		}
	}

	/** 某一端的开关档位；{@code end} 不是 B 就按 A 端（端只有两个值，不做第三种兜底）。 */
	public static int switchOfEnd(int lightA, int lightB, int end) {
		return end == END_B ? lightB : lightA;
	}

	/**
	 * **一盏灯这一帧该亮什么**（判据顺序见类注释）。
	 *
	 * @param switchState 这一端自己的开关档位（{@link #switchOfEnd}）
	 * @param reverser    引擎权威的换向器位置（{@code 0} = N）
	 * @return {@link #OFF} / {@link #TAIL} / {@link #LOW} / {@link #HIGH}
	 */
	public static int lampState(int switchState, int reverser) {
		if (switchState == OFF) {
			return OFF;
		}
		if (reverser == 0) {
			return TAIL;
		}
		return isKnown(switchState) ? switchState : DEFAULT;
	}

	/**
	 * 这个档位是不是"白色前照灯"（渲染侧据此决定灯罩白/红）。
	 *
	 * <p>近光与远光**都是白灯**（真实动车组就是这样：两个白灯档只差亮度与射程，尾灯才是红的）
	 * ⇒ 灯罩颜色只看这条，亮度/射程再看 {@link #LOW} / {@link #HIGH}。</p>
	 */
	public static boolean isHeadlight(int state) {
		return state == LOW || state == HIGH;
	}

	/*
	 * --------------------------------------------------------------------------------------------
	 * **自动运行（"AI 驾驶员"）的那一档**：用户口径 2026-10-03「AI 驾驶员也需要控制车灯开关，
	 * 并且尾部车灯需要变红」。
	 *
	 * <p>人开车时灯由人拨（L 键 → 镜像 → 渲染，见类注释）；**没有人的那趟车以前谁都不拨**，
	 * 于是 ① 车头一路是暗的（两端都停在出厂值尾灯），② 上一次人工驾驶在车尾那一端留下的白灯
	 * 没人收，车尾就一直是白的（"尾部车灯需要变红"说的就是这一条）。这三条纯函数就是补上那个"拨灯的人"：
	 * 车头 → {@link #autoHeadlightState}（按**世界时**选近光/远光），车尾 → {@link #TAIL}，
	 * 而"哪一端是车头"由走行体的行驶方向给出（{@link #autoLeadingEnd}）。
	 *
	 * <p>三者都是纯函数：世界时、行驶方向、REV 都是入参 —— 于是真值表可以直接测
	 * （{@code MmtrLightSwitchTests}），而且引擎里只有这一处决定"自动车灯亮什么"。
	 * --------------------------------------------------------------------------------------------
	 */

	/**
	 * 自动运行时**车头**那一端该拨到哪一档：天亮 = {@link #LOW}（近光）、天黑 = {@link #HIGH}（远光）。
	 *
	 * <p>为什么这里敢自己选而不是"让 AI 随手开一个"：近光/远光都是白灯、只差亮度与射程，
	 * 而引擎手上**真的有时间** —— 游戏端每 25 秒推一次 {@code SetTime}
	 * （{@code Simulator#setGameTime} / {@code getGameHour()}），它就是世界里的钟。
	 * 白天路面看得清 ⇒ 近光（不晃对向）、夜里 ⇒ 远光（看得远），与真车司机的用法同口径。
	 * 边界取 {@code [06:00, 18:00)}，与 MC 自己的白天同口径。
	 *
	 * @param gameHour 世界时的小时数（0–23）；<b>负值</b>（引擎还不知道世界时）按远光 —— 那一档最亮，
	 *                 比"看不见的前照灯"安全
	 */
	public static int autoHeadlightState(int gameHour) {
		return gameHour >= AUTO_DAY_FIRST_HOUR && gameHour < AUTO_NIGHT_FIRST_HOUR ? LOW : HIGH;
	}

	/**
	 * 自动运行时的**车头端**：走行体的行驶方向说了算（{@code travelsTowardB} ⇒ B 端在前）。
	 *
	 * <p>不用"被占用的驾驶室那一端"：自动运行允许**尾在前**跑（走行体 {@code travelReversed}，
	 * 现场就是"规划不出来 ⇒ 翻一次换向器再试"，见 {@code mmtrMotionSelfArmMission}）。
	 * 车灯要跟着**真实行驶方向**，否则车尾会亮白灯、车头是红的。
	 */
	public static int autoLeadingEnd(boolean travelsTowardB) {
		return travelsTowardB ? END_B : END_A;
	}

	/** {@code end} 的另一端（自动运行的车尾 = 车头的另一端）。 */
	public static int otherEnd(int end) {
		return end == END_B ? END_A : END_B;
	}

	/**
	 * 自动运行时**镜像里的换向器值**：在档（非 N）—— 前进 = {@code 1}，尾在前（REV）= {@code −1}。
	 *
	 * <p>为什么自动车也必须写这个字段：{@link #lampState} 的第 2 条是"换向器 N ⇒ 固定红"，
	 * 而自动车以前**从来没被 {@code applyMmtrControl} 写过** {@code mmtrReverser}
	 * （只有人工控制才会写）⇒ 客户端镜像里它恒为 N，于是"车头开关拨到远光"照样画成红的。
	 * 这一条让镜像说的是实话：这台车此刻在档、朝哪边走。
	 */
	public static int autoReverser(boolean travelReversed) {
		return travelReversed ? -1 : 1;
	}

	/** 档位的中文名（HUD、日志、动作栏共用一份说法）。 */
	public static String label(int state) {
		switch (state) {
			case OFF:
				return "关闭";
			case TAIL:
				return "尾灯";
			case LOW:
				return "近光";
			case HIGH:
				return "远光";
			default:
				return "未知(" + state + ")";
		}
	}

	/**
	 * 一行说清两端现在的灯（HUD 与"5 秒一行"的日志共用）。
	 *
	 * <p>换向器 N 时**实际生效的是"两端红"**，所以这里报的是生效后的结果而不是开关本身 ——
	 * 否则司机会看到"开关写着远光、车却是红的"，只能靠猜。</p>
	 */
	public static String describe(int lightA, int lightB, int reverser, boolean hasOff) {
		final int stateA = lampState(sanitize(lightA, hasOff), reverser);
		final int stateB = lampState(sanitize(lightB, hasOff), reverser);
		final String suffix = reverser == 0 ? "（换向N：固定红）" : "";
		return "A端=" + label(stateA) + " B端=" + label(stateB) + suffix;
	}

	/**
	 * 车底配置里 {@code lightSwitch} 的值 → 有没有"关闭"档。
	 *
	 * <p>缺省（没写）与 {@code MU} 一样是 false：不动既有车底的行为（零回归）。</p>
	 */
	public static boolean parseOffPosition(String value) {
		return VALUE_LOCO.equalsIgnoreCase(value == null ? "" : value.trim());
	}

	/** {@code lightSwitch} 的值是不是认识的（写错了要点名，不静默退回）。 */
	public static boolean isKnownConfigValue(String value) {
		final String trimmed = value == null ? "" : value.trim();
		return VALUE_LOCO.equalsIgnoreCase(trimmed) || VALUE_MU.equalsIgnoreCase(trimmed);
	}
}
