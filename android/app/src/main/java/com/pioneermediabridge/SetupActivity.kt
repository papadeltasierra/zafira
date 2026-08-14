package com.pioneermediabridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pioneermediabridge.databinding.ActivitySetupBinding
import com.pioneermediabridge.ble.BleSession
import com.pioneermediabridge.model.AppSettings
import com.pioneermediabridge.ui.FirmwareUpdateFlow
import com.pioneermediabridge.ui.SetupViewModel
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val viewModel: SetupViewModel by viewModels()
    private var knownDevices: List<KnownBluetoothDevice> = emptyList()

    private val openFirmwareLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            firmwareFlow.onDocumentPicked(uri)
        }

    private lateinit var firmwareFlow: FirmwareUpdateFlow

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshKnownDevices()
            updateMacDropdown(
                binding.editPioneerName.text.toString(),
                binding.editPioneerMac
            )
            updateMacDropdown(
                binding.editOutputName.text.toString(),
                binding.editOutputMac
            )
        } else {
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadKnownDevicesWhenAllowed()
        firmwareFlow = FirmwareUpdateFlow(this, this) {
            openFirmwareLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
        binding.editPioneerName.afterTextChanged {
            updateMacDropdown(it, binding.editPioneerMac)
        }
        binding.editOutputName.afterTextChanged {
            updateMacDropdown(it, binding.editOutputMac)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { s ->
                    if (!binding.editPioneerMac.isFocused) binding.editPioneerMac.setText(s.pioneerMac)
                    if (!binding.editPioneerName.isFocused) binding.editPioneerName.setText(s.pioneerName)
                    if (!binding.editOutputMac.isFocused) binding.editOutputMac.setText(s.outputBleMac)
                    if (!binding.editOutputName.isFocused) binding.editOutputName.setText(s.outputBleName)
                    if (!binding.editSnoopPath.isFocused) binding.editSnoopPath.setText(s.snoopFilePath)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val info = BleSession.writer?.firmwareInfo ?: return@repeatOnLifecycle
                info.collect { firmware ->
                    binding.textDeviceFirmware.text = when {
                        firmware == null -> getString(R.string.firmware_unknown)
                        firmware.pendingVerify -> getString(
                            R.string.firmware_device_version_verifying,
                            firmware.version.toString()
                        )
                        else -> getString(R.string.firmware_device_version, firmware.version.toString())
                    }
                }
            }
        }

        binding.buttonUpdateFirmware.setOnClickListener { firmwareFlow.onPickRequested() }
        binding.buttonSave.setOnClickListener { saveSettings() }
        binding.buttonDefaultPath.setOnClickListener {
            binding.editSnoopPath.setText(AppSettings.DEFAULT_SNOOP_PATH)
        }
    }

    private fun saveSettings() {
        val pioneerMac = binding.editPioneerMac.text.toString().trim()
        val pioneerName = binding.editPioneerName.text.toString().trim()
        val outputMac = binding.editOutputMac.text.toString().trim()
        val outputName = binding.editOutputName.text.toString().trim()
        val snoopPath = binding.editSnoopPath.text.toString().trim()

        if (pioneerMac.isEmpty() && pioneerName.isEmpty()) {
            binding.editPioneerMac.error = getString(R.string.error_pioneer_required)
            return
        }
        if (outputMac.isEmpty() && outputName.isEmpty()) {
            binding.editOutputMac.error = getString(R.string.error_output_required)
            return
        }
        if (pioneerMac.isNotEmpty() && !isValidMac(pioneerMac)) {
            binding.editPioneerMac.error = getString(R.string.error_invalid_mac)
            return
        }
        if (outputMac.isNotEmpty() && !isValidMac(outputMac)) {
            binding.editOutputMac.error = getString(R.string.error_invalid_mac)
            return
        }

        viewModel.save(pioneerMac, pioneerName, outputMac, outputName, snoopPath)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun isValidMac(mac: String): Boolean =
        mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))

    private fun loadKnownDevicesWhenAllowed() {
        if (hasBluetoothConnectPermission()) {
            refreshKnownDevices()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshKnownDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        knownDevices = adapter.bondedDevices
            .mapNotNull { device -> device.toKnownBluetoothDevice() }
            .sortedWith(compareBy<KnownBluetoothDevice> { it.name.lowercase() }.thenBy { it.address })
    }

    private fun updateMacDropdown(deviceName: String, macInput: AutoCompleteTextView) {
        val normalizedName = deviceName.trim()
        val matches = if (normalizedName.isEmpty()) {
            emptyList()
        } else {
            knownDevices.filter { it.name.equals(normalizedName, ignoreCase = true) }
        }

        macInput.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                matches.map { it.address }
            )
        )

        if (matches.size == 1) {
            val address = matches.single().address
            if (!macInput.isFocused || macInput.text.toString().isBlank()) {
                macInput.setText(address, false)
            }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toKnownBluetoothDevice(): KnownBluetoothDevice? {
        val deviceName = name?.trim().orEmpty()
        val deviceAddress = address?.trim().orEmpty()
        if (deviceName.isEmpty() || deviceAddress.isEmpty()) return null
        return KnownBluetoothDevice(deviceName, deviceAddress)
    }

    private fun TextView.afterTextChanged(onChanged: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private data class KnownBluetoothDevice(
        val name: String,
        val address: String
    )

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
