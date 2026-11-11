package airlock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import airlock.Messages.Door;
import airlock.Messages.DoorIn;
import airlock.Messages.Entry;
import airlock.Messages.Granted;
import airlock.Messages.LockIn;

/**
 * The interlocker, as an active object. It holds the token that gives a door
 * the right to open. Requests that cannot be granted wait in a FIFO queue, so
 * neither door can starve.
 */
public final class Interlocker {
    public sealed interface State {
        record Free() implements State {
            @Override public String toString() { return "Free"; }
        }
        record Held(Door holder) implements State {
            @Override public String toString() { return "Held(" + holder + ")"; }
        }
    }

    private State state = new State.Free();
    private final Deque<Door> queue = new ArrayDeque<>(); // waiting doors, first come first served; empty when Free

    public State state() {
        return state;
    }

    // Implements: P1, P2
    public Optional<Granted> onInput(LockIn input) {
        if (state instanceof State.Free && input instanceof LockIn.Request r) {
            state = new State.Held(r.door());
            return Optional.of(new Granted(r.door()));
        }
        if (state instanceof State.Held h && input instanceof LockIn.Request r
                && r.door() != h.holder() && !queue.contains(r.door())) {
            queue.addLast(r.door());
            return Optional.empty();
        }
        if (state instanceof State.Held h && input instanceof LockIn.Release r && r.door() == h.holder()) {
            // The token goes to the first door waiting, if any.
            Door next = queue.pollFirst();
            if (next != null) {
                state = new State.Held(next);
                return Optional.of(new Granted(next));
            }
            state = new State.Free();
            return Optional.empty();
        }
        // Ignored: a request from the holder or from a door already queued, and a
        // release by a door that is not the holder. Correct doors never send
        // these, but the interlocker does not rely on it.
        return Optional.empty();
    }

    /** The interlocker's thread. It is given its inbox and the queues to both doors when it starts. */
    public static void run(BlockingQueue<LockIn> inbox, List<BlockingQueue<DoorIn>> doors, RunContext context) {
        Interlocker lock = new Interlocker();
        while (!context.stopped()) {
            LockIn input = RunContext.receive(inbox, RunContext.POLL);
            if (input == null) {
                continue;
            }
            context.log(new Entry.LockInput(input));
            lock.onInput(input).ifPresent(granted -> {
                context.log(new Entry.LockOutput(granted));
                context.send(doors.get(granted.door().ordinal()), DoorIn.Granted);
            });
        }
    }
}
