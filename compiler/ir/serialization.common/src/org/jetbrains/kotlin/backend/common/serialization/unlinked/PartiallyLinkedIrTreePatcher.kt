/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.unlinked

import org.jetbrains.kotlin.backend.common.serialization.unlinked.PartiallyLinkedDeclarationOrigin.UNIMPLEMENTED_ABSTRACT_CALLABLE_MEMBER
import org.jetbrains.kotlin.backend.common.serialization.unlinked.UnlinkedIrElementRenderer.appendDeclaration
import org.jetbrains.kotlin.backend.common.serialization.unlinked.UnlinkedIrElementRenderer.renderError
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.IrMessageLogger.Location
import org.jetbrains.kotlin.ir.util.IrMessageLogger.Severity
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.utils.addIfNotNull

internal class PartiallyLinkedIrTreePatcher(
    private val builtIns: IrBuiltIns,
    private val markerTypeHandler: PartiallyLinkedIrMarkerTypeHandler,
    private val classifierExplorer: LinkedClassifierExplorer,
    private val messageLogger: IrMessageLogger
) {
    fun patchUsageOfUnlinkedSymbols(roots: Collection<IrElement>) {
        roots.forEach { it.transformChildrenVoid(UsageTransformer()) }
    }

    // TODO: do we need to fix unlinked types?
    // TODO: if yes, then do we need to do it everywhere?
    private inner class UsageTransformer : IrElementTransformerVoid() {
        private var currentFile: IrFile? = null

        override fun visitFile(declaration: IrFile): IrFile {
            currentFile = declaration
            return try {
                super.visitFile(declaration)
            } finally {
                currentFile = null
            }
        }

        override fun visitFunction(declaration: IrFunction): IrStatement {
            (declaration as? IrOverridableDeclaration<*>)?.filterOverriddenSymbols()

            val isMissingOverriddenMemberImplementation = declaration.origin == UNIMPLEMENTED_ABSTRACT_CALLABLE_MEMBER
            val removedUnlinkedTypes = declaration.fixUnlinkedTypes()

            return if (isMissingOverriddenMemberImplementation || removedUnlinkedTypes.isNotEmpty()) {
                val errorMessages = listOfNotNull(
                    if (isMissingOverriddenMemberImplementation)
                        buildString {
                            append("Abstract ").appendDeclaration(declaration)
                            append(" is not implemented in non-abstract ").appendDeclaration(declaration.parentAsClass)
                        }
                    else null,
                    if (removedUnlinkedTypes.isNotEmpty()) {
                        // TODO: it would be more precise to use the set of unlinked symbols than a collection of unlinked types here.
                        declaration.composeUnlinkedSymbolsErrorMessage(removedUnlinkedTypes.mapNotNull { (it as? IrSimpleType)?.classifier })
                    } else null
                )

                declaration.body?.let { body ->
                    val bb = body as IrBlockBody
                    bb.statements.clear()
                    bb.statements += declaration.throwLinkageError(errorMessages, declaration.location())
                }

                declaration
            } else {
                super.visitFunction(declaration)
            }
        }

        override fun visitProperty(declaration: IrProperty): IrStatement {
            declaration.filterOverriddenSymbols()
            return super.visitProperty(declaration)
        }

        override fun visitField(declaration: IrField): IrStatement {
            return if (declaration.type.isUnlinked()) {
                // TODO: it would be more precise to use the set of unlinked symbols than a collection of unlinked types here.
                declaration.logLinkageError(listOfNotNull((declaration.type as? IrSimpleType)?.classifier))
                declaration.type = markerTypeHandler.markerType
                declaration.initializer = null
                declaration
            } else {
                super.visitField(declaration)
            }
        }

        override fun visitVariable(declaration: IrVariable): IrStatement {
            return if (declaration.type.isUnlinked()) {
                // TODO: it would be more precise to use the set of unlinked symbols than a collection of unlinked types here.
                declaration.logLinkageError(listOfNotNull((declaration.type as? IrSimpleType)?.classifier))
                declaration.type = markerTypeHandler.markerType
                declaration.initializer = null
                declaration
            } else {
                super.visitVariable(declaration)
            }
        }

        override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
            return if (expression.typeOperandClassifier.isUnlinked()) {
                IrCompositeImpl(expression.startOffset, expression.endOffset, builtIns.nothingType).apply {
                    statements += expression.argument
                    statements += expression.throwLinkageError(expression.typeOperandClassifier)
                }
            } else {
                super.visitTypeOperator(expression)
            }
        }

        override fun visitExpression(expression: IrExpression): IrExpression {
            return if (expression.type.isUnlinked() || expression.type.isUnlinkedMarkerType())
                expression.throwLinkageError() // TODO: which exactly classifiers are unlinked?
            else
                super.visitExpression(expression)
        }

        override fun visitMemberAccess(expression: IrMemberAccessExpression<*>): IrExpression {
            return if (expression.symbol.isUnlinked() || expression.type.isUnlinked()) {
                IrCompositeImpl(expression.startOffset, expression.endOffset, builtIns.nothingType, expression.origin).apply {
                    statements.addIfNotNull(expression.dispatchReceiver)
                    statements.addIfNotNull(expression.extensionReceiver)

                    for (i in 0 until expression.valueArgumentsCount) {
                        statements.addIfNotNull(expression.getValueArgument(i))
                    }

                    statements += expression.throwLinkageError() // TODO: which exactly classifiers are unlinked?
                }
            } else {
                super.visitMemberAccess(expression)
            }
        }

        override fun visitFieldAccess(expression: IrFieldAccessExpression): IrExpression {
            return if (expression.symbol.isUnlinked()) {
                IrCompositeImpl(expression.startOffset, expression.endOffset, builtIns.nothingType, expression.origin).apply {
                    statements.addIfNotNull(expression.receiver)
                    if (expression is IrSetField)
                        statements += expression.value
                    statements += expression.throwLinkageError(expression.symbol)
                }
            } else {
                super.visitFieldAccess(expression)
            }
        }

        override fun visitClassReference(expression: IrClassReference): IrExpression {
            return if (expression.symbol.isUnlinked())
                expression.throwLinkageError(expression.symbol)
            else
                super.visitClassReference(expression)
        }

        override fun visitClass(declaration: IrClass): IrStatement {
            if (declaration.symbol.isUnlinked()) {
                val anonInitializer = declaration.declarations.firstNotNullOfOrNull { it as? IrAnonymousInitializer }
                    ?: builtIns.irFactory.createAnonymousInitializer(
                        declaration.startOffset,
                        declaration.endOffset,
                        IrDeclarationOrigin.DEFINED,
                        IrAnonymousInitializerSymbolImpl()
                    ).also {
                        it.body = builtIns.irFactory.createBlockBody(declaration.startOffset, declaration.endOffset)
                        it.parent = declaration
                        declaration.declarations.add(it)
                    }
                anonInitializer.body.statements.clear()
                anonInitializer.body.statements += declaration.throwLinkageError() // TODO: which exactly classifiers are unlinked?

                declaration.superTypes = declaration.superTypes.filter { !it.isUnlinked() }
            }

            return super.visitClass(declaration)
        }

        /**
         * Returns the set of all unlinked types encountered during transformation of the given [IrFunction].
         * Or empty set if there were no unlinked types.
         */
        private fun IrFunction.fixUnlinkedTypes(): Set<IrType> = buildSet {
            fun IrValueParameter.fixType() {
                if (type.isUnlinked()) {
                    this@buildSet += type
                    type = markerTypeHandler.markerType
                    defaultValue = null
                }
                varargElementType?.let {
                    if (it.isUnlinked()) {
                        this@buildSet += it
                        varargElementType = markerTypeHandler.markerType
                    }
                }
            }

            dispatchReceiverParameter?.fixType()
            extensionReceiverParameter?.fixType()
            valueParameters.forEach { it.fixType() }
            if (returnType.isUnlinked()) {
                this += returnType
                returnType = markerTypeHandler.markerType
            }
            typeParameters.forEach {
                val unlinkedSuperType = it.superTypes.firstOrNull { s -> s.isUnlinked() }
                if (unlinkedSuperType != null) {
                    this += unlinkedSuperType
                    it.superTypes = listOf(markerTypeHandler.markerType)
                }
            }
        }

        private fun IrExpression.throwLinkageError(unlinkedSymbol: IrSymbol? = null): IrCall =
            throwLinkageError(
                messages = listOf(composeUnlinkedSymbolsErrorMessage(listOfNotNull(unlinkedSymbol))),
                location = locationIn(currentFile)
            )
    }

    private fun IrSymbol.isUnlinked(): Boolean {
        if (!isBound) return true
        when (this) {
            is IrClassifierSymbol -> isUnlinked()
            is IrPropertySymbol -> {
                owner.getter?.let { if (it.symbol.isUnlinked()) return true }
                owner.setter?.let { if (it.symbol.isUnlinked()) return true }
                owner.backingField?.let { return it.symbol.isUnlinked() }
            }
            is IrFunctionSymbol -> return isUnlinked()
        }
        return false
    }

    private fun IrClassifierSymbol.isUnlinked(): Boolean = classifierExplorer.isPartiallyLinkedClassifier(this)

    private fun IrType.isUnlinked(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false

        if (simpleType.classifier.isUnlinked()) return true

        return simpleType.arguments.any { it is IrTypeProjection && it.type.isUnlinked() }
    }

    private fun IrFieldSymbol.isUnlinked(): Boolean {
        return owner.type.isUnlinkedMarkerType()
    }

    private fun IrFunctionSymbol.isUnlinked(): Boolean {
        val function = owner
        if (function.returnType.isUnlinkedMarkerType()) return true
        if (function.dispatchReceiverParameter?.type?.isUnlinkedMarkerType() == true) return true
        if (function.extensionReceiverParameter?.type?.isUnlinkedMarkerType() == true) return true
        if (function.valueParameters.any { it.type.isUnlinkedMarkerType() }) return true
        if (function.typeParameters.any { tp -> tp.superTypes.any { st -> st.isUnlinkedMarkerType() } }) return true
        return false
    }

    // That's not the same as IrType.isUnlinked()!
    private fun IrType.isUnlinkedMarkerType(): Boolean = markerTypeHandler.isMarkerType(this)

    private fun IrElement.composeUnlinkedSymbolsErrorMessage(unlinkedSymbols: Collection<IrSymbol>) =
        renderError(this@composeUnlinkedSymbolsErrorMessage, unlinkedSymbols)

    private fun IrDeclaration.throwLinkageError(unlinkedSymbols: Collection<IrSymbol> = emptyList()): IrCall =
        throwLinkageError(
            messages = listOf(composeUnlinkedSymbolsErrorMessage(unlinkedSymbols)),
            location = location()
        )

    private fun IrElement.throwLinkageError(messages: List<String>, location: Location?): IrCall {
        check(messages.isNotEmpty())

        messages.forEach { logLinkageError(it, location) }

        val irCall = IrCallImpl(startOffset, endOffset, builtIns.nothingType, builtIns.linkageErrorSymbol, 0, 1, PARTIAL_LINKAGE_RUNTIME_ERROR)
        irCall.putValueArgument(0, IrConstImpl.string(startOffset, endOffset, builtIns.stringType, messages.joinToString("\n")))
        return irCall
    }

    private fun IrDeclaration.logLinkageError(unlinkedSymbols: Collection<IrSymbol>) {
        logLinkageError(
            composeUnlinkedSymbolsErrorMessage(unlinkedSymbols),
            location()
        )
    }

    private fun logLinkageError(message: String, location: Location?) {
        messageLogger.report(Severity.WARNING, message, location) // It's OK. We log it as a warning.
    }

    private fun <S : IrSymbol> IrOverridableDeclaration<S>.filterOverriddenSymbols() {
        overriddenSymbols = overriddenSymbols.filter { symbol ->
            symbol.isBound
                    // Handle the case when the overridden declaration became private.
                    && (symbol.owner as? IrDeclarationWithVisibility)?.visibility != DescriptorVisibilities.PRIVATE
        }
    }

    private object PARTIAL_LINKAGE_RUNTIME_ERROR : IrStatementOriginImpl("PARTIAL_LINKAGE_RUNTIME_ERROR")
}


