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

package com.github.cloudfiles.core.delegate

import com.github.cloudfiles.core.{FileSystem, Model}

/**
 * A trait describing a [[FileSystem]] whose basic functionality can be
 * extended by delegating file systems.
 *
 * The basic idea is that an ''ExtensibleFileSystem'' is wrapped inside another
 * ''FileSystem'' implementation. The outer file system uses the wrapped one to
 * access files and folders, but manipulates these objects in a specific way
 * (e.g. encrypting their content). For this to work, the original file system
 * must provide some means to manipulate the attributes of files and folders,
 * so that the outer file system can implement its own functionality.
 * Therefore, this trait extends the base trait by some functions that support
 * such a manipulation.
 *
 * Note that the functions added by this trait are not intended to be called by
 * end users; they are rather used by extension file systems to execute their
 * specific manipulations.
 */
trait ExtensibleFileSystem[ID, FILE, FOLDER, FOLDER_CONTENT] extends FileSystem[ID, FILE, FOLDER, FOLDER_CONTENT] {
  /**
   * Constructs an implementation-specific folder object based on the
   * properties of the given source folder with the given patch specification
   * applied. This allows components that extend the functionality of a
   * ''FileSystem'' to manipulate the attributes of a folder without having to
   * know the concrete implementation class.
   *
   * @param source the source folder
   * @param spec   the ''ElementPatchSpec'' to manipulate properties
   * @return the resulting folder object
   */
  def patchFolder(source: Model.Folder[ID], spec: ElementPatchSpec): FOLDER

  /**
   * Constructs an implementation specific file object based on the properties
   * of the given source file with the given patch specification applied. This
   * allows components that extend the functionality of a ''FileSystem'' to
   * manipulate the attributes of a file without having to know the concrete
   * implementation class.
   *
   * @param source the source file
   * @param spec   the ''ElementPatchSpec'' to manipulate properties
   * @return the resulting file object
   */
  def patchFile(source: Model.File[ID], spec: ElementPatchSpec): FILE
}
