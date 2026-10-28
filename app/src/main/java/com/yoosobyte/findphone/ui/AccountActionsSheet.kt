package com.yoosobyte.findphone.ui

import android.app.Activity
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yoosobyte.findphone.R
import com.yoosobyte.findphone.data.Account
import com.yoosobyte.findphone.databinding.SheetAccountActionsBinding
import com.yoosobyte.findphone.util.authorizationStatus
import com.yoosobyte.findphone.util.avatarInitial
import com.yoosobyte.findphone.util.maskAccount

class AccountActionsSheet(
    private val activity: Activity,
    private val account: Account,
    private val listener: Listener,
) {

    interface Listener {
        fun onRelogin()
        fun onEdit()
        fun onDelete()
    }

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = SheetAccountActionsBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)
        dialog.makeBackgroundTransparent()

        val palette = CardPalettes.forAccount(activity, account.id)
        binding.actionsAvatar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(palette.accent)
        binding.actionsAvatar.setTextColor(palette.onAccent)
        binding.actionsAvatar.text = avatarInitial(account.displayName)
        binding.actionsName.text = account.displayName
        binding.actionsAccount.text = activity.getString(
            R.string.actions_account_format,
            activity.getString(account.accountType.labelRes),
            maskAccount(account.account),
        )
        binding.actionsStatus.text = authorizationStatus(
            activity,
            account.isAuthorized,
            account.tokenTime,
        )
        binding.actionsStatus.setTextColor(
            if (account.isAuthorized) {
                palette.accent
            } else {
                androidx.core.content.ContextCompat.getColor(activity, R.color.coral_700)
            },
        )

        binding.actionRelogin.setOnClickListener {
            dialog.dismiss()
            listener.onRelogin()
        }
        binding.actionEdit.setOnClickListener {
            dialog.dismiss()
            listener.onEdit()
        }
        binding.actionDelete.setOnClickListener {
            dialog.dismiss()
            listener.onDelete()
        }

        dialog.show()
    }
}

