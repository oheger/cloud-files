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

package com.github.cloudfiles

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import com.github.cloudfiles.core.http.UriEncodingHelper

package object webdav {
  /**
   * Returns an URI based on the given one that is guaranteed to end with a
   * slash.
   *
   * @param uri the URI
   * @return the URI ending on a slash
   */
  def withTrailingSlash(uri: Uri): Uri =
    if (uri.path.toString().endsWith(UriEncodingHelper.UriSeparator)) uri
    else uri.withPath(Path(uri.path.toString() + UriEncodingHelper.UriSeparator))
}
