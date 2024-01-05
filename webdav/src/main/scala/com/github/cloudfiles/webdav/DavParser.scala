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

package com.github.cloudfiles.webdav

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.UriEncodingHelper
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCode, Uri}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery
import javax.xml.parsers.SAXParserFactory
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object DavParser {
  /**
   * Constant for a date value that is set by the parser if the date in a
   * server response cannot be parsed.
   */
  final val UndefinedDate = Instant.EPOCH

  /**
   * Constant for an undefined file size. This constant is used if the file
   * size property cannot be parsed.
   */
  final val UndefinedSize = -1L

  /** The namespace URI for elements belonging to the core DAV namespace. */
  final val NS_DAV = "DAV:"

  /** Standard attribute for the href element. */
  final val AttrHref = davAttribute("href")

  /** Standard attribute for the creation date. */
  final val AttrCreatedAt = davAttribute("creationdate")

  /** Standard attribute for the last modification date. */
  final val AttrModifiedAt = davAttribute("getlastmodified")

  /** Standard attribute for the (file) size. */
  final val AttrSize = davAttribute("getcontentlength")

  /** Standard attribute for the name attribute. */
  final val AttrName = davAttribute("displayname")

  /** Standard attribute for the resource type (collection or element). */
  final val AttrResourceType = davAttribute("resourcetype")

  /**
   * An artificial attribute that is used to indicate whether the current
   * element is a collection.
   */
  final val AttrCollection = davAttribute("collection")

  /** Name of the XML response element. */
  private val ElemResponse = "response"

  /** Name of the XML href element. */
  private val ElemHref = "href"

  /** Name of the XML propstat element. */
  private val ElemPropStat = "propstat"

  /** Name of the XML is collection element. */
  private val ElemCollection = "collection"

  /** Name of the XML status element. */
  private val ElemStatus = "status"

  /**
   * A set with element attributes that are directly accessible from properties
   * of a folder or file. These are filtered out from the object with
   * additional attributes.
   */
  private val StandardAttributes = Set(AttrCreatedAt, AttrModifiedAt, AttrName, AttrSize)

  /**
   * A regular expression to extract the status code from an XML status
   * element.
   */
  private val RegStatus = """.*\s(\d+)\s.*""".r

  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[DavParser])

  /**
   * Parses a date in string form to a corresponding ''Instant''. The date is
   * expected to be in the format defined by RFC 1123, but this function tries
   * to be more tolerant and accepts also ISO timestamps. If parsing of the
   * date fails, result is the ''DateUndefined'' constant.
   *
   * @param strDate the date as string
   * @return the resulting ''Instant''
   */
  private def parseTimeAttribute(strDate: String): Instant = Try {
    val query: TemporalQuery[Instant] = Instant.from _
    DateTimeFormatter.RFC_1123_DATE_TIME.parse(strDate, query)
  } orElse Try(Instant.parse(strDate)) getOrElse UndefinedDate

  /**
   * Parses the given XML string using the provided [[ResultHandler]]. Returns
   * the result produced by the handler.
   *
   * @param xml     the XML to be parsed as string
   * @param handler the handler
   * @tparam A the result type of the handler
   * @return the result from the handler
   */
  private def parseXml[A](xml: ByteString, handler: ResultHandler[A]): A = {
    val parserFactory = SAXParserFactory.newInstance()
    parserFactory.setNamespaceAware(true)
    val parser = parserFactory.newSAXParser()

    parser.parse(new ByteArrayInputStream(xml.toArray), handler)
    handler.result
  }

  /**
   * Parses the given XML string using a handler that is able to extract
   * elements. This works with folder results and single element results.
   *
   * @param xml               the XML response as a string
   * @param optDescriptionKey optional key of the description element
   * @return the [[ElementHandler]] with the extracted elements
   */
  private def parseWithElementHandler(xml: ByteString, optDescriptionKey: Option[DavModel.AttributeKey]):
  List[Model.Element[Uri]] =
    parseXml(xml, new ElementHandler(optDescriptionKey))

  /**
   * Extracts the content of a folder from the given XML string.
   *
   * @param xml               the XML response from the server
   * @param optDescriptionKey optional key of the description element
   * @return an object with the content of this folder
   */
  private def parseFolderContentXml(xml: ByteString, optDescriptionKey: Option[DavModel.AttributeKey]):
  Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder] = {
    val elements = parseWithElementHandler(xml, optDescriptionKey)

    val folderElements = elements.drop(1) // first element is the folder itself
      .foldLeft((Map.empty[Uri, DavModel.DavFolder], Map.empty[Uri, DavModel.DavFile])) { (maps, elem) =>
        (elem: @unchecked) match {
          case folder: DavModel.DavFolder =>
            (maps._1 + (folder.id -> folder), maps._2)
          case file: DavModel.DavFile =>
            (maps._1, maps._2 + (file.id -> file))
        }
      }
    Model.FolderContent(elements.head.id, folderElements._2, folderElements._1)
  }

  /**
   * Extracts a single element object from the given XML string. This is
   * similar to parsing the content of a folder, but only the first element is
   * extracted.
   *
   * @param xml               the XML response from the server
   * @param optDescriptionKey optional key of the description element
   * @return the model object for the extracted element
   */
  private def parseElementXml(xml: ByteString, optDescriptionKey: Option[DavModel.AttributeKey]):
  Try[Model.Element[Uri]] =
    Try(parseWithElementHandler(xml, optDescriptionKey).head)

  /**
   * Tries to parse the given string as an HTTP status code. Result is a
   * ''Failure'' if the status cannot be parsed.
   *
   * @param status the status string
   * @return a ''Try'' with the extracted HTTP status
   */
  private def parseStatusString(status: String): Try[StatusCode] =
    status match {
      case RegStatus(code) =>
        Try(StatusCode.int2StatusCode(code.toInt))
      case t =>
        Failure(new IllegalStateException(s"Invalid HTTP status in multi-status response: '$t'"))
    }

  /**
   * Reads the source from the response of a folder request completely and
   * returns the resulting ''ByteString''.
   *
   * @param source the source to be read
   * @param system the actor system
   * @return a future with the ''ByteString'' built from the response
   */
  private def readSource(source: Source[ByteString, Any])
                        (implicit system: ActorSystem[_]): Future[ByteString] = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink)
  }

  private def createElement(attributes: Map[DavModel.AttributeKey, String],
                            optDescriptionKey: Option[DavModel.AttributeKey]): Try[Model.Element[Uri]] =
    Try {
      val elemUri = attributes(AttrHref)
      val name = attributes.getOrElse(AttrName, nameFromUri(elemUri))
      val createdAt = parseTimeAttribute(attributes.getOrElse(AttrCreatedAt, UndefinedDate.toString))
      val lastModified = parseTimeAttribute(attributes.getOrElse(AttrModifiedAt, UndefinedDate.toString))
      val desc = optDescriptionKey flatMap attributes.get
      val elemAttributes: DavModel.Attributes = constructElementAttributes(attributes, optDescriptionKey)
      val isCollection = attributes(AttrCollection).toBoolean

      if (isCollection)
        DavModel.DavFolder(withTrailingSlash(elemUri), name, desc, createdAt, lastModified, elemAttributes)
      else
        DavModel.DavFile(elemUri, name, desc, createdAt, lastModified, parseFileSize(attributes), elemAttributes)
    }

  /**
   * Returns the final ''Attributes'' instance for the current element based on
   * the map provided. The standard attributes are removed first.
   *
   * @param attributes        the map with all attributes of the element
   * @param optDescriptionKey optional key of the description element
   * @return the final ''Attributes'' object for this element
   */
  private def constructElementAttributes(attributes: Map[DavModel.AttributeKey, String],
                                         optDescriptionKey: Option[DavModel.AttributeKey]): DavModel.Attributes = {
    val stdAttributes = optDescriptionKey.map(StandardAttributes + _) getOrElse StandardAttributes
    DavModel.Attributes(attributes.filterNot(e => stdAttributes.contains(e._1)))
  }

  /**
   * Extracts an element name from its URI. The name is the last component of
   * this URI, even if it ends on a slash.
   *
   * @param uri the URI
   * @return the element name from this URI
   */
  private def nameFromUri(uri: Uri): String = {
    val name = UriEncodingHelper.decode(UriEncodingHelper.splitParent(uri.path.toString())._2)
    if (name.isEmpty) {
      throw new IllegalArgumentException(s"Cannot extract name from URI '$uri'.")
    }
    name
  }

  /**
   * Parses the attribute with the file size.
   *
   * @param attributes the attributes of the file
   * @return the file size
   */
  private def parseFileSize(attributes: Map[DavModel.AttributeKey, String]): Long = Try {
    attributes(AttrSize).toLong
  } getOrElse UndefinedSize

  /**
   * Removes new line and special characters from the given string. Also
   * handles the case that indention after a new line will add additional
   * whitespace; this is collapsed to a single space.
   *
   * @param s the string to be processed
   * @return the string with removed line breaks
   */
  private def removeLF(s: String): String =
    trimMultipleSpaces(s.map(c => if (c < ' ') ' ' else c)).trim

  /**
   * Replaces multiple space characters in a sequence in the given string by a
   * single one.
   *
   * @param s the string to be processed
   * @return the processed string
   */
  @tailrec private def trimMultipleSpaces(s: String): String = {
    val pos = s.indexOf("  ")
    if (pos < 0) s
    else {
      val s1 = s.substring(0, pos + 1)
      val s2 = s.substring(pos).dropWhile(_ == ' ')
      trimMultipleSpaces(s1 + s2)
    }
  }

  /**
   * Generates the key of an attribute belonging to the core DAV namespace.
   *
   * @param key the actual element key
   * @return the resulting attribute key (including the DAV namespace)
   */
  private def davAttribute(key: String): DavModel.AttributeKey =
    DavModel.AttributeKey(NS_DAV, key)

  /**
   * A base [[DefaultHandler]] implementation that is able to generate a result
   * from the parsed document. The trait already provides some basic
   * functionality for dealing with the text content of elements.
   *
   * @tparam A the type of the results produced by this handler
   */
  private trait ResultHandler[A] extends DefaultHandler {
    /** Temporarily stores the text content of the current XML element. */
    private val content = new StringBuilder

    /**
     * Returns the result of XML processing.
     *
     * @return the processing result
     */
    def result: A

    /**
     * @inheritdoc This implementation makes sure that the buffer for
     *             collecting the text content of the current element is
     *             cleared.
     */
    override def startElement(uri: String, localName: String, qName: String, attributes: Attributes): Unit = {
      content.clear()
    }

    override def characters(ch: Array[Char], start: Int, length: Int): Unit = {
      content.appendAll(ch, start, length)
    }

    /**
     * Returns the (trimmed) content of the current XML element.
     *
     * @return the trimmed element content
     */
    protected def currentElementContent: String = removeLF(content.toString())
  }

  /**
   * Handler implementation for processing events during XML SAX parsing of a
   * WebDav folder or file structure. After processing, the elements that were
   * found can be queried.
   *
   * @param optDescriptionKey optional key of the description element
   */
  private class ElementHandler(optDescriptionKey: Option[DavModel.AttributeKey])
    extends ResultHandler[List[Model.Element[Uri]]] {
    /** Stores the model elements extracted from the XML document. */
    private val foundElements = ListBuffer.empty[Model.Element[Uri]]

    /** Temporarily stores the properties of the current element. */
    private val elemProperties = mutable.Map.empty[DavModel.AttributeKey, String]

    /**
     * Stores the properties of the current ''propstat'' element. This is
     * needed for input consisting of multiple elements of this type.
     */
    private val propStatProperties = mutable.Map.empty[DavModel.AttributeKey, String]

    override def result: List[Model.Element[Uri]] = foundElements.toList

    override def startElement(uri: String, localName: String, qName: String, attributes: Attributes): Unit = {
      super.startElement(uri, localName, qName, attributes)

      localName match {
        case `ElemResponse` =>
          elemProperties.clear()
          elemProperties += AttrCollection -> false.toString
        case `ElemPropStat` =>
          propStatProperties.clear()
        case _ =>
      }
    }

    override def endElement(uri: String, localName: String, qName: String): Unit = {
      localName match {
        case `ElemResponse` =>
          if (elemProperties.size > 2) {
            createElement(elemProperties.toMap, optDescriptionKey) match {
              case Success(element) => foundElements += element
              case Failure(exception) =>
                log.error(s"Could not parse response for element $elemProperties.", exception)
            }
          }
        case `ElemCollection` =>
          propStatProperties += AttrCollection -> true.toString
        case `ElemStatus` =>
          val successStatus = parseStatusString(currentElementContent).map(_.isSuccess()).getOrElse(false)
          if (successStatus) {
            elemProperties ++= propStatProperties
          }
        case `ElemHref` =>
          elemProperties += AttrHref -> currentElementContent
        case _ =>
          val key = DavModel.AttributeKey(uri, localName)
          propStatProperties += key -> currentElementContent
      }
    }
  }

  /**
   * Handler implementation for checking the status codes in a response with
   * multiple status elements. For the first failed status, a corresponding
   * [[FailedResponseException]] is returned, or a different exception if the
   * status string could not be parsed. An empty option as result means that
   * all status values encountered were successful.
   */
  private class MultiStatusHandler extends ResultHandler[Option[Throwable]] {
    /** Stores an exception for a found failed status. */
    private var failedStatus: Option[Throwable] = None

    override def result: Option[Throwable] = failedStatus

    override def endElement(uri: String, localName: String, qName: String): Unit = {
      if (failedStatus.isEmpty) {
        if (localName == ElemStatus) {
          parseStatusString(currentElementContent) match {
            case Failure(exception) =>
              failedStatus = Some(exception)
            case Success(status) =>
              if (status.isFailure()) {
                failedStatus = Some(FailedResponseException(HttpResponse(status = status)))
              }
          }
        }
      }
    }
  }
}

