package airlock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import airlock.Messages.Door;
import airlock.Messages.DoorIn;
import airlock.Messages.HardwareIn;
import airlock.Messages.Motion;
import airlock.Messages.Signal;

/**
 * Fake hardware for one door, on its own thread: the button, the motor, the
 * "fully open" and "closed" sensors, the light, the siren, and the passage
 * sensor. Each door has its own instance. It is part of the system, where a
 * driver would be: it stands for the driver and for the physical door. On a
 * real airlock, drivers for the real hardware would replace this class, behind
 * the same queues.
 */
public final class DoorHardware {
    private DoorHardware() {}

    /** How long a door usually takes to open or to close. A scenario may make it slower, not slower than MOVE_TIMEOUT. */
    public static final long TRAVEL = 1_000;

    /** The one kind of fault: a door whose sensor never confirms a movement. */
    public sealed interface Fault {
        record None() implements Fault {}
        record Stuck(Door door, int nth) implements Fault {} // this door's nth movement is never confirmed
    }

    /**
     * The fake hardware's settings, the same for both doors.
     *
     * @param travel how long a door takes to move
     * @param fault  which movement, if any, is never confirmed
     */
    public record Config(long travel, Fault fault) {}

    /** A user at a door: which door, and what they see or hear. */
    public record UserSignal(Door door, Signal signal) {}

    private record Due(long time, DoorIn event) {}

    /**
     * The thread of one door's hardware. It receives commands from its
     * controller, and the users' presses and attempts to go through; it sends
     * the button and the sensor events to its controller, and shows the light
     * and sounds the siren to the users.
     */
    public static void run(Door door, Config config, BlockingQueue<HardwareIn> inbox,
                           BlockingQueue<DoorIn> controller, BlockingQueue<UserSignal> users, RunContext context) {
        List<Due> due = new ArrayList<>(); // sensor events still to send, and when
        boolean fullyOpen = false; // only then can a user go through
        int moves = 0;
        long travel = config.travel();
        Fault fault = config.fault();
        while (!context.stopped()) {
            // Send what is due.
            long now = context.now();
            for (Iterator<Due> it = due.iterator(); it.hasNext(); ) {
                Due d = it.next();
                if (d.time() <= now) {
                    fullyOpen = d.event() == DoorIn.FullyOpen;
                    context.send(controller, d.event());
                    it.remove();
                }
            }
            HardwareIn input = RunContext.receive(inbox, RunContext.POLL);
            if (input == null) {
                continue;
            }
            switch (input) {
                // The sensor answers after `travel`, unless this movement is the stuck one.
                case HardwareIn.Motor m -> {
                    fullyOpen = false;
                    moves += 1;
                    if (!fault.equals(new Fault.Stuck(door, moves))) {
                        DoorIn event = m.motion() == Motion.Open ? DoorIn.FullyOpen : DoorIn.Closed;
                        due.add(new Due(context.now() + travel, event));
                    }
                }
                case HardwareIn.Press p -> context.send(controller, DoorIn.Button);
                case HardwareIn.Light l -> context.send(users, new UserSignal(door, new Signal.Light(l.light())));
                case HardwareIn.Alarm a -> context.send(users, new UserSignal(door, new Signal.Alarm()));
                // A red light does not stop a user: only the door's position does.
                case HardwareIn.Pass p -> {
                    if (fullyOpen) {
                        context.send(controller, DoorIn.Passed);
                    }
                }
            }
        }
    }
}
