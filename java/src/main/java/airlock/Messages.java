package airlock;

/**
 * Every message between the objects of SPEC.md, section 3.1, and every entry
 * of the event log. Message names are the names in the specification, so the
 * log prints them as the expected logs in scenarios/ write them, such as
 * {@code Out(A, Motor(Open))}.
 */
public final class Messages {
    private Messages() {}

    public enum Door { A, B }

    public enum Motion { Open, Close }

    /** The light above a door, which tells the user whether to go through. */
    public enum Light { Red, Green }

    /** Messages a door receives. */
    public enum DoorIn {
        Button,    // from the door's button, through its hardware
        FullyOpen, // from the door's sensors
        Closed,    // from the door's sensors
        Passed,    // from the door's passage sensor: the user went through
        Granted,   // from the interlocker: you hold the token
        Timeout    // from the door's own timer
    }

    /** What a door decides to do. */
    public sealed interface DoorOut {
        record Motor(Motion motion) implements DoorOut { // to the hardware
            @Override public String toString() { return "Motor(" + motion + ")"; }
        }
        record Light(Messages.Light light) implements DoorOut { // to the hardware
            @Override public String toString() { return "Light(" + light + ")"; }
        }
        record Alarm() implements DoorOut { // to the hardware: the siren
            @Override public String toString() { return "Alarm"; }
        }
        record Request() implements DoorOut { // to the interlocker
            @Override public String toString() { return "Request"; }
        }
        record Release() implements DoorOut { // to the interlocker
            @Override public String toString() { return "Release"; }
        }
        record StartTimer(long ms) implements DoorOut { // replaces any running timer
            @Override public String toString() { return "StartTimer(" + ms + ")"; }
        }
        record StopTimer() implements DoorOut {
            @Override public String toString() { return "StopTimer"; }
        }
    }

    /** Messages a door's hardware receives. */
    public sealed interface HardwareIn {
        record Motor(Motion motion) implements HardwareIn {} // from the door's controller
        record Light(Messages.Light light) implements HardwareIn {} // from the door's controller
        record Alarm() implements HardwareIn {} // from the door's controller: sound the siren
        record Press() implements HardwareIn {} // from the user, who presses the door's button
        record Pass() implements HardwareIn {} // from the user, who tries to go through
    }

    /** Messages the interlocker receives. */
    public sealed interface LockIn {
        record Request(Door door) implements LockIn {
            @Override public String toString() { return "Request(" + door + ")"; }
        }
        record Release(Door door) implements LockIn {
            @Override public String toString() { return "Release(" + door + ")"; }
        }
    }

    /** Messages the interlocker sends, each addressed to one door. */
    public record Granted(Door door) {
        @Override public String toString() { return "Granted(" + door + ")"; }
    }

    /** What a user at a door sees or hears. */
    public sealed interface Signal {
        record Light(Messages.Light light) implements Signal {}
        record Alarm() implements Signal {} // the siren
    }

    /** One logged message. */
    public sealed interface Entry {
        record In(Door door, DoorIn input) implements Entry { // received by a door
            @Override public String toString() { return "In(" + door + ", " + input + ")"; }
        }
        record Out(Door door, DoorOut output) implements Entry { // sent or done by a door
            @Override public String toString() { return "Out(" + door + ", " + output + ")"; }
        }
        record LockInput(LockIn input) implements Entry { // received by the interlocker
            @Override public String toString() { return "LockIn(" + input + ")"; }
        }
        record LockOutput(Granted output) implements Entry { // sent by the interlocker
            @Override public String toString() { return "LockOut(" + output + ")"; }
        }
    }
}
