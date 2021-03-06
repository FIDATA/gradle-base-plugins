#!/usr/bin/env groovy
/*
 * org.fidata.project Gradle plugin
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
package org.fidata.gradle

import static com.dorongold.gradle.tasktree.TaskTreePlugin.TASK_TREE_TASK_NAME
import static org.ajoberstar.gradle.git.release.base.BaseReleasePlugin.RELEASE_TASK_NAME
import static org.fidata.gpg.GpgUtils.getGpgHome
import static org.fidata.utils.VersionUtils.SNAPSHOT_SUFFIX
import static org.fidata.utils.VersionUtils.isPreReleaseVersion
import static org.gradle.api.Project.DEFAULT_BUILD_DIR_NAME
import static org.gradle.api.plugins.ProjectReportsPlugin.PROJECT_REPORT
import static org.gradle.initialization.DefaultSettings.DEFAULT_BUILD_SRC_DIR
import static org.gradle.internal.FileUtils.toSafeFileName
import static org.gradle.language.base.plugins.LifecycleBasePlugin.ASSEMBLE_TASK_NAME
import static org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
import com.dorongold.gradle.tasktree.TaskTreeTask
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.zafarkhaja.semver.ParseException
import com.github.zafarkhaja.semver.Version
import com.google.common.collect.ImmutableSet
/*
 * WORKAROUND:
 * cz.malohlava plugin doesn't work with Gradle 5
 * https://github.com/mmalohlava/gradle-visteg/issues/12
 * <grv87 2018-12-01>
 */
// import cz.malohlava.VisTegPluginExtension
import de.gliderpilot.gradle.semanticrelease.SemanticReleasePluginExtension
import de.gliderpilot.gradle.semanticrelease.UpdateGithubRelease
import groovy.text.StreamingTemplateEngine
import groovy.text.Template
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import org.ajoberstar.gradle.git.publish.GitPublishExtension
import org.ajoberstar.grgit.Grgit
import org.ajoberstar.grgit.auth.AuthConfig
import org.fidata.gradle.internal.AbstractProjectPlugin
import org.fidata.gradle.tasks.CodeNarcTaskConvention
import org.fidata.gradle.tasks.InputsOutputs
import org.fidata.gradle.tasks.NoJekyll
import org.fidata.gradle.tasks.ResignGitCommit
import org.fidata.gradle.utils.PathDirector
import org.fidata.gradle.utils.PluginDependeesUtils
import org.fidata.gradle.utils.ReportPathDirectorException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileTreeElement
import org.gradle.api.plugins.ProjectReportsPluginConvention
import org.gradle.api.plugins.quality.CodeNarc
import org.gradle.api.plugins.quality.CodeNarcExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.reporting.components.ComponentReport
import org.gradle.api.reporting.dependencies.HtmlDependencyReportTask
import org.gradle.api.reporting.dependents.DependentComponentsReport
import org.gradle.api.reporting.model.ModelReport
import org.gradle.api.tasks.TaskCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.diagnostics.BuildEnvironmentReportTask
import org.gradle.api.tasks.diagnostics.DependencyInsightReportTask
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import org.gradle.api.tasks.diagnostics.ProjectReportTask
import org.gradle.api.tasks.diagnostics.PropertyReportTask
import org.gradle.api.tasks.diagnostics.TaskReportTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.util.GradleVersion
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention

/**
 * Provides an environment for a general, language-agnostic project
 */
@CompileStatic
final class ProjectPlugin extends AbstractProjectPlugin {
  /**
   * Name of fidata.root convention for {@link Project}
   */
  public static final String FIDATA_ROOT_CONVENTION_NAME = 'fidata.root'

  /**
   * Name of fidata convention for {@link Project}
   */
  public static final String FIDATA_CONVENTION_NAME = 'fidata'

  static final Template COMMIT_MESSAGE_TEMPLATE = new StreamingTemplateEngine().createTemplate(
    '''
      $type: $subject

      Generated by $generatedBy
    '''.stripIndent()
  )

  /**
   * List of filenames considered as license files
   */
  public static final Set<String> LICENSE_FILE_NAMES = ImmutableSet.of(
    // License file names recognized by JFrog Artifactory
    'license',
    'LICENSE',
    'license.txt',
    'LICENSE.txt',
    'LICENSE.TXT',
    // GPL standard file names
    'COPYING',
    'COPYING.LESSER',
  )

