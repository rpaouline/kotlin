/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cpp

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.utils.Maybe
import org.jetbrains.kotlin.utils.asMaybe
import org.jetbrains.kotlin.utils.capitalized
import javax.inject.Inject

/**
 * Generating [JSON Compilation Database](https://clang.llvm.org/docs/JSONCompilationDatabase.html).
 *
 * Allows generating compilation databases for different targets. For example:
 * ```
 * compilationDatabase {
 *    target(someTarget1) {
 *        entry { ... }
 *    }
 *    allTargets {}
 * }
 *
 * dependencies {
 *     compilationDatabase.configuration(project(":some:other:project"))
 * }
 * ```
 * Adds an entry for target `someTarget1`, and for each known target merges in databases from `:some:other:project`.
 * The task that generates the database can be found via `compilationDatabase.target(someTarget1).task`.
 *
 * @see CompilationDatabasePlugin gradle plugin that creates this extension.
 */
abstract class CompilationDatabaseExtension @Inject constructor(private val project: Project) : TargetDomainObjectContainer<CompilationDatabaseExtension.Target>(project) {
    init {
        this.factory = { target, sanitizer ->
            project.objects.newInstance<Target>(this, target, sanitizer.asMaybe)
        }

        project.dependencies.attributesSchema {
            attribute(TargetAttribute.TARGET_ATTRIBUTE)
        }
    }

    private val outgoingConfiguration = project.configurations.create("generateCompilationDatabase") {
        description = "Generate Compilation Database"
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, USAGE))
        }
    }

    private val incomingConfiguration = project.configurations.create(CONFIGURATION_NAME) {
        description = "Compilation Database"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, USAGE))
        }
    }

    /**
     * Configuration to attach dependencies to.
     *
     * All dependencies will automatically be merged into the resulting database.
     */
    val configuration
        get() = incomingConfiguration

    /**
     * Entries in the compilation database.
     *
     * Single [Entry] generates a number of compilation database entries: one for each file in [files].
     *
     * @property target target for which this [Entry] is generated.
     * @property sanitizer optional sanitizer for [target].
     */
    abstract class Entry @Inject constructor(
            val target: KonanTarget,
            _sanitizer: Maybe<SanitizerKind>,
    ) {
        val sanitizer = _sanitizer.orNull

        /**
         * **directory** from the [JSON Compilation Database](https://clang.llvm.org/docs/JSONCompilationDatabase.html#format).
         *
         * The working directory of the compilation. All paths in the [arguments] must either be absolute or relative to this directory.
         */
        abstract val directory: DirectoryProperty

        /**
         * **file** from the [JSON Compilation Database](https://clang.llvm.org/docs/JSONCompilationDatabase.html#format).
         *
         * Collection of files being compiled with given [arguments]. For each file a separate
         * entry will be generated in the database with the same [directory], [arguments] and [output].
         */
        abstract val files: ConfigurableFileCollection

        /**
         * **arguments** from the [JSON Compilation Database](https://clang.llvm.org/docs/JSONCompilationDatabase.html#format).
         *
         * List of arguments of the compilation commands. The first argument must be the executable.
         */
        abstract val arguments: ListProperty<String>

        /**
         * **output** from the [JSON Compilation Database](https://clang.llvm.org/docs/JSONCompilationDatabase.html#format).
         *
         * The name of the output created by the compilation step. Used as a key to distinguish
         * between different modes of compilation of the same sources.
         */
        abstract val output: Property<String>
    }

    /**
     * Configure compilation database generation for [target].
     *
     * [entry] to add new entries.
     * [task] is the gradle task for compilation database generation.
     *
     * @property target target for which compilation database is generated.
     * @property sanitizer optional sanitizer for which compilation database is generated.
     */
    abstract class Target @Inject constructor(
            private val owner: CompilationDatabaseExtension,
            val target: KonanTarget,
            _sanitizer: Maybe<SanitizerKind>,
    ) {
        val sanitizer = _sanitizer.orNull

        private val project by owner::project

        protected abstract val entries: ListProperty<GenerateCompilationDatabase.Entry>

        /**
         * Add an entry to the compilation database for [target] with optional [sanitizer].
         *
         * @param action configure [Entry]
         */
        fun entry(action: Action<in Entry>) {
            entries.add(project.provider {
                val instance = project.objects.newInstance<Entry>(target, sanitizer.asMaybe).apply {
                    action.execute(this)
                }
                project.objects.newInstance<GenerateCompilationDatabase.Entry>().apply {
                    directory.set(instance.directory)
                    files.from(instance.files)
                    arguments.set(instance.arguments)
                    output.set(instance.output)
                }
            })
        }

        /**
         * Gradle task that generates compilation database for [target] with optional [sanitizer].
         */
        val task = project.tasks.register<GenerateCompilationDatabase>("compilationDatabase${target.name.capitalized}${sanitizer.targetSuffix}") {
            description = "Generate compilation database for $target${sanitizer.targetSuffix}"
            group = TASK_GROUP
            mergeFiles.from(owner.incomingConfiguration.incoming.artifactView {
                attributes {
                    attribute(TargetAttribute.TARGET_ATTRIBUTE, project.objects.targetAttribute(target, sanitizer))
                }
            }.files)
            entries.set(this@Target.entries)
            outputFile.set(project.layout.buildDirectory.file("${target}${sanitizer.targetSuffix}/compile_commands.json"))
        }

        init {
            owner.outgoingConfiguration.outgoing {
                variants {
                    create("${target}${sanitizer.targetSuffix}") {
                        attributes {
                            attribute(TargetAttribute.TARGET_ATTRIBUTE, project.objects.targetAttribute(target, sanitizer))
                        }
                        artifact(task)
                    }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        val TASK_GROUP = "development support"

        @JvmStatic
        val USAGE = "compilationDatabase"

        @JvmStatic
        val CONFIGURATION_NAME = "compilationDatabase"
    }
}

/**
 * Generating [JSON Compilation Database](https://clang.llvm.org/docs/JSONCompilationDatabase.html).
 *
 * Creates [CompilationDatabaseExtension] extension named `compilationDatabase`.
 *
 * @see CompilationDatabaseExtension extension that this plugin creates.
 */
open class CompilationDatabasePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create<CompilationDatabaseExtension>("compilationDatabase", project)
    }
}
