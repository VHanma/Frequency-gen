package com.vhanma.lightcode.photophone

import java.util.Collections
import java.util.Random

/** Allows deterministic shuffling when list concatenation inferred the read-only List type. */
internal fun <T> List<T>.shuffle(random: Random) {
    @Suppress("UNCHECKED_CAST")
    val mutable = this as? MutableList<T>
        ?: error("The trial condition list is not mutable.")
    Collections.shuffle(mutable, random)
}
