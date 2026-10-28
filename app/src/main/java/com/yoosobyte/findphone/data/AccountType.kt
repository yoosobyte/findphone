package com.yoosobyte.findphone.data

import androidx.annotation.StringRes
import com.yoosobyte.findphone.R

/**
 * 云服务账号类型。
 *
 * 当前 OPPO 和一加共用同一套云服务入口，所以界面上只有一个选项；保留枚举和数据库字段，
 * 后续若要单独接入其他品牌，只需要新增一个枚举值和一个下拉项。
 */
enum class AccountType(
    val storageValue: String,
    @param:StringRes @get:StringRes val labelRes: Int,
) {
    OPPO_ONE_PLUS("OPPO_ONE_PLUS", R.string.account_type_oppo_oneplus);

    companion object {
        fun fromStorage(value: String?): AccountType =
            entries.firstOrNull { it.storageValue == value } ?: OPPO_ONE_PLUS
    }
}
