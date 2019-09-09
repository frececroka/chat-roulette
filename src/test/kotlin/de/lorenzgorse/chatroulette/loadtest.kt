package de.lorenzgorse.chatroulette

import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.client.features.websocket.wss
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.host
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.selects.select
import java.net.URL
import java.time.Duration
import kotlin.random.Random

val numStages = 14
val numClientsPerStage = 1000
val msgsPerConnection = 100
val msgDelayMin = 300L
val msgDelayMax = 6000L

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
@KtorExperimentalAPI
fun main() = runBlocking {
    val status = Channel<Status>(100)
    val statusPrinter = launch { printStatus(status) }
    val connect = RemoteServer("localhost", port = 8080)::connect
    produce {
        repeat(numStages) {
            repeat(numClientsPerStage) {
                send(launch { singleUser(status, connect) })
            }
            delay(60_000)
        }
    }.toList().forEach { it.join() }
    statusPrinter.cancel()
}

sealed class Status {
    data class Delay(val delay: Long) : Status()
    object Connect : Status()
    object Disconnect : Status()
    object MsgSent : Status()
    object MsgReceived : Status()
    object BrokenChannel : Status()
}

private suspend fun printStatus(status: Channel<Status>) {
    val startTime = System.nanoTime()
    val avgDelay = RollingAverage()
    var n: Long = 0
    var connects: Long = 0
    var disconnects: Long = 0
    var active: Long = 0
    var msgsSent: Long = 0
    var msgsReceived: Long = 0
    var brokenChannels: Long = 0
    var lastNewline: Long = 0
    var lastPrint: Long = 0
    while (true) {
        when (val msg = status.receive()) {
            is Status.Delay -> {
                avgDelay.add(msg.delay.toDouble())
                n += 1
            }
            is Status.Connect -> {
                connects += 1
                active += 1
            }
            is Status.Disconnect -> {
                disconnects += 1
                active -= 1
            }
            is Status.MsgSent -> msgsSent += 1
            is Status.MsgReceived -> msgsReceived += 1
            is Status.BrokenChannel -> brokenChannels += 1
        }

        // TODO: Track number of paired users.
        if (System.nanoTime() - lastPrint > 100_000_000) {
            val elapsed = (System.nanoTime() - startTime) / 1e9
            val sendFreq = msgsSent / ((System.nanoTime() - lastNewline) / 1e9)
            lastPrint = System.nanoTime()
            val fmt = "\r" +
                    "elapsed %.1fs ; " +
                    "avg delay %.4fms ; " +
                    "%d active ; " +
                    "%d connects ; " +
                    "%d disconnects ; " +
                    "%d msgs sent (%.2f msgs/s) ; " +
                    "%d msgs received ; " +
                    "%d broken channels"
            if (System.nanoTime() - lastNewline > 25_000_000_000) {
                println(fmt.format(elapsed, avgDelay.get()/1e6, active, connects, disconnects, msgsSent, sendFreq, msgsReceived, brokenChannels))
                lastNewline = System.nanoTime()
                avgDelay.reset()
                msgsSent = 0
                msgsReceived = 0
                connects = 0
                disconnects = 0
            } else {
                print(fmt.format(elapsed, avgDelay.get()/1e6, active, connects, disconnects, msgsSent, sendFreq, msgsReceived, brokenChannels))
            }
        }
    }
}

class RollingAverage {

    private var avg = 0.0
    private var n = 0

    fun add(value: Double) {
        avg = avg*n/(n+1) + value/(n+1)
        n += 1
    }

    fun reset() {
        avg = 0.0
        n = 0
    }

    fun get() = avg

}

class ConnectedScope(val incoming: ReceiveChannel<Frame>, val outgoing: SendChannel<Frame>)

class LocalServer(private val scope: CoroutineScope) {

    @ExperimentalCoroutinesApi
    private val matchMaker = MatchMaker.create(scope)

    @ExperimentalCoroutinesApi
    @ObsoleteCoroutinesApi
    suspend fun connect(block: suspend ConnectedScope.() -> Unit) {
        val incoming = Channel<Frame>(UNLIMITED)
        val outgoing = Channel<Frame>(UNLIMITED)
        val chat = Chat(incoming, outgoing, matchMaker)
        scope.launch { chat.start() }
        try {
            ConnectedScope(outgoing, incoming).block()
        } finally {
            incoming.close()
            outgoing.close()
        }
    }

}

