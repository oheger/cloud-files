/*
 * Copyright 2020-2025 The Developers Team.
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

import com.github.cloudfiles.core.utils.LRUCache.{CacheEntry, removeKey}

import scala.collection.SortedMap

object LRUCache {
  /**
   * Creates a new instance of ''LRUCache'' with the given capacity.
   *
   * @param capacity the capacity
   * @tparam K the type of keys
   * @tparam V the type of values
   * @return the newly created instance
   */
  def apply[K, V](capacity: Int): LRUCache[K, V] =
    new LRUCache[K, V](capacity, Map.empty, SortedMap.empty)

  /**
   * An internally used data class that stores information about an entry
   * stored in the cache.
   *
   * @param value the value associated with the entry
   * @param index the index of this entry in the LRU list
   * @tparam V the value type
   */
  private case class CacheEntry[+V](value: V, index: Int)

  /**
   * Helper function to remove a key from a sorted map. The ''-'' method has
   * been deprecated in Scala 2.13.
   *
   * @param map the sorted map
   * @param key the key to be removed
   * @tparam K the key type of the cache
   * @return the sorted map with the key removed
   */
  private def removeKey[K](map: SortedMap[Int, K], key: Int): SortedMap[Int, K] =
    map.filterNot(e => e._1 == key)
}

/**
 * An implementation of a LRU cache.
 *
 * This class implements a cache with a given capacity. Instances are
 * immutable and allow fast access to the keys they contain. When elements are
 * added (either new elements or ones that already exist in the cache) they
 * are put in the front of the cache; if the cache's capacity is reached,
 * elements at the end are discarded.
 *
 * The intended usage scenario is that a number of read operations is
 * performed on an instance. Based on these operations it is determined which
 * new elements are to be added or which ones need to be put in front. The
 * corresponding updates are then triggered leading to a new instance.
 *
 * @param capacity the capacity of the cache
 * @param entries  a map storing the data of the cache
 * @param lruList  a map storing LRU-related information
 * @tparam K the type of keys
 * @tparam V the type of values
 */
class LRUCache[K, +V] private(val capacity: Int,
                              entries: Map[K, CacheEntry[V]],
                              lruList: SortedMap[Int, K]) {
  /**
   * Checks whether the given key is contained in this cache.
   *
   * @param key the key in question
   * @return a flag whether this key is contained in this cache
   */
  def contains(key: K): Boolean = entries contains key

  /**
   * Returns the number of elements contained in this cache.
   *
   * @return the number of contained elements
   */
  def size: Int = entries.size

  /**
   * Returns a set with all the keys that are currently contained in this
   * cache.
   *
   * @return the current key set of this cache
   */
  def keySet: Set[K] = entries.keySet

  /**
   * Adds or updates the given elements to the cache. If keys in the list
   * already exist, they are removed and then added with the new value (which
   * causes them to be moved to the front). If necessary, elements at the end
   * of the LRU list are removed.
   *
   * @param pairs the entries to be put in the cache
   * @return the updated instance of this cache
   */
  def put[U >: V](pairs: (K, U)*): LRUCache[K, U] = {
    val currentIndex = lruList.lastOption.map(_._1).getOrElse(0)
    val (updEntries, updLruList, _) =
      pairs.foldLeft((entries: Map[K, CacheEntry[U]], lruList, currentIndex)) { (maps, e) =>
        val index = maps._3 + 1
        val optOldValue = maps._1 get e._1
        val nextEntries = maps._1 + (e._1 -> CacheEntry(e._2, index))
        val nextLru = optOldValue.map(ce => removeKey(maps._2, ce.index))
          .getOrElse(maps._2).concat(List(index -> e._1))
        (nextEntries, nextLru, index)
      }

    val (resEntries, resLruList) = ensureCapacity(updEntries, updLruList)
    new LRUCache(capacity, resEntries, resLruList)
  }

  /**
   * Returns an ''Option'' with the value of the given key. If the key is not
   * found in the cache, result is ''None''.
   *
   * @param key the key in question
   * @return an ''Option'' with the value of this key
   */
  def get(key: K): Option[V] = entries.get(key) map (_.value)

  /**
   * Returns an ''Option'' with the value of the given key and a copy of this
   * instance with the key put in front of the LRU list (if it exists). If the
   * key cannot be found, this instance is returned unchanged.
   *
   * @param key the key in question
   * @return a tuple with the value of this key and the cache instance with the
   *         updated LRU list
   */
  def getLRU(key: K): (Option[V], LRUCache[K, V]) =
    get(key) match {
      case opt@Some(value) => (opt, put((key, value)))
      case None => (None, this)
    }

  /**
   * Returns the value of the given key. If the key is not found in the cache,
   * an exception is thrown.
   *
   * @param key the key in question
   * @return the value of this key
   * @throws NoSuchElementException if the key cannot be found
   */
  def apply(key: K): V = entries(key).value

  /**
   * Queries the given key and returns its value if it is found. Otherwise, the
   * given creator function is invoked with the key, and a pair with the key
   * and the return value is added to this cache. In both cases, the key is now
   * in front of the LRU list.
   *
   * @param key    the key in question
   * @param create the function to create a new value
   * @return a tuple with the value of the key and the cache instance with the
   *         updated LRU list
   */
  def getOrPut[U >: V](key: K)(create: K => U): (U, LRUCache[K, U]) = {
    val (optVal, next) = getLRU(key)
    optVal match {
      case Some(value) => (value, next)
      case None =>
        val newValue = create(key)
        (newValue, put(key -> newValue))
    }
  }

  /**
   * Removes elements from the given maps until the capacity limit of this
   * cache is reached.
   *
   * @param map the map with cache entries
   * @param lru the LRU list
   * @return a tuple with the updated collections
   */
  @scala.annotation.tailrec
  private def ensureCapacity[U >: V](map: Map[K, CacheEntry[U]], lru: SortedMap[Int, K]):
  (Map[K, CacheEntry[U]], SortedMap[Int, K]) =
    if (map.size > capacity) {
      val oldestKey = lru.firstKey
      ensureCapacity(map - lru(oldestKey), removeKey(lru, oldestKey))
    } else (map, lru)
}
