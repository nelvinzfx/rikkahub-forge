package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.UUID

internal const val TERMUX_TRANSFER_HELPER_TIMEOUT_MS = 20_000L
private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
internal const val TERMUX_EXTERNAL_WRITER_BOUNDARY = "expected_sha256 serializes RikkaHub writers and fails closed on detected external changes; an uncooperative same-UID Termux writer can race the final check-to-rename boundary."
internal const val TERMUX_DURABILITY_CONTRACT = "Publication fully syncs the temporary file before rename/link, then fully syncs the published file and final parent directory. A post-publication sync failure is reported even though publication may already have happened. Recursively created ancestor directories are not individually synced."

internal val TERMUX_TRANSFER_COMMON_SCRIPT = """
    set -euo pipefail
    export LC_ALL=C
    umask 077
    request_id=${'$'}1
    valid_request_id() { [[ "${'$'}1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]]; }
    valid_sha256() { [[ "${'$'}1" =~ ^[0-9a-f]{64}${'$'} ]]; }
    valid_uint() { [[ "${'$'}1" =~ ^(0|[1-9][0-9]*)${'$'} ]]; }
    valid_request_id "${'$'}request_id" || exit 64
    cache_root=${'$'}HOME/.cache/rikkahub
    transfer_root=${'$'}cache_root/transfers
    secure_root() {
        [ ! -L "${'$'}HOME/.cache" ] || return 1
        mkdir -p -- "${'$'}HOME/.cache" || return 1
        [ -d "${'$'}HOME/.cache" ] || return 1
        [ ! -L "${'$'}cache_root" ] || return 1
        mkdir -p -- "${'$'}cache_root" || return 1
        [ -d "${'$'}cache_root" ] && [ ! -L "${'$'}cache_root" ] || return 1
        chmod 700 -- "${'$'}cache_root" || return 1
        [ ! -L "${'$'}transfer_root" ] || return 1
        mkdir -p -- "${'$'}transfer_root" || return 1
        [ -d "${'$'}transfer_root" ] && [ ! -L "${'$'}transfer_root" ] || return 1
        chmod 700 -- "${'$'}transfer_root" || return 1
    }
    secure_root || exit 65
    # Cooperative same-UID boundary: RikkaHub validates this regular non-symlink lock,
    # but another Termux process with the same UID can replace cache objects outside this protocol.
    transfer_lock=${'$'}cache_root/transfer.lock
    if [ -e "${'$'}transfer_lock" ] || [ -L "${'$'}transfer_lock" ]; then
        [ -f "${'$'}transfer_lock" ] && [ ! -L "${'$'}transfer_lock" ] || exit 65
    fi
    : >> "${'$'}transfer_lock"; chmod 600 -- "${'$'}transfer_lock"
    exec 8>> "${'$'}transfer_lock"; flock -x 8
    while IFS= read -r -d '' old; do
        name=${'$'}{old##*/}
        if valid_request_id "${'$'}name" && [ -d "${'$'}old" ] && [ ! -L "${'$'}old" ]; then rm -rf -- "${'$'}old"; fi
    done < <(find "${'$'}transfer_root" -mindepth 1 -maxdepth 1 -type d -mmin +${TERMUX_TRANSFER_TTL_MINUTES} -print0 2>/dev/null)
    transfer_dir=${'$'}transfer_root/${'$'}request_id
""".trimIndent()

