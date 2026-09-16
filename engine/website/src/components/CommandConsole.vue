<script setup lang="ts">
import {computed, nextTick, onMounted, ref, useTemplateRef, watch} from "vue";
import {fetchCommandLog, sendCommand, type CommandResult} from "@/api/commands";
import {pendingCommand, takePendingCommand} from "@/domain/consoleBridge";

/**
 * 指令栏：在这里直接对世界下指令（`vehicle spawn …` / `signal set …` / `server restart` …）。
 *
 * <h3>为什么值得单独做</h3>
 * <p>地图只能"看"。而世界里的事（在某条股道生成一列车、把某个道岔扳向侧线、重启服务端）
 * 本来只存在"改 JSON 再重启"这一条路。有了指令通道之后，这些事情变成**即时**的：
 * 输入一条名词打头的指令，引擎当场执行，回复里带回受影响的 id 与实情。</p>
 *
 * <h3>只显示服务端说的话</h3>
 * <p>回复里的 `namespace`/`verb`/`affected`/`lines` 全部原样显示，前端不加工、不翻译。
 * 指令认不出名词时服务端会回**用法表**，那张表直接铺在日志里就是最好的帮助——所以这里
 * 连"帮助"按钮都不需要另做一份，否则两份用法早晚会不一致。</p>
 *
 * <h3>历史来自服务端</h3>
 * <p>日志是引擎里的 `mmtrCommandLog`，**网页发的指令与游戏端写进来的行（如信号灯扫描统计）
 * 都在同一份历史里**。所以打开面板时读一次、之后每次执行用回复里带回来的 `log` 刷新，
 * 不在前端另记一份——两处记录必然有一天对不上。</p>
 */

const result = defineEmits<{(event: "changed"): void}>();

const open = ref(false);
const input = ref("");
const busy = ref(false);
const log = ref<readonly string[]>([]);
/** 最近一次回复：ok=false 时把用法表也留在日志里，不用另开错误框。 */
const last = ref<CommandResult | null>(null);
const history = ref<string[]>([]);
const historyIndex = ref(-1);
const logBox = useTemplateRef<HTMLElement>("logBox");
/** 输入行：地图送指令进来时要把光标放到这里（见 acceptPendingCommand）。 */
const inputBox = useTemplateRef<HTMLInputElement>("inputBox");

/**
 * 只读指令不会改世界，执行完就不必重取拓扑。
 *
 * <p>判断依据是**动词**而不是整条指令文本：`vehicle list` 与 `vehicle spawn` 的同名词不同动词，
 * 前者重取只是白跑一次接口。</p>
 */
const READ_ONLY_VERBS = new Set(["list", "query", "info", "status", "topology", "signals", "trains", "points", "sections", "depots"]);

/** 需要重取拓扑的指令（真正改动了世界的那几条）。 */
function mutatesWorld(namespace: string, verb: string): boolean {
	if (namespace === "world") {
		return verb !== "scan-signals";
	}
	return !READ_ONLY_VERBS.has(verb);
}

/** 停机类指令：引擎会退出，所以要提前告诉用户"这不是失败，是它真的停了"。 */
function isShutdown(namespace: string, verb: string): boolean {
	return namespace === "server" && (verb === "restart" || verb === "stop" || verb === "shutdown");
}

/** 常用指令：点一下填进输入框，省得记语法（语法本身仍以服务端用法表为准）。 */
const starters = [
	"query depots",
	"vehicle list",
	"signal list",
	"point list",
	"query sections",
	"point locks",
	"point unlock --all",
];

async function refreshLog() {
	try {
		const data = await fetchCommandLog();
		log.value = data.log ?? [];
	} catch (error) {
		log.value = [...log.value, `读取命令日志失败：${error instanceof Error ? error.message : String(error)}`];
	}
}

function scrollToEnd() {
	void nextTick(() => {
		const box = logBox.value;
		if (box) {
			box.scrollTop = box.scrollHeight;
		}
	});
}

async function openPanel() {
	open.value = true;
	await refreshLog();
	scrollToEnd();
}

/*
 * 地图上的节点/灯可以把一条指令**送**进来（见 domain/consoleBridge.ts）：
 * 打开面板、把指令填进输入行、光标放到那里 —— 不自动回车。
 *
 * 为什么不自动执行：送到这里的既有只读的 `signal why …`，也有 `signal bind …` 这类改世界的；
 * 自动回车会让"看一眼再按"变成"按了才知道发了什么"。填好、聚焦，按一下回车即可。
 */
function acceptPendingCommand() {
	const command = takePendingCommand();
	if (!command) {
		return;
	}
	void openPanel().then(() => {
		input.value = command;
		void nextTick(() => inputBox.value?.focus());
	});
}

watch(pendingCommand, acceptPendingCommand, {flush: "post"});
onMounted(() => {
	if (pendingCommand.value) {
		acceptPendingCommand();
	}
});

