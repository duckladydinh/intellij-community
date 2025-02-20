// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.debugger.test.mock

import com.sun.jdi.*
import org.jetbrains.kotlin.backend.common.output.OutputFile
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

class SmartMockReferenceTypeContext(outputFiles: List<OutputFile>) {
    constructor(outputFiles: OutputFileCollection) : this(outputFiles.asList())

    val virtualMachine = MockVirtualMachine()

    val classes = outputFiles
        .filter { it.relativePath.endsWith(".class") }
        .map { it.readClass() }

    private val referenceTypes: List<ReferenceType> by lazy {
        classes.map { SmartMockReferenceType(it, this) }
    }

    val referenceTypesByName by lazy {
        referenceTypes.map { Pair(it.name(), it) }.toMap()
    }
}

private fun OutputFile.readClass(): ClassNode {
    val classNode = ClassNode()
    ClassReader(asByteArray()).accept(classNode, ClassReader.EXPAND_FRAMES)
    return classNode
}

class SmartMockReferenceType(val classNode: ClassNode, private val context: SmartMockReferenceTypeContext) : ReferenceType {
    override fun instances(maxInstances: Long) = emptyList<ObjectReference>()
    override fun isPublic() = (classNode.access and Opcodes.ACC_PUBLIC) != 0
    override fun classLoader() = null
    override fun sourceName(): String? = classNode.sourceFile
    override fun defaultStratum() = "Java"
    override fun isStatic() = (classNode.access and Opcodes.ACC_STATIC) != 0
    override fun modifiers() = classNode.access
    override fun isProtected() = (classNode.access and Opcodes.ACC_PROTECTED) != 0
    override fun isFinal() = (classNode.access and Opcodes.ACC_FINAL) != 0
    override fun allLineLocations() = methodsCached.flatMap { it.allLineLocations() }
    override fun genericSignature(): String? = classNode.signature
    override fun isAbstract() = (classNode.access and Opcodes.ACC_ABSTRACT) != 0
    override fun isPrepared() = true
    override fun name() = classNode.name.replace('/', '.')
    override fun isInitialized() = true
    override fun sourcePaths(stratum: String) = listOf(classNode.sourceFile)
    override fun failedToInitialize() = false
    override fun virtualMachine() = context.virtualMachine
    override fun isPrivate() = (classNode.access and Opcodes.ACC_PRIVATE) != 0
    override fun signature(): String? = classNode.signature
    override fun sourceNames(stratum: String) = listOf(classNode.sourceFile)
    override fun availableStrata() = emptyList<String>()

    private val methodsCached by lazy { classNode.methods.map { MockMethod(it, this) } }
    override fun methods() = methodsCached

    override fun nestedTypes(): List<ReferenceType> {
        val fromInnerClasses = classNode.innerClasses
            .filter { it.outerName == classNode.name }
            .mapNotNull { context.classes.find { c -> it.name == c.name } }

        val fromOuterClasses = context.classes.filter { it.outerClass == classNode.name }

        return (fromInnerClasses + fromOuterClasses).distinctBy { it.name }.map { SmartMockReferenceType(it, context) }
    }

    override fun isPackagePrivate(): Boolean {
        return ((classNode.access and Opcodes.ACC_PUBLIC) == 0
                && (classNode.access and Opcodes.ACC_PROTECTED) == 0
                && (classNode.access and Opcodes.ACC_PRIVATE) == 0)
    }

    override fun isVerified() = true

    override fun fields() = TODO()
    override fun allFields() = TODO()
    override fun fieldByName(fieldName: String) = TODO()
    override fun getValue(p0: Field?) = TODO()
    override fun visibleFields() = TODO()
    override fun allLineLocations(stratum: String, sourceName: String) = TODO()
    override fun majorVersion() = TODO()
    override fun constantPoolCount() = TODO()
    override fun constantPool() = TODO()
    override fun compareTo(other: ReferenceType?) = TODO()
    override fun sourceDebugExtension() = TODO()
    override fun visibleMethods() = TODO()
    override fun locationsOfLine(lineNumber: Int) = TODO()
    override fun locationsOfLine(stratum: String, sourceName: String, lineNumber: Int) = TODO()
    override fun getValues(p0: MutableList<out Field>?) = TODO()
    override fun minorVersion() = TODO()
    override fun classObject() = TODO()
    override fun methodsByName(p0: String?) = TODO()
    override fun methodsByName(p0: String?, p1: String?) = TODO()
    override fun allMethods() = TODO()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as SmartMockReferenceType

