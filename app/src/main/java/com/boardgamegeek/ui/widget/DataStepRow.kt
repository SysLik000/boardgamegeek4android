package com.boardgamegeek.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import com.boardgamegeek.R
import com.boardgamegeek.extensions.fadeIn
import com.boardgamegeek.extensions.fadeOut
import kotlinx.android.synthetic.main.widget_data_step_row.view.*

class DataStepRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    init {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_data_step_row, this, true)

        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gravity = Gravity.CENTER_VERTICAL
        orientation = VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.view_row_height)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.padding_half)
        setPadding(0, verticalPadding, 0, verticalPadding)

        context.withStyledAttributes(attrs, R.styleable.DataStepRow, defStyleAttr) {
            typeView.text = getString(R.styleable.DataStepRow_titleLabel)
            descriptionView.text = getString(R.styleable.DataStepRow_descriptionLabel)
        }
    }

    fun onExport(l: OnClickListener) {
        exportButton.setOnClickListener(l)
    }

    fun onImport(l: OnClickListener) {
        importButton.setOnClickListener(l)
    }

    fun initProgressBar() {
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressBar.fadeIn()
        importButton?.isEnabled = false
        exportButton?.isEnabled = false
    }

    fun updateProgressBar(max: Int, progress: Int) {
        if (max < 0) {
            progressBar.isIndeterminate = true
        } else {
            progressBar.isIndeterminate = false
            progressBar.max = max
            progressBar.progress = progress
        }
    }

    fun hideProgressBar() {
        progressBar.fadeOut()
        importButton?.isEnabled = true
        exportButton?.isEnabled = true
    }
}
