// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.nj2k.conversions

import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.SequentialBaseConversion
import org.jetbrains.kotlin.nj2k.tree.JKTreeElement


abstract class MatchBasedConversion(override val context: NewJ2kConverterContext) : SequentialBaseConversion {
    fun <R : JKTreeElement, T> applyRecursive(element: R, data: T, func: (JKTreeElement, T) -> JKTreeElement): R =
        org.jetbrains.kotlin.nj2k.tree.applyRecursive(element, data, ::onElementChanged, func)

    fun <R : JKTreeElement> applyRecursive(element: R, func: (JKTreeElement) -> JKTreeElement): R {
        return applyRecursive(element, null) { it, _ -> func(it) }
    }

    private inline fun <T> applyRecursiveToList(
        element: JKTreeElement,
        child: List<JKTreeElement>,
        iter: MutableListIterator<Any>,
        data: T,
        func: (JKTreeElement, T) -> JKTreeElement
    ): List<JKTreeElement> {

        val newChild = child.map {
            func(it, data)
        }

        child.forEach { it.detach(element) }
        iter.set(child)
        newChild.forEach { it.attach(element) }
        newChild.zip(child).forEach { (old, new) ->
            if (old !== new) {
                onElementChanged(new, old)
            }
        }
        return newChild
    }


    abstract fun onElementChanged(new: JKTreeElement, old: JKTreeElement)
}