#!/usr/bin/env groovy
/*
 * Unit tests for VersionUtils class
 * Copyright © 2017-2018  Basil Peace
 *
 * This file is part of gradle-base-plugins.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.fidata.gradle.utils

import org.junit.runner.RunWith
import org.junit.Test
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import junitparams.naming.TestCaseName

/**
 * Unit tests for {@link VersionUtils} class
 */
@RunWith(JUnitParamsRunner)
class VersionUtilsTest {
  /**
   * Test method for {@link VersionUtils#isPreReleaseVersion(java.lang.String)}.
   */
  @Test
  @Parameters
  @TestCaseName('{index}: isPreReleaseVersion({0}) == {1}')
  void testIsPreReleaseVersion(final String version, final Boolean expectedResult) {
    assert expectedResult == VersionUtils.isPreReleaseVersion(version)
  }

  static Object[][] parametersForTestIsPreReleaseVersion() {
    [
      // SemVer
      [null                   , null],
      [''                     , null],
      ['1.0.0'                , false],
      ['1.0.0+20130313144700' , false],
      ['0.1.0'                , false], // We don't follow Magic Zero rule
      ['1.0.1-SNAPSHOT'       , true],
      ['1.0.0-alpha+001'      , true],
      ['1.0.0-alpha'          , true],
      // Java
      ['1.8.0_152'            , false],
      // org.spockframework:spock-core
      ['1.1-groovy-2.4'       , false],
      // javax.validation:validation-api
      ['2.0.0.Final'          , false],
      ['1.0.0.GA'             , false],
      ['2.0.0.Alpha2'         , true],
      ['2.0.0.CR1'            , true],
      // com.google.guava:guava
      ['23.5-jre'             , false],
    ]
  }
}