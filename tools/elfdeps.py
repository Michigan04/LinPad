"""Minimal ELF64 dynamic-section reader and shared-library closure resolver."""
import os
import struct

DT_NEEDED, DT_STRTAB, DT_SONAME, DT_RUNPATH = 1, 5, 14, 29
PT_LOAD, PT_DYNAMIC = 1, 2


def read_dynamic(path):
    """Return {'needed': [...], 'soname': str|None, 'runpath': str|None} or None if not ELF64."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    loads, dyn = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        p_offset, p_vaddr, _, p_filesz = struct.unpack_from("<QQQQ", data, off + 8)
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == PT_DYNAMIC:
            dyn = (p_offset, p_filesz)
    info = {"needed": [], "soname": None, "runpath": None}
    if not dyn:
        return info

    def v2o(vaddr):
        for va, off, size in loads:
            if va <= vaddr < va + size:
                return vaddr - va + off
        raise ValueError("vaddr not mapped")

    entries = []
    strtab = None
    for i in range(dyn[1] // 16):
        tag, val = struct.unpack_from("<qQ", data, dyn[0] + i * 16)
        if tag == 0:
            break
        if tag == DT_STRTAB:
            strtab = v2o(val)
        entries.append((tag, val))

    def s(offset):
        end = data.index(b"\0", strtab + offset)
        return data[strtab + offset:end].decode()

    for tag, val in entries:
        if tag == DT_NEEDED:
            info["needed"].append(s(val))
        elif tag == DT_SONAME:
            info["soname"] = s(val)
        elif tag == DT_RUNPATH:
            info["runpath"] = s(val)
    return info


def load_symlinks(stage):
    """Map 'usr/lib/libfoo.so.1' -> resolved relative path of the real file."""
    raw = {}
    path = os.path.join(stage, "symlinks.txt")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                link, _, target = line.rstrip("\n").partition("\t")
                raw[link] = target
    return raw


def resolve(stage, links, rel):
    """Follow recorded symlinks until a real file is found; return its relative path or None."""
    for _ in range(16):
        if os.path.isfile(os.path.join(stage, *rel.split("/"))):
            return rel
        if rel not in links:
            return None
        target = links[rel]
        if target.startswith("/"):
            marker = "/files/"
            rel = target.split(marker, 1)[1] if marker in target else target.lstrip("/")
        else:
            rel = os.path.normpath(os.path.join(os.path.dirname(rel), target)).replace("\\", "/")
    return None


# Provided by Android itself; never bundled.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libaaudio.so",
    "libvulkan.so", "libnativewindow.so", "libmediandk.so", "libjnigraphics.so",
}


def closure(stage, roots):
    """roots: relative paths of ELF files. Returns ({needed_name: real_rel_path}, missing set)."""
    links = load_symlinks(stage)
    found, missing = {}, set()
    todo = list(roots)
    done = set()
    while todo:
        rel = todo.pop()
        if rel in done:
            continue
        done.add(rel)
        info = read_dynamic(os.path.join(stage, *rel.split("/")))
        if not info:
            continue
        for name in info["needed"]:
            if name in found:
                continue
            real = resolve(stage, links, "usr/lib/" + name)
            if real:
                found[name] = real
                todo.append(real)
            elif name not in SYSTEM_LIBS:
                missing.add(name)
    return found, missing


if __name__ == "__main__":
    import sys

    stage = sys.argv[1]
    found, missing = closure(stage, sys.argv[2:])
    total = 0
    for name in sorted(found):
        size = os.path.getsize(os.path.join(stage, *found[name].split("/")))
        total += size
        print(f"{name:40} {size:>10}  {found[name]}")
    print(f"{len(found)} libs, {total / 1e6:.1f} MB; missing (non-system): {sorted(missing)}")
