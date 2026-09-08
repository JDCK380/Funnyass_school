package com.funnyass.test

import com.funnyass.test.net.ApiClient
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun authInvalidResponse_notifiesListener() {
        var reason: String? = null
        val listener: (String) -> Unit = { reason = it }
        ApiClient.setAuthInvalidListener(listener)
        try {
            ApiClient.parse("""{"success":false,"errorCode":10000,"errorMessage":"账号已失效"}""", Any::class.java)
            assertEquals("账号已失效", reason)
        } finally {
            ApiClient.clearAuthInvalidListener(listener)
        }
    }

    @Test
    fun normalResponse_doesNotNotifyAuthListener() {
        var notified = false
        val listener: (String) -> Unit = { notified = true }
        ApiClient.setAuthInvalidListener(listener)
        try {
            ApiClient.parse("""{"success":true,"errorCode":0,"data":{}}""", Any::class.java)
            assertFalse(notified)
        } finally {
            ApiClient.clearAuthInvalidListener(listener)
        }
    }
}
