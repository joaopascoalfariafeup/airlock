package airlock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import airlock.Messages.Door;
import airlock.Messages.DoorIn;
import airlock.Messages.DoorOut;
import airlock.Messages.Entry;
import airlock.Messages.HardwareIn;
import airlock.Messages.Light;
import airlock.Messages.LockIn;
import airlock.Messages.Motion;

/**
 * A door, as an active object: its own thread, its own inbox and its own
 * timer. The decisions are in {@link #onInput}, which does no I/O, so it can
 * be tested on its own; {@link #run} receives messages, asks it what to do,
 * and does it.
 */
public final class DoorController {
    // Required times (SPEC.md, section 2.2): the values of Timing.SPEC.
    public static final long MAX_OPEN = 5_000;
    public static final long MOVE_TIMEOUT = 2_000;
    public static final long RESPONSE_LIMIT = MAX_OPEN + 3 * MOVE_TIMEOUT;

    /**
     * A door's times, given when the airlock starts. A real airlock uses
     * {@link #SPEC}; a test driver may give shorter times, to run faster than
     * real time.
     */
    public record Timing(long maxOpen, long moveTimeout) {
        public static final Timing SPEC = new Timing(MAX_OPEN, MOVE_TIMEOUT);
    }

    public enum State {
        Closed,
        Waiting, // token requested, not yet granted
        Opening,
        Open,
        Closing,
        Fault // latched: ignores everything, and keeps the token
    }

    private final Timing timing;
    private State state = State.Closed;

    public DoorController(Timing timing) {
        this.timing = timing;
    }

    public State state() {
        return state;
    }

    // A door opens only once granted, and gives the token back only once closed.
    // The light is green only while the door is fully open.
    // Implements: P1, P2, P3, P4
    public List<DoorOut> onInput(DoorIn input) {
        switch (state) {
            case Fault:
                return List.of();
            case Closed:
                if (input == DoorIn.Button) {
                    state = State.Waiting;
                    return List.of(new DoorOut.Request());
                }
                break;
            case Waiting:
                if (input == DoorIn.Granted) {
                    state = State.Opening;
                    return List.of(new DoorOut.Motor(Motion.Open), new DoorOut.StartTimer(timing.moveTimeout()));
                }
                break;
            case Opening:
                if (input == DoorIn.FullyOpen) {
                    state = State.Open;
                    return List.of(new DoorOut.Light(Light.Green), new DoorOut.StartTimer(timing.maxOpen()));
                }
                if (input == DoorIn.Timeout) {
                    state = State.Fault;
                    return List.of(new DoorOut.Alarm());
                }
                break;
            case Open:
                if (input == DoorIn.Passed || input == DoorIn.Timeout) {
                    state = State.Closing;
                    return List.of(new DoorOut.Light(Light.Red), new DoorOut.Motor(Motion.Close),
                            new DoorOut.StartTimer(timing.moveTimeout()));
                }
                break;
            case Closing:
                if (input == DoorIn.Closed) {
                    state = State.Closed;
                    return List.of(new DoorOut.StopTimer(), new DoorOut.Release());
                }
                if (input == DoorIn.Timeout) {
                    state = State.Fault;
                    return List.of(new DoorOut.Alarm());
                }
                break;
        }
        return List.of(); // ignored: see SPEC.md, section 3.3 and D6
    }

    /**
     * The door's thread. It is given its inbox, and the queues to the
     * interlocker and to its hardware, when it starts.
     */
    public static void run(Door door, Timing timing, BlockingQueue<DoorIn> inbox, BlockingQueue<LockIn> lock,
                           BlockingQueue<HardwareIn> hardware, RunContext context) {
        DoorController machine = new DoorController(timing);
        Long deadline = null; // this door's timer, in System.nanoTime(); null when not running
        while (!context.stopped()) {
            Duration wait = RunContext.POLL;
            if (deadline != null) {
                Duration left = Duration.ofNanos(deadline - System.nanoTime());
                wait = left.compareTo(wait) < 0 ? left : wait;
            }
            DoorIn input = RunContext.receive(inbox, wait);
            if (input == null) {
                if (deadline == null || System.nanoTime() < deadline) {
                    continue;
                }
                deadline = null;
                input = DoorIn.Timeout;
            }
            context.log(new Entry.In(door, input));
            for (DoorOut out : machine.onInput(input)) {
                context.log(new Entry.Out(door, out));
                switch (out) {
                    case DoorOut.Motor m -> context.send(hardware, new HardwareIn.Motor(m.motion()));
                    case DoorOut.Light l -> context.send(hardware, new HardwareIn.Light(l.light()));
                    case DoorOut.Alarm a -> context.send(hardware, new HardwareIn.Alarm());
                    case DoorOut.Request r -> context.send(lock, new LockIn.Request(door));
                    case DoorOut.Release r -> context.send(lock, new LockIn.Release(door));
                    case DoorOut.StartTimer t -> deadline = System.nanoTime() + Duration.ofMillis(t.ms()).toNanos();
                    case DoorOut.StopTimer t -> deadline = null;
                }
            }
        }
    }
}
