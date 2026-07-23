package me.rerere.rikkahub.data.ai.tools.local

import java.util.Base64

internal const val TERMUX_EDIT_PROTOCOL_MAX_CHARS = 48 * 1024
internal const val TERMUX_EDIT_DIFF_MAX_CHARS = 60 * 1024
internal const val MAX_TERMUX_EDIT_TOTAL_ACTUAL_PATH_BYTES = 16 * 1024

internal data class TermuxEditSnapshot(
    val index: Int,
    val actualPath: String,
    val requestPathSha256: String,
    val sha256: String,
    val mode: String,
    val identity: String,
    val bytes: Int,
)
internal data class TermuxEditPublishItem(
    val index: Int,
    val actualPath: String,
    val sourceSha256: String,
    val resultSha256: String,
    val state: String,
) {
    val published: Boolean get() = state == "published" || state == "no_change"
    val rolledBack: Boolean? get() = when (state) { "rolled_back" -> true; "rollback_failed" -> false; else -> null }
}
internal data class TermuxEditPublishResult(val success: Boolean, val items: List<TermuxEditPublishItem>, val error: String? = null)

internal val TERMUX_EDIT_SNAPSHOT_SCRIPT = """
    ${TERMUX_TRANSFER_COMMON_SCRIPT}
    ${TERMUX_PATH_RESOLVER_SCRIPT}
    marker=RIKKAHUB_EDIT_SNAPSHOT_V1; count=${'$'}2; shift 2
    fail() { printf '%s\nrequest_id=%s\nerror=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}1"; exit 0; }
    valid_uint "${'$'}count" && [ "${'$'}count" -ge 1 ] && [ "${'$'}count" -le ${MAX_TERMUX_EDIT_FILES} ] || fail invalid_count
    [ "${'$'}#" -eq "${'$'}((count * 3))" ] || fail invalid_arguments
    [ ! -e "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ] || fail unsafe_transfer_state
    mkdir -- "${'$'}transfer_dir" || fail unsafe_transfer_state; chmod 700 -- "${'$'}transfer_dir"
    cleanup_on_error=1
    cleanup() { if [ "${'$'}cleanup_on_error" -eq 1 ] && [ -d "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ]; then rm -rf -- "${'$'}transfer_dir"; fi; }
    trap cleanup EXIT HUP INT TERM
    write_lock=${'$'}cache_root/write.lock
    if [ -e "${'$'}write_lock" ] || [ -L "${'$'}write_lock" ]; then [ -f "${'$'}write_lock" ] && [ ! -L "${'$'}write_lock" ] || fail unsafe_lock; fi
    : >> "${'$'}write_lock"; chmod 600 -- "${'$'}write_lock"; exec 9>> "${'$'}write_lock"; flock -x 9
    actuals=(); path_shas=(); shas=(); modes=(); identities=(); sizes=(); total_size=0; total_actual_path_bytes=0
    i=0
    while [ "${'$'}i" -lt "${'$'}count" ]; do
        path_b64=${'$'}1; expected=${'$'}2; path_sha=${'$'}3; shift 3
        [ "${'$'}expected" = - ] || valid_sha256 "${'$'}expected" || fail invalid_expected_sha256
        valid_sha256 "${'$'}path_sha" || fail invalid_path
        decoded_sha=${'$'}(printf '%s' "${'$'}path_b64" | base64 -d 2>/dev/null | sha256sum | cut -d' ' -f1) || fail invalid_path
        [ "${'$'}decoded_sha" = "${'$'}path_sha" ] || fail invalid_path
        set +e; resolve_termux_path "${'$'}path_b64"; rc=${'$'}?; set -e
        [ "${'$'}rc" -eq 0 ] || fail invalid_path
        [ ! -L "${'$'}actual_path" ] || fail symlink_rejected
        [ -e "${'$'}actual_path" ] || fail not_found
        [ -f "${'$'}actual_path" ] || fail not_regular_file
        actual_b64=${'$'}(printf '%s' "${'$'}actual_path" | base64 -w0)
        actual_path_bytes=${'$'}(printf '%s' "${'$'}actual_path" | wc -c | tr -d ' ') || fail stat_failed
        valid_uint "${'$'}actual_path_bytes" || fail stat_failed
        total_actual_path_bytes=${'$'}((total_actual_path_bytes + actual_path_bytes))
        [ "${'$'}total_actual_path_bytes" -le ${MAX_TERMUX_EDIT_TOTAL_ACTUAL_PATH_BYTES} ] || fail total_actual_path_too_large
        exec 7< "${'$'}actual_path" || fail open_failed; fd=/proc/${'$'}${'$'}/fd/7
        [ -f "${'$'}fd" ] || fail not_regular_file
        before=${'$'}(stat -Lc '%d:%i:%s:%a:%y:%z' -- "${'$'}fd") || fail stat_failed
        size=${'$'}(stat -Lc '%s' -- "${'$'}fd") || fail stat_failed
        [ "${'$'}size" -le ${MAX_TERMUX_TRANSFER_BYTES} ] || fail source_too_large
        total_size=${'$'}((total_size + size)); [ "${'$'}total_size" -le ${MAX_TERMUX_EDIT_TOTAL_SOURCE_BYTES} ] || fail total_source_too_large
        sha=${'$'}(sha256sum < "${'$'}fd" | cut -d' ' -f1) || fail hash_failed
        [ "${'$'}expected" = - ] || [ "${'$'}expected" = "${'$'}sha" ] || fail stale_source
        mode=${'$'}(stat -Lc '%a' -- "${'$'}fd") || fail stat_failed
        snap=${'$'}transfer_dir/snapshot-${'$'}i
        mkdir -- "${'$'}snap" || fail unsafe_transfer_state; chmod 700 -- "${'$'}snap"
        cat -- "${'$'}fd" > "${'$'}snap/payload" || fail copy_failed; chmod 600 -- "${'$'}snap/payload"
        after=${'$'}(stat -Lc '%d:%i:%s:%a:%y:%z' -- "${'$'}fd") || fail source_changed
        copied_sha=${'$'}(sha256sum < "${'$'}snap/payload" | cut -d' ' -f1) || fail hash_failed
        path_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path" 2>/dev/null) || fail source_changed
        fd_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}fd") || fail source_changed
        [ "${'$'}before" = "${'$'}after" ] && [ "${'$'}sha" = "${'$'}copied_sha" ] && [ "${'$'}path_identity" = "${'$'}fd_identity" ] || fail source_changed
        for prior_identity in "${'$'}{identities[@]-}"; do [ "${'$'}prior_identity" != "${'$'}fd_identity" ] || fail duplicate_identity; done
        actuals+=("${'$'}actual_b64"); path_shas+=("${'$'}path_sha"); shas+=("${'$'}sha"); modes+=("${'$'}mode"); identities+=("${'$'}fd_identity"); sizes+=("${'$'}size")
        exec 7<&-; i=${'$'}((i + 1))
    done
    printf '%s\nrequest_id=%s\ncount=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}count"
    i=0; while [ "${'$'}i" -lt "${'$'}count" ]; do
        printf 'item=%s,%s,%s,%s,%s,%s,%s\n' "${'$'}i" "${'$'}{actuals[${'$'}i]}" "${'$'}{path_shas[${'$'}i]}" "${'$'}{shas[${'$'}i]}" "${'$'}{modes[${'$'}i]}" "${'$'}{identities[${'$'}i]}" "${'$'}{sizes[${'$'}i]}"
        i=${'$'}((i + 1))
    done
    cleanup_on_error=0
""".trimIndent()