internal val TERMUX_STAGE_CHUNK_SCRIPT = """
    ${TERMUX_TRANSFER_COMMON_SCRIPT}
    index=${'$'}2; count=${'$'}3; total=${'$'}4; full_sha=${'$'}5; chunk_b64=${'$'}6
    fail() { printf 'RIKKAHUB_TRANSFER_V1\nrequest_id=%s\nerror=%s\n' "${'$'}request_id" "${'$'}1"; exit 0; }
    valid_uint "${'$'}index" && valid_uint "${'$'}count" && valid_uint "${'$'}total" || fail invalid_chunk_metadata
    valid_sha256 "${'$'}full_sha" || fail invalid_chunk_metadata
    [ "${'$'}count" -ge 1 ] && [ "${'$'}index" -lt "${'$'}count" ] || fail invalid_chunk_metadata
    [ "${'$'}total" -le ${MAX_TERMUX_TRANSFER_BYTES} ] || fail transfer_too_large
    expected_count=${'$'}(( (total + ${TERMUX_TRANSFER_CHUNK_BYTES} - 1) / ${TERMUX_TRANSFER_CHUNK_BYTES} )); [ "${'$'}expected_count" -ge 1 ] || expected_count=1
    [ "${'$'}count" -eq "${'$'}expected_count" ] || fail invalid_chunk_metadata
    [ "${'$'}{#chunk_b64}" -le 32768 ] || fail chunk_too_large
    if [ "${'$'}index" -lt "${'$'}((count - 1))" ]; then expected_chunk=${TERMUX_TRANSFER_CHUNK_BYTES}; else expected_chunk=${'$'}((total - index * ${TERMUX_TRANSFER_CHUNK_BYTES})); fi
    [ "${'$'}expected_chunk" -ge 0 ] && [ "${'$'}expected_chunk" -le ${TERMUX_TRANSFER_CHUNK_BYTES} ] || fail invalid_chunk_metadata
    chunk_tmp=${'$'}(mktemp "${'$'}transfer_root/.chunk-${'$'}request_id.XXXXXXXX") || fail unsafe_transfer_state
    meta_tmp=
    trap 'rm -f -- "${'$'}chunk_tmp"; [ -z "${'$'}meta_tmp" ] || rm -f -- "${'$'}meta_tmp"' EXIT HUP INT TERM
    [ -f "${'$'}chunk_tmp" ] && [ ! -L "${'$'}chunk_tmp" ] || fail unsafe_transfer_state
    printf '%s' "${'$'}chunk_b64" | base64 -d > "${'$'}chunk_tmp" 2>/dev/null || fail invalid_chunk_base64
    chmod 600 -- "${'$'}chunk_tmp"
    [ "${'$'}(wc -c < "${'$'}chunk_tmp" | tr -d ' ')" -eq "${'$'}expected_chunk" ] || fail invalid_chunk_size
    [ "${'$'}(base64 -w0 < "${'$'}chunk_tmp")" = "${'$'}chunk_b64" ] || fail invalid_chunk_base64
    if [ "${'$'}index" -eq 0 ]; then
        if [ -e "${'$'}transfer_dir" ] || [ -L "${'$'}transfer_dir" ]; then fail duplicate_chunk; fi
        mkdir -- "${'$'}transfer_dir" || fail unsafe_transfer_state
        [ -d "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ] || fail unsafe_transfer_state
        chmod 700 -- "${'$'}transfer_dir"
        : > "${'$'}transfer_dir/payload"; chmod 600 -- "${'$'}transfer_dir/payload"
        printf 'count=%s\ntotal=%s\nsha256=%s\nnext=0\n' "${'$'}count" "${'$'}total" "${'$'}full_sha" > "${'$'}transfer_dir/meta"
        chmod 600 -- "${'$'}transfer_dir/meta"
    fi
    [ -d "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ] || fail missing_transfer
    [ "${'$'}(stat -c '%a' -- "${'$'}transfer_dir")" = 700 ] || fail unsafe_transfer_state
    unknown=${'$'}(find "${'$'}transfer_dir" -mindepth 1 -maxdepth 1 ! -name meta ! -name payload -print -quit)
    [ -z "${'$'}unknown" ] || fail unsafe_transfer_state
    for f in meta payload; do [ -f "${'$'}transfer_dir/${'$'}f" ] && [ ! -L "${'$'}transfer_dir/${'$'}f" ] || fail unsafe_transfer_state; [ "${'$'}(stat -c '%a' -- "${'$'}transfer_dir/${'$'}f")" = 600 ] || fail unsafe_transfer_state; done
    meta_count= meta_total= meta_sha= next=
    while IFS='=' read -r key value; do
        case "${'$'}key" in count) [ -z "${'$'}meta_count" ] || fail unsafe_transfer_state; meta_count=${'$'}value;; total) [ -z "${'$'}meta_total" ] || fail unsafe_transfer_state; meta_total=${'$'}value;; sha256) [ -z "${'$'}meta_sha" ] || fail unsafe_transfer_state; meta_sha=${'$'}value;; next) [ -z "${'$'}next" ] || fail unsafe_transfer_state; next=${'$'}value;; *) fail unsafe_transfer_state;; esac
    done < "${'$'}transfer_dir/meta"
    [ "${'$'}meta_count" = "${'$'}count" ] && [ "${'$'}meta_total" = "${'$'}total" ] && [ "${'$'}meta_sha" = "${'$'}full_sha" ] || fail transfer_mismatch
    valid_uint "${'$'}next" || fail unsafe_transfer_state
    [ "${'$'}index" -eq "${'$'}next" ] || { [ "${'$'}index" -lt "${'$'}next" ] && fail duplicate_chunk || fail out_of_order_chunk; }
    [ "${'$'}(wc -c < "${'$'}transfer_dir/payload" | tr -d ' ')" -eq "${'$'}((index * ${TERMUX_TRANSFER_CHUNK_BYTES}))" ] || fail unsafe_transfer_state
    cat -- "${'$'}chunk_tmp" >> "${'$'}transfer_dir/payload"
    [ -f "${'$'}transfer_dir/payload" ] && [ ! -L "${'$'}transfer_dir/payload" ] || fail unsafe_transfer_state
    new_next=${'$'}((next + 1)); meta_tmp=${'$'}(mktemp "${'$'}transfer_root/.meta-${'$'}request_id.XXXXXXXX") || fail unsafe_transfer_state
    [ -f "${'$'}meta_tmp" ] && [ ! -L "${'$'}meta_tmp" ] || fail unsafe_transfer_state
    printf 'count=%s\ntotal=%s\nsha256=%s\nnext=%s\n' "${'$'}count" "${'$'}total" "${'$'}full_sha" "${'$'}new_next" > "${'$'}meta_tmp"
    chmod 600 -- "${'$'}meta_tmp"; mv -T -- "${'$'}meta_tmp" "${'$'}transfer_dir/meta"
    meta_tmp=
    printf 'RIKKAHUB_TRANSFER_V1\nrequest_id=%s\nindex=%s\n' "${'$'}request_id" "${'$'}index"
""".trimIndent()

