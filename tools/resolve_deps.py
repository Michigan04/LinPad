"""Resolve the dependency closure of Termux packages from a Packages index.

Usage: python resolve_deps.py <Packages-file> <pkg> [<pkg> ...]
Prints: name<TAB>version<TAB>size<TAB>filename for every package in the closure.
"""
import re
import sys


def parse(path):
    pkgs = {}
    with open(path, encoding="utf-8") as f:
        for block in f.read().split("\n\n"):
            fields = {}
            key = None
            for line in block.splitlines():
                if line[:1] in (" ", "\t") and key:
                    fields[key] += " " + line.strip()
                elif ":" in line:
                    key, _, val = line.partition(":")
                    fields[key] = val.strip()
            if "Package" in fields:
                pkgs[fields["Package"]] = fields
    return pkgs


def dep_names(field):
    names = []
    for part in field.split(","):
        first = part.split("|")[0].strip()
        name = re.sub(r"\s*\(.*\)", "", first).strip()
        if name:
            names.append(name)
    return names


def main():
    pkgs = parse(sys.argv[1])
    todo = list(sys.argv[2:])
    seen = []
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        if name not in pkgs:
            print(f"MISSING\t{name}", file=sys.stderr)
            continue
        seen.append(name)
        p = pkgs[name]
        for key in ("Depends", "Pre-Depends"):
            if key in p:
                todo.extend(dep_names(p[key]))
    total = 0
    for name in sorted(seen):
        p = pkgs[name]
        total += int(p["Size"])
        print(f"{name}\t{p['Version']}\t{p['Size']}\t{p['Filename']}")
    print(f"TOTAL\t{len(seen)} packages\t{total / 1e6:.1f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()