  /**
   * Minimum supported version of Gradle
   */
  public static final String GRADLE_MINIMUM_SUPPORTED_VERSION = '5.1'

  public static final String DEFAULT_PROJECT_GROUP = 'org.fidata'

  @PackageScope
  String defaultProjectGroup = DEFAULT_PROJECT_GROUP

  private boolean isBuildSrc

  @Override
  @SuppressWarnings('CouldBeElvis')
  protected void doApply() {
    if (GradleVersion.current() < GradleVersion.version(GRADLE_MINIMUM_SUPPORTED_VERSION)) {
      throw new UnsupportedVersionException("Gradle versions before $GRADLE_MINIMUM_SUPPORTED_VERSION are not supported")
    }

    RootProjectConvention rootProjectConvention
    if (project == project.rootProject) {
      rootProjectConvention = new RootProjectConvention(project)
      project.convention.plugins.put FIDATA_ROOT_CONVENTION_NAME, rootProjectConvention
    } else {
      rootProjectConvention = project.rootProject.convention.getPlugin(RootProjectConvention)
    }
    isBuildSrc = rootProjectConvention.isBuildSrc

    PluginDependeesUtils.applyPlugins project, isBuildSrc, ProjectPluginDependees.PLUGIN_DEPENDEES
    project.pluginManager.apply 'org.fidata.dependencies'

    project.convention.plugins.put FIDATA_CONVENTION_NAME, new ProjectConvention(project)

    if (!project.group) { project.group = "${ -> defaultProjectGroup }" }

    project.extensions.configure(ReportingExtension) { ReportingExtension extension ->
      extension.baseDir = project.convention.getPlugin(ProjectConvention).reportsDir
    }
    // project.extensions.getByType(ReportingExtension).baseDir = project.convention.getPlugin(ProjectConvention).reportsDir

    if (!isBuildSrc && project == project.rootProject) {
      configureGit()
    }

    configureLifecycle()

    configurePrerequisitesLifecycle()

    configureDependencyResolution()

    if (!isBuildSrc) {
      configureDocumentation()
    }

    configureCodeQuality()

    configureDiagnostics()

    if (!isBuildSrc) {
      configureArtifacts()

      configureReleases()
    }

    project.subprojects { Project subproject ->
      subproject.pluginManager.apply ProjectPlugin
    }
  }

  private void configureGit() {
    System.setProperty AuthConfig.USERNAME_OPTION, project./*rootProject.*/extensions.extraProperties['gitUsername'].toString()
    System.setProperty AuthConfig.PASSWORD_OPTION, project./*rootProject.*/extensions.extraProperties['gitPassword'].toString()
  }

  /**
   * Release task group name
   */
  public static final String RELEASE_TASK_GROUP_NAME = 'Release'

  /**
   * Name of property determining whether to release a new version
   */
  public static final String SHOULD_RELEASE_PROPERTY_NAME = 'shouldRelease'

  private static final String MASTER_BRANCH_PATTERN = /^master$/

  private void configureLifecycle() {
    boolean isBuildSrc = project.rootProject.convention.getPlugin(RootProjectConvention).isBuildSrc
    if (!isBuildSrc) {
      if (project == project.rootProject) {
        project./*rootProject.*/ tasks.named(RELEASE_TASK_NAME).configure { Task release ->
          release.group = RELEASE_TASK_GROUP_NAME
        }
      }
      project.rootProject.tasks.named(RELEASE_TASK_NAME).configure { Task release ->
        release.with {
          dependsOn project.tasks.named(ASSEMBLE_TASK_NAME)
          dependsOn project.tasks.named(CHECK_TASK_NAME)
        }
      }
    }
    project.tasks.named(CHECK_TASK_NAME).configure { Task check ->
      check.dependsOn check.project.tasks.withType(Test)
    }

    if (!isBuildSrc && project == project.rootProject) {
      project.extensions.getByType(SemanticReleasePluginExtension).with {
        if (!project.rootProject.extensions.extraProperties.has(SHOULD_RELEASE_PROPERTY_NAME) || !project.rootProject.extensions.extraProperties[SHOULD_RELEASE_PROPERTY_NAME].toString().toBoolean()) {
          releaseBranches.with {
            /*
             * WORKAROUND:
             * semantic-release plugin handles excludes in twisted way.
             * See https://github.com/FIDATA/gradle-semantic-release-plugin/issues/28
             * <grv87 2018-10-27>
             */
            includes = [null].toSet()
            exclude MASTER_BRANCH_PATTERN
          }
        }
        branchNames.with {
          replace MASTER_BRANCH_PATTERN, ''
          // TODO: Support other CIs
          if (System.getenv('CHANGE_ID') != null) {
            replace(/^HEAD$/, System.getenv('BRANCH_NAME'))
          }
        }
      }

      project.tasks.withType(UpdateGithubRelease).named('updateGithubRelease').configure { UpdateGithubRelease updateGithubRelease ->
        updateGithubRelease.repo.ghToken = project./*rootProject.*/extensions.extraProperties['ghToken'].toString()
      }
    }
  }

