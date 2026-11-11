//! The scenario runner, the test driver of TEST.md, section 3.2: a
//! `Scenario`, which says what the users do and what goes wrong, as read from a
//! scenario file, and `run`, which starts the airlock, plays the users and stops
//! the airlock. The users are the environment, not part of the system under
//! test: the system never identifies them, and the log holds only its own
//! events.

use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender};
use std::sync::Arc;

use airlock_rust::airlock::Airlock;
use airlock_rust::door_controller::{Timing, MAX_OPEN, MOVE_TIMEOUT, RESPONSE_LIMIT};
use airlock_rust::door_hardware::{Config, Fault, TRAVEL};
use airlock_rust::messages::{Door, DoorOut, Entry, HardwareIn, Light, Signal};
use airlock_rust::run_context::{send, Run, RunContext, POLL};
use airlock_rust::Millis;

/// One user: when they arrive, and the doors they want to go through, in
/// order, such as A then B to go in. Users are known only to the scenario:
/// the system never identifies them, and the log holds only its own events.
#[derive(Clone, Debug)]
pub struct User {
    pub start: Millis,
    pub route: Vec<Door>,
}

/// What the users do, and what goes wrong, in one run.
#[derive(Clone, Debug)]
pub struct Scenario {
    pub users: Vec<User>,
    pub walk_ms: Millis,   // from a green light to the users there going through
    pub cross_ms: Millis,  // from going through a door to pressing the next door's button
    pub travel_ms: Millis, // how long a door takes to move (usually TRAVEL)
    pub fault: Fault,
}

/// Extra time at the end of a run, so that a late event can still be seen.
const END_MARGIN: Millis = 1_000;

impl Scenario {
    /// Reads a scenario from text, as in the files in `scenarios/` (format:
    /// TEST.md, section 3.3). The
    /// parameters hold for the whole run, wherever they are written (by
    /// convention, first). Each `user` line is one user; its id only labels
    /// it in the file. Text after `#` is a comment.
    ///
    /// ```text
    /// walk = 1000     # parameter: users go through 1000 ms after a light turns green (default 1000)
    /// cross = 500     # parameter: a user presses the next door's button 500 ms after going through one (default 500)
    /// travel = 1500   # parameter: a door takes 1500 ms to move (default TRAVEL, 1000)
    /// stuck = a 2     # parameter, the fault: door A's 2nd movement is never confirmed (default none)
    /// user 1 at 0 through a b    # user 1 arrives at 0 ms, and goes through A, then B
    /// ```
    pub fn parse(text: &str) -> Result<Scenario, String> {
        let mut scenario = Scenario { users: vec![], walk_ms: 1_000, cross_ms: 500, travel_ms: TRAVEL, fault: Fault::None };
        for (n, line) in text.lines().enumerate() {
            let line = line.split('#').next().unwrap_or_default(); // drop the comment
            let bad = || format!("line {}: cannot read {:?}", n + 1, line.trim());
            let words: Vec<&str> = line.split_whitespace().collect();
            match words[..] {
                [] => {}
                ["user", _id, "at", ms, "through", ref doors @ ..] if !doors.is_empty() => {
                    let route = doors.iter().map(|d| to_door(d)).collect::<Option<Vec<Door>>>().ok_or_else(bad)?;
                    scenario.users.push(User { start: number(ms).ok_or_else(bad)?, route });
                }
                ["walk", "=", ms] => scenario.walk_ms = number(ms).ok_or_else(bad)?,
                ["cross", "=", ms] => scenario.cross_ms = number(ms).ok_or_else(bad)?,
                ["travel", "=", ms] => scenario.travel_ms = number(ms).ok_or_else(bad)?,
                ["stuck", "=", door, nth] => {
                    let (door, nth) = (to_door(door).ok_or_else(bad)?, number(nth).ok_or_else(bad)? as usize);
                    scenario.fault = Fault::Stuck { door, nth };
                }
                _ => return Err(bad()),
            }
        }
        Ok(scenario)
    }
}

