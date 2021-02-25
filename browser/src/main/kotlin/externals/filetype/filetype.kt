@file:JsModule("file-type/core")
@file:JsNonModule
package externals.filetype

import org.khronos.webgl.ArrayBuffer
import kotlin.js.Promise

external fun fromBuffer(byteArray: ArrayBuffer): Promise<FileType?>
