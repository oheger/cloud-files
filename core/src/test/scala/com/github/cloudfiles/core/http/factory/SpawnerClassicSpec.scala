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

package com.github.cloudfiles.core.http.factory

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.github.cloudfiles.core.http.factory.SpawnerClassicSpec.{Check, CheckSuccess}
import com.github.cloudfiles.core.http.factory.SpawnerTestActor.checkSpawner
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object SpawnerClassicSpec {

  /**
   * A message processed by the test client that triggers a test.
   */
  private case object Check

  /**
   * A message sent by the test client actor that indicates a successful test.
   */
  private case object CheckSuccess

}

/**
 * Test class for ''Spawner'' implementations for classic objects. This test
 * class requires a classic actor system.
 */
class SpawnerClassicSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("SpawnerClassicSpec"))

  /** The testkit for dealing with typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
    super.afterAll()
  }

  import Spawner._

  /**
   * Creates an actor that can be used to test a ''Spawner'' for a classic
   * actor context.
   *
   * @param optName an optional name for the spawned actor
   * @return the client actor to test the ''Spawner'' for the context
   */
  private def clientActor(optName: Option[String] = None): akka.actor.ActorRef =
    system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case Check =>
          val ref = checkSpawner(testKit, context, optName)
          val result = optName.forall(ref.path.toString.endsWith(_))
          if (result) {
            sender() ! CheckSuccess
          }
      }
    }))

  "Spawner" should "support creating an actor from an untyped actor system" in {
    checkSpawner(testKit, system)
  }

  it should "support creating an actor with a name from an untyped actor system" in {
    val name = "fromClassicSystem"
    val actor = checkSpawner(testKit, system, Some(name))

    actor.path.toString should endWith(name)
  }

  it should "support creating an actor from an untyped actor context" in {
    val client = clientActor()

    client ! Check
    expectMsg(CheckSuccess)
  }

  it should "support creating an actor with a name from an untyped actor context" in {
    val client = clientActor(Some("fromClassicContext"))

    client ! Check
    expectMsg(CheckSuccess)
  }
}
