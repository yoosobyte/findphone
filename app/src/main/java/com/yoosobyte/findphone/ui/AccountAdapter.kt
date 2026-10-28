package com.yoosobyte.findphone.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.yoosobyte.findphone.R
import com.yoosobyte.findphone.data.Account
import com.yoosobyte.findphone.databinding.ItemAccountBinding
import com.yoosobyte.findphone.util.authorizationStatus
import com.yoosobyte.findphone.util.avatarInitial
import com.yoosobyte.findphone.util.maskAccount
import com.yoosobyte.findphone.util.shortRelativeTime
import kotlin.math.sin

class AccountAdapter(
    private val onManage: (Account) -> Unit,
    private val onRing: (Account) -> Unit,
) : RecyclerView.Adapter<AccountAdapter.AccountViewHolder>() {

    private var items: List<Account> = emptyList()
    private var bellsEnabled: Boolean = true
    private lateinit var recyclerView: RecyclerView

    init {
        setHasStableIds(true)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemCount(): Int = items.size

    fun submit(items: List<Account>) {
        val previous = this.items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size

            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].id == items[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == items[newItemPosition]
        })
        this.items = items
        diff.dispatchUpdatesTo(this)
    }

    fun setBellsEnabled(enabled: Boolean) {
        if (bellsEnabled == enabled) return
        bellsEnabled = enabled
        for (position in items.indices) {
            notifyItemChanged(position, PAYLOAD_BELL_ENABLED)
        }
    }

    fun animateBell(accountId: Long) {
        if (!::recyclerView.isInitialized) return
        val holder = recyclerView.findViewHolderForItemId(accountId) as? AccountViewHolder ?: return
        holder.playBellAnimation()
    }

    /** 只刷新当前可见卡片的相对时间，不重新读数据库、不重建列表。 */
    fun refreshRelativeTimes() {
        if (!::recyclerView.isInitialized) return
        for (position in items.indices) {
            val holder = recyclerView.findViewHolderForAdapterPosition(position) as? AccountViewHolder
                ?: continue
            holder.refreshStatus(items[position])
        }
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AccountViewHolder {
        val binding = ItemAccountBinding.inflate(
            android.view.LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return AccountViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: AccountViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_BELL_ENABLED)) {
            holder.updateBellEnabled(bellsEnabled)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: AccountViewHolder) {
        holder.stopBellAnimation()
        super.onViewRecycled(holder)
    }

    inner class AccountViewHolder(
        private val binding: ItemAccountBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var bellAnimator: ValueAnimator? = null
        private var account: Account? = null
        private var cardPalette: CardPalette? = null

        init {
            binding.root.setOnClickListener {
                account?.let(onManage)
            }
            binding.bellButton.setOnClickListener {
                account?.let(onRing)
            }
        }

        fun bind(account: Account) {
            this.account = account
            stopBellAnimation()

            val context = binding.root.context
            val palette = CardPalettes.forAccount(context, account.id)
            cardPalette = palette

            binding.accountCard.setCardBackgroundColor(palette.background)
            binding.accountCard.scaleX = 1f
            binding.accountCard.scaleY = 1f
            binding.avatarText.backgroundTintList = ColorStateList.valueOf(palette.accent)
            binding.avatarText.setTextColor(palette.onAccent)
            binding.avatarText.text = avatarInitial(account.displayName)
            binding.nameText.text = account.displayName
            binding.nameText.setTextColor(palette.primaryText)
            binding.accountText.text = maskAccount(account.account)
            binding.accountText.setTextColor(palette.secondaryText)
            binding.typeBadge.text = context.getString(account.accountType.labelRes)
            binding.typeBadge.setTextColor(palette.primaryText)
            refreshStatus(account)

            binding.bellButton.imageTintList = ColorStateList.valueOf(palette.accent)
            binding.bellButton.contentDescription = context.getString(
                R.string.content_desc_ring_account,
                account.displayName,
            )
            binding.root.contentDescription = context.getString(
                R.string.content_desc_manage_account,
                account.displayName,
            )
            updateBellEnabled(bellsEnabled)
        }

        fun refreshStatus(account: Account) {
            val palette = cardPalette ?: return
            val context = binding.root.context
            val authorized = account.isAuthorized
            val visualStatus = if (authorized) {
                account.tokenTime?.let { shortRelativeTime(context, it) }
                    ?: context.getString(R.string.status_authorized)
            } else {
                context.getString(R.string.status_unauthorized)
            }
            binding.statusText.text = context.getString(R.string.card_status_format, visualStatus)
            binding.statusText.setTextColor(
                if (authorized) {
                    palette.accent
                } else {
                    ContextCompat.getColor(context, R.color.coral_700)
                },
            )
            binding.statusText.contentDescription = authorizationStatus(
                context,
                authorized,
                account.tokenTime,
            )
        }

        fun updateBellEnabled(enabled: Boolean) {
            binding.bellButton.isEnabled = enabled
            binding.bellButton.alpha = if (enabled) 1f else 0.48f
        }

        fun playBellAnimation() {
            stopBellAnimation()
            binding.bellButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BELL_SHAKE_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { valueAnimator ->
                    val progress = valueAnimator.animatedFraction
                    val angle = sin(progress * Math.PI * 2.0 * BELL_SHAKE_CYCLES).toFloat() *
                        15f * (1f - progress * 0.35f)
                    binding.bellButton.rotation = angle
                    val scale = 1f + 0.05f * kotlin.math.abs(
                        sin(progress * Math.PI * 2.0 * BELL_SHAKE_CYCLES).toFloat(),
                    )
                    binding.bellButton.scaleX = scale
                    binding.bellButton.scaleY = scale
                }
            }
            animator.doOnEnd {
                binding.bellButton.rotation = 0f
                binding.bellButton.scaleX = 1f
                binding.bellButton.scaleY = 1f
            }
            bellAnimator = animator
            animator.start()
        }

        fun stopBellAnimation() {
            bellAnimator?.cancel()
            bellAnimator = null
            binding.bellButton.rotation = 0f
            binding.bellButton.scaleX = 1f
            binding.bellButton.scaleY = 1f
        }
    }

    companion object {
        private const val PAYLOAD_BELL_ENABLED = "bell_enabled"
        private const val BELL_SHAKE_DURATION_MS = 3_000L
        private const val BELL_SHAKE_CYCLES = 6.0
    }
}

