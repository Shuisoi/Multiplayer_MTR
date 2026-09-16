package org.mtr.core.servlet;

import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

/**
 * Bounded-by-memory FIFO queue used to hand work off to the simulator thread.
 *
 * <p>Backed by a {@link LinkedBlockingDeque} so producers can {@link #put(Object) put} from any
 * thread without blocking the simulator. The simulator drains the queue once per tick via
 * {@link #process(Consumer)}.</p>
 *
 * @param <T> element type the queue holds
 */
@Log4j2
public final class MessageQueue<T> {

	private final LinkedBlockingDeque<T> linkedBlockingDeque = new LinkedBlockingDeque<>();

	/**
	 * Enqueue {@code object}. If the calling thread is interrupted while blocked, the interrupt
	 * flag is restored and the failure is logged so the caller can react instead of silently
	 * losing the message (see CODE_STYLES §3.14).
	 */
	public void put(T object) {
		try {
			linkedBlockingDeque.put(object);
		} catch (InterruptedException e) {
			// Restore the interrupt flag so callers higher up the stack can react to the
			// interruption — the alternative is silently swallowing it (see CODE_STYLES §3.14).
			Thread.currentThread().interrupt();
			log.error("Interrupted while enqueuing message", e);
		}
	}

	/**
	 * Drain every queued element on the calling thread, feeding each into {@code callback} in
	 * arrival order. Returns once the queue is empty.
	 */
	public void process(Consumer<T> callback) {
		while (true) {
			final T object = linkedBlockingDeque.poll();
			if (object == null) {
				break;
			} else {
				callback.accept(object);
			}
		}
	}

	/**
	 * Take one element off the head, or {@code null} when the queue is empty.
	 *
	 * <p>Unlike {@link #process(Consumer)} this lets the caller decide after <em>every</em> element
	 * whether it can afford another one — which is how the web queue honours its per-tick time
	 * budget (notes/172): a caller with a deadline cannot hand the whole queue to a drain loop that
	 * runs to empty.</p>
	 */
	public @Nullable T poll() {
		return linkedBlockingDeque.poll();
	}

	/**
	 * How many elements are waiting right now. Used for the backlog line in the health summary —
	 * a growing web queue is the first sign that the browser is asking for more than the tick can pay.
	 */
	public int size() {
		return linkedBlockingDeque.size();
	}
}