internal val TERMUX_EDIT_READ_SNAPSHOT_SCRIPT = """
    ${TERMUX_TRANSFER_COMMON_SCRIPT}
    index=${'$'}2; offset=${'$'}3; length=${'$'}4
    marker=RIKKAHUB_EDIT_CHUNK_V1
    fail() { printf '%s\nrequest_id=%s\nerror=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}1"; exit 0; }
    valid_uint "${'$'}index" && valid_uint "${'$'}offset" && valid_uint "${'$'}length" || fail invalid_arguments
    [ "${'$'}index" -lt ${MAX_TERMUX_EDIT_FILES} ] && [ "${'$'}length" -le ${TERMUX_TRANSFER_CHUNK_BYTES} ] || fail invalid_arguments
    payload=${'$'}transfer_dir/snapshot-${'$'}index/payload
    [ -f "${'$'}payload" ] && [ ! -L "${'$'}payload" ] || fail missing_snapshot
    total=${'$'}(wc -c < "${'$'}payload" | tr -d ' ')
    [ "${'$'}offset" -le "${'$'}total" ] && [ "${'$'}length" -le "${'$'}((total - offset))" ] || fail invalid_range
    data=${'$'}(dd if="${'$'}payload" bs=1 skip="${'$'}offset" count="${'$'}length" status=none | base64 -w0)
    printf '%s\nrequest_id=%s\nindex=%s\noffset=%s\nlength=%s\ndata_b64=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}index" "${'$'}offset" "${'$'}length" "${'$'}data"
""".trimIndent()

