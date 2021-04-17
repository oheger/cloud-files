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
import akka.util.ByteString
import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.{AsyncTestHelper, Model}
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.crypt.alg.ShiftCryptAlgorithm
import com.github.cloudfiles.crypt.service.CryptService
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers

import java.security.SecureRandom
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

  /** A default configuration for cryptographic operations. */
  final val DefaultCryptConfig =
    CryptConfig(ShiftCryptAlgorithm, ShiftCryptAlgorithm.encryptKey, ShiftCryptAlgorithm.decryptKey, new SecureRandom)

  /**
   * Generates the ID of a test file based on the given index.
   *
   * @param idx the index
   * @return the ID of this test file
   */
  def fileID(idx: Int): String = "file_" + idx

  /**
   * Generates the name of a file based on the given index.
   *
   * @param idx the index
   * @return the name of this test file
   */
  def fileName(idx: Int): String = s"testFile$idx.txt"

  /**
   * Generates the ID of a test folder based on the given index.
   *
   * @param idx the index
   * @return the ID of this test folder
   */
  def folderID(idx: Int): String = "folder_" + idx

  /**
   * Generates the name of a test folder based on the given index.
   *
   * @param idx the index
   * @return the name of this test folder
   */
  def folderName(idx: Int): String = "testFolder" + idx

  /**
   * Initializes the given mock of a file system element to return the
   * properties specified.
   *
   * @param elem the mock for an element
   * @param id   the ID of this element
   * @param name the name of this element
   * @tparam A the type of the element
   * @return the initialized mock
   */
  def initMock[A <: Model.Element[String]](elem: A, id: String, name: String): A = {
    when(elem.id).thenReturn(id)
    when(elem.name).thenReturn(name)
    elem
  }

  /**
   * Returns the encrypted form of the given name using the test algorithm.
   *
   * @param name the name to encrypt
   * @return the encrypted name
   */
  def encryptName(name: String): String =
    CryptService.encodeBase64(ShiftCryptAlgorithm.encrypt(ByteString(name)))

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
