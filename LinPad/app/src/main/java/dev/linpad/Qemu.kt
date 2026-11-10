package dev.linpad

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** Builds and launches QEMU command lines. */
object Qemu {
    private const val SYSTEM = "libqemu-system-aarch64.so"
    private const val IMG = "libqemu-img.so"
    private const val VARS_SIZE = 64L * 1024 * 1024

    private fun bin(context: Context, name: String) =
        File(context.applicationInfo.nativeLibraryDir, name).absolutePath

    private fun builder(context: Context, command: List<String>): ProcessBuilder {
        val prefix = Runtime.prefix(context)
        return ProcessBuilder(command).apply {
            directory(context.filesDir)
            environment()["LD_LIBRARY_PATH"] = File(prefix, "lib").absolutePath
            environment()["TMPDIR"] = File(prefix, "tmp").absolutePath
            environment()["HOME"] = context.filesDir.absolutePath
        }
    }

    fun hostRamMb(context: Context): Int {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt()
    }

    /** Runs qemu-img to completion and returns its combined output; throws on a non-zero exit. */
    fun img(context: Context, vararg args: String): String {
        val process = builder(context, listOf(bin(context, IMG)) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "qemu-img ${args.firstOrNull()} failed ($code): ${output.trim()}" }
        return output
    }

    /** Creates the VM's disk, firmware variable store and (for cloud images) cloud-init seed. */
    fun provision(context: Context, store: VmStore, vm: VmConfig, image: OsImage) {
        store.dir(vm.id).mkdirs()
        val disk = store.disk(vm.id).absolutePath
        when (image.kind) {
            ImageKind.CLOUD_IMAGE -> {
                // Copy-on-write overlay: the downloaded image stays pristine and reusable.
                img(
                    context, "create", "-f", "qcow2", "-F", "qcow2",
                    "-b", store.imageFile(image).absolutePath, disk, "${vm.diskGb}G",
                )
                writeSeed(store, vm)
            }
            ImageKind.INSTALLER_ISO -> img(context, "create", "-f", "qcow2", disk, "${vm.diskGb}G")
        }
        RandomAccessFile(store.vars(vm.id), "rw").use { it.setLength(VARS_SIZE) }
    }

    /** cloud-init "NoCloud" seed; QEMU exposes this directory as a FAT volume labelled cidata. */
    private fun writeSeed(store: VmStore, vm: VmConfig) {
        val seed = store.seedDir(vm.id).apply { mkdirs() }
        val host = vm.name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "linpad" }
        File(seed, "meta-data").writeText("instance-id: linpad-${vm.id}\nlocal-hostname: $host\n")
        File(seed, "user-data").writeText(
            buildString {
                append("#cloud-config\n")
                append("ssh_pwauth: true\n")
                append("chpasswd:\n")
                append("  expire: false\n")
                append("  users:\n")
                append("    - name: ubuntu\n")
                append("      password: ${yamlQuote(vm.password)}\n")
                append("      type: text\n")
            }
        )
    }

    private fun yamlQuote(s: String) = "'" + s.replace("'", "''") + "'"

    fun systemCommand(context: Context, store: VmStore, vm: VmConfig): List<String> {
        val image = Catalog.byId(vm.osId)
        val data = Runtime.dataDir(context)
        val tbSizeMb = if (hostRamMb(context) >= 6000) 512 else 128
        val args = mutableListOf(
            bin(context, SYSTEM),
            "-nodefaults",
            "-L", data.absolutePath,
            "-name", vm.name,
            "-machine", "virt",
            "-cpu", "max",
            "-accel", "tcg,thread=multi,tb-size=$tbSizeMb",
            "-smp", vm.cpus.toString(),
            "-m", vm.ramMb.toString(),
            // UEFI firmware (read-only code + per-VM variable store).
            "-drive", "if=pflash,format=raw,readonly=on,file=${File(data, "edk2-aarch64-code.fd")}",
            "-drive", "if=pflash,format=raw,file=${store.vars(vm.id)}",
            // Display and input.
            "-device", "virtio-gpu-pci,xres=1280,yres=800",
            "-device", "qemu-xhci",
            "-device", "usb-kbd",
            "-device", "usb-tablet",
            "-vnc", "unix:${store.vncSocket(vm.id)}",
            // Storage.
            "-drive", "if=none,id=hd0,format=qcow2,discard=unmap,file=${store.disk(vm.id)}",
            "-device", "virtio-blk-pci,drive=hd0,bootindex=0",
            // Network: user-mode NAT, guest SSH reachable on the tablet's localhost:2222.
            "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22",
            "-device", "virtio-net-pci,netdev=net0",
            "-device", "virtio-rng-pci",
            // Management and logs.
            "-qmp", "unix:${store.qmpSocket(vm.id)},server=on,wait=off",
            "-serial", "file:${store.serialLog(vm.id)}",
        )
        if (image != null) {
            when (image.kind) {
                ImageKind.INSTALLER_ISO -> args += listOf(
                    "-device", "virtio-scsi-pci,id=scsi0",
                    "-drive", "if=none,id=cd0,format=raw,readonly=on,media=cdrom,file=${store.imageFile(image)}",
                    "-device", "scsi-cd,drive=cd0,bootindex=1",
                )
                ImageKind.CLOUD_IMAGE -> args += listOf(
                    "-drive",
                    "if=none,id=seed,format=raw,readonly=on,file.driver=vvfat," +
                        "file.dir=${store.seedDir(vm.id)},file.label=cidata",
                    "-device", "virtio-blk-pci,drive=seed",
                )
            }
        }
        return args
    }

    fun startSystem(context: Context, store: VmStore, vm: VmConfig): Process {
        Runtime.ensureInstalled(context)
        Runtime.writeResolvConf(context)
        store.vncSocket(vm.id).delete()
        store.qmpSocket(vm.id).delete()
        val log = store.qemuLog(vm.id)
        val command = systemCommand(context, store, vm)
        log.writeText(command.joinToString(" ") + "\n\n")
        return builder(context, command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
    }
}