internal val TERMUX_EDIT_PUBLISH_SCRIPT = """
    ${TERMUX_TRANSFER_COMMON_SCRIPT}
    ${TERMUX_PATH_RESOLVER_SCRIPT}
    marker=RIKKAHUB_EDIT_PUBLISH_V1; count=${'$'}2; shift 2
    actuals=(); path_shas=(); old_shas=(); modes=(); identities=(); stage_ids=(); new_shas=(); changed=(); temps=(); states=(); published_identities=(); rollback_temp=
    emit() { printf '%s\nrequest_id=%s\ncount=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}count"; [ -z "${'$'}{1:-}" ] || printf 'error=%s\n' "${'$'}1"; i=0; while [ "${'$'}i" -lt "${'$'}count" ]; do printf 'item=%s,%s,%s,%s,%s\n' "${'$'}i" "${'$'}{actuals[${'$'}i]:-}" "${'$'}{old_shas[${'$'}i]:-}" "${'$'}{new_shas[${'$'}i]:-}" "${'$'}{states[${'$'}i]:-not_published}"; i=${'$'}((i+1)); done; }
    bare_fail() { emit "${'$'}1"; exit 0; }
    cleanup() { [ -z "${'$'}{temp:-}" ] || rm -f -- "${'$'}temp"; for staged_temp in "${'$'}{temps[@]-}"; do [ -z "${'$'}staged_temp" ] || rm -f -- "${'$'}staged_temp"; done; [ -z "${'$'}rollback_temp" ] || rm -f -- "${'$'}rollback_temp"; }
    decode_actual_path() {
        encoded=${'$'}1
        IFS= read -r -d '' actual_path < <(printf '%s' "${'$'}encoded" | base64 -d 2>/dev/null; printf '\0') || return 1
        [ "${'$'}(printf '%s' "${'$'}actual_path" | base64 -w0)" = "${'$'}encoded" ] || return 1
    }
    trap cleanup EXIT HUP INT TERM
    valid_uint "${'$'}count" && [ "${'$'}count" -ge 1 ] && [ "${'$'}count" -le ${MAX_TERMUX_EDIT_FILES} ] || bare_fail invalid_count
    [ "${'$'}#" -eq "${'$'}((count * 8))" ] || bare_fail invalid_arguments
    raw_args=("${'$'}@")
    i=0; while [ "${'$'}i" -lt "${'$'}count" ]; do
        offset=${'$'}((i * 8))
        actuals+=("${'$'}{raw_args[${'$'}offset]}")
        old_shas+=("${'$'}{raw_args[${'$'}((offset + 2))]}")
        new_shas+=("${'$'}{raw_args[${'$'}((offset + 6))]}")
        states+=(not_published)
        i=${'$'}((i + 1))
    done
    write_lock=${'$'}cache_root/write.lock
    if [ -e "${'$'}write_lock" ] || [ -L "${'$'}write_lock" ]; then [ -f "${'$'}write_lock" ] && [ ! -L "${'$'}write_lock" ] || bare_fail unsafe_lock; fi
    : >> "${'$'}write_lock"; chmod 600 -- "${'$'}write_lock"; exec 9>> "${'$'}write_lock"; flock -x 9
    total_result=0; i=0
    while [ "${'$'}i" -lt "${'$'}count" ]; do
        path_b64=${'$'}1; path_sha=${'$'}2; old_sha=${'$'}3; mode=${'$'}4; identity=${'$'}5; stage_id=${'$'}6; new_sha=${'$'}7; is_changed=${'$'}8; shift 8
        valid_sha256 "${'$'}path_sha" && valid_sha256 "${'$'}old_sha" && valid_sha256 "${'$'}new_sha" || bare_fail invalid_sha256
        [[ "${'$'}mode" =~ ^[0-7]{3,4}${'$'} ]] || bare_fail invalid_mode
        [[ "${'$'}identity" =~ ^[0-9]+:[0-9]+${'$'} ]] || bare_fail invalid_identity
        [ "${'$'}is_changed" = 0 ] || [ "${'$'}is_changed" = 1 ] || bare_fail invalid_changed
        decoded_sha=${'$'}(printf '%s' "${'$'}path_b64" | base64 -d 2>/dev/null | sha256sum | cut -d' ' -f1) || bare_fail invalid_path
        [ "${'$'}decoded_sha" = "${'$'}path_sha" ] || bare_fail invalid_path
        set +e; resolve_termux_path "${'$'}path_b64"; rc=${'$'}?; set -e; [ "${'$'}rc" -eq 0 ] || bare_fail invalid_path
        actual_b64=${'$'}(printf '%s' "${'$'}actual_path" | base64 -w0)
        [ "${'$'}actual_b64" = "${'$'}{actuals[${'$'}i]}" ] || bare_fail invalid_path
        j=0; while [ "${'$'}j" -lt "${'$'}i" ]; do [ "${'$'}{actuals[${'$'}j]}" != "${'$'}actual_b64" ] || bare_fail duplicate_path; j=${'$'}((j + 1)); done
        [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] || bare_fail source_changed
        current_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path") || bare_fail source_changed
        current_mode=${'$'}(stat -Lc '%a' -- "${'$'}actual_path") || bare_fail source_changed
        current_sha=${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1) || bare_fail source_changed
        [ "${'$'}current_identity" = "${'$'}identity" ] && [ "${'$'}current_mode" = "${'$'}mode" ] && [ "${'$'}current_sha" = "${'$'}old_sha" ] || bare_fail source_changed
        temp=
        if [ "${'$'}is_changed" = 1 ]; then
            valid_request_id "${'$'}stage_id" || bare_fail invalid_stage
            stage=${'$'}transfer_root/${'$'}stage_id/payload
            [ -f "${'$'}stage" ] && [ ! -L "${'$'}stage" ] || bare_fail missing_transfer
            stage_size=${'$'}(wc -c < "${'$'}stage" | tr -d ' '); [ "${'$'}stage_size" -le ${MAX_TERMUX_TRANSFER_BYTES} ] || bare_fail result_too_large
            total_result=${'$'}((total_result + stage_size)); [ "${'$'}total_result" -le ${MAX_TERMUX_EDIT_TOTAL_RESULT_BYTES} ] || bare_fail total_result_too_large
            [ "${'$'}(sha256sum < "${'$'}stage" | cut -d' ' -f1)" = "${'$'}new_sha" ] || bare_fail transfer_sha_mismatch
            parent=${'$'}{actual_path%/*}; [ -n "${'$'}parent" ] || parent=/; [ -d "${'$'}parent" ] || bare_fail parent_not_directory
            temp=${'$'}(mktemp "${'$'}parent/.rikkahub-edit.${'$'}request_id.XXXXXXXX") || bare_fail temp_failed
            [ -f "${'$'}temp" ] && [ ! -L "${'$'}temp" ] || bare_fail unsafe_temp_path
            cat -- "${'$'}stage" > "${'$'}temp" || bare_fail copy_failed; chmod "${'$'}mode" -- "${'$'}temp" || bare_fail mode_failed
            [ "${'$'}(sha256sum < "${'$'}temp" | cut -d' ' -f1)" = "${'$'}new_sha" ] || bare_fail publication_changed
            sync "${'$'}temp" || bare_fail data_sync_failed
        else [ "${'$'}stage_id" = - ] && [ "${'$'}new_sha" = "${'$'}old_sha" ] || bare_fail publication_changed; fi
        path_shas+=("${'$'}path_sha"); modes+=("${'$'}mode"); identities+=("${'$'}identity"); stage_ids+=("${'$'}stage_id"); changed+=("${'$'}is_changed"); temps+=("${'$'}temp")
        i=${'$'}((i+1))
    done
    i=0; while [ "${'$'}i" -lt "${'$'}count" ]; do
        decode_actual_path "${'$'}{actuals[${'$'}i]}" || bare_fail invalid_path
        [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] || bare_fail source_changed
        [ "${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path")" = "${'$'}{identities[${'$'}i]}" ] && [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" = "${'$'}{modes[${'$'}i]}" ] && [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" = "${'$'}{old_shas[${'$'}i]}" ] || bare_fail source_changed
        i=${'$'}((i+1))
    done
    publish_error=; i=0
    while [ "${'$'}i" -lt "${'$'}count" ]; do
        if [ "${'$'}{changed[${'$'}i]}" = 1 ]; then
            decode_actual_path "${'$'}{actuals[${'$'}i]}" || { publish_error=invalid_path; break; }; parent=${'$'}{actual_path%/*}; [ -n "${'$'}parent" ] || parent=/; temp=${'$'}{temps[${'$'}i]}
            [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] && [ "${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path")" = "${'$'}{identities[${'$'}i]}" ] && [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" = "${'$'}{modes[${'$'}i]}" ] && [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" = "${'$'}{old_shas[${'$'}i]}" ] || { publish_error=source_changed; break; }
            temp_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}temp") || { publish_error=publication_changed; break; }
            if ! mv -Tf -- "${'$'}temp" "${'$'}actual_path"; then publish_error=publish_failed; break; fi
            temps[${'$'}i]=; states[${'$'}i]=published
            if [ -L "${'$'}actual_path" ] || [ ! -f "${'$'}actual_path" ] || [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" != "${'$'}{modes[${'$'}i]}" ] || [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" != "${'$'}{new_shas[${'$'}i]}" ]; then publish_error=publication_changed; break; fi
            published_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path") || { publish_error=publication_changed; break; }; [ "${'$'}published_identity" = "${'$'}temp_identity" ] || { publish_error=publication_changed; break; }; published_identities[${'$'}i]=${'$'}published_identity
            if ! sync "${'$'}actual_path" || ! sync "${'$'}parent"; then publish_error=post_publish_sync_failed; break; fi
            if [ -L "${'$'}actual_path" ] || [ ! -f "${'$'}actual_path" ] || [ "${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path")" != "${'$'}published_identity" ] || [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" != "${'$'}{modes[${'$'}i]}" ] || [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" != "${'$'}{new_shas[${'$'}i]}" ]; then publish_error=publication_changed; break; fi
        else states[${'$'}i]=no_change; fi
        i=${'$'}((i+1))
    done
    if [ -n "${'$'}publish_error" ]; then
        j=${'$'}i; while [ "${'$'}j" -ge 0 ]; do
            if [ "${'$'}{states[${'$'}j]:-}" = published ]; then
                if ! decode_actual_path "${'$'}{actuals[${'$'}j]}"; then states[${'$'}j]=rollback_failed; j=${'$'}((j-1)); continue; fi; snap=${'$'}transfer_dir/snapshot-${'$'}j/payload; parent=${'$'}{actual_path%/*}; [ -n "${'$'}parent" ] || parent=/
                rollback_temp=${'$'}(mktemp "${'$'}parent/.rikkahub-rollback.${'$'}request_id.XXXXXXXX") || { states[${'$'}j]=rollback_failed; j=${'$'}((j-1)); continue; }
                rollback_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}rollback_temp") || { rm -f -- "${'$'}rollback_temp"; rollback_temp=; states[${'$'}j]=rollback_failed; j=${'$'}((j-1)); continue; }
                if cat -- "${'$'}snap" > "${'$'}rollback_temp" && chmod "${'$'}{modes[${'$'}j]}" -- "${'$'}rollback_temp" && [ ! -L "${'$'}rollback_temp" ] && [ -f "${'$'}rollback_temp" ] && [ "${'$'}(stat -Lc '%a' -- "${'$'}rollback_temp")" = "${'$'}{modes[${'$'}j]}" ] && [ "${'$'}(sha256sum < "${'$'}rollback_temp" | cut -d' ' -f1)" = "${'$'}{old_shas[${'$'}j]}" ] && sync "${'$'}rollback_temp" && [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] && [ "${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path")" = "${'$'}{published_identities[${'$'}j]:-}" ] && [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" = "${'$'}{modes[${'$'}j]}" ] && [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" = "${'$'}{new_shas[${'$'}j]}" ] && mv -Tf -- "${'$'}rollback_temp" "${'$'}actual_path" && { rollback_temp=; true; } && [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] && [ "${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path")" = "${'$'}rollback_identity" ] && [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" = "${'$'}{modes[${'$'}j]}" ] && [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" = "${'$'}{old_shas[${'$'}j]}" ] && sync "${'$'}actual_path" && sync "${'$'}parent" && [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] && [ "${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path")" = "${'$'}rollback_identity" ] && [ "${'$'}(stat -Lc '%a' -- "${'$'}actual_path")" = "${'$'}{modes[${'$'}j]}" ] && [ "${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1)" = "${'$'}{old_shas[${'$'}j]}" ]; then states[${'$'}j]=rolled_back; else rm -f -- "${'$'}rollback_temp"; rollback_temp=; states[${'$'}j]=rollback_failed; fi
            fi
            j=${'$'}((j-1))
        done
        emit "${'$'}publish_error"; exit 0
    fi
    emit; exit 0
""".trimIndent()

