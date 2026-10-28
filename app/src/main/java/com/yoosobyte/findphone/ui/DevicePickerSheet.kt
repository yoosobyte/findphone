package com.yoosobyte.findphone.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yoosobyte.findphone.R
import com.yoosobyte.findphone.cloud.CloudDevice
import com.yoosobyte.findphone.databinding.ItemDeviceBinding
import com.yoosobyte.findphone.databinding.SheetDevicePickerBinding

class DevicePickerSheet(
    private val activity: Activity,
    private val devices: List<CloudDevice>,
    private val onPick: (CloudDevice) -> Unit,
    private val onDismissed: () -> Unit,
) {

    private var picked = false

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = SheetDevicePickerBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)
        dialog.makeBackgroundTransparent()

        binding.devicePickerSubtitle.text = activity.getString(
            R.string.device_picker_subtitle,
            devices.size,
        )

        devices.forEach { device ->
            val item = ItemDeviceBinding.inflate(
                LayoutInflater.from(activity),
                binding.deviceContainer,
                false,
            )
            item.deviceName.text = device.name.ifBlank {
                activity.getString(R.string.device_unknown_name)
            }
            item.deviceState.text = if (device.online) {
                activity.getString(R.string.device_online)
            } else {
                activity.getString(R.string.device_offline)
            }
            item.deviceState.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (device.online) R.color.sage_600 else R.color.ink_500,
                ),
            )
            item.root.setOnClickListener {
                picked = true
                dialog.dismiss()
                onPick(device)
            }
            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = activity.resources.getDimensionPixelSize(R.dimen.device_row_gap)
            }
            binding.deviceContainer.addView(item.root, params)
        }

        dialog.setOnDismissListener {
            if (!picked) onDismissed()
        }
        dialog.show()
    }
}
