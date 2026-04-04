/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.grails.buildsrc

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.CheckstylePlugin
import org.gradle.api.plugins.quality.CodeNarc
import org.gradle.api.plugins.quality.CodeNarcExtension
import org.gradle.api.plugins.quality.CodeNarcPlugin
import org.gradle.api.tasks.Copy
import org.gradle.process.ExecSpec
import org.apache.tools.ant.taskdefs.condition.Os

@CompileStatic
class GrailsCodeStylePlugin implements Plugin<Project> {

    static String CHECKSTYLE_DIR_PROPERTY = 'grails.codestyle.dir.checkstyle'
    static String CHECKSTYLE_CONFIG_FILE_NAME = 'checkstyle.xml'
    static String CHECKSTYLE_SUPPRESSION_CONFIG_FILE_NAME = 'checkstyle-suppressions.xml'

    static String CODENARC_DIR_PROPERTY = 'grails.codestyle.dir.codenarc'
    static String CODENARC_CONFIG_FILE_NAME = 'codenarc.groovy'

    static String BASE_RESOURCE_PATH = '/META-INF/org.apache.grails.buildsrc.codestyle'

    @Override
    void apply(Project project) {
        initExtension(project)
        configureCodeStyle(project)
        registerFormattingTasks(project)
        doNotApplyStylingToTests(project)
    }

    private static void initExtension(Project project) {
        def gce = project.extensions.create('grailsCodeStyle', GrailsCodeStyleExtension)

        // Unfortunately, the codenarc plugin is still using a non-lazy property.
        // Rather than rewrite the plugin to use afterEvaluate,
        // this plugin uses properties to override the configuration location by default


        gce.checkstyleDirectory.set(project.provider {
            def directory = project.hasProperty(CHECKSTYLE_DIR_PROPERTY) ?
                    project.rootProject.layout.projectDirectory.dir(project.property(CHECKSTYLE_DIR_PROPERTY) as String) :
                    project.rootProject.layout.buildDirectory.get().dir('codestyle').dir('checkstyle')

            def toCreate = directory.asFile.toPath()
            Files.createDirectories(toCreate)

            createOrLoad(
                    toCreate.resolve(CHECKSTYLE_CONFIG_FILE_NAME),
                    "${BASE_RESOURCE_PATH}/checkstyle/${CHECKSTYLE_CONFIG_FILE_NAME}"
            )
            createOrLoad(
                    toCreate.resolve(CHECKSTYLE_SUPPRESSION_CONFIG_FILE_NAME),
                    "${BASE_RESOURCE_PATH}/checkstyle/${CHECKSTYLE_SUPPRESSION_CONFIG_FILE_NAME}"
            )

            directory
        })

        gce.codenarcDirectory.set(project.provider {
            def directory = project.hasProperty(CODENARC_DIR_PROPERTY) ?
                    project.rootProject.layout.projectDirectory.dir(project.property(CODENARC_DIR_PROPERTY) as String) :
                    project.rootProject.layout.buildDirectory.get().dir('codestyle').dir('codenarc')

            def toCreate = directory.asFile.toPath()
            Files.createDirectories(toCreate)

            createOrLoad(
                    toCreate.resolve(CODENARC_CONFIG_FILE_NAME),
                    "${BASE_RESOURCE_PATH}/codenarc/${CODENARC_CONFIG_FILE_NAME}"
            )

            directory
        })
    }

    private static void createOrLoad(Path expectedPath, String defaultResource) {
        if (!Files.exists(expectedPath) || expectedPath.size() == 0) {
            def defaultValue = GrailsCodeStylePlugin.getResourceAsStream(defaultResource)
            if (!defaultValue) {
                throw new IllegalStateException("Could not locate default configuration file: ${defaultResource}")
            }
            expectedPath.text = defaultValue.text
        }
    }

    private static void doNotApplyStylingToTests(Project project) {
        if (project.tasks.names.contains('checkstyleTest')) {
            project.tasks.named('checkstyleTest') {
                it.enabled = false // Do not check test sources at this time
            }
        }

        project.afterEvaluate {
            // Do not check test sources at this time
            ['codenarcIntegrationTest', 'codenarcTest'].each { testTaskName ->
                if (project.tasks.names.contains(testTaskName)) {
                    project.tasks.named(testTaskName) {
                        it.enabled = false
                    }
                }
            }
        }
    }

    private static void configureCodeStyle(Project project) {
        configureCheckstyle(project)
        configureCodenarc(project)

        project.tasks.register('codeStyle') {
            it.group = 'verification'
            it.description = 'Runs code style checks'
            it.dependsOn(project.tasks.withType(Checkstyle))
            it.dependsOn(project.tasks.withType(CodeNarc))
        }
    }

