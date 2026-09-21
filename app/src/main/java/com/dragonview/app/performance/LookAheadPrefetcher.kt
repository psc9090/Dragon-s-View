// app/src/main/java/com/dragonview/app/performance/LookAheadPrefetcher.kt
package com.dragonview.app.performance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Technique 4: LOOK-AHEAD PREFETCH (direction-aware, not blind).
 *
 * Tracks user navigation direction and triggers prefetching/pre-parsing for
 * strictly the immediate next 1 item (+1 or -1) on Dispatchers.Default.
 * Automatically respects Technique 9 by skipping prefetch when JankMonitor indicates frame drops.
 */
class LookAheadPrefetcher(
    private val scope: CoroutineScope,
    private val prefetchAction: suspend (targetIndex: Int) -> Unit
) {
    private var lastIndex: Int = -1
    private var activePrefetchJob: Job? = null

    fun onPositionChanged(currentIndex: Int, itemCount: Int) {
        if (lastIndex == -1) {
            lastIndex = currentIndex
            return
        }

        // Determine navigation direction: +1 (forward) or -1 (backward)
        val direction = when {
            currentIndex > lastIndex -> 1
            currentIndex < lastIndex -> -1
            else -> 0
        }
        lastIndex = currentIndex

        if (direction == 0) return

        // Skip prefetch under jank to conserve precious CPU cycles for foreground rendering
        if (JankMonitor.isJankActive.value) {
            cancelPending()
            return
        }

        val targetIndex = currentIndex + direction
        if (targetIndex in 0 until itemCount) {
            cancelPending()
            activePrefetchJob = scope.launch(Dispatchers.Default) {
                // Re-check jank status inside coroutine
                if (!JankMonitor.isJankActive.value) {
                    prefetchAction(targetIndex)
                }
            }
        }
    }

    fun cancelPending() {
        activePrefetchJob?.cancel()
        activePrefetchJob = null
    }
}