/// Plays a scenario on real threads, `speed` times faster than real
/// time, and returns the log. It starts the airlock, plays the users on
/// the calling thread, and stops the airlock. The users talk only to the
/// doors' hardware: they press and go through, and see the lights. The
/// run ends when nothing more is planned, and the last press has been
/// served or its deadline has passed.
///
/// The airlock knows nothing of the speed. This run divides every time by
/// `speed`: the airlock's `Timing`, the doors' travel and the users'
/// times. Then it multiplies the times in the log by `speed`, so the log
/// is in the scenario's time, as if it had run in real time. `speed` must
/// divide 1000, so that the required times divide exactly.
pub fn run(scenario: &Scenario, speed: Millis) -> Run {
    assert!(speed > 0 && 1_000 % speed == 0, "speed must divide 1000, not {speed}");
    let scaled = |ms: Millis| ms / speed;
    let timing = Timing { max_open: scaled(MAX_OPEN), move_timeout: scaled(MOVE_TIMEOUT) };
    let fast = Scenario {
        users: scenario.users.iter().map(|u| User { start: scaled(u.start), route: u.route.clone() }).collect(),
        walk_ms: scaled(scenario.walk_ms),
        cross_ms: scaled(scenario.cross_ms),
        travel_ms: scaled(scenario.travel_ms),
        fault: scenario.fault,
    };
    let config = Config { travel: fast.travel_ms, fault: fast.fault };

    let context = Arc::new(RunContext::new());
    let airlock = Airlock::start(timing, config, &context);
    let after_press = scaled(RESPONSE_LIMIT + 2 * END_MARGIN);
    let end = users(&fast, after_press, &airlock.hardware, &airlock.signals, &context);
    airlock.stop();
    slow(context.run(end), speed)
}

fn number(word: &str) -> Option<u64> {
    word.parse().ok()
}

fn to_door(word: &str) -> Option<Door> {
    match word {
        "a" => Some(Door::A),
        "b" => Some(Door::B),
        _ => None,
    }
}

/// The log of a fast run, in the scenario's time: its times, and the doors'
/// timer durations, multiplied by `speed`.
fn slow(run: Run, speed: Millis) -> Run {
    let entry = |entry| match entry {
        Entry::Out(door, DoorOut::StartTimer(ms)) => Entry::Out(door, DoorOut::StartTimer(ms * speed)),
        entry => entry,
    };
    Run { trace: run.trace.into_iter().map(|(t, e)| (t * speed, entry(e))).collect(), end: run.end * speed }
}

/// What the users do at a door.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Action {
    Press(Door), // press the door's button
    Pass(Door),  // go through the door: everyone waiting there
}

/// The users, on the calling thread of `run`. Each arrives at its
/// start time and presses its first door's
/// button. When a door's light turns green, everyone waiting at it, on either
/// side, goes through together `walk_ms` later, if the light is still green;
/// if not, they stay where they are. A user who has gone through a door
/// presses the next door of their route `cross_ms` later. Returns when the run
/// ends: nothing more is planned, and `after_press` has passed since the last
/// press, enough for it to be served or its deadline to pass.
fn users(
    scenario: &Scenario,
    after_press: Millis,
    hardware: &[SyncSender<HardwareIn>; 2],
    signals: &Receiver<(Door, Signal)>,
    context: &RunContext,
) -> Millis {
    let users = &scenario.users;
    let mut through = vec![0; users.len()]; // how many doors of its route each user has gone through
    let mut green = [false; 2];
    let mut end = 0;
    // What the users will do, and when.
    let mut todo: Vec<(Millis, Action)> = users.iter().map(|u| (u.start, Action::Press(u.route[0]))).collect();
    // Who is waiting at a door: arrived, and that door is next on their route.
    let waiting_at = |door: Door, through: &[usize], now: Millis| -> Vec<usize> {
        (0..users.len()).filter(|&u| users[u].start <= now && users[u].route.get(through[u]) == Some(&door)).collect()
    };

    while context.now() < end || !todo.is_empty() {
        // Do what is due.
        let now = context.now();
        let (due, later): (Vec<_>, Vec<_>) = todo.into_iter().partition(|&(t, _)| t <= now);
        todo = later;
        for (_, action) in due {
            match action {
                Action::Press(door) => {
                    send(&hardware[door as usize], HardwareIn::Press);
                    end = end.max(now + after_press);
                }
                Action::Pass(door) if green[door as usize] => {
                    send(&hardware[door as usize], HardwareIn::Pass);
                    for u in waiting_at(door, &through, now) {
                        through[u] += 1;
                        if let Some(&next) = users[u].route.get(through[u]) {
                            todo.push((now + scenario.cross_ms, Action::Press(next)));
                        }
                    }
                }
                Action::Pass(_) => {} // too late: the door is closing, and the users stay
            }
        }
        // Watch the lights, and listen for the sirens.
        match signals.recv_timeout(POLL) {
            Ok((door, Signal::Light(light))) => {
                let now = context.now();
                green[door as usize] = light == Light::Green;
                if light == Light::Green && !waiting_at(door, &through, now).is_empty() {
                    todo.push((now + scenario.walk_ms, Action::Pass(door)));
                }
            }
            Ok((_, Signal::Alarm)) | Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
    end
}
