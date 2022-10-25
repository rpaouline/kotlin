/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.unlinked

import org.jetbrains.kotlin.backend.common.overrides.FakeOverrideBuilder
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.allUnbound

internal class PartialLinkageSupportImpl(builtIns: IrBuiltIns, messageLogger: IrMessageLogger) : PartialLinkageSupport {
    // Keep this handler here for the whole duration of IR linker life cycle. This is necessary to have
    // stable reference equality (===) for the marker IR type.
    private val markerTypeHandler = PartiallyLinkedIrMarkerTypeHandlerImpl(builtIns)

    private val stubGenerator = MissingDeclarationStubGenerator(builtIns, markerTypeHandler)
    private val classifierExplorer = LinkedClassifierExplorer(classifierSymbols = LinkedClassifierSymbols(), stubGenerator)
    private val patcher = PartiallyLinkedIrTreePatcher(builtIns, markerTypeHandler, classifierExplorer, messageLogger)

    override val partialLinkageEnabled get() = true

    override fun exploreClassifiers(fakeOverrideBuilder: FakeOverrideBuilder) {
        val entries = fakeOverrideBuilder.fakeOverrideCandidates
        if (entries.isEmpty()) return

        val toExclude = buildSet {
            for (clazz in entries.keys) {
                if (classifierExplorer.isPartiallyLinkedClassifier(clazz.symbol)) {
                    this += clazz
                }
            }
        }

        entries -= toExclude
    }

    override fun exploreClassifiersInInlineLazyIrFunction(function: IrFunction) {
        classifierExplorer.exploreIrElement(function)
    }

    override fun generateStubsAndPatchUsages(symbolTable: SymbolTable, roots: () -> Collection<IrElement>) {
        // Generate stubs.
        for (symbol in symbolTable.allUnbound) {
            // Symbol could get bound as a side effect of deserializing other symbols.
            if (!symbol.isBound) {
                stubGenerator.getDeclaration(symbol)
            }
        }

        // Patch the IR tree.
        patcher.patchUsageOfUnlinkedSymbols(roots())
    }
}
