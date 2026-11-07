package com.yenreh.caligo.data.repositories

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yenreh.caligo.data.MockService
import com.yenreh.caligo.data.MockService.Companion.dropped
import com.yenreh.caligo.data.MockService.Companion.ok
import com.yenreh.caligo.data.MockService.Companion.status
import com.yenreh.caligo.data.assertThrowsSuspend
import com.yenreh.caligo.data.datasources.AppDatabase
import com.yenreh.caligo.data.datasources.CacheEntry
import com.yenreh.caligo.data.datasources.JsonCache
import com.yenreh.caligo.data.datasources.NetworkApiException
import com.yenreh.caligo.data.datasources.ServerApiException
import com.yenreh.caligo.data.datasources.StopsLocalDatasource
import com.yenreh.caligo.data.datasources.StopsRemoteDatasource
import com.yenreh.caligo.data.json.parseJson
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The planner's paths of StopsRepositoryImpl: every route, four at a
 * time and stale copies first, and what is learned of each line, on the
 * real datasource against a mock service
 */
@RunWith(AndroidJUnit4::class)
class StopsRepositoryImplTest {
    private var service: MockService? = null

    @After
    fun close() {
        service?.close()
    }

    private fun answering(respond: (call: Int, url: HttpUrl) -> MockResponse) =
        MockService(respond).also { service = it }

    private fun MockService.repository(cache: JsonCache = JsonCache.None): StopsRepositoryImpl {
        val database = AppDatabase(ApplicationProvider.getApplicationContext(), name = null)
        return StopsRepositoryImpl(
            StopsLocalDatasource(database),
            StopsRemoteDatasource(client, cache, backoff = Duration.ZERO),
            cache,
        )
    }

    private fun catalog(names: List<String>) =
        names.mapIndexed { i, name -> """{"lineId":${i + 1},"name":"$name"}""" }.joinToString(",", "[", "]")

    private fun route(line: String) =
        """[{"orientation":"0","stopSequence":1,"stopId":"${line}1","stopNam":"$line 1","longitude":-76.53,"latitude":3.45},""" +
            """{"orientation":"0","stopSequence":2,"stopId":"${line}2","stopNam":"$line 2","longitude":-76.52,"latitude":3.45}]"""

    private val names = (1..10).map { "A%02d".format(it) }

    @Test
    fun loadsEveryRouteFourAtATimeTellingEachOneDone() = runTest {
        val atOnce = AtomicInteger()
        val most = AtomicInteger()
        val repository = answering { _, url ->
            val path = url.encodedPath
            if (path.endsWith("/lines")) return@answering ok(catalog(names))
            most.accumulateAndGet(atOnce.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(100)
            atOnce.decrementAndGet()
            ok(route(path.substringAfterLast('/')))
        }.repository()
        val progress = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

        val routes = repository.getAllLineStops { done, total -> progress.add(done to total) }

        assertEquals(names.toSet(), routes.keys)
        assertEquals(listOf("A011", "A012"), routes.getValue("A01").map { it.stopId })
        assertEquals((1..10).map { it to 10 }, progress)
        assertEquals("${most.get()} at once", 4, most.get())
    }

    @Test
    fun aFewMissingLinesStillLeaveANetworkWorthPlanningOn() = runTest {
        val failing = setOf("A02", "A05", "A09")
        val repository = answering { _, url ->
            val path = url.encodedPath
            val line = path.substringAfterLast('/')
            when {
                path.endsWith("/lines") -> ok(catalog(names))
                line in failing -> status(404, "Not Found")
                else -> ok(route(line))
            }
        }.repository()
        val progress = Collections.synchronizedList(mutableListOf<Int>())

        val routes = repository.getAllLineStops { done, _ -> progress.add(done) }

        assertEquals(names.toSet() - failing, routes.keys)
        // A line that fails is done too
        assertEquals((1..10).toList(), progress)
    }

    @Test
    fun moreThanHalfOfTheLinesFailingIsAFailure() = runTest {
        val repository = answering { _, url ->
            val path = url.encodedPath
            val line = path.substringAfterLast('/')
            when {
                path.endsWith("/lines") -> ok(catalog(names))
                line < "A07" -> status(404, "Not Found")
                else -> ok(route(line))
            }
        }.repository()

        val error = assertThrowsSuspend<ServerApiException> { repository.getAllLineStops() }

        assertEquals(404, error.statusCode)
    }

    @Test
    fun halfOfTheLinesFailingStillPlans() = runTest {
        val repository = answering { _, url ->
            val path = url.encodedPath
            val line = path.substringAfterLast('/')
            when {
                path.endsWith("/lines") -> ok(catalog(names))
                line <= "A05" -> status(404, "Not Found")
                else -> ok(route(line))
            }
        }.repository()

        assertEquals(5, repository.getAllLineStops().size)
    }

    @Test
    fun withoutTheCatalogOrACopyOfItNothingLoads() = runTest {
        val repository = answering { _, _ -> dropped() }.repository()

        assertThrowsSuspend<NetworkApiException> { repository.getAllLineStops() }
    }

    @Test
    fun anEmptyCatalogIsAnEmptyNetwork() = runTest {
        val repository = answering { _, _ -> ok("[]") }.repository()

        assertTrue(repository.getAllLineStops().isEmpty())
    }

    @Test
    fun weekOldCopiesAnswerAtOnceOffline() = runTest {
        val cache = OldCache(
            listOf("lines" to catalog(listOf("A01", "A02"))) +
                listOf("A01", "A02").map { "linestops_$it" to route(it) },
        )
        val repository = answering { _, _ -> dropped() }.repository(cache)

        val routes = repository.getAllLineStops()

        assertEquals(setOf("A01", "A02"), routes.keys)
    }

    @Test
    fun handsOverWhatIsLearnedOfEachLine() = runTest {
        val repository = answering { _, _ -> ok("[]") }.repository()

        repository.observeRides(mapOf("T31" to 1.5))

        assertEquals(1.1, repository.rideFactors().getValue("T31"), 1e-9)
        assertTrue(repository.lineHeadways(System.currentTimeMillis()).isEmpty())
    }

    /** Cache holding copies saved long ago, as a week offline leaves them */
    private class OldCache(old: List<Pair<String, String>>) : JsonCache {
        private val old = old.associate { (key, text) -> key to parseJson(text) }
        val written: MutableMap<String, JsonElement> = ConcurrentHashMap()

        override suspend fun read(key: String, maxAge: Duration?): JsonElement? = written[key] ?: old[key]

        override suspend fun readEntry(key: String, remember: Boolean): CacheEntry? {
            written[key]?.let { return CacheEntry(System.currentTimeMillis(), it) }
            return old[key]?.let { CacheEntry(LONG_AGO, it) }
        }

        override suspend fun write(key: String, value: JsonElement, remember: Boolean) {
            written[key] = value
        }

        override suspend fun prune(maxAge: Duration) = Unit
    }

    private companion object {
        /** 2020-01-01 */
        const val LONG_AGO = 1_577_836_800_000L
    }
}
