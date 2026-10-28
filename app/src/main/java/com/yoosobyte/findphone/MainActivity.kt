package com.yoosobyte.findphone

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yoosobyte.findphone.cloud.CloudAuthController
import com.yoosobyte.findphone.cloud.CloudDevice
import com.yoosobyte.findphone.cloud.CloudErrorType
import com.yoosobyte.findphone.cloud.CloudResult
import com.yoosobyte.findphone.cloud.OppoCloudClient
import com.yoosobyte.findphone.cloud.OppoSession
import com.yoosobyte.findphone.data.Account
import com.yoosobyte.findphone.data.AccountRepository
import com.yoosobyte.findphone.data.FindPhoneDatabase
import com.yoosobyte.findphone.databinding.ActivityMainBinding
import com.yoosobyte.findphone.ui.AccountActionsSheet
import com.yoosobyte.findphone.ui.AccountAdapter
import com.yoosobyte.findphone.ui.AccountEditorSheet
import com.yoosobyte.findphone.ui.DevicePickerSheet
import com.yoosobyte.findphone.ui.GridSpacingItemDecoration
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: FindPhoneDatabase
    private lateinit var repository: AccountRepository
    private lateinit var adapter: AccountAdapter
    private lateinit var authController: CloudAuthController

    private val cloudClient = OppoCloudClient()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var accounts: List<Account> = emptyList()
    private var pendingRingAccountId: Long? = null
    private var ringRequestInProgress = false
    private var executingDialog: AlertDialog? = null

    private val relativeTimeTicker = object : Runnable {
        override fun run() {
            adapter.refreshRelativeTimes()
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    private val executingDialogTimeout = Runnable { dismissExecutingDialog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.versionText.text = getString(
            R.string.app_version_format,
            BuildConfig.VERSION_NAME,
        )

        database = FindPhoneDatabase(this)
        repository = AccountRepository(database)
        adapter = AccountAdapter(
            onManage = ::showAccountActions,
            onRing = ::onBellClicked,
        )
        binding.accountList.layoutManager = GridLayoutManager(this, 2)
        binding.accountList.adapter = adapter
        binding.accountList.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount = 2,
                spacing = resources.getDimensionPixelSize(R.dimen.grid_gap),
            ),
        )

        binding.addButton.setOnClickListener { showAddAccount() }
        binding.emptyAddButton.setOnClickListener { showAddAccount() }

        authController = CloudAuthController(
            activity = this,
            binding = binding,
            onAuthorized = ::onAuthorized,
            onDismissed = ::onAuthDismissed,
            onAutomationStarted = {},
            onAutomationFinished = { dismissExecutingDialog() },
            onPageError = { dismissExecutingDialog() },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authController.isShowing) {
                    authController.close()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadAccounts()
    }

    override fun onStart() {
        super.onStart()
        scheduleRelativeTimeRefresh()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(relativeTimeTicker)
        super.onStop()
    }

    private fun scheduleRelativeTimeRefresh() {
        mainHandler.removeCallbacks(relativeTimeTicker)
        adapter.refreshRelativeTimes()
        val delay = 60_000L - System.currentTimeMillis() % 60_000L
        mainHandler.postDelayed(relativeTimeTicker, delay)
    }

    override fun onDestroy() {
        dismissExecutingDialog()
        mainHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        if (::authController.isInitialized) {
            authController.destroy()
        }
        if (::database.isInitialized) {
            database.close()
        }
        super.onDestroy()
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val nightMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !nightMode
            isAppearanceLightNavigationBars = !nightMode
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            binding.mainContent.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            binding.authOverlay.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            windowInsets
        }
    }

    private fun loadAccounts() {
        accounts = repository.getAll()
        adapter.submit(accounts)
        val isEmpty = accounts.isEmpty()
        binding.emptyState.isVisible = isEmpty
        binding.accountList.isVisible = !isEmpty

        val authorizedCount = accounts.count { it.isAuthorized }
        binding.subtitleText.text = when {
            isEmpty -> getString(R.string.subtitle_empty)
            authorizedCount == 0 -> getString(
                R.string.subtitle_accounts_no_auth,
                accounts.size,
            )
            else -> getString(
                R.string.subtitle_accounts,
                accounts.size,
                authorizedCount,
            )
        }
    }

    private fun showAddAccount() {
        if (authController.isShowing) return
        AccountEditorSheet(
            activity = this,
            repository = repository,
            existing = null,
            onSaved = { account, isNew, _ ->
                loadAccounts()
                if (isNew) {
                    binding.root.post {
                        pendingRingAccountId = null
                        startAuthorization(account)
                    }
                }
            },
        ).show()
    }

    private fun showAccountActions(account: Account) {
        if (authController.isShowing) return
        AccountActionsSheet(
            activity = this,
            account = account,
            listener = object : AccountActionsSheet.Listener {
                override fun onRelogin() {
                    val latest = repository.findById(account.id) ?: return
                    pendingRingAccountId = null
                    startAuthorization(latest)
                }

                override fun onEdit() {
                    val latest = repository.findById(account.id) ?: return
                    AccountEditorSheet(
                        activity = this@MainActivity,
                        repository = repository,
                        existing = latest,
                        onSaved = { _, _, credentialsChanged ->
                            loadAccounts()
                            showMessage(
                                getString(
                                    if (credentialsChanged && latest.isAuthorized) {
                                        R.string.account_updated_need_auth
                                    } else {
                                        R.string.account_updated
                                    },
                                ),
                            )
                        },
                    ).show()
                }

                override fun onDelete() {
                    confirmDelete(account)
                }
            },
        ).show()
    }

    private fun confirmDelete(account: Account) {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Findphone_Dialog)
            .setTitle(getString(R.string.delete_title, account.displayName))
            .setMessage(R.string.delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                repository.delete(account.id)
                if (pendingRingAccountId == account.id) {
                    pendingRingAccountId = null
                }
                loadAccounts()
                showMessage(getString(R.string.delete_success, account.displayName))
            }
            .show()
    }

    private fun startAuthorization(account: Account) {
        showExecutingDialog()
        authController.start(account)
    }

    private fun onBellClicked(account: Account) {
        if (ringRequestInProgress || authController.isShowing) return
        if (!account.isAuthorized) {
            pendingRingAccountId = account.id
            startAuthorization(account)
            return
        }
        performRing(account)
    }

    private fun performRing(account: Account) {
        val latest = repository.findById(account.id) ?: return
        val session = OppoSession.fromStorage(latest.token)
        if (session == null) {
            repository.clearToken(latest.id)
            loadAccounts()
            pendingRingAccountId = latest.id
            startAuthorization(latest)
            return
        }

        setRingBusy(true)
        ioExecutor.execute {
            val result = try {
                cloudClient.getDevices(session)
            } catch (error: Exception) {
                CloudResult.Failure(CloudErrorType.UNKNOWN, error.message)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                persistSession(latest.id, session)
                when (result) {
                    is CloudResult.Success -> {
                        val devices = result.value
                        if (devices.size > 1) {
                            showDevicePicker(latest, devices)
                        } else {
                            sendRing(latest, devices.first())
                        }
                    }
                    is CloudResult.Failure -> {
                        setRingBusy(false)
                        handleCloudFailure(latest, result)
                    }
                }
            }
        }
    }

    private fun showDevicePicker(
        account: Account,
        devices: List<CloudDevice>,
    ) {
        DevicePickerSheet(
            activity = this,
            devices = devices,
            onPick = { device -> sendRing(account, device) },
            onDismissed = { setRingBusy(false) },
        ).show()
    }

    private fun sendRing(account: Account, device: CloudDevice) {
        val session = OppoSession.fromStorage(repository.findById(account.id)?.token)
        if (session == null) {
            setRingBusy(false)
            repository.clearToken(account.id)
            loadAccounts()
            pendingRingAccountId = account.id
            startAuthorization(account)
            return
        }
        ioExecutor.execute {
            val result = try {
                cloudClient.sendRing(session, device)
            } catch (error: Exception) {
                CloudResult.Failure(CloudErrorType.UNKNOWN, error.message)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                setRingBusy(false)
                persistSession(account.id, session)
                when (result) {
                    is CloudResult.Success -> {
                        adapter.animateBell(account.id)
                        showMessage(getString(R.string.ring_sent, account.displayName))
                    }
                    is CloudResult.Failure -> handleCloudFailure(account, result)
                }
            }
        }
    }

    private fun persistSession(accountId: Long, session: OppoSession) {
        val existingTime = repository.findById(accountId)?.tokenTime
        repository.updateToken(
            accountId,
            session.toStorage(),
            existingTime ?: System.currentTimeMillis(),
        )
    }

    private fun showExecutingDialog() {
        if (isFinishing || isDestroyed) return
        if (executingDialog?.isShowing == true) return

        executingDialog?.dismiss()

        executingDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Findphone_Dialog)
            .setTitle("提示")
            .setMessage(R.string.executing_message)
            .setPositiveButton(R.string.executing_confirm, null)
            .setCancelable(true)
            .setOnDismissListener {
                mainHandler.removeCallbacks(executingDialogTimeout)
            }
            .show()

        mainHandler.removeCallbacks(executingDialogTimeout)
        mainHandler.postDelayed(executingDialogTimeout, 20_000L)
    }

    private fun dismissExecutingDialog() {
        mainHandler.removeCallbacks(executingDialogTimeout)
        val dialog = executingDialog ?: return
        executingDialog = null
        if (dialog.isShowing) {
            try {
                dialog.dismiss()
            } catch (_: Exception) {
                // Activity 已经销毁时忽略。
            }
        }
    }

    private fun setRingBusy(busy: Boolean) {
        ringRequestInProgress = busy
        adapter.setBellsEnabled(!busy)
    }

    private fun handleCloudFailure(
        account: Account,
        failure: CloudResult.Failure,
    ) {
        if (failure.type == CloudErrorType.AUTH_EXPIRED) {
            repository.clearToken(account.id)
            loadAccounts()
            val latest = repository.findById(account.id)
            if (latest != null) {
                pendingRingAccountId = latest.id
                Toast.makeText(this, R.string.ring_auth_expired, Toast.LENGTH_SHORT).show()
                startAuthorization(latest)
            }
            return
        }

        val message = when (failure.type) {
            CloudErrorType.NETWORK ->
                getString(R.string.ring_network)

            CloudErrorType.FIND_PHONE_DISABLED ->
                getString(R.string.ring_find_phone_off)

            CloudErrorType.NO_DEVICE ->
                getString(R.string.ring_no_device)

            CloudErrorType.NEED_VERIFY ->
                getString(R.string.ring_need_verify)

            CloudErrorType.SERVER,
            CloudErrorType.UNKNOWN ->
                failure.serverMessage
                    ?.takeIf { message -> message.isNotBlank() && message.length <= 80 && message.any { it.isCjk() } }
                    ?: getString(R.string.ring_server)

            CloudErrorType.AUTH_EXPIRED ->
                getString(R.string.ring_auth_expired)
        }
        showMessage(getString(R.string.ring_failed, message))
    }

    private fun onAuthorized(accountId: Long, session: OppoSession) {
        dismissExecutingDialog()
        repository.updateToken(accountId, session.toStorage(), System.currentTimeMillis())
        val shouldRing = pendingRingAccountId == accountId
        pendingRingAccountId = null
        loadAccounts()
        val updated = repository.findById(accountId) ?: return
        if (shouldRing) {
            performRing(updated)
        } else {
            showMessage(getString(R.string.auth_success))
            // 顺便建立查找手机会话，减少第一次点铃铛时的 8012 往返。
            ioExecutor.execute {
                val warmUp = cloudClient.warmUp(session)
                if (warmUp is CloudResult.Success) {
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        persistSession(accountId, session)
                        loadAccounts()
                    }
                }
            }
        }
    }

    private fun onAuthDismissed() {
        dismissExecutingDialog()
        pendingRingAccountId = null
        showMessage(getString(R.string.auth_dismissed_hint))
    }

    private fun Char.isCjk(): Boolean = this in '\u4E00'..'\u9FFF'

    private fun showMessage(
        message: String,
        short: Boolean = false,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        if (isFinishing || isDestroyed) return
        val snackbar = Snackbar.make(
            binding.mainContent,
            message,
            if (short) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG,
        )
        snackbar.setBackgroundTint(
            ContextCompat.getColor(this, R.color.snackbar_background),
        )
        snackbar.setTextColor(ContextCompat.getColor(this, R.color.snackbar_text))
        snackbar.setActionTextColor(ContextCompat.getColor(this, R.color.snackbar_action))
        if (actionLabel != null && action != null) {
            snackbar.setAction(actionLabel) { action() }
        }
        snackbar.show()
    }
}
