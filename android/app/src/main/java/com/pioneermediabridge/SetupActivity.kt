package com.pioneermediabridge

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pioneermediabridge.databinding.ActivitySetupBinding
import com.pioneermediabridge.model.AppSettings
import com.pioneermediabridge.ui.SetupViewModel
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val viewModel: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { s ->
                    if (!binding.editPioneerMac.isFocused) binding.editPioneerMac.setText(s.pioneerMac)
                    if (!binding.editPioneerName.isFocused) binding.editPioneerName.setText(s.pioneerName)
                    if (!binding.editOutputMac.isFocused) binding.editOutputMac.setText(s.outputBleMac)
                    if (!binding.editOutputName.isFocused) binding.editOutputName.setText(s.outputBleName)
                    if (!binding.editSnoopPath.isFocused) binding.editSnoopPath.setText(s.snoopFilePath)
                    if (!binding.editSnoopSocket.isFocused) binding.editSnoopSocket.setText(s.snoopSocketName)
                }
            }
        }

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
        val snoopSocket = binding.editSnoopSocket.text.toString().trim()

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

        viewModel.save(pioneerMac, pioneerName, outputMac, outputName, snoopPath, snoopSocket)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun isValidMac(mac: String): Boolean =
        mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
