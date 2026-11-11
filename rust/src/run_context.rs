//! The context of one run: what all its threads share (the clock, the event
//! log and the stop flag), and helpers for channels and threads. Every
//! message, internal ones included, is logged with its time, so tests can
//! check the whole system afterwards.
//!
//! One `RunContext` is created per run, in `scenario_runner::run`, inside an `Arc`:
//! every thread holds a reference to it, and it lives until the last one has
//! finished. Nothing else is shared between threads: components talk only
//! through channels. Each part is shared safely in its own way:
//!
//! - the clock: `start` is set before any thread starts, and only read, so it
//!   needs no lock;
//! - the event log: in a `Mutex`, so one thread at a time adds to it;
//! - the stop flag: an `AtomicBool`, set once by `Airlock::stop` and read by
//!   every thread at least every `POLL`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::SyncSender;
use std::sync::{Arc, Mutex, PoisonError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::messages::Entry;
use crate::Millis; // from lib.rs

/// How long an idle thread waits for a message before it checks whether it
/// must stop, and delivers what is due. Real time, even in a fast run.
pub const POLL: Duration = Duration::from_millis(2);
/// Channel capacity. Each thread has at most a few messages in flight.
pub const QUEUE: usize = 16;

/// The event log: each message with the time it was logged.
pub type Trace = Vec<(Millis, Entry)>;

/// A finished run. `end` is when it stopped, which can be later than the last
/// entry: after an alarm, for example, nothing more is logged.
#[derive(Clone, Debug)]
pub struct Run {
    pub trace: Trace,
    pub end: Millis,
}

/// The state shared by all threads of one run (see the module comment).
pub struct RunContext {
    start: Instant,      // the start of the run: time 0
    trace: Mutex<Trace>, // the event log; one thread at a time adds to it
    stop: AtomicBool,    // set once, at the end of the run
}

impl RunContext {
    /// Starts the clock at time 0, with an empty log.
    pub fn new() -> Self {
        RunContext { start: Instant::now(), trace: Mutex::new(vec![]), stop: AtomicBool::new(false) }
    }

    /// The time now, in milliseconds since the start of the run.
    pub fn now(&self) -> Millis {
        self.start.elapsed().as_millis() as Millis
    }

    /// Adds `entry` to the log, with the time now.
    ///
    /// The time is read after taking the lock, not before, so the log is
    /// always in time order: a thread that read the time earlier but got the
    /// lock later could otherwise add an older time after a newer one. If
    /// another thread panicked while holding the lock, the log is still used
    /// as it is (a panic must not hide the rest of the run).
    pub fn log(&self, entry: Entry) {
        let mut trace = self.trace.lock().unwrap_or_else(PoisonError::into_inner);
        trace.push((self.now(), entry));
    }

    /// Tells every thread to finish. Called once, by `Airlock::stop`, when
    /// the run is over.
    pub fn stop(&self) {
        self.stop.store(true, Ordering::SeqCst);
    }

    /// Whether the run is over. Each thread checks this at least every
    /// `POLL`, and returns when it is true.
    pub fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }

    /// The log, for the tests to check, once every thread has finished.
    pub fn run(&self, end: Millis) -> Run {
        Run { trace: self.trace.lock().unwrap_or_else(PoisonError::into_inner).clone(), end }
    }
}

impl Default for RunContext {
    fn default() -> Self {
        Self::new()
    }
}

/// Sends `msg` on a channel. If the channel is full, this waits until there
/// is room. A send fails only once the receiver has stopped, at the end of a
/// run, so the error is ignored.
pub fn send<T>(to: &SyncSender<T>, msg: T) {
    let _ = to.send(msg);
}

/// Starts a thread that runs `body` with its own reference to `context`.
pub fn spawn(context: &Arc<RunContext>, body: impl FnOnce(&RunContext) + Send + 'static) -> JoinHandle<()> {
    let context = Arc::clone(context);
    thread::spawn(move || body(&context))
}

/// Waits for a thread to finish. If it panicked, this panics too, so that a
/// test fails.
pub fn join(thread: JoinHandle<()>) {
    if let Err(panic) = thread.join() {
        std::panic::resume_unwind(panic);
    }
}
