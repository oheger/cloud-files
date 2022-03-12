/*
 * Copyright 2020-2022 The Developers Team.
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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.xml.{Elem, Node, NodeSeq, XML}

object PropRequestGeneratorSpec {
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
 * Test class for ''PropRequestGenerator''.
 */
class PropRequestGeneratorSpec extends AnyFlatSpec with Matchers {

  import PropRequestGeneratorSpec._

  /**
   * Checks whether the given element has the DAV namespace and the expected
   * label.
   *
   * @param elem the element to check
   * @param name the expected name
   * @return the validated element
   */
  private def assertDavElement(elem: Elem, name: String): Elem = {
    isDav(elem) shouldBe true
    elem.label should be(name)
    elem
  }

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
  private def singleChildElement(nodeSeq: NodeSeq): Elem = {
    val childElements = nodeSeq.head.child.filter(isElement).map(_.asInstanceOf[Elem])
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
   * Parses an optional request to an XML element that can be further
   * inspected. Checks the root element.
   *
   * @param optRequest   the optional request
   * @param expRootLabel the expected label of the root element
   * @return the parsed element
   */
  private def parseRequest(optRequest: Option[String], expRootLabel: String): Elem =
    optRequest match {
      case Some(value) =>
        val stream = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))
        assertDavElement(XML.load(stream), expRootLabel)

