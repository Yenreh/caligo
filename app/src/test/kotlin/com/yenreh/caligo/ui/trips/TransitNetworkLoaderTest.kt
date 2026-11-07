@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yenreh.caligo.ui.trips

import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.domain.entities.LineStop
import com.yenreh.caligo.ui.FakeStopsRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transitNetworkProvider: the routes loading, how many are in, then the
 * network, kept for the run; a failure tried again as Riverpod 3 does
 */
class TransitNetworkLoaderTest {
    private fun stop(id: String, x: Double) = LineStop(id, "Stop $id", 3.45, -76.53 + x * 0.004, 0, (x + 1).toInt())

    private fun repository() = FakeStopsRepository(emptyList(), emptyList()).apply {
        routes = mapOf(
            "T1" to listOf(stop("a", 0.0), stop("b", 1.0)),
            "A2" to listOf(stop("b", 1.0), stop("c", 2.0)),
        )
    }

    /** Where loaders run: on the test's clock, eagerly, and let go with the test */
    private class Run(val dispatcher: CoroutineDispatcher) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        fun loader(repository: FakeStopsRepository) = TransitNetworkLoader(repository, scope, building = dispatcher)
    }

    private fun test(body: suspend TestScope.(run: Run) -> Unit) = runTest {
        val run = Run(UnconfinedTestDispatcher(testScheduler))
        try {
            body(run)
        } finally {
            run.scope.cancel()
        }
    }

    @Test
    fun loadsOnceWhenFirstWatchedAndKeepsTheNetwork() = test { run ->
        val repository = repository()
        val loader = run.loader(repository)
        assertEquals(NetworkLoad(), loader.load.value)
        assertEquals(0, repository.allRoutesCalls)

        loader.watch()
        val load = loader.load.value
        assertEquals(2, load.done)
        assertEquals(2, load.total)
        assertEquals(setOf("a", "b", "c"), load.network!!.stops.map { it.stopId }.toSet())

        loader.watch()
        assertEquals(1, repository.allRoutesCalls)
        assertSame(load, loader.load.value)
    }

    @Test
    fun tellsHowManyLinesAreInBeforeTheNetwork() = test { run ->
        val gate = CompletableDeferred<Unit>()
        val repository = repository().apply { allRoutesAnswer = { gate.await() } }
        val loader = run.loader(repository)

        loader.watch()
        assertEquals(NetworkLoad(done = 2, total = 2), loader.load.value)

        gate.complete(Unit)
        assertNotNull(loader.load.value.network)
    }

    @Test
    fun aFailureIsTriedAgainOnItsOwnSoonerFirstThenLess() = test { run ->
        val repository = repository().apply { allRoutesFailure = NetworkApiException("Connection error") }
        val loader = run.loader(repository)

        loader.watch()
        assertTrue(loader.load.value.failed)
        assertEquals(1, repository.allRoutesCalls)

        // 200, 400, 800 ms, then twice as long each time up to 6.4 s
        for ((i, wait) in listOf(200L, 400, 800, 1600, 3200, 6400, 6400, 6400, 6400, 6400).withIndex()) {
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals(i + 1, repository.allRoutesCalls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(i + 2, repository.allRoutesCalls)
        }
        // Ten times over the run, then only Retry
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(11, repository.allRoutesCalls)
        assertTrue(loader.load.value.failed)

        repository.allRoutesFailure = null
        loader.reload()
        assertNotNull(loader.load.value.network)
        assertNull(loader.load.value.error)
    }

    @Test
    fun retryStartsAtOnceAndSpendsNoneOfTheTriesOnItsOwn() = test { run ->
        val repository = repository().apply { allRoutesFailure = NetworkApiException("Connection error") }
        val loader = run.loader(repository)
        loader.watch()
        advanceTimeBy(100)

        loader.reload()
        assertEquals(2, repository.allRoutesCalls)
        // The try it replaced is let go; the next comes after the first wait again
        advanceTimeBy(199)
        runCurrent()
        assertEquals(2, repository.allRoutesCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, repository.allRoutesCalls)
    }

    @Test
    fun aFailureStaysOnShowUntilTheFirstLineOfTheNextTry() = test { run ->
        val repository = repository().apply {
            routes = emptyMap()
            allRoutesFailure = NetworkApiException("Connection error")
        }
        val loader = run.loader(repository)
        loader.watch()
        val gate = CompletableDeferred<Unit>()
        repository.allRoutesFailure = null
        repository.allRoutesAnswer = { gate.await() }

        loader.reload()
        assertTrue(loader.load.value.failed)

        gate.complete(Unit)
        assertFalse(loader.load.value.failed)
        assertTrue(loader.load.value.network!!.isEmpty)
    }

    @Test
    fun aFailureOutsideTheServiceIsNotTriedAgain() = test { run ->
        val repository = repository().apply { allRoutesFailure = IllegalStateException("Bad state") }
        val loader = run.loader(repository)

        loader.watch()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, repository.allRoutesCalls)
        assertTrue(loader.load.value.failed)
    }
}
