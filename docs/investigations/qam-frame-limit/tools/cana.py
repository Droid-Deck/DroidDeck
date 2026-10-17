# cana.py <ctrace.txt>: from the compositor trace, ticks between new game-plane buffers and how close gamescope commits land to our vsync tick.
import sys,collections,statistics as st
ev=[l.split() for l in open(sys.argv[1])]
ticks=[]; pending=None; shown=[]; margins=[]; lat=[]; cb=[]
for e in ev:
    t=int(e[0])/1e6
    if e[1]=='T':
        ft=int(e[2])/1e6; ticks.append((ft,t)); cb.append(t-ft)
    elif e[1]=='S' and e[2]=='1':
        pending=t
    elif e[1]=='R' and pending is not None:
        rs=int(e[2])/1e6
        if rs>=pending:
            ft=ticks[-1][0] if ticks else rs
            shown.append(ft); lat.append(rs-pending)
            # margin: tick callback time minus commit arrival
            margins.append(ticks[-1][1]-pending if ticks else 0)
            pending=None
iv=[b-a for a,b in zip(shown,shown[1:])]
slots=collections.Counter(round(x/16.667) for x in iv)
print("game frames shown",len(shown),"tick slots",dict(sorted(slots.items())))
q=sorted(margins); print(f"commit->tick callback margin ms: min {q[0]:.2f} p5 {q[len(q)//20]:.2f} median {st.median(q):.2f}")
q=sorted(cb); print(f"choreographer callback delay after frame time: median {st.median(q):.2f} p95 {q[int(len(q)*.95)]:.2f} max {q[-1]:.2f}")
q=sorted(lat); print(f"commit->render start ms: median {st.median(q):.2f} p95 {q[int(len(q)*.95)]:.2f} max {q[-1]:.2f}")
