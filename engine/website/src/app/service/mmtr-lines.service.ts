import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {DimensionService} from "./dimension.service";
import {SLOW_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

/** One automatic line (线路自动识别): id/name + its rails in chain order. */
export interface MmtrLine {
	id: string;
	name: string;
	lengthM: number;
	rails: string[];
}

/**
 * Lines data plane: polls the engine's automatic line detection feed. The partition is stable for
 * a fixed world, so a slow poll is enough; the map colors every rail by its line membership.
 */
@Injectable({providedIn: "root"})
export class MmtrLinesService {
	public readonly lines = signal<MmtrLine[]>([]);
	public readonly loaded = signal(false);
	private readonly lineOfRail = new Map<string, string>();
	private timeoutId = 0;

	private readonly httpClient = inject(HttpClient);
	private readonly dimensionService = inject(DimensionService);
	private readonly destroyRef = inject(DestroyRef);

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => clearTimeout(this.timeoutId));
	}

	private mapUrl(endpoint: string) {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/${endpoint}?dimension=${this.dimensionService.getDimensionIndex()}`;
	}

	private poll() {
		this.httpClient.get<{data: {lines: MmtrLine[]}}>(this.mapUrl("mmtr-lines")).subscribe({
			next: response => {
				const lines = response.data?.lines ?? [];
				this.lineOfRail.clear();
				for (const line of lines) {
					for (const hex of line.rails) {
						this.lineOfRail.set(hex, line.id);
					}
				}
				this.lines.set(lines);
				this.loaded.set(true);
				this.schedule();
			},
			error: () => {
				this.schedule();
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), SLOW_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** Line id owning the given rail hex, or undefined when the rail belongs to no detected line. */
	public lineOf(hex: string): string | undefined {
		return this.lineOfRail.get(hex);
	}
}
