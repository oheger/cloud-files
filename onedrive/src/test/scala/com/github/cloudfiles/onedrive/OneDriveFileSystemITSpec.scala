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

package com.github.cloudfiles.onedrive

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.{AsyncTestHelper, FileSystem, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{equalTo, get, stubFor, urlPathEqualTo}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

object OneDriveFileSystemITSpec {
  /** Test OneDrive ID. */
  private val DriveID = "1234567890"

  /** The base path of the server API. */
  private val ServerPath = "/v1.0/me/drives"

  /** The root path within OneDrive. */
  private val RootPath = "/my/data"

  /** A test ID that appears in some test server responses. */
  private val ResolvedID = "some_test_id"

  /**
   * Generates the full relative URI that corresponds to the given path.
   * Appends the correct prefix for all requests.
   *
   * @param path the path to be resolved
   * @return the relative URI corresponding to this path
   */
  private def drivePath(path: String): String =
    s"$ServerPath/$DriveID$path"
}

/**
 * Test class for ''OneDriveFileSystem''.
 */
class OneDriveFileSystemITSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with WireMockSupport with AsyncTestHelper {
  override protected val resourceRoot: String = "onedrive"

  import OneDriveFileSystemITSpec._

  /**
   * Creates a test configuration that points to the local WireMock server.
   *
   * @return the test configuration
   */
  private def createConfig(): OneDriveConfig =
    OneDriveConfig(driveID = DriveID, serverUri = serverUri(ServerPath), optRootPath = Some(RootPath))

  /**
   * Executes the given file system operation against the mock server.
   *
   * @param op the operation to execute
   * @tparam A the result type of the operation
   * @return a future with the result of the operation
   */
  private def runOp[A](op: FileSystem.Operation[A]): Future[A] = {
    val httpActor = spawn(HttpRequestSender(serverUri("")))
    op.run(httpActor)
  }

  private def stubResolvePath(path: String): Unit = {
    stubFor(get(urlPathEqualTo(drivePath(path)))
      .withQueryParam("select", equalTo("id"))
      .willReturn(aJsonResponse()
        .withBodyFile("resolve_response.json")))
  }

  "OneDriveFileSystem" should "determine the root ID for an undefined root path" in {
    stubResolvePath("/root")
    val fs = new OneDriveFileSystem(createConfig().copy(optRootPath = None))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "determine the root ID if a root path is specified" in {
    stubResolvePath(s"/root:$RootPath:")
    val fs = new OneDriveFileSystem(createConfig())

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "handle a trailing slash in the server URI" in {
    stubResolvePath(s"/root:$RootPath:")
    val config = createConfig()
    val fs = new OneDriveFileSystem(config.copy(serverUri = config.serverUri + "/"))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "handle a missing leading slash in the root path" in {
    stubResolvePath(s"/root:$RootPath:")
    val config = createConfig()
    val fs = new OneDriveFileSystem(config.copy(optRootPath = Some(RootPath.drop(1))))

    val rootID = futureResult(runOp(fs.rootID))
    rootID should be(ResolvedID)
  }

  it should "resolve a path for an undefined root path" in {
    val path = "/the/path/to/resolve"
    stubResolvePath(s"/root:$path:")
    val fs = new OneDriveFileSystem(createConfig().copy(optRootPath = None))

    val pathID = futureResult(runOp(fs.resolvePath(path)))
    pathID should be(ResolvedID)
  }

  it should "resolve a path if a root path is specified" in {
    val path = "/the/path/to/resolve/from/root/foo.txt"
    stubResolvePath(s"/root:$RootPath$path:")
    val fs = new OneDriveFileSystem(createConfig())

    val pathID = futureResult(runOp(fs.resolvePath(path)))
    pathID should be(ResolvedID)
  }

  it should "resolve a path that does not start with a slash" in {
    val path = "path/no/slash"
    stubResolvePath(s"/root:$RootPath/$path:")
    val fs = new OneDriveFileSystem(createConfig())

    val pathID = futureResult(runOp(fs.resolvePath(path)))
    pathID should be(ResolvedID)
  }
}