    static void configureCheckstyle(Project project) {
        project.pluginManager.apply(CheckstylePlugin)

        project.extensions.configure(CheckstyleExtension) {
            // Explicit `it` is required in extension configuration
            it.getConfigDirectory().set(project.extensions.getByType(GrailsCodeStyleExtension).checkstyleDirectory)
            it.maxWarnings = 0
            it.showViolations = true
            it.ignoreFailures = false
            it.toolVersion = project.findProperty('checkstyleVersion')
        }

        project.tasks.withType(Checkstyle).configureEach { Checkstyle task ->
            task.group = 'verification'
            task.onlyIf { !project.hasProperty('skipCodeStyle') }

            // Redirect XML report output to a single directory to consolidate
            // reports across all subprojects into one known location.
            // Include the task name to avoid overlapping outputs when a project has
            // multiple source sets (e.g. grails-cache has ast + main).
            task.reports.xml.outputLocation.set(
                    project.extensions.getByType(GrailsCodeStyleExtension)
                            .reportsDirectory.get()
                            .dir('checkstyle')
                            .file("${project.name}-${task.name}.xml")
            )
        }
    }

    static void configureCodenarc(Project project) {
        project.pluginManager.apply(CodeNarcPlugin)

        project.extensions.configure(CodeNarcExtension) {
            it.configFile = project.extensions.getByType(GrailsCodeStyleExtension)
                    .codenarcDirectory.get().file(CODENARC_CONFIG_FILE_NAME).asFile
            it.toolVersion = project.findProperty('codenarcVersion')
        }

        project.tasks.withType(CodeNarc).configureEach { CodeNarc task ->
            task.group = 'verification'
            task.onlyIf { !project.hasProperty('skipCodeStyle') }

            // Redirect XML report output to a single directory to consolidate
            // reports across all subprojects into one known location.
            // Include the task name to avoid overlapping outputs when a project has
            // multiple source sets.
            task.reports.xml.required.set(true)
            task.reports.xml.outputLocation.set(
                    project.extensions.getByType(GrailsCodeStyleExtension)
                            .reportsDirectory.get()
                            .dir('codenarc')
                            .file("${project.name}-${task.name}.xml")
            )
        }
    }

    private static void registerFormattingTasks(Project project) {
        if (project == project.rootProject) {
            project.tasks.register('installGitHooks', Copy) {
                it.group = 'verification'
                it.description = 'Installs the git pre-commit hook for automatic code formatting'
                it.from(project.rootProject.layout.projectDirectory.file('etc/hooks/pre-commit'))
                it.into(project.rootProject.layout.projectDirectory.dir('.git/hooks'))
                it.fileMode = 0755
            }
        }

        project.tasks.register('formatCode') {
            it.group = 'verification'
            it.description = 'Formats Java and Groovy source files using the IntelliJ command line formatter'

            it.doLast {
                String ideaHome = (project.findProperty('idea.home') ?: System.getenv('IDEA_HOME')) as String
                String executable = Os.isFamily(Os.FAMILY_WINDOWS) ? 'format.bat' : 'format.sh'
                File formatExec = null

                if (ideaHome) {
                    formatExec = new File(ideaHome, "bin/$executable")
                } else {
                    // Try common paths on macOS
                    if (Os.isFamily(Os.FAMILY_MAC)) {
                        def commonPaths = [
                                "/Applications/IntelliJ IDEA.app/Contents/bin/$executable",
                                "/Applications/IntelliJ IDEA CE.app/Contents/bin/$executable"
                        ]
                        for (path in commonPaths) {
                            File f = new File(path)
                            if (f.exists()) {
                                formatExec = f
                                break
                            }
                        }
                    }

                    if (formatExec == null && !Os.isFamily(Os.FAMILY_WINDOWS)) {
                        // On Linux/Mac, try to find 'idea' in PATH
                        try {
                            def out = new ByteArrayOutputStream()
                            project.exec { ExecSpec exec ->
                                exec.commandLine 'which', 'idea'
                                exec.standardOutput = out
                                exec.ignoreExitValue = true
                            }
                            def path = out.toString().trim()
                            if (path) {
                                formatExec = new File(new File(path).parentFile, executable)
                            }
                        } catch (Exception ignored) { }
                    }
                }

                if (formatExec == null || !formatExec.exists()) {
                    project.logger.error("IntelliJ formatter executable not found.")
                    project.logger.error("Please set 'idea.home' property or IDEA_HOME environment variable to your IntelliJ installation directory.")
                    project.logger.error("Example: ./gradlew formatCode -Pidea.home=/Applications/IntelliJ\\ IDEA.app/Contents")
                    throw new RuntimeException("IntelliJ formatter executable not found.")
                }

                def filesToFormat = project.findProperty('formatFiles')
                def settingsFile = project.rootProject.file('.idea/codeStyles/Project.xml')

                if (!settingsFile.exists()) {
                    throw new RuntimeException("IntelliJ code style settings not found at ${settingsFile.absolutePath}")
                }

                project.exec { ExecSpec exec ->
                    exec.commandLine formatExec.absolutePath
                    exec.args '-s', settingsFile.absolutePath
                    exec.args '-mask', '*.java,*.groovy'
                    exec.args '-r'
                    if (filesToFormat) {
                        exec.args((filesToFormat.toString()).split(','))
                    } else {
                        exec.args project.projectDir.absolutePath
                    }
                }
            }
        }
    }
}
