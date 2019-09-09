package de.lorenzgorse.chatroulette

import de.lorenzgorse.chatroulette.MatchMaker.GetPeer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumingThat

class MatchMakerTest : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private lateinit var matchMaker: SendChannel<GetPeer>

    @BeforeEach
    @ExperimentalCoroutinesApi
    fun before() {
        matchMaker = MatchMaker.create(this)
    }

    @AfterEach
    fun after() {
        matchMaker.close()
    }

    @Test
    fun testMakeMatch() { runBlocking {
        val channels = (0 until 6)
                .map { GetPeer() }
                .map {
                    matchMaker.send(it)
                    async { it.reply.receive() }
                }
                .map { it.await() }

        val unmatchedChannels = channels
                .map { (inbox, outbox) -> Pair(
                        inbox as Channel<PeerMessage>,
                        outbox as Channel<PeerMessage>) }
                .toMutableList()

        for (channel in channels) {
            assumingThat(unmatchedChannels.remove(channel)) {
                val (inbox, outbox) = channel
                assertTrue(unmatchedChannels.remove(Pair(outbox, inbox)))
            }
        }

        assertTrue(unmatchedChannels.isEmpty())
    } }

    @Test
    fun testUserVanishes() { runBlocking {
        val getPeer = GetPeer().also { matchMaker.send(it) }
        getPeer.reply.close()
        testMakeMatch()
    } }

    @Test
    fun singleUserNotServiced() { runBlocking {
        val getPeer = GetPeer().also { matchMaker.send(it) }
        try {
            withTimeout(100) { getPeer.reply.receive() }
            fail("Received reply from match maker, but there is no peer available.")
        } catch (e: TimeoutCancellationException) {
        }
    } }

}
