/*
 * Copyright 2020-2025 The Developers Team.
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

import com.github.cloudfiles.core.http.factory.SpawnerTestActor.checkSpawner
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
 * Test class for ''Spawner'' implementations for the typed world.
 */
class SpawnerTypedSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers {
  "Spawner" should "support creating an actor from a typed actor context" in {
    val probe = testKit.createTestProbe[SpawnerClientActor.CheckResponse]()
    val client = testKit.spawn(SpawnerClientActor.apply(testKit, probe.ref))

    client ! SpawnerClientActor.TriggerCheck()
    probe.expectMessage(SpawnerClientActor.CheckSuccess)
  }

  it should "support creating an actor with a name from a typed actor context" in {
    val probe = testKit.createTestProbe[SpawnerClientActor.CheckResponse]()
    val client = testKit.spawn(SpawnerClientActor.apply(testKit, probe.ref))

    client ! SpawnerClientActor.TriggerCheck(Some("fromTypedActorContext"))
    probe.expectMessage(SpawnerClientActor.CheckSuccess)
  }
}

object SpawnerClientActor {

  /**
   * A trait for the messages handled by this client actor.
   */
  sealed trait Check

  /**
   * The message that triggers a test of the spawner.
   *
   * @param optName an optional name for the actor to create
   */
  case class TriggerCheck(optName: Option[String] = None) extends Check

  /**
   * A trait for the responses sent by this client actor.
   */
  sealed trait CheckResponse

  /**
   * The message sent in case of a successful check.
   */
  case object CheckSuccess extends CheckResponse

  /**
   * Creates a new client actor that can be used to check a ''Spawner'' for a
   * typed actor context. The test is started when this actor receives a
   * [[TriggerCheck]] message.
   *
   * @param testKit the current test kit
   * @param notify  the actor to notify for a successful test
   * @return the behavior of the new actor
   */
  def apply(testKit: ActorTestKit, notify: ActorRef[CheckResponse]): Behavior[Check] =
    Behaviors.receivePartial {
      case (ctx, TriggerCheck(optName)) =>
        val ref = checkSpawner(testKit, ctx, optName)
        val result = optName.forall(ref.path.toString.endsWith(_))
        if (result) {
          notify ! CheckSuccess
        }
        Behaviors.same
    }
}
