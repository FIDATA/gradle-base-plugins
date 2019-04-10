#!/usr/bin/env groovy
/*
 * Jenkinsfile for gradle-base-plugins
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
//noinspection GroovyUnusedAssignment
@SuppressWarnings(['UnusedVariable', 'NoDef', 'VariableTypeRequired'])
@Library('jenkins-pipeline-shared-library@develop') dummy

defaultJvmPipeline(
  publicReleases: Boolean.FALSE,
  timeouts: [
    Test: 10,
  ],
  tests: [
    'test',
    'functionalTest',
  ].toSet(),
  gradlePlugin: Boolean.TRUE
)
