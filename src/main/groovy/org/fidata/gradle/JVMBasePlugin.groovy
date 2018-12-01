#!/usr/bin/env groovy
/*
 * org.fidata.project.jdk Gradle plugin
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
package org.fidata.gradle

import static java.nio.charset.StandardCharsets.UTF_8
import static org.gradle.api.plugins.JavaPlugin.COMPILE_CONFIGURATION_NAME
import static org.gradle.api.plugins.JavaPlugin.API_CONFIGURATION_NAME
import static org.gradle.api.plugins.JavaPlugin.TEST_TASK_NAME
import static org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME
import static org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import static org.ajoberstar.gradle.git.release.base.BaseReleasePlugin.RELEASE_TASK_NAME
import static ProjectPlugin.LICENSE_FILE_NAMES
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
import static org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask.ARTIFACTORY_PUBLISH_TASK_NAME
import static org.gradle.initialization.IGradlePropertiesLoader.ENV_PROJECT_PROPERTIES_PREFIX
import java.nio.file.InvalidPathException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.plugins.quality.JDepend
import org.gradle.api.Namer
import org.fidata.gradle.utils.PathDirector
import org.fidata.gradle.utils.ReportPathDirectorException
import org.gradle.api.Task
import groovy.transform.PackageScope
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.plugins.signing.Sign
import org.gradle.api.file.CopySpec
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.fidata.gradle.utils.PluginDependeesUtils
import org.gradle.api.publish.PublishingExtension
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask
import org.fidata.gradle.tasks.CodeNarcTaskConvention
import org.gradle.api.artifacts.ModuleDependency
import groovy.transform.CompileStatic
import org.fidata.gradle.internal.AbstractProjectPlugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeEvent
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.reporting.ReportingExtension
import com.jfrog.bintray.gradle.BintrayExtension
import com.jfrog.bintray.gradle.tasks.BintrayPublishTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.nio.file.Path
import java.nio.file.Paths
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.plugins.quality.CodeNarc
import org.gradle.api.publish.PublicationContainer

/**
 * Provides an environment for a JDK project
 */
@CompileStatic
final class JVMBasePlugin extends AbstractProjectPlugin implements PropertyChangeListener {
  /**
   * Name of jvm extension for {@link Project}
   */
  public static final String JVM_EXTENSION_NAME = 'jvm'

  @Override
  void apply(Project project) {
    super.apply(project)

    if (project == project.rootProject) {
      project.pluginManager.apply ProjectPlugin
    }

    boolean isBuildSrc = project.rootProject.convention.getPlugin(RootProjectConvention).isBuildSrc

    PluginDependeesUtils.applyPlugins project, isBuildSrc, JVMBasePluginDependees.PLUGIN_DEPENDEES

    project.extensions.add JVM_EXTENSION_NAME, new JVMBaseExtension(project)

    project.convention.getPlugin(ProjectConvention).addPropertyChangeListener this

    if (!isBuildSrc) {
      configurePublicReleases()
    }

    project.tasks.withType(JavaCompile).configureEach { JavaCompile javaCompile ->
      javaCompile.options.encoding = UTF_8.name()
    }

    if (!isBuildSrc) {
      project.tasks.withType(ProcessResources).configureEach { ProcessResources processResources ->
        processResources.from(LICENSE_FILE_NAMES) { CopySpec copySpec ->
          copySpec.into 'META-INF'
        }
      }
    }

    if (!isBuildSrc) {
      configureDocumentation()
    }

    configureTesting()

    if (!isBuildSrc) {
      configureArtifactsPublishing()
    }

    configureCodeQuality()
  }

  /**
   * Gets called when a property is changed
   */
  void propertyChange(PropertyChangeEvent e) {
    switch (e.source) {
      case project.convention.getPlugin(ProjectConvention):
        switch (e.propertyName) {
          case 'publicReleases':
            configurePublicReleases()
            break
        }
        break
      default:
        project.logger.warn('org.fidata.base.jvm: unexpected property change source: {}', e.source)
    }
  }

