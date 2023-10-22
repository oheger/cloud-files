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

package com.github.cloudfiles.crypt.fs.resolver

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.Spawner
import com.github.cloudfiles.core.{AsyncTestHelper, FileSystem, Model}
import com.github.cloudfiles.crypt.fs.resolver.CachePathComponentsResolver.PathLookupCommand
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.mockito.Mockito.{verify, when}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object CachePathComponentsResolverSpec {
  /** Constant for the ID of the file system's root folder. */
  private val RootID = "<<ROOT>>"

  /** The size of the cache. */
  private val CacheSize = 32

  /**
   * Returns an ''Operation'' that produces the given future result.
   *
   * @param result the result ''Future''
   * @tparam R the type of the result
   * @return the ''Operation'' producing this result
   */
  private def stubFutureOperation[R](result: Future[R]): Operation[R] =
    Operation { _ => result }

  /**
   * The base trait for commands related to death watch of actors.
   */
  private sealed trait WatchCommand

  /**
   * A command that triggers watching of another actor. When this other actor
   * is terminated, the passed in actor is sent a '''true''' message.
   *
   * @param actor    the actor to watch
   * @param notifier the actor to notify on termination of the watched actor
   */
  private case class StartWatching(actor: ActorRef[_], notifier: ActorRef[Boolean]) extends WatchCommand

  /**
   * A message that indicates that a watched actor has terminated.
   *
   * @param notifier the actor to notify on termination of the watched actor
   */
  private case class WatchTerminated(notifier: ActorRef[Boolean]) extends WatchCommand

  /**
   * Returns the behavior of an actor that sets a flag when it is triggered.
   * This is used to implement death watch on other actors.
   *
   * @return the behavior to set a flag
   */
  private def watchActor(): Behavior[WatchCommand] = Behaviors.receivePartial {
    case (ctx, StartWatching(actor, notifier)) =>
      ctx.watchWith(actor, WatchTerminated(notifier))
      ctx.log.info("Watching {} for termination.", actor)
      Behaviors.same

    case (ctx, WatchTerminated(notifier)) =>
      ctx.log.info("Watched actor terminated.")
      notifier ! true
      Behaviors.stopped
  }
}

/**
 * Test class for ''CachePathComponentsResolver''.
 */
class CachePathComponentsResolverSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with AsyncTestHelper with MockitoSugar {

  import CachePathComponentsResolverSpec._
  import com.github.cloudfiles.crypt.fs.CryptFileSystemTestHelper._

  /** Type for a factory to create a test resolver. */
  private type ResolverFactory = () => PathResolver[String, FileType, FolderType]

  /**
   * Creates a mock for a file with the given properties.
   *
   * @param idx  the index of the test file
   * @param name the name of this file
   * @return the mock for this test file
   */
  private def createFileMock(idx: Int, name: String): FileType =
    initMock(mock[FileType], fileID(idx), name)

  /**
   * Creates a mock for a folder with the given properties.
   *
   * @param idx  the index of the test folder
   * @param name the name of this folder
   * @return the mock for this test folder
   */
  private def createFolderMock(idx: Int, name: String): FolderType =
    initMock(mock[FolderType], folderID(idx), name)

  /**
   * Creates a ''FolderContent'' object with a number of mock elements.
   *
   * @param fileIdx     the start index of file mocks
   * @param folderIdx   the start index of folder mocks
   * @param fileCount   the number of files
   * @param folderCount the number of folders
   * @return the ''FolderContent'' object with this content
   */
  private def createFolderContent(id: String, fileIdx: Int = 1, folderIdx: Int = 1, fileCount: Int = 4, folderCount: Int = 2):
  Model.FolderContent[String, FileType, FolderType] = {
    val files = (fileIdx until (fileIdx + fileCount))
      .map { idx => (fileID(idx), createFileMock(idx, encryptName(fileName(idx)))) }
      .toMap
    val folders = (folderIdx until (folderIdx + folderCount))
      .map { idx => (folderID(idx), createFolderMock(idx, encryptName(folderName(idx)))) }
      .toMap
    Model.FolderContent(id, files, folders)
  }

  /**
   * Checks whether the given actor terminates within the test timeout.
   *
   * @param actor the actor to check
   */
  private def expectTerminated(actor: ActorRef[_]): Unit = {
    val probe = testKit.createTestProbe[Boolean]()
    val watcher = testKit.spawn(watchActor())
    watcher ! StartWatching(actor, probe.ref)

    probe.expectMessage(true)
  }

