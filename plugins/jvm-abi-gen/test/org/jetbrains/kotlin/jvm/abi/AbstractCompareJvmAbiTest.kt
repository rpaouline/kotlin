/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jvm.abi

import org.jetbrains.kotlin.incremental.testingUtils.assertEqualDirectories
import java.io.File
import kotlin.test.assertFails

abstract class AbstractCompareJvmAbiTest : BaseJvmAbiTest() {
    companion object {
        private const val IGNORE_FILE = "ignoreLegacyBackend.txt"
    }

    fun doTest(path: String) {
        val testDir = File(path)
        try {
            doTestWithoutIgnore(testDir)
            checkUselessIgnore(testDir)
        } catch (e: AssertionError) {
            ignoreIfNeeded(testDir, e)
        } catch (e: Throwable) {
            ignoreIfNeeded(testDir, e)
        }
    }

    private fun doTestWithoutIgnore(testDir: File) {
        val base = Compilation(testDir, "base").also { make(it) }
        val sameAbiDir = testDir.resolve("sameAbi")
        val differentAbiDir = testDir.resolve("differentAbi")

        assert(sameAbiDir.exists() || differentAbiDir.exists()) { "Nothing to compare" }

        if (sameAbiDir.exists()) {
            val sameAbi = Compilation(testDir, "sameAbi").also { make(it) }
            assertEqualDirectories(sameAbi.abiDir, base.abiDir, forgiveExtraFiles = false)
        }

        if (differentAbiDir.exists()) {
            val differentAbi = Compilation(testDir, "differentAbi").also { make(it) }
            assertFails("$base and $differentAbi abi are equal") {
                assertEqualDirectories(differentAbi.abiDir, base.abiDir, forgiveExtraFiles = false)
            }
        }
    }

    private fun ignoreIfNeeded(testDir: File, e: Throwable) {
        if (useLegacyAbiGen && testDir.ignoreFile.exists()) return
        throw e
    }

    private fun checkUselessIgnore(testDir: File) {
        if (useLegacyAbiGen && testDir.ignoreFile.exists()) {
            fail("Test is passing but ignore file is present. Please delete $IGNORE_FILE")
        }
    }

    private val File.ignoreFile: File
        get() = resolve(IGNORE_FILE)
}

