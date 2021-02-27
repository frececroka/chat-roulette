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

/**
 * Every instance of this class handles one client that is connected to this server.
 *
 * @param clientIn the channel over which we receive WebSocket frames from the client.
 * @param clientOut the channel over which we send WebSocket frames to the client.
 * @param matchMaker the channel over which we communicate with the match maker.
 */
class Chat(
        private val clientIn: ReceiveChannel<Frame>,
        private val clientOut: SendChannel<Frame>,
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
            // We begin by informing the client of its user id.
            sendToClient(ChatEvent.UserId(user.id))

            coroutineScope {
                // This sends periodic status messages to the client.
                val statusJob = launch { status() }

                // This handles communication with peers (chat partners).
                val peerJob = launch { while (loop()) { } }

                // TODO: just cancel the whole scope.
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

    /**
     * Sends periodic status messages to the client.
     */
    @ObsoleteCoroutinesApi
    private suspend fun status() {
        while (true) {
            try {
                sendToClient(ChatEvent.UserCount(activeUsers.get()))
            } catch (e: ClosedSendChannelException) {
                return
            }
            delay(1000)
        }
    }

    /**
     * This requests a new peer from the match maker and handles the communication with
     * it once we get one. When this method returns, either the peer has disconnected
     * (this is indicated by the return value `true`) or the user itself has
     * disconnected (which is indicated by the return value `false`).
     */
    @ExperimentalCoroutinesApi
    private suspend fun loop(): Boolean = coroutineScope {
        // This consumes messages that the clients sends while we are not connected to
        // a peer. It is cancelled once we get a peer (because then these messages
        // should be forwarded to the peer).
        val consumeJob = launch {
            try {
                while (true) {
                    val msg = clientIn.receive()
                    log.debug("Ignoring message $msg received from client while not connected to a peer.")
                }
            } catch (_: ClosedReceiveChannelException) {
                log.info("Incoming connection closed while not connected to a peer.")
            }
        }

        // Request a peer from the match maker.
        val getPeer = GetPeer()
        matchMaker.send(getPeer)
        log.info("Waiting for peer on channel ${getPeer.reply}.")

        // Wait for the match maker to give us the channels that we can use to
        // communicate with our peer.
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
            val (peerIn, peerOut) = channels
            try {
                // Cancel the consume job, because now we want to forward messages to
                // our peer.
                consumeJob.cancelAndJoin()
                // Enter the connected state.
                ConnectedState(peerIn, peerOut).loop()
            } finally {
                peerOut.close()
            }
        } else false
    }

    inner class ConnectedState(
            private val peerIn: ReceiveChannel<ChatEvent>,
            private val peerOut: SendChannel<ChatEvent>
    ) {

        private val log = LoggerFactory.getLogger("user-${user.id}")!!

        /**
         * Handles interaction with a connected peer. When this method returns, either
         * the peer has disconnected (this is indicated by the return value `true`) or
         * the user itself has disconnected (which is indicated by the return value
         * `false`).
         */
        @ExperimentalCoroutinesApi
        suspend fun loop(): Boolean {
            log.info("New connection to peer.")
            try {
                // Say hello to our peer.
                peerOut.send(ChatEvent.Hello(user))

                // Wait for our peer to say hello.
                val hello = peerIn.receive()
                require(hello is ChatEvent.Hello) {
                    "First message from peer must be hello, but was $hello." }

                // Forward the peer's hello to the client.
                sendToClient(hello)

                while (true) {
                    select<Unit> {
                        clientIn.onReceive { handleClientMessage(it) }
                        peerIn.onReceive { handlePeerMessage(it) }
                    }
                }
            } catch (_: ClosedSendChannelException) {
                return handleClosedChannel()
            } catch (_: ClosedReceiveChannelException) {
                return handleClosedChannel()
            }
        }

        private suspend fun handleClientMessage(frame: Frame) {
            val chatEvent = when (frame) {
                is Frame.Text -> Json.decodeFromString<ChatEvent>(frame.readText())
                is Frame.Binary -> ChatEvent.Message.Image(frame.data)
                else -> {
                    log.debug("Ignoring message: $frame")
                    return
                }
            }
            peerOut.send(chatEvent)
        }

        private suspend fun handlePeerMessage(chatEvent: ChatEvent) {
            // TODO: we forward all chat events that we receive from our peer to out
            //  client. This is not entirely correct. A peer could, for example, send a
            //  ChatEvent.UserCount message, which changes the user count that is
            //  displayed by our client. We should split the ChatEvent type into four
            //  types:
            //    - Events that we can send to our client
            //    - Events that our client can send to us
            //    - Events that we can send to our peer
            //    - Events that our peer can send to us
            sendToClient(chatEvent)
        }

        @ExperimentalCoroutinesApi
        private suspend fun handleClosedChannel(): Boolean =
                when {
                    clientOut.isClosedForSend || clientIn.isClosedForReceive -> false
                    peerOut.isClosedForSend || peerIn.isClosedForReceive -> peerDisconnected()
                    else -> throw IllegalStateException()
                }

        private suspend fun peerDisconnected(): Boolean =
                try {
                    sendToClient(ChatEvent.Disconnected)
                    true
                } catch (e: ClosedSendChannelException) {
                    false
                }

    }

    private suspend fun sendToClient(chatEvent: ChatEvent) {
        val frame = when (chatEvent) {
            is ChatEvent.Message.Image -> {
                require(chatEvent.image is ByteArray)
                Frame.Binary(true, chatEvent.image)
            }
            else ->
                Frame.Text(Json.encodeToString(chatEvent))
        }
        clientOut.send(frame)
    }

}
