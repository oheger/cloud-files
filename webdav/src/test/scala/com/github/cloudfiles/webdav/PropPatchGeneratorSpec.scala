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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.xml.{Elem, Node, NodeSeq, XML}

object PropPatchGeneratorSpec {
  /** The namespace for WebDav elements. */
  private val NS_DAV = "DAV:"

  /** Key for the test description attribute. */
  private final val DescKey = DavModel.AttributeKey("urn:test.org:", "testDesc")

  /**
   * Checks whether the given node belongs to the DAV namespace.
   *
   * @param node the node to check
   * @return a flag whether this is a DAV node
   */
  private def isDav(node: Node): Boolean =
    node.namespace == NS_DAV

  /**
   * A filter function to find XML element nodes.
   *
   * @param node the node to check
   * @return a flag whether this is an element node
   */
  private def isElement(node: Node): Boolean =
    node match {
      case _: Elem => true
      case _ => false
    }
}

/**
 * Test class for ''PropPatchGenerator''.
 */
class PropPatchGeneratorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers {

  import PropPatchGeneratorSpec._

  /**
   * Obtains child elements with the given name from the parent node
   * specified. Checks that all child nodes belong to the DAV namespace.
   *
   * @param parent the parent node
   * @param child  the name of the child nodes
   * @return the node sequence with the child nodes found
   */
  def davElem(parent: Node, child: String): NodeSeq = {
    val seq = parent \ child
    seq forall isDav shouldBe true
    seq
  }

  /**
   * Finds an element node in the children of the given node. Checks whether
   * this is the only child element.
   *
   * @param nodeSeq the node sequence to check
   * @return the single child element node
   */
  private def singleChildElement(nodeSeq: NodeSeq): Node = {
    val childElements = nodeSeq.head.child filter isElement
    childElements should have size 1
    childElements.head
  }

  /**
   * Extracts the property set commands from the given element.
   *
   * @param elem the property update root element
   * @return a map with information about the property set commands
   */
  private def parseSetCommands(elem: Elem): Map[DavModel.AttributeKey, String] = {
    val sets = elem \ "set"
    sets.foldLeft(Map.empty[DavModel.AttributeKey, String]) { (map, node) =>
      isDav(node) shouldBe true
      val propNode = singleChildElement(davElem(node, "prop"))
      map + (DavModel.AttributeKey(propNode.namespace, propNode.label) -> propNode.text)
    }
  }

  /**
   * Extracts the remove property commands from the given element.
   *
   * @param elem the property update root element
   * @return a list with information about the properties to remove
   */
  private def parseRemoveCommands(elem: Elem): Seq[DavModel.AttributeKey] = {
    val removes = elem \ "remove"
    removes map { node =>
      isDav(node) shouldBe true
      val propNode = singleChildElement(davElem(node, "prop"))
      propNode.text should be("")
      DavModel.AttributeKey(propNode.namespace, propNode.label)
    }
  }

  /**
   * Parses the XML patch request to an element.
   *
   * @param optRequest the optional request
   * @return the parsed element
   */
  private def parsePatchRequest(optRequest: Option[String]): Elem = {
    optRequest match {
      case Some(value) =>
        val stream = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))
        val elem = XML.load(stream)
        isDav(elem) shouldBe true
        elem.label should be("propertyupdate")
        elem

      case None => fail("No request was generated.")
    }
  }

  "PropPatchGenerator" should "return no request if there are no attributes" in {
    val attributes = DavModel.Attributes(Map.empty)
    val optRequest = PropPatchGenerator.generatePropPatch(attributes, None, Some(DescKey))

    optRequest should be(None)
  }

  it should "create a request with a description attribute" in {
    val Desc = "some description"
    val attributes = DavModel.Attributes(Map.empty)
    val request = PropPatchGenerator.generatePropPatch(attributes, Some(Desc), Some(DescKey))

    val setCommands = parseSetCommands(parsePatchRequest(request))
    setCommands should have size 1
    setCommands(DescKey) should be(Desc)
  }

  it should "ignore the description if no attribute is defined" in {
    val attributes = DavModel.Attributes(Map.empty)
    val optRequest = PropPatchGenerator.generatePropPatch(attributes, Some("some desc"), None)

    optRequest should be(None)
  }

  it should "create a request to set additional attributes" in {
    val keyAdd1 = DavModel.AttributeKey(DescKey.namespace, "foo")
    val keyAdd2 = DavModel.AttributeKey("urn:other:", "bar")
    val Desc = "<cool> desc"
    val attributes = DavModel.Attributes(Map(keyAdd1 -> "<foo> value", keyAdd2 -> "<bar> value"))
    val request = PropPatchGenerator.generatePropPatch(attributes, Some(Desc), Some(DescKey))

    val root = parsePatchRequest(request)
    val setCommands = parseSetCommands(root)
    setCommands should have size 3
    setCommands(DescKey) should be(Desc)
    setCommands(keyAdd1) should be("<foo> value")
    setCommands(keyAdd2) should be("<bar> value")
    parseRemoveCommands(root) should have size 0
  }

  it should "create a request to remove attributes" in {
    val keyDel1 = DavModel.AttributeKey(DescKey.namespace, "foo")
    val keyDel2 = DavModel.AttributeKey("urn:remove:", "bar")
    val attributes = DavModel.Attributes(Map.empty, List(keyDel1, DescKey, keyDel2))
    val request = PropPatchGenerator.generatePropPatch(attributes, null, None)

    val root = parsePatchRequest(request)
    parseSetCommands(root) should have size 0
    val removeCommands = parseRemoveCommands(root)
    removeCommands should contain only(keyDel1, keyDel2, DescKey)
  }

  it should "create a request with both set and remove elements" in {
    val keyDel = DavModel.AttributeKey(DescKey.namespace, "remove")
    val Desc = "the new description"
    val attributes = DavModel.Attributes(Map.empty, List(keyDel))
    val request = PropPatchGenerator.generatePropPatch(attributes, Some(Desc), Some(DescKey))

    val root = parsePatchRequest(request)
    val setCommands = parseSetCommands(root)
    setCommands should have size 1
    setCommands(DescKey) should be(Desc)
    val removeCommands = parseRemoveCommands(root)
    removeCommands should have size 1
    removeCommands should contain only keyDel
  }
}
