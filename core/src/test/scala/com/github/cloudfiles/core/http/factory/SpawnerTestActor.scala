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

package com.github.cloudfiles.core.http.factory

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/**
 * A simple actor implementation for testing different [[Spawner]]
 * implementations.
 *
 * This actor class receives messages of a specific type and forwards them to
 * another actor. That way it can be checked whether the actor has been
 * correctly created and is alive.
 */
object SpawnerTestActor {

  /**
   * The message class processed by the test actor implementation.
   *
   * @param content the content of the message
   */
  case class Message(content: String)

  /**
   * Creates a new instance of this test actor that forwards all incoming
   * messages to the specified probe.
   *
   * @param probe the probe to forward all messages to
   * @return the behavior of the test actor
   */
  def apply(probe: ActorRef[Message]): Behavior[Message] =
    Behaviors.receiveMessagePartial {
      case m: Message =>
        probe ! m
        Behaviors.same
    }

  /**
   * Tests whether an actor can be created via the ''Spawner'' provided and
   * whether it behaves as expected.
   *
   * @param testKit the actor test kit
   * @param spawner the ''Spawner''
   * @param optName the optional actor name
   * @return a reference to the new actor
   */
  def checkSpawner(testKit: ActorTestKit, spawner: Spawner, optName: Option[String] = None):
  ActorRef[SpawnerTestActor.Message] = {
    val probe = testKit.createTestProbe[SpawnerTestActor.Message]()
    val msg = SpawnerTestActor.Message("This is a test message.")
    val actor = spawner.spawn(SpawnerTestActor(probe.ref), optName)
    actor ! msg
    probe.expectMessage(msg)
    actor
  }
}
