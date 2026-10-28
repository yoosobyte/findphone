package com.yoosobyte.findphone.data

data class Account(
    val id: Long,
    val displayName: String,
    val account: String,
    val password: String,
    val accountType: AccountType,
    val token: String?,
    val tokenTime: Long?,
    val createdAt: Long,
) {
    val isAuthorized: Boolean
        get() = !token.isNullOrBlank()
}
