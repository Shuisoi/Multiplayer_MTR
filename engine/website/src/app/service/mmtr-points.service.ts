import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {DimensionService} from "./dimension.service";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

/** One continuation of a turnout (道岔) under the current approach direction. */
export interface MmtrPointLeg {
	hex: string;
	kind: "STRAIGHT" | "LEFT" | "RIGHT" | "OTHER";
}

/**
 * One operator fork: (node x,y,z + approach rail via) with its ordered legs. The leg INDEX is the
 * same index the engine walker/planner elect against (straight > left > right > other), so a click
 * on leg N is exactly the operator setting the walker will honour at runtime.
 */
export interface MmtrPoint {
	x: number;
	y: number;
	z: number;
	via: string;
	form: string;
	legs: MmtrPointLeg[];
	/** Operator manual branch index, -1 when the operator never set it. */
	manual: number;
	locked: boolean;
	/** Authority holder (auto/task owner) and the leg it holds, empty/-1 when free. */
	holder: string;
	holderLeg: number;
	/** Queued auto requests, oldest first: "owner@leg". */
	queue: string[];
}

export interface MmtrPointFeedback {
	text: string;
	ok: boolean;
}

/**
 * Turnout console data plane (道岔控制台, P2): polls the engine's direction-aware point list with
 * live authority state and issues manual operator ops (branch leg set / unset / lock / unlock)
 * through the same mmtr-point-op endpoint the game-side tooling uses.
 */
@Injectable({providedIn: "root"})
export class MmtrPointsService {
	public readonly points = signal<MmtrPoint[]>([]);
	public readonly loading = signal(true);
	public readonly feedback = signal<MmtrPointFeedback | undefined>(undefined);
	private feedbackTimer = 0;
	private timeoutId = 0;

	private readonly httpClient = inject(HttpClient);
	private readonly dimensionService = inject(DimensionService);
	private readonly destroyRef = inject(DestroyRef);

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => {
			clearTimeout(this.timeoutId);
			clearTimeout(this.feedbackTimer);
		});
	}

	private mapUrl(endpoint: string) {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/${endpoint}?dimension=${this.dimensionService.getDimensionIndex()}`;
	}

	private poll() {
		this.httpClient.get<{data: {points: MmtrPoint[]}}>(this.mapUrl("mmtr-points")).subscribe({
			next: response => {
				this.points.set(response.data?.points ?? []);
				this.loading.set(false);
				this.schedule();
			},
			error: () => {
				this.loading.set(false);
				this.schedule();
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** Force an immediate re-poll (after a write) so the console reflects the change quickly. */
	public refresh() {
		clearTimeout(this.timeoutId);
		this.poll();
	}

	/** Throw the fork: operator sets manual branch to the given ordered-leg index. */
	public setBranch(point: MmtrPoint, leg: number) {
		this.op({x: point.x, y: point.y, z: point.z, via: point.via, branch: leg}, `已搬至 leg ${leg}（${point.legs[leg]?.kind ?? ""}）`);
	}

	/** Clear the operator branch (fork returns to "unset": trains wait unless an auto grant holds it). */
	public clearBranch(point: MmtrPoint) {
		this.op({x: point.x, y: point.y, z: point.z, via: point.via, branch: -1}, "已清除人工设岔（未设=等待）");
	}

	/** Operator lock parks the fork for manual use (auto requests queue until unlocked). */
	public setLocked(point: MmtrPoint, locked: boolean) {
		this.op(
			{x: point.x, y: point.y, z: point.z, via: point.via, ...(locked ? {lock: true} : {unlock: true})},
			locked ? "已锁定（自动申请排队）" : "已解锁（自动可接管）",
		);
	}

	private op(body: Record<string, unknown>, okText: string) {
		this.httpClient.post<{data: {ok: boolean}}>(this.mapUrl("mmtr-point-op"), body).subscribe({
			next: response => {
				this.setFeedback(response.data?.ok ? okText : "操作被拒绝", response.data?.ok);
				this.refresh();
			},
			error: () => {
				this.setFeedback("操作请求失败", false);
			},
		});
	}

	private setFeedback(text: string, ok: boolean) {
		this.feedback.set({text, ok});
		clearTimeout(this.feedbackTimer);
		this.feedbackTimer = setTimeout(() => this.feedback.set(undefined), 5000) as unknown as number;
	}

	public static pointKey(point: MmtrPoint): string {
		return `${point.x},${point.y},${point.z}|${point.via}`;
	}

	public static nodeKey(point: MmtrPoint): string {
		return `${point.x},${point.y},${point.z}`;
	}
}