internal val TERMUX_TRANSFER_CLEANUP_SCRIPT = """
    ${TERMUX_TRANSFER_COMMON_SCRIPT}
    if [ -e "${'$'}transfer_dir" ] || [ -L "${'$'}transfer_dir" ]; then
        [ -d "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ] || exit 65
        rm -rf -- "${'$'}transfer_dir"
    fi
    printf 'RIKKAHUB_TRANSFER_V1\nrequest_id=%s\nindex=0\n' "${'$'}request_id"
""".trimIndent()

internal val TERMUX_ATOMIC_WRITE_SCRIPT = """
    ${TERMUX_TRANSFER_COMMON_SCRIPT}
    ${TERMUX_PATH_RESOLVER_SCRIPT}
    path_b64=${'$'}2; expected_sha=${'$'}3; create_only=${'$'}4; expected_path_sha=${'$'}5
    marker=RIKKAHUB_WRITE_V1; actual_b64=; temp=; probe_prefix=
    cleanup() { [ -z "${'$'}temp" ] || rm -f -- "${'$'}temp"; [ -z "${'$'}probe_prefix" ] || rm -rf -- "${'$'}probe_prefix".*; if [ -d "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ]; then rm -rf -- "${'$'}transfer_dir"; fi; }
    trap cleanup EXIT HUP INT TERM
    bare_error() { printf '%s\nrequest_id=%s\nerror=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}1"; exit 0; }
    path_error() { printf '%s\nrequest_id=%s\nerror=%s\nactual_path_b64=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}1" "${'$'}actual_b64"; exit 0; }
    stale_error() { printf '%s\nrequest_id=%s\nerror=stale_source\nactual_path_b64=%s\ncurrent_sha256=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}1"; exit 0; }
    [ "${'$'}create_only" = 0 ] || [ "${'$'}create_only" = 1 ] || bare_error invalid_create_only
    [ "${'$'}expected_sha" = - ] || valid_sha256 "${'$'}expected_sha" || bare_error invalid_expected_sha256
    valid_sha256 "${'$'}expected_path_sha" || bare_error invalid_path
    [ "${'$'}create_only" = 0 ] || [ "${'$'}expected_sha" = - ] || bare_error conflicting_guards
    [ -d "${'$'}transfer_dir" ] && [ ! -L "${'$'}transfer_dir" ] || bare_error missing_transfer
    unknown=${'$'}(find "${'$'}transfer_dir" -mindepth 1 -maxdepth 1 ! -name meta ! -name payload -print -quit); [ -z "${'$'}unknown" ] || bare_error unsafe_transfer_state
    for f in meta payload; do [ -f "${'$'}transfer_dir/${'$'}f" ] && [ ! -L "${'$'}transfer_dir/${'$'}f" ] || bare_error unsafe_transfer_state; [ "${'$'}(stat -c '%a' -- "${'$'}transfer_dir/${'$'}f")" = 600 ] || bare_error unsafe_transfer_state; done
    meta_count= meta_total= meta_sha= next=
    while IFS='=' read -r key value; do case "${'$'}key" in count) [ -z "${'$'}meta_count" ] || bare_error unsafe_transfer_state; meta_count=${'$'}value;; total) [ -z "${'$'}meta_total" ] || bare_error unsafe_transfer_state; meta_total=${'$'}value;; sha256) [ -z "${'$'}meta_sha" ] || bare_error unsafe_transfer_state; meta_sha=${'$'}value;; next) [ -z "${'$'}next" ] || bare_error unsafe_transfer_state; next=${'$'}value;; *) bare_error unsafe_transfer_state;; esac; done < "${'$'}transfer_dir/meta"
    valid_uint "${'$'}meta_count" && valid_uint "${'$'}meta_total" && valid_uint "${'$'}next" && valid_sha256 "${'$'}meta_sha" || bare_error unsafe_transfer_state
    [ "${'$'}meta_total" -le ${MAX_TERMUX_TRANSFER_BYTES} ] && [ "${'$'}next" -eq "${'$'}meta_count" ] || bare_error incomplete_transfer
    [ "${'$'}(wc -c < "${'$'}transfer_dir/payload" | tr -d ' ')" -eq "${'$'}meta_total" ] || bare_error transfer_size_mismatch
    [ "${'$'}(sha256sum < "${'$'}transfer_dir/payload" | cut -d' ' -f1)" = "${'$'}meta_sha" ] || bare_error transfer_sha_mismatch
    actual_path_sha=${'$'}(printf '%s' "${'$'}path_b64" | base64 -d | sha256sum | cut -d' ' -f1) || bare_error invalid_path_encoding
    [ "${'$'}actual_path_sha" = "${'$'}expected_path_sha" ] || bare_error invalid_path
    set +e; resolve_termux_path "${'$'}path_b64"; resolver_rc=${'$'}?; set -e
    if [ "${'$'}resolver_rc" -ne 0 ]; then case "${'$'}resolver_rc" in 64) bare_error path_too_large;; 65) bare_error invalid_path_encoding;; 66) bare_error blank_path;; 68) bare_error tmp_escape;; *) bare_error invalid_path;; esac; fi
    actual_b64=${'$'}(printf '%s' "${'$'}actual_path" | base64 -w0)
    parent=${'$'}{actual_path%/*}; [ -n "${'$'}parent" ] || parent=/
    mkdir -p -- "${'$'}parent" 2>/dev/null || path_error parent_create_failed
    [ -d "${'$'}parent" ] || path_error parent_not_directory
    [ ! -L "${'$'}actual_path" ] || path_error symlink_rejected
    write_lock=${'$'}cache_root/write.lock
    if [ -e "${'$'}write_lock" ] || [ -L "${'$'}write_lock" ]; then [ -f "${'$'}write_lock" ] && [ ! -L "${'$'}write_lock" ] || path_error unsafe_lock; fi
    : >> "${'$'}write_lock"; chmod 600 -- "${'$'}write_lock"; exec 9>> "${'$'}write_lock"; flock -x 9
    exists=0; current_sha=${EMPTY_SHA256}; mode=600; before_stat=
    if [ -e "${'$'}actual_path" ]; then
        [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] || path_error not_regular_file
        exec 7< "${'$'}actual_path" || path_error open_failed
        fd_path=/proc/${'$'}${'$'}/fd/7
        [ -f "${'$'}fd_path" ] || path_error not_regular_file
        before_stat=${'$'}(stat -Lc '%d:%i:%s:%a:%y:%z' -- "${'$'}fd_path") || path_error stat_failed
        current_sha=${'$'}(sha256sum < "${'$'}fd_path" | cut -d' ' -f1) || path_error hash_failed
        mode=${'$'}(stat -Lc '%a' -- "${'$'}fd_path") || path_error stat_failed
        exists=1
    fi
    if [ "${'$'}expected_sha" != - ]; then
        if [ "${'$'}exists" -eq 0 ]; then stale_error "${EMPTY_SHA256}"; else [ "${'$'}current_sha" = "${'$'}expected_sha" ] || stale_error "${'$'}current_sha"; fi
    fi
    if [ "${'$'}create_only" = 1 ] && { [ -e "${'$'}actual_path" ] || [ -L "${'$'}actual_path" ]; }; then path_error already_exists; fi
    temp=${'$'}(mktemp "${'$'}parent/.rikkahub-write.${'$'}request_id.XXXXXXXX") || path_error temp_failed
    [ -f "${'$'}temp" ] && [ ! -L "${'$'}temp" ] || path_error unsafe_temp_path
    chmod 600 -- "${'$'}temp"
    cat -- "${'$'}transfer_dir/payload" >> "${'$'}temp" || path_error copy_failed
    chmod "${'$'}mode" -- "${'$'}temp" || path_error mode_failed
    final_total=${'$'}(wc -c < "${'$'}temp" | tr -d ' '); final_sha=${'$'}(sha256sum < "${'$'}temp" | cut -d' ' -f1) || path_error hash_failed
    [ "${'$'}final_total" -eq "${'$'}meta_total" ] && [ "${'$'}final_sha" = "${'$'}meta_sha" ] || path_error publication_changed
    temp_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}temp") || path_error stat_failed
    # Closest possible pre-publication recheck. The RikkaHub lock serializes cooperative writers;
    # an uncooperative same-UID writer can still race this check-to-rename interval.
    [ ! -L "${'$'}actual_path" ] || path_error symlink_rejected
    if [ "${'$'}exists" -eq 1 ]; then
        [ -f "${'$'}actual_path" ] || path_error source_changed
        after_stat=${'$'}(stat -Lc '%d:%i:%s:%a:%y:%z' -- "${'$'}fd_path") || path_error source_changed
        after_sha=${'$'}(sha256sum < "${'$'}fd_path" | cut -d' ' -f1) || path_error source_changed
        path_stat=${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path" 2>/dev/null) || path_error source_changed
        fd_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}fd_path") || path_error source_changed
        [ "${'$'}before_stat" = "${'$'}after_stat" ] && [ "${'$'}current_sha" = "${'$'}after_sha" ] && [ "${'$'}path_stat" = "${'$'}fd_identity" ] || path_error source_changed
    else
        [ ! -e "${'$'}actual_path" ] && [ ! -L "${'$'}actual_path" ] || { [ "${'$'}create_only" = 1 ] && path_error already_exists || path_error source_changed; }
    fi
    sync "${'$'}temp" || path_error data_sync_failed
    # Closest possible fd/path/stat/SHA and final-component type recheck, immediately before
    # the no-container publication primitive. This still cannot create CAS against an
    # uncooperative same-UID process in the check-to-rename interval.
    [ ! -L "${'$'}actual_path" ] || path_error symlink_rejected
    if [ "${'$'}exists" -eq 1 ]; then
        [ -f "${'$'}actual_path" ] || path_error source_changed
        publish_stat=${'$'}(stat -Lc '%d:%i:%s:%a:%y:%z' -- "${'$'}fd_path") || path_error source_changed
        publish_sha=${'$'}(sha256sum < "${'$'}fd_path" | cut -d' ' -f1) || path_error source_changed
        publish_path_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path" 2>/dev/null) || path_error source_changed
        [ "${'$'}publish_stat" = "${'$'}before_stat" ] && [ "${'$'}publish_sha" = "${'$'}current_sha" ] && [ "${'$'}publish_path_identity" = "${'$'}fd_identity" ] || path_error source_changed
    else
        [ ! -e "${'$'}actual_path" ] && [ ! -L "${'$'}actual_path" ] || { [ "${'$'}create_only" = 1 ] && path_error already_exists || path_error source_changed; }
    fi
    probe_cleanup() { [ -z "${'$'}probe_prefix" ] || rm -rf -- "${'$'}probe_prefix".*; }
    verify_noreplace_capability() {
        # Direct same-parent private objects provide target-filesystem evidence, not a cross-platform promise.
        probe_prefix=${'$'}parent/.rikkahub-noreplace.${'$'}request_id
        probe_src=${'$'}(mktemp "${'$'}probe_prefix.absent-src.XXXXXXXX") || return 1
        chmod 600 -- "${'$'}probe_src"; printf probe-source > "${'$'}probe_src"; probe_absent=${'$'}probe_src.absent
        [ ! -e "${'$'}probe_absent" ] && [ ! -L "${'$'}probe_absent" ] || return 1
        mv -nT -- "${'$'}probe_src" "${'$'}probe_absent" || return 1
        [ ! -e "${'$'}probe_src" ] && [ ! -L "${'$'}probe_src" ] && [ -f "${'$'}probe_absent" ] && [ ! -L "${'$'}probe_absent" ] || return 1
        [ "${'$'}(cat -- "${'$'}probe_absent")" = probe-source ] || return 1
        for kind in regular symlink directory; do
            src=${'$'}(mktemp "${'$'}probe_prefix.${'$'}kind-src.XXXXXXXX") || return 1
            chmod 600 -- "${'$'}src"; printf source-${'$'}kind > "${'$'}src"; target=${'$'}src.target
            [ ! -e "${'$'}target" ] && [ ! -L "${'$'}target" ] || return 1
            case "${'$'}kind" in regular) printf target-regular > "${'$'}target"; chmod 600 -- "${'$'}target";; symlink) ln -s -- missing-target "${'$'}target";; directory) mkdir -- "${'$'}target"; chmod 700 -- "${'$'}target";; esac
            mv -nT -- "${'$'}src" "${'$'}target" || return 1
            [ -f "${'$'}src" ] && [ ! -L "${'$'}src" ] && [ "${'$'}(cat -- "${'$'}src")" = source-${'$'}kind ] || return 1
            case "${'$'}kind" in regular) [ "${'$'}(cat -- "${'$'}target")" = target-regular ] || return 1;; symlink) [ -L "${'$'}target" ] && [ "${'$'}(readlink -- "${'$'}target")" = missing-target ] || return 1;; directory) [ -d "${'$'}target" ] && [ ! -L "${'$'}target" ] && [ -z "${'$'}(find "${'$'}target" -mindepth 1 -print -quit)" ] || return 1;; esac
        done
        i=0
        while [ "${'$'}i" -lt 8 ]; do
            left=${'$'}(mktemp "${'$'}probe_prefix.race-${'$'}i-left.XXXXXXXX") || return 1
            right=${'$'}(mktemp "${'$'}probe_prefix.race-${'$'}i-right.XXXXXXXX") || return 1
            chmod 600 -- "${'$'}left" "${'$'}right"; printf left-${'$'}i > "${'$'}left"; printf right-${'$'}i > "${'$'}right"; winner=${'$'}left.winner
            [ ! -e "${'$'}winner" ] && [ ! -L "${'$'}winner" ] || return 1
            (mv -nT -- "${'$'}left" "${'$'}winner") & p1=${'$'}!; (mv -nT -- "${'$'}right" "${'$'}winner") & p2=${'$'}!; wait "${'$'}p1" || return 1; wait "${'$'}p2" || return 1
            retained=0; [ -e "${'$'}left" ] && retained=${'$'}((retained + 1)); [ -e "${'$'}right" ] && retained=${'$'}((retained + 1))
            [ "${'$'}retained" -eq 1 ] && [ -f "${'$'}winner" ] && [ ! -L "${'$'}winner" ] || return 1
            winner_bytes=${'$'}(cat -- "${'$'}winner"); [ "${'$'}winner_bytes" = left-${'$'}i ] || [ "${'$'}winner_bytes" = right-${'$'}i ] || return 1
            i=${'$'}((i + 1))
        done
        probe_cleanup; probe_prefix=
        return 0
    }
    if [ "${'$'}create_only" = 1 ]; then
        if ! ln -T -- "${'$'}temp" "${'$'}actual_path" 2>/dev/null; then
            { [ -e "${'$'}actual_path" ] || [ -L "${'$'}actual_path" ]; } && path_error already_exists
            # Fail closed unless same-parent probes demonstrate GNU mv -nT no-clobber behavior
            # for absent/existing/symlink/directory and bounded concurrent targets.
            verify_noreplace_capability || { probe_cleanup; probe_prefix=; path_error noreplace_unsupported; }
            mv -nT -- "${'$'}temp" "${'$'}actual_path" || path_error publish_failed
            if [ -e "${'$'}temp" ] || [ -L "${'$'}temp" ]; then
                { [ -e "${'$'}actual_path" ] || [ -L "${'$'}actual_path" ]; } && path_error already_exists
                path_error publish_failed
            fi
        fi
        rm -f -- "${'$'}temp"; temp=
    else
        mv -Tf -- "${'$'}temp" "${'$'}actual_path" || path_error publish_failed; temp=
    fi
    # Publication can already be visible from here onward. Verify identity/type/bytes/mode/SHA.
    [ ! -L "${'$'}actual_path" ] && [ -f "${'$'}actual_path" ] || path_error publication_changed
    published_identity=${'$'}(stat -Lc '%d:%i' -- "${'$'}actual_path") || path_error publication_changed
    published_mode=${'$'}(stat -Lc '%a' -- "${'$'}actual_path") || path_error publication_changed
    published_total=${'$'}(wc -c < "${'$'}actual_path" | tr -d ' ') || path_error publication_changed
    published_sha=${'$'}(sha256sum < "${'$'}actual_path" | cut -d' ' -f1) || path_error publication_changed
    [ "${'$'}published_identity" = "${'$'}temp_identity" ] && [ "${'$'}published_mode" = "${'$'}mode" ] && [ "${'$'}published_total" = "${'$'}final_total" ] && [ "${'$'}published_sha" = "${'$'}final_sha" ] || path_error publication_changed
    sync "${'$'}actual_path" || path_error post_publish_sync_failed
    sync "${'$'}parent" || path_error post_publish_sync_failed
    printf '%s\nrequest_id=%s\noperation=write\nactual_path_b64=%s\npath_request_sha256=%s\ncontent_sha256=%s\nbytes_written=%s\ntotal_bytes=%s\nsha256=%s\n' "${'$'}marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}expected_path_sha" "${'$'}meta_sha" "${'$'}meta_total" "${'$'}final_total" "${'$'}final_sha"
""".trimIndent()

