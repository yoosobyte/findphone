package com.yoosobyte.findphone.ui

import android.graphics.Color
import android.view.View
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

internal fun BottomSheetDialog.makeBackgroundTransparent(expand: Boolean = true) {
    setOnShowListener {
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.setBackgroundColor(Color.TRANSPARENT)
        if (sheet != null) {
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.skipCollapsed = true
            if (expand) behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
