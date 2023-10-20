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

package com.github.cloudfiles.localfs

import java.nio.file.{LinkOption, Path}
import scala.concurrent.ExecutionContext

/**
 * The configuration class for the local file system implementation.
 *
 * The most important property is the base path of the local file system.
 * Relative paths are resolved against this root folder. As the ''FileSystem''
 * API accepts arbitrary paths, it is possible to manipulate elements that are
 * not in the sub tree spawned by the base path. Depending on the context of an
 * application, this may be a security issue. To prevent this, this
 * configuration supports an option to enable a mode in which paths are
 * sanitized; this option is '''true''' per default. In this mode, all paths
 * passed to the file system are normalized, and it is checked whether they are
 * actually sub paths of the base path. If this check fails, the operation
 * fails with an exception.
 *
 * As the file system implementation typically uses blocking operations, a
 * dedicated ''ExecutionContext'' has to be provided. This is to force the user
 * to think about this option, because simply using the execution context of an
 * already available actor system may not be the best option.
 *
 * @param basePath         the base path of the file system; this should be an
 *                         absolute path
 * @param executionContext the execution context
 * @param linkOptions      options for dealing with links
 * @param sanitizePaths    flag whether paths should be checked against the
 *                         base path
 */
case class LocalFsConfig(basePath: Path,
                         executionContext: ExecutionContext,
                         linkOptions: Seq[LinkOption] = Nil,
                         sanitizePaths: Boolean = true)
