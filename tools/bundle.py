"""Assemble the QEMU runtime into the LinPad Android project.

Usage: python bundle.py <project-dir>

For each ABI this writes
  app/src/<flavor>/jniLibs/<abi>/lib<exe>.so   executables (Android only allows exec from there)
  app/src/<flavor>/assets/runtime.zip          shared libraries, laid out as usr/lib/<needed-name>
and once, shared by both ABIs,
  app/src/main/assets/firmware.zip             UEFI firmware, option ROMs, keymaps

The Termux binaries hard-code /data/data/com.termux/files/usr. The app's package
name (dev.linpad) is deliberately the same length, so rewriting the package name
in place makes every baked-in path point into our own private data directory.
"""
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from elfdeps import closure  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OLD, NEW = b"com.termux", b"dev.linpad"
assert len(OLD) == len(NEW)

TARGETS = [("x86_64", "x64", "x86_64"), ("aarch64", "arm64", "arm64-v8a")]
EXECUTABLES = {
    "usr/bin/qemu-system-aarch64": "libqemu-system-aarch64.so",
    "usr/bin/qemu-img": "libqemu-img.so",
}
FIRMWARE = ["edk2-aarch64-code.fd", "efi-virtio.rom"]


def patched(path):
    with open(path, "rb") as f:
        return f.read().replace(OLD, NEW)


def main():
    project = os.path.abspath(sys.argv[1])
    for arch, flavor, abi in TARGETS:
        stage = os.path.join(ROOT, "stage", arch)
        found, missing = closure(stage, EXECUTABLES)
        if missing:
            sys.exit(f"{arch}: unresolved libraries: {sorted(missing)}")
        jni = os.path.join(project, "app", "src", flavor, "jniLibs", abi)
        os.makedirs(jni, exist_ok=True)
        for rel, name in EXECUTABLES.items():
            with open(os.path.join(jni, name), "wb") as f:
                f.write(patched(os.path.join(stage, *rel.split("/"))))
        assets = os.path.join(project, "app", "src", flavor, "assets")
        os.makedirs(assets, exist_ok=True)
        with zipfile.ZipFile(os.path.join(assets, "runtime.zip"), "w", zipfile.ZIP_DEFLATED) as z:
            for name in sorted(found):
                z.writestr("usr/lib/" + name, patched(os.path.join(stage, *found[name].split("/"))))
        print(f"{abi}: {len(EXECUTABLES)} executables, {len(found)} libraries")

    share = os.path.join(ROOT, "stage", "x86_64", "usr", "share", "qemu")
    assets = os.path.join(project, "app", "src", "main", "assets")
    os.makedirs(assets, exist_ok=True)
    count = 0
    with zipfile.ZipFile(os.path.join(assets, "firmware.zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for name in FIRMWARE:
            z.write(os.path.join(share, name), "usr/share/qemu/" + name)
            count += 1
        keymaps = os.path.join(share, "keymaps")
        for name in sorted(os.listdir(keymaps)):
            z.write(os.path.join(keymaps, name), "usr/share/qemu/keymaps/" + name)
            count += 1
    print(f"firmware: {count} files")


if __name__ == "__main__":
    main()
