# Two-door airlock: a worked example

A worked example of Assignment II: a two-door airlock built as three active
objects, each on its own thread. Two doors and an interlocker, which holds the
token that gives a door the right to open, exchange messages over bounded
queues. Each door's hardware is faked by a thread of its own, and the tests
play the users. It is not in the project pool. Use it to see what each
deliverable looks like, then build your own.

There is one specification and two implementations of it, in Rust and in
Java, to show that the specification and the test plan do not depend on the
language. Your project needs only one language: yours will look like this
folder with one of `rust/` or `java/`, or with everything at the top.

| | |
|---|---|
| Specification: context, requirements (journeys, properties with their hazards, times), design (UML views, design decisions; modules, their dependencies and shared data, section 3.4) | [SPEC.md](SPEC.md) |
| Test plan: criteria, test scenarios; how the tests are built, in both languages | [TEST.md](TEST.md) |
| Scenario files and expected logs (S1 to S6), read by both versions | [scenarios/](scenarios/) |
| tracecheck: compares actual logs with expected logs, for any language (Python, standard library only) | [tracecheck/](tracecheck/README.md) |
| Agent context: rules for any language | [AGENTS.md](AGENTS.md) |
| Rust version: code, tests, agent context, agent log | [rust/](rust/README.md) |
| Java version: code, tests, agent context, agent log | [java/](java/README.md) |

## Run it

The tests compare each run's log with its expected log using the provided
checker, [tracecheck/](tracecheck/README.md), so they need Python 3.8 or later.

```
cd rust
cargo test        # every check, about 30 s; each scenario's log goes to target/scenarios/

cd java
mvn test          # the same
```

## How it works

- **Each component is an active object.** Its thread gets its queues when it
  starts, receives messages, asks its decision function what to do, and does
  it. The decision function only decides, so it can be reviewed against its
  state machine, line by line.
- **Every message is logged** with its time, including those between the doors
  and the interlocker. Each test runs a scenario and compares its log with
  an expected log, written from the specification. Both versions print the
  log in the same format, so the same expected logs check both.
- **The tests run the real threads,** 10 times faster than real time. Threads
  are never exactly on time, so every scenario keeps its events well apart,
  and where they do happen together, the expected log lists every order
  allowed.

## Using this example

You may copy and adapt any of this with your agent, into any of the languages
of the assignment. Record that in your agent log, and treat what you copy like
any other agent output: check it, and be able to explain it. Watch for airlock
assumptions that survive the adaptation, such as two doors, or a timing limit
that does not apply to your controller.

Prove early that your adapted setup really tests something: break the code on
purpose, for example release the token too early, and check that a scenario
fails.

## What is required, and what goes beyond

This example does more than the assignment asks. Your project needs:

- **Modelling:** a structural diagram of any kind (here, a communication
  diagram), a state machine for each concurrent component, and a sequence
  diagram of the main scenario.
- **Testing:** scenarios with expected logs, described in your TEST.md,
  that together take every state and transition of every state machine and
  stress every property, all run by one command; and a review of the code of
  each state machine against its diagram.

These parts are optional: copy them if they help, and leave them out if not.

- Expected logs with alternatives (`or`) or gaps (`...`).
- Users with routes: simpler fake users that just press buttons at given
  times are enough.
- `REPEAT`, to run every scenario many times.

## What this example leaves out

- It covers one kind of fault, a stuck door.
- It ignores a press while a door is closing, and has no obstruction sensor,
  to keep the code short. `SPEC.md` describes both.
- Times are checked only where an expected log gives a bound.
