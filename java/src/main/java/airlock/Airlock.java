package airlock;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import airlock.DoorController.Timing;
import airlock.DoorHardware.Config;
import airlock.DoorHardware.UserSignal;
import airlock.Messages.Door;
import airlock.Messages.DoorIn;
import airlock.Messages.HardwareIn;
import airlock.Messages.LockIn;

/**
 * The system: the two door controllers, the interlocker and each door's
 * hardware, the five active objects of SPEC.md, section 3.1. {@link #start}
 * creates their queues, gives each the ones it needs, and starts their
 * threads: it is the communication diagram in code.
 *
 * <p>The airlock's boundary is what a user does and sees (SPEC.md, section
 * 2.3): users press a door's button and go through it, at that door's
 * hardware, and see its light and hear its siren. The hardware here is a fake
 * (DoorHardware); on a real airlock, drivers for the real hardware would
 * replace that class, behind the same queues, and the rest of this class
 * would not change.
 */
public final class Airlock {
    private final List<BlockingQueue<HardwareIn>> hardware;
    private final BlockingQueue<UserSignal> signals;
    private final List<Thread> threads;
    private final RunContext context;

    private Airlock(List<BlockingQueue<HardwareIn>> hardware, BlockingQueue<UserSignal> signals, List<Thread> threads,
                    RunContext context) {
        this.hardware = hardware;
        this.signals = signals;
        this.threads = threads;
        this.context = context;
    }

    /**
     * Starts the two doors' controllers and hardware, and the interlocker, each
     * on its own thread. {@code timing} is the doors' times, usually
     * Timing.SPEC; {@code config} is the fake hardware's (how long a door takes
     * to move, and which movement gets stuck). The threads log every message to
     * {@code context}.
     */
    public static Airlock start(Timing timing, Config config, RunContext context) {
        List<BlockingQueue<DoorIn>> doors = List.of(RunContext.queue(), RunContext.queue()); // to door A's and door B's controller
        BlockingQueue<LockIn> lock = RunContext.queue(); // to the interlocker
        List<BlockingQueue<HardwareIn>> hardware = List.of(RunContext.queue(), RunContext.queue()); // to door A's and door B's hardware
        BlockingQueue<UserSignal> signals = RunContext.queue(); // to the users: lights and sirens
        List<Thread> threads = List.of(
                new Thread(() -> DoorController.run(Door.A, timing, doors.get(0), lock, hardware.get(0), context)),
                new Thread(() -> DoorController.run(Door.B, timing, doors.get(1), lock, hardware.get(1), context)),
                new Thread(() -> Interlocker.run(lock, doors, context)),
                new Thread(() -> DoorHardware.run(Door.A, config, hardware.get(0), doors.get(0), signals, context)),
                new Thread(() -> DoorHardware.run(Door.B, config, hardware.get(1), doors.get(1), signals, context)));
        threads.forEach(Thread::start);
        return new Airlock(hardware, signals, threads, context);
    }

    /** Its inputs from the users, at door A's and door B's hardware: Press and Pass. */
    public List<BlockingQueue<HardwareIn>> hardware() {
        return hardware;
    }

    /** Its outputs to the users: each door's light and siren. */
    public BlockingQueue<UserSignal> signals() {
        return signals;
    }

    /** Ends the run, and waits for the threads to finish. */
    public void stop() {
        context.stop();
        RunContext.join(threads);
    }
}