internal data class TermuxWriteRequest(
    val path: String,
    val pathBytes: ByteArray,
    val contentBytes: ByteArray,
    val expectedSha256: String?,
    val createOnly: Boolean,
)

private fun JsonPrimitive.writeStrictString(): String? = content.takeIf { isString }
private fun JsonPrimitive.writeStrictBoolean(): Boolean? =
    if (!isString) when (content) { "true" -> true; "false" -> false; else -> null } else null

internal fun parseTermuxWriteRequest(input: JsonElement): PublicInputResult<TermuxWriteRequest> {
    val obj = input as? JsonObject ?: return PublicInputResult.Error(PublicInputError("request_must_be_object"))
    val allowed = setOf("path", "content", "expected_sha256", "create_only")
    val unknown = obj.keys - allowed
    val path = (obj["path"] as? JsonPrimitive)?.writeStrictString()
    if (unknown.isNotEmpty()) return PublicInputResult.Error(PublicInputError("unknown_fields:${unknown.sorted().joinToString(",")}", path))
    if (path == null) return PublicInputResult.Error(PublicInputError("path_must_be_string"))
    val pathBytes = when (val encoded = encodeUtf8StrictBounded(path, MAX_TERMUX_PATH_BYTES)) {
        is BoundedUtf8Result.Ok -> encoded.bytes
        BoundedUtf8Result.TooLarge -> return PublicInputResult.Error(PublicInputError("path_too_large", path))
        BoundedUtf8Result.InvalidUtf8 -> return PublicInputResult.Error(PublicInputError("invalid_utf8", path))
    }
    when (val resolved = resolveTermuxPathLexically(path)) {
        is TermuxPathResolution.Error -> return PublicInputResult.Error(PublicInputError(resolved.code, path))
        is TermuxPathResolution.Ok -> Unit
    }
    val content = (obj["content"] as? JsonPrimitive)?.writeStrictString()
        ?: return PublicInputResult.Error(PublicInputError("content_must_be_string", path))
    val contentBytes = when (val encoded = encodeUtf8StrictBounded(content, MAX_TERMUX_TRANSFER_BYTES)) {
        is BoundedUtf8Result.Ok -> encoded.bytes
        BoundedUtf8Result.TooLarge -> return PublicInputResult.Error(PublicInputError("content_too_large", path))
        BoundedUtf8Result.InvalidUtf8 -> return PublicInputResult.Error(PublicInputError("content_invalid_utf8", path))
    }
    val expected = when (val raw = obj["expected_sha256"]) {
        null, JsonNull -> null
        is JsonPrimitive -> raw.writeStrictString()
            ?: return PublicInputResult.Error(PublicInputError("expected_sha256_must_be_string_or_null", path))
        else -> return PublicInputResult.Error(PublicInputError("expected_sha256_must_be_string_or_null", path))
    }
    if (expected != null && !isValidSha256(expected)) return PublicInputResult.Error(PublicInputError("invalid_expected_sha256", path))
    val createOnly = when (val raw = obj["create_only"]) {
        null -> false
        is JsonPrimitive -> raw.writeStrictBoolean()
            ?: return PublicInputResult.Error(PublicInputError("create_only_must_be_boolean", path))
        else -> return PublicInputResult.Error(PublicInputError("create_only_must_be_boolean", path))
    }
    if (createOnly && expected != null) return PublicInputResult.Error(PublicInputError("conflicting_guards", path))
    return PublicInputResult.Ok(TermuxWriteRequest(path, pathBytes, contentBytes, expected, createOnly))
}

