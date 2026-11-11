//! Fake hardware for one door, on its own thread: the button, the motor, the
//! "fully open" and "closed" sensors, the light, the siren, and the passage
//! sensor. Each door has its own instance. It is part of the system, where a
//! driver would be: it stands for the driver and for the physical door. On a
//! real airlock, drivers for the real hardware would replace this module,
//! behind the same channels.

use std::sync::mpsc::{Receiver, RecvTimeoutError, SyncSender};

use crate::messages::{Door, DoorIn, HardwareIn, Motion, Signal};
use crate::run_context::{send, RunContext, POLL};
use crate::Millis;

/// How long a door usually takes to open or to close. A scenario may make it
/// slower, but not slower than MOVE_TIMEOUT.
pub const TRAVEL: Millis = 1_000;

/// The one kind of fault: a door whose sensor never confirms a movement.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Fault {
    None,
    Stuck { door: Door, nth: usize }, // this door's nth movement is never confirmed
}

/// The fake hardware's settings, the same for both doors.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Config {
    pub travel: Millis, // how long a door takes to move
    pub fault: Fault,   // which movement, if any, is never confirmed
}

/// The thread of one door's hardware. It receives commands from its
/// controller, and the user's presses and attempts to go through; it sends
/// the button and the sensor events to its controller, and shows the light
/// and sounds the siren to the user.
pub fn run(
    door: Door,
    config: Config,
    inbox: Receiver<HardwareIn>,
    controller: SyncSender<DoorIn>,
    user: SyncSender<(Door, Signal)>,
    context: &RunContext,
) {
    let mut due: Vec<(Millis, DoorIn)> = vec![]; // sensor events still to send, and when
    let mut fully_open = false; // only then can a user go through
    let mut moves = 0;
    let Config { travel, fault } = config;
    while !context.stopped() {
        // Send what is due.
        let now = context.now();
        due.retain(|&(t, event)| {
            if t <= now {
                fully_open = event == DoorIn::FullyOpen;
                send(&controller, event);
            }
            t > now
        });
        let input = match inbox.recv_timeout(POLL) {
            Ok(input) => input,
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => return,
        };
        match input {
            // The sensor answers after `travel`, unless this movement is the stuck one.
            HardwareIn::Motor(motion) => {
                fully_open = false;
                moves += 1;
                if fault == (Fault::Stuck { door, nth: moves }) {
                    continue;
                }
                let event = match motion {
                    Motion::Open => DoorIn::FullyOpen,
                    Motion::Close => DoorIn::Closed,
                };
                due.push((context.now() + travel, event));
            }
            HardwareIn::Press => send(&controller, DoorIn::Button),
            HardwareIn::Light(light) => send(&user, (door, Signal::Light(light))),
            HardwareIn::Alarm => send(&user, (door, Signal::Alarm)),
            // A red light does not stop a user: only the door's position does.
            HardwareIn::Pass if fully_open => send(&controller, DoorIn::Passed),
            HardwareIn::Pass => {} // the door is not open: the user cannot go through
        }
    }
}
