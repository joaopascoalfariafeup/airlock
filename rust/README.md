# Two-door airlock in Rust

The Rust version of the airlock. The specification, the test plan and the
scenarios are one level up and shared with the Java version: start with
[../README.md](../README.md).

## Run it

Rust stable; on Windows, WSL is recommended. The tests run the provided
checker, `../tracecheck/tracecheck.py`: they need Python 3.8 or later, as
`python3` (or set `PYTHON`).

```
cargo test                   # every check (about 30 s: the system tests run on real threads);
                             # each scenario's actual log goes to target/scenarios/
REPEAT=20 cargo test         # runs each scenario file 20 times, to look for flaky behaviour
cargo clippy --all-targets   # must report no warnings
```

## Where things are

What each module of the system does and exposes, and what it shares between
threads: [SPEC.md, section 3.4](../SPEC.md); the test architecture:
[TEST.md, section 3.1](../TEST.md).

| Part | File |
|---|---|
| Components: decisions (`on_input`) and threads (`run`) | [src/door_controller.rs](src/door_controller.rs), [src/interlocker.rs](src/interlocker.rs) |
| Messages, log entries | [src/messages.rs](src/messages.rs) |
| The system: starts and stops the doors and the interlocker (the communication diagram in code) | [src/airlock.rs](src/airlock.rs) |
| Fake hardware of one door, fault injection | [src/door_hardware.rs](src/door_hardware.rs) |
| Run context: clock, event log, stop flag; queue and thread helpers | [src/run_context.rs](src/run_context.rs) |
| System tests: every scenario file against its expected log | [tests/airlock_test.rs](tests/airlock_test.rs) |
| The test driver: scenarios (users, faults), their file format, and running them | [tests/scenario_runner/mod.rs](tests/scenario_runner/mod.rs) |
| Agent context (Rust part), agent log and code review checklist | [AGENTS.md](AGENTS.md), [AGENT_LOG.md](AGENT_LOG.md) |

`src/` is the system (with fake hardware), and `tests/` is everything that
plays and judges it: the test driver, with the users, and the test. Channels are bounded (`sync_channel`), and the code uses only the
standard library.
