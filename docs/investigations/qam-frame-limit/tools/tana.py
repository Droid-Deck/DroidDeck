# tana.py <trace.txt> [appid]: from the gamescope DD_FRAMETRACE log, the vblank slots between displayable game commits and frame_done -> next commit times.
import sys,collections,statistics as st
app=sys.argv[2] if len(sys.argv)>2 else "206420"
ev=[l.split() for l in open(sys.argv[1]) if len(l.split())>=2]
lastF=None; lastD=None; pend={}
lat=[]; cad=[]; firstV=None; vb=[]
for e in ev:
    t=int(e[0])/1e6; k=e[1]
    if k=='V': vb.append(t)
    if len(e)<3 or e[2]!=app: continue
    if k=='C': pend[e[3]]=t; 
    if k=='C' and lastF is not None: lat.append(t-lastF)
    if k=='F': lastF=t
    if k=='D':
        if lastD is not None: cad.append(t-lastD)
        lastD=t
ref=st.median([b-a for a,b in zip(vb,vb[1:])]) if len(vb)>2 else 16.67
slots=collections.Counter(round(c/ref) for c in cad)
print(f"vblank {ref:.2f}ms  game frames {len(cad)}  display slots {dict(sorted(slots.items()))}")
if lat:
    q=sorted(lat); print(f"F->next commit ms: median {st.median(q):.1f} p90 {q[int(len(q)*.9)]:.1f} max {q[-1]:.1f}  over 31ms: {sum(1 for x in q if x>31)}")
