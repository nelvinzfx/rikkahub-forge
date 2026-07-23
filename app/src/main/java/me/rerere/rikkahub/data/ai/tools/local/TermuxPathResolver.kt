package me.rerere.rikkahub.data.ai.tools.local

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.Base64

internal const val TERMUX_FILES_HOME = "/data/data/com.termux/files/home"
internal const val TERMUX_FILES_TMPDIR = "/data/data/com.termux/files/usr/tmp"
internal const val MAX_TERMUX_PATH_BYTES = 4_096
internal const val MAX_TERMUX_ACTUAL_PATH_BYTES = 4_608

internal sealed class TermuxPathResolution {
    data class Ok(val actualPath: String) : TermuxPathResolution()
    data class Error(val code: String) : TermuxPathResolution()
}

/** Pure mirror for tests and early lexical rejection. Runtime path output remains authoritative. */
internal fun resolveTermuxPathLexically(
    userPath: String,
    home: String = TERMUX_FILES_HOME,
    tmpDir: String = TERMUX_FILES_TMPDIR,
): TermuxPathResolution {
    if (userPath.isBlank()) return TermuxPathResolution.Error("blank_path")
    if ('\u0000' in userPath) return TermuxPathResolution.Error("invalid_path")
    if (userPath.toByteArray(StandardCharsets.UTF_8).size > MAX_TERMUX_PATH_BYTES) {
        return TermuxPathResolution.Error("path_too_large")
    }
    return try {
        val lexicalTmp = userPath == "/tmp" || userPath.startsWith("/tmp/")
        val candidate = when {
            userPath == "~" -> home
            userPath.startsWith("~/") -> "$home/${userPath.substring(2)}"
            lexicalTmp -> tmpDir + userPath.removePrefix("/tmp")
            userPath.startsWith('/') -> userPath
            else -> "$home/$userPath"
        }
        val normalized = Paths.get(candidate).normalize().toString().let {
            if (it.startsWith('/')) it else "/$it"
        }
        val normalizedTmp = Paths.get(tmpDir).normalize().toString()
        if (lexicalTmp && normalized != normalizedTmp && !normalized.startsWith("$normalizedTmp/")) {
            TermuxPathResolution.Error("tmp_escape")
        } else {
            TermuxPathResolution.Ok(normalized)
        }
    } catch (_: InvalidPathException) {
        TermuxPathResolution.Error("invalid_path")
    }
}

internal fun encodeTermuxPath(path: String): String =
    Base64.getEncoder().encodeToString(path.toByteArray(StandardCharsets.UTF_8))

internal fun decodeCanonicalBase64(value: String, maxBytes: Int): ByteArray? = try {
    val decoded = Base64.getDecoder().decode(value)
    decoded.takeIf { it.size <= maxBytes && Base64.getEncoder().encodeToString(it) == value }
} catch (_: IllegalArgumentException) {
    null
}

internal fun decodeStrictUtf8(bytes: ByteArray, absolutePath: Boolean = false): String? = try {
    val decoded = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    decoded.takeIf {
        '\u0000' !in it && (!absolutePath || (it.startsWith('/') && it.isNotBlank()))
    }
} catch (_: Exception) {
    null
}

internal fun decodeTermuxPath(encoded: String): String? =
    decodeCanonicalBase64(encoded, MAX_TERMUX_PATH_BYTES)?.let(::decodeStrictUtf8)

/**
 * Shared argv-safe resolver. Input is canonical Base64 in one positional argv element.
 * realpath output is NUL-delimited so legal trailing newlines survive unchanged.
 */
internal val TERMUX_PATH_RESOLVER_SCRIPT = """
    resolve_termux_path() {
        encoded_path=${'$'}1
        [ "${'$'}{#encoded_path}" -le 5464 ] || return 64
        decoded_path=${'$'}(mktemp "${'$'}TMPDIR/rikkahub-path.XXXXXXXX") || return 67
        [ -f "${'$'}decoded_path" ] && [ ! -L "${'$'}decoded_path" ] || { rm -f -- "${'$'}decoded_path"; return 67; }
        printf '%s' "${'$'}encoded_path" | base64 -d > "${'$'}decoded_path" 2>/dev/null || { rm -f -- "${'$'}decoded_path"; return 65; }
        decoded_bytes=${'$'}(wc -c < "${'$'}decoded_path" | tr -d ' ')
        [ "${'$'}decoded_bytes" -le 4096 ] || { rm -f -- "${'$'}decoded_path"; return 64; }
        canonical_b64=${'$'}(base64 -w0 < "${'$'}decoded_path")
        [ "${'$'}canonical_b64" = "${'$'}encoded_path" ] || { rm -f -- "${'$'}decoded_path"; return 65; }
        non_nul_bytes=${'$'}(tr -d '\000' < "${'$'}decoded_path" | wc -c | tr -d ' ')
        [ "${'$'}decoded_bytes" -eq "${'$'}non_nul_bytes" ] || { rm -f -- "${'$'}decoded_path"; return 65; }
        IFS= read -r -d '' user_path < "${'$'}decoded_path" || true
        rm -f -- "${'$'}decoded_path"
        [ -n "${'$'}user_path" ] || return 66
        case "${'$'}user_path" in *[![:space:]]*) ;; *) return 66 ;; esac
        lexical_tmp=0
        case "${'$'}user_path" in
            /tmp) candidate=${'$'}TMPDIR; lexical_tmp=1 ;;
            /tmp/*) candidate=${'$'}TMPDIR/${'$'}{user_path#/tmp/}; lexical_tmp=1 ;;
            '~') candidate=${'$'}HOME ;;
            '~/'*) candidate=${'$'}HOME/${'$'}{user_path#\~/} ;;
            /*) candidate=${'$'}user_path ;;
            *) candidate=${'$'}HOME/${'$'}user_path ;;
        esac
        IFS= read -r -d '' actual_path < <(realpath -msz -- "${'$'}candidate") || return 67
        if [ "${'$'}lexical_tmp" -eq 1 ]; then
            IFS= read -r -d '' normalized_tmp < <(realpath -msz -- "${'$'}TMPDIR") || return 67
            case "${'$'}actual_path" in "${'$'}normalized_tmp"|"${'$'}normalized_tmp"/*) ;; *) return 68 ;; esac
        fi
        return 0
    }
""".trimIndent()
