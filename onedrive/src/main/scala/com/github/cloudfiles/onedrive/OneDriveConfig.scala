/*
 * Copyright 2020-2024 The Developers Team.
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

package com.github.cloudfiles.onedrive

import org.apache.pekko.util.Timeout

import scala.concurrent.duration._

object OneDriveConfig {
  /**
   * The default root URI of the OneDrive REST API. This URI is used by default
   * by the OneDrive configuration, but it can be overridden if needed.
   */
  final val OneDriveServerUri = "https://graph.microsoft.com/v1.0/me/drives"

  /**
   * Default value for the upload chunk size (in bytes). OneDrive accepts only
   * uploads of a limited size (60 MB currently). Larger files need to be
   * split into multiple chunks. This constant defines the default chunk size
   * used when the user has not explicitly defined one.
   */
  final val DefaultUploadChunkSize = 10 * 1024 * 1024

  /**
   * The default timeout for requests against the OneDrive server. This value
   * is used if the user does not provide an explicit timeout.
   */
  final val DefaultTimeout = Timeout(30.seconds)
}

/**
 * A configuration class for the OneDrive ''FileSystem'' implementation.
 *
 * The properties defined here affect the communication with the OneDrive
 * server. The URIs generated for OneDrive requests are constructed based on
 * these properties. Some behavioral aspects are defined as well, such as
 * timeouts for requests.
 *
 * @param driveID         the ID of the drive to access
 * @param serverUri       the URI of the OneDrive server; the base URI for
 *                        OneDrive requests is generated from a concatenation
 *                        of this URI and the ''driveID'' property
 * @param optRootPath     allows defining an alternative root path; if
 *                        defined, this path is used as base when resolving
 *                        relative paths and also as root path; otherwise,
 *                        root is the drive's root folder
 * @param uploadChunkSize the size (in bytes) for splitting files in uploads
 * @param timeout         the timeout for server requests
 */
case class OneDriveConfig(driveID: String,
                          serverUri: String = OneDriveConfig.OneDriveServerUri,
                          optRootPath: Option[String] = None,
                          uploadChunkSize: Int = OneDriveConfig.DefaultUploadChunkSize,
                          timeout: Timeout = OneDriveConfig.DefaultTimeout)
