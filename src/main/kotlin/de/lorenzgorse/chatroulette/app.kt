package de.lorenzgorse.chatroulette

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ConditionalHeaders
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.defaultResource
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.response.respondText
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.*
import java.time.Duration

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
fun createServer(port: Int) = embeddedServer(Netty, port) {

    install(ContentNegotiation) {
        gson {}
    }

    install(ConditionalHeaders)

    install(StatusPages) {
        exception { e: Exception ->
            call.respondText(e.localizedMessage, status = HttpStatusCode.InternalServerError) }
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(5)
    }

    val matchMaker = MatchMaker.create(this)

    routing {

        webSocket("/chat") {
            Chat(incoming, outgoing, matchMaker).start()
        }

        static("/") {
            resources("/static")
            defaultResource("/static/index.html")
        }

    }

}

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
@InternalCoroutinesApi
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val server = createServer(port)
    server.start(true)
}
