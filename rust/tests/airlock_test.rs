//! System tests: the whole airlock on real threads, 10 times faster than real
//! time. Each scenario file in `../scenarios/` is run; P1 is checked over the
//! whole log; and the log, written to `target/scenarios/<name>.actual.txt`, is
//! compared with the expected log by the provided tool, `tracecheck` (in
//! order and within the time bounds it gives). Each run takes a second or two.
//! The tool needs Python 3.8 or later: `python3`, or the program named by the
//! environment variable `PYTHON`.

mod scenario_runner; // the test driver: a scenario, and running it (tests/scenario_runner/)

use airlock_rust::messages::{Door, DoorIn, DoorOut, Entry, Motion};
use airlock_rust::run_context::Run;
use std::process::Command;
use std::{env, fs};

/// Simulated milliseconds per real millisecond.
const SPEED: u64 = 10;

/// Where the actual logs go: in the build folder, so that they are not
/// committed, and the Rust and Java versions do not overwrite each other's.
const ACTUAL_DIR: &str = "target/scenarios";

/// The scenarios, and the tool, shared with the Java version, one level up.
/// (Cargo runs tests from the package folder. Not env!("CARGO_MANIFEST_DIR"):
/// that is fixed at compile time, and goes stale when the folder is moved.)
const SCENARIOS: &str = "../scenarios";
const TRACECHECK: &str = "../tracecheck/tracecheck.py";

/// P1 over the whole log, whatever the scenario: a door never starts opening
/// while the other is not closed. A door is not closed from its
/// `Motor(Open)` until its sensor reports `Closed`. An expected log cannot
/// check this where it has a gap (`...`), which accepts any message.
// Verifies: P1
fn both_doors_open(log: &Run) -> Option<String> {
    let mut not_closed = [false, false];
    for (t, entry) in &log.trace {
        match *entry {
            Entry::Out(door, DoorOut::Motor(Motion::Open)) => {
                let other = match door {
                    Door::A => Door::B,
                    Door::B => Door::A,
                };
                if not_closed[other as usize] {
                    return Some(format!("P1 broken at {t} ms: door {door:?} starts opening while door {other:?} is not closed"));
                }
                not_closed[door as usize] = true;
            }
            Entry::In(door, DoorIn::Closed) => not_closed[door as usize] = false,
            _ => {}
        }
    }
    None
}

/// Writes `target/scenarios/<name>.actual.txt`, the format `tracecheck`
/// reads: one arrow per line, with its time as a comment. The log records a
/// message between components twice, when sent and when received; a diagram
/// shows it once, so the received copy is left out.
fn write_actual(name: &str, log: &Run) -> String {
    let path = format!("{ACTUAL_DIR}/{name}.actual.txt");
    let mut text = format!("# Actual log of {name}: one line per arrow, with its time (in the airlock's time).\n# Never copy it into an expected log.\n");
    for (t, entry) in &log.trace {
        if !matches!(entry, Entry::LockIn(_) | Entry::In(_, DoorIn::Granted)) {
            text.push_str(&format!("{:<28}# {t} ms\n", format!("{entry:?}")));
        }
    }
    fs::create_dir_all(ACTUAL_DIR).and_then(|()| fs::write(&path, text)).unwrap_or_else(|e| panic!("{path}: {e}"));
    path
}

/// Runs `tracecheck` on one expected log and one actual log. Returns which
/// alternative matched (1 for the first), or the tool's report.
fn tracecheck(expected: &str, actual: &str) -> Result<usize, String> {
    let python = env::var("PYTHON").unwrap_or_else(|_| "python3".to_string());
    let out = Command::new(&python)
        .args(["-I", TRACECHECK, expected, actual])
        .output()
        .unwrap_or_else(|e| panic!("cannot run {python} {TRACECHECK} ({e}): install Python 3.8 or later, or set PYTHON"));
    let report = String::from_utf8_lossy(&out.stdout).to_string();
    match out.status.code() {
        Some(0) => Ok(report.split("(alternative ").nth(1).and_then(|s| s.split(' ').next()?.parse().ok()).unwrap_or(1)),
        Some(1) => Err(report),
        _ => panic!("tracecheck could not read {expected}:\n{report}{}", String::from_utf8_lossy(&out.stderr)),
    }
}

/// Every scenario in `scenarios/` (S1 to S6, and any file added there). A
/// scenario whose expected log has alternatives runs 10 times, the others
/// once; `REPEAT=n cargo test` runs each n times, to look for flaky behaviour.
/// A scenario stops at its first failing run, whose actual log is kept. The
/// scenarios run one after the other: many runs at once would delay each
/// other's threads, and a timer could then fire before a sensor's message.
// Scenario: S1, S2, S3, S4, S5, S6
// Verifies: P1, P2, P3, P4
#[test]
fn every_scenario_file() {
    let repeat: Option<usize> = env::var("REPEAT").ok().and_then(|n| n.parse().ok());
    let mut names: Vec<String> = fs::read_dir(SCENARIOS)
        .expect("the scenarios folder")
        .filter_map(|entry| entry.ok()?.file_name().into_string().ok())
        .filter_map(|file| Some(file.strip_suffix(".txt")?.to_string()))
        .filter(|name| !name.ends_with(".expected"))
        .collect();
    names.sort();

    let mut failures = vec![];
    for name in &names {
        let text = fs::read_to_string(format!("{SCENARIOS}/{name}.txt")).expect("a readable file");
        let scenario = scenario_runner::Scenario::parse(&text).unwrap_or_else(|e| panic!("{name}.txt: {e}"));
        let expected = format!("{SCENARIOS}/{name}.expected.txt");
        let has_expected = fs::metadata(&expected).is_ok();
        let alternatives = if has_expected { fs::read_to_string(&expected).unwrap().lines().filter(|l| l.split('#').next().unwrap_or_default().trim() == "or").count() + 1 } else { 1 };
        let runs = repeat.unwrap_or(if alternatives > 1 { 10 } else { 1 });
        let mut matched = vec![0; alternatives]; // runs that matched each alternative
        for run in 1..=runs {
            let log = scenario_runner::run(&scenario, SPEED);
            let actual = write_actual(name, &log);
            let verdict = match both_doors_open(&log) {
                Some(broken) => Err(broken),
                None if has_expected => tracecheck(&expected, &actual),
                None => Ok(1), // no expected log: any log will do
            };
            match verdict {
                Ok(i) => matched[i - 1] += 1,
                Err(why) => {
                    failures.push(format!("{name}, run {run} of {runs} (log in rust/{actual}):\n{why}"));
                    break;
                }
            }
        }
        println!("{name}: {runs} runs, matched by alternative: {matched:?}");
    }
    assert!(failures.is_empty(), "{}", failures.join("\n\n"));
}
