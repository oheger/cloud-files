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

import com.github.cloudfiles.webdav.DavModel.{AttributeKey, Attributes}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Test class for functionality of the model classes of the DAV module. Basic
 * functionality is already tested when using the model objects in other tests.
 * So this class focuses on some specialities.
 */
class DavModelSpec extends AnyFlatSpec with Matchers {
  "Attributes" should "support adding an attribute by its key" in {
    val key = AttributeKey("urn:foo", "myKey")
    val attributesOrg = Attributes(Map.empty)

    val attributes = attributesOrg.withAttribute(key, "bar")
    attributes.keysToDelete should be(attributesOrg.keysToDelete)
    attributes.values should have size 1
    attributes.values(key) should be("bar")
  }

  it should "support adding an attribute using a namespace key pair" in {
    val key = AttributeKey("urn:foo", "myKey")
    val attributesOrg = Attributes(Map.empty)

    val attributes = attributesOrg.withAttribute(key.namespace, key.key, "bar")
    attributes.keysToDelete should be(attributesOrg.keysToDelete)
    attributes.values should have size 1
    attributes.values(key) should be("bar")
  }
}