  private void configurePrerequisitesLifecycle() {
    project.tasks.withType(DependencyUpdatesTask).configureEach { DependencyUpdatesTask dependencyUpdates ->
      dependencyUpdates.group = null
      dependencyUpdates.revision = 'release'
      dependencyUpdates.outputFormatter = 'xml'
      dependencyUpdates.outputDir = project.convention.getPlugin(ProjectConvention).getXmlReportDir(Paths.get('dependencyUpdates')).toString()
      dependencyUpdates.resolutionStrategy = { ResolutionStrategy resolutionStrategy ->
        resolutionStrategy.componentSelection.all { ComponentSelection selection ->
          if (dependencyUpdates.revision == 'release' && isPreReleaseVersion(selection.candidate.version)) {
            selection.reject 'Pre-release version'
          }
        }
      }
    }

    project.tasks.withType(Wrapper).configureEach { Wrapper wrapper ->
      wrapper.with {
        if (name == 'wrapper') {
          gradleVersion = '5.3.1'
        }
      }
    }
  }

  /**
   * URL of FIDATA Artifactory
   */
  public static final String ARTIFACTORY_URL = 'https://fidata.jfrog.io/fidata'

  /**
   * List of patters of environment variables
   * excluded from build info
   */
  /*
   * TODO:
   * Move these filters into separate library
   * <grv87 2018-09-22>
   */
  public static final Set<String> BUILD_INFO_ENV_VARS_EXCLUDE_PATTERS = ImmutableSet.of(
    '*Password',
    '*Passphrase',
    '*SecretKey',
    '*SECRET_KEY',
    '*APIKey',
    '*_API_KEY',
    '*gradlePluginsKey',
    '*gradlePluginsSecret',
    '*OAuthClientSecret',
    '*Token',
  )

  private void configureDependencyResolution() {
    RootProjectConvention rootProjectConvention = project.rootProject.convention.getPlugin(RootProjectConvention)
    project.repositories.maven { MavenArtifactRepository mavenArtifactRepository ->
      mavenArtifactRepository.with {
        /*
         * WORKAROUND:
         * Groovy bug?
         * When GString is used, URI property setter is called anyway, and we got cast error
         * <grv87 2018-06-26>
         */
        url = project.uri("$ARTIFACTORY_URL/libs-${ !rootProjectConvention.isBuildSrc && rootProjectConvention.isRelease.get() ? 'release' : 'snapshot' }/")
        credentials.username = project.rootProject.extensions.extraProperties['artifactoryUser'].toString()
        credentials.password = project.rootProject.extensions.extraProperties['artifactoryPassword'].toString()
      }
    }
  }

  /**
   * Name of NoJekyll task
   */
  public static final String NO_JEKYLL_TASK_NAME = 'noJekyll'