        return classNode.name == other.classNode.name
    }

    override fun hashCode(): Int {
        return classNode.name.hashCode()
    }

    class MockMethod(private val methodNode: MethodNode, val containingClass: SmartMockReferenceType) : Method {
        override fun virtualMachine() = containingClass.context.virtualMachine

        override fun modifiers() = methodNode.access
        override fun isStaticInitializer() = methodNode.name == "<clinit>"
        override fun isPublic() = (methodNode.access and Opcodes.ACC_PUBLIC) != 0
        override fun isNative() = (methodNode.access and Opcodes.ACC_NATIVE) != 0
        override fun isStatic() = (methodNode.access and Opcodes.ACC_STATIC) != 0
        override fun isBridge() = (methodNode.access and Opcodes.ACC_BRIDGE) != 0
        override fun isProtected() = (methodNode.access and Opcodes.ACC_PROTECTED) != 0
        override fun isFinal() = (methodNode.access and Opcodes.ACC_FINAL) != 0
        override fun isAbstract() = (methodNode.access and Opcodes.ACC_ABSTRACT) != 0
        override fun isSynthetic() = (methodNode.access and Opcodes.ACC_SYNTHETIC) != 0
        override fun isConstructor() = methodNode.name == "<init>"
        override fun isPrivate() = (methodNode.access and Opcodes.ACC_PRIVATE) != 0

        override fun isPackagePrivate(): Boolean {
            return ((methodNode.access and Opcodes.ACC_PUBLIC) == 0
                    && (methodNode.access and Opcodes.ACC_PROTECTED) == 0
                    && (methodNode.access and Opcodes.ACC_PRIVATE) == 0)
        }

        override fun declaringType() = containingClass
        override fun name(): String? = methodNode.name
        override fun signature(): String? = methodNode.signature

        override fun location(): Location? {
            val instructionList = methodNode.instructions ?: return null
            var current = instructionList.first
            while (current != null) {
                if (current is LineNumberNode) {
                    return MockLocation(this, current.line)
                }
                current = current.next
            }
            return null
        }

        override fun allLineLocations(): List<Location> {
            val instructionList = methodNode.instructions ?: return emptyList()
            var current = instructionList.first
            val locations = mutableListOf<Location>()
            while (current != null) {
                if (current is LineNumberNode) {
                    locations += MockLocation(this, current.line)
                }
                current = current.next
            }
            return locations
        }

        override fun argumentTypeNames() = TODO()
        override fun arguments() = TODO()
        override fun allLineLocations(p0: String?, p1: String?) = TODO()
        override fun genericSignature() = TODO()
        override fun returnType() = TODO()
        override fun compareTo(other: Method?) = TODO()
        override fun isObsolete() = false
        override fun variablesByName(p0: String?) = TODO()
        override fun argumentTypes() = TODO()
        override fun locationOfCodeIndex(p0: Long) = TODO()
        override fun bytecodes() = TODO()
        override fun returnTypeName() = TODO()
        override fun locationsOfLine(p0: Int) = TODO()
        override fun locationsOfLine(p0: String?, p1: String?, p2: Int) = TODO()
        override fun variables() = TODO()
        override fun isVarArgs() = TODO()
        override fun isSynchronized() = TODO()
    }

    private class MockLocation(val method: MockMethod, val line: Int) : Location {
        override fun virtualMachine() = method.containingClass.context.virtualMachine
        override fun sourceName() = method.containingClass.sourceName()
        override fun lineNumber() = line
        override fun sourcePath() = sourceName()
        override fun declaringType() = method.containingClass
        override fun method() = method

        override fun sourceName(stratum: String) = TODO()
        override fun codeIndex() = TODO()
        override fun lineNumber(stratum: String) = TODO()
        override fun sourcePath(stratum: String) = TODO()

        override fun compareTo(other: Location?) = TODO()
    }
}

