package dev.mwkov.spoilershield;

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.Test;

public class TrackMathTest {

    /** avg error, worst error, worst per-frame jump; averaged/maxed over many random runs. */
    private static double[] measure(int kind, int which) {
        double[] tot = new double[3];
        int seeds = 100;
        for (int seed = 0; seed < seeds; seed++) {
            Random r = new Random(seed * 31 + kind);
            Sim.buildProfile(kind, r);
            List<Sim.Ev> evs = Sim.events(r);
            Sim.Algo a = which == 0 ? new Sim.Old() : which == 1 ? new Sim.NewEvents()
                    : which == 2 ? new Sim.NewPolls(new Random(seed)) : new Sim.Touch(new Random(seed));
            double[] m = Sim.run(a, evs);
            tot[0] += m[0] / seeds;
            tot[1] = Math.max(tot[1], m[1]);
            tot[2] = Math.max(tot[2], m[2]);
        }
        return tot;
    }

    @Test
    public void reReadsTrackFarBetterThanOldGlide() {
        for (int kind = 0; kind < 2; kind++) {
            double[] old = measure(kind, 0), now = measure(kind, 2);
            assertTrue("avg error " + now[0] + " vs old " + old[0], now[0] < old[0] * 0.5);
            assertTrue("worst jump " + now[2], now[2] < 60);
        }
    }

    @Test
    public void fingerDownTrackingBeatsIdleChecks() {
        for (int kind = 0; kind < 2; kind++) {
            double[] idle = measure(kind, 2), touch = measure(kind, 3);
            assertTrue("avg " + touch[0] + " vs " + idle[0], touch[0] < idle[0] * 0.8);
            assertTrue("worst " + touch[1] + " vs " + idle[1], touch[1] < idle[1]);
            assertTrue("worst jump " + touch[2], touch[2] < 45);
        }
    }

    @Test
    public void eventsOnlyHasNoBigJumps() {
        for (int kind = 0; kind < 2; kind++) {
            double[] old = measure(kind, 0), now = measure(kind, 1);
            assertTrue("worst jump " + now[2] + " vs old " + old[2], now[2] < old[2] * 0.6);
        }
    }

    @Test
    public void flingPredictionSlowsDownAndNeverReverses() {
        TrackMath t = new TrackMath();
        // content moving up, slowing: 300 px then 200 px per 100 ms
        t.addDelta(-300, 100, 100);
        t.addDelta(-200, 200, 100);
        float p1 = t.target(260), p2 = t.target(320), p3 = t.target(2000);
        assertTrue(p2 <= p1);              // still moving the same way
        assertTrue(p3 >= -800);            // prediction capped, no runaway
    }

    @Test
    public void correctionsAreBlendedNotSnapped() {
        TrackMath t = new TrackMath();
        t.setPosition(0, 0);
        for (long f = 0; f < 100; f += 16) t.frame(f);
        t.setPosition(100, 100);           // surprise: content is 100 px away
        float first = t.frame(116);
        assertTrue("first frame moved " + first, first < 60);
        for (long f = 132; f < 400; f += 16) {
            if (f % 48 == 4) t.setPosition(100, f);   // still screen: same reading again
            t.frame(f);
        }
        assertTrue("settled at " + t.shown(), Math.abs(t.shown() - 100) < 1);
    }

    @Test
    public void stopSettlesOnLastMeasuredPositionNotPrediction() {
        TrackMath t = new TrackMath();
        t.addDelta(-150, 100, 100);
        t.addDelta(-150, 200, 100);
        for (long f = 200; f < 330; f += 16) t.frame(f);   // prediction ran ahead
        t.stop(330);
        for (long f = 330; f < 700; f += 16) t.frame(f);
        assertTrue("ended at " + t.shown(), Math.abs(t.shown() + 300) < 1);
    }

    @Test(timeout = 2000)
    public void badPatternGivesUpInsteadOfHanging() {
        // takes ~8 s on 30 characters without the limit
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(.*a){12}b");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append('a');
        boolean timedOut = false;
        try {
            p.matcher(new TimedText(sb, 20)).find();
        } catch (TimedText.TimeUp e) {
            timedOut = true;
        }
        assertTrue(timedOut);
    }
}
