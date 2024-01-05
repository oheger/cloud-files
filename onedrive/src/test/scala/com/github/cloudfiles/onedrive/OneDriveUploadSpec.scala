/*
 * Copyright 2020-2024 The Developers Team.
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

import com.github.cloudfiles.core.{AsyncTestHelper, FileTestHelper}
import com.github.cloudfiles.onedrive.OneDriveUpload.UploadRequestSource
import com.github.cloudfiles.onedrive.OneDriveUpload.UploadStreamCoordinatorActor.{NextUploadChunk, UploadStreamCoordinationMessage}
import org.apache.pekko.Done
import org.apache.pekko.actor.DeadLetter
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.stage.AsyncCallback
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

object OneDriveUploadSpec {
  /** Test upload URI. */
  private val UploadUri = "http://upload.test.io/test"

  /** The length of the file to be uploaded. */
  private val FileSize = FileTestHelper.TestData.length

  /**
   * Creates a source with test data that uses the block size specified.
   *
   * @param blockSize the block size
   * @return the source with test data in this block size
   */
  private def testSource(blockSize: Int): Source[ByteString, Any] =
    Source(FileTestHelper.testBytes().grouped(blockSize).map(ByteString(_)).toList)

  /**
   * Creates a OneDrive test configuration with the given settings.
   *
   * @param chunkSize the upload chunk size
   * @param timeout   a timeout
   * @return the test configuration
   */
  private def createConfig(chunkSize: Int, timeout: Timeout = 3.seconds): OneDriveConfig =
    OneDriveConfig(driveID = "someDriveID", uploadChunkSize = chunkSize, timeout = timeout)

  /**
   * A test implementation of the upload flow that allow access to the
   * coordinator actor.
   *
   * @param config the configuration
   * @param ec     the execution context
   * @param system the actor system
   */
  private class UploadFlowTestImpl(config: OneDriveConfig)(implicit ec: ExecutionContext, system: ActorSystem[_])
    extends OneDriveUpload.UploadBytesToRequestFlow(config, UploadUri, FileSize) {
    /** A queue to query the coordinator actor ref. */
    private val actorQueue = new ArrayBlockingQueue[ActorRef[UploadStreamCoordinationMessage]](2)

    /**
     * Returns the coordinator actor ref.
     *
     * @return the coordinator actor ref
     */
    def coordinatorActor: ActorRef[UploadStreamCoordinationMessage] = {
      val coordinator = actorQueue.poll(3, TimeUnit.SECONDS)
      if (coordinator == null) throw new AssertionError("No coordinator actor reference!")
      coordinator
    }

    /**
     * @inheritdoc This implementation records the actor reference.
     */
    override private[onedrive] def createCoordinatorActor(callback: AsyncCallback[Unit]):
    ActorRef[UploadStreamCoordinationMessage] = {
      val actor = super.createCoordinatorActor(callback)
      actorQueue offer actor
      actor
    }
  }

}

/**
 * Test class for the OneDrive upload functionality. This class mainly tests
 * corner cases; for the main functionality, there is an integration test.
 */
class OneDriveUploadSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with AsyncTestHelper {

  import OneDriveUploadSpec._

  /**
   * Helper function to check whether the coordinator actor has been stopped
   * after stream processing.
   *
   * @param source    the source of the simulated upload stream
   * @param chunkSize the upload chunk size
   */
  private def checkStreamCoordinatorActorIsStopped(source: Source[ByteString, Any], chunkSize: Int): Unit = {
    val config = createConfig(chunkSize)
    implicit val ec: ExecutionContext = system.executionContext
    val flow = new UploadFlowTestImpl(config)
    val sink = Sink.foreach[HttpRequest] { request =>
      request.entity.dataBytes.runWith(Sink.ignore)
    }

    futureResult(source.via(flow).runWith(sink).fallbackTo(Future.successful(Done)))
    val probe = testKit.createDeadLetterProbe()
    val coordinator = flow.coordinatorActor
    coordinator ! NextUploadChunk(testKit.createTestProbe().ref)
    probe.expectMessageType[DeadLetter]
  }

  "UploadBytesToRequestFlow" should "stop the coordinator actor if there is a single chunk only" in {
    val source = Source.single(ByteString(FileTestHelper.testBytes()))
    checkStreamCoordinatorActorIsStopped(source, FileTestHelper.TestData.length)
  }

  it should "stop the coordinator actor if the block size fits the chunk size" in {
    checkStreamCoordinatorActorIsStopped(testSource(16), 64)
  }

  it should "stop the coordinator actor if the block size does not fit the chunk size" in {
    checkStreamCoordinatorActorIsStopped(testSource(13), 64)
  }

  it should "stop the coordinator actor if there is a failure upstream" in {
    val source = Source.failed[ByteString](new IllegalStateException("Stream failure"))
    checkStreamCoordinatorActorIsStopped(source, 256)
  }

  "UploadRequestSource" should "handle a failure when requesting data from the coordinator actor" in {
    implicit val ec: ExecutionContext = system.executionContext
    val config = createConfig(1024, timeout = 200.millis)
    val requestSource = new UploadRequestSource(config, testKit.createTestProbe[UploadStreamCoordinationMessage]().ref)
    val source = Source.fromGraph(requestSource)

    expectFailedFuture[TimeoutException](source.runWith(Sink.ignore))
  }
}
