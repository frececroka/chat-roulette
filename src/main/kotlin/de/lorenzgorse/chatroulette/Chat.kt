package de.lorenzgorse.chatroulette

import de.lorenzgorse.chatroulette.MatchMaker.GetPeer
import de.lorenzgorse.chatroulette.PeerMessage.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class User(val id: Long = nextId.getAndIncrement(), val created: Instant = Instant.now()) {
    companion object {
        private val nextId = AtomicLong()
    }
}

sealed class PeerMessage {
    data class Hello(val user: User) : PeerMessage()
    data class IsTyping(val typing: Boolean) : PeerMessage()
    sealed class ChatMessage : PeerMessage() {
        data class Text(val text: String) : ChatMessage()
        data class Image(val image: ByteArray) : ChatMessage()
    }
}

class Chat(
        private val incoming: ReceiveChannel<Frame>,
        private val outgoing: SendChannel<Frame>,
        private val matchMaker: SendChannel<GetPeer>
) {

    companion object {
        private val activeUsers = AtomicLong()
    }

    private val user = User()

    private val log = LoggerFactory.getLogger("user-${user.id}")!!

    @ObsoleteCoroutinesApi
    @ExperimentalCoroutinesApi
    suspend fun start() {
        log.info("New user ${user.id}.")
        activeUsers.incrementAndGet()

        try {
            outgoing.send(Frame.Text("user_id ${user.id}"))
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
                outgoing.send(Frame.Text("users ${activeUsers.get()}"))
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
                select<Pair<ReceiveChannel<PeerMessage>, SendChannel<PeerMessage>>?> {
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
            private val inbox: ReceiveChannel<PeerMessage>,
            private val outbox: SendChannel<PeerMessage>
    ) {

        private val log = LoggerFactory.getLogger("user-${user.id}")!!

        /**
         * Handles interaction with a connected peer. When this method returns, either the peer has disconnected (this is indicated by the return value `true`) or the user itself has disconnected (which is indicated by the return value `false`).
         */
        @ExperimentalCoroutinesApi
        suspend fun loop(): Boolean {
            log.info("New connection to peer.")
            try {
                outbox.send(Hello(user))
                val hello = inbox.receive()
                require(hello is Hello) {
                    "First message from peer must be hello, but was $hello." }
                outgoing.send(Frame.Text("connected ${hello.user.id}"))
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
                    val content = frame.readText()
                    when {
                        content.startsWith("typing ") -> {
                            val typing = content.substring("typing ".length).toBoolean()
                            outbox.send(IsTyping(typing))
                        }
                        content.startsWith("message ") ->
                            outbox.send(ChatMessage.Text(content.substring(" message".length)))
                    }
                }
                is Frame.Binary ->
                    outbox.send(ChatMessage.Image(frame.data))
            }
        }

        private suspend fun handlePeerMessage(message: PeerMessage) {
            when (message) {
                is IsTyping ->
                    outgoing.send(Frame.Text("peer_typing ${message.typing}"))
                is ChatMessage.Text ->
                    outgoing.send(Frame.Text("message ${message.text}"))
                is ChatMessage.Image ->
                    outgoing.send(Frame.Binary(true, message.image))
            }
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
                    outgoing.send(Frame.Text("disconnected"))
                    true
                } catch (e: ClosedSendChannelException) {
                    false
                }

    }

}
