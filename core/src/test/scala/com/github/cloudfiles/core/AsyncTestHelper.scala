/*
 * Copyright 2020-2022 The Developers Team.
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

package com.github.cloudfiles.core

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag
import scala.util.Failure

/**
 * A test helper class that supports tests of functionality that runs
 * asynchronously or is based on futures.
 *
 * This trait offers methods to obtain the result of a future with a timeout
 * and to check for failed future operations.
 */
trait AsyncTestHelper {
  /**
   * The default timeout when waiting for a future result. If derived classes
   * need a different timeout value, this property can be overridden.
   */
  val asyncTimeout: Duration = 3.seconds

  /**
   * Waits for the given future to complete and returns the result.
   *
   * @param future the future
   * @tparam A the type of the future
   * @return the result of the future
   */
  def futureResult[A](future: Future[A]): A =
    Await.result(future, asyncTimeout)

  /**
   * Checks whether a future failed with the given exception type.
   *
   * @param future the future
   * @param ct     the class tag
   * @tparam E the type of the expected exception
   * @return the exception
   */
  def expectFailedFuture[E](future: Future[_])(implicit ct: ClassTag[E]): E = {
    Await.ready(future, asyncTimeout)
    future.value match {
      case Some(Failure(exception)) if ct.runtimeClass.isInstance(exception) =>
        ct.runtimeClass.cast(exception).asInstanceOf[E]
      case r =>
        throw new AssertionError("Unexpected result: " + r)
    }
  }
}
