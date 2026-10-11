package com.strategywar;

/**
 * Per-unit driving logic. Fed the target's bearing and distance once per tick, it picks a mode and
 * produces redstone levels (0-15) for the Nav Table's sides.
 *
 * <ul>
 *   <li>FORWARD: drive at the target.</li>
 *   <li>REVERSE: target close behind: back into it tail-first.</li>
 *   <li>TURN: target far behind, inside the turning circle, or no progress (circling/stuck): back up with
 *       opposite lock so the nose swings toward the target, then drive forward. Repeats as a multi-point turn.</li>
 *   <li>ARRIVED: stop.</li>
 * </ul>
 */
public final class Driver {
    public enum Mode { FORWARD, REVERSE, TURN, ARRIVED }

    // Calibration knobs; tune here if your vehicles are much bigger or smaller.
    static final double ARRIVE = 4, LEAVE = 7;          // stop inside ARRIVE, start again outside LEAVE (hysteresis)
    static final double BACKUP_RANGE = 20;              // targets behind and closer than this are reversed into
    static final double TURN_RADIUS = 8;                // closer than this with the target off to the side: turn instead
    static final double FULL_LOCK = Math.toRadians(45); // steering error at which steering is maxed out
    static final int STUCK_TICKS = 60;                  // no progress for this long: try something else
    static final int MAX_TURN_TICKS = 80;               // longest single back-up during a TURN

    private static final double BEHIND = Math.toRadians(120);

    Mode mode = Mode.FORWARD;
    private double best = Double.MAX_VALUE;
    private int sinceBest, turnTicks;

    /** Outputs, as redstone strength. halt: 15 = stop, 0 = full speed (Analog Transmission). reverse: 15 = reverse (Gearshift). */
    int steerLeft, steerRight, halt = 15, reverse;

    /** A new order came in (or a mode finished): forget progress tracking and drive forward. */
    void retarget() {
        setMode(Mode.FORWARD);
    }

    private void setMode(Mode m) {
        mode = m;
        best = Double.MAX_VALUE;
        sinceBest = 0;
        turnTicks = 0;
    }

    /**
     * @param bearing  radians from the vehicle's nose to the target, positive = target on the left
     * @param distance horizontal blocks to the target
     */
    void tick(double bearing, double distance) {
        double off = Math.abs(bearing);
        if (distance < best - 0.5) {
            best = distance;
            sinceBest = 0;
        } else {
            sinceBest++;
        }
        boolean stuck = sinceBest > STUCK_TICKS;

        if (mode != Mode.ARRIVED && distance < ARRIVE) setMode(Mode.ARRIVED);
        switch (mode) {
            case ARRIVED -> {
                if (distance > LEAVE) setMode(Mode.FORWARD);
            }
            case FORWARD -> {
                if (off > BEHIND) setMode(distance < BACKUP_RANGE ? Mode.REVERSE : Mode.TURN);
                else if (off > Math.toRadians(60) && distance < TURN_RADIUS || stuck) setMode(Mode.TURN);
            }
            case REVERSE -> {
                if (off < Math.toRadians(60) || stuck) setMode(Mode.FORWARD); // target ended up ahead, or blocked behind
            }
            case TURN -> {
                if (off < Math.toRadians(20) || ++turnTicks > MAX_TURN_TICKS) setMode(Mode.FORWARD);
            }
        }

        // Steering, + = left. Reversing with left lock swings the tail left (and the nose right).
        double steer = switch (mode) {
            case FORWARD -> lock(bearing);                              // nose toward target
            case REVERSE -> -lock(Math.IEEEremainder(bearing - Math.PI, 2 * Math.PI)); // tail toward target
            case TURN -> -lock(bearing);                                // nose swings toward target
            case ARRIVED -> 0;
        };
        steerLeft = (int) Math.round(Math.max(0, steer));
        steerRight = (int) Math.round(Math.max(0, -steer));

        double approach = Math.clamp(distance / 12, 0.3, 1); // slow down near the target
        double speed = switch (mode) {
            case FORWARD -> approach * (off > Math.toRadians(45) ? 0.6 : 1);
            case REVERSE -> approach * 0.6;
            case TURN -> 0.5;
            case ARRIVED -> 0;
        };
        halt = (int) Math.round(15 * (1 - speed));
        reverse = mode == Mode.REVERSE || mode == Mode.TURN ? 15 : 0;
    }

    private static double lock(double error) {
        return Math.clamp(error / FULL_LOCK, -1, 1) * 15;
    }
}
