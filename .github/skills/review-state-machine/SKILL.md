---
name: review-state-machine
description: Reviews a component's decision function against its state machine in SPEC.md, transition by transition, including the events that must be ignored. Use it after changing a decision function, and for the design-conformance criterion of TEST.md. It reports; it does not change the code.
---

# Review a decision function against its state machine

The design says each component decides in a function with no I/O, threads
or clock (D4): (state, message) to (new state, actions). This review checks
that the function is exactly the state machine of SPEC.md, section 3.3: every
transition is there, with the same guard, outputs and next state, and
nothing else happens.

## Steps

1. Read the state machine (its diagram and caption) and the message table
   (Table 4) in SPEC.md. List the transitions, with their ids (T1, T2, …).
2. Read the decision function (in the example, `onInput` in Java,
   `on_input` in Rust) and nothing else of the code.
3. For each transition, find the code that implements it, and check:
   - the source state and the message (and the guard, if any);
   - the outputs, in the same order;
   - the next state.
4. Check the other direction: every branch of the code must be a transition
   of the diagram, or a message that is deliberately ignored (D6).
5. For each state, list the messages that can arrive in it but have no
   transition (sneak paths), and check that the code ignores them: no
   output, no change of state. Note any that may arrive in normal operation
   (for example, a press while a door is closing).
6. Check that the function does no I/O, reads no clock, and touches no
   shared state.

## Output

A table with one row per transition: id; source state and message; outputs
and next state in the diagram; where in the code; OK or the difference. Then
a list of the ignored messages per state, and every branch of the code that
is not in the diagram. End with a verdict: conforms, or the differences to
fix, in the code or in the diagram. Do not change either: the user decides.
