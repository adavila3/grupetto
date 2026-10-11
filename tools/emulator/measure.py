"""Measure the Grupetto overlay on a device: frame rate, CPU per thread, and a 5 s frame trace.

usage: python measure.py <adb-serial> <label> [seconds]
Uses $ADB if set, otherwise `adb` on PATH. Read-only apart from resetting gfxinfo counters.
"""
import os
import re
import subprocess
import sys
import time
from collections import defaultdict

ADB = os.environ.get("ADB", "adb")
SERIAL, LABEL = sys.argv[1], sys.argv[2]
SECS = int(sys.argv[3]) if len(sys.argv) > 3 else 20
PKG = "com.spop.poverlay"
HZ = 100  # CLK_TCK


def sh(*args, timeout=90):
    return subprocess.run([ADB, "-s", SERIAL, "shell", *args], capture_output=True, text=True,
                          timeout=timeout, encoding="utf-8", errors="replace").stdout


def threads(pid):
    out = sh(f"for t in /proc/{pid}/task/*; do cat $t/stat; done")
    result = {}
    for line in out.splitlines():
        m = re.match(r"(\d+) \((.*?)\) (.*)", line)
        if m:
            f = m.group(3).split()
            result[m.group(1)] = (m.group(2), int(f[11]) + int(f[12]))
    return result


pid = sh("pidof", PKG).strip()
if not pid:
    sys.exit(f"{PKG} is not running on {SERIAL}")
sh("dumpsys", "gfxinfo", PKG, "reset")
before, t0 = threads(pid), time.time()
time.sleep(SECS)
after, dt = threads(pid), time.time() - t0
gfx = sh("dumpsys", "gfxinfo", PKG)
frames = int(re.search(r"Total frames rendered: (\d+)", gfx).group(1))
janky = re.search(r"Janky frames: \d+ \(([\d.]+)%\)", gfx)

by_name = defaultdict(int)
for tid, (name, jiffies) in after.items():
    if tid in before:
        by_name[re.sub(r"[-#:]?\d+_?\d*$", "", name)] += jiffies - before[tid][1]
total = sum(by_name.values())
print(f"[{LABEL}] {dt:.0f}s  frames {frames} ({frames / dt:.1f}/s)  janky {janky.group(1) if janky else '?'}%  "
      f"process CPU {100 * total / HZ / dt:.1f}% of one core")
for name, jiffies in sorted(by_name.items(), key=lambda x: -x[1])[:6]:
    print(f"    {name:20s} {100 * jiffies / HZ / dt:5.1f}%")

trace = sh("atrace", "-t", "5", "-b", "16384", "-a", PKG, "view", "gfx", timeout=60)
stack, busy, counts = [], defaultdict(float), defaultdict(int)
first = last = None
for line in trace.splitlines():
    if f"-{pid} " not in line or "tracing_mark_write" not in line:
        continue
    ts = float(re.search(r"(\d+\.\d+):", line).group(1))
    first = first or ts
    last = ts
    begin = re.search(r"B\|\d+\|(.+)$", line)
    if begin:
        name = begin.group(1).strip()
        stack.append((name, ts))
        counts[name] += 1
    elif stack:
        name, start = stack.pop()
        if name == "Choreographer#doFrame":
            busy["doFrame"] += ts - start
        elif len(stack) == 1 and stack[0][0] == "Choreographer#doFrame":
            busy[name] += ts - start
d = (last - first) if first else 1
print(f"    trace: doFrame {counts['Choreographer#doFrame'] / d:.0f}/s, draw {counts['draw'] / d:.0f}/s, "
      f"measure {counts['measure'] / d:.0f}/s; UI thread busy "
      + ", ".join(f"{k} {100 * v / d:.1f}%" for k, v in sorted(busy.items(), key=lambda x: -x[1])))
