#!/usr/bin/env python3
"""tracecheck: checks an actual log against an expected log.

    python tracecheck.py EXPECTED ACTUAL [--slack MS]
    python tracecheck.py --dir SCENARIOS ACTUALS [--slack MS]

The first form checks one pair of files; the second checks every
`<name>.expected.txt` in SCENARIOS against `<name>.actual.txt` in ACTUALS.
The exit code is 0 if every log matches, 1 if one does not, and 2 if a file
cannot be read. The notation is described in README.md, next to this file.
Only the Python standard library is used.
"""

import re
import sys
from pathlib import Path

DEFAULT_SLACK = 500  # ms: how late a line may be beyond a `within` bound
BOUND = re.compile(r'\s+(after|within)\s+(\d+)\s+of\s+(\w+)\s*$')
LABEL = re.compile(r'^(\w+):\s*(.*)$')
TIME = re.compile(r'#\s*(\d+)\s*ms')


class Line:
    """One line of an expected log: an arrow (or "..."), its label, and its bounds."""

    def __init__(self, text, label, bounds, number):
        self.text, self.label, self.bounds, self.number = text, label, bounds, number


def read_expected(path):
    """The alternatives of an expected log: lists of Lines, separated by lines `or`."""
    alternatives = [[]]
    for number, raw in enumerate(Path(path).read_text(encoding='utf-8').splitlines(), 1):
        line = raw.split('#', 1)[0].strip()  # drop the comment
        if not line:
            continue
        if line == 'or':
            alternatives.append([])
            continue
        label = None
        m = LABEL.match(line)
        if m and '(' not in m.group(1):
            label, line = m.group(1), m.group(2).strip()
        bounds = []
        while (m := BOUND.search(line)):
            bounds.insert(0, (m.group(1) == 'after', int(m.group(2)), m.group(3)))
            line = line[:m.start()].strip()
        if re.search(r'\b(after|within)\b', line):
            raise ValueError(f'{path}, line {number}: write "after <ms> of <label>" or "within <ms> of <label>"')
        defined = {l.label for l in alternatives[-1]}
        for _, _, of in bounds:
            if of not in defined:
                raise ValueError(f'{path}, line {number}: "{of}" is not the label of an earlier line')
        alternatives[-1].append(Line(' '.join(line.split()), label, bounds, number))
    return alternatives


def read_actual(path):
    """The actual log: (time in ms, arrow) for each line, as the tests write it."""
    log = []
    for number, raw in enumerate(Path(path).read_text(encoding='utf-8').splitlines(), 1):
        text, _, comment = raw.partition('#')
        text = ' '.join(text.split())
        if not text:
            continue
        m = TIME.search('#' + comment)
        if not m:
            raise ValueError(f'{path}, line {number}: no time ("# <ms> ms") after "{text}"')
        log.append((int(m.group(1)), text))
    return log


def align(log, expected, at=0):
    """Where each expected line matches the log, from `at` on, or None. A line "..." stands
    for any number of log lines and gets None; each line matches as early as it can."""
    if not expected:
        return [] if at == len(log) else None
    first, rest = expected[0], expected[1:]
    if first.text == '...':
        for skip in range(at, len(log) + 1):
            tail = align(log, rest, skip)
            if tail is not None:
                return [None] + tail
        return None
    if at < len(log) and log[at][1] == first.text:
        tail = align(log, rest, at + 1)
        if tail is not None:
            return [at] + tail
    return None


def broken_bounds(log, expected, positions, slack):
    """The time bounds that an aligned log breaks."""
    times, broken = {}, []
    for line, i in zip(expected, positions):
        if i is None:
            continue
        t = log[i][0]
        if line.label:
            times[line.label] = t
        for after, ms, of in line.bounds:
            gap = t - times[of]
            if after and gap < ms:
                broken.append(f'"{line.text}" came {gap} ms after {of}, less than {ms} ms')
            if not after and gap > ms + slack:
                broken.append(f'"{line.text}" came {gap} ms after {of}, more than {ms} ms (+ {slack} ms of tolerance)')
    return broken


def first_mismatch(log, expected):
    """For a log that does not match in order: where a match, line by line and as early as
    possible, first fails. It explains the failure, but a different alignment might fail
    elsewhere."""
    pos, gap = 0, False
    for line in expected:
        if line.text == '...':
            gap = True
            continue
        if gap:
            found = next((i for i in range(pos, len(log)) if log[i][1] == line.text), None)
            if found is None:
                return f'line {line.number}, "{line.text}", is not in the log after its line {pos + 1}'
            pos, gap = found + 1, False
        elif pos < len(log) and log[pos][1] == line.text:
            pos += 1
        elif pos < len(log):
            return f'line {line.number} expects "{line.text}", but the log has "{log[pos][1]}" (its line {pos + 1})'
        else:
            return f'line {line.number} expects "{line.text}", but the log has ended'
    if not gap and pos < len(log):
        return f'the log goes on after the expected end, with "{log[pos][1]}" (its line {pos + 1})'
    return 'no match'


def check(expected_path, actual_path, slack):
    """(n, None) if the actual log matches alternative n of the expected log (1 for
    the first), or (None, problems) if it matches none."""
    alternatives = read_expected(expected_path)
    log = read_actual(actual_path)
    problems = []
    for n, alternative in enumerate(alternatives, 1):
        positions = align(log, alternative)
        name = f'alternative {n}: ' if len(alternatives) > 1 else ''
        if positions is None:
            problems.append(name + first_mismatch(log, alternative))
            continue
        broken = broken_bounds(log, alternative, positions, slack)
        if not broken:
            return n, None
        problems.append(name + 'in order, but ' + '; '.join(broken))
    return None, problems


def main(argv):
    args = list(argv)
    slack = DEFAULT_SLACK
    if '--slack' in args:
        i = args.index('--slack')
        slack = int(args[i + 1])
        del args[i:i + 2]
    if len(args) == 3 and args[0] == '--dir':
        scenarios, actuals = Path(args[1]), Path(args[2])
        pairs = [(e, actuals / (e.name[:-len('.expected.txt')] + '.actual.txt'))
                 for e in sorted(scenarios.glob('*.expected.txt'))]
    elif len(args) == 2:
        pairs = [(Path(args[0]), Path(args[1]))]
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    failed = 0
    for expected, actual in pairs:
        try:
            matched, problems = check(expected, actual, slack)
            count = len(read_expected(expected))
        except (OSError, ValueError) as e:
            print(f'ERROR {expected.name}: {e}')
            return 2
        if problems is None:
            which = f' (alternative {matched} of {count})' if count > 1 else ''
            print(f'ok    {expected.name}{which}')
        else:
            failed += 1
            print(f'FAIL  {expected.name} (against {actual})')
            for p in problems:
                print(f'      {p}')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
