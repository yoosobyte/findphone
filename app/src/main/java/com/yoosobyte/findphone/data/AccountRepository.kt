package com.yoosobyte.findphone.data

import android.content.ContentValues
import android.database.Cursor

class AccountRepository(
    private val database: FindPhoneDatabase,
) {

    fun getAll(): List<Account> {
        database.readableDatabase.query(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            FindPhoneDatabase.ALL_COLUMNS,
            null,
            null,
            null,
            null,
            "${FindPhoneDatabase.COL_CREATED_AT} ASC, ${FindPhoneDatabase.COL_ID} ASC",
        ).use { cursor ->
            val result = ArrayList<Account>(cursor.count)
            while (cursor.moveToNext()) {
                result += cursor.toAccount()
            }
            return result
        }
    }

    fun findById(id: Long): Account? {
        database.readableDatabase.query(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            FindPhoneDatabase.ALL_COLUMNS,
            "${FindPhoneDatabase.COL_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toAccount() else null
        }
    }

    fun exists(account: String, excludeId: Long? = null): Boolean {
        val selection = if (excludeId == null) {
            "${FindPhoneDatabase.COL_ACCOUNT} = ? COLLATE NOCASE"
        } else {
            "${FindPhoneDatabase.COL_ACCOUNT} = ? COLLATE NOCASE AND ${FindPhoneDatabase.COL_ID} != ?"
        }
        val args = if (excludeId == null) {
            arrayOf(account)
        } else {
            arrayOf(account, excludeId.toString())
        }
        database.readableDatabase.query(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            arrayOf(FindPhoneDatabase.COL_ID),
            selection,
            args,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun insert(
        displayName: String,
        account: String,
        password: String,
        accountType: AccountType,
    ): Account {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(FindPhoneDatabase.COL_DISPLAY_NAME, displayName)
            put(FindPhoneDatabase.COL_ACCOUNT, account)
            put(FindPhoneDatabase.COL_PASSWORD, password)
            put(FindPhoneDatabase.COL_ACCOUNT_TYPE, accountType.storageValue)
            putNull(FindPhoneDatabase.COL_TOKEN)
            putNull(FindPhoneDatabase.COL_TOKEN_TIME)
            put(FindPhoneDatabase.COL_CREATED_AT, now)
            put(FindPhoneDatabase.COL_UPDATED_AT, now)
        }
        val id = database.writableDatabase.insertOrThrow(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            null,
            values,
        )
        return requireNotNull(findById(id))
    }

    fun update(
        id: Long,
        displayName: String,
        account: String,
        password: String,
        accountType: AccountType,
        token: String?,
        tokenTime: Long?,
    ): Boolean {
        val values = ContentValues().apply {
            put(FindPhoneDatabase.COL_DISPLAY_NAME, displayName)
            put(FindPhoneDatabase.COL_ACCOUNT, account)
            put(FindPhoneDatabase.COL_PASSWORD, password)
            put(FindPhoneDatabase.COL_ACCOUNT_TYPE, accountType.storageValue)
            if (token == null) putNull(FindPhoneDatabase.COL_TOKEN) else put(FindPhoneDatabase.COL_TOKEN, token)
            if (tokenTime == null) putNull(FindPhoneDatabase.COL_TOKEN_TIME) else put(FindPhoneDatabase.COL_TOKEN_TIME, tokenTime)
            put(FindPhoneDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        return database.writableDatabase.update(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            values,
            "${FindPhoneDatabase.COL_ID} = ?",
            arrayOf(id.toString()),
        ) > 0
    }

    fun updateToken(id: Long, token: String, tokenTime: Long) {
        val values = ContentValues().apply {
            put(FindPhoneDatabase.COL_TOKEN, token)
            put(FindPhoneDatabase.COL_TOKEN_TIME, tokenTime)
            put(FindPhoneDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        database.writableDatabase.update(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            values,
            "${FindPhoneDatabase.COL_ID} = ?",
            arrayOf(id.toString()),
        )
    }

    fun clearToken(id: Long) {
        val values = ContentValues().apply {
            putNull(FindPhoneDatabase.COL_TOKEN)
            putNull(FindPhoneDatabase.COL_TOKEN_TIME)
            put(FindPhoneDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        database.writableDatabase.update(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            values,
            "${FindPhoneDatabase.COL_ID} = ?",
            arrayOf(id.toString()),
        )
    }

    fun delete(id: Long) {
        database.writableDatabase.delete(
            FindPhoneDatabase.TABLE_ACCOUNTS,
            "${FindPhoneDatabase.COL_ID} = ?",
            arrayOf(id.toString()),
        )
    }
}

private fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.toAccount(): Account = Account(
    id = getLong(getColumnIndexOrThrow(FindPhoneDatabase.COL_ID)),
    displayName = getString(getColumnIndexOrThrow(FindPhoneDatabase.COL_DISPLAY_NAME)),
    account = getString(getColumnIndexOrThrow(FindPhoneDatabase.COL_ACCOUNT)),
    password = getString(getColumnIndexOrThrow(FindPhoneDatabase.COL_PASSWORD)),
    accountType = AccountType.fromStorage(
        getString(getColumnIndexOrThrow(FindPhoneDatabase.COL_ACCOUNT_TYPE)),
    ),
    token = getStringOrNull(FindPhoneDatabase.COL_TOKEN),
    tokenTime = getLongOrNull(FindPhoneDatabase.COL_TOKEN_TIME),
    createdAt = getLong(getColumnIndexOrThrow(FindPhoneDatabase.COL_CREATED_AT)),
)