  private void configureDocumentation() {
    if (project == project.rootProject) {
      project.extensions.configure(GitPublishExtension) { GitPublishExtension extension ->
        extension.with {
          branch.set 'gh-pages'
          preserve.include '**'
          /*
           * CAVEAT:
           * SNAPSHOT documentation for other branches should be removed manually
           */
          preserve.exclude { FileTreeElement fileTreeElement ->
            Matcher m = SNAPSHOT_SUFFIX.matcher(fileTreeElement.relativePath.segments[0])
            if (!m) {
              return false
            }
            String dirVersion = m.replaceFirst('')
            String projectVersion = project.version.toString() - SNAPSHOT_SUFFIX
            try {
              return Version.valueOf(dirVersion).preReleaseVersion == Version.valueOf(projectVersion).preReleaseVersion
            } catch (IllegalArgumentException | ParseException e) {
              /*
               * These exceptions caught mean that the directory name is not a valid semver version.
               * So, we don't exclude such directory from preserves (in other words, it is preserved)
               */
              return false
            }
          }
          commitMessage.set COMMIT_MESSAGE_TEMPLATE.make(
            type: 'docs',
            subject: "publish documentation for version ${ project.version }",
            generatedBy: 'org.ajoberstar:gradle-git-publish'
          ).toString()
        }
      }

      boolean repoClean = ((Grgit) project./*rootProject.*/extensions.extraProperties.get('grgit')).status().clean

      TaskProvider<Task> gitPublishCommitProvider = project.tasks.named(/* WORKAROUND: GitPublishPlugin.COMMIT_TASK has package scope <grv87 2018-06-23> */ 'gitPublishCommit')
      TaskProvider<NoJekyll> noJekyllProvider = project.tasks.register(NO_JEKYLL_TASK_NAME, NoJekyll) { NoJekyll noJekyll ->
        noJekyll.with {
          description = 'Generates .nojekyll file in gitPublish repository'
          destinationDir.set project.extensions.getByType(GitPublishExtension).repoDir
        }
        /*
         * WORKAROUND:
         * Without that we get error:
         * [Static type checking] - Cannot call <T extends org.gradle.api.Task>
         * org.gradle.api.tasks.TaskContainer#register(java.lang.String, java.lang.Class <T>, org.gradle.api.Action
         * <java.lang.Object extends java.lang.Object>) with arguments [java.lang.String, java.lang.Class
         * <org.fidata.gradle.tasks.NoJekyll>, groovy.lang.Closure <java.io.File>]
         * <grv87 2018-07-31>
         */
        null
      }
      /*
       * WORKAROUND:
       * JGit doesn't support signed commits yet.
       * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=382212
       * <grv87 2018-06-22>
       */
      ResignGitCommit.registerTask(project, gitPublishCommitProvider) { ResignGitCommit resignGitPublishCommit ->
        resignGitPublishCommit.with {
          enabled = repoClean
          description = 'Amend git publish commit adding sign to it'
          workingDir.set project.extensions.getByType(GitPublishExtension).repoDir
          onlyIf { gitPublishCommitProvider.get().didWork }
        }
        /*
         * WORKAROUND:
         * Without that we get error:
         * [Static type checking] - Cannot call <T extends org.gradle.api.Task>
         * org.gradle.api.tasks.TaskContainer#register(java.lang.String, java.lang.Class <T>, org.gradle.api.Action
         * <java.lang.Object extends java.lang.Object>) with arguments [groovy.lang.GString, java.lang.Class
         * <org.fidata.gradle.tasks.ResignGitCommit>, groovy.lang.Closure <java.lang.Void>]
         * <grv87 2018-07-31>
         */
        null
      }
      gitPublishCommitProvider.configure { Task gitPublishCommit ->
        gitPublishCommit.with {
          enabled = repoClean
          dependsOn noJekyllProvider
        }
      }

      TaskProvider<Task> gitPublishPushProvider = project.tasks.named(/* WORKAROUND: GitPublishPlugin.PUSH_TASK has package scope <grv87 2018-06-23> */ 'gitPublishPush')
      gitPublishPushProvider.configure { Task gitPublishPush ->
        gitPublishPush.enabled = repoClean
      }
      project.tasks.named(RELEASE_TASK_NAME).configure { Task release ->
        release.dependsOn gitPublishPushProvider
      }
    }
  }

  /**
   * Name of lint task
   */
  public static final String LINT_TASK_NAME = 'lint'

  /**
   * Name of PMD common task
   */
  public static final String PMD_TASK_NAME = 'pmd'

  /**
   * Name of CodeNarc common task
   */
  public static final String CODENARC_TASK_NAME = 'codenarc'

  /**
   * Name of disabledRules convention for {@link CodeNarc} tasks
   */
  public static final String CODENARC_DISABLED_RULES_CONVENTION_NAME = 'disabledRules'

