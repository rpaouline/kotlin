/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.unlinked

import gnu.trove.THashMap
import gnu.trove.THashSet
import org.jetbrains.kotlin.backend.common.serialization.unlinked.LinkedClassifierSymbolStatus.Fully
import org.jetbrains.kotlin.backend.common.serialization.unlinked.LinkedClassifierSymbolStatus.Partially
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType

internal sealed interface LinkedClassifierSymbolStatus {
    /** Indicates the IR symbol of partially linked classifier. Subclasses represent various causes of partial linkage. */
    sealed interface Partially : LinkedClassifierSymbolStatus {
        /** There is no owner declaration for the symbol. Likely the declaration has been deleted in newer version of the library. */
        object MissingOwnerDeclaration : Partially

        /**
         * There is no enclosing class for inner class (or enum entry). This might happen if the inner class became a top-level class
         * in newer version of the library.
         */
        object MissingEnclosingClass : Partially

        /**
         * The current symbol is partially linked because it refers other partially linked symbol.
         * The [cause] will be further included into the error message.
         */
        @JvmInline
        value class UsageOfOtherPartiallyLinkedSymbol(val cause: IrClassifierSymbol) : Partially

        /**
         * Rare case: A non-[IrSimpleType] encountered while traversing IR types, which is not supported yet.
         */
        @JvmInline
        value class UnsupportedClassOfIrType(val classOfIrType: Class<out IrType>) : Partially
    }

    /** Indicates the IR symbol of fully linked classifier. */
    object Fully : LinkedClassifierSymbolStatus

    companion object {
        inline val LinkedClassifierSymbolStatus.isPartiallyLinked: Boolean get() = this is Partially
    }
}

internal class LinkedClassifierSymbols {
    private val fullyLinkedSymbols = THashSet<IrClassifierSymbol>()
    private val partiallyLinkedSymbols = THashMap<IrClassifierSymbol, Partially>()

    operator fun get(symbol: IrClassifierSymbol): LinkedClassifierSymbolStatus? =
        if (symbol in fullyLinkedSymbols) Fully else partiallyLinkedSymbols[symbol]

    fun registerPartiallyLinked(symbol: IrClassifierSymbol, reason: Partially): Partially {
        partiallyLinkedSymbols[symbol] = reason
        return reason
    }

    fun registerFullyLinked(symbol: IrClassifierSymbol): Fully {
        fullyLinkedSymbols += symbol
        return Fully
    }
}
