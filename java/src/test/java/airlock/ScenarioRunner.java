package airlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import airlock.DoorController.Timing;
import airlock.DoorHardware.Config;
import airlock.DoorHardware.Fault;
import airlock.DoorHardware.UserSignal;
import airlock.Messages.Door;
import airlock.Messages.DoorOut;
import airlock.Messages.Entry;
import airlock.Messages.HardwareIn;
import airlock.Messages.Light;
import airlock.Messages.Signal;

/**
 * The scenario runner, the test driver of TEST.md, section 3.2: a
 * {@link Scenario}, which says what the users do and what goes wrong, as read
 * from a scenario file, and {@link #run}, which starts the airlock, plays the
 * users and stops the airlock. The users are the environment, not part of the
 * system under test: the system never identifies them, and the log holds only
 * its own events.
 */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    /** Extra time at the end of a run, so that a late event can still be seen. */
    private static final long END_MARGIN = 1_000;

    /**
     * What the users do, and what goes wrong, in one run.
     *
     * @param users     the users
     * @param walkMs    from a green light to the users there going through
     * @param crossMs   from going through a door to pressing the next door's button
     * @param travelMs  how long a door takes to move (usually TRAVEL)
     * @param fault     what goes wrong
     */
    public record Scenario(List<User> users, long walkMs, long crossMs, long travelMs, Fault fault) {

        /**
         * Reads a scenario from text, as in the files in scenarios/ (format:
         * TEST.md, section 3.3).
         */
        public static Scenario parse(String text) {
            List<User> users = new ArrayList<>();
            long walk = 1_000, cross = 500, travel = DoorHardware.TRAVEL;
            Fault fault = new Fault.None();
            String[] lines = text.split("\n", -1);
            for (int n = 0; n < lines.length; n++) {
                String line = lines[n].split("#", -1)[0].trim(); // drop the comment
                if (line.isEmpty()) {
                    continue;
                }
                String[] w = line.split("\\s+");
                try {
                    if (w.length >= 6 && w[0].equals("user") && w[2].equals("at") && w[4].equals("through")) {
                        List<Door> route = new ArrayList<>();
                        for (int i = 5; i < w.length; i++) {
                            route.add(door(w[i]));
                        }
                        users.add(new User(Long.parseLong(w[3]), List.copyOf(route)));
                    } else if (w.length == 3 && w[1].equals("=") && w[0].equals("walk")) {
                        walk = Long.parseLong(w[2]);
                    } else if (w.length == 3 && w[1].equals("=") && w[0].equals("cross")) {
                        cross = Long.parseLong(w[2]);
                    } else if (w.length == 3 && w[1].equals("=") && w[0].equals("travel")) {
                        travel = Long.parseLong(w[2]);
                    } else if (w.length == 4 && w[1].equals("=") && w[0].equals("stuck")) {
                        fault = new Fault.Stuck(door(w[2]), Integer.parseInt(w[3]));
                    } else {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("line " + (n + 1) + ": cannot read \"" + line + "\"", e);
                }
            }
            return new Scenario(List.copyOf(users), walk, cross, travel, fault);
        }

        }

        /** One user: when they arrive, and the doors they want to go through, in order. */
        public record User(long start, List<Door> route) {}

        private static Door door(String word) {
            return switch (word) {
                case "a" -> Door.A;
                case "b" -> Door.B;
                default -> throw new IllegalArgumentException(word);
            };
        }

    /**
     * Plays a scenario on real threads, {@code speed} times faster than real
     * time, and returns the log. It starts the airlock, plays the users on the
     * calling thread, and stops the airlock. The users talk only to the doors'
     * hardware: they press and go through, and see the lights. The run ends
     * when nothing more is planned, and the last press has been served or its
     * deadline has passed.
     *
     * <p>The airlock knows nothing of the speed. This run divides every time by
     * {@code speed}: the airlock's Timing, the doors' travel and the users'
     * times. Then it multiplies the times in the log by {@code speed}, so the
     * log is in the scenario's time, as if it had run in real time.
     * {@code speed} must divide 1000, so that the required times divide exactly.
     */
    public static RunContext.Run run(Scenario scenario, long speed) {
        if (speed <= 0 || 1_000 % speed != 0) {
            throw new IllegalArgumentException("speed must divide 1000, not " + speed);
        }
        Timing timing = new Timing(DoorController.MAX_OPEN / speed, DoorController.MOVE_TIMEOUT / speed);
        Scenario fast = new Scenario(scenario.users().stream().map(u -> new User(u.start() / speed, u.route())).toList(),
                scenario.walkMs() / speed, scenario.crossMs() / speed, scenario.travelMs() / speed, scenario.fault());

        RunContext context = new RunContext();
        Airlock airlock = Airlock.start(timing, new Config(fast.travelMs(), fast.fault()), context);
        long afterPress = (DoorController.RESPONSE_LIMIT + 2 * END_MARGIN) / speed;
        long end = playUsers(fast, afterPress, airlock.hardware(), airlock.signals(), context);
        airlock.stop();
        return slow(context.run(end), speed);
    }

    /** The log of a fast run, in the scenario's time: its times, and the doors' timer durations, multiplied by speed. */
    private static RunContext.Run slow(RunContext.Run run, long speed) {
        List<RunContext.Logged> trace = run.trace().stream().map(l -> {
            Entry e = l.entry();
            if (e instanceof Entry.Out(Door d, DoorOut.StartTimer(long ms))) {
                e = new Entry.Out(d, new DoorOut.StartTimer(ms * speed));
            }
            return new RunContext.Logged(l.time() * speed, e);
        }).toList();
        return new RunContext.Run(trace, run.end() * speed);
    }

    /** What the users do at a door: press its button, or go through it (everyone waiting there). */
    private record Action(long time, boolean press, Door door) {}

    /**
     * The users, on the calling thread of run. Each arrives at its
     * start time and presses its first door's button. When a door's light turns
     * green, everyone waiting at it, on either side, goes through together
     * walkMs later, if the light is still green; if not, they stay where they
     * are. A user who has gone through a door presses the next door of their
     * route crossMs later. Returns when the run ends: nothing more is planned,
     * and afterPress has passed since the last press, enough for it to be
     * served or its deadline to pass.
     */
    private static long playUsers(Scenario scenario, long afterPress, List<BlockingQueue<HardwareIn>> hardware,
                                  BlockingQueue<UserSignal> signals, RunContext context) {
        List<User> users = scenario.users();
        int[] through = new int[users.size()]; // how many doors of its route each user has gone through
        boolean[] green = new boolean[2];
        long end = 0;
        List<Action> todo = new ArrayList<>(); // what the users will do, and when
        for (User u : users) {
            todo.add(new Action(u.start(), true, u.route().get(0)));
        }
        while (context.now() < end || !todo.isEmpty()) {
            // Do what is due.
            long now = context.now();
            List<Action> due = todo.stream().filter(a -> a.time() <= now).toList();
            todo.removeAll(due);
            for (Action a : due) {
                int d = a.door().ordinal();
                if (a.press()) {
                    context.send(hardware.get(d), new HardwareIn.Press());
                    end = Math.max(end, now + afterPress);
                } else if (green[d]) {
                    context.send(hardware.get(d), new HardwareIn.Pass());
                    for (int u : waitingAt(users, a.door(), through, now)) {
                        through[u] += 1;
                        if (through[u] < users.get(u).route().size()) {
                            todo.add(new Action(now + scenario.crossMs(), true, users.get(u).route().get(through[u])));
                        }
                    }
                } // else: too late, the door is closing, and the users stay
            }
            // Watch the lights, and listen for the sirens.
            UserSignal s = RunContext.receive(signals, RunContext.POLL);
            if (s != null && s.signal() instanceof Signal.Light l) {
                long t = context.now();
                green[s.door().ordinal()] = l.light() == Light.Green;
                if (l.light() == Light.Green && !waitingAt(users, s.door(), through, t).isEmpty()) {
                    todo.add(new Action(t + scenario.walkMs(), false, s.door()));
                }
            }
        }
        return end;
    }

    /** Who is waiting at a door: arrived, and that door is next on their route. */
    private static List<Integer> waitingAt(List<User> users, Door door, int[] through, long now) {
        List<Integer> waiting = new ArrayList<>();
        for (int u = 0; u < users.size(); u++) {
            List<Door> route = users.get(u).route();
            if (users.get(u).start() <= now && through[u] < route.size() && route.get(through[u]) == door) {
                waiting.add(u);
            }
        }
        return waiting;
    }
}
