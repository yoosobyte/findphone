package com.yoosobyte.findphone.ui

import android.app.Activity
import android.database.sqlite.SQLiteConstraintException
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yoosobyte.findphone.R
import com.yoosobyte.findphone.data.Account
import com.yoosobyte.findphone.data.AccountRepository
import com.yoosobyte.findphone.data.AccountType
import com.yoosobyte.findphone.databinding.SheetAccountEditorBinding

class AccountEditorSheet(
    private val activity: Activity,
    private val repository: AccountRepository,
    private val existing: Account?,
    private val onSaved: (account: Account, isNew: Boolean, credentialsChanged: Boolean) -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = SheetAccountEditorBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)
        dialog.makeBackgroundTransparent()

        binding.editorTitle.setText(
            if (existing == null) R.string.editor_title_add else R.string.editor_title_edit,
        )
        binding.saveButton.setText(
            if (existing == null) R.string.action_save_and_auth else R.string.action_save,
        )
        binding.editorCloseButton.setOnClickListener { dialog.dismiss() }

        var selectedType = existing?.accountType ?: AccountType.OPPO_ONE_PLUS
        val typeLabels = AccountType.entries.map { activity.getString(it.labelRes) }
        binding.typeInput.setAdapter(
            ArrayAdapter(activity, R.layout.item_dropdown, typeLabels),
        )
        binding.typeInput.setText(activity.getString(selectedType.labelRes), false)
        binding.typeInput.setOnItemClickListener { _, _, position, _ ->
            selectedType = AccountType.entries.getOrElse(position) { AccountType.OPPO_ONE_PLUS }
        }

        existing?.let {
            binding.displayNameInput.setText(it.displayName)
            binding.accountInput.setText(it.account)
            binding.passwordInput.setText(it.password)
        }

        binding.saveButton.setOnClickListener { save(dialog, binding, selectedType) }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save(dialog, binding, selectedType)
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun save(
        dialog: BottomSheetDialog,
        binding: SheetAccountEditorBinding,
        selectedType: AccountType,
    ) {
        binding.displayNameLayout.error = null
        binding.accountLayout.error = null
        binding.passwordLayout.error = null

        val displayName = binding.displayNameInput.text?.toString()?.trim().orEmpty()
        val account = binding.accountInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        if (displayName.isEmpty()) {
            binding.displayNameLayout.error = activity.getString(
                R.string.field_required,
                activity.getString(R.string.field_display_name),
            )
            binding.displayNameInput.requestFocus()
            return
        }
        if (account.isEmpty()) {
            binding.accountLayout.error = activity.getString(
                R.string.field_required,
                activity.getString(R.string.field_account),
            )
            binding.accountInput.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.passwordLayout.error = activity.getString(
                R.string.field_required,
                activity.getString(R.string.field_password),
            )
            binding.passwordInput.requestFocus()
            return
        }
        if (repository.exists(account, existing?.id)) {
            binding.accountLayout.error = activity.getString(R.string.field_account_exists)
            binding.accountInput.requestFocus()
            return
        }

        binding.saveButton.isEnabled = false
        try {
            if (existing == null) {
                val saved = repository.insert(
                    displayName = displayName,
                    account = account,
                    password = password,
                    accountType = selectedType,
                )
                dialog.dismiss()
                onSaved(saved, true, false)
            } else {
                val credentialsChanged = existing.account != account ||
                    existing.password != password ||
                    existing.accountType != selectedType
                val token = if (credentialsChanged) null else existing.token
                val tokenTime = if (credentialsChanged) null else existing.tokenTime
                repository.update(
                    id = existing.id,
                    displayName = displayName,
                    account = account,
                    password = password,
                    accountType = selectedType,
                    token = token,
                    tokenTime = tokenTime,
                )
                val updated = existing.copy(
                    displayName = displayName,
                    account = account,
                    password = password,
                    accountType = selectedType,
                    token = token,
                    tokenTime = tokenTime,
                )
                dialog.dismiss()
                onSaved(updated, false, credentialsChanged)
            }
        } catch (_: SQLiteConstraintException) {
            binding.saveButton.isEnabled = true
            binding.accountLayout.error = activity.getString(R.string.field_account_exists)
        } catch (_: Exception) {
            binding.saveButton.isEnabled = true
            Toast.makeText(activity, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
