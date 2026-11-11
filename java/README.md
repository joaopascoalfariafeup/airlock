# Two-door airlock in Java

The Java version of the airlock. The specification, the test plan and the
scenarios are one level up and shared with the Rust version: start with
[../README.md](../README.md).

## Run it

Java 21 or later, and Maven. The tests run the provided checker,
`../tracecheck/tracecheck.py`: they need Python 3.8 or later, as `python3`
(`python` on Windows; or set `PYTHON`).

```
mvn test                      # every check (about 30 s: the system tests run on real threads);
                              # each scenario's actual log goes to target/scenarios/
REPEAT=20 mvn test            # runs each scenario file 20 times, to look for flaky behaviour
```

The compiler treats every warning as an error (`-Xlint:all -Werror` in
`pom.xml`): that is this version's linter. SpotBugs or Error Prone can be
added on top. The tests run in parallel, one per processor
(`src/test/resources/junit-platform.properties`), as `cargo test` does.

## Where things are

What each module of the system does and exposes, and what it shares between
threads: [SPEC.md, section 3.4](../SPEC.md); the test architecture:
[TEST.md, section 3.1](../TEST.md).

| Part | File (in `src/main/java/airlock/` or `src/test/java/airlock/`) |
|---|---|
| Messages, log entries | [Messages.java](src/main/java/airlock/Messages.java) |
| Components: decisions (`onInput`) and threads (`run`) | [DoorController.java](src/main/java/airlock/DoorController.java), [Interlocker.java](src/main/java/airlock/Interlocker.java) |
| The system: starts and stops the doors and the interlocker (the communication diagram in code) | [Airlock.java](src/main/java/airlock/Airlock.java) |
| The test driver: scenarios (users, faults), their file format, and running them | [ScenarioRunner.java](src/test/java/airlock/ScenarioRunner.java) |
| Fake hardware of one door, fault injection | [DoorHardware.java](src/main/java/airlock/DoorHardware.java) |
| Run context: clock, event log, stop flag; queue and thread helpers | [RunContext.java](src/main/java/airlock/RunContext.java) |
| System tests: every scenario file against its expected log | [AirlockTest.java](src/test/java/airlock/AirlockTest.java) |
| Agent context (Java part), agent log and code review checklist | [AGENTS.md](AGENTS.md), [AGENT_LOG.md](AGENT_LOG.md) |

Messages are sealed interfaces and records, handled with `switch` pattern
matching, so the compiler checks that every message is handled. Their names
are the names in the specification, and they print as the expected logs write
them. Queues are bounded (`ArrayBlockingQueue`), and the only library is JUnit.
