package com.yoosobyte.findphone.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class OppoCryptoTest {

    /**
     * 这个值来自网页端真实的 `ctoken`：TOKENKEY 为
     * `ocloudwebc09a062f8a794255902f4166a34f239c` 时，请求体里的 ctoken
     * 是 1389707630。算法是网页端的 djb2 变体 + `& 0x7fffffff`。
     */
    @Test
    fun ctokenMatchesWebClient() {
        assertEquals(
            1389707630,
            OppoCrypto.ctoken("ocloudwebc09a062f8a794255902f4166a34f239c"),
        )
    }
}
