package dev.healthbridge

import java.io.File

internal fun makeSyntheticLegacyGeneration(root: File, id: String) {
    val dir = File(root, id)
    check(File(dir, ATTEMPT_INTENT_NAME).delete())
    val reservation = File(dir, "reservation")
    val fields = reservation.readLines().toMutableList()
    fields[5] = (fields[5].toLong() - 2 * attemptIntentBudget(fields[4].toLong())).toString()
    reservation.writeText(fields.joinToString("\n"))
}
