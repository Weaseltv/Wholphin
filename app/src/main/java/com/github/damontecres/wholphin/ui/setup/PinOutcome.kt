package com.github.damontecres.wholphin.ui.setup

import com.github.damontecres.wholphin.kids.PinCheckResult

/** What a submitted PIN turned out to be; [WRONG] entries count towards the forgot-PIN hint (contract 11.2) */
enum class PinOutcome { WRONG, OTHER }

/** A gate check's [PinOutcome]: a lockout came from a wrong entry too */
fun PinCheckResult.outcome(): PinOutcome = if (this == PinCheckResult.Correct) PinOutcome.OTHER else PinOutcome.WRONG
