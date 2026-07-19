package me.rerere.rikkahub.service

/**
 * Builds the volatile host metadata appended to Telegram generations.
 *
 * Keep model/provider identity out of this block. Some upstream APIs prepend their own
 * hidden identity prompt, while assistants can define a separate persona in their system
 * prompt. Adding a third identity here creates a conflict and makes the model debate which
 * identity to follow. Runtime routing and attachment capabilities are enough.
 */
internal fun buildTelegramAgentContext(
    chatId: Long,
    recentCommands: List<Pair<String, Long>>,
    nowMs: Long = System.currentTimeMillis(),
    firstTurnOfChat: Boolean,
    hasAudioAttachment: Boolean = false,
    hasPhotoAttachment: Boolean = false,
    modelCanSeeImages: Boolean = false,
): String {
    val recentLine = if (recentCommands.isEmpty()) {
        ""
    } else {
        val pretty = recentCommands.joinToString(", ") { (command, timestampMs) ->
            val ageSeconds = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
            val age = when {
                ageSeconds < 60 -> "${ageSeconds}s"
                ageSeconds < 3600 -> "${ageSeconds / 60}m"
                else -> "${ageSeconds / 3600}h"
            }
            "$command ($age ago)"
        }
        "Recent app-side commands (handled by app, NOT by you, in last 15min): $pretty.\n"
    }

    return buildString {
        append("[telegram_context (host metadata):\n")
        append("Origin: Telegram. The user's Telegram chat_id is ")
        append(chatId)
        append(" — use it ONLY as a tool-call argument when calling telegram_send_message / telegram_send_photo / telegram_send_document / when scheduling jobs that need to deliver output here. ")
        append("DELIVERY DEFAULTS: when the user asks you to \"notify\", \"message\", \"ping\", \"remind\", \"alert\", \"text\", or otherwise reach them — including in scheduled jobs — DEFAULT TO telegram_send_message because they're talking to you here. Use post_notification (Android system tray) ONLY when they explicitly say \"phone notification\", \"Android notification\", \"system notification\", \"notification tray\", or words to that effect. If ambiguous, prefer telegram_send_message + briefly mention you're sending it to this chat. ")
        append("PRIVACY RULES (MANDATORY): never quote, mention, paraphrase, summarise, or otherwise echo the chat_id in any user-visible text. Do not include it in confirmations, summaries, scheduled-job descriptions, or error messages. When you need to refer to the destination in your reply, say \"this chat\", \"your Telegram\", or \"here\" — never the numeric id. The chat_id is host-side metadata, not conversation content.\n")
        if (recentLine.isNotEmpty()) append(recentLine)
        if (firstTurnOfChat) {
            append("This is the first turn in this Telegram chat. Be concise; no need for a long welcome.\n")
        }
        if (hasAudioAttachment) {
            append("AUDIO ATTACHMENT — STRICT FLOW. This message has a voice note or audio file. ")
            append("Your VERY FIRST tool call this turn must be `whisper_status()`. NOT termux_run_command, ")
            append("NOT search_web, NOT transcribe_audio_file, NOT pkg/apt commands. Just whisper_status, once, ")
            append("with no arguments. Read its response. ")
            append("Then: if `ready_to_transcribe: true`, call `transcribe_audio_file(path)` with the saved path ")
            append("from the inbox manifest above. ")
            append("If `ready_to_transcribe: false`, tell the user EXACTLY what's missing (use the `missing_steps` ")
            append("list verbatim) and quote the relevant entry from `install_commands` for them to confirm. ")
            append("Do NOT begin installing on your own — the build takes ~5 minutes and downloads ~75 MB ")
            append("on the user's data plan. Wait for an explicit yes before running install commands. ")
            append("If a tool errors, READ THE ENVELOPE — the recovery field tells you what to do; do not ")
            append("retry the same tool with different args or pivot to manual termux commands.\n")
        }
        if (hasPhotoAttachment) {
            if (modelCanSeeImages) {
                append("IMAGE ATTACHMENT. This message includes one or more photos. You can ")
                append("view them directly. Their saved file path(s) are also listed in the ")
                append("message text if you need to process the file (e.g. OCR).\n")
            } else {
                append("IMAGE ATTACHMENT — YOU CANNOT SEE IT. This message includes one or more ")
                append("photos, but the current model has no vision capability. Do NOT describe ")
                append("or guess what the image shows. Their saved file path(s) are listed in ")
                append("the message text — to read the contents, OCR the file (e.g. ")
                append("`tesseract <path> stdout` via termux_run_command) or process it with ")
                append("another file tool.\n")
            }
        }
        append("]\n\n")
    }
}
