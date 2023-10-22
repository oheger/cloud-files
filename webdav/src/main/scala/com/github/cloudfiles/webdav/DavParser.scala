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

package com.github.cloudfiles.webdav

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.UriEncodingHelper
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCode, Uri}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, NodeSeq, XML}

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

  /** Name of the XML response element. */
  private val ElemResponse = "response"

  /** Name of the XML href element. */
  private val ElemHref = "href"

  /** Name of the XML propstat element. */
  private val ElemPropStat = "propstat"

  /** Name of the XML prop element. */
  private val ElemProp = "prop"

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
  def parseTimeAttribute(strDate: String): Instant = Try {
    val query: TemporalQuery[Instant] = Instant.from _
    DateTimeFormatter.RFC_1123_DATE_TIME.parse(strDate, query)
  } orElse Try(Instant.parse(strDate)) getOrElse UndefinedDate

  /**
   * Extracts the content of a folder from the given XML string.
   *
   * @param xml               the XML response from the server
   * @param optDescriptionKey optional key of the description element
   * @return an object with the content of this folder
   */
  private def parseFolderContentXml(xml: ByteString, optDescriptionKey: Option[DavModel.AttributeKey]):
  Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder] = {
    val responses = parseResponses(xml)
    val folderUri = Uri(UriEncodingHelper withTrailingSeparator elemText(responses.head, ElemHref))

    val folderElements = responses.drop(1) // first element is the folder itself
      .foldLeft((Map.empty[Uri, DavModel.DavFolder], Map.empty[Uri, DavModel.DavFile])) { (maps, node) =>
        (extractElement(node, optDescriptionKey): @unchecked) match {
          case Success(folder: DavModel.DavFolder) =>
            (maps._1 + (folder.id -> folder), maps._2)
          case Success(file: DavModel.DavFile) =>
            (maps._1, maps._2 + (file.id -> file))
          case Failure(exception) =>
            log.error(s"Could not parse response for element $node.", exception)
            maps
        }
      }
    Model.FolderContent(folderUri, folderElements._2, folderElements._1)
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
    for {
      responses <- Try(parseResponses(xml))
      respElem <- Try(responses.head)
      elem <- extractElement(respElem, optDescriptionKey)
    } yield elem

  /**
   * Parses a multi-status response and extracts the single status values
   * contained therein.
   *
   * @param xml the XML string with the multi-status response
   * @param ec  the execution context
   * @return a ''Future'' with the extracted status codes
   */
  private def parseMultiStatusXml(xml: ByteString)(implicit ec: ExecutionContext): Future[Iterable[StatusCode]] =
    Future.sequence(parseStatusCodes(parseResponses(xml) \ ElemPropStat))

  /**
   * Parses the single status codes elements contained in a multi-status
   * response and returns a collection with the future results.
   *
   * @param propStats the sequence of ''propstat'' nodes to parse
   * @return a collection with the future parsed status codes
   */
  private def parseStatusCodes(propStats: NodeSeq): Iterable[Future[StatusCode]] =
    propStats.map { node => Future.fromTry(extractStatus(node)) }

  /**
   * Tries to extract the HTTP status code from the ''status'' child element of
   * the given node. Result is a ''Failure'' if the status cannot be parsed.
   *
   * @param node the node in question
   * @return a ''Try'' with the extracted HTTP status
   */
  private def extractStatus(node: Node): Try[StatusCode] =
    elemText(node, ElemStatus) match {
      case RegStatus(code) =>
        Try(StatusCode.int2StatusCode(code.toInt))
      case t =>
        Failure(new IllegalStateException(s"Invalid HTTP status in multi-status response: '$t'"))
    }

  /**
   * Checks whether all the status codes of a multi-status response are
   * successful. If so, a successful ''Future'' is returned; otherwise an
   * exception for the first failed response status is generated.
   *
   * @param codes the codes to be checked
   * @return a ''Future'' with the outcome of the check
   */
  private def checkMultiStatusCodes(codes: Iterable[StatusCode]): Future[Unit] =
    codes.find(_.isFailure()).map { code =>
      FailedResponseException(HttpResponse(status = code))
    }.fold(Future.successful(()))(Future.failed(_))

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

  /**
   * Parses the given byte string as an XML document and returns a ''NodeSeq''
   * with the response elements found in this document.
   *
   * @param xml the XML response from the server
   * @return a sequence with the response elements
   */
  private def parseResponses(xml: ByteString): NodeSeq =
    parseXml(xml) \ ElemResponse

  /**
   * Parses a ''ByteString'' to an XML element.
   *
   * @param xml the XML string
   * @return the resulting ''Elem''
   */
  private def parseXml(xml: ByteString): Elem = {
    val xmlStream = new ByteArrayInputStream(xml.toArray)
    XML.load(xmlStream)
  }

  /**
   * Extracts a model object from the given XML node. Depending on the node's
   * content either a folder or file is constructed.
   *
   * @param node              the XML node to process
   * @param optDescriptionKey optional key of the description element
   * @return the element extracted from the node
   */
  private def extractElement(node: Node, optDescriptionKey: Option[DavModel.AttributeKey]): Try[Model.Element[Uri]] =
    Try {
      val elemUri = Uri(elemText(node, ElemHref))
      val attributeNodes = collectPropStats(node)
      val attributes = attributeNodes.map(e => (e._1, nodeText(e._2)))

      val name = attributes.getOrElse(AttrName, nameFromUri(elemUri))
      val createdAt = parseTimeAttribute(attributes.getOrElse(AttrCreatedAt, UndefinedDate.toString))
      val lastModified = parseTimeAttribute(attributes.getOrElse(AttrModifiedAt, UndefinedDate.toString))
      val desc = optDescriptionKey flatMap attributes.get
      val elemAttributes: DavModel.Attributes = constructElementAttributes(attributes, optDescriptionKey)
      if (isCollection(attributeNodes))
        DavModel.DavFolder(withTrailingSlash(elemUri), name, desc, createdAt, lastModified, elemAttributes)
      else
        DavModel.DavFile(elemUri, name, desc, createdAt, lastModified, parseFileSize(attributes), elemAttributes)
    }

  /**
   * Processes all the ''propstat'' elements under the current node that could
   * be retrieved successfully and aggregates their properties.
   *
   * @param node the current node
   * @return a map with the property nodes of this node
   */
  private def collectPropStats(node: Node): Map[DavModel.AttributeKey, Node] =
    (node \ ElemPropStat).filter { propStat =>
      extractStatus(propStat) match {
        case Success(status) if status.isSuccess() => true
        case _ => false
      }
    }.foldLeft(Map.empty[DavModel.AttributeKey, Node]) { (attributes, stat) =>
      attributes ++ extractElementAttributes((stat \ ElemProp).head)
    }

  /**
   * Extracts all attributes of a folder element from the given XML node seq.
   *
   * @param propNode the nodes representing the element's properties
   * @return a map with the attributes extracted
   */
  private def extractElementAttributes(propNode: Node): Map[DavModel.AttributeKey, Node] = {
    propNode.child.foldLeft(Map.empty[DavModel.AttributeKey, Node]) { (attributes, node) =>
      val attrKey = DavModel.AttributeKey(node.namespace, node.label)
      attributes + (attrKey -> node)
    }
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
   * Extracts the text of a sub element of the given XML node. Handles line
   * breaks in the element.
   *
   * @param node     the node representing the parent element
   * @param elemName the name of the element to be obtained
   * @return the text of this element
   */
  private def elemText(node: NodeSeq, elemName: String): String =
    removeLF((node \ elemName).text)

  /**
   * Extracts the text of the given XML node. Handles line breaks in the
   * element text.
   *
   * @param node the node in question
   * @return the text of this node
   */
  private def nodeText(node: Node): String = removeLF(node.text)

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
   * Checks whether an element is a collection. In this case the element
   * represents a folder rather than a single file.
   *
   * @param attributeNodes the map with attribute nodes of this node
   * @return a flag whether this element is a collection
   */
  private def isCollection(attributeNodes: Map[DavModel.AttributeKey, Node]): Boolean =
    attributeNodes.get(AttrResourceType).exists { resTypeNode =>
      val elemCollection = resTypeNode \ ElemCollection
      elemCollection.nonEmpty
    }

  /**
   * Generates the key of an attribute belonging to the core DAV namespace.
   *
   * @param key the actual element key
   * @return the resulting attribute key (including the DAV namespace)
   */
  private def davAttribute(key: String): DavModel.AttributeKey =
    DavModel.AttributeKey(NS_DAV, key)
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
    for {
      xml <- readSource(source)
      codes <- parseMultiStatusXml(xml)
      check <- checkMultiStatusCodes(codes)
    } yield check
  }
}
