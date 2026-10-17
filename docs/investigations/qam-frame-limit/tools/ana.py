# ana.py <out.json> [base-hh:mm:ss] [threshold] [window-s]: per window, how often the picture changed and the spacing of changes in 60 Hz slots.
import json,sys,collections,statistics as S
d=json.load(open(sys.argv[1])); base=sys.argv[2] if len(sys.argv)>2 else None
th=float(sys.argv[3]) if len(sys.argv)>3 else 0.6
win=float(sys.argv[4]) if len(sys.argv)>4 else 2.0
def clk(t):
    if not base: return f"{t:6.1f}"
    h,m,s=map(int,base.split(":")); x=h*3600+m*60+s+t
    return "%02d:%02d:%02d"%(x//3600,x//60%60,x%60)
ch=[(t,v) for t,v in d[1:] if v>th]
buckets=collections.defaultdict(list)
for i in range(1,len(ch)):
    buckets[int(ch[i][0]//win)].append((ch[i][0]-ch[i-1][0])*1000)
allf=collections.Counter(int(t//win) for t,_ in d)
for b in sorted(buckets):
    iv=buckets[b]; hist=collections.Counter(min(5,round(x/16.67)) for x in iv)
    mags=[v for t,v in d if int(t//win)==b and v>th]
    print(f"{clk(b*win)} frames={allf[b]:3d} changes={len(iv):3d} ({len(iv)/win:4.1f}/s) iv(ms) mean={S.mean(iv):5.1f} sd={S.pstdev(iv):5.1f}  slots 1:{hist[1]:3d} 2:{hist[2]:3d} 3:{hist[3]:3d} 4+:{hist[4]+hist[5]:3d}  motionCV={S.pstdev(mags)/max(1e-9,S.mean(mags)):.2f}")