class RemoteServer(private val host: String, private val port: Int? = null, private val secure: Boolean = false) {

    @ObsoleteCoroutinesApi
    @KtorExperimentalAPI
    suspend fun connect(block: suspend ConnectedScope.() -> Unit) {
        HttpClient { install(WebSockets) }.use {
            if (secure) {
                it.wss({ populate() }) { run(block) }
            } else {
                it.ws({ populate() }) { run(block) }
            }
        }
    }

    fun HttpRequestBuilder.populate() {
        method = HttpMethod.Get
        url.host = this@RemoteServer.host
        if (this@RemoteServer.port != null) {
            url.port = this@RemoteServer.port
        }
        url.path("chat")
    }

    private suspend fun WebSocketSession.run(block: suspend ConnectedScope.() -> Unit) {
        ConnectedScope(incoming, outgoing).block()
    }

}

@ObsoleteCoroutinesApi
private suspend fun singleUser(status: Channel<Status>, connect: suspend (suspend ConnectedScope.() -> Unit) -> Unit) {
    while (true) {
        try {
            status.send(Status.Connect)
            connect {
                LoadtestClient(status, incoming, outgoing).start()
            }
        } catch (e: ClosedSendChannelException) {
            status.send(Status.BrokenChannel)
        } catch (e: ClosedReceiveChannelException) {
            status.send(Status.BrokenChannel)
        } finally {
            status.send(Status.Disconnect)
        }
    }
}

class LoadtestClient(
        private val status: Channel<Status>,
        private val incoming: ReceiveChannel<Frame>,
        private val outgoing: SendChannel<Frame>
) {

    private var userId: Long? = null
    private var peerId: Long? = null
    private var lastMessage: Long? = null
    private var totalMessages: Long? = null

    @ObsoleteCoroutinesApi
    suspend fun start() {
        val timeout = ticker(Random.nextLong(msgDelayMin, msgDelayMax))
        for (i in 1..msgsPerConnection) {
            sendOne(timeout, i, msgsPerConnection)
        }
    }

    private suspend fun sendOne(timeout: ReceiveChannel<Unit>, i: Int, count: Int) {
        while (
                select {
                    incoming.onReceive { handleFrame(it); true }

                    if (userId != null) {
                        timeout.onReceive {
                            status.send(Status.MsgSent)
                            outgoing.send(Frame.Text("message $userId $i $count ${System.nanoTime()}"))
                            false
                        }
                    }
                }
        ) { }
    }

    private suspend fun handleFrame(frame: Frame) {
        if (frame is Frame.Text) {
            val content = frame.readText()
            when {
                content.startsWith("user_id ") -> handleUserId(content)
                content.startsWith("connected ") -> handleConnected(content)
                content == "disconnected" -> handleDisconnected()
                content.startsWith("message ") -> handleMessage(content)
            }
        }
    }

    private fun handleUserId(content: String) {
        require(userId == null)
        userId = content.substring("user_id ".length).toLong()
    }

    private fun handleConnected(content: String) {
        require(userId != null)
        require(peerId == null) { "User $userId already connected to $peerId" }
        peerId = content.substring("connected ".length).toLong()
        lastMessage = null
    }

    private fun handleDisconnected() {
        peerId = null
        lastMessage = null
        totalMessages = null
    }

    private suspend fun handleMessage(content: String) {
        status.send(Status.MsgReceived)
        require(userId != null)
        require(peerId != null)
        val message = content.substring("message ".length)
        val match = Regex("(\\d+) (\\d+) (\\d+) (\\d+)").matchEntire(message)
        require(match != null) { message }
        val (senderId, msgNum, msgTotal, sendTime) = match.groupValues.drop(1).map { it.toLong() }
        require(senderId == peerId) {
            "User $userId received message from $senderId, but is currently connected to $peerId" }
        require(lastMessage == null || lastMessage?.inc() == msgNum) {
            "Last message was $lastMessage, current message is $msgNum." }
        require(totalMessages == null || totalMessages == msgTotal)
        totalMessages = msgTotal
        lastMessage = msgNum
        status.send(Status.Delay(System.nanoTime() - sendTime))
    }

}
