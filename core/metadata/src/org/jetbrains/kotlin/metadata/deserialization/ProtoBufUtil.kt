/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.metadata.deserialization

import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.protobuf.GeneratedMessageLite

fun <M : GeneratedMessageLite.ExtendableMessage<M>, T> GeneratedMessageLite.ExtendableMessage<M>.getExtensionOrNull(
    extension: GeneratedMessageLite.GeneratedExtension<M, T>
): T? = if (hasExtension(extension)) getExtension(extension) else null

fun <M : GeneratedMessageLite.ExtendableMessage<M>, T> GeneratedMessageLite.ExtendableMessage<M>.getExtensionOrNull(
    extension: GeneratedMessageLite.GeneratedExtension<M, List<T>>, index: Int
): T? = if (index < getExtensionCount(extension)) getExtension(extension, index) else null

val ProtoBuf.ClassOrBuilder.propertyProgramOrderMap: Map<Int, Int>
    get() = buildMap {
        propertiesProgramOrderList.forEachIndexed { declaredIndex, sortedIndex ->
            put(sortedIndex, declaredIndex)
        }
    }

val ProtoBuf.ClassOrBuilder.propertyListInDeclarationOrder: List<ProtoBuf.Property>
    get() {
        val properties = propertyList
        val order = propertyProgramOrderMap.takeIf { it.isNotEmpty() } ?: return properties
        return properties.withIndex().sortedBy { order[it.index] }.map { it.value }
    }
