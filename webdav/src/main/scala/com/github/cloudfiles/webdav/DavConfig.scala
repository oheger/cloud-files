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

import akka.http.scaladsl.model.Uri
import akka.util.Timeout

import scala.concurrent.duration._

/**
 * A data class storing configuration options for interacting with a WebDav
 * server.
 *
 * The server is identified by a root URI. This URI can have a path component,
 * the file system then uses this path as its root. Further properties define
 * which attribute should be used to extract the description of elements, the
 * behavior when overriding files, and timeouts when waiting for server
 * responses.
 *
 * @param rootUri              the root URI of the WebDav file system
 * @param optDescriptionKey    optional key for the description attribute
 * @param deleteBeforeOverride flag whether files should be deleted before
 *                             they are uploaded again; set to '''true''' if
 *                             file uploads are unreliable if files already
 *                             exist on the server, but be aware that this
 *                             removes all the properties of this file
 * @param timeout              the timeout for server requests
 */
case class DavConfig(rootUri: Uri,
                     optDescriptionKey: Option[DavModel.AttributeKey] = None,
                     deleteBeforeOverride: Boolean = false,
                     timeout: Timeout = Timeout(30.seconds))
