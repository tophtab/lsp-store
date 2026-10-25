package io.github.tophtab.lspstore.ui

/**
 * One-shot gate for "a finished refresh returns the list to the top".
 *
 * The refresh counter lives in the view model, but the scroll itself is a
 * composition effect. Leaving the list for a module page and coming back builds
 * a *new* composition, which re-launches that effect with the counter it had
 * already acted on, so a plain `tick > 0` check threw the list back to the top
 * every time a module was closed.
 *
 * The gate remembers the counter value it last acted on and lives in the view
 * model, next to the counter itself, so it outlives the composition that
 * consumed it: only a refresh that finishes while the screen is around counts.
 */
class RefreshScrollGate {

    private var handledTick = 0

    /**
     * True exactly once per new [tick]. The initial 0 is never acted on, so the
     * first refresh still has to bump the counter to be seen.
     */
    fun consume(tick: Int): Boolean {
        if (tick == handledTick) return false
        handledTick = tick
        return true
    }
}
