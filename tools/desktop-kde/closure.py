#!/usr/bin/env python3
"""The packages the KDE desktop package ships: the dependency closure of the seeds over Arch Linux
ARM's extracted repository databases, minus what the runtime already has at a version that serves.

    closure.py <db dir with x_core, x_extra, x_alarm> <runtime-packages.txt> <seeds.txt>

Prints one "repo/filename sha256" per package to ship (the sha256 the repository database gives).
"""
import collections
import os
import re
import sys

REPOS = ("core", "extra", "alarm")


def vercmp_part(a, b):
    # rpmvercmp, as pacman compares version strings.
    if a == b:
        return 0
    sa = re.findall(r"\d+|[a-zA-Z]+", a)
    sb = re.findall(r"\d+|[a-zA-Z]+", b)
    for x, y in zip(sa, sb):
        if x.isdigit() and y.isdigit():
            x, y = int(x), int(y)
            if x != y:
                return 1 if x > y else -1
        elif x.isdigit():
            return 1
        elif y.isdigit():
            return -1
        elif x != y:
            return 1 if x > y else -1
    if len(sa) == len(sb):
        return 0
    return 1 if len(sa) > len(sb) else -1


def vercmp(a, b):
    def split(v):
        epoch, _, rest = v.rpartition(":") if ":" in v else ("0", "", v)
        ver, _, rel = rest.partition("-")
        return int(epoch or 0), ver, rel
    ea, va, ra = split(a)
    eb, vb, rb = split(b)
    if ea != eb:
        return 1 if ea > eb else -1
    c = vercmp_part(va, vb)
    if c or not ra or not rb:
        return c
    return vercmp_part(ra, rb)


def parse_dep(d):
    m = re.match(r"^([^<>=]+)(>=|<=|=|>|<)?(.*)$", d)
    return m.group(1), m.group(2), m.group(3)


def satisfies(version, op, want):
    if not op:
        return True
    c = vercmp(version, want)
    return {"=": c == 0, ">=": c >= 0, "<=": c <= 0, ">": c > 0, "<": c < 0}[op]


def main():
    dbdir, runtime_file, seeds_file = sys.argv[1:4]
    runtime = {}
    for line in open(runtime_file):
        parts = line.split()
        if len(parts) == 2:
            runtime[parts[0]] = parts[1]
    seeds = [l.strip() for l in open(seeds_file) if l.strip() and not l.lstrip().startswith("#")]

    pkgs, providers = {}, collections.defaultdict(list)
    for repo in REPOS:
        root = os.path.join(dbdir, "x_" + repo)
        for entry in sorted(os.listdir(root)):
            fields, key = {}, None
            for fname in ("desc", "depends"):
                path = os.path.join(root, entry, fname)
                if not os.path.exists(path):
                    continue
                for line in open(path, encoding="utf-8", errors="replace"):
                    line = line.rstrip("\n")
                    if line.startswith("%") and line.endswith("%"):
                        key = line.strip("%")
                        fields[key] = []
                    elif line == "":
                        key = None
                    elif key:
                        fields[key].append(line)
            name = fields.get("NAME", [None])[0]
            if not name or name in pkgs:
                continue
            provides = [parse_dep(p) for p in fields.get("PROVIDES", [])]
            pkgs[name] = {"repo": repo, "file": fields["FILENAME"][0], "version": fields["VERSION"][0],
                          "sha256": fields.get("SHA256SUM", [""])[0],
                          "depends": fields.get("DEPENDS", []), "provides": provides}
            providers[name].append((name, pkgs[name]["version"]))
            for pname, _, pver in provides:
                providers[pname].append((name, pver or pkgs[name]["version"]))

    def resolve(dep):
        """The package to take for a dependency string, and whether the runtime's copy serves."""
        name, op, want = parse_dep(dep)
        # A runtime package that is the dependency or provides it, at a version that serves.
        if name in runtime and satisfies(runtime[name], op, want):
            return name, True
        candidates = providers.get(name, [])
        for real, _ in candidates:
            if real in runtime:
                # A provided name (a soname, mostly): the runtime's build serves as long as it is
                # the repository's own, or nothing asks for a particular version.
                if not op or runtime[real] == pkgs[real]["version"]:
                    return real, True
                if satisfies_provider(real, name, op, want):
                    return real, False
        for real, pver in candidates:
            if satisfies(pver, op, want):
                return real, False
        return None, False

    def satisfies_provider(real, name, op, want):
        if real == name:
            return satisfies(pkgs[real]["version"], op, want)
        return any(p == name and satisfies(v or pkgs[real]["version"], op, want) for p, _, v in pkgs[real]["provides"])

    ship, seen, missing = [], set(), []
    queue = list(seeds)
    while queue:
        dep = queue.pop()
        real, served = resolve(dep)
        if real is None:
            missing.append(dep)
            continue
        if served or real in seen:
            continue
        seen.add(real)
        ship.append(real)
        queue.extend(pkgs[real]["depends"])
    if missing:
        sys.exit("unresolved: " + " ".join(sorted(set(missing))))
    upgraded = sorted(n for n in ship if n in runtime)
    if upgraded:
        print("replacing runtime packages: " + " ".join(f"{n} {runtime[n]} -> {pkgs[n]['version']}" for n in upgraded),
              file=sys.stderr)
    for name in sorted(ship):
        print(pkgs[name]["repo"] + "/" + pkgs[name]["file"], pkgs[name]["sha256"])


if __name__ == "__main__":
    main()
