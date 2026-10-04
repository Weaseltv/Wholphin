package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.SeerrQuickConnectException
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import org.junit.Assert
import org.junit.Test

/**
 * The reconnect policy exists to keep the request server's fail2ban jail
 * (five 401/403s in ten minutes bans the household) from ever being tripped by
 * one TV, while still letting the app recover from an outage on its own.
 */
class SeerrReconnectPolicyTest {
    private val fail2banWindowMs = 10L * 60L * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun `a 403 from the quick connect exchange is a refusal`() {
        Assert.assertTrue(
            SeerrReconnectPolicy.isRefusal(SeerrQuickConnectException("no", 403)),
        )
        Assert.assertTrue(
            SeerrReconnectPolicy.isRefusal(ClientException("no", 401)),
        )
    }

    @Test
    fun `a server that is down or times out is not a refusal`() {
        Assert.assertFalse(
            SeerrReconnectPolicy.isRefusal(SeerrQuickConnectException("timeout")),
        )
        Assert.assertFalse(
            SeerrReconnectPolicy.isRefusal(SeerrQuickConnectException("http", 502)),
        )
        Assert.assertFalse(SeerrReconnectPolicy.isRefusal(RuntimeException("x")))
    }

    @Test
    fun `first attempt is always allowed`() {
        Assert.assertTrue(SeerrReconnectPolicy.mayRetry(null, true, now))
    }

    @Test
    fun `a refusal is not retried inside the cooldown`() {
        Assert.assertFalse(SeerrReconnectPolicy.mayRetry(now - 1000L, true, now))
        Assert.assertTrue(
            SeerrReconnectPolicy.mayRetry(
                now - SeerrReconnectPolicy.REFUSAL_COOLDOWN_MS,
                true,
                now,
            ),
        )
    }

    @Test
    fun `an outage is retried after a short gap`() {
        Assert.assertFalse(SeerrReconnectPolicy.mayRetry(now - 1000L, false, now))
        Assert.assertTrue(
            SeerrReconnectPolicy.mayRetry(
                now - SeerrReconnectPolicy.FAILURE_COOLDOWN_MS,
                false,
                now,
            ),
        )
    }

    @Test
    fun `refusal cooldown outlasts the fail2ban window so one TV is one strike`() {
        Assert.assertTrue(SeerrReconnectPolicy.REFUSAL_COOLDOWN_MS > fail2banWindowMs)
    }
}
