"""Download a Termux package closure and unpack it into a staging prefix.

Usage: python fetch_termux.py <arch> <out-dir> <pkg> [<pkg> ...]

Reads downloads/termux/Packages-<arch>, resolves dependencies, downloads every
.deb (cached), and extracts each data.tar.* so that <out-dir>/usr mirrors
/data/data/com.termux/files/usr. Symlinks are recorded in <out-dir>/symlinks.txt
(link<TAB>target) instead of being created, because the staging disk is exFAT.
"""
import io
import lzma
import os
import sys
import tarfile
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from resolve_deps import dep_names, parse  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "https://packages.termux.dev/apt/termux-main/"
PREFIX = "data/data/com.termux/files/"


def closure(pkgs, wanted):
    todo, seen = list(wanted), []
    while todo:
        name = todo.pop()
        if name in seen or name not in pkgs:
            continue
        seen.append(name)
        for key in ("Depends", "Pre-Depends"):
            if key in pkgs[name]:
                todo.extend(dep_names(pkgs[name][key]))
    return sorted(seen)


def ar_members(data):
    assert data[:8] == b"!<arch>\n", "not an ar archive"
    pos = 8
    while pos < len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        pos += 60
        yield name, data[pos:pos + size]
        pos += size + (size & 1)


def open_data_tar(name, blob):
    if name.endswith(".xz"):
        blob = lzma.decompress(blob)
        return tarfile.open(fileobj=io.BytesIO(blob), mode="r:")
    if name.endswith(".zst"):
        import zstandard  # only needed if the repo uses zstd

        blob = zstandard.ZstdDecompressor().decompress(blob, max_output_size=1 << 31)
        return tarfile.open(fileobj=io.BytesIO(blob), mode="r:")
    return tarfile.open(fileobj=io.BytesIO(blob), mode="r:*")


def main():
    arch, out = sys.argv[1], os.path.abspath(sys.argv[2])
    pkgs = parse(os.path.join(ROOT, "downloads", "termux", f"Packages-{arch}"))
    cache = os.path.join(ROOT, "downloads", "termux", "debs")
    os.makedirs(cache, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    links = []
    names = closure(pkgs, sys.argv[3:])
    for i, name in enumerate(names, 1):
        rel = pkgs[name]["Filename"]
        local = os.path.join(cache, os.path.basename(rel).replace(":", "_"))
        if not os.path.exists(local) or os.path.getsize(local) != int(pkgs[name]["Size"]):
            url = REPO + urllib.request.quote(rel)
            req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
            with urllib.request.urlopen(req) as r, open(local, "wb") as f:
                f.write(r.read())
        with open(local, "rb") as f:
            data = f.read()
        for member, blob in ar_members(data):
            if not member.startswith("data.tar"):
                continue
            with open_data_tar(member, blob) as tar:
                for m in tar:
                    path = m.name.lstrip("./")
                    if not path.startswith(PREFIX):
                        continue
                    relpath = path[len(PREFIX):]
                    dest = os.path.join(out, *relpath.split("/"))
                    if m.isdir():
                        os.makedirs(dest, exist_ok=True)
                    elif m.issym() or m.islnk():
                        links.append((relpath, m.linkname))
                    elif m.isfile():
                        os.makedirs(os.path.dirname(dest), exist_ok=True)
                        with open(dest, "wb") as f:
                            f.write(tar.extractfile(m).read())
        print(f"[{i}/{len(names)}] {name}", flush=True)
    with open(os.path.join(out, "symlinks.txt"), "w", encoding="utf-8", newline="\n") as f:
        for link, target in links:
            f.write(f"{link}\t{target}\n")
    print(f"done: {len(names)} packages, {len(links)} symlinks recorded")


if __name__ == "__main__":
    main()
