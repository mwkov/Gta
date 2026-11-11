"""Measures how well Spoiler Shield covered the GTA lines, frame by frame.

Input: log.txt with lines from the harness (H <ms> l,t,r,b;...), the shield (C <ms> covers P curtains,
Q poll, S scan, F finger-down) and markers (M name). For every harness frame we take the covers that
were on screen at that moment and check how much of each GTA line was visible.
"""
import sys, statistics, collections

def rects(s):
    out = []
    for p in s.split(';'):
        if p.count(',') == 3:
            out.append(tuple(int(float(x)) for x in p.split(',')))
    return out

def uncovered(r, cov, step=4):
    l, t, rr, b = r
    t, b = max(t, 0), min(b, 2337)   # above the gesture bar
    if b <= t or rr <= l: return 0.0, 0
    tot = vis = 0; vis_rows = 0
    for y in range(t, b, step):
        row_vis = False
        for x in range(l, rr, 8):
            tot += 1
            if not any(c[0] <= x < c[2] and c[1] <= y < c[3] for c in cov):
                vis += 1; row_vis = True
        if row_vis: vis_rows += 1
    return (vis / tot if tot else 0.0), vis_rows * step

def main(path):
    lines = open(path, errors='ignore').read().splitlines()
    sections = collections.OrderedDict(); screen = '?'; cur = None
    polls, scans, fingers = collections.defaultdict(list), collections.defaultdict(list), collections.Counter()
    listscans = collections.defaultdict(list)
    winscans = collections.defaultdict(lambda: collections.defaultdict(list))
    for ln in lines:
        p = ln.split(' ', 2)
        if not p or not p[0]: continue
        k = p[0]
        if k == 'M':
            name = ln[2:]
            if name.startswith('screen '): screen = name[7:]; continue
            cur = (screen, name); sections.setdefault(cur, {'H': [], 'C': []}); continue
        if cur is None or len(p) < 2: continue
        try: t = int(p[1])
        except ValueError: continue
        rest = p[2] if len(p) > 2 else ''
        if k == 'H': sections[cur]['H'].append((t, rects(rest)))
        elif k == 'C':
            a, _, b = rest.partition(' P ')
            sections[cur]['C'].append((t, rects(a), rects(b)))
        elif k == 'Q': polls[screen].append(int(rest.split()[0]))
        elif k == 'S':
            f = rest.split()
            scans[screen].append(int(f[0]))
            if len(f) > 5 and f[5] == 'list': listscans[screen].append(int(f[0]))
            elif len(f) > 6: winscans[screen][' '.join(f[6:])[:40]].append(int(f[0]))
        elif k == 'F': fingers[screen] += 1
    print('%-8s %-14s %7s %9s %10s %11s %10s %12s' % ('screen', 'gesture', 'frames', 'exposed%', 'worst px', 'cover err', 'err p95', 'longest ms'))
    totals = collections.defaultdict(lambda: [0, 0, 0, []])
    for (scr, name), d in sections.items():
        if name == 'end': continue
        C = d['C']; ci = -1; frames = exposed = worst = 0; errs = []
        run_start = None; longest = 0
        for (t, hs) in d['H']:
            frame_exposed = False
            while ci + 1 < len(C) and C[ci + 1][0] <= t: ci += 1
            covs = C[ci][1] if ci >= 0 else []
            curt = C[ci][2] if ci >= 0 else []
            for r in hs:
                if r[3] < 0 or r[1] > 2337: continue
                frames += 1
                frac, rows = uncovered(r, covs + curt)
                if frac > 0.03:
                    exposed += 1; worst = max(worst, rows); frame_exposed = True
                best = None
                for c in covs:
                    ov = min(c[3], r[3]) - max(c[1], r[1])
                    if ov > 0 and (best is None or ov > best[0]): best = (ov, c)
                if best:
                    c = best[1]
                    # where the cover sits vs the text: compare centres (stretching widens covers)
                    errs.append(abs((c[1] + c[3]) / 2 - (r[1] + r[3]) / 2))
            # longest stretch of time with some GTA text showing
            if frame_exposed:
                if run_start is None: run_start = t
                longest = max(longest, t - run_start)
            else:
                run_start = None
        e = statistics.mean(errs) if errs else 0
        p95 = sorted(errs)[int(len(errs) * .95)] if errs else 0
        print('%-8s %-14s %7d %8.1f%% %9d %10.1f %10.1f %12d' % (scr, name, frames, 100 * exposed / max(1, frames), worst, e, p95, longest))
        T = totals[scr]; T[0] += frames; T[1] += exposed; T[2] = max(T[2], worst); T[3] += errs
        T.append(longest)
    print()
    for scr, T in totals.items():
        e = statistics.mean(T[3]) if T[3] else 0
        print('%-8s TOTAL: GTA text visible on %.1f%% of frames, worst %d px tall, avg cover offset %.1f px, longest exposure %d ms'
              % (scr, 100 * T[1] / max(1, T[0]), T[2], e, max(T[4:] or [0])))
    for scr in totals:
        q = polls[scr]; s = scans[scr]
        print('%-8s re-reads: %d, median %s ms, p90 %s ms | full reads: %d, median %s ms, max %s ms | finger-downs seen: %d'
              % (scr, len(q), statistics.median(q) if q else '-', sorted(q)[int(len(q) * .9)] if q else '-',
                 len(s), statistics.median(s) if s else '-', max(s) if s else '-', fingers[scr]))
        l = listscans[scr]
        if l: print('%-8s list-only reads: %d, median %s ms, max %s ms' % (scr, len(l), statistics.median(l), max(l)))
        for lab, v in sorted(winscans[scr].items(), key=lambda kv: -len(kv[1])):
            print('%-8s   window %-40s reads %4d, median %6s ms, max %6d ms' % (scr, lab, len(v), statistics.median(v), max(v)))

if __name__ == '__main__':
    main(sys.argv[1])
