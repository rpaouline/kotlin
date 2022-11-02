/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.metadata.deserialization

import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.protobuf.GeneratedMessageLite
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationPluginMetadataExtensions

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
        val order = propertyProgramOrderMap.takeIf { it.isNotEmpty() } ?: return tryComputeDeclarationOrderBySerializationExtension()
        return propertyList.withIndex().sortedBy { order[it.index] }.map { it.value }
    }

private fun ProtoBuf.ClassOrBuilder.tryComputeDeclarationOrderBySerializationExtension(): List<ProtoBuf.Property> {
    val properties = propertyList
    val order = getExtension(SerializationPluginMetadataExtensions.propertiesNamesInProgramOrder)
        .takeIf { it.isNotEmpty() }
        ?: return properties
    val propertiesByName = properties.groupBy { it.name }
    return order.flatMap { propertiesByName[it] ?: emptyList() }.also {
        assert(it.size == properties.size)
    }
}
