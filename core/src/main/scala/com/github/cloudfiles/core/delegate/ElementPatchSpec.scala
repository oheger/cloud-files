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

package com.github.cloudfiles.core.delegate

/**
 * A data class describing changes on the properties of an file system element
 * to be applied on behalf of a delegate file system.
 *
 * A file system that adds specific functionality to another file system
 * typically has to modify some attributes of file and folder objects obtained
 * from the user or the underlying file system. This is problematic because the
 * concrete type of the file and folder objects depends on the wrapped file
 * system. Simply wrapping these objects to override some of their attributes
 * would be incorrect, as the original objects might contain additional
 * information specific to the implementation, which can no longer be accessed
 * by the file system if the objects are wrapped.
 *
 * As a solution to this problem, this class defines optional fields for the
 * writable properties of files or folders that can be set to values that
 * should override the original properties of the corresponding file system
 * elements. A concrete [[com.github.cloudfiles.core.FileSystem]]
 * implementation must be able to apply these patches to its own data
 * structures.
 *
 * @param patchName optional value to override the name
 * @param patchSize optional value to override the size
 */
case class ElementPatchSpec(patchName: Option[String] = None,
                            patchSize: Option[Long] = None)