private fun IrDeclaration.location(): Location? = locationIn(fileOrNull)

private fun IrElement.locationIn(currentFile: IrFile?): Location? {
    if (currentFile == null) return null

    val moduleName: String = currentFile.module.name.asString()
    val filePath: String = currentFile.fileEntry.name

    val lineNumber: Int
    val columnNumber: Int

    when (val effectiveStartOffset = startOffsetOfFirstDenotableIrElement()) {
        UNDEFINED_OFFSET -> {
            lineNumber = UNDEFINED_LINE_NUMBER
            columnNumber = UNDEFINED_COLUMN_NUMBER
        }
        else -> {
            lineNumber = currentFile.fileEntry.getLineNumber(effectiveStartOffset) + 1 // since humans count from 1, not 0
            columnNumber = currentFile.fileEntry.getColumnNumber(effectiveStartOffset) + 1
        }
    }

    // TODO: should module name still be added here?
    return Location("$moduleName @ $filePath", lineNumber, columnNumber)
}

private tailrec fun IrElement.startOffsetOfFirstDenotableIrElement(): Int = when (this) {
    is IrPackageFragment -> UNDEFINED_OFFSET
    !is IrDeclaration -> {
        // We don't generate non-denotable IR expressions in the course of partial linkage.
        startOffset
    }
    else -> if (origin is PartiallyLinkedDeclarationOrigin) {
        // There is no sense to take coordinates from the declaration that does not exist in the code.
        // Let's take the coordinates of the parent.
        parent.startOffsetOfFirstDenotableIrElement()
    } else {
        startOffset
    }
}