private suspend fun invokeTransferHelper(context: Context, script: String, arguments: Array<String>, timeoutMs: Long): CaptureResult =
    runCommandCapture(
        ctx = context,
        executable = "/data/data/com.termux/files/usr/bin/bash",
        arguments = arrayOf("-c", script, "rikka-transfer", *arguments),
        workingDir = TERMUX_FILES_HOME,
        timeoutMs = timeoutMs,
        spoolOutput = false,
    )

private suspend fun cleanupTermuxTransfer(context: Context, requestId: String) {
    invokeTransferHelper(context, TERMUX_TRANSFER_CLEANUP_SCRIPT, arrayOf(requestId), TERMUX_TRANSFER_HELPER_TIMEOUT_MS)
}

internal suspend fun writeTermuxFile(context: Context, request: TermuxWriteRequest): TermuxTransferProtocolResult<TermuxWriteEnvelope> {
    val requestId = UUID.randomUUID().toString()
    val chunks = splitTermuxTransferBytes(request.contentBytes)
    val pathSha = sha256Hex(request.pathBytes)
    val contentSha = sha256Hex(request.contentBytes)
    try {
        for (chunk in chunks) {
            val capture = invokeTransferHelper(
                context, TERMUX_STAGE_CHUNK_SCRIPT,
                arrayOf(requestId, chunk.index.toString(), chunk.count.toString(), chunk.totalBytes.toString(), chunk.fullSha256, chunk.canonicalBase64),
                TERMUX_TRANSFER_HELPER_TIMEOUT_MS,
            )
            val ack = when (capture) {
                is CaptureResult.Success -> if (capture.exitCode == 0) parseTermuxTransferAck(capture.stdout, requestId, chunk.index)
                    else TermuxTransferProtocolResult.Error("termux_transfer_failed", capture.stderr.take(1_000))
                is CaptureResult.Timeout -> TermuxTransferProtocolResult.Error("transfer_timeout")
                is CaptureResult.Denied -> TermuxTransferProtocolResult.Error("termux_permission_denied")
                is CaptureResult.OtherError -> TermuxTransferProtocolResult.Error("termux_transfer_failed", capture.message)
            }
            if (ack is TermuxTransferProtocolResult.Error) return ack
        }
        val finalCapture = invokeTransferHelper(
            context, TERMUX_ATOMIC_WRITE_SCRIPT,
            arrayOf(requestId, encodeTermuxPath(request.path), request.expectedSha256 ?: "-", if (request.createOnly) "1" else "0", pathSha),
            TermuxRuntime.commandTimeoutMs,
        )
        return when (finalCapture) {
            is CaptureResult.Success -> if (finalCapture.exitCode == 0) parseTermuxWriteEnvelope(
                finalCapture.stdout, requestId, pathSha, contentSha, request.contentBytes.size,
            ) else TermuxTransferProtocolResult.Error("termux_write_failed", finalCapture.stderr.take(1_000))
            is CaptureResult.Timeout -> TermuxTransferProtocolResult.Error("write_timeout")
            is CaptureResult.Denied -> TermuxTransferProtocolResult.Error("termux_permission_denied")
            is CaptureResult.OtherError -> TermuxTransferProtocolResult.Error("termux_write_failed", finalCapture.message)
        }
    } finally {
        // Exactly one bounded best-effort cleanup for every exit, including cancellation and exceptions.
        withContext(NonCancellable) { runCatching { cleanupTermuxTransfer(context, requestId) } }
    }
}

