//! The system: the two door controllers, the interlocker and each door's
//! hardware, the five active objects of SPEC.md, section 3.1.
//! `Airlock::start` creates their channels, gives each the ones it needs, and
//! starts their threads: it is the communication diagram in code.
//!
//! The airlock's boundary is what a user does and sees (SPEC.md, section 2.3):
//! users press a door's button and go through it, at that door's hardware, and
//! see its light and hear its siren. The hardware here is a fake
//! (`door_hardware.rs`); on a real airlock, drivers for the real hardware
//! would replace that module, behind the same channels, and the rest of this
//! file would not change.

use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::Arc;
use std::thread::JoinHandle;

use crate::door_controller::{self, Timing};
use crate::door_hardware::{self, Config};
use crate::interlocker;
use crate::messages::{Door, HardwareIn, Signal};
use crate::run_context::{join, spawn, RunContext, QUEUE};

/// A running airlock.
pub struct Airlock {
    /// Its inputs from the users, at door A's and door B's hardware: `Press`
    /// and `Pass`.
    pub hardware: [SyncSender<HardwareIn>; 2],
    /// Its outputs to the users: each door's light and siren.
    pub signals: Receiver<(Door, Signal)>,
    threads: Vec<JoinHandle<()>>,
    context: Arc<RunContext>,
}

impl Airlock {
    /// Starts the two doors' controllers and hardware, and the interlocker,
    /// each on its own thread. `timing` is the doors' times, usually
    /// `Timing::SPEC`; `config` is the fake hardware's (how long a door takes
    /// to move, and which movement gets stuck). The threads log every message
    /// to `context`.
    pub fn start(timing: Timing, config: Config, context: &Arc<RunContext>) -> Airlock {
        let (a_tx, a_rx) = sync_channel(QUEUE); // to door A's controller
        let (b_tx, b_rx) = sync_channel(QUEUE); // to door B's controller
        let (lock_tx, lock_rx) = sync_channel(QUEUE); // to the interlocker
        let (hw_a_tx, hw_a_rx) = sync_channel(QUEUE); // to door A's hardware
        let (hw_b_tx, hw_b_rx) = sync_channel(QUEUE); // to door B's hardware
        let (user_tx, user_rx) = sync_channel(QUEUE); // to the users: lights and sirens

        let (lock, hw_a, hw_b) = (lock_tx.clone(), hw_a_tx.clone(), hw_b_tx.clone());
        let doors = [a_tx.clone(), b_tx.clone()];
        let (to_a, to_b, user) = (a_tx.clone(), b_tx.clone(), user_tx.clone());
        let threads = vec![
            spawn(context, move |c| door_controller::run(Door::A, timing, a_rx, lock, hw_a, c)),
            spawn(context, move |c| door_controller::run(Door::B, timing, b_rx, lock_tx, hw_b, c)),
            spawn(context, move |c| interlocker::run(lock_rx, doors, c)),
            spawn(context, move |c| door_hardware::run(Door::A, config, hw_a_rx, to_a, user, c)),
            spawn(context, move |c| door_hardware::run(Door::B, config, hw_b_rx, to_b, user_tx, c)),
        ];
        Airlock { hardware: [hw_a_tx, hw_b_tx], signals: user_rx, threads, context: Arc::clone(context) }
    }

    /// Ends the run, and waits for the threads to finish.
    pub fn stop(self) {
        self.context.stop();
        self.threads.into_iter().for_each(join);
    }
}
