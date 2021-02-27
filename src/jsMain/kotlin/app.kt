import de.lorenzgorse.chatroulette.ChatEvent
import externals.filetype.fileType
import externals.isSvg
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.*
import org.w3c.files.Blob
import org.w3c.xhr.FormData

val inputForm = document.getElementById("input-form") as HTMLFormElement
val messageInput = inputForm.getElementsByClassName("message-input")[0] as HTMLInputElement
val fileInput = inputForm.getElementsByClassName("image-input")[0] as HTMLInputElement
val connectedUsers = document.getElementById("connected-users") as HTMLElement
val connectionSpinner = document.getElementById("connection-spinner") as HTMLElement
val youreAlone = document.getElementById("youre-alone") as HTMLElement
val strangerIsTyping = document.getElementById("stranger-is-typing") as HTMLElement
val waitingForStranger = document.getElementById("waiting-for-stranger") as HTMLElement
val chatBox = document.getElementById("chat-box") as HTMLElement

fun main() {
    val proto = if (window.location.protocol === "https:") "wss" else "ws"
    val webSocket = WebSocket(proto + "://" + window.location.host + "/chat")

    connectionSpinner.show()
    webSocket.onopen = {
        waitingForStranger.show()
        connectionSpinner.hide()
        null
    }

    webSocket.onmessage = {
        GlobalScope.launch {
            handleFrame(it)
        }
    }

    fun reconnect() {
        webSocket.onopen = null
        webSocket.onmessage = null
        webSocket.onclose = null
        messageInput.onkeydown = null
        fileInput.onchange = null
        inputForm.onsubmit = null
        webSocket.close()
        main()
    }

    webSocket.onclose = {
        enableInputs(false)
        connectedUsers.innerText = ""
        connectionSpinner.hide()
        youreAlone.hide()
        strangerIsTyping.hide()
        waitingForStranger.hide()
        appendStatusMessage("Connection lost.",
                mapOf("Reconnect?" to ::reconnect))
    }

    val (typingActivate, typingDeactivate) = activeTimeout(
            { sendChatEvent(webSocket, ChatEvent.IsTyping(true)) },
            { sendChatEvent(webSocket, ChatEvent.IsTyping(false)) })

    messageInput.onkeydown = { typingActivate() }

    inputForm.onsubmit = { event ->
        val form = event.target as HTMLFormElement
        val data = FormData(form)
        val message = data.get("message") as String
        sendChatEvent(webSocket, ChatEvent.Message.Text(message))
        appendTextMessage("You", message)
        typingDeactivate()
        form.reset()
        event.preventDefault()
    }

    fileInput.onchange = {
        GlobalScope.launch {
            sendImages(webSocket)
        }
    }
}

private suspend fun handleFrame(event: MessageEvent) {
    val chatEvent = when (val data = event.data) {
        is String -> Json.decodeFromString<ChatEvent>(data)
        is Blob -> ChatEvent.Message.Image(readBlob(data))
        else -> return
    }
    when (chatEvent) {
        is ChatEvent.UserCount -> {
            youreAlone.show(chatEvent.userCount == 1L)
            connectedUsers.innerText = "${chatEvent.userCount} users"
        }
        is ChatEvent.UserId -> {
            appendStatusMessage("You are stranger #${chatEvent.userId}.")
        }
        is ChatEvent.Hello -> {
            enableInputs(true)
            youreAlone.hide()
            waitingForStranger.hide()
            appendStatusMessage("Connected to stranger #${chatEvent.user.id}.")
        }
        is ChatEvent.Disconnected -> {
            enableInputs(false)
            strangerIsTyping.show(false)
            waitingForStranger.show()
            appendStatusMessage("Disconnected from stranger.")
        }
        is ChatEvent.IsTyping -> {
            strangerIsTyping.show(chatEvent.typing)
        }
        is ChatEvent.Message.Text -> {
            strangerIsTyping.hide()
            appendTextMessage("Stranger", chatEvent.text)
        }
        is ChatEvent.Message.Image -> {
            require(chatEvent.image is ArrayBuffer)
            appendImageMessage("Stranger", "image/jpeg", chatEvent.image)
        }
    }
}

private suspend fun sendImages(webSocket: WebSocket) {
    val files = fileInput.files.toArray()
    val smallFiles = files.filter { it.size.toLong() <= 20 * 1024 * 1024 }
    if (smallFiles.size < files.size) {
        val diff = files.size - smallFiles.size
        val msg = if (diff == 1) "this image is" else "$diff images were"
        window.alert("Sorry, $msg too big. The maximum file size is 20Mb.")
    }

    for (file in smallFiles) {
        val arrayBuffer = readBlob(file)
        val isSvg = isSvg(arrayToString(arrayBuffer))
        val mime = if (isSvg) "image/svg+xml" else fileType(arrayBuffer)?.mime
        if (mime != null && mime.startsWith("image/")) {
            sendChatEvent(webSocket, ChatEvent.Message.Image(arrayBuffer))
            appendImageMessage("You", mime, arrayBuffer)
        } else {
            window.alert("Sorry, that is not an image.")
        }
    }
}

private fun sendChatEvent(webSocket: WebSocket, chatEvent: ChatEvent) {
    when (chatEvent) {
        is ChatEvent.Message.Image -> {
            require(chatEvent.image is ArrayBuffer)
            webSocket.send(chatEvent.image)
        }
        else -> webSocket.send(Json.encodeToString(chatEvent))
    }
}

private fun enableInputs(enabled: Boolean) {
    messageInput.disabled = !enabled
    fileInput.disabled = !enabled
}

fun appendTextMessage(sender: String, message: String) {
    appendMessage(sender, el("span", text = message))
}

fun appendImageMessage(sender: String, mime: String, img: ArrayBuffer): HTMLImageElement {
    val base64 = arrayToBase64String(img)
    return appendImageMessage(sender, "data:$mime;base64,$base64")
}

fun appendImageMessage(sender: String, url: String): HTMLImageElement {
    val imgElement = el("img") as HTMLImageElement
    imgElement.src = url
    appendMessage(sender, imgElement)
    return imgElement
}

fun appendMessage(sender: String, element: HTMLElement) {
    chatBox.prepend(
            el("p", classes = listOf("message"), children = listOf(
                    el("span", classes = listOf("message-sender"), text = "$sender: "),
                    element)))
}

fun appendStatusMessage(message: String, actions: Map<String, () -> Unit> = emptyMap()) {
    val compoundElement = el("p", classes = listOf("status-message"), children = listOf(
            el("span", text = message)))

    val actionElementPairs = actions.map { (text, action) ->
        val actionElement = el("button",
                classes = listOf("status-action"),
                text = text) as HTMLButtonElement
        Pair(actionElement, action)
    }

    actionElementPairs.forEach { (actionElement, action) ->
        actionElement.onclick = {
            actionElement.classList.add("status-action-chosen")
            actionElementPairs.forEach { (e, _) -> e.disabled = true }
            action()
        }
        compoundElement.append(actionElement)
    }

    chatBox.prepend(compoundElement)
}
