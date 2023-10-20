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

import org.slf4j.LoggerFactory

/**
 * An internal helper object that generates the requests to patch or retrieve
 * the properties of elements.
 *
 * Files and folders on a WebDav server can be assigned arbitrary properties.
 * To set these properties, special requests using the `PROPPATCH` method need
 * to be sent. Also, when retrieving the content of folders via `PROPFIND`
 * requests, the attributes to add to the result can be specified. The request
 * entities are rather complex XML documents listing the properties affected;
 * the fact that there can be multiple namespaces involved does not make them
 * easier.
 *
 * The module offers a function to generate such requests based on the
 * attributes the user has set for a file or folder.
 */
private object PropRequestGenerator {
  /**
   * A set with the standard attributes that are included in a PROPFIND request
   * per default unless the user explicitly overrides them.
   */
  final val StandardAttributes = Set(DavParser.AttrName, DavParser.AttrSize, DavParser.AttrCreatedAt,
    DavParser.AttrModifiedAt, DavParser.AttrSize, DavParser.AttrResourceType)

  /** Type alias for a mapping from a namespace prefix to the full name. */
  private type NamespaceMapping = Map[String, String]

  /** A prefix for generated namespace prefixes. */
  private val NamespacePrefix = "ns"

  /**
   * A string with the elements representing the standard WebDav attributes to
   * be retrieved by every PROPFIND request.
   */
  private val StandardElements = StandardAttributes.map(attr => s"<D:${attr.key}/>").mkString("")

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Generates the XML body of a request to patch the properties of an element.
   * If no properties need to be modified, result is an empty ''Option''; then
   * the request can be skipped.
   *
   * @param attributes        the attributes to modify
   * @param desc              the optional element description
   * @param optDescriptionKey the optional attribute for the description; if
   *                          undefined, no description is stored
   * @return an ''Option'' with the XML content of the request
   */
  def generatePropPatch(attributes: DavModel.Attributes, desc: Option[String],
                        optDescriptionKey: Option[DavModel.AttributeKey]): Option[String] = {
    if (desc != null && optDescriptionKey.isEmpty) {
      log.warn("No description attribute defined. Ignoring element description.")
    }

    val optDesc = for {
      descKey <- optDescriptionKey
      descValue <- desc
    } yield (descKey, descValue)
    val setCommands = List(attributes.values.toList, optDesc.toList).flatten

    if (setCommands.isEmpty && attributes.keysToDelete.isEmpty) None
    else {
      val namespaces = generateNamespaceMapping(setCommands.map(_._1) ++ attributes.keysToDelete)
      val namespaceHeaders = generateNamespacesHeader(namespaces)
      val setXml = generateSetElements(setCommands, namespaces)
      val removeXml = generateRemoveElements(attributes.keysToDelete, namespaces)
      Some(
        s"""<?xml version="1.0" encoding="utf-8" ?>
           |<D:propertyupdate xmlns:D="DAV:"$namespaceHeaders>
           |$setXml
           |$removeXml
           |</D:propertyupdate>
           |""".stripMargin)
    }
  }

  /**
   * Creates the XML body of a request for retrieving the content of a folder.
   * Here the attributes of elements that should be retrieved are specified. If
   * no attributes are defined, result is ''None'', indicating that the default
   * properties returned by the server should be used. Otherwise, at least the
   * standard attributes are requested.
   *
   * @param attributes        additional attributes to retrieve
   * @param optDescriptionKey the optional attribute for the description
   * @return an ''Option'' with the XML content of the request
   */
  def generatePropFind(attributes: Iterable[DavModel.AttributeKey],
                       optDescriptionKey: Option[DavModel.AttributeKey]): Option[String] = {
    val additionalAttributes = optDescriptionKey.fold(attributes)(a => attributes.toSet + a)
      .filterNot(StandardAttributes)

    if (additionalAttributes.isEmpty && optDescriptionKey.isEmpty && attributes.isEmpty) None
    else {
      val nsMapping = generateNamespaceMapping(additionalAttributes)
      val elements = additionalAttributes.map(elem => s"<${elementName(elem, nsMapping)}/>").mkString("")
      val namespaceHeaders = generateNamespacesHeader(nsMapping)
      Some(
        s"""<?xml version="1.0" encoding="utf-8" ?>
           |<D:propfind xmlns:D="DAV:">
           |<D:prop$namespaceHeaders>
           |$StandardElements$elements
           |</D:prop>
           |</D:propfind>
           |""".stripMargin)
    }
  }

  /**
   * Returns a map with prefixes and namespace URIs based on the passed in
   * attribute keys. The keys of the map are the unique namespace URIs, the
   * values are the prefixes to reference these namespaces.
   *
   * @param keys the attribute keys
   * @return the namespace mapping
   */
  private def generateNamespaceMapping(keys: Iterable[DavModel.AttributeKey]): NamespaceMapping = {
    val namespaces = keys.map(_.namespace).toSet
    namespaces.zipWithIndex.map(t => (t._1, NamespacePrefix + t._2)).toMap
  }

  /**
   * Returns a string with the ''xmlns'' attributes corresponding to the
   * namespaces in the given mapping.
   *
   * @param namespaces the namespace mapping
   * @return the namespace attributes for this mapping
   */
  private def generateNamespacesHeader(namespaces: NamespaceMapping): String =
    namespaces.map(e => s"""xmlns:${e._2}="${e._1}"""")
      .mkString(" ", " ", "")

  /**
   * Generates the part of the patch request that deals with setting attribute
   * values.
   *
   * @param setCommands the list with attributes to set
   * @param namespaces  the namespace mapping
   * @return the XML fragment to set attributes
   */
  private def generateSetElements(setCommands: List[(DavModel.AttributeKey, String)],
                                  namespaces: NamespaceMapping): String =
    setCommands.map { e =>
      s"""<D:set>
         |  <D:prop>
         |    ${generateSetElement(e._1, e._2, namespaces)}
         |  </D:prop>
         |</D:set>""".stripMargin
    }.mkString("\n")

  /**
   * Generates the XML to set the value of a property.
   *
   * @param key        the attribute key
   * @param value      the value for this attribute
   * @param namespaces the namespace mapping
   * @return the XML fragment to set this attribute
   */
  private def generateSetElement(key: DavModel.AttributeKey, value: String,
                                 namespaces: NamespaceMapping): String = {
    val elem = elementName(key, namespaces)
    s"<$elem>${xml.Utility.escape(value)}</$elem>"
  }

  /**
   * Generates the part of the patch request that deals with removing
   * attributes.
   *
   * @param keys       a sequence with the keys to remove
   * @param namespaces the namespace mapping
   * @return the XML fragment to remove attributes
   */
  private def generateRemoveElements(keys: Iterable[DavModel.AttributeKey], namespaces: NamespaceMapping): String =
    keys.map { key =>
      s"""<D:remove>
         |  <D:prop><${elementName(key, namespaces)}/></D:prop>
         |</D:remove>""".stripMargin
    }.mkString("\n")

  /**
   * Generates the fully-qualified name of the element with the given key using
   * the specified mapping of namespaces.
   *
   * @param key        the element key
   * @param namespaces the namespace mapping
   * @return the fully-qualified element name
   */
  private def elementName(key: DavModel.AttributeKey, namespaces: NamespaceMapping): String =
    s"${namespaces(key.namespace)}:${key.key}"
}