private val SNAPSHOT_ERROR_CODES = setOf(
    "invalid_count", "invalid_arguments", "unsafe_transfer_state", "unsafe_lock",
    "invalid_expected_sha256", "invalid_path", "symlink_rejected", "not_found",
    "not_regular_file", "duplicate_identity", "open_failed", "stat_failed",
    "source_too_large", "total_source_too_large", "total_actual_path_too_large", "hash_failed", "stale_source",
    "copy_failed", "source_changed",
)
private val CHUNK_ERROR_CODES = setOf("invalid_arguments", "missing_snapshot", "invalid_range")
private val PUBLISH_ERROR_CODES = setOf(
    "invalid_count", "invalid_arguments", "unsafe_lock", "invalid_sha256", "invalid_mode",
    "invalid_identity", "invalid_changed", "invalid_path", "duplicate_path", "source_changed",
    "invalid_stage", "missing_transfer", "result_too_large", "total_result_too_large",
    "transfer_sha_mismatch", "parent_not_directory", "temp_failed", "unsafe_temp_path",
    "copy_failed", "mode_failed", "publication_changed", "data_sync_failed", "publish_failed",
    "post_publish_sync_failed",
)
private fun parseLines(text: String, marker: String, expectedRequestId: String): Pair<Map<String, String>, List<String>>? {
    if (text.length > TERMUX_EDIT_PROTOCOL_MAX_CHARS || !isValidRequestId(expectedRequestId)) return null
    val lines = text.removeSuffix("\n").split('\n')
    if (lines.firstOrNull() != marker) return null
    val fields = mutableMapOf<String, String>(); val items = mutableListOf<String>()
    for (line in lines.drop(1)) {
        val split = line.indexOf('='); if (split <= 0) return null
        val key = line.substring(0, split); val value = line.substring(split + 1)
        if (key == "item") items += value else if (fields.put(key, value) != null) return null
    }
    if (fields["request_id"] != expectedRequestId) return null
    return fields to items
}

