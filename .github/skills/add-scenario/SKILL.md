---
name: add-scenario
description: Test first, step 1. Adds the next scenario of TEST.md (a scenario file and its expected log), runs the tests, and checks that the new scenario fails. Use it before implementing any new behaviour. It never changes the controller's code.
---

# Add the next scenario, and see it fail

You are doing step 1 of the test-first cycle (TEST.md; AGENTS.md). Your job
ends when the new scenario exists and fails for the right reason. Do not
implement anything.

## Steps

1. Read TEST.md, section 2, and find the first scenario in the table that
   has no file in `scenarios/` yet. If the user named a scenario, use that one.
2. Write `scenarios/<name>.txt` from the "Inputs" column, in the format of
   TEST.md, section 3.3. Start the file with a comment that gives the
   scenario's id and what it checks (journeys, flows, properties).
3. Write `scenarios/<name>.expected.txt` with the `derive-expected-log`
   skill, or by following its rules: from SPEC.md and TEST.md only. Never
   read the controller's code or any actual log for this.
4. Run the one command (for example `cargo test` or `mvn test`).
5. Check that the new scenario fails, and read why:
   - it should fail because the behaviour is missing (a message is missing
     or out of order, or a time bound is not met);
   - if it fails because the file cannot be parsed, or the test driver
     cannot run it, fix the scenario file or the driver, not the expected
     log, and run again;
   - if it passes, say so: either the behaviour already exists, or the
     scenario does not check what TEST.md says. Do not weaken it.
6. Stop, and report: the two files, the command and its result, and the
   first difference that tracecheck reported.

## Never

- Change the controller's code, the state machines, or another scenario.
- Copy an actual log (`target/scenarios/*.actual.txt`) into an expected log.
- Change an expected log so that a test passes.
