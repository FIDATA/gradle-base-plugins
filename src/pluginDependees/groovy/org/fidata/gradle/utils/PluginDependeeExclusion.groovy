#!/usr/bin/env groovy
/*
 * PluginDependeeExclusion class
 * Copyright © 2018  Basil Peace
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
package org.fidata.gradle.utils

import groovy.transform.CompileStatic

/**
 * Exclusion for plugin dependee
 */
@CompileStatic
final class PluginDependeeExclusion {
  /**
   * Group name
   */
  String group
  /**
   * Module name
   */
  String module
}
