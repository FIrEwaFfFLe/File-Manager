package org.fossify.filemanager.activities

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.ActivityRecentsSettingsBinding
import org.fossify.filemanager.extensions.config

class RecentsSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRecentsSettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.recentsSettingsScroll))
        setupMaterialScrollListener(binding.recentsSettingsScroll, binding.recentsSettingsAppbar)
        setupTopAppBar(binding.recentsSettingsAppbar, org.fossify.commons.helpers.NavigationIcon.Arrow)
        binding.recentsLimitHolder.setOnClickListener { showLimitDialog() }
        binding.recentsImages.isChecked = config.recentsImages
        binding.recentsVideos.isChecked = config.recentsVideos
        binding.recentsOther.isChecked = config.recentsOther
        binding.recentsInternalStorage.isChecked = config.recentsInternalStorage
        binding.recentsUsbStorage.isChecked = config.recentsUsbStorage
        binding.recentsImagesHolder.setOnClickListener { binding.recentsImages.toggle() }
        binding.recentsVideosHolder.setOnClickListener { binding.recentsVideos.toggle() }
        binding.recentsOtherHolder.setOnClickListener { binding.recentsOther.toggle() }
        binding.recentsInternalStorageHolder.setOnClickListener { binding.recentsInternalStorage.toggle() }
        binding.recentsUsbStorageHolder.setOnClickListener { binding.recentsUsbStorage.toggle() }
        binding.recentsImages.setOnCheckedChangeListener { _, value -> config.recentsImages = value }
        binding.recentsVideos.setOnCheckedChangeListener { _, value -> config.recentsVideos = value }
        binding.recentsOther.setOnCheckedChangeListener { _, value -> config.recentsOther = value }
        binding.recentsInternalStorage.setOnCheckedChangeListener { _, value -> config.recentsInternalStorage = value }
        binding.recentsUsbStorage.setOnCheckedChangeListener { _, value -> config.recentsUsbStorage = value }
        updateLimit()
        updateTextColors(binding.root)
        binding.recentsLimitLabel.setTextColor(getProperPrimaryColor())
    }

    private fun updateLimit() {
        binding.recentsLimitValue.text = config.recentsLimit.toString()
    }

    private fun showLimitDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.recentsLimit.toString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.maximum_recent_files)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in 1..10000) {
                    input.error = getString(R.string.recent_limit_error)
                } else {
                    config.recentsLimit = value
                    updateLimit()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