internal fun parseTermuxEditSnapshots(
    text: String,
    requestId: String,
    expectedPathShas: List<String>,
): TermuxTransferProtocolResult<List<TermuxEditSnapshot>> {
    val parsed = parseLines(text, "RIKKAHUB_EDIT_SNAPSHOT_V1", requestId) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    parsed.first["error"]?.let { error ->
        return if (parsed.first.keys == setOf("request_id", "error") && parsed.second.isEmpty() && error in SNAPSHOT_ERROR_CODES) TermuxTransferProtocolResult.Error(error) else TermuxTransferProtocolResult.Error("invalid_protocol")
    }
    val expectedCount = expectedPathShas.size
    if (parsed.first.keys != setOf("request_id", "count") || parsed.first["count"] != expectedCount.toString() || parsed.second.size != expectedCount) return TermuxTransferProtocolResult.Error("invalid_protocol")
    var total = 0L
    var totalActualPathBytes = 0L
    val identities = mutableSetOf<String>()
    val actualPaths = mutableSetOf<String>()
    val snapshots = parsed.second.mapIndexed { expectedIndex, item ->
        val values = item.split(',', limit = 7); if (values.size != 7 || values[0] != expectedIndex.toString()) return TermuxTransferProtocolResult.Error("response_mismatch")
        val actualBytes = decodeCanonicalBase64(values[1], MAX_TERMUX_ACTUAL_PATH_BYTES) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
        totalActualPathBytes += actualBytes.size
        if (totalActualPathBytes > MAX_TERMUX_EDIT_TOTAL_ACTUAL_PATH_BYTES) return TermuxTransferProtocolResult.Error("response_mismatch")
        val actual = decodeStrictUtf8(actualBytes, true) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
        val bytes = values[6].toIntOrNull()?.takeIf { it in 0..MAX_TERMUX_TRANSFER_BYTES } ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
        total += bytes
        if (total > MAX_TERMUX_EDIT_TOTAL_SOURCE_BYTES || values[2] != expectedPathShas[expectedIndex] || !isValidSha256(values[2]) || !isValidSha256(values[3]) || !values[4].matches(Regex("^[0-7]{3,4}$")) || !values[5].matches(Regex("^[0-9]+:[0-9]+$"))) return TermuxTransferProtocolResult.Error("response_mismatch")
        if (!actualPaths.add(actual) || !identities.add(values[5])) return TermuxTransferProtocolResult.Error("response_mismatch")
        TermuxEditSnapshot(expectedIndex, actual, values[2], values[3], values[4], values[5], bytes)
    }
    return TermuxTransferProtocolResult.Ok(snapshots)
}

