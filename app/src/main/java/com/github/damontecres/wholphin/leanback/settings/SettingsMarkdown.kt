package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole

/**
 * A small Markdown renderer for release notes (no-Compose UI, 2026-11-22), replacing the Compose `Markdown` the
 * old `ReleaseNotes` used with the same type scale: `#` headlineLarge, `##` headlineMedium, `###` headlineSmall,
 * text and code bodySmall. Supports headings, bullet lists, bold, italic, inline code and links (shown as text).
 * Show the result in a TextView styled with [styleNotes] (the body size and colour; headings carry their own size).
 */
internal object SettingsMarkdown {
    private val bold = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val italic = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?!_)")
    private val code = Regex("`([^`]+)`")
    private val link = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /** The body type of release notes: bodySmall in the theme's text colour */
    fun styleNotes(view: android.widget.TextView) {
        view.applyTextRole(TextRole.BODY_SMALL)
        view.setTextColor(AppTheme.palette.onSurface)
    }

    fun render(
        context: Context,
        markdown: String,
    ): CharSequence {
        val weasel = AppTheme.useWeaselFonts

        fun sp(role: TextRole) = (if (weasel) role.neonSp else role.stockSp).toInt()
        val out = SpannableStringBuilder()
        val textColor = AppTheme.palette.onSurface
        val bulletGap = (8 * context.resources.displayMetrics.density).toInt()
        val lines = markdown.replace(comment, "").replace("\r\n", "\n").split("\n")
        var lastBlank = true
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (!lastBlank) out.append("\n")
                lastBlank = true
                continue
            }
            lastBlank = false
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            val bullet = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            val start = out.length
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    appendInline(out, heading.groupValues[2])
                    val role =
                        when (level) {
                            1 -> TextRole.HEADLINE_LARGE
                            2 -> TextRole.HEADLINE_MEDIUM
                            else -> TextRole.HEADLINE_SMALL
                        }
                    out.setSpan(AbsoluteSizeSpan(sp(role), true), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                bullet != null -> {
                    appendInline(out, bullet.groupValues[1])
                    out.setSpan(BulletSpan(bulletGap, textColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                else -> {
                    appendInline(out, line.trim())
                }
            }
            out.append("\n")
        }
        while (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    /** Appends [text] with its inline bold, italic, code and links */
    private fun appendInline(
        out: SpannableStringBuilder,
        text: String,
    ) {
        var plain = link.replace(text) { it.groupValues[1] }

        data class Mark(
            val start: Int,
            val end: Int,
            val span: Any,
        )
        val marks = mutableListOf<Mark>()

        fun strip(
            regex: Regex,
            span: () -> Any,
        ) {
            val sb = StringBuilder()
            var last = 0
            regex.findAll(plain).forEach { m ->
                sb.append(plain, last, m.range.first)
                val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
                val s = sb.length
                sb.append(inner)
                marks.add(Mark(s, sb.length, span()))
                last = m.range.last + 1
            }
            sb.append(plain.substring(last))
            plain = sb.toString()
        }
        strip(code) { TypefaceSpan("monospace") }
        strip(bold) { StyleSpan(Typeface.BOLD) }
        strip(italic) { StyleSpan(Typeface.ITALIC) }
        val base = out.length
        out.append(plain)
        // Positions shift as later passes strip markers; spans from earlier passes are approximate after that,
        // which only affects text that mixes several kinds of emphasis
        marks.forEach { mark ->
            val s = (base + mark.start).coerceAtMost(out.length)
            val e = (base + mark.end).coerceAtMost(out.length)
            if (e > s) out.setSpan(mark.span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
