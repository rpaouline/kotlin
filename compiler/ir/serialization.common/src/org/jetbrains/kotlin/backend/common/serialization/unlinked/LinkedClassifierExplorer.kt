/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.unlinked

import org.jetbrains.kotlin.backend.common.serialization.unlinked.LinkedClassifierSymbolStatus.*
import org.jetbrains.kotlin.backend.common.serialization.unlinked.LinkedClassifierSymbolStatus.Companion.isPartiallyLinked
import org.jetbrains.kotlin.backend.common.serialization.unlinked.LinkedClassifierSymbolStatus.Partially.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstantObject
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.isEnumEntry
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

internal class LinkedClassifierExplorer(
    private val classifierSymbols: LinkedClassifierSymbols,
    private val stubGenerator: MissingDeclarationStubGenerator
) {

    /** Explore the IR type to find the first cause why this type should be considered as partially linked. */
    private fun IrType.exploreType(visitedSymbols: MutableSet<IrClassifierSymbol>): LinkedClassifierSymbolStatus {
        val simpleType = this as? IrSimpleType ?: return UnsupportedClassOfIrType(this::class.java)
        val symbol = simpleType.classifier

        return symbol.findPartiallyLinkedStatusForOuterSymbol(visitedSymbols)
            ?: simpleType.arguments.findPartiallyLinkedStatus { (it as? IrTypeProjection)?.type?.exploreType(visitedSymbols) }
            ?: Fully
    }

    /**
     * The receiver in this function is a symbol (inner one) that is used in another [IrClassifierSymbol] (outer one).
     *
     * If the inner symbol is partially linked, then the outer one is also considered as partially linked. However, these symbols
     * will get different [Partially], describing the different reasons why the symbols are p.l.
     * - the reason for the outer symbol will be [UsageOfOtherPartiallyLinkedSymbol] with cause=<inner symbol>
     * - the reason for the inner symbol might be any subclass of [Partially] as detected by the algorithm in this *.kt file
     *
     * This is needed to indicate only the closest p.l. reasons in emitted error messages to lower the level of verbosity in
     * compiler logs still preserving all the necessary details.
     *
     * The only exception here is when the inner symbol represents a type parameter. In this case the reason for the outer symbol
     * will be the same as for the inner one.
     *
     * Examples:
     *   class A : B
     *   class A<T : B>
     *   // In both cases B is partially linked because of [MissingOwnerDeclaration].
     *   // And in both cases A is also partially linked because of [UsageOfOtherPartiallyLinkedSymbol] with cause=B.
     *   // Note, that in the first example symbol A directly depends on symbol B (and gets the reason of p.l. directly from B).
     *   // In the second example symbol A depends on T which in its turn depends on B. The reason of p.l. for symbol T is
     *   // [UsageOfOtherPartiallyLinkedSymbol] with cause=B. And the reason of p.l. for symbol A is the same as it's just
     *   // taken from the symbol T.
     */
    private fun IrClassifierSymbol.findPartiallyLinkedStatusForOuterSymbol(visitedSymbols: MutableSet<IrClassifierSymbol>): Partially? {
        return when (val status = exploreSymbol(visitedSymbols)) {
            is UsageOfOtherPartiallyLinkedSymbol -> if (this is IrTypeParameterSymbol) status else UsageOfOtherPartiallyLinkedSymbol(this)
            is Partially -> UsageOfOtherPartiallyLinkedSymbol(this)
            else -> null
        }
    }

    /** Iterate the collection and find the first partially linked status. */
    private inline fun <T> List<T>.findPartiallyLinkedStatus(transform: (T) -> LinkedClassifierSymbolStatus?): Partially? =
        firstNotNullOfOrNull { transform(it) as? Partially }

    private fun IrClassifierSymbol.exploreSymbol(visitedSymbols: MutableSet<IrClassifierSymbol>): LinkedClassifierSymbolStatus {
        classifierSymbols[this]?.let { status ->
            // Already explored and registered symbol.
            return status
        }

        val partiallyLinkedStatus: Partially? = when {
            !isBound -> {
                stubGenerator.getDeclaration(this) // Generate a stub.
                MissingOwnerDeclaration
            }
            !visitedSymbols.add(this) -> return Fully // Recursion avoidance.
            else -> when (val classifier = owner) {
                is IrClass -> {
                    val parentPartiallyLinkedStatus: Partially? = if (classifier.isInner || classifier.isEnumEntry) {
                        when (val parentClassSymbol = classifier.parentClassOrNull?.symbol) {
                            null -> MissingEnclosingClass
                            else -> parentClassSymbol.findPartiallyLinkedStatusForOuterSymbol(visitedSymbols)
                        }
                    } else
                        null

                    parentPartiallyLinkedStatus
                        ?: classifier.typeParameters.findPartiallyLinkedStatus {
                            it.symbol.findPartiallyLinkedStatusForOuterSymbol(visitedSymbols)
                        }
                        ?: classifier.superTypes.findPartiallyLinkedStatus { it.exploreType(visitedSymbols) }
                }
                is IrTypeParameter -> classifier.superTypes.findPartiallyLinkedStatus { it.exploreType(visitedSymbols) }
                else -> null
            }
        }

        return if (partiallyLinkedStatus != null)
            classifierSymbols.registerPartiallyLinked(this, partiallyLinkedStatus)
        else
            classifierSymbols.registerFullyLinked(this)
    }

    fun isPartiallyLinkedClassifier(symbol: IrClassifierSymbol): Boolean =
        symbol.exploreSymbol(visitedSymbols = hashSetOf()).isPartiallyLinked

    fun exploreIrElement(element: IrElement) {
        element.acceptChildrenVoid(IrElementExplorer { it.exploreType(visitedSymbols = hashSetOf()) })
    }
}

private class IrElementExplorer(private val visitType: (IrType) -> Unit) : IrElementVisitorVoid {
    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitValueParameter(declaration: IrValueParameter) {
        visitType(declaration.type)
        super.visitValueParameter(declaration)
    }

    override fun visitTypeParameter(declaration: IrTypeParameter) {
        declaration.superTypes.forEach(visitType)
        super.visitTypeParameter(declaration)
    }

    override fun visitFunction(declaration: IrFunction) {
        visitType(declaration.returnType)
        super.visitFunction(declaration)
    }

    override fun visitField(declaration: IrField) {
        visitType(declaration.type)
        super.visitField(declaration)
    }

    override fun visitVariable(declaration: IrVariable) {
        visitType(declaration.type)
        super.visitVariable(declaration)
    }

    override fun visitExpression(expression: IrExpression) {
        visitType(expression.type)
        super.visitExpression(expression)
    }

    override fun visitClassReference(expression: IrClassReference) {
        visitType(expression.classType)
        super.visitClassReference(expression)
    }

    override fun visitConstantObject(expression: IrConstantObject) {
        expression.typeArguments.forEach(visitType)
        super.visitConstantObject(expression)
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall) {
        visitType(expression.typeOperand)
        super.visitTypeOperator(expression)
    }
}
