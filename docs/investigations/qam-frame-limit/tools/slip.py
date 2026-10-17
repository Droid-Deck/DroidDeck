# slip.py <trace.txt>: for each 3+ slot gap in the gamescope trace, whether a game commit was waiting at the vblanks it skipped.
import sys
app="206420"; ev=[l.split() for l in open(sys.argv[1])]
pend={}; lastD=None; lastF=None; out=[]; vbs=[]
for e in ev:
    t=int(e[0])/1e6; k=e[1]
    if k=='V': vbs.append((t,len(pend),lastD,lastF)); continue
    if len(e)<3 or e[2]!=app: continue
    if k=='C': pend[e[3]]=t
    elif k=='F': lastF=t
    elif k=='D':
        if lastD is not None and t-lastD>40:
            # vblanks between: show pending count & time since last D/F
            mids=[(round(v-lastD,1),p,round(v-(f or 0),1)) for v,p,d,f in vbs if lastD+5<v<t-1]
            out.append((round(t-lastD,1),mids))
        pend.pop(e[3],None); lastD=t
for o in out[:15]: print(o)
print(len(out),"slips")