private fun termuxWriteError(error: TermuxTransferProtocolResult.Error, requestedPath: String? = null) = buildJsonObject {
    put("success", false); put("ok", false); requestedPath?.let { put("path", it) }
    error.actualPath?.let { put("actual_path", it) }; error.currentSha256?.let { put("current_sha256", it) }
    put("error", error.code); error.detail?.let { put("detail", it) }
    put("recovery", when (error.code) {
        "stale_source" -> "Re-read the file and retry with current_sha256. RikkaHub writers serialize, but an uncooperative same-UID Termux writer can race the final check-to-rename boundary."
        "already_exists" -> "Choose another path or retry without create_only after reviewing the existing entry."
        "noreplace_unsupported" -> "No target was published: same-parent no-clobber capability probes failed, so create_only stopped fail-closed."
        "symlink_rejected" -> "Use a normal-file destination; final-component symlinks are deliberately rejected."
        "source_changed" -> "Another writer changed the target. Re-read it and retry after that writer is idle."
        "publication_changed", "post_publish_sync_failed" -> "Publication may already have happened. Re-read actual_path and verify its SHA before deciding whether to retry."
        "content_too_large", "transfer_too_large" -> "Split the operation; one atomic write is limited to 4 MiB of UTF-8 content."
        "invalid_utf8", "content_invalid_utf8" -> "Remove lone UTF-16 surrogate code units; path and content require strict UTF-8."
        else -> "Check the request, target path, and Termux RUN_COMMAND integration, then retry."
    })
}

