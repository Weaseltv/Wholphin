package com.github.damontecres.wholphin.util

import org.jellyfin.sdk.model.api.BaseItemKind
import java.text.Normalizer
import java.util.UUID

/**
 * Fuzzy title search (owner, 2026-10-16). Jellyfin's search is a substring match on names with
 * punctuation turned into spaces, so "MASH" misses M*A*S*H ("m a s h") and "mob land" misses
 * MobLand. Search also matches the query against an index of the member's titles, compared as
 * [searchKey]s, and shows those matches first. The phone app follows the same rules, so the two
 * apps find the same titles in the same order: change them together.
 */
object FuzzySearch {
    /** The types the title index holds, among those Search shows. */
    val TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET)

    /** The most exact, prefix and substring matches per type. */
    const val MAX_MATCHES = 50

    /** The most typo matches per type. */
    const val MAX_TYPOS = 10

    /** Shorter queries skip the index: one character matches nearly everything. */
    private const val MIN_QUERY = 2

    /** Shorter queries get no typo matches: "mash" would find Christmas and Flash. */
    private const val MIN_TYPO_QUERY = 5

    private val marks = Regex("\\p{M}+")
    private val word = Regex("[\\p{L}\\p{N}]+")

    /** A title in the index, with its [key] and where each of its words starts in the key. */
    data class Entry(
        val id: UUID,
        val name: String,
        val type: BaseItemKind,
        val year: Int?,
    ) {
        val key: String
        val wordStarts: IntArray

        init {
            val words = words(name)
            key = words.joinToString("")
            wordStarts = words.runningFold(0) { start, part -> start + part.length }.dropLast(1).toIntArray()
        }
    }

    /** The index's matches for a query: exact, prefix and substring [matches], and [typos], each best first. */
    data class Ranked<T>(
        val matches: List<T>,
        val typos: List<T>,
    ) {
        val all: List<T> get() = matches + typos

        fun <R : Any> mapNotNull(transform: (T) -> R?) = Ranked(matches.mapNotNull(transform), typos.mapNotNull(transform))
    }

    /**
     * [text] reduced to its letters and digits: "M*A*S*H", "M.A.S.H." and "mash" are all "mash",
     * "Mob Land" is "mobland", "Amélie" is "amelie" and "Law & Order" is "lawandorder". Unicode
     * aware, so titles in other scripts still match.
     */
    fun searchKey(text: String): String = words(text).joinToString("")

    /** [text]'s words: runs of letters and digits, without accents or case, and with "&" as "and". */
    private fun words(text: String): List<String> =
        word
            .findAll(
                Normalizer
                    .normalize(text, Normalizer.Form.NFKD)
                    .replace(marks, "")
                    .lowercase()
                    .replace("&", "and"),
            ).map { it.value }
            .toList()

    /**
     * The [entries] that match [query]. A title whose key equals the query's ranks first, then one
     * that starts with it, then one that contains it; ties go to the shorter key, then the name. At
     * most [MAX_MATCHES] of each type. For queries of 5+ characters, titles with a word that starts
     * with the query give or take a typo or two are [Ranked.typos]: fewest edits first, then as above,
     * at most [MAX_TYPOS] of each type. Remaining ties keep [entries]' order.
     */
    fun rank(
        entries: List<Entry>,
        query: String,
    ): Ranked<Entry> {
        val q = searchKey(query)
        if (q.length < MIN_QUERY) return Ranked(emptyList(), emptyList())
        val maxEdits =
            when {
                q.length < MIN_TYPO_QUERY -> null
                q.length <= 8 -> 1
                else -> 2
            }
        val matches = mutableListOf<Match>()
        val typos = mutableListOf<Match>()
        entries.forEach { entry ->
            val key = entry.key
            when {
                key == q -> matches += Match(entry, 0)
                key.startsWith(q) -> matches += Match(entry, 1)
                key.contains(q) -> matches += Match(entry, 2)
                maxEdits != null -> typoDistance(q, key, entry.wordStarts, maxEdits)?.let { typos += Match(entry, it) }
            }
        }
        return Ranked(
            matches = best(matches, MAX_MATCHES),
            typos = best(typos, MAX_TYPOS),
        )
    }

    /**
     * A section's results: the [index]'s matches first, in their ranked order, then the [server]'s own
     * results (it can match on fields the index doesn't hold), then the index's typo matches, so a
     * guess never buries a real match. Each item once.
     */
    fun <T> merge(
        index: Ranked<T>,
        server: List<T>,
        id: (T) -> Any?,
    ): List<T> = (index.matches + server + index.typos).distinctBy(id)

    /** A match and its tier (exact, prefix, substring) or number of edits (typos). */
    private class Match(
        val entry: Entry,
        val rank: Int,
    )

    private fun best(
        matches: List<Match>,
        perType: Int,
    ): List<Entry> {
        val counts = mutableMapOf<BaseItemKind, Int>()
        return matches
            .sortedWith(compareBy<Match>({ it.rank }, { it.entry.key.length }, { it.entry.name }))
            .filter {
                val count = (counts[it.entry.type] ?: 0) + 1
                counts[it.entry.type] = count
                count <= perType
            }.map { it.entry }
    }

    /**
     * The fewest edits that turn [q] into the start of [key] from one of its [wordStarts], or null
     * when that's more than [maxEdits].
     */
    internal fun typoDistance(
        q: String,
        key: String,
        wordStarts: IntArray,
        maxEdits: Int,
    ): Int? =
        wordStarts
            .filter { key.length - it >= q.length - maxEdits }
            .mapNotNull { start -> prefixDistance(q, key, start, maxEdits) }
            .minOrNull()

    /**
     * The fewest edits that turn [q] into some prefix of [key] from [start] (edit distance with a free
     * end), or null when that's more than [maxEdits].
     */
    internal fun prefixDistance(
        q: String,
        key: String,
        start: Int,
        maxEdits: Int,
    ): Int? {
        // A prefix more than maxEdits longer than q is never close enough
        val length = minOf(key.length - start, q.length + maxEdits)
        var previous = IntArray(length + 1) { it }
        var current = IntArray(length + 1)
        for (i in 1..q.length) {
            current[0] = i
            var rowMin = i
            for (j in 1..length) {
                val substitute = previous[j - 1] + if (q[i - 1] == key[start + j - 1]) 0 else 1
                current[j] = minOf(substitute, previous[j] + 1, current[j - 1] + 1)
                if (current[j] < rowMin) rowMin = current[j]
            }
            // A row's best never improves further down, so give up early
            if (rowMin > maxEdits) return null
            val swap = previous
            previous = current
            current = swap
        }
        return previous.min().takeIf { it <= maxEdits }
    }
}