  private void configurePublicReleases() {
    if (project.convention.getPlugin(ProjectConvention).publicReleases) {
      configureBintray()
    }
  }

  /**
   * Adds JUnit dependency to specified source set configuration
   * @param sourceSet source set
   */
  void addJUnitDependency(NamedDomainObjectProvider<SourceSet> sourceSetProvider) {
    sourceSetProvider.configure { SourceSet sourceSet ->
      project.dependencies.add(sourceSet.implementationConfigurationName, [
        group: 'junit',
        name: 'junit',
        version: '[4, 5['
      ])
    }
  }

  /**
   * Adds Spock to specified source set and task
   * @param sourceSet source set
   * @param task test task.
   *        If null, task with the same name as source set is used
   */
  void addSpockDependency(NamedDomainObjectProvider<SourceSet> sourceSetProvider, TaskProvider<Test> task = null) {
    addSpockDependency sourceSetProvider, [task ?: project.tasks.withType(Test).named(sourceSetProvider.name)], new PathDirector<TaskProvider<Test>>() {
      @Override
      Path determinePath(TaskProvider<Test> object)  {
        try {
          Paths.get(object.name)
        } catch (InvalidPathException e) {
          throw new ReportPathDirectorException(object, e)
        }
      }
    }
  }

  /**
   * Namer of codenarc task for source sets
   */
  static final Namer<NamedDomainObjectProvider<SourceSet>> CODENARC_NAMER = new Namer<NamedDomainObjectProvider<SourceSet>>() {
    @Override
    String determineName(NamedDomainObjectProvider<SourceSet> sourceSetProvider)  {
      "codenarc${ sourceSetProvider.name.capitalize() }"
    }
  }

