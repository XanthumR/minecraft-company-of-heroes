package com.strategywar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverTest {
    private static final double DEG = Math.PI / 180;

    @Test
    void drivesForwardSteeringTowardTarget() {
        Driver d = new Driver();
        d.tick(30 * DEG, 50);
        assertEquals(Driver.Mode.FORWARD, d.mode);
        assertEquals(10, d.steerLeft);
        assertEquals(0, d.steerRight);
        assertEquals(0, d.halt);    // far and nearly ahead: full speed
        assertEquals(0, d.reverse);
    }

    @Test
    void closeBehindBacksStraightIn() {
        Driver d = new Driver();
        d.tick(180 * DEG, 15);
        assertEquals(Driver.Mode.REVERSE, d.mode);
        assertEquals(15, d.reverse);
        assertTrue(d.halt < 15, "must actually move");
        assertEquals(0, d.steerLeft + d.steerRight); // dead astern: straight back
    }

    @Test
    void reversingSteersTailTowardTarget() {
        Driver d = new Driver();
        d.tick(150 * DEG, 15); // behind, on the left: tail must swing left
        assertEquals(Driver.Mode.REVERSE, d.mode);
        assertEquals(10, d.steerLeft);
        assertEquals(0, d.steerRight);
    }

    @Test
    void reversesAllTheWayAndArrives() {
        Driver d = new Driver();
        for (double dist = 15; dist > 4.5; dist -= 0.5) {
            d.tick(175 * DEG, dist);
            assertEquals(Driver.Mode.REVERSE, d.mode, "dist=" + dist);
        }
        d.tick(175 * DEG, 3);
        assertEquals(Driver.Mode.ARRIVED, d.mode);
        assertEquals(15, d.halt);
        assertEquals(0, d.reverse);
    }

    @Test
    void farBehindTurnsAroundWithOppositeLock() {
        Driver d = new Driver();
        d.tick(150 * DEG, 40); // behind on the left, too far to back into
        assertEquals(Driver.Mode.TURN, d.mode);
        assertEquals(15, d.reverse);
        assertEquals(15, d.steerRight); // back up steering right so the nose swings left
    }

    @Test
    void targetInsideTurningCircleTurns() {
        Driver d = new Driver();
        d.tick(80 * DEG, 5);
        assertEquals(Driver.Mode.TURN, d.mode);
    }

    @Test
    void circlingWithoutProgressTurns() {
        Driver d = new Driver();
        for (int t = 0; t <= Driver.STUCK_TICKS + 1; t++) d.tick(50 * DEG, 20); // orbiting: distance never shrinks
        assertEquals(Driver.Mode.TURN, d.mode);
    }

    @Test
    void turnEndsOnceNoseFacesTarget() {
        Driver d = new Driver();
        d.tick(150 * DEG, 40);
        d.tick(10 * DEG, 40);
        assertEquals(Driver.Mode.FORWARD, d.mode);
    }

    @Test
    void turnGivesUpAfterTimeout() {
        Driver d = new Driver();
        d.tick(150 * DEG, 40);
        for (int t = 0; t <= Driver.MAX_TURN_TICKS; t++) d.tick(90 * DEG, 40);
        assertEquals(Driver.Mode.FORWARD, d.mode);
    }

    @Test
    void stopsOnArrivalAndRestartsWhenPushedAway() {
        Driver d = new Driver();
        d.tick(0, 3);
        assertEquals(Driver.Mode.ARRIVED, d.mode);
        assertEquals(15, d.halt);
        assertEquals(0, d.steerLeft + d.steerRight);
        d.tick(0, 6); // inside hysteresis band: stay put
        assertEquals(Driver.Mode.ARRIVED, d.mode);
        d.tick(0, 8);
        assertEquals(Driver.Mode.FORWARD, d.mode);
    }
}
