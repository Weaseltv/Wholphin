package com.github.damontecres.wholphin.services

/**
 * The app's back stacks (no-Compose UI, 2026-11-22): a thread-safe [MutableList] that hands its contents to
 * [onChange] after every change, replacing Navigation3's snapshot-state `NavBackStack`.
 *
 * Callers keep using it as a plain `MutableList` (`add`, `removeLastOrNull`, `[0] = ...`, `clear`, `addAll`); the
 * shell observes the snapshots. `clear` and `addAll` are one change each, so a caller's `clear()` + `addAll(...)`
 * reports at most one empty list in between, which the shell ignores.
 */
class ObservableBackStack<T : Any>(
    initial: Collection<T>,
    private val onChange: (List<T>) -> Unit,
) : AbstractMutableList<T>(),
    RandomAccess {
    private val lock = Any()
    private val items = ArrayList<T>(initial)

    override val size: Int
        get() = synchronized(lock) { items.size }

    override fun get(index: Int): T = synchronized(lock) { items[index] }

    override fun set(
        index: Int,
        element: T,
    ): T = mutate { items.set(index, element) }

    override fun add(
        index: Int,
        element: T,
    ) = mutate { items.add(index, element) }

    override fun removeAt(index: Int): T = mutate { items.removeAt(index) }

    override fun clear() = mutate { items.clear() }

    override fun addAll(elements: Collection<T>): Boolean = mutate { items.addAll(elements) }

    override fun addAll(
        index: Int,
        elements: Collection<T>,
    ): Boolean = mutate { items.addAll(index, elements) }

    /** A copy of the current contents */
    fun snapshot(): List<T> = synchronized(lock) { items.toList() }

    // The listener runs inside the lock, so snapshots from different threads arrive in the order of the changes
    private inline fun <R> mutate(block: () -> R): R =
        synchronized(lock) {
            val result = block()
            onChange(items.toList())
            result
        }
}