  /**
   * Adds Spock to specified source set and tasks
   * @param sourceSet source set
   * @param tasks list of test tasks.
   * @param reportDirector path director for task reports
   */
  /*
   * WORKAROUND:
   * Groovy error. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings('UnnecessarySetter')
  void addSpockDependency(NamedDomainObjectProvider<SourceSet> sourceSetProvider, Iterable<TaskProvider<Test>> tasks, PathDirector<TaskProvider<Test>> reportDirector) {
    addJUnitDependency sourceSetProvider

    project.pluginManager.apply GroovyBasePlugin

    sourceSetProvider.configure { SourceSet sourceSet ->
      project.dependencies.with {
        add(sourceSet.implementationConfigurationName, [
          group: 'org.spockframework',
          name: 'spock-core',
          version: "1.2-groovy-${ (GroovySystem.version =~ /^\d+\.\d+/)[0] }"
        ]) { ModuleDependency dependency ->
          dependency.exclude(
            group: 'org.codehaus.groovy',
            module: 'groovy-all'
          )
        }
        add(sourceSet.runtimeOnlyConfigurationName, [
          group: 'com.athaydes',
          name: 'spock-reports',
          version: '[1, 2['
        ]) { ModuleDependency dependency ->
          dependency.transitive = false
        }
      }
      project.plugins.withType(GroovyBasePlugin).configureEach { GroovyBasePlugin plugin ->
        plugin.addGroovyDependency project.configurations.named(sourceSet.implementationConfigurationName)
      }
    }

    ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
    tasks.each { TaskProvider<Test> taskProvider ->
      taskProvider.configure { Test test ->
        test.with {
          reports.with {
            html.enabled = false
            junitXml.setDestination projectConvention.getXmlReportDir(reportDirector, taskProvider)
          }
          File spockHtmlReportDir = projectConvention.getHtmlReportDir(reportDirector, taskProvider)
          File spockJsonReportDir = projectConvention.getJsonReportDir(reportDirector, taskProvider)
          systemProperty 'com.athaydes.spockframework.report.outputDir', spockHtmlReportDir.absolutePath
          systemProperty 'com.athaydes.spockframework.report.aggregatedJsonReportDir', spockJsonReportDir.absolutePath
          outputs.dir spockHtmlReportDir
          outputs.dir spockJsonReportDir
        }
        /*
         * WORKAROUND:
         * Without that we get error:
         * [Static type checking] - Cannot call org.gradle.api.tasks.TaskProvider <Test>#configure(org.gradle.api.Action
         * <java.lang.Object extends java.lang.Object>) with arguments [groovy.lang.Closure <org.gradle.api.Task>]
         * <grv87 2018-07-31>
         */
        null
      }
    }

    project.plugins.withType(GroovyBasePlugin).configureEach { GroovyBasePlugin plugin -> // TODO: 4.9
      project.tasks.withType(CodeNarc).named(CODENARC_NAMER.determineName(sourceSetProvider)).configure { CodeNarc codenarc ->
        codenarc.convention.getPlugin(CodeNarcTaskConvention).disabledRules.addAll 'MethodName', 'FactoryMethodName', 'JUnitPublicProperty', 'JUnitPublicNonTestMethod'
      }
    }
  }

  /**
   * Configures integration test source set classpath
   * See <a href="https://docs.gradle.org/current/userguide/java_testing.html#sec:configuring_java_integration_tests">https://docs.gradle.org/current/userguide/java_testing.html#sec:configuring_java_integration_tests</a>
   * @param sourceSet source set to configure
   */
  void configureIntegrationTestSourceSetClasspath(SourceSet sourceSet) {
    // https://docs.gradle.org/current/userguide/java_testing.html#sec:configuring_java_integration_tests
    // https://docs.gradle.org/current/userguide/test_kit.html#sub:test-kit-automatic-classpath-injection
    // +
    SourceSet mainSourceSet = project.convention.getPlugin(JavaPluginConvention).sourceSets.getByName(MAIN_SOURCE_SET_NAME)
    sourceSet.compileClasspath += mainSourceSet.output
    sourceSet.runtimeClasspath += sourceSet.output + mainSourceSet.output // TOTEST

    project.configurations.named(sourceSet.implementationConfigurationName).configure { Configuration configuration ->
      configuration.extendsFrom project.configurations.getByName(mainSourceSet.implementationConfigurationName)
    }
    project.configurations.named(sourceSet.runtimeOnlyConfigurationName).configure { Configuration configuration ->
      configuration.extendsFrom project.configurations.getByName(mainSourceSet.runtimeOnlyConfigurationName)
    }
  }

  /**
   * Name of functional test source set
   */
  public static final String FUNCTIONAL_TEST_SOURCE_SET_NAME = 'functionalTest'
  /**
   * Name of functional test source directory
   */
  public static final String FUNCTIONAL_TEST_SRC_DIR_NAME = 'functionalTest'
  /**
   * Name of functional test task
   */
  public static final String FUNCTIONAL_TEST_TASK_NAME = 'functionalTest'
  /**
   * Name of functional test reports directory
   */
  @Deprecated
  public static final String FUNCTIONAL_TEST_REPORTS_DIR_NAME = 'functionalTest'
  /**
   * Path of functional test reports directory
   */
  public static final Path FUNCTIONAL_TEST_REPORTS_PATH = Paths.get('functionalTest')

  /*
   * WORKAROUND:
   * Groovy error. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings('UnnecessarySetter')
  private void configureFunctionalTests() {
    NamedDomainObjectProvider<SourceSet> functionalTestSourceSetProvider = project.convention.getPlugin(JavaPluginConvention).sourceSets.register(FUNCTIONAL_TEST_SOURCE_SET_NAME) { SourceSet sourceSet ->
      sourceSet.java.srcDir project.file("src/$FUNCTIONAL_TEST_SRC_DIR_NAME/java")
      sourceSet.resources.srcDir project.file("src/$FUNCTIONAL_TEST_SRC_DIR_NAME/resources")

      configureIntegrationTestSourceSetClasspath sourceSet
    }

    TaskProvider<Test> functionalTestProvider = project.tasks.register(FUNCTIONAL_TEST_TASK_NAME, Test) { Test test ->
      SourceSet functionalTestSourceSet = functionalTestSourceSetProvider.get()
      test.with {
        group = VERIFICATION_GROUP
        description = 'Runs functional tests'
        shouldRunAfter project.tasks.named(TEST_TASK_NAME)
        testClassesDirs = functionalTestSourceSet.output.classesDirs
        classpath = functionalTestSourceSet.runtimeClasspath
      }
    }

    addSpockDependency functionalTestSourceSetProvider, functionalTestProvider
  }

  private void configureTesting() {
    project.convention.getPlugin(JavaPluginConvention).with {
      ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
      testReportDirName = project.extensions.getByType(ReportingExtension).baseDir.toPath().relativize(projectConvention.htmlReportsDir.toPath()).toString()
      testResultsDirName = project.buildDir.toPath().relativize(projectConvention.xmlReportsDir.toPath()).toString()
    }
    project.tasks.withType(Test).configureEach { Test test ->
      test.with {
        environment = environment.findAll { String key, Object value -> key != 'GRADLE_OPTS' && !key.startsWith(ENV_PROJECT_PROPERTIES_PREFIX) }
        maxParallelForks = Runtime.runtime.availableProcessors().intdiv(2) ?: 1
        testLogging.exceptionFormat = TestExceptionFormat.FULL
      }
    }

    addJUnitDependency project.convention.getPlugin(JavaPluginConvention).sourceSets.named(TEST_SOURCE_SET_NAME)

    configureFunctionalTests()
  }

  /**
   * Namer of sign task for maven publications
   */
  static final Namer<MavenPublication> SIGN_MAVEN_PUBLICATION_NAMER = new Namer<MavenPublication>() {
    @Override
    String determineName(MavenPublication mavenPublication)  {
      "sign${ mavenPublication.name.capitalize() }Publication"
    }
  }

  private void configureArtifactory() {
    project.convention.getPlugin(ArtifactoryPluginConvention).with {
      clientConfig.publisher.repoKey = "libs-${ project.rootProject.convention.getPlugin(RootProjectConvention).isRelease.get() ? 'release' : 'snapshot' }-local"
      clientConfig.publisher.username = project.rootProject.extensions.extraProperties['artifactoryUser'].toString()
      clientConfig.publisher.password = project.rootProject.extensions.extraProperties['artifactoryPassword'].toString()
      clientConfig.publisher.maven = true
    }
    project.tasks.withType(ArtifactoryTask).named(ARTIFACTORY_PUBLISH_TASK_NAME).configure { ArtifactoryTask artifactoryPublish ->
      PublicationContainer publications = project.extensions.getByType(PublishingExtension).publications
      publications.withType(MavenPublication) { MavenPublication mavenPublication ->
        artifactoryPublish.mavenPublications.add mavenPublication
      }
      publications.whenObjectRemoved { MavenPublication mavenPublication ->
        artifactoryPublish.mavenPublications.remove mavenPublication
      }

      artifactoryPublish.dependsOn project.tasks.withType(Sign).matching { Sign sign -> // TODO
        /*
         * WORKAROUND:
         * Without that cast we got compilation error with Groovy 2.5.2:
         * [Static type checking] - Reference to method is ambiguous.
         * Cannot choose between
         * [boolean java.lang.Iterable <T>#any(groovy.lang.Closure),
         * boolean java.lang.Object#any(groovy.lang.Closure)]
         * <grv87 2018-12-01>
         */
        ((Iterable<MavenPublication>)publications.withType(MavenPublication)).any { MavenPublication mavenPublication ->
          sign.name == SIGN_MAVEN_PUBLICATION_NAMER.determineName(mavenPublication)
        }
      }
    }
    project.rootProject.tasks.named(RELEASE_TASK_NAME).configure { Task release ->
      release.finalizedBy project.tasks.withType(ArtifactoryTask)
    }
  }

  /**
   * Name of maven java publication
   */
  public static final String MAVEN_JAVA_PUBLICATION_NAME = 'mavenJava'
  /**
   * Name of maven java publication
   * @deprecated Typo in the name. Use {@link #MAVEN_JAVA_PUBLICATION_NAME} instead
   */
  @Deprecated
  public static final String MAVEN_JAVA_PUBICATION_NAME = MAVEN_JAVA_PUBLICATION_NAME

  @PackageScope
  boolean createMavenJavaPublication = true

  private void configureArtifactsPublishing() {
    project.afterEvaluate {
      if (createMavenJavaPublication) {
        project.extensions.getByType(PublishingExtension).publications.register(MAVEN_JAVA_PUBLICATION_NAME, MavenPublication) { MavenPublication publication ->
          publication.from project.components.getByName('java' /* TODO */)
        }
      }
    }
    project.extensions.getByType(SigningExtension).sign project.extensions.getByType(PublishingExtension).publications

    configureArtifactory()
  }

  @SuppressWarnings(['UnnecessaryObjectReferences', 'UnnecessarySetter'])
  private void configureBintray() {
    RootProjectConvention rootProjectConvention = project.rootProject.convention.getPlugin(RootProjectConvention)
    ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
    project.pluginManager.apply 'com.jfrog.bintray'

    project.extensions.configure(BintrayExtension) { BintrayExtension extension ->
      extension.with {
        user = project.rootProject.extensions.extraProperties['bintrayUser'].toString()
        key = project.rootProject.extensions.extraProperties['bintrayAPIKey'].toString()
        pkg.repo = 'generic'
        pkg.name = 'gradle-project'
        pkg.userOrg = 'fidata'
        pkg.version.name = ''
        pkg.version.vcsTag = '' // TODO
        pkg.version.gpg.sign = true // TODO ?
        pkg.desc = project.version.toString() == '1.0.0' ? project.description : rootProjectConvention.changeLogTxt.get().toString()
        pkg.labels = projectConvention.tags.get().toArray(new String[0])
        pkg.setLicenses projectConvention.license
        pkg.vcsUrl = rootProjectConvention.vcsUrl.get()
        // pkg.version.attributes // Attributes to be attached to the version
      }
    }
    project.tasks.withType(BintrayPublishTask).configureEach { BintrayPublishTask bintrayPublish ->
      bintrayPublish.onlyIf { rootProjectConvention.isRelease.get() }
    }
    project.rootProject.tasks.named(RELEASE_TASK_NAME).configure { Task release ->
      release.finalizedBy project.tasks.withType(BintrayPublishTask)
    }
  }

  private void configureDocumentation() {
    if ([project.configurations.getByName(COMPILE_CONFIGURATION_NAME), project.configurations.getByName(API_CONFIGURATION_NAME)].any { Configuration configuration ->
      configuration.dependencies.contains(project.dependencies.gradleApi())
    }) {
      project.extensions.getByType(JVMBaseExtension).javadocLinks['org.gradle'] = project.uri("https://docs.gradle.org/${ project.gradle.gradleVersion }/javadoc/index.html?")
    }

    project.tasks.withType(Javadoc).configureEach { Javadoc javadoc ->
      javadoc.options.encoding = UTF_8.name()
      javadoc.doFirst {
        /*
         * WORKAROUND:
         * https://github.com/gradle/gradle/issues/6168
         * <grv87 2018-08-01>
         */
        javadoc.destinationDir.deleteDir()
        javadoc.options { StandardJavadocDocletOptions options ->
          javadoc.project.extensions.getByType(JVMBaseExtension).javadocLinks.values().each { URI link ->
            options.links link.toString()
          }
        }
      }
    }
  }

  /**
   * Name of FindBugs common task
   */
  public static final String FINDBUGS_TASK_NAME = 'findbugs'

  /**
   * Name of JDepend common task
   */
  public static final String JDEPEND_TASK_NAME = 'jdepend'

  private void configureCodeQuality() {
    project.plugins.getPlugin(ProjectPlugin).addCodeQualityCommonTask 'FindBugs', FINDBUGS_TASK_NAME, FindBugs
    project.plugins.getPlugin(ProjectPlugin).addCodeQualityCommonTask 'JDepend', JDEPEND_TASK_NAME, JDepend
  }
}
