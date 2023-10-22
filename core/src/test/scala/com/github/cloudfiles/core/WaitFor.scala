/*
 * Copyright 2020-2023 The Developers Team.
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

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Promise, TimeoutException}

/**
 * A helper module to allow waiting for a condition to be fulfilled.
 *
 * This module replaces the ''waitCond()'' function from the test kit for
 * classic actors.
 */
object WaitFor {
  /**
   * Waits until the given condition is fulfilled or throws a
   * ''TimeoutException'' when the maximum wait time is exceeded.
   *
   * @param testKit   the test kit
   * @param maxWait   the maximum time to wait
   * @param period    the interval in which the condition is checked
   * @param condition the condition to check
   */
  def condition(testKit: ActorTestKit, maxWait: FiniteDuration, period: FiniteDuration)
               (condition: => Boolean): Unit = {
    val endTime = System.currentTimeMillis() + maxWait.toMillis
    val promise = Promise[Unit]()

    checkCondition(testKit, endTime, period, promise)(condition)
    Await.result(promise.future, maxWait)
  }

  /**
   * Waits for the configured period before re-checking the condition.
   *
   * @param testKit   the test kit
   * @param endTime   the time when waiting ends
   * @param period    the interval in which the condition is checked
   * @param promise   the ''Promise'' to indicate the outcome
   * @param condition the condition to check
   */
  private def waitAndCheckCondition(testKit: ActorTestKit, endTime: Long, period: FiniteDuration,
                                    promise: Promise[Unit])(condition: => Boolean): Unit = {
    val remainingTime = math.max(1, endTime - System.currentTimeMillis()).millis
    implicit val ec: ExecutionContext = testKit.system.executionContext
    testKit.scheduler.scheduleOnce(period.min(remainingTime),
      () => checkCondition(testKit, endTime, period, promise)(condition))
  }

  /**
   * Checks the condition. If it is fulfilled, complete the ''Promise''
   * successfully. Otherwise, trigger another wait operation or fail the
   * ''Promise'' if the maximum waiting time has exceeded.
   *
   * @param testKit   the test kit
   * @param endTime   the time when waiting ends
   * @param period    the interval in which the condition is checked
   * @param promise   the ''Promise'' to indicate the outcome
   * @param condition the condition to check
   */
  private def checkCondition(testKit: ActorTestKit, endTime: Long, period: FiniteDuration, promise: Promise[Unit])
                            (condition: => Boolean): Unit = {
    if (!condition) {
      if (System.currentTimeMillis() >= endTime) {
        promise.failure(new TimeoutException("Waiting for condition failed."))
      }

      waitAndCheckCondition(testKit, endTime, period, promise)(condition)
    } else promise.success(())
  }
}
