import sys
import time

count = int(sys.argv[1])
paths = ["/sys/class/thermal/thermal_zone%d/temp" % n for n in (22, 26, 28)]
start = time.perf_counter()
for _ in range(count):
    for p in paths:
        with open(p) as f:
            f.read()
elapsed = time.perf_counter() - start
print(f"{count * len(paths)} opens {elapsed:.3f}s {elapsed / (count * len(paths)) * 1e6:.0f}us each")
