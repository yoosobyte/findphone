package com.yoosobyte.findphone.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 整个应用只有一张业务表 accounts。
 * 账号、密码、账号类型、云端 Token、Token 获取时间和显示名称都存在这里。
 */
class FindPhoneDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ACCOUNTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_ACCOUNT TEXT NOT NULL COLLATE NOCASE UNIQUE,
                $COL_PASSWORD TEXT NOT NULL,
                $COL_ACCOUNT_TYPE TEXT NOT NULL,
                $COL_TOKEN TEXT,
                $COL_TOKEN_TIME INTEGER,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有 v1，保留入口以便后续做无损迁移。
    }

    companion object {
        private const val DATABASE_NAME = "findphone.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_ACCOUNTS = "accounts"
        const val COL_ID = "id"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_ACCOUNT = "account"
        const val COL_PASSWORD = "password"
        const val COL_ACCOUNT_TYPE = "account_type"
        const val COL_TOKEN = "token"
        const val COL_TOKEN_TIME = "token_time"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"

        val ALL_COLUMNS = arrayOf(
            COL_ID,
            COL_DISPLAY_NAME,
            COL_ACCOUNT,
            COL_PASSWORD,
            COL_ACCOUNT_TYPE,
            COL_TOKEN,
            COL_TOKEN_TIME,
            COL_CREATED_AT,
        )
    }
}
