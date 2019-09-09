import org.khronos.webgl.ArrayBuffer

data class FileType(val ext: String, val mime: String)

@JsModule("file-type")
external fun fileType(byteArray: ArrayBuffer): FileType?

@JsModule("is-svg")
external fun isSvg(content: String): Boolean
