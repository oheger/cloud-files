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

package com.github.cloudfiles.crypt.fs

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.{AsyncTestHelper, Model}
import com.github.cloudfiles.core.http.HttpRequestSender
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

/**
 * A test helper module providing common functionality that is needed when
 * testing cryptographic file systems.
 */
object CryptFileSystemTestHelper extends Matchers with AsyncTestHelper {
  /** Alias for the type of files. */
  type FileType = Model.File[String]

  /** Alias for the type of folders. */
  type FolderType = Model.Folder[String]

  /** Alias for the type used for the folder content. */
  type ContentType = Model.FolderContent[String, FileType, FolderType]

  /** Constant for an ID value. */
  final val FileID = "testFileID"

  /**
   * Generates a stub operation that just returns a successful future with the
   * value provided.
   *
   * @param value the value to return from the operation
   * @tparam A the result type of the operation
   * @return the operation returning this value
   */
  def stubOperation[A](value: A): Operation[A] =
    Operation { httpSender =>
      httpSender should not be null
      Future.successful(value)
    }

  /**
   * Executes the passed in operation and returns its ''Future'' result.
   *
   * @param testKit the actor testkit
   * @param op      the operation to execute
   * @tparam A the result type of the operation
   * @return the ''Future'' returned by the operation
   */
  def runOpFuture[A](testKit: ActorTestKit, op: Operation[A]): Future[A] = {
    // The sender actor is not expected to be actually invoked.
    val sender = testKit.spawn(HttpRequestSender.apply("http://localhost"))
    op.run(sender)
  }

  /**
   * Executes the passed in operation and returns the result contained in the
   * ''Future'' produced by the operation.
   *
   * @param testKit the actor testkit
   * @param op      the operation to execute
   * @tparam A the result type of the operation
   * @return the result returned by the operation
   */
  def runOp[A](testKit: ActorTestKit, op: Operation[A]): A =
    futureResult(runOpFuture(testKit, op))
}
