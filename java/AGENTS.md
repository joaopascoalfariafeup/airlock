# Project rules for AI agents: Java version

Read `../AGENTS.md` first: the rules for any language. This file adds the
Java ones.

## Architecture

Package `airlock`, Java 21, Maven.

- `DoorController.java` and `Interlocker.java`: one component each. `onInput`
  only decides; `run` is the component's thread and does what was decided.
- `Messages.java`: every message and log entry, as enums, sealed interfaces
  and records, named as in `SPEC.md`; their `toString` is the format of the
  expected logs. Handle messages with `switch` pattern matching, with no
  `default`, so that the compiler checks that every case is handled.
- `Airlock.java`: the system. `Airlock.start` creates the queues and starts
  the threads of the two doors' controllers and hardware and of the
  interlocker; `stop` ends them. It knows nothing of the scenario.
- `DoorHardware.java`: fake hardware of one door, one thread per door. Part of
  the system, in place of a real driver.
- `RunContext.java`: clock, event log (`synchronized`) and stop flag (atomic).
  Nothing else is shared.
- `src/test/java/airlock/AirlockTest.java`: the system test, which runs every
  scenario file, checks P1 over the whole log, and has
  `../tracecheck/tracecheck.py` compare the actual log with the expected log.
  Do not reimplement that comparison.
- `src/test/java/airlock/ScenarioRunner.java`: the test driver. A `Scenario`
  record (users, times, fault) and its file format, and `run`, which starts the
  airlock, plays the users on the calling thread, and stops the airlock.
  Environment, not system.

## Conventions

- Run `mvn test` before reporting a task as finished. The build must have no
  compiler warnings (`-Xlint:all -Werror`).
- The test `everyScenarioFile` runs every file in `../scenarios/`.

## Forbidden

- unbounded queues (use `ArrayBlockingQueue`, not `LinkedBlockingQueue` without
  a capacity)
- `synchronized`, locks or `volatile` in the components: they share nothing
- catching an exception and ignoring it, except `InterruptedException` while
  shutting down, which restores the interrupt flag
- `null` as a message; `Thread.sleep` or the clock in `onInput`
- any library other than JUnit without approval
