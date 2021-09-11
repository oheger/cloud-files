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

package com.github.cloudfiles.gdrive

import akka.util.Timeout

import scala.concurrent.duration.DurationInt

object GoogleDriveConfig {
  /** The default root URI for the GoogleDrive API. */
  final val GoogleDriveServerUri = "https://www.googleapis.com"

  /**
   * The default timeout for requests against the GoogleDrive server. This value
   * is used if the user does not provide an explicit timeout.
   */
  final val DefaultTimeout = Timeout(30.seconds)
}

/**
 * A configuration class for the GoogleDrive ''FileSystem'' implementation.
 *
 * Using this class, certain aspects of the behavior of the GoogleDrive
 * implementation can be configured. Configuration is optional, meaningful
 * default values are set for all of the properties.
 *
 * @param serverUri      the root URI of the GoogleDrive API
 * @param optRootPath    an optional root path; if defined, all paths are
 *                       resolved relatively to this root path
 * @param timeout        the timeout for server requests
 * @param includeTrashed flag whether files in the trash should be included in
 *                       query results
 */
case class GoogleDriveConfig(serverUri: String = GoogleDriveConfig.GoogleDriveServerUri,
                             optRootPath: Option[String] = None,
                             timeout: Timeout = GoogleDriveConfig.DefaultTimeout,
                             includeTrashed: Boolean = false)
