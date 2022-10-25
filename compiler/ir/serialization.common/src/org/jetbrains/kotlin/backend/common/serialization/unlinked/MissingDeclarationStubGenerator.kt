/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.unlinked

import org.jetbrains.kotlin.backend.common.overrides.FakeOverrideBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.linkage.IrProvider
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.error.ErrorUtils

/**
 * Generates the simplest possible stubs for missing declarations.
 *
 * Note: This is a special type of [IrProvider]. It should not be used in row with other IR providers, because it may bring to
 * undesired situation when stubs for unbound fake override symbols are generated even before the corresponding call of
 * [FakeOverrideBuilder.provideFakeOverrides] is made leaving no chance for proper linkage of fake overrides. This IR provider
 * should be applied only after the fake overrides generation.
 */
internal class MissingDeclarationStubGenerator(
    private val builtIns: IrBuiltIns,
    private val markerTypeHandler: PartiallyLinkedIrMarkerTypeHandler
) : IrProvider {
    private val commonParent by lazy {
        IrExternalPackageFragmentImpl.createEmptyExternalPackageFragment(ErrorUtils.errorModule, FqName.ROOT)
    }

    override fun getDeclaration(symbol: IrSymbol): IrDeclaration {
        assert(!symbol.isBound)

        return when (symbol) {
            is IrClassSymbol -> generateIrClass(symbol)
            is IrConstructorSymbol -> generateIrConstructor(symbol)
            else -> TODO("Generation of stubs for ${symbol::class.java} is not supported yet")
        }
    }

    private fun generateIrClass(symbol: IrClassSymbol): IrClass {
        return builtIns.irFactory.createClass(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = PartiallyLinkedDeclarationOrigin.MISSING_DECLARATION,
            symbol = symbol,
            name = symbol.guessName(),
            kind = ClassKind.CLASS,
            visibility = DescriptorVisibilities.DEFAULT_VISIBILITY,
            modality = Modality.OPEN
        ).apply {
            parent = commonParent
            createImplicitParameterDeclarationWithWrappedDescriptor()
        }
    }

    private fun generateIrConstructor(symbol: IrConstructorSymbol): IrConstructor {
        return builtIns.irFactory.createConstructor(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = PartiallyLinkedDeclarationOrigin.MISSING_DECLARATION,
            symbol = symbol,
            name = SpecialNames.INIT,
            visibility = DescriptorVisibilities.DEFAULT_VISIBILITY,
            returnType = markerTypeHandler.markerType,
            isInline = false,
            isExternal = false,
            isPrimary = false,
            isExpect = false,
        ).apply {
            parent = commonParent
            // The body will be patched later, in PartiallyLinkedIrTreePatcher.
            body = builtIns.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        }
    }

    private fun IrSymbol.guessName(): Name =
        signature?.guessName() ?: UNKNOWN_NAME

    private fun IdSignature.guessName(): Name? =
        when (this) {
            is IdSignature.CommonSignature -> Name.guessByFirstCharacter(shortName)
            is IdSignature.CompositeSignature -> inner.guessName()
            is IdSignature.AccessorSignature -> accessorSignature.guessName()
            else -> null
        }

    companion object {
        private val UNKNOWN_NAME = Name.identifier("<unknown name>")
    }
}
