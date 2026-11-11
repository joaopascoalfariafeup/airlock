package airlock;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import airlock.Messages.Door;
import airlock.Messages.DoorIn;
import airlock.Messages.DoorOut;
import airlock.Messages.Entry;
import airlock.Messages.Motion;
import airlock.RunContext.Run;
import airlock.ScenarioRunner.Scenario;

/**
 * System tests: the whole airlock on real threads, 10 times faster than real
 * time. Each scenario file in ../scenarios/ is run; P1 is checked over the
 * whole log; and the log, written to target/scenarios/name.actual.txt, is
 * compared with the expected log by the provided tool, tracecheck (in order
 * and within the time bounds it gives). Each run takes a second or two. The
 * tool needs Python 3.8 or later: python3 (python on Windows), or the program
 * named by the environment variable PYTHON.
 */
class AirlockTest {
    /** Simulated milliseconds per real millisecond. */
    private static final long SPEED = 10;

    /**
     * Where the actual logs go: in the build folder, so that they are not
     * committed, and the Rust and Java versions do not overwrite each other's.
     */
    private static final Path ACTUAL_DIR = Path.of("target/scenarios");

    /** The scenarios, and the tool, shared with the Rust version, one level up. */
    private static final Path SCENARIOS = Path.of("../scenarios");
    private static final Path TRACECHECK = Path.of("../tracecheck/tracecheck.py");

    /** What tracecheck said: the alternative matched (1 for the first), or its report. */
    private record Verdict(int matched, String why) {}

    /**
     * P1 over the whole log, whatever the scenario: a door never starts opening
     * while the other is not closed. A door is not closed from its Motor(Open)
     * until its sensor reports Closed. An expected log cannot check this where
     * it has a gap ("..."), which accepts any message. Returns why it is
     * broken, or null.
     */
    // Verifies: P1
    private static String bothDoorsOpen(Run run) {
        boolean[] notClosed = new boolean[2];
        for (RunContext.Logged l : run.trace()) {
            if (l.entry() instanceof Entry.Out(Door door, DoorOut.Motor(Motion motion)) && motion == Motion.Open) {
                Door other = door == Door.A ? Door.B : Door.A;
                if (notClosed[other.ordinal()]) {
                    return "P1 broken at " + l.time() + " ms: door " + door + " starts opening while door " + other
                            + " is not closed";
                }
                notClosed[door.ordinal()] = true;
            } else if (l.entry() instanceof Entry.In(Door door, DoorIn input) && input == DoorIn.Closed) {
                notClosed[door.ordinal()] = false;
            }
        }
        return null;
    }

    /**
     * Writes target/scenarios/name.actual.txt, the format tracecheck reads: one
     * arrow per line, with its time as a comment. The log records a message
     * between components twice, when sent and when received; a diagram shows it
     * once, so the received copy is left out.
     */
    private static Path writeActual(String name, Run run) throws IOException {
        Path path = ACTUAL_DIR.resolve(name + ".actual.txt");
        StringBuilder text = new StringBuilder("# Actual log of " + name
                + ": one line per arrow, with its time (in the airlock's time).\n# Never copy it into an expected log.\n");
        for (RunContext.Logged l : run.trace()) {
            Entry e = l.entry();
            if (!(e instanceof Entry.LockInput) && !(e instanceof Entry.In i && i.input() == DoorIn.Granted)) {
                text.append(String.format("%-28s# %d ms%n", e, l.time()));
            }
        }
        Files.createDirectories(ACTUAL_DIR);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    /** Runs tracecheck on one expected log and one actual log. */
    private static Verdict tracecheck(Path expected, Path actual) throws IOException, InterruptedException {
        String python = System.getenv().getOrDefault("PYTHON",
                System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Process p;
        try {
            p = new ProcessBuilder(python, "-I", TRACECHECK.toString(), expected.toString(), actual.toString())
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("cannot run " + python + " " + TRACECHECK
                    + ": install Python 3.8 or later, or set PYTHON", e);
        }
        String report = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code == 0) {
            int at = report.indexOf("(alternative ");
            return new Verdict(at < 0 ? 1 : Integer.parseInt(report.substring(at + 13).split(" ")[0]), null);
        }
        if (code == 1) {
            return new Verdict(0, report);
        }
        throw new IllegalStateException("tracecheck could not read " + expected + ":\n" + report);
    }

    /**
     * Every scenario in scenarios/ (S1 to S6, and any file added there). A
     * scenario whose expected log has alternatives runs 10 times, the others
     * once; REPEAT=n runs each n times, to look for flaky behaviour. A scenario
     * stops at its first failing run, whose actual log is kept. The scenarios
     * run one after the other: many runs at once would delay each other's
     * threads, and a timer could then fire before a sensor's message.
     */
    // Scenario: S1, S2, S3, S4, S5, S6
    // Verifies: P1, P2, P3, P4
    @Test
    void everyScenarioFile() throws IOException, InterruptedException {
        String repeatVar = System.getenv("REPEAT");
        Integer repeat = repeatVar == null ? null : Integer.valueOf(repeatVar);
        List<String> names;
        try (Stream<Path> files = Files.list(SCENARIOS)) {
            names = files.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".txt") && !f.endsWith(".expected.txt"))
                    .map(f -> f.substring(0, f.length() - ".txt".length()))
                    .sorted()
                    .toList();
        }
        List<String> failures = new ArrayList<>();
        for (String name : names) {
            Scenario scenario = Scenario.parse(Files.readString(SCENARIOS.resolve(name + ".txt"), StandardCharsets.UTF_8));
            Path expected = SCENARIOS.resolve(name + ".expected.txt");
            boolean hasExpected = Files.exists(expected);
            int alternatives = !hasExpected ? 1 : 1 + (int) Files.readAllLines(expected, StandardCharsets.UTF_8).stream()
                    .filter(l -> l.split("#", -1)[0].trim().equals("or")).count();
            int runs = repeat != null ? repeat : alternatives > 1 ? 10 : 1;
            int[] matched = new int[alternatives]; // runs that matched each alternative
            for (int run = 1; run <= runs; run++) {
                Run log = ScenarioRunner.run(scenario, SPEED);
                Path actual = writeActual(name, log);
                String unsafe = bothDoorsOpen(log);
                Verdict v = unsafe != null ? new Verdict(0, unsafe)
                        : hasExpected ? tracecheck(expected, actual)
                        : new Verdict(1, null); // no expected log: any log will do
                if (v.why() != null) {
                    failures.add(name + ", run " + run + " of " + runs + " (log in java/"
                            + actual.toString().replace('\\', '/') + "):\n" + v.why());
                    break;
                }
                matched[v.matched() - 1] += 1;
            }
            System.out.println(name + ": " + runs + " runs, matched by alternative: " + Arrays.toString(matched));
        }
        assertTrue(failures.isEmpty(), String.join("\n\n", failures));
    }
}
