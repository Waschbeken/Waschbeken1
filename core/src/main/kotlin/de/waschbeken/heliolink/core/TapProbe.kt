package de.waschbeken.heliolink.core

/**
 * Finds out whether the strap reports being tapped. The strap has no button and no known "tapped"
 * message, but it notices a tap while "find strap" is running – maybe it says more than we know.
 * The test records everything the strap sends: first while it is left alone, then while it is
 * tapped. What only comes while tapping is the strap's reaction.
 */
object TapProbe {
    /** A message from the strap: [source] is "Endpunkt 001a" or "Kanal 0010", [hex] its content. */
    data class Message(val atMs: Long, val source: String, val hex: String) {
        /** Where it came from and its first byte (the command); the rest often changes from message to message. */
        val key: String get() = source + if (hex.length >= 2) " " + hex.take(2) else ""
    }

    data class Phase(val name: String, val fromMs: Long, val toMs: Long, val tapping: Boolean)

    data class Result(
        /** Kinds of messages ([Message.key]) that came while tapping, but never while the strap was left alone. */
        val reactions: List<String>,
        val byPhase: Map<Phase, List<Message>>,
    )

    /** The test starts with a battery request; its answer shows the recording works. */
    const val CONTROL_SOURCE = "Endpunkt 0029"

    fun controlArrived(messages: List<Message>): Boolean = messages.any { it.source == CONTROL_SOURCE }

    fun analyse(messages: List<Message>, phases: List<Phase>): Result {
        val byPhase = phases.associateWith { p -> messages.filter { it.atMs >= p.fromMs && it.atMs < p.toMs } }
        val quiet = byPhase.filterKeys { !it.tapping }.values.flatten().map { it.key }.toSet()
        val reactions = byPhase.filterKeys { it.tapping }.values.flatten().map { it.key }.distinct().filter { it !in quiet }
        return Result(reactions, byPhase)
    }

    /** Plain text for sending: every phase with its messages, seconds from the start. */
    fun report(result: Result, phases: List<Phase>, header: String): String = buildString {
        appendLine(header)
        val start = phases.firstOrNull()?.fromMs ?: 0L
        appendLine(if (result.reactions.isEmpty()) "Keine Reaktion auf das Tippen." else "Reaktion: " + result.reactions.joinToString(", "))
        for (p in phases) {
            val list = result.byPhase[p].orEmpty()
            appendLine()
            appendLine("${p.name} (${list.size})")
            for (m in list) appendLine("  +%.1f s  %s  %s".format(java.util.Locale.US, (m.atMs - start) / 1000.0, m.source, m.hex))
        }
    }
}
