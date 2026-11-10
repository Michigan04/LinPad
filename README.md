# LinPad

Run Linux virtual machines on an Android tablet or phone. LinPad is a small
VirtualBox-style app: pick an operating system, set memory, cores and disk
size, and it downloads the image, creates the disk and boots it with a bundled
QEMU. The VM's screen and keyboard are built into the app.

Status: early. Ubuntu 24.04 (cloud image) and Alpine 3.22 boot to a working text
login with networking, and the Debian 13 graphical installer starts and responds
to touch. You can also create a VM from any ARM64 installer ISO stored on the
device. Full desktop installs have not been tested end to end yet.

## Install on a tablet

1. On the tablet, open the [Releases page](../../releases/latest) and download
   `LinPad-0.3-arm64.apk`.
2. Open the downloaded file. Android will ask you to allow your browser (or
   Files app) to install unknown apps; allow it, then tap Install.
3. If Play Protect warns about an unrecognised developer, choose
   "Install anyway". The APK is signed with a development key, not a store key.

Requirements: a 64-bit ARM device (almost every tablet and phone from the last
several years) running Android 8.0 or newer, about 1 GB of free storage for
the app plus an Ubuntu image, and more for the VM's disk as it fills.

## Use

1. Tap **New virtual machine**, choose **Ubuntu 24.04 LTS**, and set a
   password. The defaults for memory and cores are picked from your device.
2. Wait for the image download (about 620 MB, once) and tap **Start**.
3. The first boot is the slowest because Ubuntu configures itself. Log in as
   `ubuntu` with the password you set.
4. **Shut down** in the toolbar powers the guest off cleanly. The **Log**
   button shows the boot console if the screen stays black.

Inside the guest, SSH is forwarded to port 2222 on the tablet itself
(`ssh -p 2222 ubuntu@127.0.0.1` from a terminal app such as Termux).

## How it works, and its limits

Android does not let ordinary apps use hardware virtualisation, so LinPad
runs QEMU in pure emulation (TCG). The guest is ARM64, the same architecture
as the tablet, but every instruction is still translated in software, so
expect it to be several times slower than the tablet's native speed. One VM
runs at a time, kept alive by a foreground service.

## Build from source

The Gradle project is in `LinPad/`. Flavors: `arm64` for real devices and
`x64` for the Android emulator.

    gradle assembleArm64Debug

The prebuilt QEMU runtime under `LinPad/app/src/*/jniLibs` and
`LinPad/app/src/*/assets` is produced by the scripts in `tools/`, which
download Termux packages and repackage them (`tools/bundle.py`). `env.ps1`
and `.vscode/tasks.json` are Windows conveniences that assume the Android SDK
and Gradle are unpacked next to this folder.

## Third-party software

LinPad's APK bundles unmodified prebuilt binaries from the
[Termux](https://termux.dev) package repository
(`https://packages.termux.dev/apt/termux-main/`), with only the embedded
install path rewritten:

- [QEMU](https://www.qemu.org) 11.1.3 (GPL-2.0), with its EDK2 UEFI firmware
  and option ROMs
- its library dependencies, including GLib, GnuTLS, Nettle, GMP, libslirp,
  SPICE, pixman, GStreamer, libcurl, OpenSSL, libssh, zlib, zstd and others,
  each under its own licence (mostly LGPL, GPL, BSD or MIT)

Source code and the exact build recipes for every one of those packages are
published by Termux at <https://github.com/termux/termux-packages>; QEMU's
source is at <https://gitlab.com/qemu-project/qemu>. Operating system images
are downloaded at run time from Canonical and Alpine Linux and are not part
of this repository.
