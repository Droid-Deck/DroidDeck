package com.droiddeck.launcher.stores

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopbackListenerTest {
    private val outcomes = CopyOnWriteArrayList<LoopbackListener.Outcome>()
    private val settled = CountDownLatch(1)
    private var listener: LoopbackListener? = null
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @After fun stop() { listener?.close() }

    private fun listen(timeoutMs: Long = 30_000): LoopbackListener =
        LoopbackListener("state-1", "<p>done</p>", timeoutMs) { outcomes.add(it); settled.countDown() }
            .also { it.start("/launcher/authorized"); listener = it }

    private fun get(port: Int, target: String, host: String = "localhost:$port"): Pair<Int, String> =
        Socket(loopback, port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            text.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt() to text
        }

    @Test fun a_matching_callback_hands_over_the_code_once() {
        val l = listen()
        val (status, text) = get(l.port, "/launcher/authorized?code=abc123&state=state-1")
        assertEquals(200, status)
        assertTrue(text.contains("<p>done</p>"))
        assertTrue(text.contains("Cache-Control: no-store"))
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals("abc123", (outcomes.single() as LoopbackListener.Outcome.Code).code)
        assertTrue(runCatching { get(l.port, "/launcher/authorized?code=other&state=state-1") }.isFailure)
        assertEquals(1, outcomes.size)
    }

    @Test fun a_wrong_or_missing_state_is_refused_and_the_sign_in_stays_open() {
        val l = listen()
        assertEquals(400, get(l.port, "/launcher/authorized?code=x&state=nope").first)
        assertEquals(400, get(l.port, "/launcher/authorized?code=x").first)
        assertEquals(400, get(l.port, "/launcher/authorized?code=x&state=state-1x").first)
        assertTrue(outcomes.isEmpty())
        assertEquals(200, get(l.port, "/launcher/authorized?code=real&state=state-1").first)
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals("real", (outcomes.single() as LoopbackListener.Outcome.Code).code)
    }

    @Test fun other_hosts_paths_and_methods_are_refused() {
        val l = listen()
        assertEquals(400, get(l.port, "/launcher/authorized?code=x&state=state-1", host = "evil.example:${l.port}").first)
        assertEquals(400, get(l.port, "/launcher/authorized?code=x&state=state-1", host = "localhost").first)
        assertEquals(400, get(l.port, "/favicon.ico").first)
        assertEquals(400, get(l.port, "/launcher/authorized/x?code=x&state=state-1").first)
        val post = Socket(loopback, l.port).use { s ->
            s.getOutputStream().write("POST /launcher/authorized?code=x&state=state-1 HTTP/1.1\r\nHost: localhost:${l.port}\r\n\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(post.startsWith("HTTP/1.1 400"))
        assertTrue(outcomes.isEmpty())
    }

    @Test fun an_empty_code_is_not_a_sign_in() {
        val l = listen()
        assertEquals(400, get(l.port, "/launcher/authorized?code=&state=state-1").first)
        assertTrue(outcomes.isEmpty())
    }

    @Test fun a_refusal_from_the_provider_is_reported_only_with_the_right_state() {
        val l = listen()
        assertEquals(400, get(l.port, "/launcher/authorized?error=access_denied&state=bad").first)
        assertTrue(outcomes.isEmpty())
        assertEquals(200, get(l.port, "/launcher/authorized?error=access_denied&state=state-1").first)
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertTrue(outcomes.single() is LoopbackListener.Outcome.Denied)
    }

    @Test fun an_idle_connection_does_not_hold_up_the_callback() {
        val l = listen()
        Socket(loopback, l.port).use {
            assertEquals(200, get(l.port, "/launcher/authorized?code=c&state=state-1").first)
        }
        assertTrue(settled.await(5, TimeUnit.SECONDS))
    }

    @Test fun the_wait_ends_when_nobody_comes_back() {
        listen(timeoutMs = 300)
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertTrue(outcomes.single() is LoopbackListener.Outcome.TimedOut)
    }

    @Test fun closing_the_listener_reports_nothing() {
        val l = listen(timeoutMs = 300)
        l.close()
        assertFalse(settled.await(1, TimeUnit.SECONDS))
        assertTrue(outcomes.isEmpty())
    }

    @Test fun the_page_escapes_its_text_and_link() {
        val page = LoopbackListener.page("a <b> & \"c\"", "intent:#Intent;package=x;end")
        assertTrue(page.contains("a &lt;b&gt; &amp; &quot;c&quot;"))
        assertTrue(page.contains("href=\"intent:#Intent;package=x;end\""))
    }
}
