package de.lorenzgorse.chatroulette

import de.lorenzgorse.chatroulette.MatchMaker.SelectResult.*
import de.lorenzgorse.chatroulette.MatchMaker.Stage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory

class MatchMaker(private val inbox: ReceiveChannel<GetPeer>) {

    data class GetPeer(val reply: Channel<Pair<ReceiveChannel<ChatEvent>, SendChannel<ChatEvent>>> = Channel())

    companion object {

        @ExperimentalCoroutinesApi
        fun create(scope: CoroutineScope): Channel<GetPeer> {
            val inbox = Channel<GetPeer>(UNLIMITED)
            scope.launch { MatchMaker(inbox).start() }
            return inbox
        }

    }

    private val log = LoggerFactory.getLogger("matchmaker")

    private val pending = mutableListOf<GetPeer>()

    @ExperimentalCoroutinesApi
    suspend fun start() {
        while (singleMatch()) { }
    }

    enum class Stage {
        Stage1, Stage2
    }

    private sealed class SelectResult {
        object UserLeft : SelectResult()
        data class NewGetPeer(val getPeer: GetPeer) : SelectResult()
        data class MatchInitiated(val getPeer: GetPeer) : SelectResult()
    }

    @ExperimentalCoroutinesApi
    private suspend fun singleMatch(
            inbox1: Channel<ChatEvent> = Channel(UNLIMITED),
            inbox2: Channel<ChatEvent> = Channel(UNLIMITED),
            stage: Stage = Stage1
    ): Boolean {
        while (true) {
            log.info("Making match ($stage).")
            log.debug("backlog=${pending.size}")
            val result =
                    try {
                        select<SelectResult> {
                            inbox.onReceive { NewGetPeer(it) }
                            if (pending.size >= 2 || stage == Stage2) {
                                pending.forEach { p ->
                                    p.reply.onSend(Pair(inbox1, inbox2)) { MatchInitiated(p) }
                                }
                            }
                        }
                    } catch (e: ClosedSendChannelException) {
                        UserLeft
                    } catch (e: ClosedReceiveChannelException) {
                        return false
                    }

            when (result) {
                is UserLeft -> {
                    log.info("A user vanished.")
                    val sizeBefore = pending.size
                    pending.removeIf { it.reply.isClosedForSend }
                    require(pending.size < sizeBefore)
                }
                is NewGetPeer -> {
                    val getPeer = result.getPeer
                    log.info("New peer request: $getPeer.")
                    pending.add(getPeer)
                }
                is MatchInitiated -> {
                    log.info("Peer found ($stage).")
                    pending.remove(result.getPeer)
                    if (stage == Stage1) singleMatch(inbox2, inbox1, Stage2)
                    return true
                }
            }
        }
    }

}
