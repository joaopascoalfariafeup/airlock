//! A door, as an active object: its own thread, its own inbox and its own
//! timer. The decisions are in `DoorController::on_input`, which does no I/O, so
//! it can be tested on its own; `run` receives messages, asks it what to do,
//! and does it.

use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender};
use std::time::{Duration, Instant};

use crate::messages::{Door, DoorIn, DoorOut, Entry, HardwareIn, Light, LockIn, Motion};
use crate::run_context::{send, RunContext, POLL};
use crate::Millis;

// Required times (SPEC.md, section 2.2): the values of `Timing::SPEC`.
pub const MAX_OPEN: Millis = 5_000;
pub const MOVE_TIMEOUT: Millis = 2_000;
pub const RESPONSE_LIMIT: Millis = MAX_OPEN + 3 * MOVE_TIMEOUT;

/// A door's times, given when the airlock starts. A real airlock uses
/// `Timing::SPEC`; a test driver may give shorter times, to run faster than
/// real time.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Timing {
    pub max_open: Millis,
    pub move_timeout: Millis,
}

impl Timing {
    pub const SPEC: Timing = Timing { max_open: MAX_OPEN, move_timeout: MOVE_TIMEOUT };
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    Closed,
    Waiting, // token requested, not yet granted
    Opening,
    Open,
    Closing,
    Fault, // latched: ignores everything, and keeps the token
}

#[derive(Clone, Debug)]
pub struct DoorController {
    state: State,
    timing: Timing,
}

impl DoorController {
    pub fn new(timing: Timing) -> Self {
        DoorController { state: State::Closed, timing }
    }

    pub fn state(&self) -> State {
        self.state
    }

    // A door opens only once granted, and gives the token back only once closed.
    // The light is green only while the door is fully open.
    // Implements: P1, P2, P3, P4
    pub fn on_input(&mut self, input: DoorIn) -> Vec<DoorOut> {
        use DoorIn as In;
        use State::*;
        match (self.state, input) {
            (Fault, _) => vec![],
            (Closed, In::Button) => {
                self.state = Waiting;
                vec![DoorOut::Request]
            }
            (Waiting, In::Granted) => {
                self.state = Opening;
                vec![DoorOut::Motor(Motion::Open), DoorOut::StartTimer(self.timing.move_timeout)]
            }
            (Opening, In::FullyOpen) => {
                self.state = Open;
                vec![DoorOut::Light(Light::Green), DoorOut::StartTimer(self.timing.max_open)]
            }
            (Open, In::Passed | In::Timeout) => {
                self.state = Closing;
                vec![DoorOut::Light(Light::Red), DoorOut::Motor(Motion::Close), DoorOut::StartTimer(self.timing.move_timeout)]
            }
            (Closing, In::Closed) => {
                self.state = Closed;
                vec![DoorOut::StopTimer, DoorOut::Release]
            }
            (Opening | Closing, In::Timeout) => {
                self.state = Fault;
                vec![DoorOut::Alarm]
            }
            _ => vec![], // ignored: see SPEC.md, section 3.3 and D6
        }
    }
}

/// The door's thread. It is given its inbox, and the channels to the
/// interlocker and to its hardware, when it starts.
pub fn run(
    door: Door,
    timing: Timing,
    inbox: Receiver<DoorIn>,
    lock: SyncSender<LockIn>,
    hardware: SyncSender<HardwareIn>,
    context: &RunContext,
) {
    let mut machine = DoorController::new(timing);
    let mut deadline: Option<Instant> = None; // this door's timer
    while !context.stopped() {
        let wait = deadline.map_or(POLL, |d| d.saturating_duration_since(Instant::now()).min(POLL));
        let input = match inbox.recv_timeout(wait) {
            Ok(input) => input,
            Err(RecvTimeoutError::Timeout) if deadline.is_some_and(|d| Instant::now() >= d) => {
                deadline = None;
                DoorIn::Timeout
            }
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => return,
        };
        context.log(Entry::In(door, input));
        for out in machine.on_input(input) {
            context.log(Entry::Out(door, out));
            match out {
                DoorOut::Motor(motion) => send(&hardware, HardwareIn::Motor(motion)),
                DoorOut::Light(light) => send(&hardware, HardwareIn::Light(light)),
                DoorOut::Request => send(&lock, LockIn::Request(door)),
                DoorOut::Release => send(&lock, LockIn::Release(door)),
                DoorOut::StartTimer(ms) => deadline = Some(Instant::now() + Duration::from_millis(ms)),
                DoorOut::StopTimer => deadline = None,
                DoorOut::Alarm => send(&hardware, HardwareIn::Alarm),
            }
        }
    }
}
