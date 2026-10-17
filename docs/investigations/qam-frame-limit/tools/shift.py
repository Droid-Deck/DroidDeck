# shift.py <clip.mp4>: horizontal camera displacement per recorded frame during a pan. cv = step-size variation (judder), held = repeated frames.
import subprocess,sys,statistics as S,collections
v=sys.argv[1]; W,H=640,120
pts=[float(x.strip(",")) for x in subprocess.run(["ffprobe","-v","error","-select_streams","v:0","-show_entries","frame=pts_time","-of","csv=p=0",v],capture_output=True,text=True).stdout.split()]
raw=subprocess.run(["ffmpeg","-v","error","-i",v,"-fps_mode","passthrough","-vf",f"crop=iw*0.62:ih*0.3:iw*0.33:ih*0.12,scale={W}:{H},format=gray","-f","rawvideo","-"],capture_output=True).stdout
n=len(raw)//(W*H)
prof=[]
for i in range(n):
    f=raw[i*W*H:(i+1)*W*H]
    prof.append([sum(f[y*W+x] for y in range(0,H,2)) for x in range(W)])
def shift(a,b,R=60):
    best=None
    for s in range(-R,R+1):
        lo,hi=max(0,s),min(W,W+s)
        sad=sum(abs(a[x]-b[x-s]) for x in range(lo+R,hi-R))
        if best is None or sad<best[0]: best=(sad,s)
    return best[1]
rows=[]
for i in range(1,n):
    rows.append((pts[i],pts[i]-pts[i-1],shift(prof[i],prof[i-1])))
win=float(sys.argv[2]) if len(sys.argv)>2 else 2
b=collections.defaultdict(list)
for t,dt,s in rows: b[int(t//win)].append((dt,s))
for k in sorted(b):
    xs=[s for dt,s in b[k]]; mv=[abs(s) for s in xs if s!=0]
    zero=sum(1 for s in xs if s==0)
    if len(mv)<5: print(f"{k*win:5.1f}s n={len(xs)} static"); continue
    per_s=sum(abs(s) for s in xs)/win
    print(f"{k*win:5.1f}s n={len(xs):3d} held={zero:3d} speed={per_s:6.0f}px/s step mean={S.mean(mv):5.2f} cv={S.pstdev(mv)/S.mean(mv):.2f} hist={dict(sorted(collections.Counter(mv).items()))}")
