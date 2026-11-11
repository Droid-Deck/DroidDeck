package com.droiddeck.launcher.stores

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class StoreSizesTest {
    private val ea = CatalogItem(Store.EA, "Origin.OFR.50.0001", "Game", imageUrl = null, owned = true)

    @After fun clear() {
        StoreSizes.known.clear()
    }

    @Test fun anUnresolvedSizeIsUnknown() {
        assertEquals(0L, StoreSizes.size(ea))
    }

    @Test fun aResolvedSizeReplacesTheCatalogValue() {
        StoreSizes.known[ea.key] = 4_200_000_000L
        assertEquals(4_200_000_000L, StoreSizes.size(ea))
    }

    @Test fun aResolvedSizeOnlyAppliesToItsOwnGame() {
        StoreSizes.known[ea.key] = 4_200_000_000L
        assertEquals(0L, StoreSizes.size(ea.copy(id = "Origin.OFR.50.0002")))
        assertEquals(7L, StoreSizes.size(ea.copy(id = "Origin.OFR.50.0002", sizeBytes = 7L)))
    }

    @Test fun anEaSizeDoesNotReplaceAnotherStoresCatalogSize() {
        StoreSizes.known[ea.key] = 4_200_000_000L
        val epic = ea.copy(store = Store.EPIC, id = "Epic.Game", sizeBytes = 7L)
        assertEquals(7L, StoreSizes.size(epic))
    }

    @Test fun aFailedLookupIsRetriedOnTheNextRequest() {
        val requests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
        val failed = StoreSizes.shared(requests, ea.key) { CompletableFuture.completedFuture(null) }
        assertEquals(null, failed.get())
        val retried = StoreSizes.shared(requests, ea.key) { CompletableFuture.completedFuture(9L) }
        assertNotSame(failed, retried)
        assertEquals(9L, retried.get())
    }

    @Test fun aResolvedLookupIsSharedAndKept() {
        val requests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
        val first = StoreSizes.shared(requests, ea.key) { CompletableFuture.completedFuture(5L) }
        val second = StoreSizes.shared(requests, ea.key) { CompletableFuture.completedFuture(6L) }
        assertSame(first, second)
        assertEquals(5L, second.get())
    }

    @Test fun anInFlightLookupIsSharedBetweenRequests() {
        val requests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
        val pending = CompletableFuture<Long?>()
        val first = StoreSizes.shared(requests, ea.key) { pending }
        val second = StoreSizes.shared(requests, ea.key) { CompletableFuture.completedFuture(1L) }
        assertSame(first, second)
        pending.complete(null)
        assertEquals(null, second.get())
        assertEquals(0, requests.size)
    }

    @Test fun aContinuationThatRequestsAgainAfterAFailureStartsANewLookup() {
        val requests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
        val pending = CompletableFuture<Long?>()
        val first = StoreSizes.shared(requests, ea.key) { pending }
        val retried = CompletableFuture<Long?>()
        first.thenAccept { bytes ->
            if (bytes == null) retried.complete(StoreSizes.shared(requests, ea.key) { CompletableFuture.completedFuture(9L) }.get())
        }
        pending.complete(null)
        assertEquals(9L, retried.get())
    }

    @Test fun concurrentCallersShareOneLookup() {
        val requests = ConcurrentHashMap<String, CompletableFuture<Long?>>()
        val pending = CompletableFuture<Long?>()
        var starts = 0
        val callers = (1..3).map { StoreSizes.shared(requests, ea.key) { starts++; pending } }
        pending.complete(3L)
        assertEquals(1, starts)
        callers.forEach { assertEquals(3L, it.get()) }
    }
}
