package com.strategywar;

/**
 * A shell's flight characteristics, in blocks and ticks. Mirrors how CBC moves projectiles:
 * each tick {@code pos += vel}, then {@code vel += gravity - drag along vel}.
 */
public record Ballistics(double speed, double gravity, double drag, boolean quadraticDrag) {
    /** Guess used for the ranging shot, before a cannon has fired and we've measured a real shell. */
    public static final Ballistics RANGING_GUESS = new Ballistics(4, -0.05, 0, false);

    // ponytail: low arc only, fixed bracket. Draggy shells reach max range below 40 degrees.
    private static final double MIN_ELEVATION = Math.toRadians(-30), MAX_ELEVATION = Math.toRadians(40);

    /** Elevation in radians that hits a point {@code dist} away horizontally and {@code dy} up, or null if out of range. */
    public Double solve(double dist, double dy) {
        double lo = MIN_ELEVATION, hi = MAX_ELEVATION;
        if (heightAt(hi, dist) < dy) return null;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (heightAt(mid, dist) < dy) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** Height of the shell when it has travelled {@code dist} horizontally. -infinity if it never gets there. */
    double heightAt(double elevation, double dist) {
        double vx = speed * Math.cos(elevation), vy = speed * Math.sin(elevation), x = 0, y = 0;
        for (int t = 0; t < 1200 && vx > 1e-4; t++) {
            if (x + vx >= dist) return y + vy * (dist - x) / vx;
            x += vx;
            y += vy;
            double v = Math.sqrt(vx * vx + vy * vy);
            double d = Math.min(drag * v * (quadraticDrag ? v : 1), v);
            vx -= vx / v * d;
            vy += -vy / v * d + gravity;
        }
        return Double.NEGATIVE_INFINITY;
    }
}
