package claudechat.git

import java.io.File
import java.io.IOException

/**
 * A comparable spelling of [path]: the canonical path (symlinks resolved, trailing slash gone), or, when the
 * filesystem cannot be asked, the path with trailing slashes trimmed (a lone "/" is kept).
 */
fun canonicalPath(path: String): String = try {
    File(path).canonicalPath
} catch (_: IOException) {
    path.trimEnd('/').ifEmpty { "/" }
}
