package com.github.damontecres.wholphin.ui.kids

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.KidLevel

/** The level card's title */
val KidLevel.label: Int
    get() =
        when (this) {
            KidLevel.KIDS -> R.string.kid_level_kids
            KidLevel.OLDER_KIDS -> R.string.kid_level_older_kids
            KidLevel.TEENS -> R.string.kid_level_teens
        }

/** The level card's description */
val KidLevel.description: Int
    get() =
        when (this) {
            KidLevel.KIDS -> R.string.kid_level_kids_description
            KidLevel.OLDER_KIDS -> R.string.kid_level_older_kids_description
            KidLevel.TEENS -> R.string.kid_level_teens_description
        }
