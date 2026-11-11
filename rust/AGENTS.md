# Project rules for AI agents: Rust version

Read `../AGENTS.md` first: the rules for any language. This file adds the
Rust ones.

## Architecture

- `src/door_controller.rs` and `src/interlocker.rs`: one component each. `on_input` only
  decides; `run` is the component's thread and does what was decided.
- `src/messages.rs`: every message and log entry, named as in
  `SPEC.md`; their `Debug` format is the format of the expected logs. It uses
  no other module, and the components depend on each other only through it.
- `src/airlock.rs`: the system. `Airlock::start` creates the channels and
  starts the threads of the two doors' controllers and hardware and of the
  interlocker; `stop` ends them. It knows nothing of the scenario.
- `src/door_hardware.rs`: fake hardware of one door, one thread per door.
  Part of the system, in place of a real driver.
- `src/run_context.rs`: clock, event log (in a `Mutex`) and stop flag (atomic).
  Nothing else is shared. Module names are those of the Java classes, in
  snake_case (SPEC.md, section 3.4).
- `tests/airlock_test.rs`: the system test, which runs every scenario file,
  checks P1 over the whole log, and has `../tracecheck/tracecheck.py` compare
  the actual log with the expected log. Do not reimplement that comparison.
- `tests/scenario_runner/mod.rs`: the test driver. A `Scenario` (users, times,
  fault) and its file format, and `run`, which starts the airlock, plays the
  users on the calling thread, and stops the airlock. Environment, not system.

## Conventions

- Run `cargo test` and `cargo clippy --all-targets` before reporting a task as
  finished.
- The test `every_scenario_file` runs every file in `../scenarios/`.

## Forbidden

- `unsafe`
- `unwrap()` outside tests
- shared state between components other than `RunContext` (send a message instead)
- unbounded queues (use `sync_channel`, not `channel`)
- any crate: this project uses the standard library only
- `thread::sleep` or the clock in `on_input`
