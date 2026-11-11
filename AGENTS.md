# Project rules for AI agents

These rules hold for any language. `rust/AGENTS.md` and `java/AGENTS.md` add
the architecture and the forbidden constructs of each version.

Read `SPEC.md` first, then `TEST.md`. They are the reference: if the code and
the specification disagree, stop and say so, and do not change a property to
make a test pass.

## Architecture, in any language

- One component per active object (door, interlocker): a decision function
  that only decides (no I/O, threads or clock), and a thread that does what
  was decided.
- One function starts the system, the hardware included: it creates the
  queues and starts the threads, and is given the doors' times (D9) and the
  fake hardware's settings. Components get their queues there, never from each
  other. It knows nothing of the scenario.
- The controller uses only the real clock. Running faster than real time is
  the test driver's job: it gives shorter times, speeds up the environment the
  same way, and converts the log back.
- The environment is the users, who play a scenario and talk only to the
  doors' hardware (D3). The hardware is a fake driver, one thread per door,
  inside the system. Neither decides for the controller.
- Only the queues and the run context (the clock, the event log and the stop
  flag) are shared; SPEC.md, section 3.4, says how each is protected. Every message is
  logged, in the format of the expected logs.
- `scenarios/`: scenario files and expected logs (`*.expected.txt`), read by
  both versions. One test runs every file there: to add a scenario, add its
  file, and its expected log if its outcome is known.
- The expected logs are written by the students. Propose changes, but do not
  edit them unless asked.

## Conventions

- One small task at a time. Run every test and the linter before reporting a
  task as finished, and report any failure as it is.
- Every timing value is a named constant that matches `SPEC.md` (required
  times) or `TEST.md` (test and implementation values).
- Test first: each task starts from a scenario that fails (its file and
  expected log, from the specification). Implement just enough for it to
  pass, then refactor with every scenario passing. Never edit an expected log
  to make a test pass.
- Every failure found by a test becomes a regression test.
- A time bound in an expected log (`after 2000 of t1`, `within 2000 of t1`)
  is a required time of `SPEC.md`, named in a comment (`# P3: MOVE_TIMEOUT`).
- An expected log is written from the specification: the main scenario's
  sequence diagram in `SPEC.md`, or the scenario's description in `TEST.md`;
  never from the program's output. If
  a test shows they differ, stop and say so: either the code or the
  specification is wrong.
- Tests on threads keep wide margins: no scenario may depend on the order of
  two events less than half a second apart (in the airlock's time); where
  events happen together, the expected log lists every order allowed.
- Traceability: every item in `SPEC.md` and `TEST.md` has an id (J1 journeys,
  A1 and E1 alternative and exception flows, P1 properties, T1 transitions
  of the state machines, D1 design decisions, S1 scenarios). Code that provides one
  carries `// Implements: P1`, and a test that checks one carries
  `// Verifies: P1`. Say which ids each change serves.

## Forbidden, in any language

- shared state between components other than the clock, the log and the stop
  flag (send a message instead)
- unbounded queues
- sleeping or reading the clock in a decision function
- any library beyond the standard one and the test framework, without approval
