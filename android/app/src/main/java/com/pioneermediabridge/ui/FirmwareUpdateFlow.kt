package com.pioneermediabridge.ui

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pioneermediabridge.R
import com.pioneermediabridge.ble.BleSession
import com.pioneermediabridge.ble.EspImageValidator
import com.pioneermediabridge.ble.FirmwareImage
import com.pioneermediabridge.ble.ImageValidation
import com.pioneermediabridge.ble.OtaPhase
import com.pioneermediabridge.ble.SemVer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Setup-page firmware update flow: pick from Download, validate locally, confirm twice,
 * then hand off to the transfer manager and return to the main screen.
 */
class FirmwareUpdateFlow(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val openDocumentPicker: () -> Unit
) {

    private var progressDialog: Dialog? = null

    fun onPickRequested() {
        val writer = BleSession.writer
        if (writer == null || writer.firmwareInfo.value == null) {
            alert(R.string.firmware_not_connected)
            return
        }
        if (!writer.supportsOta) {
            alert(R.string.firmware_ota_unsupported)
            return
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val canListDirectly = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        val images = if (canListDirectly) {
            downloads.listFiles { f -> f.isFile && f.name.endsWith(".bin", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        } else {
            emptyList()
        }

        if (images.isEmpty()) {
            // No direct read access, or nothing there: let the user point at the file explicitly.
            openDocumentPicker()
            return
        }

        val labels = images.map { "${it.name}  (${Formatter.formatShortFileSize(activity, it.length())})" }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.firmware_pick_title)
            .setItems(labels.toTypedArray()) { _, index -> validate(images[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onDocumentPicked(uri: Uri?) {
        if (uri == null) return
        lifecycleOwner.lifecycleScope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                alert(R.string.firmware_read_failed)
                return@launch
            }
            validateBytes(name, bytes)
        }
    }

    private fun validate(file: File) {
        lifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes == null) {
                alert(R.string.firmware_read_failed)
                return@launch
            }
            validateBytes(file.name, bytes)
        }
    }

    private fun validateBytes(name: String, bytes: ByteArray) {
        val slotSize = BleSession.writer?.firmwareInfo?.value?.otaSlotSize?.takeIf { it > 0 }
        when (val result = EspImageValidator.validate(name, bytes, slotSize)) {
            is ImageValidation.Invalid -> MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.firmware_invalid_title)
                .setMessage(result.reason)
                .setPositiveButton(android.R.string.ok, null)
                .show()

            is ImageValidation.Valid -> confirmFirst(result.image)
        }
    }

    // ── Confirmation, twice ───────────────────────────────────────────────────

    private fun confirmFirst(image: FirmwareImage) {
        val current = BleSession.writer?.firmwareInfo?.value?.version ?: SemVer.ZERO
        val comparison = image.version.compareTo(current)
        val isDowngrade = comparison < 0
        val isSame = comparison == 0

        val body = buildString {
            append("Current device firmware: $current\n")
            append("Selected image: ${image.version}\n")
            append("File: ${image.displayName} (${Formatter.formatShortFileSize(activity, image.size.toLong())})\n")
            if (image.versionAssumed) {
                append("\nThe image carries no valid version string ('${image.versionText}'), so it is treated as 0.0.0.\n")
            }
            append("\n")
            when {
                isDowngrade -> append("WARNING: This is a DOWNGRADE. The device will lose any changes made after $current.")
                isSame -> append("This is the SAME version that is already installed.")
                else -> append("This is an upgrade.")
            }
        }

        val actionLabel = when {
            isDowngrade -> R.string.ota_action_downgrade
            isSame -> R.string.ota_action_reinstall
            else -> R.string.ota_action_update
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (isDowngrade) R.string.ota_confirm_title_downgrade else R.string.ota_confirm_title)
            .setMessage(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(actionLabel) { _, _ -> confirmSecond(image, isDowngrade, actionLabel) }
            .show()
    }

    private fun confirmSecond(image: FirmwareImage, isDowngrade: Boolean, actionLabel: Int) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_ota_confirm, null)
        val checkbox = view.findViewById<CheckBox>(R.id.check_understand)
        view.findViewById<TextView>(R.id.text_confirm_body).text = buildString {
            append("About to install ${image.version} on the device.\n")
            if (isDowngrade) append("\nThis is a DOWNGRADE from the running firmware.\n")
            append("\nDo not power off the device until it reconnects.")
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ota_confirm_final_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(actionLabel) { _, _ -> startTransfer(image) }
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            checkbox.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
        }
        dialog.show()
    }

    // ── Transfer ──────────────────────────────────────────────────────────────

    private fun startTransfer(image: FirmwareImage) {
        val writer = BleSession.writer ?: run { alert(R.string.firmware_not_connected); return }
        val transfer = writer.otaTransfer ?: run { alert(R.string.firmware_not_connected); return }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_ota_progress, null)
        val bar = view.findViewById<ProgressBar>(R.id.progress_ota)
        val status = view.findViewById<TextView>(R.id.text_ota_status)
        val detail = view.findViewById<TextView>(R.id.text_ota_detail)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ota_progress_title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> transfer.cancel() }
            .create()
        progressDialog = dialog
        dialog.show()

        transfer.start(writer, image)

        lifecycleOwner.lifecycleScope.launch {
            transfer.progress.collect { progress ->
                bar.progress = progress.percent
                status.text = progress.message
                detail.text = activity.getString(
                    R.string.ota_progress_detail,
                    progress.bytesSent / 1024,
                    progress.totalBytes / 1024
                )

                when (progress.phase) {
                    // The main screen owns the post-reboot outcome, so hand over as soon as
                    // the image is on the device.
                    OtaPhase.REBOOTING, OtaPhase.SUCCESS -> {
                        dismissProgress()
                        activity.finish()
                    }
                    OtaPhase.FAILED -> {
                        dismissProgress()
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.firmware_invalid_title)
                            .setMessage(progress.message)
                            .setPositiveButton(android.R.string.ok) { _, _ -> transfer.acknowledgeResult() }
                            .show()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun alert(messageRes: Int) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
