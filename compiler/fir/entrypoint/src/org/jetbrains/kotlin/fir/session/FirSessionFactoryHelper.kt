/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.session

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectEnvironment
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.incremental.components.EnumWhenTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices

object FirSessionFactoryHelper {
    inline fun createCommonOrJvmSessionWithDependencies(
        moduleName: Name,
        platform: TargetPlatform,
        analyzerServices: PlatformDependentAnalyzerServices,
        externalSessionProvider: FirProjectSessionProvider?,
        projectEnvironment: AbstractProjectEnvironment,
        languageVersionSettings: LanguageVersionSettings,
        javaSourcesScope: AbstractProjectFileSearchScope,
        librariesScope: AbstractProjectFileSearchScope,
        lookupTracker: LookupTracker?,
        enumWhenTracker: EnumWhenTracker?,
        incrementalCompilationContext: IncrementalCompilationContext?,
        extensionRegistrars: List<FirExtensionRegistrar>,
        needRegisterJavaElementFinder: Boolean,
        dependenciesConfigurator: DependencyListForCliModule.Builder.() -> Unit = {},
        noinline sessionConfigurator: FirSessionConfigurator.() -> Unit = {},
        isCommonSession: Boolean = false,
        useDependentLibraryProviders: Boolean = false
    ): FirSession {
        val dependencyList = DependencyListForCliModule.build(moduleName, platform, analyzerServices, dependenciesConfigurator)
        val sessionProvider = externalSessionProvider ?: FirProjectSessionProvider()
        val packagePartProvider = projectEnvironment.getPackagePartProvider(librariesScope)
        if (isCommonSession) {
            FirCommonSessionFactory.createLibrarySession(
                moduleName,
                sessionProvider,
                dependencyList,
                projectEnvironment,
                librariesScope,
                packagePartProvider,
                languageVersionSettings,
                useDependentLibraryProviders = useDependentLibraryProviders
            )
        } else {
            FirJvmSessionFactory.createLibrarySession(
                moduleName,
                sessionProvider,
                dependencyList,
                projectEnvironment,
                librariesScope,
                packagePartProvider,
                languageVersionSettings,
                useDependentLibraryProviders = useDependentLibraryProviders
            )
        }

        val mainModuleData = FirModuleDataImpl(
            moduleName,
            dependencyList.regularDependencies,
            dependencyList.dependsOnDependencies,
            dependencyList.friendsDependencies,
            dependencyList.platform,
            dependencyList.analyzerServices
        )
        return if (isCommonSession) {
            FirCommonSessionFactory.createModuleBasedSession(
                mainModuleData,
                sessionProvider,
                projectEnvironment,
                incrementalCompilationContext,
                extensionRegistrars,
                languageVersionSettings,
                lookupTracker,
                enumWhenTracker,
                sessionConfigurator
            )
        } else {
            FirJvmSessionFactory.createModuleBasedSession(
                mainModuleData,
                sessionProvider,
                javaSourcesScope,
                projectEnvironment,
                incrementalCompilationContext,
                extensionRegistrars,
                languageVersionSettings,
                lookupTracker,
                enumWhenTracker,
                needRegisterJavaElementFinder,
                sessionConfigurator,
            )
        }
    }

    @OptIn(SessionConfiguration::class, PrivateSessionConstructor::class)
    @TestOnly
    fun createEmptySession(): FirSession {
        return object : FirSession(null, Kind.Source) {}.apply {
            val moduleData = FirModuleDataImpl(
                Name.identifier("<stub module>"),
                dependencies = emptyList(),
                dependsOnDependencies = emptyList(),
                friendDependencies = emptyList(),
                platform = JvmPlatforms.unspecifiedJvmPlatform,
                analyzerServices = JvmPlatformAnalyzerServices
            )
            registerModuleData(moduleData)
            moduleData.bindSession(this)
            // Empty stub for tests
            register(FirLanguageSettingsComponent::class, FirLanguageSettingsComponent(
                object : LanguageVersionSettings {

                    private fun stub(): Nothing = TODO(
                        "It does not yet have well-defined semantics for tests." +
                                "If you're seeing this, implement it in a test-specific way"
                    )

                    override fun getFeatureSupport(feature: LanguageFeature): LanguageFeature.State {
                        return LanguageFeature.State.DISABLED
                    }

                    override fun isPreRelease(): Boolean = stub()

                    override fun <T> getFlag(flag: AnalysisFlag<T>): T = stub()

                    override val apiVersion: ApiVersion
                        get() = stub()
                    override val languageVersion: LanguageVersion
                        get() = stub()
                }
            ))
        }
    }
}