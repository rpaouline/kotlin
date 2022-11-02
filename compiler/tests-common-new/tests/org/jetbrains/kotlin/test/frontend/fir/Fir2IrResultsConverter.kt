/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.backend.jvm.JvmIrDeserializerImpl
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendClassResolver
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendExtension
import org.jetbrains.kotlin.fir.backend.jvm.JvmFir2IrExtensions
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.ir.backend.jvm.serialization.JvmIrMangler
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.model.BackendKinds
import org.jetbrains.kotlin.test.model.Frontend2BackendConverter
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.dependencyProvider

class Fir2IrResultsConverter(
    testServices: TestServices
) : Frontend2BackendConverter<FirOutputArtifact, IrBackendInput>(
    testServices,
    FrontendKinds.FIR,
    BackendKinds.IrBackend
) {
    override fun transform(
        module: TestModule,
        inputArtifact: FirOutputArtifact
    ): IrBackendInput {
        val compilerConfigurationProvider = testServices.compilerConfigurationProvider
        val configuration = compilerConfigurationProvider.getCompilerConfiguration(module)

        val dependentComponents = mutableListOf<Fir2IrComponents>()
        if (module.languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects)) {
            val dependencyProvider = testServices.dependencyProvider

            for (dependency in module.dependsOnDependencies) {
                val testModule = dependencyProvider.getTestModule(dependency.moduleName)
                val artifact = dependencyProvider.getArtifact(testModule, BackendKinds.IrBackend)
                if (artifact is IrBackendInput.JvmIrBackendInput) {
                    artifact.components?.let { dependentComponents.add(it) }
                }
            }
        }

        val fir2IrExtensions = JvmFir2IrExtensions(configuration, JvmIrDeserializerImpl(), JvmIrMangler)
        val (irModuleFragment, components) = inputArtifact.firAnalyzerFacade.convertToIr(fir2IrExtensions, dependentComponents)
        val dummyBindingContext = NoScopeRecordCliBindingTrace().bindingContext

        val phaseConfig = configuration.get(CLIConfigurationKeys.PHASE_CONFIG)
        val codegenFactory = JvmIrCodegenFactory(configuration, phaseConfig)

        // TODO: handle fir from light tree
        val ktFiles = inputArtifact.firFiles.values.mapNotNull { it.psi as KtFile? }
        val sourceFiles = inputArtifact.firFiles.values.mapNotNull { it.sourceFile }

        // Create and initialize the module and its dependencies
        val project = compilerConfigurationProvider.getProject(module)
        val container = TopDownAnalyzerFacadeForJVM.createContainer(
            project, ktFiles, NoScopeRecordCliBindingTrace(), configuration,
            compilerConfigurationProvider.getPackagePartProviderFactory(module),
            ::FileBasedDeclarationProviderFactory, CompilerEnvironment,
            TopDownAnalyzerFacadeForJVM.newModuleSearchScope(project, ktFiles), emptyList()
        )

        val generationState = GenerationState.Builder(
            project, ClassBuilderFactories.TEST,
            container.get(), dummyBindingContext, configuration
        ).isIrBackend(
            true
        ).jvmBackendClassResolver(
            FirJvmBackendClassResolver(components)
        ).build()

        return IrBackendInput.JvmIrBackendInput(
            generationState,
            codegenFactory,
            JvmIrCodegenFactory.JvmIrBackendInput(
                irModuleFragment,
                components.symbolTable,
                phaseConfig,
                components.irProviders,
                fir2IrExtensions,
                FirJvmBackendExtension(inputArtifact.session, components),
                notifyCodegenStart = {},
            ),
            components,
            sourceFiles
        )
    }
}
