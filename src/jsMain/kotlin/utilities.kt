import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLElement
import org.w3c.files.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun activeTimeout(onActive: () -> Unit, onInactive: () -> Unit): Pair<() -> Unit, () -> Unit> {
    var isActive = false
    var inactiveTimeout: Int? = null

    fun deactivate() {
        window.clearTimeout(inactiveTimeout ?: 0)
        isActive = false
        inactiveTimeout = null
        onInactive()
    }

    fun activate() {
        if (!isActive) {
            onActive()
            isActive = true
        }
        window.clearTimeout(inactiveTimeout ?: 0)
        inactiveTimeout = window.setTimeout(::deactivate, 1000)
    }

    return Pair(::activate, ::deactivate)
}

fun el(
        tag: String,
        text: String? = null,
        classes: List<String> = emptyList(),
        children: List<HTMLElement> = emptyList()
): HTMLElement {
    val element = document.createElement(tag) as HTMLElement
    if (text != null) element.innerText = text
    element.append(*children.toTypedArray())
    element.classList.add(*classes.toTypedArray())
    return element
}

fun arrayToBase64String(a: ArrayBuffer): String {
    return window.btoa(arrayToString(a))
}

fun FileList?.toArray(): List<File> =
        if (this == null) listOf()
        else
            mutableListOf<File>().also {
                for (i in 0..length) {
                    val file = this[i]
                    if (file != null) {
                        it.add(file)
                    }
                }
            }

suspend fun readBlob(blob: Blob): ArrayBuffer = suspendCoroutine { cont ->
    val reader = FileReader()
    reader.onload = { cont.resume(reader.result as ArrayBuffer) }
    reader.readAsArrayBuffer(blob)
}

fun arrayToString(a: ArrayBuffer): String {
    val typedArray = Uint8Array(a)
    var string = ""
    for (i in 0 until typedArray.length) {
        string += typedArray[i].toChar()
    }
    return string
}

fun HTMLElement.show(state: Boolean = true) = hide(!state)
fun HTMLElement.hide(state: Boolean = true) {
    style.display = if (state) "none" else ""
}
