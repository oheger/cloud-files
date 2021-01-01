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

package com.github.cloudfiles.webdav

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.cloudfiles.Model
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Node, NodeSeq, XML}

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
  private val NS_DAV = "DAV:"

  /** Name of the XML response element. */
  private val ElemResponse = "response"

  /** Name of the XML href element. */
  private val ElemHref = "href"

  /** Name of the XML propstat element. */
  private val ElemPropStat = "propstat"

  /** Name of the XML prop element. */
  private val ElemProp = "prop"

  /** Name of the XML resource type element. */
  private val ElemResourceType = "resourcetype"

  /** Name of the XML is collection element. */
  private val ElemCollection = "collection"

  /** Standard attribute for the creation date. */
  private val AttrCreatedAt = davAttribute("creationdate")

  /** Standard attribute for the last modification date. */
  private val AttrModifiedAt = davAttribute("getlastmodified")

  /** Standard attribute for the (file) size. */
  private val AttrSize = davAttribute("getcontentlength")

  /** Standard attribute for the name attribute. */
  private val AttrName = davAttribute("displayname")

  /**
   * A set with element attributes that are directly accessible from properties
   * of a folder or file. These are filtered out from the object with
   * additional attributes.
   */
  private val StandardAttributes = Set(AttrCreatedAt, AttrModifiedAt, AttrName, AttrSize)

  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[DavParser])

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
    val folderUri = Uri(elemText(responses.head, ElemHref))

    val folderElements = responses.drop(1) // first element is the folder itself
      .foldLeft((Map.empty[Uri, DavModel.DavFolder], Map.empty[Uri, DavModel.DavFile])) { (maps, node) =>
        (extractElement(node, optDescriptionKey): @unchecked) match {
          case Success(folder: DavModel.DavFolder) =>
            (maps._1 + (folder.id -> folder), maps._2)
          case Success(file: DavModel.DavFile) =>
            (maps._1, maps._2 + (file.id -> file))
          case Failure(exception) =>
            log.error("Could not parse response for element {}.", node, exception)
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
  private def parseResponses(xml: ByteString): NodeSeq = {
    val xmlStream = new ByteArrayInputStream(xml.toArray)
    val elem = XML.load(xmlStream)
    elem \ ElemResponse
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
      val propNode = (node \ ElemPropStat \ ElemProp).head
      val attributes = extractElementAttributes(propNode)

      val name = attributes.getOrElse(AttrName, elemUri.path.reverse.head.toString)
      val createdAt = parseTimeAttribute(attributes.getOrElse(AttrCreatedAt, UndefinedDate.toString))
      val lastModified = parseTimeAttribute(attributes.getOrElse(AttrModifiedAt, UndefinedDate.toString))
      val desc = (optDescriptionKey flatMap attributes.get).orNull
      val elemAttributes: DavModel.Attributes = constructElementAttributes(attributes, optDescriptionKey)
      if (isCollection(propNode))
        DavModel.DavFolder(elemUri, name, desc, createdAt, lastModified, elemAttributes)
      else
        DavModel.DavFile(elemUri, name, desc, createdAt, lastModified, parseFileSize(attributes), elemAttributes)
    }

  /**
   * Extracts all attributes of a folder element from the given XML node seq.
   *
   * @param propNode the nodes representing the element's properties
   * @return a map with the attributes extracted
   */
  private def extractElementAttributes(propNode: Node): Map[DavModel.AttributeKey, String] = {
    propNode.child.foldLeft(Map.empty[DavModel.AttributeKey, String]) { (attributes, node) =>
      val attrKey = DavModel.AttributeKey(node.namespace, node.label)
      val value = nodeText(node)
      attributes + (attrKey -> value)
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
   * @param propNode the top-level node for the current element
   * @return a flag whether this element is a collection
   */
  private def isCollection(propNode: NodeSeq): Boolean = {
    val elemCollection = propNode \ ElemResourceType \ ElemCollection
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
}
