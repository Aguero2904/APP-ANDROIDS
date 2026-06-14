package com.example

import com.example.data.network.BcvRateFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
    @Test
    fun testBcvRateFetcher() = runBlocking {
        println("--- TESTING BCV RATE FETCHER PRODUCTION PIPELINE ---")
        val rate = BcvRateFetcher.fetchLatestRate()
        println("Fetched official BCV Rate: $rate")
        
        assertNotNull("Exchange rate must not be null", rate)
        assertTrue("Exchange rate must be positive", rate!! > 0)
    }
}
