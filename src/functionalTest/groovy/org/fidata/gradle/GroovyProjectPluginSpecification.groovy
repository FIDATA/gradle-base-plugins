#!/usr/bin/env groovy
/*
 * Specification for org.fidata.project.groovy Gradle plugin
 * Copyright © 2018  Basil Peace
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
package org.fidata.gradle

import static org.fidata.testfixtures.TestFixtures.initEmptyGitRepository
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * Specification for {@link org.fidata.gradle.GroovyProjectPlugin} class
 */
class GroovyProjectPluginSpecification extends Specification {
  // fields
  @Rule
  final TemporaryFolder testProjectDir = new TemporaryFolder()

  Project project

  static final Map<String, String> EXTRA_PROPERTIES = [
    'artifactoryUser'    : 'dummyArtifactoryUser',
    'artifactoryPassword': 'dummyArtifactoryPassword',
    'gitUsername': 'dummyGitUser',
    'gitPassword': 'dummyGitPassword',
    'ghToken': 'dummyGhToken',
    'gpgKeyId'            : 'ABCD1234',
    'gpgKeyPassword'      : '',
    'gpgSecretKeyRingFile': 'dummyGPGSecretKeyRingFile',
  ]

  // fixture methods

  // run before the first feature method
  // void setupSpec() { }

  // run before every feature method
  void setup() {
    initEmptyGitRepository(testProjectDir.root)
    testProjectDir.newFile('settings.gradle') << '''\
      enableFeaturePreview('STABLE_PUBLISHING')
    '''.stripIndent()
    project = ProjectBuilder.builder().withProjectDir(testProjectDir.root).build()
    EXTRA_PROPERTIES.each { String key, String value ->
      project.ext.setProperty key, value
    }
  }

  // run after every feature method
  // void cleanup() { }

  // run after the last feature method
  // void cleanupSpec() { }

  // feature methods

  void 'can be applied'() {
    when:
    'plugin is applied'
    project.apply plugin: 'org.fidata.project.groovy'

    then:
    'no exception is thrown'
    noExceptionThrown()

    when: 'project evaluated'
    project.evaluate()

    then:
    'no exception is thrown'
    noExceptionThrown()
  }

  // helper methods
}