private fun termuxWriteJson(value: TermuxWriteEnvelope) = buildJsonObject {
    put("success", true); put("ok", true); put("operation", value.operation); put("actual_path", value.actualPath)
    put("path_request_sha256", value.pathRequestSha256); put("content_sha256", value.contentSha256)
    put("bytes_written", value.bytesWritten); put("total_bytes", value.totalBytes); put("sha256", value.sha256); put("error", JsonNull)
}

private fun writeSchema() = buildJsonObject {
    put("path", buildJsonObject { put("type", "string"); put("description", "Strict UTF-8. Relative/~ paths resolve from Termux HOME; /tmp maps to TMPDIR.") })
    put("content", buildJsonObject { put("type", "string"); put("description", "Up to 4 MiB of strict UTF-8, preserved exactly including NUL, CRLF, and final-newline state.") })
    put("expected_sha256", buildJsonObject {
        put("type", buildJsonArray { add("string"); add("null") })
        put("description", "Optional lowercase SHA-256 stale guard; a missing target is stale.")
    })
    put("create_only", buildJsonObject { put("type", "boolean"); put("description", "Race-safe no-clobber creation among filesystem publishers; mutually exclusive with expected_sha256.") })
}

fun termuxWriteFileTool(context: Context): Tool = Tool(
    name = "termux_write_file",
    description = "Atomically create or replace one normal Termux file from up to 4 MiB of exact strict UTF-8. Binder-safe staged chunks are byte/SHA verified; the same-directory temp is fully synced and published with no-container rename or no-clobber hard link (falling back only after same-parent no-clobber rename capability probes where Android denies hard links), then the file and final parent are fully synced. Existing mode bits are preserved; final-component symlinks are rejected. expected_sha256 serializes RikkaHub writers and fails closed on detected external changes; an uncooperative same-UID Termux writer can race the final check-to-rename boundary. Parent symlinks follow normal resolution; actual_path is authoritative. Post-publication sync failure is reported and publication may already be visible; recursively created ancestor directories are not individually synced.",
    parameters = { InputSchema.Obj(writeSchema(), listOf("path", "content")) },
    execute = { input ->
        when (val parsed = parseTermuxWriteRequest(input)) {
            is PublicInputResult.Error -> listOf(UIMessagePart.Text(termuxWriteError(TermuxTransferProtocolResult.Error(parsed.value.code), parsed.value.path).toString()))
            is PublicInputResult.Ok -> listOf(UIMessagePart.Text(when (val result = writeTermuxFile(context, parsed.value)) {
                is TermuxTransferProtocolResult.Ok -> termuxWriteJson(result.value)
                is TermuxTransferProtocolResult.Error -> termuxWriteError(result, parsed.value.path)
            }.toString()))
        }
    },
)
