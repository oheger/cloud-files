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

package com.github.cloudfiles.http

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Test class for ''Secret''.
 */
class SecretSpec extends AnyFlatSpec with Matchers {
  "A Secret" should "create the password on demand" in {
    val secret = new Secret(Array('S', '3', 'c', 'r', '3', 'T', '!'))

    secret.secret should be("S3cr3T!")
  }

  it should "not cache the password" in {
    val secret = new Secret(Array('S', '3', 'c', 'r', '4', 'T', '?'))
    val sec1 = secret.secret

    secret.secret should not be theSameInstanceAs(sec1)
  }

  it should "support creating an instance from a string" in {
    val s = "TopS3cr3t!"

    val secret = Secret(s)
    secret.secret should not be theSameInstanceAs(s)
  }
}