  /**
   * Path director for codenarc reports
   */
  static final PathDirector<CodeNarc> CODENARC_REPORT_DIRECTOR = new PathDirector<CodeNarc>() {
    @Override
    Path determinePath(CodeNarc object)  {
      try {
        Paths.get(toSafeFileName((object.name - ~/^codenarc/ /* WORKAROUND: CodeNarcPlugin.getTaskBaseName has protected scope <grv87 2018-06-23> */).uncapitalize()))
      } catch (InvalidPathException e) {
        throw new ReportPathDirectorException(object, e)
      }
    }
  }

  @PackageScope
  void addCodeQualityCommonTask(String toolName, String commonTaskName, Class<? extends Task> taskClass) {
    TaskCollection<? extends Task> tasks = project.tasks.withType(taskClass)

    TaskProvider<Task> commonTaskProvider = project.tasks.register(commonTaskName) { Task commonTask ->
      commonTask.with {
        group = VERIFICATION_GROUP
        description = "Runs $toolName analysis for all source sets"
        dependsOn tasks
      }
    }
    project.tasks.named(LINT_TASK_NAME).configure { Task lint ->
      lint.dependsOn commonTaskProvider
    }
  }

  /*
   * WORKAROUND:
   * Groovy bug. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings('UnnecessarySetter')
  private void configureCodeQuality() {
    TaskProvider<Task> lintProvider = project.tasks.register(LINT_TASK_NAME) { Task lint ->
      lint.with {
        group = VERIFICATION_GROUP
        description = 'Runs all static code analyses'
      }
    }
    TaskProvider<Task> checkProvider = project.tasks.named(CHECK_TASK_NAME)
    checkProvider.configure { Task check ->
      check.dependsOn lintProvider
    }

    addCodeQualityCommonTask 'CodeNarc', CODENARC_TASK_NAME, CodeNarc
    addCodeQualityCommonTask 'PMD', PMD_TASK_NAME, Pmd

    project.extensions.configure(CodeNarcExtension) { CodeNarcExtension extension ->
      extension.with {
        toolVersion = '[1.3, 2['
        reportFormat = 'console'
      }
    }

    project.dependencies.add(/* WORKAROUND: CodeNarcPlugin.getConfigurationName has protected scope <grv87 2018-08-23> */ 'codenarc', [
      group: 'org.codenarc',
      name: 'CodeNarc',
      version: '[1, 2['
    ])

    ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
    project.tasks.withType(CodeNarc).configureEach { CodeNarc codenarc ->
      codenarc.with {
        convention.plugins.put CODENARC_DISABLED_RULES_CONVENTION_NAME, new CodeNarcTaskConvention(codenarc)
        Path reportSubpath = Paths.get('codenarc')
        reports.xml.enabled = true
        reports.xml.setDestination projectConvention.getXmlReportFile(reportSubpath, CODENARC_REPORT_DIRECTOR, codenarc)
        reports.html.enabled = true
        reports.html.setDestination projectConvention.getHtmlReportFile(reportSubpath, CODENARC_REPORT_DIRECTOR, codenarc)
      }
    }

    if (!isBuildSrc) {
      project.tasks.register("${ /* WORKAROUND: CodeNarcPlugin.getTaskBaseName has protected scope <grv87 2018-06-23> */ 'codenarc' }${ DEFAULT_BUILD_SRC_DIR.capitalize() }", CodeNarc) { CodeNarc codenarc ->
        codenarc.with {
          source project.fileTree(dir: project.projectDir, includes: ['*.gradle'])
          source project.fileTree(dir: project.projectDir, includes: ['*.groovy'])
          source project.fileTree(dir: project.file('gradle'), includes: ['**/*.gradle'])
          source project.fileTree(dir: project.file('gradle'), includes: ['**/*.groovy'])
          source project.fileTree(dir: project.file('config'), includes: ['**/*.groovy'])
          if (project == project.rootProject) {
            Closure buildDirMatcher = { FileTreeElement fte ->
              String[] p = fte.relativePath.segments
              int i = 0
              while (i < p.length && p[i] == DEFAULT_BUILD_SRC_DIR) {
                i++
              }
              i < p.length && p[i] == DEFAULT_BUILD_DIR_NAME
            }
            /*
             * WORKAROUND:
             * We have to pass to CodeNarc resolved fileTree, otherwise we get the following error:
             * Cannot add include/exclude specs to Ant node. Only include/exclude patterns are currently supported.
             * This is not a problem since build sources can't change at build time,
             * and also this code is executed only when codenarcBuildSrc task is actually created (i.e. run)
             * <grv87 2018-08-22>
             */
            source project.fileTree(DEFAULT_BUILD_SRC_DIR) { ConfigurableFileTree fileTree ->
              fileTree.include '**/*.gradle'
              fileTree.include '**/*.groovy'
              fileTree.exclude buildDirMatcher
            }.files
            source 'Jenkinsfile'
          }
        }
      }
    }
  }

  /**
   * Name of Diagnostics task group
   */
  public static final String DIAGNOSTICS_TASK_GROUP_NAME = 'Diagnostics'

  /**
   * Name of InputsOutputs task
   */
  public static final String INPUTS_OUTPUTS_TASK_NAME = 'inputsOutputs'

  /*
   * WORKAROUND:
   * Groovy error. Usage of `destination =` instead of setDestination leads to error:
   * [Static type checking] - Cannot set read-only property: destination
   * Also may be CodeNarc error
   * <grv87 2018-06-26>
   */
  @SuppressWarnings('UnnecessarySetter')
  private void configureDiagnostics() {
    ProjectConvention projectConvention = project.convention.getPlugin(ProjectConvention)
    project.convention.getPlugin(ProjectReportsPluginConvention).projectReportDirName = projectConvention.getTxtReportDir(Paths.get('project')).toString()

    project.tasks.withType(BuildEnvironmentReportTask).configureEach { BuildEnvironmentReportTask buildEnvironmentReport ->
      buildEnvironmentReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ComponentReport).configureEach { ComponentReport componentReport ->
      componentReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependencyReportTask).configureEach { DependencyReportTask dependencyReport ->
      dependencyReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependencyInsightReportTask).configureEach { DependencyInsightReportTask dependencyInsightReport ->
      dependencyInsightReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(DependentComponentsReport).configureEach { DependentComponentsReport dependentComponentsReport ->
      dependentComponentsReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ModelReport).configureEach { ModelReport modelReport ->
      modelReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(ProjectReportTask).configureEach { ProjectReportTask projectReport ->
      projectReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(PropertyReportTask).configureEach { PropertyReportTask propertyReport ->
      propertyReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.withType(HtmlDependencyReportTask).configureEach { HtmlDependencyReportTask htmlDependencyReport ->
      htmlDependencyReport.with {
        group = DIAGNOSTICS_TASK_GROUP_NAME
        reports.html.setDestination projectConvention.getHtmlReportDir(Paths.get('dependencies'))
      }
    }
    project.tasks.withType(TaskReportTask).configureEach { TaskReportTask taskReport ->
      taskReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }
    project.tasks.named(PROJECT_REPORT).configure { Task projectReport ->
      projectReport.group = DIAGNOSTICS_TASK_GROUP_NAME
    }

    project.tasks.register(INPUTS_OUTPUTS_TASK_NAME, InputsOutputs) { InputsOutputs inputsOutputs ->
      inputsOutputs.with {
        group = DIAGNOSTICS_TASK_GROUP_NAME
        description = 'Generates report about all task file inputs and outputs'
        outputFile.set new File(projectConvention.txtReportsDir, DEFAULT_OUTPUT_FILE_NAME)
      }
    }

    project.tasks.withType(TaskTreeTask).named(TASK_TREE_TASK_NAME).configure { TaskTreeTask taskTree ->
      taskTree.group = DIAGNOSTICS_TASK_GROUP_NAME
    }

    /*
     * WORKAROUND:
     * cz.malohlava plugin doesn't work with Gradle 5
     * https://github.com/mmalohlava/gradle-visteg/issues/12
     * <grv87 2018-12-01>
     */
    /*project.extensions.configure(VisTegPluginExtension) { VisTegPluginExtension extension ->
      extension.with {
        enabled        = (project.logging.level ?: project.gradle.startParameter.logLevel) <= LogLevel.INFO
        colouredNodes  = true
        colouredEdges  = true
        destination    = new File(projectConvention.reportsDir, 'visteg.dot').toString()
        exporter       = 'dot'
        colorscheme    = 'paired12'
        nodeShape      = 'box'
        startNodeShape = 'hexagon'
        endNodeShape   = 'doubleoctagon'
      }
    }*/
  }

  // TODO: CodeNarc bug
  @SuppressWarnings('UnnecessaryGetter')
  private void configureArtifacts() {
    /*
     * WORKAROUND:
     * https://github.com/gradle/gradle/issues/1918
     * Signing plugin doesn't support GPG 2 key IDs
     * <grv87 2018-07-01>
     */
    project.extensions.extraProperties['signing.keyId'] = project.rootProject.extensions.extraProperties['gpgKeyId'].toString()[-8..-1]
    project.extensions.extraProperties['signing.password'] = project.rootProject.extensions.extraProperties.has('gpgKeyPassphrase') ? project.rootProject.extensions.extraProperties['gpgKeyPassphrase'] : null
    project.extensions.extraProperties['signing.secretKeyRingFile'] = getGpgHome().resolve('secring.gpg')

    project.extensions.extraProperties['signing.gnupg.executable'] = 'gpg'
    project.extensions.extraProperties['signing.gnupg.keyName'] = project.rootProject.extensions.extraProperties['gpgKeyId']
    project.extensions.extraProperties['signing.gnupg.passphrase'] = project.rootProject.extensions.extraProperties.has('gpgKeyPassphrase') ? project.rootProject.extensions.extraProperties['gpgKeyPassphrase'] : null
    String gnupgHome = System.getenv('GNUPGHOME')
    if (gnupgHome != null) {
      project.extensions.extraProperties['signing.gnupg.homeDir'] = gnupgHome
    }
  }

  /**
   * Name of generateChangelog task
   */
  public static final String GENERATE_CHANGELOG_TASK_NAME = 'generateChangelog'

  /**
   * generateChangelog output file name
   */
  public static final String GENERATE_CHANGELOG_OUTPUT_FILE_NAME = 'CHANGELOG.md'

  /**
   * Name of generateChangelogTxt task
   */
  public static final String GENERATE_CHANGELOG_TXT_TASK_NAME = 'generateChangelogTxt'

  /**
   * generateChangelogTxt output file name
   */
  public static final String GENERATE_CHANGELOG_TXT_OUTPUT_FILE_NAME = 'CHANGELOG.txt'

  @SuppressWarnings(['FactoryMethodName', 'BuilderMethodWithSideEffects'])
  private void createGenerateChangelogTasks() {
    Path changelogOutputDir = project.buildDir.toPath().resolve('changelog')
    RootProjectConvention rootProjectConvention = project.rootProject.convention.getPlugin(RootProjectConvention)
    project.tasks.register(GENERATE_CHANGELOG_TASK_NAME) { Task generateChangelog ->
      File outputFile = changelogOutputDir.resolve(GENERATE_CHANGELOG_OUTPUT_FILE_NAME).toFile()
      String changeLog = rootProjectConvention.changeLog.get()
      generateChangelog.with {
        inputs.property 'changeLog', changeLog
        doLast {
          outputFile.text = changeLog
        }
        outputs.file outputFile
      }
    }
    project.tasks.register(GENERATE_CHANGELOG_TXT_TASK_NAME) { Task generateChangelogTxt ->
      File outputFile = changelogOutputDir.resolve(GENERATE_CHANGELOG_TXT_OUTPUT_FILE_NAME).toFile()
      String changeLog = rootProjectConvention.changeLogTxt.get()
      generateChangelogTxt.with {
        inputs.property 'changeLog', changeLog
        doLast {
          outputFile.text = changeLog
        }
        outputs.file outputFile
      }
    }
  }

  private void configureArtifactory() {
    project.convention.getPlugin(ArtifactoryPluginConvention).with {
      contextUrl = ARTIFACTORY_URL
      clientConfig.with {
        includeEnvVars = Boolean.TRUE
        envVarsExcludePatterns = BUILD_INFO_ENV_VARS_EXCLUDE_PATTERS.join(',')
      }
    }
  }

  private void configureReleases() {
    if (project == project.rootProject) {
      createGenerateChangelogTasks()
    }

    configureArtifactory()
  }
}