/**
 * An internal class for parsing responses from a DAV server and converting the
 * XML payload to the object model supported by this module.
 *
 * The class offers a function to read the response stream from the server and
 * pass the result to the XML parser. Then information about the folders and
 * files is extracted.
 *
 * As WebDav does not support a standard property for an element description,
 * it is possible to specify an attribute key that should be mapped to the
 * description. If unspecified, the description of elements is always
 * '''null'''.
 *
 * @param optDescriptionKey optional key for the description attribute
 */
private class DavParser(optDescriptionKey: Option[DavModel.AttributeKey] = None) {

  import DavParser._

  /**
   * Parses a response from a WebDav server with the content of a folder and
   * returns a ''Future'' with the resulting object model.
   *
   * @param source the source with the response from the server
   * @param system the actor system
   * @return a ''Future'' with the result of the parse operation
   */
  def parseFolderContent(source: Source[ByteString, Any])(implicit system: ActorSystem[_]):
  Future[Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder]] = {
    implicit val ec: ExecutionContext = system.executionContext
    readSource(source)
      .map(bs => parseFolderContentXml(bs, optDescriptionKey))
  }

  /**
   * Parses a response from a WebDav server with the properties of a single
   * element. Depending on the properties, either a file or a folder model
   * object is returned.
   *
   * @param source the source with the response from the server
   * @param system the actor system
   * @return a ''Future'' with the element extracted from the response
   */
  def parseElement(source: Source[ByteString, Any])(implicit system: ActorSystem[_]): Future[Model.Element[Uri]] = {
    implicit val ec: ExecutionContext = system.executionContext
    for {
      xml <- readSource(source)
      elem <- Future.fromTry(parseElementXml(xml, optDescriptionKey))
    } yield elem
  }

  /**
   * Parses a response from a WebDav server that contains a multi-status
   * document. This function does not actually extract any data from the
   * document, but only checks whether all operation results encoded in it
   * indicate success. If this is the case, result is a successful ''Future'';
   * otherwise, the ''Future'' fails with a ''FailedResponseException'' with
   * the first non-success status code that was encountered.
   *
   * @param source the source with the response from the server
   * @param system the actor system
   * @return a ''Future'' indicating whether the response is successful
   */
  def parseMultiStatus(source: Source[ByteString, Any])(implicit system: ActorSystem[_]): Future[Unit] = {
    implicit val ec: ExecutionContext = system.executionContext

    readSource(source) map { xml =>
      val optFailure = parseXml(xml, new MultiStatusHandler)
      optFailure foreach {
        throw _
      }
    }
  }
}
