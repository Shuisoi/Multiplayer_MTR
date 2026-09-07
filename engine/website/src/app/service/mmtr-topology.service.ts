import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {DimensionService} from "./dimension.service";
import {SLOW_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

/** One real rail segment of the map dimension: its sampled centreline (curve points, world
 * coordinates, sampled along the real RailMath geometry by the engine so bends follow the track). */
export interface MmtrRailSegment {
	hex: string;
	pts: { x: number; z: number }[];
}

/**
 * Rail topology data plane (P2 follow-up): fetches the real track network (every rail with its two
 * endpoints plus all nodes) once per slow cadence; the map draws the rails underneath the fork
 * markers so the operator sees the actual topology - which forks connect to which tracks.
 */
@Injectable({providedIn: "root"})
export class MmtrTopologyService {
	public readonly rails = signal<MmtrRailSegment[]>([]);
	public readonly loaded = signal(false);
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
		this.httpClient.get<{data: {rails: MmtrRailSegment[]}}>(this.mapUrl("mmtr-topology")).subscribe({
			next: response => {
				this.rails.set(response.data?.rails ?? []);
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
}