function closePanel() {
	open.value = false;
}

/**
 * 一键解锁人工锁。
 *
 * <p>为什么要一个直接执行的按钮、而不是只放一条"常用指令"：道岔人工锁是**永久生效直到解锁**的
 * （落盘在 `mmtr-points.json`），而界面（地图节点菜单）只能**逐进向**解 —— 人工搬岔一次锁的是三条进向，
 * 其中没有按钮的那些永远解不掉，现场表现就是"网页上锁闭显示 0、重启后锁全回来"。
 * 引擎里有全解锁入口（`point unlock --all`，清的是引擎自己持有的锁键），这里把它一次发出去。</p>
 */
async function unlockAllPoints() {
	if (busy.value) {
		return;
	}
	input.value = "point unlock --all";
	await run();
}

async function run() {
	const command = input.value.trim();
	if (!command || busy.value) {
		return;
	}
	busy.value = true;
	last.value = null;
	// 先把自己发的那条放进本地日志尾部：网络往返期间用户能看到"我确实发了"
	log.value = [...log.value, `> ${command}`];
	scrollToEnd();

	try {
		const reply = await sendCommand(command);
		last.value = reply;
		// 服务端回复里带着完整日志（含它自己写进去的这条指令与详情），直接采用
		if (reply.log) {
			log.value = reply.log;
		}
		if (isShutdown(reply.namespace, reply.verb)) {
			log.value = [...log.value, "（引擎即将退出：这条连接会断，属于预期。由 scripts/dev-server.ps1 拉起的服务端会自己回来。）"];
		}
		if (reply.ok && mutatesWorld(reply.namespace, reply.verb)) {
			result("changed");
		}

		history.value = [command, ...history.value.filter(item => item !== command)].slice(0, 30);
		historyIndex.value = -1;
		input.value = "";
	} catch (error) {
		const text = error instanceof Error ? error.message : String(error);
		// 停机类指令会让连接在回复前就断掉：这不是错误，别把它显示成失败
		const shutdownLike = /server (restart|stop|shutdown)/.test(command);
		log.value = [...log.value, shutdownLike
			? `（连接已断开：${text}）引擎正在停机，属于预期；由 scripts/dev-server.ps1 拉起的服务端会自己回来。`
			: `[发送失败] ${text}`];
	} finally {
		busy.value = false;
		scrollToEnd();
	}
}

/** ↑ / ↓ 翻历史：与终端一致的习惯。 */
function onKeydown(event: KeyboardEvent) {
	if (event.key === "ArrowUp") {
		const items = history.value;
		if (items.length === 0) {
			return;
		}
		historyIndex.value = Math.min(historyIndex.value + 1, items.length - 1);
		input.value = items[historyIndex.value] ?? "";
		event.preventDefault();
	} else if (event.key === "ArrowDown") {
		if (historyIndex.value <= 0) {
			historyIndex.value = -1;
			input.value = "";
		} else {
			historyIndex.value--;
			input.value = history.value[historyIndex.value] ?? "";
		}
		event.preventDefault();
	} else if (event.key === "Escape") {
		closePanel();
	}
}

const okText = computed(() => (last.value ? (last.value.ok ? "成功" : "未执行") : ""));

watch(open, value => {
	if (value) {
		scrollToEnd();
	}
});

onMounted(() => {
	// 不自动打开：指令栏是"要用的时候才要"的东西，开机铺一屏日志只会挡住地图
	void refreshLog();
});

defineExpose({openPanel});
</script>

<template>
	<div class="console" :class="{open}">
		<!-- 收起状态：只有一枚按钮，不占地图 -->
		<button v-if="!open" class="tab" type="button" @click="openPanel">
			指令栏
			<span class="hint">回车执行 · 名词打头</span>
		</button>

		<section v-else class="panel">
			<header class="head">
				<span class="title">指令栏</span>
				<span class="sub">名词打头 · 服务端当场执行并回复受影响的对象</span>
				<span class="spacer"/>
				<span v-if="last" class="verdict" :class="last.ok ? 'ok' : 'bad'">
					{{ last.namespace || "?" }} {{ last.verb || "?" }} · {{ okText }}
					<template v-if="last.affected.length > 0"> · 影响 {{ last.affected.length }} 项</template>
				</span>
				<button class="action" type="button" :disabled="busy" @click="unlockAllPoints">一键解锁人工锁</button>
				<button class="action" type="button" @click="refreshLog">刷新日志</button>
				<button class="action" type="button" @click="closePanel">收起</button>
			</header>

			<div ref="logBox" class="log">
				<div v-if="log.length === 0" class="empty">还没有命令记录。</div>
				<div
					v-for="(line, index) in log"
					:key="index"
					class="line"
					:class="{
						cmd: line.startsWith('> '),
						ok: line.startsWith('[ok]'),
						bad: line.startsWith('[失败]'),
						note: line.startsWith('    ') || line.startsWith('（'),
					}"
				>{{ line }}</div>
			</div>

			<!-- 受影响的 id：单独列出来，这样"到底改了哪个对象"是能核对的 -->
			<div v-if="last && last.affected.length > 0" class="affected">
				<span class="label">受影响</span>
				<code v-for="id in last.affected" :key="id" class="id">{{ id }}</code>
			</div>

			<div class="starters">
				<span class="label">常用</span>
				<button v-for="item in starters" :key="item" class="chip" type="button" @click="input = item">{{ item }}</button>
			</div>

			<div class="entry">
				<span class="prompt">›</span>
				<input
					ref="inputBox"
					v-model="input"
					class="input"
					type="text"
					spellcheck="false"
					autocomplete="off"
					placeholder="vehicle spawn saf101 --siding=4894120133460482950"
					:disabled="busy"
					@keydown="onKeydown"
					@keydown.enter.prevent="run"
				/>
				<button class="action primary" type="button" :disabled="busy || !input.trim()" @click="run">
					{{ busy ? "执行中…" : "执行" }}
				</button>
			</div>
		</section>
	</div>
