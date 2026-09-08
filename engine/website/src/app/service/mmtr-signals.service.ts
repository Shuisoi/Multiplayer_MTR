import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

export interface MmtrSignalEntry {
	key: string;
	x: number;
	y: number;
	z: number;
	angle: number;
	aspects: number;
	mode: "AUTO" | "BOUND";
	target: string;
	aspect: string;
}

export interface MmtrRailRef {
	hex: string;
	label: string;
}

/**
 * Wayside signals management (信号机管理): polls mmtr-signals, registers/binds/removes entries
 * through mmtr-signal-op, and hosts the OP command input (指令栏). Commands are resolved
 * locally for the time being; fabric-side commands (e.g. /signals scan) plug in later.
 */
@Injectable({providedIn: "root"})
export class MmtrSignalsService {
	public readonly signals = signal<MmtrSignalEntry[]>([]);
	public readonly rails = signal<MmtrRailRef[]>([]);
	public readonly feedback = signal("");
	public readonly commandLog = signal<string[]>([]);
	public readonly loading = signal(true);

	private readonly httpClient = inject(HttpClient);
	private readonly destroyRef = inject(DestroyRef);
	private timeoutId = 0;
	private feedbackTimer = 0;

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => clearTimeout(this.timeoutId));
	}

	private mapUrl(path: string) {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/${path}`;
	}

	private poll() {
		this.httpClient.get<{data: {signals: MmtrSignalEntry[]}}>(this.mapUrl("mmtr-signals")).subscribe({
			next: response => {
				this.signals.set(response.data.signals ?? []);
				this.loading.set(false);
				this.schedule();
			},
			error: error => {
				console.error("mmtr-signals feed failed", error);
				this.loading.set(false);
				this.schedule();
			},
		});
		// Rail picker for covered binds (读取目标): reuse the topology rail list, labelled by coords.
		this.httpClient.get<{data: {rails: {hex: string, x1: number, z1: number, x2: number, z2: number}[]}}>(this.mapUrl("mmtr-topology")).subscribe({
			next: response => {
				this.rails.set((response.data.rails ?? []).map(rail => ({
					hex: rail.hex,
					label: `${rail.hex.slice(0, 12)}… (${rail.x1},${rail.z1})→(${rail.x2},${rail.z2})`,
				})));
			},
			error: error => console.error("mmtr-topology fetch for signal binds failed", error),
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** Register or bind a signal: empty target = AUTO, rail hex target = BOUND. */
	public setSignal(x: number, y: number, z: number, angle: number, aspects: number, targetHex: string) {
		const payload = {x, y, z, angle, aspects, op: "set", target: targetHex ?? ""};
		this.httpClient.post<{data: {ok: boolean}}>(this.mapUrl("mmtr-signal-op"), payload).subscribe({
			next: response => this.setFeedback(response.data?.ok ? `✓ 信号机 (${x},${y},${z}) 已登记` : `✗ 登记失败`),
			error: error => {
				console.error("mmtr-signal-op failed", error);
				this.setFeedback("✗ 信号机登记请求失败：" + (error.status ?? "网络错误"));
			},
		});
	}

	public removeSignal(x: number, y: number, z: number) {
		const payload = {x, y, z, angle: 0, aspects: 2, op: "remove"};
		this.httpClient.post<{data: {ok: boolean}}>(this.mapUrl("mmtr-signal-op"), payload).subscribe({
			next: response => this.setFeedback(response.data?.ok ? `✓ 已移除 (${x},${y},${z})` : `✗ 移除失败或不存在`),
			error: error => {
				console.error("mmtr-signal-op failed", error);
				this.setFeedback("✗ 移除请求失败：" + (error.status ?? "网络错误"));
			},
		});
	}

	/** OP command input (指令栏): resolves known local commands, uploads the rest to the engine
	 * command queue for the game-side executor (fabric) to run. */
	public runCommand(line: string) {
		const cmd = (line ?? "").trim();
		if (!cmd) {
			return;
		}
		const local = this.resolveLocal(cmd);
		if (local) {
			this.commandLog.update(log => [...log.slice(-19), `> ${cmd}`, local]);
			return;
		}
		this.httpClient.post<{data: {ok: boolean, log: string[]}}>(this.mapUrl("mmtr-command"), {command: cmd}).subscribe({
			next: response => {
				const reply = response.data?.ok ? "✓ 已入队，等待游戏侧执行器处理" : "✗ 指令未受理";
				this.commandLog.update(log => [...log.slice(-19), `> ${cmd}`, reply]);
			},
			error: error => {
				console.error("mmtr-command failed", error);
				this.commandLog.update(log => [...log.slice(-19), `> ${cmd}`, "✗ 指令上传失败：" + (error.status ?? "网络错误")]);
			},
		});
	}

	/** Pure-local command help/counts; null when the command must go to the engine queue. */
	private resolveLocal(cmd: string): string | null {
		const parts = cmd.split(/\s+/);
		if (parts[0] === "help") {
			return "可用指令：help / signals list / signals scan（游戏侧执行器扫描登记信号灯）/ signal bind <x y z> <railHex>（或下方表格绑定）";
		}
		if (parts[0] === "signals" && parts[1] === "list") {
			return `已登记信号机 ${this.signals().length} 盏（见下方信号机管理表）`;
		}
		return null;
	}

	public setFeedback(text: string) {
		this.feedback.set(text);
		clearTimeout(this.feedbackTimer);
		this.feedbackTimer = setTimeout(() => this.feedback.set(""), 5000) as unknown as number;
	}
}
