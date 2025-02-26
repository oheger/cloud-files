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

package com.github.cloudfiles.webdav

import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout

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
 * When querying the content of folders using ''PROPFIND'' requests, servers
 * can behave differently with regards to the properties included in the
 * responses by default: Some servers always return the full set of properties
 * available, while others limit responses to the set of standard DAV
 * properties. When working with custom properties it is therefore necessary to
 * explicitly list the fully-qualified names of these properties in requests.
 * This can be achieved by passing them as ''additionalAttributes''. The
 * parameter defaults to the set of standard properties, causing requests to
 * select only these properties. If custom properties are to be taken into
 * account, provide a collection with their fully-qualified names here. (The
 * collection does not need to include the standard properties; they are always
 * retrieved. Also, the optional description key - if provided - is taken into
 * account automatically.) By explicitly setting the ''additionalAttributes''
 * parameter to an empty list and passing ''None'' for the description key, no
 * body is generated for ''PROPFIND'' requests; the results then depend on a
 * concrete server.
 *
 * @param rootUri              the root URI of the WebDav file system
 * @param additionalAttributes a collection with the keys for custom properties
 *                             to retrieve from the server
 * @param optDescriptionKey    optional key for the description attribute
 * @param deleteBeforeOverride flag whether files should be deleted before
 *                             they are uploaded again; set to '''true''' if
 *                             file uploads are unreliable if files already
 *                             exist on the server, but be aware that this
 *                             removes all the properties of this file
 * @param timeout              the timeout for server requests
 */
case class DavConfig(rootUri: Uri,
                     additionalAttributes: Iterable[DavModel.AttributeKey] = Nil,
                     optDescriptionKey: Option[DavModel.AttributeKey] = None,
                     deleteBeforeOverride: Boolean = false,
                     timeout: Timeout = Timeout(30.seconds))
