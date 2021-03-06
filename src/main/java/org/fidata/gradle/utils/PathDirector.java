/*
 * PathDirector interface
 * Copyright ©  Basil Peace
 *
 * This file is part of gradle-base-plugins.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package org.fidata.gradle.utils;

import java.nio.file.Path;

/**
 * A path director is capable of providing a path based on some inherent characteristics of an object.
 * Analogue for {@link org.gradle.api.Namer}
 *
 * @param <T> Type of objects which paths are determined
 */
public interface PathDirector<T> {
  /**
   * Determines the path for the given object.
   * Implementation should manually call {@link org.gradle.internal.FileUtils#toSafeFileName}
   * on individual directory/file names whenever necessary
   *
   * @param object The object to determine the path for
   * @return The path for the object. Never null
   */
  Path determinePath(T object);
}
