package externals.filetype

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer

suspend fun fileType(byteArray: ArrayBuffer): FileType? = fromBuffer(byteArray).await()
