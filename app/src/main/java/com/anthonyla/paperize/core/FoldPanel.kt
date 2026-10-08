package com.anthonyla.paperize.core

/** PaperizeFold: the two physical screens of a book-style foldable. */
enum class FoldPanel {
    /** The large inner screen. Regular phones only have this one. */
    MAIN,

    /** The outer cover screen, in use while the phone is folded. */
    COVER;

    val other: FoldPanel get() = if (this == MAIN) COVER else MAIN
}

/** The static wallpaper slots a write covers: HOME, LOCK, or both for BOTH. */
fun ScreenType.staticSlots(): Set<ScreenType> = when (this) {
    ScreenType.HOME -> setOf(ScreenType.HOME)
    ScreenType.LOCK -> setOf(ScreenType.LOCK)
    ScreenType.BOTH -> setOf(ScreenType.HOME, ScreenType.LOCK)
    ScreenType.LIVE -> emptySet()
}
