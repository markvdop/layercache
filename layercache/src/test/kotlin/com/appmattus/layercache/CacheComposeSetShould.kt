/*
 * Copyright 2017 Appmattus Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.appmattus.layercache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.hamcrest.core.Is.isA
import org.hamcrest.core.StringStartsWith
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyString
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CacheComposeSetShould {

    @get:Rule
    var thrown: ExpectedException = ExpectedException.none()

    @get:Rule
    var executions = ExecutionExpectation()

    @Mock
    private lateinit var firstCache: AbstractCache<String, String>

    @Mock
    private lateinit var secondCache: AbstractCache<String, String>

    private lateinit var composedCache: Cache<String, String>

    @Before
    fun before() {
        MockitoAnnotations.initMocks(this)
        Mockito.`when`(firstCache.compose(secondCache)).thenCallRealMethod()
        composedCache = firstCache.compose(secondCache)
        verify(firstCache).compose(secondCache)
    }

    @Test
    fun `throw exception when key is null`() {
        runBlocking {
            // expect exception
            thrown.expect(IllegalArgumentException::class.java)
            thrown.expectMessage(StringStartsWith("Parameter specified as non-null is null"))

            // when key is null
            composedCache.set(TestUtils.uninitialized(), "value").await()
        }
    }

    @Test
    fun `throw exception when value is null`() {
        runBlocking {
            // expect exception
            thrown.expect(IllegalArgumentException::class.java)
            thrown.expectMessage(StringStartsWith("Parameter specified as non-null is null"))

            Mockito.`when`(firstCache.set(anyString(), MockitoKotlin.any())).then {
                GlobalScope.async {

                }
            }
            Mockito.`when`(secondCache.set(anyString(), MockitoKotlin.any())).then {
                GlobalScope.async {

                }
            }

            // when value is null
            composedCache.set("key", TestUtils.uninitialized()).await()
        }
    }


    @Test
    fun `execute internal requests on set in parallel`() {
        runBlocking {
            val jobTimeInMillis = 250L

            // given we have two caches with a long running job to set a value
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                async(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
                    TestUtils.blockingTask(jobTimeInMillis)
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                async(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
                    TestUtils.blockingTask(jobTimeInMillis)
                }
            }

            // when we set the value and start the timer
            val start = System.nanoTime()
            composedCache.set("key", "value").await()

            // then set is called in parallel
            val elapsedTimeInMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            Assert.assertTrue(elapsedTimeInMillis < (jobTimeInMillis * 2))
        }
    }

    @Test
    fun `execute set for each cache`() {
        runBlocking {
            // given we have two caches
            Mockito.`when`(firstCache.set(anyString(), anyString())).then { GlobalScope.async {} }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then { GlobalScope.async {} }

            // when we set the value
            composedCache.set("key", "value").await()

            // then set is called on both caches
            verify(firstCache).set("key", "value")
            verify(secondCache).set("key", "value")
            Mockito.verifyNoMoreInteractions(firstCache)
            Mockito.verifyNoMoreInteractions(secondCache)
        }
    }

    @Test
    fun `throw internal exception on set when the first cache throws`() {
        runBlocking {
            // expect exception and successful execution of secondCache
            thrown.expect(CacheException::class.java)
            thrown.expectMessage("set failed for firstCache")
            thrown.expectCause(isA(TestException::class.java))
            executions.expect(1)

            // given the first cache throws an exception
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    throw TestException()
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    delay(50)
                    executions.execute()
                }
            }

            // when we set the value
            val job = composedCache.set("key", "value")
            yield()

            // then set on the second cache still completes and an exception is thrown
            job.await()
        }
    }

    @Test
    fun `throw internal exception on set when the second cache throws`() {
        runBlocking {
            // expect exception and successful execution of firstCache
            thrown.expect(CacheException::class.java)
            thrown.expectMessage("set failed for secondCache")
            thrown.expectCause(isA(TestException::class.java))
            executions.expect(1)

            // given the second cache throws an exception
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    delay(50)
                    executions.execute()
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    throw TestException()
                }
            }

            // when we set the value
            val job = composedCache.set("key", "value")
            yield()

            // then an exception is thrown
            job.await()
        }
    }

    @Test
    fun `throw internal exception on set when both caches throws`() {
        runBlocking {
            // expect exception
            thrown.expect(CacheException::class.java)
            thrown.expectMessage("set failed for firstCache, set failed for secondCache")
            thrown.expectCause(isA(TestException::class.java))

            // given both caches throw an exception
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    throw TestException()
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    throw TestException()
                }
            }

            // when we set the value
            val job = composedCache.set("key", "value")
            yield()

            // then an exception is thrown
            job.await()
        }
    }

    @Test
    fun `throw exception when job cancelled on set and first cache is executing`() {
        runBlocking {
            // expect exception and successful execution of secondCache
            thrown.expect(CancellationException::class.java)
            thrown.expectMessage("Job was cancelled")
            executions.expect(1)

            // given the first cache throws an exception
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    delay(250)
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    executions.execute()
                }
            }

            // when we set the value
            val job = composedCache.set("key", "value")
            delay(50)
            job.cancel()
            yield()

            // then set on the second cache still completes and an exception is thrown
            job.await()
        }
    }

    @Test
    fun `throw exception when job cancelled on set and second cache is executing`() {
        runBlocking {
            // expect exception and successful execution of firstCache
            thrown.expect(CancellationException::class.java)
            thrown.expectMessage("Job was cancelled")
            executions.expect(1)

            // given the first cache throws an exception
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    executions.execute()
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    delay(250)
                }
            }

            // when we set the value
            val job = composedCache.set("key", "value")
            delay(50)
            job.cancel()
            yield()

            // then set on the first cache still completes and an exception is thrown
            job.await()
        }
    }

    @Test
    fun `throw exception when job cancelled on set and both caches executing`() {
        runBlocking {
            // expect exception and no execution of caches
            thrown.expect(CancellationException::class.java)
            thrown.expectMessage("Job was cancelled")
            executions.expect(0)

            // given the first cache throws an exception
            Mockito.`when`(firstCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    delay(50)
                    executions.execute()
                }
            }
            Mockito.`when`(secondCache.set(anyString(), anyString())).then {
                GlobalScope.async {
                    delay(50)
                    executions.execute()
                }
            }

            // when we set the value
            val job = composedCache.set("key", "value")
            delay(25)
            job.cancel()
            yield()

            // then an exception is thrown
            job.await()
        }
    }
}