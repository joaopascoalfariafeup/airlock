//! Every message between the objects of SPEC.md, section 3.1, and every entry
//! of the event log. Message names are the names in the specification, so the
//! log prints them (with `{:?}`) as the expected logs in `../scenarios/` write
//! them, such as `Out(A, Motor(Open))`. It uses no other module (only `Millis`,
//! from `lib.rs`): the others use it, and the components depend on each
//! other only through it.

use crate::Millis;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Door {
    A,
    B,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Motion {
    Open,
    Close,
}

/// The light above a door, which tells the user whether to go through.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Light {
    Red,
    Green,
}

/// Messages a door receives.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DoorIn {
    Button,    // from the door's button, through its hardware
    FullyOpen, // from the door's sensors
    Closed,    // from the door's sensors
    Passed,    // from the door's passage sensor: the user went through
    Granted,   // from the interlocker: you hold the token
    Timeout,   // from the door's own timer
}

/// What a door decides to do.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DoorOut {
    Motor(Motion), // to the hardware
    Light(Light),  // to the hardware
    Alarm,         // to the hardware: the siren
    Request,            // to the interlocker
    Release,            // to the interlocker
    StartTimer(Millis), // replaces any running timer
    StopTimer,
}

/// Messages a door's hardware receives.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HardwareIn {
    Motor(Motion), // from the door's controller
    Light(Light),  // from the door's controller
    Alarm,         // from the door's controller: sound the siren
    Press,         // from the user, who presses the door's button
    Pass,          // from the user, who tries to go through
}

/// Messages the interlocker receives.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LockIn {
    Request(Door),
    Release(Door),
}

/// Messages the interlocker sends, each addressed to one door.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LockOut {
    Granted(Door),
}

/// What a user at the door sees or hears.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Signal {
    Light(Light),
    Alarm, // the siren
}

/// One logged message.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Entry {
    In(Door, DoorIn),   // received by a door
    Out(Door, DoorOut), // sent or done by a door
    LockIn(LockIn),     // received by the interlocker
    LockOut(LockOut),   // sent by the interlocker
}
