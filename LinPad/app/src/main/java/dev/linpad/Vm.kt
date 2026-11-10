package dev.linpad

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class ImageKind {
    /** Bootable installer ISO, attached as a CD-ROM next to an empty disk. */
    INSTALLER_ISO,

    /** Pre-installed qcow2 cloud image, configured on first boot through cloud-init. */
    CLOUD_IMAGE,
}

data class OsImage(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val fileName: String,
    val kind: ImageKind,
    val defaultDiskGb: Int,
)

/** ARM64 guests only: the same images run in the x86_64 emulator and on ARM tablets. */
object Catalog {
    val images = listOf(
        OsImage(
            id = "ubuntu-24.04",
            title = "Ubuntu 24.04 LTS",
            description = "Official Ubuntu cloud image (about 600 MB download). Login: ubuntu",
            url = "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64.img",
            fileName = "noble-server-cloudimg-arm64.img",
            kind = ImageKind.CLOUD_IMAGE,
            defaultDiskGb = 16,
        ),
        OsImage(
            id = "alpine-3.22",
            title = "Alpine Linux 3.22",
            description = "Tiny live/installer ISO (about 70 MB). Login: root, no password",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-virt-3.22.2-aarch64.iso",
            fileName = "alpine-virt-3.22.2-aarch64.iso",
            kind = ImageKind.INSTALLER_ISO,
            defaultDiskGb = 4,
        ),
    )

    fun byId(id: String): OsImage? = images.firstOrNull { it.id == id }
}

data class VmConfig(
    val id: String,
    val name: String,
    val osId: String,
    val ramMb: Int,
    val cpus: Int,
    val diskGb: Int,
    val password: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("osId", osId)
        .put("ramMb", ramMb)
        .put("cpus", cpus)
        .put("diskGb", diskGb)
        .put("password", password)

    companion object {
        fun fromJson(o: JSONObject) = VmConfig(
            id = o.getString("id"),
            name = o.getString("name"),
            osId = o.getString("osId"),
            ramMb = o.getInt("ramMb"),
            cpus = o.getInt("cpus"),
            diskGb = o.getInt("diskGb"),
            password = o.optString("password", ""),
        )
    }
}

/** On-disk layout: files/vms/<id>/{config.json, disk.qcow2, vars.fd, seed/, *.log, *.sock}. */
class VmStore(context: Context) {
    private val root = File(context.filesDir, "vms")
    val imagesDir = File(context.filesDir, "images")

    fun dir(id: String) = File(root, id)
    fun disk(id: String) = File(dir(id), "disk.qcow2")
    fun vars(id: String) = File(dir(id), "vars.fd")
    fun seedDir(id: String) = File(dir(id), "seed")
    fun vncSocket(id: String) = File(dir(id), "vnc.sock")
    fun qmpSocket(id: String) = File(dir(id), "qmp.sock")
    fun qemuLog(id: String) = File(dir(id), "qemu.log")
    fun serialLog(id: String) = File(dir(id), "serial.log")
    fun imageFile(image: OsImage) = File(imagesDir, image.fileName)

    fun list(): List<VmConfig> =
        (root.listFiles() ?: emptyArray())
            .map { File(it, "config.json") }
            .filter { it.isFile }
            .mapNotNull { runCatching { VmConfig.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun get(id: String): VmConfig? = list().firstOrNull { it.id == id }

    fun save(vm: VmConfig) {
        dir(vm.id).mkdirs()
        File(dir(vm.id), "config.json").writeText(vm.toJson().toString(2))
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun newId(): String {
        while (true) {
            val id = (1..6).map { "0123456789abcdef".random() }.joinToString("")
            if (!dir(id).exists()) return id
        }
    }
}
