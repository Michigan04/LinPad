package dev.linpad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** VM library: create, start, open and delete virtual machines. */
class MainActivity : Activity() {
    private lateinit var store: VmStore
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature = ""

    /** An ISO file the user picked from their own storage for the VM being created. */
    private class PickedIso(val uri: Uri, val name: String, val sizeBytes: Long)

    private var pickedIso: PickedIso? = null
    private var onIsoPicked: ((PickedIso?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VmStore(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(text("LinPad", 30f, Color.WHITE, bold = true))
        content.addView(
            text(
                "Linux virtual machines on this device · ${Qemu.hostRamMb(this)} MB RAM, " +
                    "${java.lang.Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.first()}",
                14f, MUTED,
            )
        )
        content.addView(
            Button(this).apply {
                text = "New virtual machine"
                isAllCaps = false
                setOnClickListener { showCreateDialog() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16); bottomMargin = dp(8) },
        )
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(0xFF0D1117.toInt())
                addView(content)
            }
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Unpack the runtime ahead of the first VM start.
        Thread { runCatching { Runtime.ensureInstalled(this) } }.start()
    }

    override fun onResume() {
        super.onResume()
        lastSignature = ""
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    /** Re-renders the list when the set of VMs or the running VM changes. */
    private val poll = object : Runnable {
        override fun run() {
            val vms = store.list()
            val signature = vms.joinToString { it.id } + "|" + VmRunner.runningId
            if (signature != lastSignature) {
                lastSignature = signature
                render(vms)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun render(vms: List<VmConfig>) {
        list.removeAllViews()
        if (vms.isEmpty()) {
            list.addView(text("No virtual machines yet. Create one to get started.", 15f, MUTED).apply {
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        for (vm in vms) list.addView(card(vm), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun card(vm: VmConfig): View {
        val running = VmRunner.isRunning(vm.id)
        val os = Catalog.byId(vm.osId)?.title ?: vm.osId
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF161B22.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (running) 0xFF7CE38B.toInt() else 0xFF30363D.toInt())
            }
            addView(text(vm.name, 19f, Color.WHITE, bold = true))
            addView(text("$os · ${vm.ramMb} MB RAM · ${vm.cpus} cores · ${vm.diskGb} GB disk", 14f, MUTED))
            addView(text(if (running) "Running" else "Stopped", 13f, if (running) 0xFF7CE38B.toInt() else MUTED))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(flat("Delete") { confirmDelete(vm) })
                    addView(flat(if (running) "Open" else "Start") { open(vm) })
                }
            )
        }
    }

    private fun open(vm: VmConfig) {
        if (VmRunner.anyRunning() && !VmRunner.isRunning(vm.id)) {
            toast("Another VM is running. Shut it down first.")
            return
        }
        startActivity(VmActivity.intent(this, vm.id))
    }

    private fun confirmDelete(vm: VmConfig) {
        if (VmRunner.isRunning(vm.id)) {
            toast("Shut the VM down before deleting it.")
            return
        }
        // The downloaded or imported image can go too, unless another VM still needs it.
        val imageFile = Catalog.byId(vm.osId)?.let { store.imageFile(it) }?.takeIf { it.isFile }
        val shared = store.list().any { it.id != vm.id && it.osId == vm.osId }
        val builder = AlertDialog.Builder(this)
            .setTitle("Delete ${vm.name}?")
            .setMessage("Its disk and everything on it will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(vm.id)
                lastSignature = ""
            }
            .setNegativeButton("Cancel", null)
        if (imageFile != null && !shared) {
            builder.setNeutralButton("Delete + ${imageFile.length() / 1_000_000} MB image") { _, _ ->
                store.delete(vm.id)
                imageFile.delete()
                lastSignature = ""
            }
        }
        builder.show()
    }

    // ---- ISO from the user's own storage -------------------------------------------------

    private fun pickIso() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQUEST_ISO,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ISO) return
        val uri = data?.data?.takeIf { resultCode == RESULT_OK }
        val picked = uri?.let {
            var name = "disc.iso"
            var size = -1L
            runCatching {
                contentResolver.query(it, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0) c.getString(n)?.let { v -> name = v }
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            PickedIso(it, name, size)
        }
        onIsoPicked?.invoke(picked)
    }

    /** File name the picked ISO gets inside the app; QEMU option strings cannot hold commas or spaces. */
    private fun importName(picked: PickedIso): String {
        val clean = picked.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "disc.iso" }
        val existing = File(store.imagesDir, clean)
        // Same name and size: the earlier import is reused. Same name, different file: keep both.
        return if (existing.isFile && existing.length() != picked.sizeBytes) {
            "${System.currentTimeMillis() / 1000}-$clean"
        } else {
            clean
        }
    }

    private fun looksLikePcIso(name: String) =
        Regex("amd64|x86_64|x86-64|x64|i386|i686", RegexOption.IGNORE_CASE).containsMatchIn(name)

    private fun importIso(source: PickedIso, target: File, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (target.isFile && target.length() > 0) return
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val input = contentResolver.openInputStream(source.uri) ?: throw IOException("Could not open the selected file")
        input.use { from ->
            part.outputStream().use { to ->
                val buffer = ByteArray(1 shl 20)
                var done = 0L
                var lastReport = 0L
                while (!cancelled.get()) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        progress(done, source.sizeBytes)
                    }
                }
            }
        }
        if (cancelled.get()) {
            part.delete()
            throw IOException("Import cancelled")
        }
        if (!part.renameTo(target)) throw IOException("Could not move the ISO into place")
    }

    // ---- create --------------------------------------------------------------------------

    private fun showCreateDialog() {
        val hostRam = Qemu.hostRamMb(this)
        val hostCpus = java.lang.Runtime.getRuntime().availableProcessors()
        val maxRam = hostRam * 3 / 4
        val defaultRam = minOf(4096, hostRam / 2) / 256 * 256
        val defaultCpus = (hostCpus - 2).coerceIn(1, 6)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val os = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Catalog.images.map { it.title } + "ISO file from this device…",
            )
        }
        val customIndex = Catalog.images.size
        pickedIso = null
        val note = text("", 13f, MUTED)
        fun field(label: String, value: String, type: Int): EditText {
            form.addView(text(label, 13f, MUTED).apply { setPadding(0, dp(10), 0, 0) })
            return EditText(this).apply {
                setText(value)
                inputType = type
                setSingleLine()
                form.addView(this)
            }
        }
        form.addView(text("Operating system", 13f, MUTED))
        form.addView(os)
        form.addView(note)
        val name = field("Name", "Ubuntu", InputType.TYPE_CLASS_TEXT)
        val ram = field("Memory (MB, up to $maxRam)", defaultRam.toString(), InputType.TYPE_CLASS_NUMBER)
        val cpus = field("CPU cores (up to $hostCpus)", defaultCpus.toString(), InputType.TYPE_CLASS_NUMBER)
        val disk = field("Disk size (GB)", "16", InputType.TYPE_CLASS_NUMBER)
        val password = field(
            "Password for the “ubuntu” user", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )

