package com.yoosobyte.findphone.util

import android.content.Context
import com.yoosobyte.findphone.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

fun maskAccount(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""

    if (value.contains("@")) {
        val local = value.substringBefore("@")
        val domain = value.substringAfter("@")
        val visible = when {
            local.length >= 2 -> local.take(2)
            else -> local.take(1)
        }
        return "$visible***@$domain"
    }

    var digits = value.filter { it.isDigit() }
    if (digits.length > 11 && digits.startsWith("86")) {
        digits = digits.drop(2)
    }
    if (digits.length >= 7) {
        return "${digits.take(3)}****${digits.takeLast(4)}"
    }
    if (value.length > 4) {
        return "${value.take(1)}***${value.takeLast(2)}"
    }
    return value
}

fun shortRelativeTime(context: Context, timestamp: Long?, now: Long = System.currentTimeMillis()): String {
    if (timestamp == null || timestamp <= 0L) return context.getString(R.string.status_unauthorized)
    val diff = max(0L, now - timestamp)
    return when {
        diff < 60_000L -> context.getString(R.string.status_time_just_now)
        diff < 3_600_000L -> context.getString(
            R.string.status_time_minutes,
            (diff / 60_000L).toInt(),
        )
        diff < 86_400_000L -> context.getString(
            R.string.status_time_hours,
            (diff / 3_600_000L).toInt(),
        )
        diff < 7 * 86_400_000L -> context.getString(
            R.string.status_time_days,
            (diff / 86_400_000L).toInt(),
        )
        else -> context.getString(
            R.string.status_time_date,
            SimpleDateFormat("M月d日", Locale.CHINA).format(Date(timestamp)),
        )
    }
}

fun authorizationStatus(context: Context, isAuthorized: Boolean, tokenTime: Long?): String {
    if (!isAuthorized) return context.getString(R.string.status_unauthorized)
    return if (tokenTime != null) {
        context.getString(R.string.status_authorized_ago, shortRelativeTime(context, tokenTime))
    } else {
        context.getString(R.string.status_authorized)
    }
}

fun avatarInitial(displayName: String): String {
    val first = displayName.trim().firstOrNull() ?: return "手"
    return first.toString().uppercase(Locale.getDefault())
}
