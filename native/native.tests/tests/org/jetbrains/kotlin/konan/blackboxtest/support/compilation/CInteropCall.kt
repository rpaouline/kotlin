/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest.support.compilation

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.konan.blackboxtest.support.settings.KotlinNativeTargets
import org.jetbrains.kotlin.konan.file.File as KonanFile
import org.jetbrains.kotlin.native.interop.gen.jvm.InternalInteropOptions
import org.jetbrains.kotlin.native.interop.gen.jvm.interop
import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

internal fun invokeCInterop(
    targets: KotlinNativeTargets,
    inputDef: File,
    outputLib: File,
    extraArgs: Array<String>
): CompilationToolCallResult {
    val args = arrayOf("-o", outputLib.canonicalPath, "-def", inputDef.canonicalPath, "-no-default-libs", "-target", targets.testTarget.name)
    val buildDir = KonanFile("${outputLib.canonicalPath}-build")
    val generatedDir = KonanFile(buildDir, "kotlin")
    val nativesDir = KonanFile(buildDir, "natives")
    val manifest = KonanFile(buildDir, "manifest.properties")
    val cstubsName = "cstubs"
    val possibleSubsequentCompilerInvocationArgs: Array<String>?

    @OptIn(ExperimentalTime::class)
    val duration = measureTime {
        possibleSubsequentCompilerInvocationArgs = interop(
            "native",
            args + extraArgs,
            InternalInteropOptions(generatedDir.absolutePath, nativesDir.absolutePath, manifest.path, cstubsName),
            false
        )
    }
    // In currently tested usecases, cinterop must return no args for the subsequent compiler call
    return if (possibleSubsequentCompilerInvocationArgs == null) {
        // TODO There is no technical ability to extract `toolOutput` and `toolOutputHasErrors`
        //      from C-interop tool invocation at the moment. This should be fixed in the future.
        CompilationToolCallResult(exitCode = ExitCode.OK, toolOutput = "", toolOutputHasErrors = false, duration)
    } else {
        CompilationToolCallResult(
            exitCode = ExitCode.COMPILATION_ERROR,
            toolOutput = possibleSubsequentCompilerInvocationArgs.joinToString(" "),
            toolOutputHasErrors = true,
            duration
        )
    }
}
