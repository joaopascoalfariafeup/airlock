//! The interlocker, as an active object. It holds the token that gives a door
//! the right to open. Requests that cannot be granted wait in a FIFO queue, so
//! neither door can starve.

use std::collections::VecDeque;
use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender};

use crate::messages::{Door, DoorIn, Entry, LockIn, LockOut};
use crate::run_context::{send, RunContext, POLL};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    Free,
    Held(Door), // by this door
}

#[derive(Clone, Debug)]
pub struct Interlocker {
    state: State,
    queue: VecDeque<Door>, // waiting doors, first come first served; empty when Free
}

impl Interlocker {
    pub fn new() -> Self {
        Interlocker { state: State::Free, queue: VecDeque::new() }
    }

    pub fn state(&self) -> State {
        self.state
    }

    // Implements: P1, P2
    pub fn on_input(&mut self, input: LockIn) -> Option<LockOut> {
        match (self.state, input) {
            (State::Free, LockIn::Request(d)) => {
                self.state = State::Held(d);
                Some(LockOut::Granted(d))
            }
            (State::Held(holder), LockIn::Request(d)) if d != holder && !self.queue.contains(&d) => {
                self.queue.push_back(d);
                None
            }
            (State::Held(holder), LockIn::Release(d)) if d == holder => {
                // The token goes to the first door waiting, if any.
                if let Some(n) = self.queue.pop_front() {
                    self.state = State::Held(n);
                    Some(LockOut::Granted(n))
                } else {
                    self.state = State::Free;
                    None
                }
            }
            // Ignored: a request from the holder or from a door already queued, and a
            // release by a door that is not the holder. Correct doors never send
            // these, but the interlocker does not rely on it.
            _ => None,
        }
    }
}

impl Default for Interlocker {
    fn default() -> Self {
        Self::new()
    }
}

/// The interlocker's thread. It is given its inbox and the channels to both
/// doors when it starts.
pub fn run(inbox: Receiver<LockIn>, doors: [SyncSender<DoorIn>; 2], context: &RunContext) {
    let mut lock = Interlocker::new();
    while !context.stopped() {
        let input = match inbox.recv_timeout(POLL) {
            Ok(input) => input,
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => return,
        };
        context.log(Entry::LockIn(input));
        if let Some(out) = lock.on_input(input) {
            context.log(Entry::LockOut(out));
            let LockOut::Granted(d) = out;
            send(&doors[d as usize], DoorIn::Granted);
        }
    }
}