      case None => fail("No request was generated.")
    }

  /**
   * Parses the XML patch request to an element.
   *
   * @param optRequest the optional request
   * @return the parsed element
   */
  private def parsePatchRequest(optRequest: Option[String]): Elem =
    parseRequest(optRequest, "propertyupdate")

  /**
   * Parses a PROPFIND request and extracts the properties that should be
   * retrieved.
   *
   * @param optRequest the optional request
   * @return a list with the properties contained in the request
   */
  private def parsePropFindRequest(optRequest: Option[String]): List[DavModel.AttributeKey] = {
    val root = parseRequest(optRequest, "propfind")
    val elemProp = assertDavElement(singleChildElement(root), "prop")
    elemProp.child.filter(_.isInstanceOf[Elem])
      .map(node => DavModel.AttributeKey(node.namespace, node.label)).toList
  }

  /**
   * Checks whether the given list contains all the standard attributes.
   * Returns a list with the remaining, non-standard attributes.
   *
   * @param attributes the attributes to check
   * @return the list with the standard attributes removed
   */
  private def checkStandardPropFindAttributes(attributes: List[DavModel.AttributeKey]):
  List[DavModel.AttributeKey] = {
    val standardAttributes = Set(DavParser.AttrSize, DavParser.AttrCreatedAt, DavParser.AttrName,
      DavParser.AttrResourceType, DavParser.AttrModifiedAt)
    attributes should contain allElementsOf standardAttributes
    attributes filterNot standardAttributes
  }

  /**
   * Checks that the given attribute occurs only once in the given request.
   *
   * @param attr    the attribute
   * @param request the request
   */
  private def checkOneOccurrence(attr: DavModel.AttributeKey, request: String): Unit = {
    val regElem = attr.key.r
    regElem.findAllIn(request) should have size 1
  }

  "PropRequestGenerator" should "return no request if there are no attributes" in {
    val attributes = DavModel.Attributes(Map.empty)
    val optRequest = PropRequestGenerator.generatePropPatch(attributes, None, Some(DescKey))

    optRequest should be(None)
  }

  it should "create a request with a description attribute" in {
    val Desc = "some description"
    val attributes = DavModel.Attributes(Map.empty)
    val request = PropRequestGenerator.generatePropPatch(attributes, Some(Desc), Some(DescKey))

    val setCommands = parseSetCommands(parsePatchRequest(request))
    setCommands should have size 1
    setCommands(DescKey) should be(Desc)
  }

  it should "ignore the description if no attribute is defined" in {
    val attributes = DavModel.Attributes(Map.empty)
    val optRequest = PropRequestGenerator.generatePropPatch(attributes, Some("some desc"), None)

    optRequest should be(None)
  }

  it should "create a request to set additional attributes" in {
    val keyAdd1 = DavModel.AttributeKey(DescKey.namespace, "foo")
    val keyAdd2 = DavModel.AttributeKey("urn:other:", "bar")
    val Desc = "<cool> desc"
    val attributes = DavModel.Attributes(Map(keyAdd1 -> "<foo> value", keyAdd2 -> "<bar> value"))
    val request = PropRequestGenerator.generatePropPatch(attributes, Some(Desc), Some(DescKey))

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
    val request = PropRequestGenerator.generatePropPatch(attributes, null, None)

    val root = parsePatchRequest(request)
    parseSetCommands(root) should have size 0
    val removeCommands = parseRemoveCommands(root)
    removeCommands should contain only(keyDel1, keyDel2, DescKey)
  }

  it should "create a request with both set and remove elements" in {
    val keyDel = DavModel.AttributeKey(DescKey.namespace, "remove")
    val Desc = "the new description"
    val attributes = DavModel.Attributes(Map.empty, List(keyDel))
    val request = PropRequestGenerator.generatePropPatch(attributes, Some(Desc), Some(DescKey))

    val root = parsePatchRequest(request)
    val setCommands = parseSetCommands(root)
    setCommands should have size 1
    setCommands(DescKey) should be(Desc)
    val removeCommands = parseRemoveCommands(root)
    removeCommands should have size 1
    removeCommands should contain only keyDel
  }

  it should "define standard attributes for PROPFIND requests" in {
    val expectedAttributes = List("creationdate", "getlastmodified", "getcontentlength", "displayname",
      "resourcetype")

    PropRequestGenerator.StandardAttributes foreach { attr =>
      attr.namespace should be(NS_DAV)
    }
    PropRequestGenerator.StandardAttributes.map(_.key) should contain theSameElementsAs expectedAttributes
  }

  it should "return no PROPFIND request if no attributes are selected" in {
    val optRequest = PropRequestGenerator.generatePropFind(Nil, None)

    optRequest shouldBe empty
  }

  it should "return a PROPFIND request containing the standard attributes and the description attribute" in {
    val descAttribute = DavModel.AttributeKey("someNS", "theDesc")

    val properties = parsePropFindRequest(PropRequestGenerator.generatePropFind(Nil, Some(descAttribute)))
    checkStandardPropFindAttributes(properties) should contain only descAttribute
  }

  it should "return a PROPFIND request containing the additional attributes" in {
    val attr1 = DavModel.AttributeKey("namespace", "element")
    val attr2 = DavModel.AttributeKey("namespace", "another_element")
    val attr3 = DavModel.AttributeKey("another_namespace", "one_more_element")
    val attributes = List(attr1, attr2, attr3)

    val properties = parsePropFindRequest(PropRequestGenerator.generatePropFind(attributes, None))
    checkStandardPropFindAttributes(properties) should contain theSameElementsAs attributes
  }

  it should "return a PROPFIND request containing additional attributes and the description attribute" in {
    val attr1 = DavModel.AttributeKey("namespace", "element")
    val attr2 = DavModel.AttributeKey("namespace", "another_element")
    val attr3 = DavModel.AttributeKey("another_namespace", "one_more_element")
    val attributes = List(attr1, attr2, attr3)
    val attrDesc = DavModel.AttributeKey("descNS", "desc")
    val allAttributes = attrDesc :: attributes

    val properties = parsePropFindRequest(PropRequestGenerator.generatePropFind(attributes, Some(attrDesc)))
    checkStandardPropFindAttributes(properties) should contain theSameElementsAs allAttributes
  }

  it should "return a PROPFIND request containing the description attribute only once" in {
    val attrDesc = DavModel.AttributeKey("desc", "the_desc")

    val request = PropRequestGenerator.generatePropFind(List(attrDesc), Some(attrDesc)).get
    checkOneOccurrence(attrDesc, request)
  }

  it should "return a PROPFIND request containing no duplicate standard attributes" in {
    val attr = DavModel.AttributeKey("foo", "bar")

    val request = PropRequestGenerator.generatePropFind(List(DavParser.AttrName, DavParser.AttrModifiedAt, attr),
      Some(DavParser.AttrSize)).get
    checkOneOccurrence(DavParser.AttrName, request)
    checkOneOccurrence(DavParser.AttrSize, request)
    checkOneOccurrence(attr, request)
  }

  it should "return a PROPFIND request if the user has provided a description property" in {
    val properties = parsePropFindRequest(PropRequestGenerator.generatePropFind(Nil, Some(DavParser.AttrName)))

    checkStandardPropFindAttributes(properties) shouldBe empty
  }

  it should "return a PROPFIND request if the user has provided an additional attribute" in {
    val properties = parsePropFindRequest(PropRequestGenerator.generatePropFind(List(DavParser.AttrName), None))

    checkStandardPropFindAttributes(properties) shouldBe empty
  }
}
