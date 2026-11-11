package dev.mwkov.spoilershield;

import java.util.*;

/** Simulated scrolling used by TrackMathTest: batched events, delayed re-reads. */
class Sim {
    // ---- the content's true motion (px; negative = moving up), time in ms
    static double content(double t) { return pos[(int) Math.min(pos.length - 1, Math.max(0, Math.round(t * 10)))]; }
    static double[] pos;
    static void buildProfile(int kind, Random r) {
        int n = 40000; pos = new double[n]; double p = 0, v = 0;
        for (int i = 1; i < n; i++) {
            double t = i / 10.0;
            if (kind == 0) {                       // drag then fling then slow drag then hard stop
                if (t < 100) v = 0;
                else if (t < 450) v = -1.5 - 0.5 * Math.sin(t / 60);
                else if (t < 2200) { double f = (t - 450); v = -3.2 * Math.exp(-f / 380.0); if (Math.abs(v) < 0.05) v = 0; }
                else if (t < 2500) v = 0;
                else if (t < 3200) v = -0.35;
                else v = 0;
            } else {                               // back-and-forth reading scroll
                if (t < 100) v = 0;
                else if (t < 900) v = -0.9;
                else if (t < 1100) v = 0;
                else if (t < 1700) v = 0.7;
                else if (t < 1750) v = 0;
                else if (t < 2600) v = -2.0 * Math.exp(-(t - 1750) / 300.0);
                else v = 0;
            }
            p += v * 0.1; pos[i] = p;
        }
    }
    // app renders at 60 fps: the scroll position the app reports changes only on its frames
    static double appPos(double t) { return content(Math.floor(t / 16.667) * 16.667); }

    static class Ev { double sent, arrive, delta, window; }
    static List<Ev> events(Random r) {
        List<Ev> out = new ArrayList<>(); double pending = -1, acc = 0, lastSent = -1, prev = appPos(0);
        for (double f = 16.667; f < 3990; f += 16.667) {
            double cur = appPos(f), d = cur - prev; prev = cur;
            if (d != 0) { acc += d; if (pending < 0) pending = f + 100; }
            if (pending >= 0 && f >= pending) {
                Ev e = new Ev(); e.sent = f; e.delta = acc; acc = 0;
                e.window = lastSent < 0 || f - lastSent > 300 ? 100 : f - lastSent; lastSent = f;
                double j = 2 + r.nextDouble() * 22; if (r.nextDouble() < 0.1) j += 40;   // delivery delay
                e.arrive = f + j; out.add(e); pending = -1;
            }
        }
        out.sort(Comparator.comparingDouble(x -> x.arrive));
        return out;
    }

    interface Algo { void event(Ev e, double now); double frame(double now); }

    static class Old implements Algo {      // the v2.x-v3.x tracking, reimplemented
        double v, pred, lastArr = -1e9, shown; boolean active;
        public void event(Ev e, double now) {
            double dt = now - lastArr; if (dt < 16 || dt > 300) dt = 100;
            shown += e.delta - pred; pred = 0;
            double nv = e.delta / dt; v = active ? 0.5 * v + 0.5 * nv : nv; lastArr = now; active = true;
        }
        double lastF = -1;
        public double frame(double now) {
            double dt = lastF < 0 ? 0 : Math.min(34, now - lastF); lastF = now;
            if (active && now - lastArr > 120) { active = false; v = 0; }
            if (active) { double s = v * dt; pred += s; shown += s; }
            return shown;
        }
    }

    static class NewEvents implements Algo {
        TrackMath t = new TrackMath(); double lastArr = -1e9; boolean moving;
        public void event(Ev e, double now) { t.addDelta((float) e.delta, Math.round(e.sent), (float) e.window); lastArr = now; moving = true; }
        public double frame(double now) {
            if (moving && now - lastArr > 220) { t.stop(Math.round(now)); moving = false; }
            t.frame(Math.round(now)); return t.shown();
        }
    }

    static class NewPolls implements Algo {   // events + re-reading one item; also checks every 50 ms when idle
        TrackMath t = new TrackMath(); Random r; double lastArr = -1e9, lastPollLive = -1e9;
        double busyUntil = -1, measAt, measPos, sentAt, nextIdle = 0; boolean inFlight, moving; double lastMovePoll = -1e9, lastPollPos = Double.NaN;
        NewPolls(Random r) { this.r = r; }
        public void event(Ev e, double now) {
            lastArr = now; moving = true;
            if (now - lastPollLive > 150) t.addDelta((float) e.delta, Math.round(e.sent), (float) e.window);
        }
        public double frame(double now) {
            if (inFlight && now >= busyUntil) {
                inFlight = false; lastPollLive = now;
                long at = Math.round((sentAt + now) / 2) - 6;
                if (!Double.isNaN(lastPollPos) && Math.abs(measPos - lastPollPos) >= 0.5) { lastMovePoll = now; moving = true; }
                lastPollPos = measPos;
                t.setPosition((float) measPos, at);
            }
            if (!inFlight && (moving || now >= nextIdle)) {
                inFlight = true; sentAt = now; nextIdle = now + 50;
                double lat = 3 + r.nextDouble() * 17; measAt = now + lat * 0.6; measPos = Math.round(appPos(measAt));
                busyUntil = now + lat;
            }
            boolean pollsStill = now - lastPollLive < 100 && now - lastMovePoll > 90;
            boolean eventsStill = now - lastArr > 220 && now - lastPollLive > 100;
            if (moving && (pollsStill || eventsStill)) { t.stop(Math.round(now)); moving = false; }
            t.frame(Math.round(now)); return t.shown();
        }
    }

    static double[] run(Algo a, List<Ev> evs) {
        int ei = 0; double sumSq = 0, max = 0, maxJerk = 0, prevShown = 0, prevContent = 0; int n = 0;
        for (double f = 3; f < 3990; f += 16.667) {
            while (ei < evs.size() && evs.get(ei).arrive <= f) { a.event(evs.get(ei), evs.get(ei).arrive); ei++; }
            double s = a.frame(f), c = appPos(f), err = s - c;
            sumSq += err * err; n++; max = Math.max(max, Math.abs(err));
            maxJerk = Math.max(maxJerk, Math.abs((s - prevShown) - (c - prevContent)));
            prevShown = s; prevContent = c;
        }
        return new double[]{Math.sqrt(sumSq / n), max, maxJerk};
    }

}