  /**
   * Creates a test resolver using a spawner.
   *
   * @return the test resolver instance
   */
  private def createResolverFromSpawner(): PathResolver[String, FileType, FolderType] = {
    val ActorName = "MyTestResolverActor"
    val spawner = new Spawner {
      override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] = {
        optName should be(Some(ActorName))
        testKit.spawn(behavior)
      }
    }

    CachePathComponentsResolver[String, FileType, FolderType](spawner, CacheSize, optActorName = Some(ActorName))
  }

  /**
   * Creates a test resolver using the given resolver actor.
   *
   * @param actor the resolver actor
   * @return the test resolver instance
   */
  private def createResolverFromActor(actor: ActorRef[PathLookupCommand[String, FileType, FolderType]]):
  ResolverFactory = () => CachePathComponentsResolver[String, FileType, FolderType](actor)

  "CachePathComponentsResolver" should "resolve an element in the root folder" in {
    val rootContent = createFolderContent(RootID)
    val helper = new ResolverTestHelper

    helper.prepareRootID()
      .prepareFolderContent(rootContent)
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
  }

  it should "ignore elements with invalid names when processing folder content" in {
    val baseRootContent = createFolderContent(RootID)
    val invalidFile = createFileMock(42, "notEncoded.file")
    val rootContent = baseRootContent.copy(files = baseRootContent.files + (invalidFile.id -> invalidFile))
    val helper = new ResolverTestHelper

    helper.prepareRootID()
      .prepareFolderContent(rootContent)
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
  }

  it should "cache already resolved paths" in {
    val rootContent = createFolderContent(RootID)
    val helper = new ResolverTestHelper

    helper.prepareRootID()
      .prepareFolderContent(rootContent)
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
      .resolveAndExpect(Seq(folderName(1)), folderID(1))
      .verifyFolderRequestedOnce(RootID)
  }

  it should "request a folder only once if there are concurrent requests" in {
    val rootContent = createFolderContent(RootID)
    val promiseFolderContent = Promise[Model.FolderContent[String, FileType, FolderType]]()
    val helper = new ResolverTestHelper

    helper.prepareRootID()
      .prepareFolderContentFuture(RootID, promiseFolderContent.future)
    val futRes1 = helper.resolve(Seq(fileName(1)))
    val futRes2 = helper.resolve(Seq(fileName(2)))
    promiseFolderContent.complete(Success(rootContent))
    futureResult(futRes1) should be(fileID(1))
    futureResult(futRes2) should be(fileID(2))
    helper.verifyFolderRequestedOnce(RootID)
  }

  it should "handle a failed request for the root folder" in {
    val exceptionRootFolder = new IOException("Test exception: Could not locate root folder!")
    val promiseRoot = Promise[String]()
    val helper = new ResolverTestHelper

    helper.prepareRootID(futID = promiseRoot.future)
    val futRes1 = helper.resolve(Seq(fileName(1)))
    val futRes2 = helper.resolve(Seq(folderName(1)))
    promiseRoot.complete(Failure(exceptionRootFolder))
    expectFailedFuture[IOException](futRes1) should be(exceptionRootFolder)
    expectFailedFuture[IOException](futRes2) should be(exceptionRootFolder)
  }

  it should "recover from a failure to resolve the root folder" in {
    val helper = new ResolverTestHelper
    val futFailed = helper.prepareRootID(Future.failed(new IOException("Test exception: No root folder!")))
      .prepareFolderContent(createFolderContent(RootID))
      .resolve(Seq(fileName(1)))
    expectFailedFuture[IOException](futFailed)

    helper.prepareRootID()
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
  }

  it should "handle a failed request for the content of a folder" in {
    val exception = new IOException("Test exception: Could not resolve folder!")
    val helper = new ResolverTestHelper

    val futRes = helper.prepareRootID()
      .prepareFolderContentFuture(RootID, Future.failed(exception))
      .resolve(Seq(fileID(1)))
    expectFailedFuture[IOException](futRes) should be(exception)
  }

  it should "handle a path that cannot be resolved" in {
    val components = Seq("this", "path", "does", "not", "exist")
    val helper = new ResolverTestHelper

    val futRes = helper.prepareRootID()
      .prepareFolderContent(createFolderContent(RootID))
      .resolve(components)
    val exception = expectFailedFuture[IllegalArgumentException](futRes)
    exception.getMessage should include(components.mkString(","))
  }

  it should "handle path resolve requests with multiple components" in {
    val rootContent = createFolderContent(RootID, fileCount = 0)
    val subContent = createFolderContent(folderID(1), fileCount = 2, folderCount = 3, folderIdx = 3)
    val subSub1Content = createFolderContent(folderID(4), folderIdx = 6, fileIdx = 3, fileCount = 2)
    val subSub2Content = createFolderContent(folderID(5), folderIdx = 8, fileIdx = 8, fileCount = 1)
    val subSubSub1Content = createFolderContent(folderID(6), folderCount = 0, fileCount = 3, fileIdx = 10)
    val subSubSub2Content = createFolderContent(folderID(7), folderCount = 0, fileCount = 2, fileIdx = 20)
    val components1 = Seq(folderName(1), folderName(4), folderName(6), fileName(10))
    val components2 = Seq(folderName(1), folderName(4), folderName(7), fileName(20))
    val components3 = Seq(folderName(1), folderName(4), folderName(7), "nonExistingFile.txt")
    val components4 = Seq(folderName(1), folderName(5), folderName(88), folderName(99))
    val helper = new ResolverTestHelper
    helper.prepareRootID()
      .prepareFolderContent(rootContent)
      .prepareFolderContent(subContent)
      .prepareFolderContent(subSub1Content)
      .prepareFolderContent(subSub2Content)
      .prepareFolderContent(subSubSub1Content)
      .prepareFolderContent(subSubSub2Content)

    val futRes1 = helper.resolve(components1)
    val futRes2 = helper.resolve(components2)
    val futRes3 = helper.resolve(components3)
    val futRes4 = helper.resolve(components4)
    futureResult(futRes1) should be(fileID(10))
    futureResult(futRes2) should be(fileID(20))
    val ex1 = expectFailedFuture[IllegalArgumentException](futRes3)
    val ex2 = expectFailedFuture[IllegalArgumentException](futRes4)
    ex1.getMessage should include(components3.last)
    components4.drop(2) foreach (comp => ex2.getMessage should include(comp))
    helper.verifyFolderRequestedOnce(rootContent.folderID)
      .verifyFolderRequestedOnce(subContent.folderID)
      .verifyFolderRequestedOnce(subSub1Content.folderID)
  }

  it should "correctly encode and decode path components" in {
    val FolderIO = "Input / Output"
    val FolderIOID = "id_IO"
    val FolderInput = "Sub: Input"
    val FolderInputID = "id_input"
    val folderIOMock = initMock(mock[FolderType], FolderIOID, encryptName(FolderIO))
    val folderInputMock = initMock(mock[FolderType], FolderInputID, encryptName(FolderInput))
    val rootContentOrg = createFolderContent(RootID, fileCount = 2, folderCount = 3)
    val rootContent = rootContentOrg.copy(folders = rootContentOrg.folders + (FolderIOID -> folderIOMock))
    val subContentOrg = createFolderContent(FolderIOID, fileIdx = 3, fileCount = 1, folderIdx = 4, folderCount = 1)
    val subContent = subContentOrg.copy(folders = subContentOrg.folders + (FolderInputID -> folderInputMock))
    val subSubContent = createFolderContent(FolderInputID, fileIdx = 10, fileCount = 1, folderCount = 0)
    val components = Seq(FolderIO, FolderInput, fileName(10))
    val helper = new ResolverTestHelper

    helper.prepareRootID()
      .prepareFolderContent(rootContent)
      .prepareFolderContent(subContent)
      .prepareFolderContent(subSubContent)
      .resolveAndExpect(components, fileID(10))
  }

  it should "handle a folder that is larger than the cache size" in {
    val content = createFolderContent(RootID, folderCount = CacheSize, fileCount = 2 * CacheSize)
    val components1 = Seq(fileName(1))
    val components2 = Seq(fileName(2 * CacheSize))
    val helper = new ResolverTestHelper

    helper.prepareRootID()
      .prepareFolderContent(content)
      .resolveAndExpect(components1, fileID(1))
      .resolveAndExpect(components2, fileID(2 * CacheSize))
  }

  it should "stop the resolver actor in its close() function in initialization phase" in {
    val resolverActor =
      testKit.spawn(CachePathComponentsResolver.pathResolverActor[String, FileType, FolderType](CacheSize, 0))
    val helper = new ResolverWithActorTestHelper(resolverActor)

    helper.closeResolver()
    expectTerminated(resolverActor)
  }

  it should "stop the resolver actor in its close() function in request processing phase" in {
    val resolverActor =
      testKit.spawn(CachePathComponentsResolver.pathResolverActor[String, FileType, FolderType](CacheSize, 0))
    val helper = new ResolverWithActorTestHelper(resolverActor)

    helper.prepareRootID()
      .prepareFolderContent(createFolderContent(RootID))
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
      .closeResolver()
    expectTerminated(resolverActor)
  }

  it should "support a chunk size for decrypt operations" in {
    val resolverActor =
      testKit.spawn(CachePathComponentsResolver.pathResolverActor[String, FileType, FolderType](CacheSize,
        CacheSize / 2))
    val helper = new ResolverWithActorTestHelper(resolverActor)

    helper.prepareRootID()
      .prepareFolderContent(createFolderContent(RootID))
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
  }

  it should "handle a chunk size that is larger than the cache size" in {
    val resolverActor =
      testKit.spawn(CachePathComponentsResolver.pathResolverActor[String, FileType, FolderType](CacheSize,
        CacheSize * 2))
    val helper = new ResolverWithActorTestHelper(resolverActor)

    helper.prepareRootID()
      .prepareFolderContent(createFolderContent(RootID, fileCount = 3 * CacheSize))
      .resolveAndExpect(Seq(fileName(1)), fileID(1))
  }

  /**
   * A base test helper class managing a resolver to test and its dependencies.
   */
  private abstract class ResolverTestHelperBase(factory: ResolverFactory) {
    /** The file system mock. */
    private val fileSystem = createFileSystem()

    /**
     * An actor for sending HTTP request. This actor is passed to the file
     * system, but it won't be actually invoked; therefore, the base URL is
     * irrelevant.
     */
    private val httpActor = testKit.spawn(HttpRequestSender("https://not-used.example.org"))

    /** The resolver to be tested. */
    private val resolver = factory()

    /**
     * Prepares the mock for the ''FileSystem'' to return an operation that
     * yields the default root ID.
     *
     * @param futID the ''Future'' with the ID to return
     * @return this test helper
     */
    def prepareRootID(futID: Future[String] = Future.successful(RootID)): ResolverTestHelperBase = {
      when(fileSystem.rootID).thenReturn(stubFutureOperation(futID))
      this
    }

    /**
     * Prepares the mock for the ''FileSystem'' to return an operation that
     * yields the given content object as an answer to a folder content
     * request.
     *
     * @param content the content to be returned
     * @return this test helper
     */
    def prepareFolderContent(content: Model.FolderContent[String, FileType, FolderType]): ResolverTestHelperBase = {
      when(fileSystem.folderContent(content.folderID)).thenReturn(stubOperation(content))
      this
    }

    /**
     * Prepares the mock for the ''FileSystem'' to return an operation with the
     * given result ''Future'' as an answer to a folder content request.
     *
     * @param folderID   the ID of the folder
     * @param futContent the result ''Future''
     * @return this test helper
     */
    def prepareFolderContentFuture(folderID: String,
                                   futContent: Future[Model.FolderContent[String, FileType, FolderType]]):
    ResolverTestHelperBase = {
      when(fileSystem.folderContent(folderID)).thenReturn(stubFutureOperation(futContent))
      this
    }

    /**
     * Invokes the test resolver to resolve the given path components.
     *
     * @param components the sequence with components to resolve
     * @return the result from the resolver
     */
    def resolve(components: Seq[String]): Future[String] =
      resolver.resolve(components, fileSystem, DefaultCryptConfig).run(httpActor)

    /**
     * Invokes the test resolver with the given path components, expects a
     * success result, and checks whether the expected ID is returned.
     *
     * @param components the sequence with components to resolve
     * @param expID      the expected resulting ID
     * @return this test helper
     */
    def resolveAndExpect(components: Seq[String], expID: String): ResolverTestHelperBase = {
      val id = futureResult(resolve(components))
      id should be(expID)
      this
    }

    /**
     * Verifies that the given folder was requested exactly once from the file
     * system mock.
     *
     * @param folderID the folder ID
     * @return this test helper
     */
    def verifyFolderRequestedOnce(folderID: String): ResolverTestHelperBase = {
      verify(fileSystem).folderContent(folderID)
      this
    }

    /**
     * Invokes the ''close()'' function on the test resolver instance.
     *
     * @return this test helper
     */
    def closeResolver(): ResolverTestHelperBase = {
      resolver.close()
      this
    }

    /**
     * Creates the mock file system.
     *
     * @return the mock for the file system
     */
    private def createFileSystem(): FileSystem[String, FileType, FolderType,
      Model.FolderContent[String, FileType, FolderType]] = {
      mock[FileSystem[String, FileType, FolderType, Model.FolderContent[String, FileType, FolderType]]]
    }
  }

  /**
   * A test helper class for testing a resolver instance created via a spawner.
   */
  private class ResolverTestHelper extends ResolverTestHelperBase(createResolverFromSpawner)

  /**
   * A test helper class for testing a resolver instance created via an
   * existing actor.
   *
   * @param actor the actor to be used
   */
  private class ResolverWithActorTestHelper(actor: ActorRef[PathLookupCommand[String, FileType, FolderType]])
    extends ResolverTestHelperBase(createResolverFromActor(actor))
}
