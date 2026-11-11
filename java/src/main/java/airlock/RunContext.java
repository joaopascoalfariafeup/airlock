package airlock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import airlock.Messages.Entry;

/**
 * The context of one run: what all its threads share (the clock, the event
 * log and the stop flag), and helpers for queues and threads. Every message,
 * internal ones included, is logged with its time, so tests can check the
 * whole system afterwards.
 *
 * <p>One RunContext is created per run, in ScenarioRunner.run, and every thread gets
 * a reference to it. Nothing else is shared between threads: components talk
 * only through queues. Each part is shared safely in its own way:
 * <ul>
 * <li>the clock: {@code start} is final, set before any thread starts, and
 * only read, so it needs no lock;</li>
 * <li>the event log: every access is {@code synchronized} on the list, so one
 * thread at a time adds to it;</li>
 * <li>the stop flag: an {@code AtomicBoolean}, set once by Airlock.stop and
 * read by every thread at least every POLL.</li>
 * </ul>
 */
public final class RunContext {
    /** How long an idle thread waits for a message before it checks whether it must stop. Real time. */
    public static final Duration POLL = Duration.ofMillis(2);
    /** Queue capacity. Each thread has at most a few messages in flight. */
    public static final int QUEUE = 16;

    /** One log entry: a message, and the time it was logged. */
    public record Logged(long time, Entry entry) {}

    /** A finished run. {@code end} is when it stopped, which can be later than the last entry. */
    public record Run(List<Logged> trace, long end) {}

    private final long start = System.nanoTime(); // the start of the run: time 0
    private final List<Logged> trace = new ArrayList<>(); // guarded by itself
    private final AtomicBoolean stop = new AtomicBoolean(false);

    /** The time now, in milliseconds since the start of the run. */
    public long now() {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * Adds an entry to the log, with the time now. The time is read
     * after taking the lock, so the log is always in time order.
     */
    public void log(Entry entry) {
        synchronized (trace) {
            trace.add(new Logged(now(), entry));
        }
    }

    /** Tells every thread to finish. Called once, by Airlock.stop, when the run is over. */
    public void stop() {
        stop.set(true);
    }

    /** Whether the run is over. Each thread checks this at least every POLL. */
    public boolean stopped() {
        return stop.get();
    }

    /** The log, for the tests to check, once every thread has finished. */
    public Run run(long end) {
        synchronized (trace) {
            return new Run(List.copyOf(trace), end);
        }
    }

    /**
     * Sends a message on a queue. If the queue is full, this waits until there
     * is room, or until the run is over (the receiver may have stopped).
     */
    public <T> void send(BlockingQueue<T> to, T message) {
        try {
            while (!stopped()) {
                if (to.offer(message, POLL.toNanos(), TimeUnit.NANOSECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A new bounded queue. */
    public static <T> BlockingQueue<T> queue() {
        return new ArrayBlockingQueue<>(QUEUE);
    }

    /** Waits for threads to finish. */
    public static void join(List<Thread> threads) {
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Waits for a message for at most {@code wait}; null if none came. */
    public static <T> T receive(BlockingQueue<T> from, Duration wait) {
        try {
            return from.poll(Math.max(0, wait.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
