# vdiff.py <clip.mp4> <ffmpeg crop> <out.json>: mean absolute luma change between consecutive screen-recording frames, with timestamps.
import subprocess,sys,json
v=sys.argv[1]; crop=sys.argv[2] if len(sys.argv)>2 else "iw*0.6:ih*0.5:iw*0.3:ih*0.2"
W,H=96,54
pts=[float(x.strip(",")) for x in subprocess.run(["ffprobe","-v","error","-select_streams","v:0","-show_entries","frame=pts_time","-of","csv=p=0",v],capture_output=True,text=True).stdout.split()]
raw=subprocess.run(["ffmpeg","-v","error","-i",v,"-fps_mode","passthrough","-vf",f"crop={crop},scale={W}:{H},format=gray","-f","rawvideo","-"],capture_output=True).stdout
n=len(raw)//(W*H); prev=None; out=[]
for i in range(n):
    f=raw[i*W*H:(i+1)*W*H]
    d=0 if prev is None else sum(abs(a-b) for a,b in zip(f,prev))/(W*H)
    out.append((pts[i] if i<len(pts) else None,d)); prev=f
json.dump(out,open(sys.argv[3] if len(sys.argv)>3 else v+".diff.json","w"))
print(n,len(pts))
