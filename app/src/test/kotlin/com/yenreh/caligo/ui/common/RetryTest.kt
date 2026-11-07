@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.common

import com.yenreh.caligo.data.datasources.NetworkApiException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Riverpod 3.0.3's automatic retry of a failing provider, on the test's clock */
class RetryTest {
    private val failure = NetworkApiException("Connection error")

    /** A load answering from [answers] in turn, the last one for good; counts its calls */
    private class Script(private val answers: List<Result<String>>) {
        val at = mutableListOf<Long>()

        fun next(now: Long): String {
            at += now
            return answers[minOf(at.size, answers.size) - 1].getOrThrow()
        }
    }

    private fun test(body: suspend TestScope.(scope: CoroutineScope) -> Unit) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun waitsAsRiverpodDefaultRetry() {
        val rule = ProviderRetry.default
        val waits = (0..9).map { rule(it, failure)!!.inWholeMilliseconds }
        assertEquals(listOf(200L, 400, 800, 1600, 3200, 6400, 6400, 6400, 6400, 6400), waits)
        assertNull(rule(10, failure))
        // Bugs, Dart's Errors, are not retried
        assertNull(rule(0, IllegalStateException()))
    }

    @Test
    fun retriesTenTimesThenShowsTheFailure() = test { scope ->
        val script = Script(listOf(Result.failure(failure)))
        val load = RetryingLoad(scope) { script.next(testScheduler.currentTime) }

        load.watch()
        runCurrent()
        // Loading while it is tried again, as `when` shows a provider retrying
        assertEquals(Loaded.Loading, load.state.value)

        advanceTimeBy(60_000)
        runCurrent()

        // The first try at once, then after 200, 400 ... 6400 ms
        assertEquals(listOf(0L, 200, 600, 1400, 3000, 6200, 12_600, 19_000, 25_400, 31_800, 38_200), script.at)
        assertEquals(Loaded.Failed(failure), load.state.value)
    }

    @Test
    fun aRetryThatAnswersShowsTheValue() = test { scope ->
        val script = Script(listOf(Result.failure(failure), Result.failure(failure), Result.success("stations")))
        val load = RetryingLoad(scope) { script.next(testScheduler.currentTime) }

        load.watch()
        advanceTimeBy(599)
        runCurrent()
        assertEquals(Loaded.Loading, load.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(Loaded.Ready("stations"), load.state.value)
        assertEquals(listOf(0L, 200, 600), script.at)
    }

    @Test
    fun aBugIsShownAtOnce() = test { scope ->
        val bug = IllegalStateException("bug")
        val script = Script(listOf(Result.failure(bug)))
        val load = RetryingLoad(scope) { script.next(testScheduler.currentTime) }

        load.watch()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, script.at.size)
        assertEquals(Loaded.Failed(bug), load.state.value)
    }

    @Test
    fun reloadDropsThePendingRetryAndSpendsNone() = test { scope ->
        val script = Script(listOf(Result.failure(failure)))
        val load = RetryingLoad(scope) { script.next(testScheduler.currentTime) }

        load.watch()
        advanceTimeBy(100)
        runCurrent()
        // Retry by hand before the first automatic one: that one never comes
        load.reload()
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(0L, 100), script.at)

        // The count was not spent: the next wait is still the first one
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0L, 100, 300), script.at)
    }

    @Test
    fun retriesAreCountedOverTheLifeNotReset() = test { scope ->
        val script = Script(listOf(Result.failure(failure)))
        val load = RetryingLoad(scope) { script.next(testScheduler.currentTime) }

        load.watch()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(11, script.at.size)

        // Retry by hand once they are spent: one try, its failure shown
        load.reload()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(12, script.at.size)
        assertEquals(Loaded.Failed(failure), load.state.value)
    }

    @Test
    fun reloadKeepsTheFailureOnShowUntilTheNewAnswer() = test { scope ->
        var answer: Result<String> = Result.failure(failure)
        var calls = 0
        val load = RetryingLoad(scope, ProviderRetry.none) {
            calls++
            answer.getOrThrow()
        }

        load.watch()
        runCurrent()
        assertEquals(Loaded.Failed(failure), load.state.value)

        answer = Result.success("lines")
        load.reload()
        assertEquals(Loaded.Failed(failure), load.state.value)
        runCurrent()
        assertEquals(Loaded.Ready("lines"), load.state.value)
        assertEquals(2, calls)
    }

    @Test
    fun cancelStopsRetrying() = test { scope ->
        val script = Script(listOf(Result.failure(failure)))
        val load = RetryingLoad(scope) { script.next(testScheduler.currentTime) }

        load.watch()
        runCurrent()
        load.cancel()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, script.at.size)
    }

    @Test
    fun anotherFailureReplacesThePendingRetry() = test { scope ->
        val retry = AutoRetry(scope)
        var runs = 0

        retry.afterFailure(failure) { runs++ }
        retry.afterFailure(failure) { runs += 10 }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(10, runs)
    }

    @Test
    fun aZeroWaitStillRuns() = test { scope ->
        val retry = AutoRetry(scope, rule = { retries, _ -> if (retries == 0) 0.milliseconds else null })
        var runs = 0

        retry.afterFailure(failure) { runs++ }
        runCurrent()

        assertEquals(1, runs)
    }
}
