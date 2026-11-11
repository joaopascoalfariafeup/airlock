/// Time in milliseconds.
pub type Millis = u64;

pub mod airlock; // the system: starts the doors and the interlocker, and stops them
pub mod door_controller; // a door's controller: its decisions, its thread
pub mod door_hardware; // fake hardware of one door, on its own thread
pub mod interlocker; // the interlocker: its decisions, its thread
pub mod messages; // every message and log entry
pub mod run_context; // what all threads of a run share: clock, event log, stop flag