internal fun parseTermuxEditChunk(text: String, requestId: String, index: Int, offset: Int, length: Int): TermuxTransferProtocolResult<ByteArray> {
    val parsed = parseLines(text, "RIKKAHUB_EDIT_CHUNK_V1", requestId) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    parsed.first["error"]?.let { error ->
        return if (parsed.first.keys == setOf("request_id", "error") && parsed.second.isEmpty() && error in CHUNK_ERROR_CODES) TermuxTransferProtocolResult.Error(error) else TermuxTransferProtocolResult.Error("invalid_protocol")
    }
    val expected = setOf("request_id", "index", "offset", "length", "data_b64")
    if (parsed.first.keys != expected || parsed.second.isNotEmpty() || parsed.first["index"] != index.toString() || parsed.first["offset"] != offset.toString() || parsed.first["length"] != length.toString()) return TermuxTransferProtocolResult.Error("response_mismatch")
    val bytes = decodeCanonicalBase64(parsed.first.getValue("data_b64"), TERMUX_TRANSFER_CHUNK_BYTES) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    return if (bytes.size == length) TermuxTransferProtocolResult.Ok(bytes) else TermuxTransferProtocolResult.Error("response_mismatch")
}

internal fun parseTermuxEditPublish(
    text: String,
    requestId: String,
    expected: List<Triple<String, String, String>>,
): TermuxTransferProtocolResult<TermuxEditPublishResult> {
    val parsed = parseLines(text, "RIKKAHUB_EDIT_PUBLISH_V1", requestId) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    val expectedCount = expected.size
    if (parsed.first["count"] != expectedCount.toString() || parsed.second.size != expectedCount) return TermuxTransferProtocolResult.Error("invalid_protocol")
    val error = parsed.first["error"]
    val validKeys = if (error == null) setOf("request_id", "count") else setOf("request_id", "count", "error")
    if (parsed.first.keys != validKeys || error != null && error !in PUBLISH_ERROR_CODES) return TermuxTransferProtocolResult.Error("invalid_protocol")
    val orderedRollbackErrors = setOf("source_changed", "publication_changed", "publish_failed", "post_publish_sync_failed")
    val prePublicationErrors = PUBLISH_ERROR_CODES - orderedRollbackErrors

    val items = parsed.second.mapIndexed { expectedIndex, item ->
        val values = item.split(',', limit = 5); if (values.size != 5 || values[0] != expectedIndex.toString()) return TermuxTransferProtocolResult.Error("response_mismatch")
        val actual = decodeCanonicalBase64(values[1], MAX_TERMUX_ACTUAL_PATH_BYTES)?.let { decodeStrictUtf8(it, true) }
            ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
        val sourceSha = values[2].takeIf(::isValidSha256) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
        val resultSha = values[3].takeIf(::isValidSha256) ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
        val state = values[4]
        if (state !in setOf("published", "no_change", "not_published", "rolled_back", "rollback_failed")) return TermuxTransferProtocolResult.Error("invalid_protocol")
        val exp = expected[expectedIndex]
        if (actual != exp.first || sourceSha != exp.second || resultSha != exp.third) return TermuxTransferProtocolResult.Error("response_mismatch")
        val changed = sourceSha != resultSha
        val validState = when {
            error == null && changed -> state == "published"
            error == null -> state == "no_change"
            error in prePublicationErrors -> state == "not_published"
            changed -> state in setOf("not_published", "rolled_back", "rollback_failed")
            else -> state in setOf("not_published", "no_change")
        }
        if (!validState) return TermuxTransferProtocolResult.Error("response_mismatch")
        TermuxEditPublishItem(expectedIndex, actual, sourceSha, resultSha, state)
    }
    if (error != null && error in orderedRollbackErrors) {
        var pending = false
        var restored = false
        for (item in items) {
            if (item.state == "not_published") pending = true
            else {
                if (pending) return TermuxTransferProtocolResult.Error("response_mismatch")
                if (item.state in setOf("rolled_back", "rollback_failed")) restored = true
            }
        }
        if (error in setOf("source_changed", "publish_failed") && !pending) return TermuxTransferProtocolResult.Error("response_mismatch")
        if (error == "post_publish_sync_failed" && !restored) return TermuxTransferProtocolResult.Error("response_mismatch")
    }
    return TermuxTransferProtocolResult.Ok(TermuxEditPublishResult(error == null, items, error))
}
