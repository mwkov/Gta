package dev.mwkov.spoilershield;

/**
 * One axis of scroll tracking. Measurements of where the content really is go in;
 * a smooth per-frame movement for the covers comes out. Pure Java, so it is unit-tested
 * on a PC against simulated drags and flings.
 *
 * Why it is built this way:
 *  - Android batches scroll events: at most one every 100 ms, carrying the movement
 *    added up since the previous one. Between batches we must predict.
 *  - Flings slow down, so prediction models deceleration and never reverses direction.
 *  - When a measurement shows the prediction was off, the difference is blended in over a
 *    few frames instead of applied in one jump.
 *  - Measurements are stamped with when they happened, not when they arrived.
 */
final class TrackMath {

    /** Share of the remaining error removed per frame. */
    static final float CORRECTION = 0.35f;

    private float truth;        // measured content position (cumulative px)
    private long truthT;        // when that was true (uptime ms)
    private boolean hasTruth;
    private float v;            // px per ms at truthT
    private float a;            // px per ms^2, only ever slowing v towards 0
    private boolean hasV;
    private float shown;        // where the covers have been moved to
    private long lastFrameT;
    private boolean hasLastFrame;
    private float aheadMs = 120f;

    // previous velocity sample, for the deceleration estimate
    private float vPrev;
    private long vPrevT;
    private boolean hasVPrev;

    /** Start fresh at the covers' current position. */
    void reset() {
        truth = shown;
        hasTruth = false;
        hasV = false;
        hasVPrev = false;
        v = a = 0;
        hasLastFrame = false;
    }

    float shown() {
        return shown;
    }

    float velocity() {
        return hasV ? v : 0;
    }

    /** Covers minus where they should be right now (signed). */
    float error(long now) {
        return shown - target(now);
    }

    /**
     * A batched movement: content moved by `delta` during the `windowMs` before `at`.
     * The average speed over the window is the speed at its middle.
     */
    void addDelta(float delta, long at, float windowMs) {
        if (!hasTruth) {
            truth = shown;
            hasTruth = true;
        }
        truth += delta;
        truthT = at;
        aheadMs = 120f;
        float avgV = delta / Math.max(1f, windowMs);
        updateVelocity(avgV, at - (long) (windowMs / 2), at);
    }

    /** An exact position measured at time `at` (from re-reading one covered item). */
    void setPosition(float pos, long at) {
        if (hasTruth && at > truthT) {
            float dt = at - truthT;
            if (dt >= 6) updateVelocity((pos - truth) / dt, at - (long) (dt / 2), at);
        } else if (!hasTruth) {
            hasV = false;
        }
        truth = pos;
        truthT = at;
        hasTruth = true;
        aheadMs = 40f;
    }

    private void updateVelocity(float sample, long sampleT, long now) {
        if (hasVPrev && sampleT > vPrevT && Math.signum(sample) == Math.signum(vPrev)
                && Math.abs(sample) < Math.abs(vPrev)) {
            float na = (sample - vPrev) / (sampleT - vPrevT);   // opposite sign to v: slowing
            a = hasV ? 0.5f * a + 0.5f * na : na;
        } else {
            a = 0;
        }
        vPrev = sample;
        vPrevT = sampleT;
        hasVPrev = true;
        // carry the mid-window speed forward to `now`
        float cur = sample + a * (now - sampleT);
        if (Math.signum(cur) != Math.signum(sample)) cur = 0;
        v = cur;
        hasV = true;
    }

    /**
     * Content has stopped. No new measurement since the last one means it stopped there,
     * so the covers settle on the last measured position (not the prediction past it).
     */
    void stop(long now) {
        v = a = 0;
        hasV = false;
        hasVPrev = false;
    }

    /** Where the content should be at `now`, predicting at most `aheadMs` past the last fact. */
    float target(long now) {
        if (!hasTruth) return shown;
        if (!hasV) return truth;
        float dt = Math.min(Math.max(0, now - truthT), aheadMs);
        return truth + travel(v, a, dt);
    }

    static float travel(float v, float a, float dt) {
        if (a == 0 || v == 0) return v * dt;
        float tStop = -v / a;                    // a opposes v, so this is positive
        if (tStop <= 0) return 0;
        float d = Math.min(dt, tStop);
        return v * d + 0.5f * a * d * d;
    }

    /** Called once per display frame. Returns how far to move the covers now. */
    float frame(long now) {
        float target = target(now);
        float move;
        if (!hasLastFrame) {
            move = CORRECTION * (target - shown);
        } else {
            // Follow the motion the CURRENT model predicts between the last frame and now,
            // and remove part of the leftover error. A new measurement changes the model, so
            // its surprise lands in the leftover error and is blended in over a few frames.
            float before = target(lastFrameT);
            move = (target - before) + CORRECTION * (before - shown);
        }
        lastFrameT = now;
        hasLastFrame = true;
        if (Math.abs(target - (shown + move)) < 0.3f) move = target - shown;
        shown += move;
        return move;
    }

    /** Still needs frames: moving, or covers not yet where they belong. */
    boolean busy(long now) {
        return (hasV && v != 0) || Math.abs(target(now) - shown) >= 0.5f;
    }
}
