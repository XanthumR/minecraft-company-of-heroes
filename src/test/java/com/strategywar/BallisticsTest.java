package com.strategywar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BallisticsTest {
    @Test
    void solvedElevationHitsTarget() {
        for (Ballistics b : new Ballistics[]{
                Ballistics.RANGING_GUESS,
                new Ballistics(3, -0.05, 0.01, false),
                new Ballistics(6, -0.05, 0.002, true)}) {
            for (double dy : new double[]{-10, 0, 15}) {
                Double elev = b.solve(80, dy);
                assertNotNull(elev, b + " dy=" + dy);
                assertEquals(dy, b.heightAt(elev, 80), 0.05, b + " dy=" + dy);
            }
        }
    }

    @Test
    void outOfRangeIsNull() {
        assertNull(Ballistics.RANGING_GUESS.solve(5000, 0));
    }
}
