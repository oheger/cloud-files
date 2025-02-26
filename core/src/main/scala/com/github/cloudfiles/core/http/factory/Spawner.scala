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

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.{actor => classic}

import scala.language.implicitConversions

/**
 * An object implementing helper functionality related to concrete
 * implementations of the [[Spawner]] trait.
 *
 * This object especially provides some implicit conversions from objects that
 * support spawning new actors to corresponding ''Spawner'' objects.
 */
object Spawner {
  /**
   * Implicit conversion function to create a ''Spawner'' from a classic actor
   * system.
   *
   * @param system the actor system
   * @return the ''Spawner'' creating actors using this system
   */
  implicit def classicActorSystemSpawner(system: classic.ActorSystem): Spawner =
    new ClassicActorSystemSpawner(system)

  /**
   * Implicit conversion function to create a ''Spawner'' from a classic actor
   * context.
   *
   * @param context the actor context
   * @return the ''Spawner'' creating actors using this context
   */
  implicit def classicActorContextSpawner(context: classic.ActorContext): Spawner =
    new ClassicActorContextSpawner(context)

  /**
   * Implicit conversion function to create a ''Spawner'' from a typed actor
   * context.
   *
   * @param context the actor context
   * @return the ''Spawner'' creating actors using this context
   */
  implicit def typedActorContextSpawner(context: ActorContext[_]): Spawner =
    new TypedActorContextSpawner(context)
}

/**
 * A trait that allows abstracting of the creation of a typed actor.
 *
 * There are multiple sources that can be used to create typed actor
 * references, such as a typed actor context, an untyped actor context, or an
 * untyped actor system. With this trait, all these sources can be treated in a
 * common way.
 */
trait Spawner {
  /**
   * Creates a new typed actor using a specific behavior with some optional
   * properties.
   *
   * @param behavior the behavior of the new actor
   * @param optName  an optional name for the actor
   * @param props    optional ''Props'' for the actor
   * @tparam T the type of the messages accepted by the actor
   * @return a reference to the the newly created
   */
  def spawn[T](behavior: Behavior[T], optName: Option[String] = None, props: Props = Props.empty): ActorRef[T]
}

/**
 * An internal [[Spawner]] implementation that uses a classic actor system for
 * spawning new typed actors.
 *
 * @param system the classic actor system
 */
private class ClassicActorSystemSpawner(system: classic.ActorSystem) extends Spawner {
  override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
    optName match {
      case Some(name) =>
        system.spawn(behavior, name, props)
      case None =>
        system.spawnAnonymous(behavior, props)
    }
}

/**
 * An internal [[Spawner]] implementation that uses a classic actor context for
 * spawning new typed actors.
 *
 * @param context the classic actor context
 */
private class ClassicActorContextSpawner(context: classic.ActorContext) extends Spawner {
  override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
    optName match {
      case Some(name) =>
        context.spawn(behavior, name, props)
      case None =>
        context.spawnAnonymous(behavior, props)
    }
}

/**
 * An internal [[Spawner]] implementation that uses a typed actor context for
 * spawning new typed actors.
 *
 * @param context the typed actor context
 */
private class TypedActorContextSpawner(context: ActorContext[_]) extends Spawner {
  override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
    optName match {
      case Some(name) =>
        context.spawn(behavior, name, props)
      case None =>
        context.spawnAnonymous(behavior, props)
    }
}