</template>

<style scoped>
.console {
	position: absolute;
	left: 0;
	right: 0;
	bottom: 0;
	display: flex;
	justify-content: center;
	pointer-events: none;
}

.tab {
	position: absolute;
	right: 12px;
	bottom: 10px;
	display: flex;
	align-items: baseline;
	gap: 8px;
	padding: 4px 12px;
	font-family: var(--font-ui);
	font-size: 12px;
	color: var(--fg-secondary);
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	cursor: pointer;
	pointer-events: auto;
}

.tab:hover {
	color: var(--fg);
	border-color: #4a4a4a;
}

.tab .hint {
	color: var(--fg-faint);
}

.panel {
	display: flex;
	flex-direction: column;
	width: 100%;
	max-height: 46vh;
	background: var(--glass);
	background-image: var(--glass-fog);
	border-top: 1px solid var(--hairline);
	backdrop-filter: blur(14px);
	pointer-events: auto;
}

.head {
	display: flex;
	align-items: center;
	gap: 10px;
	padding: 7px 12px;
	border-bottom: 1px solid var(--line);
}

.title {
	font-family: var(--font-title);
	font-size: 13px;
	color: var(--fg);
}

.sub {
	font-size: 11px;
	color: var(--fg-faint);
}

.spacer {
	flex: 1;
}

.verdict {
	font-family: var(--font-value);
	font-size: 11px;
}

.verdict.ok {
	color: var(--ok);
}

.verdict.bad {
	color: var(--warn);
}

.log {
	flex: 1;
	min-height: 96px;
	margin: 0;
	padding: 8px 12px;
	overflow: auto;
	font-family: var(--font-value);
	font-size: 12px;
	line-height: 1.55;
	color: var(--fg-dim);
	white-space: pre-wrap;
	word-break: break-all;
}

.log .line.cmd {
	color: var(--fg);
}

.log .line.ok {
	color: var(--ok);
}

.log .line.bad {
	color: var(--danger);
}

.log .line.note {
	color: var(--fg-secondary);
}

.empty {
	color: var(--fg-faint);
}

.affected,
.starters {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 6px;
	padding: 6px 12px;
	border-top: 1px solid var(--line);
	font-size: 11px;
}

.label {
	color: var(--fg-faint);
}

.id {
	padding: 1px 6px;
	color: var(--fg-secondary);
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
}

.chip {
	padding: 1px 8px;
	font-family: var(--font-value);
	font-size: 11px;
	color: var(--fg-dim);
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	cursor: pointer;
}

.chip:hover {
	color: var(--fg);
	border-color: #4a4a4a;
}

.entry {
	display: flex;
	align-items: center;
	gap: 8px;
	padding: 8px 12px 10px;
	border-top: 1px solid var(--line);
}

.prompt {
	color: var(--accent);
	font-family: var(--font-value);
}

.input {
	flex: 1;
	padding: 5px 8px;
	font-family: var(--font-value);
	font-size: 12px;
	color: var(--fg);
	background: #000;
	border: 1px solid var(--line);
	border-radius: var(--radius);
	outline: none;
}

.input:focus {
	border-color: var(--accent);
}

.input::placeholder {
	color: var(--fg-faint);
}

.action {
	padding: 4px 12px;
	font-family: var(--font-ui);
	font-size: 12px;
	color: var(--fg-secondary);
	background: var(--panel);
	border: 1px solid var(--line);
	border-radius: var(--radius);
	cursor: pointer;
}

.action:hover:not(:disabled) {
	color: var(--fg);
	border-color: #4a4a4a;
}

.action:disabled {
	color: var(--fg-faint);
	cursor: default;
}

.action.primary {
	color: var(--fg);
	border-color: var(--accent);
	background: var(--accent-soft);
}

.action.primary:hover:not(:disabled) {
	background: rgba(0, 120, 215, 0.18);
}
</style>
