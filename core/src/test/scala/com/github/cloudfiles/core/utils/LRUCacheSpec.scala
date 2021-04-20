/*
 * Copyright 2020-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.cloudfiles.core.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object LRUCacheSpec {
  /**
   * Generates a test key based on the given index.
   *
   * @param idx the index
   * @return the test key with this index
   */
  private def entryKey(idx: Int): String = "k" + idx

  /**
   * Generates a test cache entry based on the given index.
   *
   * @param idx the index
   * @return the key-value pair based on this index
   */
  private def entry(idx: Int): (String, Int) = (entryKey(idx), idx)

  /**
   * Generates a sequence of test cache entries with indices in the provided
   * range.
   *
   * @param from the from index
   * @param to   the to index (including)
   * @return the sequence of entries
   */
  private def entries(from: Int, to: Int): Seq[(String, Int)] =
    (from to to) map entry

  /**
   * Adds the specified entries to the given cache.
   *
   * @param cache the cache
   * @param pairs the entries to be added
   * @return the cache with entries added
   */
  private def addEntries(cache: LRUCache[String, Int], pairs: Seq[(String, Int)]):
  LRUCache[String, Int] =
    cache.put(pairs: _*)
}

/**
 * Test class for ''LRUCache''.
 */
class LRUCacheSpec extends AnyFlatSpec with Matchers {

  import LRUCacheSpec._

  /**
   * Checks that the given cache contains all the specified entries.
   *
   * @param cache the cache
   * @param pairs the entries to be checked
   */
  private def assertContains(cache: LRUCache[String, Int], pairs: (String, Int)*): Unit = {
    pairs foreach { p =>
      cache contains p._1 shouldBe true
      cache get p._1 should be(Some(p._2))
    }
  }

  "A LRUCache" should "allow adding entries up to its capacity" in {
    val pairs = entries(1, 10)

    val cache = addEntries(LRUCache[String, Int](pairs.size), pairs)
    cache.size should be(pairs.size)
    cache.keySet should contain theSameElementsAs pairs.map(_._1)
    assertContains(cache, pairs: _*)
  }

  it should "remove older entries to keep its maximum capacity" in {
    val pairs = entries(1, 8)
    val CacheCapacity = pairs.size / 2

    val cache = addEntries(LRUCache[String, Int](CacheCapacity), pairs)
    cache.size should be(CacheCapacity)
    cache contains pairs.head._1 shouldBe false
    cache get pairs.head._1 shouldBe None
    assertContains(cache, pairs.drop(CacheCapacity): _*)
    cache.keySet should contain theSameElementsAs pairs.drop(CacheCapacity).map(_._1)
  }

  it should "move an entry to the front when it is re-added" in {
    val pairs = entries(1, 8)
    val c1 = addEntries(LRUCache[String, Int](pairs.size), pairs)

    val c2 = c1.put(pairs.head._1 -> pairs.head._2)
    val cache = c2.put(entryKey(42) -> 42)
    cache get pairs.head._1 should be(Some(pairs.head._2))
    cache contains pairs.drop(1).head._1 shouldBe false
  }

  it should "handle an LRU get for an unknown key" in {
    val pairs = entries(0, 7)
    val cache = addEntries(LRUCache[String, Int](10), pairs)

    val (optVal, nextCache) = cache.getLRU("nonExistingKey")
    optVal should be(None)
    nextCache should be theSameInstanceAs cache
  }

  it should "move an existing entry to the front when it is queried by getLRU" in {
    val pairs = entries(1, 8)
    val c1 = addEntries(LRUCache[String, Int](pairs.size), pairs)

    val (optVal, c2) = c1.getLRU(pairs.head._1)
    optVal should be(Some(pairs.head._2))
    val cache = c2.put(entryKey(42) -> 42)
    cache get pairs.head._1 should be(Some(pairs.head._2))
    cache contains pairs.drop(1).head._1 shouldBe false
  }

  it should "handle a getOrPut() operation for an existing key" in {
    val pairs = entries(1, 4)
    val c1 = addEntries(LRUCache[String, Int](pairs.size), pairs)

    val (value, c2) = c1.getOrPut(pairs.head._1)(_ => throw new UnsupportedOperationException("Unexpected call"))
    value should be(pairs.head._2)
    val cache = c2.put(entryKey(42) -> 42)
    cache get pairs.head._1 should be(Some(pairs.head._2))
    cache contains pairs.drop(1).head._1 shouldBe false
  }

  it should "handle a getOrPut() operation for a non-existing key" in {
    val NewKey = "brandNewKey"
    val NewValue = 20210420
    val c1 = addEntries(LRUCache[String, Int](4), entries(1, 3))

    val (value, c2) = c1.getOrPut(NewKey) { key =>
      key should be(NewKey)
      NewValue
    }
    value should be(NewValue)
    val cache = c2.put(entry(4))
    cache(NewKey) should be(NewValue)
  }
}
