---
name: derive-expected-log
description: Writes the expected log of a scenario from the specification alone (SPEC.md state machines and message table, TEST.md rules), as an independent test oracle. Use it in a fresh session that has not seen the code. It never reads the code or the actual logs.
---

# Derive an expected log from the specification

An expected log is a test oracle: it says what the specification requires,
so it must not be influenced by what the program does. Work as if the code
did not exist.

## Read only

- SPEC.md: the requirements (section 2), the message table (Table 4, with the
  form each message takes in the log) and the state machines (section 3.3).
- TEST.md: the scenario's row in section 2, and the rules in section 3.3.
- The scenario file, `scenarios/<name>.txt`.
- `tracecheck/README.md`, for the notation.

Do not open the source code, the tests, or `target/`. If you cannot write a
line without them, the specification is incomplete: stop, and say what is
missing.

## Rules (TEST.md, section 3.3)

1. Follow the scenario in time: each user action, and each fault, in order.
2. One line per message to or from a controller, in the form of Table 4.
   Messages between the users and the hardware are not logged.
3. Each message a controller receives (`In`) is followed by the outputs of
   the transition it triggers, in the order the state machine lists them.
   Write the transition's id in a comment (`# T4`). A message with no
   transition in that state is ignored (D6): it is logged, with no outputs.
4. A door's `Request` is followed by the coordinator's grant if the resource
   is free; a `Release` by the next waiting door's grant. A message logged by
   both its sender and its receiver appears once, as Table 4 says.
5. Where a requirement sets a time, label the line it counts from (`t1:`) and
   bound the later line: `within <ms> of t1` for "at most", `after <ms> of t1`
   for "not before", with the property and the named time in a comment
   (`# P2: RESPONSE_LIMIT`).
6. Where the order of two events is not fixed by the specification (a race),
   write each valid order as an alternative, separated by `or`, or use `...`
   for the part that may vary. Never pick the order you expect the program to
   produce.
7. End with `...` only if later messages do not matter; otherwise the end of
   the log is checked too. For a scenario about a late event, you may start
   with `...` at the first message that shows what it is about.

## Output

The file `scenarios/<name>.expected.txt`, starting with a comment that gives
the scenario's id and the requirements it checks. Then list, for the user to
review: every transition the log takes, every time bound and the property it
checks, and every place where you had to interpret the specification.
