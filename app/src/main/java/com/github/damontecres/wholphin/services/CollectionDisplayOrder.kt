package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import java.util.Locale

/** Owner's exact Collections sequence; server SortName and tags cannot change it. */
object CollectionDisplayOrder {
    private val orderedNames =
        listOf(
            "A24 Mood",
            "AFIs 100 Greatest",
            "Amblin Magic",
            "Anime Feature Night",
            "Blockbuster Friday",
            "Box Office Champs",
            "Capes And Cowls",
            "Clear Your Evening",
            "Cozy Season",
            "Critics Circle",
            "Crowd Pleasers",
            "Date Night",
            "Edge Of Your Seat",
            "Family Movie Night",
            "Front Lines",
            "Game Day",
            "Gold Statue",
            "Groovy 70s",
            "Grown Up Toons",
            "Hot Right Now",
            "IMDb Most Popular",
            "IMDb Top 250",
            "Ink And Pixels",
            "Just Dropped",
            "Lights Off",
            "Mic Drop",
            "One And Done",
            "Originals Only",
            "Out Of This World",
            "Passport Night",
            "Quick Fix",
            "Ripped From The Headlines",
            "Saddle Up",
            "Silver Screen",
            "Spin The Wheel",
            "Spooky Season",
            "Sword And Sorcery",
            "The Auteur Shelf",
            "The Big Score",
            "The Complete Set",
            "The Sandlerverse",
            "The Usual Suspects",
            "TMDB Top Rated",
            "Turn It Up",
            "Vhs Vault",
            "Whodunit",
            "Y2K Rewind",
            "Boxing",
            "UFC Fight Night",
            "UFC on ABC",
            "UFC on ESPN",
            "UFC PPV",
            "Netflix",
            "Disney+",
            "Hulu",
            "HBO Max",
            "Prime Video",
            "Paramount+",
            "Peacock",
            "Apple TV",
            "AMC+",
            "MGM+",
            "Starz",
            "Britbox",
            "Crunchyroll",
            "Hallmark+",
            "Angel",
        )

    private fun key(name: String?): String {
        val value =
            name
                .orEmpty()
                .lowercase(Locale.ROOT)
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]"), "")
        return when (value) {
            "max", "maxhbo", "hbo" -> "hbomax"
            "angelstudios" -> "angel"
            "animefeaturenights" -> "animefeaturenight"
            "criticcircle" -> "criticscircle"
            "whodunnit" -> "whodunit"
            else -> value
        }
    }

    private val ranks = orderedNames.mapIndexed { index, name -> key(name) to index }.toMap()

    private fun rank(item: BaseItem) = ranks[key(item.name)] ?: Int.MAX_VALUE

    private fun group(rank: Int) =
        when {
            rank < 47 -> 0
            rank < 52 -> 1
            rank < orderedNames.size -> 2
            else -> 3
        }

    fun sort(
        items: List<BaseItem>,
        descending: Boolean = false,
    ): List<BaseItem> =
        items.sortedWith(
            Comparator { a, b ->
                val aRank = rank(a)
                val bRank = rank(b)
                val groupOrder = group(aRank).compareTo(group(bRank))
                if (groupOrder != 0) return@Comparator groupOrder
                val direction = if (descending && group(aRank) != 2) -1 else 1
                val rankOrder = aRank.compareTo(bRank) * direction
                if (rankOrder != 0) return@Comparator rankOrder
                val nameOrder = a.name.orEmpty().compareTo(b.name.orEmpty(), ignoreCase = true) * direction
                if (nameOrder != 0) nameOrder else a.id.toString().compareTo(b.id.toString())
            },
        )
}
