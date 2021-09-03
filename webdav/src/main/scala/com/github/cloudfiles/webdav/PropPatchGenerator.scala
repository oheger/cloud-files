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

import org.slf4j.LoggerFactory

/**
 * An internal helper object that generates the requests to patch the
 * properties of elements.
 *
 * Files and folders on a WebDav server can be assigned arbitrary properties.
 * To set these properties, special requests using the `PROPPATCH` method need
 * to be sent. The request entities are rather complex XML documents listing
 * the properties to be modified; the fact that there can be multiple
 * namespaces involved does not make them easier.
 *
 * The module offers a function to generate such requests based on the
 * attributes the user has set for a file or folder.
 */
private object PropPatchGenerator {
  /** A prefix for generated namespace prefixes. */
  private val NamespacePrefix = "ns"

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
      val namespaceHeaders = namespaces.map(e => s"""xmlns:${e._2}="${e._1}"""")
        .mkString(" ", " ", "")
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
   * Returns a map with prefixes and namespace URIs based on the passed in
   * attribute keys. The keys of the map are the unique namespace URIs, the
   * values are the prefixes to reference these namespaces.
   *
   * @param keys the attribute keys
   * @return the namespace mapping
   */
  private def generateNamespaceMapping(keys: Iterable[DavModel.AttributeKey]): Map[String, String] = {
    val namespaces = keys.map(_.namespace).toSet
    namespaces.zipWithIndex.map(t => (t._1, NamespacePrefix + t._2)).toMap
  }

  /**
   * Generates the part of the patch request that deals with setting attribute
   * values.
   *
   * @param setCommands the list with attributes to set
   * @param namespaces  the namespace mapping
   * @return the XML fragment to set attributes
   */
  private def generateSetElements(setCommands: List[(DavModel.AttributeKey, String)],
                                  namespaces: Map[String, String]): String =
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
                                 namespaces: Map[String, String]): String = {
    val elem = s"${namespaces(key.namespace)}:${key.key}"
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
  private def generateRemoveElements(keys: Iterable[DavModel.AttributeKey], namespaces: Map[String, String]): String =
    keys.map { key =>
      s"""<D:remove>
         |  <D:prop><${namespaces(key.namespace)}:${key.key}/></D:prop>
         |</D:remove>""".stripMargin
    }.mkString("\n")
}
