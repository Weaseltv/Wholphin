package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.SeerrQuickConnect
import com.github.damontecres.wholphin.api.seerr.SeerrQuickConnectException
import com.github.damontecres.wholphin.api.seerr.UsersApi
import com.github.damontecres.wholphin.api.seerr.model.User
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Covers the two guarantees the owner required of the silent Quick Connect flow:
 *
 *  1. the app only ever approves the code IT initiated in that same exchange
 *  2. it fails into a clear error rather than hanging when Quick Connect is off
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeerrQuickConnectLoginTest {
    private val seerrUser = mockk<User>(relaxed = true)

    private fun clientReturning(
        code: String,
        secret: String,
    ): SeerrApiClient {
        val usersApi = mockk<UsersApi>()
        coEvery { usersApi.authMeGet() } returns seerrUser
        return mockk<SeerrApiClient>(relaxed = true).also {
            every { it.quickConnectInitiate() } returns SeerrQuickConnect(code, secret)
            every { it.usersApi } returns usersApi
        }
    }

    /**
     * THE security property. If the app could be induced to approve an arbitrary code,
     * an attacker could start their own Quick Connect against Seerr and have a
     * customer's TV silently approve it, handing over that customer's account.
     */
    @Test
    fun `only ever authorizes the code it was itself issued`() =
        runTest {
            val client = clientReturning(code = "SERVER-ISSUED-123", secret = "s3cr3t")
            val approved = mutableListOf<String>()

            seerrQuickConnectLogin(client) { code ->
                approved.add(code)
                true
            }

            Assert.assertEquals(listOf("SERVER-ISSUED-123"), approved)
        }

    @Test
    fun `never authenticates with a secret whose code was not approved`() =
        runTest {
            val client = clientReturning(code = "C", secret = "s")

            val ex =
                Assert.assertThrows(SeerrQuickConnectException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        seerrQuickConnectLogin(client) { false }
                    }
                }

            Assert.assertTrue(ex.message!!.isNotBlank())
            // The secret must never be presented once approval failed.
            verify(exactly = 0) { client.quickConnectAuthenticate(any()) }
        }

    /**
     * Quick Connect disabled server-side: Seerr answers 403 and the client raises a
     * customer-readable error. It must surface, not be swallowed into a spinner.
     */
    @Test
    fun `surfaces a clear error when quick connect is disabled server side`() =
        runTest {
            val client = mockk<SeerrApiClient>(relaxed = true)
            every { client.quickConnectInitiate() } throws
                SeerrQuickConnectException("Quick Connect is not enabled on the media server.")

            val ex =
                Assert.assertThrows(SeerrQuickConnectException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        seerrQuickConnectLogin(client) { true }
                    }
                }

            Assert.assertEquals(
                "Quick Connect is not enabled on the media server.",
                ex.message,
            )
            verify(exactly = 0) { client.quickConnectAuthenticate(any()) }
        }

    /**
     * A server that accepts the connection but never answers must not hang a TV on a
     * spinner forever. runTest uses virtual time, so this completes instantly.
     */
    @Test
    fun `times out instead of hanging when the server never responds`() =
        runTest {
            val client = clientReturning(code = "C", secret = "s")

            val ex =
                Assert.assertThrows(SeerrQuickConnectException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        seerrQuickConnectLogin(client) {
                            delay(10 * 60 * 1000L) // far beyond the flow's budget
                            true
                        }
                    }
                }

            Assert.assertTrue(
                "message should be readable on a TV, was: ${ex.message}",
                ex.message!!.contains("did not respond"),
            )
        }

    @Test
    fun `rejects an incomplete response from the server`() =
        runTest {
            val client = clientReturning(code = "", secret = "")

            Assert.assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    seerrQuickConnectLogin(client) { true }
                }
            }
        }
}
