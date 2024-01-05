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

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.onedrive.OneDriveJsonProtocol._
import com.github.cloudfiles.onedrive.OneDriveUpload.UploadStreamCoordinatorActor.{NextUploadChunk, UploadChunk, UploadStreamCoordinationMessage}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.stage._
import org.apache.pekko.util.{ByteString, Timeout}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * A module implementing functionality related to file uploads to a OneDrive
 * server.
 *
 * Uploads to OneDrive are pretty complicated because they require multiple
 * steps. (There is also a direct upload mechanism, but this is supported for
 * small files only; as the files to be processed can have an arbitrary size,
 * the complex mechanism is used here.)
 *
 * In a first step a so-called ''upload session'' has to be created. This is
 * done by sending a special HTTP request to the server. The response is a
 * JSON document that - among other information - contains an upload URL. With
 * the upload URL available, the file's content can be sent to this URL in
 * one or multiple requests. There is a size restriction for a single request
 * (of ~60 MB, but the chunk size can be configured in the OneDrive config);
 * so it may be necessary to split the file into multiple requests.
 *
 * This module provides a function that creates a source of HTTP requests to
 * upload the single chunks of a file. The source is populated from a stream
 * with the content of the file to be uploaded. This is pretty tricky because
 * multiple streams have to be coordinated. The stream with the file content
 * is mapped by a custom flow stage to a stream of HTTP requests. The entity
 * of each request is defined by a source that is fed by the main stream.
 * Unfortunately, there is no default operator for this use case available;
 * therefore, a custom flow stage and a custom source have been implemented
 * that use a special actor for their coordination.
 */
private object OneDriveUpload {
  private val NameCounter = new AtomicInteger

