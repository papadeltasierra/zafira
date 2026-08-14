package com.pioneermediabridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pioneermediabridge.ble.BleSession
import com.pioneermediabridge.ble.OtaPhase
import com.pioneermediabridge.ble.OtaProgress
import com.pioneermediabridge.databinding.ActivityMainBinding
import com.pioneermediabridge.service.MonitorService
import com.pioneermediabridge.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var allFilesPromptShown = false

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startBridgeIfAllowed() }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val media = intent.getStringExtra(MonitorService.EXTRA_MEDIA_INFO) ?: "Idle"
            val ble = intent.getStringExtra(MonitorService.EXTRA_BLE_STATE) ?: "DISCONNECTED"
            viewModel.updateFromBroadcast(media, ble)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBridgeIfAllowed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.textBleState.text = state.bleState
                    binding.textMediaInfo.text = state.currentMedia
                    binding.textConfigStatus.text = if (state.isConfigured)
                        getString(R.string.configured) else getString(R.string.not_configured)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The writer only exists once the monitor service has started.
                while (true) {
                    val transfer = BleSession.writer?.otaTransfer
                    if (transfer != null) {
                        transfer.progress.collect { renderOtaBanner(it) }
                    }
                    delay(1_000)
                }
            }
        }
    }

    private fun renderOtaBanner(progress: OtaProgress) {
        val text = when (progress.phase) {
            OtaPhase.REBOOTING -> {
                if (BleSession.writer?.firmwareInfo?.value?.pendingVerify == true) {
                    getString(R.string.ota_banner_verifying)
                } else {
                    getString(R.string.ota_banner_waiting)
                }
            }
            OtaPhase.SUCCESS -> getString(R.string.ota_banner_success, progress.message)
            OtaPhase.FAILED -> getString(R.string.ota_banner_failed, progress.message)
            else -> null
        }

        binding.textOtaBanner.text = text.orEmpty()
        binding.textOtaBanner.visibility = if (text == null) View.GONE else View.VISIBLE
        binding.textOtaBanner.setTextColor(
            if (progress.phase == OtaPhase.FAILED) getColor(R.color.ota_error)
            else getColor(R.color.ota_info)
        )
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(MonitorService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startBridgeIfAllowed()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SetupActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun hasAllFilesPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 36) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return Environment.isExternalStorageManager()
    }

    private fun promptAllFilesPermission() {
        if (allFilesPromptShown) return
        allFilesPromptShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_all_files_title)
            .setMessage(R.string.perm_all_files_msg)
            .setPositiveButton(R.string.perm_all_files_button) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"))
                allFilesLauncher.launch(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startBridgeIfAllowed() {
        lifecycleScope.launch {
            if (!viewModel.isConfigured()) return@launch
            if (!hasAllFilesPermission()) {
                promptAllFilesPermission()
                return@launch
            }
            if (hasRequiredPermissions()) {
                MonitorService.start(this@MainActivity)
            } else {
                requestPermissions()
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val toRequest = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        permissionLauncher.launch(toRequest.toTypedArray())
    }
}