        os.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position == customIndex) {
                    password.isEnabled = false
                    note.text = "Choose an ARM64 (aarch64) installer ISO. ISOs made for PCs (amd64, x86_64) cannot boot here."
                    onIsoPicked = { picked ->
                        if (picked != null) {
                            pickedIso = picked
                            val mb = if (picked.sizeBytes > 0) " (${picked.sizeBytes / 1_000_000} MB)" else ""
                            note.text = "${picked.name}$mb. It will be copied into LinPad, so it needs that much " +
                                "free space again; you can delete the original afterwards."
                            name.setText(picked.name.substringBeforeLast('.').take(24))
                            disk.setText("16")
                        } else if (pickedIso == null) {
                            os.setSelection(0)
                        }
                    }
                    pickIso()
                    return
                }
                val image = Catalog.images[position]
                note.text = image.description
                name.setText(image.title.substringBefore(' '))
                disk.setText(image.defaultDiskGb.toString())
                // Graphical installers need far more memory than a text console.
                if (image.downloadMb > 2000) ram.setText(minOf(maxRam, maxOf(defaultRam, 4096)).toString())
                password.isEnabled = image.kind == ImageKind.CLOUD_IMAGE
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("New virtual machine")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        // Shrink the form above the on-screen keyboard so Create/Cancel stay reachable.
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            // Validate before dismissing, so mistakes do not lose what was typed.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val custom = os.selectedItemPosition == customIndex
                val picked = pickedIso.takeIf { custom }
                val image = if (custom) picked?.let { Catalog.custom(importName(it)) } else Catalog.images[os.selectedItemPosition]
                val ramMb = ram.text.toString().toIntOrNull()
                val cpuCount = cpus.text.toString().toIntOrNull()
                val diskGb = disk.text.toString().toIntOrNull()
                val vmName = name.text.toString().replace(",", " ").trim()
                val error = when {
                    image == null -> "Choose an ISO file first."
                    picked != null && looksLikePcIso(picked.name) ->
                        "This looks like a PC (amd64/x86) ISO. LinPad can only boot ARM64 (aarch64) ISOs."
                    vmName.isEmpty() -> "Give the VM a name."
                    ramMb == null || ramMb !in 256..maxRam -> "Memory must be between 256 and $maxRam MB."
                    cpuCount == null || cpuCount !in 1..hostCpus -> "CPU cores must be between 1 and $hostCpus."
                    diskGb == null || diskGb !in 1..512 -> "Disk size must be between 1 and 512 GB."
                    image.kind == ImageKind.CLOUD_IMAGE && password.text.isEmpty() ->
                        "Set a password. You will need it to log in."
                    else -> null
                }
                if (error != null || image == null) {
                    toast(error ?: "Choose an ISO file first.")
                } else {
                    dialog.dismiss()
                    create(
                        VmConfig(store.newId(), vmName, image.id, ramMb!!, cpuCount!!, diskGb!!, password.text.toString()),
                        image, picked,
                    )
                }
            }
        }
        dialog.show()
    }

    private fun create(vm: VmConfig, image: OsImage, source: PickedIso? = null) {
        val cancelled = AtomicBoolean(false)
        val message = text("Preparing…", 14f, Color.WHITE)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Creating ${vm.name}")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
                addView(message)
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            })
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()

        // Multi-gigabyte downloads must not stall because the screen timed out.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val result = runCatching {
                val imageFile = store.imageFile(image)
                val partFile = File(imageFile.path + ".part")
                val incomingMb = when {
                    imageFile.isFile -> 0L
                    source != null -> maxOf(0L, source.sizeBytes) / 1_000_000
                    else -> image.downloadMb - partFile.length() / 1_000_000
                }
                val neededMb = incomingMb + 1024
                val freeMb = filesDir.usableSpace / 1_000_000
                check(freeMb >= neededMb) {
                    "Not enough free storage: this needs about $neededMb MB and only $freeMb MB is free."
                }
                Runtime.ensureInstalled(this)
                val verb = if (source != null) "Copying" else "Downloading"
                val progress = { done: Long, total: Long ->
                    handler.post {
                        val mb = done / 1_000_000
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (done * 1000 / total).toInt()
                            message.text = "$verb ${image.title}: $mb of ${total / 1_000_000} MB"
                        } else {
                            bar.isIndeterminate = true
                            message.text = "$verb ${image.title}: $mb MB"
                        }
                    }
                    Unit
                }
                if (source != null) {
                    importIso(source, imageFile, cancelled, progress)
                } else {
                    try {
                        Downloader.download(image.url, imageFile, cancelled, progress)
                    } catch (e: IOException) {
                        if (image.fallbackUrl.isEmpty() || cancelled.get()) throw e
                        partFile.delete()
                        Downloader.download(image.fallbackUrl, imageFile, cancelled, progress)
                    }
                }
                handler.post {
                    bar.isIndeterminate = true
                    message.text = "Creating disk…"
                }
                Qemu.provision(this, store, vm, image)
                store.save(vm)
            }
            handler.post {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.dismiss()
                lastSignature = ""
                result.onFailure {
                    store.delete(vm.id)
                    if (!cancelled.get()) {
                        AlertDialog.Builder(this)
                            .setTitle("Could not create ${vm.name}")
                            .setMessage(it.message ?: it.toString())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    // ---- small view helpers --------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun flat(label: String, action: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setTextColor(0xFF58A6FF.toInt())
            setOnClickListener { action() }
        }

    companion object {
        private const val MUTED = 0xFF8B949E.toInt()
        private const val REQUEST_ISO = 2
    }
}
