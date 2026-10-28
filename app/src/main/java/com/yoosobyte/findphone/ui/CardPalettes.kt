package com.yoosobyte.findphone.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.yoosobyte.findphone.R
import kotlin.math.absoluteValue

data class CardPalette(
    val background: Int,
    val accent: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val onAccent: Int,
)

object CardPalettes {

    private val accents = listOf(
        R.color.coral_700,
        R.color.sage_600,
        R.color.blue_600,
        R.color.plum_600,
        R.color.gold_600,
        R.color.rose_600,
    )

    fun forAccount(context: Context, accountId: Long): CardPalette {
        val accent = accents[(accountId % accents.size).toInt().absoluteValue]
        return CardPalette(
            background = ContextCompat.getColor(context, R.color.card_surface),
            accent = ContextCompat.getColor(context, accent),
            primaryText = ContextCompat.getColor(context, R.color.ink_900),
            secondaryText = ContextCompat.getColor(context, R.color.ink_700),
            onAccent = ContextCompat.getColor(context, R.color.avatar_on_accent),
        )
    }
}

