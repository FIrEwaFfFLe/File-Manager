package org.fossify.filemanager.dialogs

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.filemanager.R
import org.fossify.filemanager.helpers.compression.SmartCompressionProgress
import java.util.concurrent.atomic.AtomicBoolean

class SmartCompressionProgressDialog(private val activity: BaseSimpleActivity) {
    val isCancelled = AtomicBoolean(false)
    private val details = TextView(activity).apply {
        setTextColor(activity.getProperTextColor())
        textSize = 14f
    }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val margin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(margin, margin, margin, margin)
            addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val progressParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            progressParams.topMargin = margin
            addView(progress, progressParams)
        }
        activity.getAlertDialogBuilder()
            .setTitle(R.string.smart_compression_progress_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(content, this) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.setCancelable(false)
                    alertDialog.setCanceledOnTouchOutside(false)
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        isCancelled.set(true)
                        details.setText(R.string.compression_cancel_requested)
                        alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                    }
                }
            }
    }

    fun update(value: SmartCompressionProgress) {
        val phase = when (value.stage) {
            SmartCompressionProgress.Stage.SCANNING -> activity.getString(R.string.scanning)
            SmartCompressionProgress.Stage.COMPRESSING -> activity.getString(R.string.compressing)
            SmartCompressionProgress.Stage.VERIFYING -> activity.getString(R.string.verifying_archives)
            SmartCompressionProgress.Stage.PUBLISHING -> activity.getString(R.string.finalizing_archive)
        }
        details.text = activity.getString(
            R.string.smart_compression_progress_detail,
            phase,
            value.currentArchive,
            value.completedArchives,
            value.processedEntries,
            value.totalEntries
        )
        progress.max = value.totalEntries.coerceAtLeast(1)
        progress.progress = value.processedEntries.coerceAtMost(progress.max)
        progress.visibility = if (value.totalEntries == 0) View.GONE else View.VISIBLE
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}
