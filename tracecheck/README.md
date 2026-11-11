# tracecheck: expected logs against actual logs

A small tool, provided with the assignment, that checks the log of a test run
(the *actual log*) against the log the specification says it should be (the
*expected log*). It does not depend on your programming language: your tests
write each run's log to a text file, and the tool compares it with the
expected log. You write the scenarios and the expected logs; you do not need
to write the matching.

```
python tracecheck.py EXPECTED ACTUAL [--slack MS]
python tracecheck.py --dir SCENARIOS ACTUALS [--slack MS]
```

The first form checks one pair of files. The second checks every
`<name>.expected.txt` in the folder SCENARIOS against `<name>.actual.txt` in
the folder ACTUALS. The exit code is 0 if every log matches, 1 if one does
not, and 2 if a file cannot be read; so a test can run the tool and fail when
it does. It needs Python 3.8 or later, and nothing else.

From the worked example, after its tests have run:

```
python tracecheck/tracecheck.py --dir scenarios rust/target/scenarios
```

## The actual log

One message per line, as your log prints it, with its time in milliseconds
as a comment. Lines that start with `#` are ignored. Write it in the time of
the real system, even if the tests run faster (multiply the times back).

```text
In(A, Button)               # 0 ms
Out(A, Request)             # 0 ms
Out(A, Motor(Open))         # 0 ms
In(A, Timeout)              # 2000 ms
```

The messages are compared as text, after collapsing runs of spaces, so the
expected log must write them exactly as your log prints them.

## The expected log

One message per line, in order, and nothing else in between, except where a
line says otherwise. Text after `#` is a comment.

| Line | Meaning |
|---|---|
| `In(A, Button)` | exactly this message, next |
| `...` | any number of messages, none included |
| `or` | separates alternatives: the log must match one of them |
| `t1: In(A, Button)` | a label, `t1`, for this line, used by bounds on later lines |
| `In(A, FullyOpen)  within 11000 of t1` | at most 11 000 ms after the line labelled `t1`, plus a tolerance (`--slack`, 500 ms by default), since threads are always a little late |
| `In(A, Timeout)  after 2000 of t1` | at least 2 000 ms after `t1`, with no tolerance |

A line may have both bounds (`after 2000 of t1  within 2000 of t1`). Each
line matches the earliest message it can, after the line before it, and the
bounds are checked on that match. Without a final `...`, nothing may follow
the last line.

When a log does not match, the tool says where, for each alternative: the
first expected line it could not find, or the bound it broke and by how much.

## What it cannot see

A `...` accepts any messages, including ones that should never happen: a
forbidden message hidden in a gap goes unnoticed. Where a property must hold
over the whole run, whatever the scenario (for example, "the two doors are
never open at once"), check it with a small function over the whole log in
your test, as the worked example does for P1.
