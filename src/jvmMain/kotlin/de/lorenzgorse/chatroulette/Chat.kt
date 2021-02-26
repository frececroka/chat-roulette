package de.lorenzgorse.chatroulette

import de.lorenzgorse.chatroulette.MatchMaker.GetPeer
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Chat(
        private val incoming: ReceiveChannel<Frame>,
        private val outgoing: SendChannel<Frame>,
        private val matchMaker: SendChannel<GetPeer>
) {

    companion object {
        private val nextUserId = AtomicLong()
        private val activeUsers = AtomicLong()
    }

    private val user = User(nextUserId.getAndIncrement())

    private val log = LoggerFactory.getLogger("user-${user.id}")!!

    @ObsoleteCoroutinesApi
    @ExperimentalCoroutinesApi
    suspend fun start() {
        log.info("New user ${user.id}.")
        activeUsers.incrementAndGet()

        try {
            sendChatEvent(ChatEvent.UserId(user.id))
            coroutineScope {
                val statusJob = launch { status() }
                val peerJob = launch { while (loop()) { } }

                select<Unit> {
                    statusJob.onJoin {
                        log.info("Status job exited, canceling peer job.")
                        peerJob.cancel()
                    }
                    peerJob.onJoin {
                        log.info("Peer job exited, canceling status job.")
                        statusJob.cancel()
                    }
                }
            }
        } finally {
            activeUsers.decrementAndGet()
            log.info("User ${user.id} left.")
        }
    }

    @ObsoleteCoroutinesApi
    private suspend fun status() {
        while (true) {
            try {
                sendChatEvent(ChatEvent.UserCount(activeUsers.get()))
            } catch (e: ClosedSendChannelException) {
                return
            }
            delay(1000)
        }
    }

    @ExperimentalCoroutinesApi
    private suspend fun loop(): Boolean = coroutineScope {
        val consumeJob = launch {
            try {
                while (true) {
                    val msg = incoming.receive()
                    log.debug("Ignoring message $msg received from client while not connected to a peer.")
                }
            } catch (_: ClosedReceiveChannelException) {
                log.info("Incoming connection closed while not connected to a peer.")
            }
        }

        val getPeer = GetPeer()
        matchMaker.send(getPeer)
        log.info("Waiting for peer on channel ${getPeer.reply}.")

        val channels =
            try {
                select<Pair<ReceiveChannel<ChatEvent>, SendChannel<ChatEvent>>?> {
                    consumeJob.onJoin { null }
                    getPeer.reply.onReceive { it }
                }
            } finally {
                getPeer.reply.close()
            }

        if (channels != null) {
            val (inbox, outbox) = channels
            try {
                consumeJob.cancelAndJoin()
                ConnectedState(inbox, outbox).loop()
            } finally {
                outbox.close()
            }
        } else false
    }

    inner class ConnectedState(
            private val inbox: ReceiveChannel<ChatEvent>,
            private val outbox: SendChannel<ChatEvent>
    ) {

        private val log = LoggerFactory.getLogger("user-${user.id}")!!

        /**
         * Handles interaction with a connected peer. When this method returns, either the peer has disconnected (this is indicated by the return value `true`) or the user itself has disconnected (which is indicated by the return value `false`).
         */
        @ExperimentalCoroutinesApi
        suspend fun loop(): Boolean {
            log.info("New connection to peer.")
            try {
                outbox.send(ChatEvent.Hello(user))
                val hello = inbox.receive()
                require(hello is ChatEvent.Hello) {
                    "First message from peer must be hello, but was $hello." }
                sendChatEvent(hello)
                while (true) {
                    select<Unit> {
                        incoming.onReceive { handleFrame(it) }
                        inbox.onReceive { handlePeerMessage(it) }
                    }
                }
            } catch (_: ClosedSendChannelException) {
                return handleClosedChannel()
            } catch (_: ClosedReceiveChannelException) {
                return handleClosedChannel()
            }
        }

        private suspend fun handleFrame(frame: Frame) {
            when (frame) {
                is Frame.Text -> {
                    val json = frame.readText()
                    val chatEvent = Json.decodeFromString<ChatEvent>(json)
                    outbox.send(chatEvent)
                }
                else ->
                    log.debug("Ignoring message: $frame")
            }
        }

        private suspend fun handlePeerMessage(chatEvent: ChatEvent) {
            sendChatEvent(chatEvent)
        }

        @ExperimentalCoroutinesApi
        private suspend fun handleClosedChannel(): Boolean =
                when {
                    outgoing.isClosedForSend || incoming.isClosedForReceive -> false
                    outbox.isClosedForSend || inbox.isClosedForReceive -> peerDisconnected()
                    else -> throw IllegalStateException()
                }

        private suspend fun peerDisconnected(): Boolean =
                try {
                    sendChatEvent(ChatEvent.Disconnected)
                    true
                } catch (e: ClosedSendChannelException) {
                    false
                }

    }

    private suspend fun sendChatEvent(chatEvent: ChatEvent) {
        val json = Json.encodeToString(chatEvent)
        outgoing.send(Frame.Text(json))
    }

}