  /**
   * A source implementation that provides the data for a single request to
   * upload a chunk of a file.
   *
   * The stream with the file's content is split into multiple requests with
   * a configurable chunk size. For each request, an instance of this class
   * is created. The instance uses the stream coordinator provided to obtain
   * blocks of data.
   *
   * @param config            the OneDrive configuration
   * @param streamCoordinator the stream coordinator actor
   * @param ec                the execution context
   */
  class UploadRequestSource(config: OneDriveConfig, streamCoordinator: ActorRef[UploadStreamCoordinationMessage])
                           (implicit ec: ExecutionContext, system: ActorSystem[_])
    extends GraphStage[SourceShape[ByteString]] {
    val out: Outlet[ByteString] = Outlet("UploadRequestSource")

    /** Timeout for communication with the coordinator actor. */
    private implicit val timeout: Timeout = config.timeout

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            val callback = getAsyncCallback[Try[UploadChunk]](chunkAvailable)
            requestUploadChunk() onComplete callback.invoke
          }
        })

        /**
         * Asks the stream coordinator actor for the next block of data.
         *
         * @return a future with the response from the actor
         */
        private def requestUploadChunk(): Future[UploadChunk] =
          streamCoordinator.ask(ref => NextUploadChunk(ref))

        /**
         * Handles a response from the stream coordinator actor. If new data
         * is available, it is passed downstream. If the current chunk is
         * complete (indicated by an empty block of data), the source is
         * completed. Failures from upstream are also handled.
         *
         * @param triedChunk a ''Try'' with the next block of data
         */
        private def chunkAvailable(triedChunk: Try[UploadChunk]): Unit = {
          triedChunk match {
            case Success(chunk) =>
              if (chunk.data.nonEmpty) {
                push(out, chunk.data)
              } else {
                completeStage()
              }

            case Failure(exception) =>
              failStage(exception)
          }
        }
      }
  }

  /**
   * A custom flow stage implementation to split the stream with the content
   * of a file into multiple upload requests.
   *
   * The class receives blocks of data from upstream and passes them to the
   * stream coordinator actor. From there they can be queried by the source
   * that produces the content of upload requests.
   *
   * Each pull signal from downstream generates another HTTP request to upload
   * a chunk of the file affected. The requests have a special header to
   * indicate which part of the file is uploaded. The stream coordinator actor
   * keeps track when a chunk is complete, so that the next upload request can
   * be started.
   *
   * @param config    the OneDrive configuration
   * @param uploadUri the URI where to upload the data
   * @param fileSize  the size of the file to be uploaded
   * @param ec        the execution context
   * @param system    the actor system
   */
  class UploadBytesToRequestFlow(config: OneDriveConfig, uploadUri: Uri, fileSize: Long)
                                (implicit ec: ExecutionContext, system: ActorSystem[_])
    extends GraphStage[FlowShape[ByteString, HttpRequest]] {
    val in: Inlet[ByteString] = Inlet("UploadBytesToRequestFlow.in")
    val out: Outlet[HttpRequest] = Outlet("UploadBytesToRequestFlow.out")

    override def shape: FlowShape[ByteString, HttpRequest] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLogging {
        /**
         * The actor to coordinate between this flow stage and the sources
         * for the upload requests.
         */
        private var streamCoordinator: ActorRef[UploadStreamCoordinationMessage] = _

        /**
         * Keeps track of the number of bytes that have already been uploaded.
         * This is used to find out when stream processing is complete.
         */
        private var bytesUploaded = 0L

        /** Records that an upload finished signal has been received. */
        private var finished = false

        override def preStart(): Unit = {
          super.preStart()
          val callback = getAsyncCallback[Unit] { _ =>
            pollFromCoordinator()
          }
          streamCoordinator = createCoordinatorActor(callback)
        }

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            streamCoordinator ! UploadChunk(grab(in))
          }

          override def onUpstreamFinish(): Unit = {
            finished = true
          }

          override def onUpstreamFailure(ex: Throwable): Unit = {
            super.onUpstreamFailure(ex)
            stopStreamCoordinator()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (bytesUploaded >= fileSize) {
              complete(out)
              stopStreamCoordinator()
            } else {
              push(out, createNextRequest())
            }
          }
        })

        /**
         * Creates the request to upload a chunk of bytes for the current file.
         *
         * @return the upload request
         */
        private def createNextRequest(): HttpRequest = {
          val chunkEnd = math.min(bytesUploaded + config.uploadChunkSize, fileSize) - 1
          log.debug("Uploading chunk {}-{}/{} to {}.", bytesUploaded, chunkEnd, fileSize, uploadUri)
          val dataSource = new UploadRequestSource(config, streamCoordinator)
          val request = createUploadRequest(uploadUri, bytesUploaded, chunkEnd, fileSize,
            Source.fromGraph(dataSource))
          bytesUploaded += config.uploadChunkSize
          request
        }

        /**
         * Handler function for the callback invoked by the coordinator actor.
         * This function is called when the actor requests new data. If
         * upstream is already finished, this graph stage can now be
         * completed; otherwise, data is pulled from the in channel.
         */
        private def pollFromCoordinator(): Unit = {
          if (finished) {
            log.debug("Upload to {} completed; sent {} bytes.", uploadUri, bytesUploaded)
            complete(out)
          } else pull(in)
        }

        /**
         * Stops the stream coordinator actor by sending it an empty chunk
         * message. This is the signal for the actor to stop itself after all
         * pending messages have been delivered.
         */
        private def stopStreamCoordinator(): Unit = {
          streamCoordinator ! UploadStreamCoordinatorActor.EmptyChunk
        }
      }

    /**
     * Creates the actor that does the stream coordination.
     *
     * @param callback the callback for the actor
     * @return the stream coordinator actor
     */
    private[onedrive] def createCoordinatorActor(callback: AsyncCallback[Unit]):
    ActorRef[UploadStreamCoordinationMessage] =
      system.systemActorOf(UploadStreamCoordinatorActor(config.uploadChunkSize, fileSize, callback),
        "OneDriveUploadActor" + NameCounter.incrementAndGet())
  }

  /**
   * An actor implementation that coordinates the interactions between the
   * custom flow stage ([[UploadBytesToRequestFlow]]) and the custom source
   * ([[UploadRequestSource]]) implementations.
   *
   * This actor class passes data from the stream with the content of the
   * file to be uploaded to the sources representing the entities of upload
   * requests. It also makes sure that chunks of the correct size are
   * uploaded.
   *
   * The basic idea behind this actor is that data flow is controlled from
   * downstream to upstream. A source for the entity of an upload request
   * sends a [[NextUploadChunk]] message to this actor to query the next block
   * of data. This request is forwarded to the custom flow stage via an
   * asynchronous callback. As a reaction to this callback, the flow pulls its
   * upstream source and receives a block of data, which it passes to this
   * actor. The actor can then pass this block to the entity source.
   */
  object UploadStreamCoordinatorActor {

    /**
     * The base trait for the messages processed by this actor.
     */
    sealed trait UploadStreamCoordinationMessage

    /**
     * A message processed by [[UploadStreamCoordinatorActor]] that requests
     * the next block of data. The message is sent by [[UploadRequestSource]].
     *
     * @param replyTo the actor to send the response to
     */
    case class NextUploadChunk(replyTo: ActorRef[UploadChunk]) extends UploadStreamCoordinationMessage

    /**
     * A message that contains a block of data.
     *
     * The message is both received and sent by
     * [[UploadStreamCoordinatorActor]]. [[UploadBytesToRequestFlow]] sends
     * this message to pass data from the uploaded file to this actor. It is
     * then also sent as response of a [[NextUploadChunk]] message to
     * [[UploadRequestSource]]. Note that messages with an empty block of data
     * have a special meaning indicating the end of a chunk or the whole
     * stream.
     *
     * @param data the block of data
     */
    case class UploadChunk(data: ByteString) extends UploadStreamCoordinationMessage

    /**
     * Constant for an empty chunk of data. This is used to indicate the end of
     * the stream for a single upload request.
     */
    final val EmptyChunk: UploadChunk = UploadChunk(ByteString.empty)

    /**
     * Returns a ''Props'' object for creating a new instance of this actor
     * class.
     *
     * @param chunkSize the upload chunk size
     * @param fileSize  the size of the file to be uploaded
     * @param callback  a callback to request more data from
     *                  [[UploadBytesToRequestFlow]]
     * @return ''Props'' for creating a new instance
     */
    def apply(chunkSize: Int, fileSize: Long, callback: AsyncCallback[Unit]):
    Behavior[UploadStreamCoordinationMessage] =
      handle(chunkSize, fileSize, callback, List.empty, null, 0, 0, finished = false)

    /**
     * The actual message handling function that manages and updates the state
     * of this actor.
     *
     * @param chunkSize           the upload chunk size
     * @param fileSize            the size of the file to be uploaded
     * @param callback            a callback to request more data
     * @param pendingData         a list with data that can be queried from
     *                            downstream
     * @param client              the client of the latest data request
     * @param bytesUploaded       number of bytes that have been uploaded
     * @param bytesInCurrentChunk number of bytes in the current chunk
     * @param finished            indicates whether all data is processed
     * @return the next behavior function
     */
    private def handle(chunkSize: Int, fileSize: Long, callback: AsyncCallback[Unit], pendingData: List[UploadChunk],
                       client: ActorRef[UploadChunk], bytesUploaded: Long,
                       bytesInCurrentChunk: Int, finished: Boolean): Behavior[UploadStreamCoordinationMessage] =
      Behaviors.receivePartial {
        case (_, NextUploadChunk(replyTo)) =>
          pendingData match {
            case h :: t =>
              replyTo ! h
              if (finished && t.isEmpty) Behaviors.stopped
              else handle(chunkSize, fileSize, callback, t, client, bytesUploaded, bytesInCurrentChunk, finished)
            case _ =>
              callback.invoke(())
              handle(chunkSize, fileSize, callback, pendingData, replyTo, bytesUploaded, bytesInCurrentChunk, finished)
          }

        case (_, UploadChunk(data)) if data.isEmpty =>
          if (pendingData.isEmpty) Behaviors.stopped
          else handle(chunkSize, fileSize, callback, pendingData, client, bytesUploaded, bytesInCurrentChunk,
            finished = true)

        case (_, c@UploadChunk(data)) =>
          val nextBytesUploaded = bytesUploaded + data.length
          val (nextPendingData, nextBytesInCurrentChunk, chunk) = if (bytesInCurrentChunk + data.length > chunkSize) {
            val (last, next) = data.splitAt(chunkSize - bytesInCurrentChunk)
            (List(EmptyChunk, UploadChunk(next)), next.length, UploadChunk(last))
          } else {
            if (bytesUploaded >= fileSize || bytesInCurrentChunk == chunkSize)
              (List(EmptyChunk), 0, c)
            else (pendingData, bytesInCurrentChunk + data.length, c)
          }

          client ! chunk
          handle(chunkSize, fileSize, callback, nextPendingData, client, nextBytesUploaded,
            nextBytesInCurrentChunk, finished)
      }
  }

  /**
   * The class representing the ''Content-Range'' custom header.
   *
   * This header is required for upload requests to a OneDrive server. Large
   * files can be uploaded in multiple chunks, and the header describes the
   * current chunk.
   *
   * @param value the value of this header
   */
  class ContentRangeHeader(override val value: String) extends ModeledCustomHeader[ContentRangeHeader] {
    override def companion: ModeledCustomHeaderCompanion[ContentRangeHeader] = ContentRangeHeader

    override def renderInRequests(): Boolean = true

    override def renderInResponses(): Boolean = true
  }

  object ContentRangeHeader extends ModeledCustomHeaderCompanion[ContentRangeHeader] {
    override def name: String = "Content-Range"

    override def parse(value: String): Try[ContentRangeHeader] =
      Try(new ContentRangeHeader(value))

    /**
     * Returns a ''Content-Range'' header from the parameters of the current
     * upload chunk.
     *
     * @param from  the start byte of the current chunk
     * @param to    the end byte of the current chunk
     * @param total the total file size
     * @return the header with these parameters
     */
    def fromChunk(from: Long, to: Long, total: Long): ContentRangeHeader =
      apply(s"bytes $from-$to/$total")
  }

  /**
   * Executes the upload of a file to a given upload URI. If successful, a
   * future with the ID of the uploaded file is returned.
   *
   * @param config     the OneDrive configuration
   * @param fileSize   the size of the file to be uploaded
   * @param fileSource the source with the content of the file
   * @param uploadUri  the URI where to upload the file
   * @param ec         the execution context
   * @param system     the actor system
   * @return a future with the ID of the drive item affected
   */
  def upload(config: OneDriveConfig, fileSize: Long, fileSource: Source[ByteString, Any], uploadUri: Uri,
             httpSender: ActorRef[HttpRequestSender.HttpCommand])
            (implicit ec: ExecutionContext, system: ActorSystem[_], timeout: Timeout): Future[String] = {
    val requestSource = createUploadRequestsSource(config, fileSize, fileSource, uploadUri)
    val sink = Sink.last[UploadChunkResponse]
    requestSource.mapAsync(1) { req =>
      HttpRequestSender.sendRequestSuccess(httpSender, req)
    }.mapAsync(1) { result =>
      Unmarshal(result.response).to[UploadChunkResponse]
    }.runWith(sink)
      .flatMap { response =>
        response.id match {
          case Some(id) => Future.successful(id)
          case None => Future.failed(throw new IllegalStateException(s"No ID found in upload response to $uploadUri."))
        }
      }
  }

  /**
   * Generates a source that produces HTTP requests to upload the single chunks
   * of the file which is the target of this upload operation. If the file fits
   * into a single chunk, a simple source is created that yields only a single
   * request. Otherwise, the complex chunking logic has to be applied.
   *
   * @param config     the OneDrive configuration
   * @param fileSize   the size of the file to be uploaded
   * @param fileSource the source with the content of the file
   * @param uploadUri  the URI where to upload the file
   * @param ec         the execution context
   * @param system     the actor system
   * @return the ''Source'' with upload requests
   */
  private def createUploadRequestsSource(config: OneDriveConfig, fileSize: Long, fileSource: Source[ByteString, Any],
                                         uploadUri: Uri)
                                        (implicit ec: ExecutionContext, system: ActorSystem[_]):
  Source[HttpRequest, Any] =
    if (fileSize <= config.uploadChunkSize)
      Source.single(createUploadRequest(uploadUri, 0, fileSize - 1, fileSize, fileSource))
    else fileSource.via(new UploadBytesToRequestFlow(config, uploadUri, fileSize))

  /**
   * Creates a request to upload a specific chunk of data of a file.
   *
   * @param uploadUri  the upload URI
   * @param chunkStart the start position of the current chunk
   * @param chunkEnd   the end position of the current chunk
   * @param totalSize  the total file size
   * @param dataSource the source with the binary data of the chunk
   * @return the upload request for this chunk
   */
  private def createUploadRequest(uploadUri: Uri, chunkStart: Long, chunkEnd: Long, totalSize: Long,
                                  dataSource: Source[ByteString, Any]): HttpRequest =
    HttpRequest(method = HttpMethods.PUT, uri = uploadUri,
      headers = List(ContentRangeHeader.fromChunk(chunkStart, chunkEnd, totalSize)),
      entity = HttpEntity(ContentTypes.`application/octet-stream`, chunkEnd - chunkStart + 1, dataSource))
}
