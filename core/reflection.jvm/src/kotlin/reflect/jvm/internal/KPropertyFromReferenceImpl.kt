/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.reflect.jvm.internal

import kotlin.jvm.internal.*

internal class KProperty0FromReferenceImpl(
        val reference: PropertyReference0
) : KProperty0Impl<Any?>(reference.owner as KDeclarationContainerImpl, reference.name, reference.signature) {
    override val name: String get() = reference.name

    override fun get(): Any? = reference.get()
}

internal class KMutableProperty0FromReferenceImpl(
        val reference: MutablePropertyReference0
) : KMutableProperty0Impl<Any?>(reference.owner as KDeclarationContainerImpl, reference.name, reference.signature) {
    override val name: String get() = reference.name

    override fun get(): Any? = reference.get()

    override fun set(value: Any?) = reference.set(value)
}

internal class KProperty1FromReferenceImpl(
        val reference: PropertyReference1
) : KProperty1Impl<Any?, Any?>(reference.owner as KDeclarationContainerImpl, reference.name, reference.signature) {
    override val name: String get() = reference.name

    override fun get(receiver: Any?): Any? = reference.get(receiver)
}

internal class KMutableProperty1FromReferenceImpl(
        val reference: MutablePropertyReference1
) : KMutableProperty1Impl<Any?, Any?>(reference.owner as KDeclarationContainerImpl, reference.name, reference.signature) {
    override val name: String get() = reference.name

    override fun get(receiver: Any?): Any? = reference.get(receiver)

    override fun set(receiver: Any?, value: Any?) = reference.set(receiver, value)
}

internal class KProperty2FromReferenceImpl(
        val reference: PropertyReference2
) : KProperty2Impl<Any?, Any?, Any?>(reference.owner as KDeclarationContainerImpl, reference.name, reference.signature) {
    override val name: String get() = reference.name

    override fun get(receiver1: Any?, receiver2: Any?): Any? = reference.get(receiver1, receiver2)
}

internal class KMutableProperty2FromReferenceImpl(
        val reference: MutablePropertyReference2
) : KMutableProperty2Impl<Any?, Any?, Any?>(reference.owner as KDeclarationContainerImpl, reference.name, reference.signature) {
    override val name: String get() = reference.name

    override fun get(receiver1: Any?, receiver2: Any?): Any? = reference.get(receiver1, receiver2)

    override fun set(receiver1: Any?, receiver2: Any?, value: Any?) = reference.set(receiver1, receiver2, value)
}
