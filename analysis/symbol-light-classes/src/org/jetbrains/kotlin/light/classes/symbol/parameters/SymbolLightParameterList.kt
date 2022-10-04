/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.light.classes.symbol.parameters

import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import com.intellij.psi.impl.light.LightParameterListBuilder
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.symbols.KtCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.project.structure.KtModule
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.elements.KtLightElementBase
import org.jetbrains.kotlin.light.classes.symbol.classes.analyzeForLightClasses
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightMethodBase
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameterList

internal class SymbolLightParameterList(
    private val parent: SymbolLightMethodBase,
    private val callableSymbolPointer: KtSymbolPointer<KtCallableSymbol>?,
    private val ktModule: KtModule,
    parameterPopulator: (KtAnalysisSession.(LightParameterListBuilder, KtCallableSymbol) -> Unit)?,
) : KtLightElement<KtParameterList, PsiParameterList>,
    // With this, a parent chain is properly built: from SymbolLightParameter through SymbolLightParameterList to SymbolLightMethod
    KtLightElementBase(parent),
    // NB: we can't use delegation here, which will conflict getTextRange from KtLightElementBase
    PsiParameterList {

    override val kotlinOrigin: KtParameterList?
        get() = (parent.kotlinOrigin as? KtFunction)?.valueParameterList

    private val clsDelegate: PsiParameterList by lazyPub {
        val builder = LightParameterListBuilder(manager, language)

        if (parameterPopulator != null) {
            analyzeForLightClasses(ktModule) {
                callableSymbolPointer?.restoreSymbol()?.let { callableSymbol ->
                    SymbolLightParameterForReceiver.tryGet(callableSymbol, parent)?.let { receiver ->
                        builder.addParameter(receiver)
                    }

                    parameterPopulator(builder, callableSymbol)
                }
            }
        }

        builder
    }

    override fun getParameters(): Array<PsiParameter> = clsDelegate.parameters

    override fun getParameterIndex(p: PsiParameter): Int = clsDelegate.getParameterIndex(p)

    override fun getParametersCount(): Int = clsDelegate.parametersCount
}
