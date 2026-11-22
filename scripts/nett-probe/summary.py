#!/usr/bin/env python3
"""Design 127 step 0: one line per row of matrix.sh's output. Usage: summary.py FILE"""
import re, sys

def verdict(s):
    if s is None: return "-"
    oks = re.findall(r"(tcp|dns|http)=(ok|FAIL|\d|[0-9a-f:.]+)", s)
    return "/".join("ok" if not v.startswith("FAIL") else "x" for _, v in oks) or s[:40]

row = {}
def flush():
    if not row: return
    bind = row.get("cut.bind")
    b = "-" if bind is None else ("LEAK" if re.search(r"=(ok|bound=\w+ ok|\d{3})\(", bind) else "x")
    dm = row.get("cut.dm")
    d = "-" if dm is None else (re.search(r"status=(\d+)", dm).group(1) if "status=" in dm else dm[:30])
    print(f"{row['label']:<26} cut tcp/dns/https={verdict(row.get('cut.basic'))} nc={row.get('cut.nc','-').replace('exit=','')} bind={b} dm={d} | free={verdict(row.get('free.basic'))} | launcher={verdict(row.get('launcher'))}")
    row.clear()

for line in open(sys.argv[1]):
    line = line.rstrip("\n")
    if line.startswith("=== "):
        flush(); row["label"] = line[4:]
    elif line.startswith(("---", "vpn:", "phone")):
        flush(); print(line[:200])
    else:
        m = re.match(r"(cut\.basic|cut\.nc|cut\.bind|cut\.dm|free\.basic|launcher):\s*(.*)", line)
        if m: row[m.group(1)] = m.group(2)
flush()
