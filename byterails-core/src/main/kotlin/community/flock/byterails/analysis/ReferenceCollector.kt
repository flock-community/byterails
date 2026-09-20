package community.flock.byterails.analysis

import community.flock.byterails.model.ClassName
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * Reads one class file and collects every class name the compiler wrote into it.
 *
 * The list is meant to be exhaustive: header, fields, method declarations, method bodies,
 * generic signatures, annotations and their arguments, invokedynamic call sites with their
 * bootstrap arguments, local variables and exception handlers. Nesting attributes are not
 * references and are skipped.
 */
object ClassFileAnalyzer {

    private const val ASM = Opcodes.ASM9

    fun analyze(bytes: ByteArray): AnalyzedClass {
        val collector = Collector()
        ClassReader(bytes).accept(collector, ClassReader.SKIP_FRAMES)
        return collector.result()
    }

    private class Collector : ClassVisitor(ASM) {
        private lateinit var className: ClassName
        private var sourceFile: String? = null
        private var access = 0
        private val annotations = mutableListOf<ClassName>()
        private val references = LinkedHashSet<Reference>()

        fun result() = AnalyzedClass(className, sourceFile, access, annotations.toList(), references.toList())

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            className = ClassName(name)
            this.access = access
            val site = Site.ClassHeader
            superName?.let { addInternalName(it, site, null) }
            interfaces?.forEach { addInternalName(it, site, null) }
            addSignature(signature, site, null, typeOnly = false)
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
            annotations += ClassName(Type.getType(descriptor).internalName)
            return annotationVisitor(descriptor, Site.ClassHeader, null)
        }

        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean): AnnotationVisitor =
            annotationVisitor(descriptor, Site.ClassHeader, null)

        override fun visitPermittedSubclass(permittedSubclass: String) {
            addInternalName(permittedSubclass, Site.ClassHeader, null)
        }

        override fun visitRecordComponent(name: String, descriptor: String, signature: String?): RecordComponentVisitor {
            val site = Site.Field(name)
            addDescriptor(descriptor, site, null)
            addSignature(signature, site, null, typeOnly = true)
            return object : RecordComponentVisitor(ASM) {
                override fun visitAnnotation(descriptor: String, visible: Boolean) = annotationVisitor(descriptor, site, null)
                override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) =
                    annotationVisitor(descriptor, site, null)
            }
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
            val site = Site.Field(name)
            addDescriptor(descriptor, site, null)
            addSignature(signature, site, null, typeOnly = true)
            return object : FieldVisitor(ASM) {
                override fun visitAnnotation(descriptor: String, visible: Boolean) = annotationVisitor(descriptor, site, null)
                override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) =
                    annotationVisitor(descriptor, site, null)
            }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val site = Site.Method(name, descriptor)
            addDescriptor(descriptor, site, null)
            addSignature(signature, site, null, typeOnly = false)
            exceptions?.forEach { addInternalName(it, site, null) }
            return MethodCollector(site)
        }

        // Nesting attributes describe structure, not use.
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) = Unit
        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) = Unit
        override fun visitNestHost(nestHost: String) = Unit
        override fun visitNestMember(nestMember: String) = Unit
        override fun visitAttribute(attribute: Attribute?) = Unit

        private inner class MethodCollector(private val site: Site.Method) : MethodVisitor(ASM) {
            private var line: Int? = null

            override fun visitLineNumber(line: Int, start: Label) {
                this.line = line
            }

            override fun visitAnnotationDefault(): AnnotationVisitor = valuesVisitor(site, null)
            override fun visitAnnotation(descriptor: String, visible: Boolean) = annotationVisitor(descriptor, site, null)
            override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) =
                annotationVisitor(descriptor, site, null)
            override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean) =
                annotationVisitor(descriptor, site, null)
            override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) =
                annotationVisitor(descriptor, site, line)
            override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) =
                annotationVisitor(descriptor, site, line)
            override fun visitLocalVariableAnnotation(
                typeRef: Int, typePath: TypePath?, start: Array<Label>?, end: Array<Label>?, index: IntArray?, descriptor: String, visible: Boolean,
            ) = annotationVisitor(descriptor, site, null)

            override fun visitTypeInsn(opcode: Int, type: String) {
                addObjectType(type, site, line)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                addObjectType(owner, site, line)
                addDescriptor(descriptor, site, line)
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                addObjectType(owner, site, line)
                addDescriptor(descriptor, site, line)
            }

            override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
                addDescriptor(descriptor, site, line)
                addHandle(bootstrapMethodHandle, site, line)
                bootstrapMethodArguments.forEach { addConstant(it, site, line) }
            }

            override fun visitLdcInsn(value: Any) {
                addConstant(value, site, line)
            }

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                addDescriptor(descriptor, site, line)
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                type?.let { addInternalName(it, site, line) }
            }

            override fun visitLocalVariable(name: String, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                addDescriptor(descriptor, site, null)
                addSignature(signature, site, null, typeOnly = true)
            }
        }

        private fun annotationVisitor(descriptor: String, site: Site, line: Int?): AnnotationVisitor {
            addDescriptor(descriptor, site, line)
            return valuesVisitor(site, line)
        }

        /** Annotation arguments can carry class literals, enum constants and nested annotations. */
        private fun valuesVisitor(site: Site, line: Int?): AnnotationVisitor = object : AnnotationVisitor(ASM) {
            override fun visit(name: String?, value: Any) = addConstant(value, site, line)
            override fun visitEnum(name: String?, descriptor: String, value: String) = addDescriptor(descriptor, site, line)
            override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor = annotationVisitor(descriptor, site, line)
            override fun visitArray(name: String?): AnnotationVisitor = this
        }

        private fun addInternalName(internalName: String, site: Site, line: Int?) {
            references += Reference(ClassName(internalName), site, line)
        }

        /** Owners and type operands may be plain internal names or array descriptors. */
        private fun addObjectType(name: String, site: Site, line: Int?) {
            addType(Type.getObjectType(name), site, line)
        }

        private fun addDescriptor(descriptor: String, site: Site, line: Int?) {
            addType(Type.getType(descriptor), site, line)
        }

        private fun addType(type: Type, site: Site, line: Int?) {
            when (type.sort) {
                Type.OBJECT -> addInternalName(type.internalName, site, line)
                Type.ARRAY -> addType(type.elementType, site, line)
                Type.METHOD -> {
                    type.argumentTypes.forEach { addType(it, site, line) }
                    addType(type.returnType, site, line)
                }
                else -> Unit
            }
        }

        private fun addHandle(handle: Handle, site: Site, line: Int?) {
            addObjectType(handle.owner, site, line)
            addDescriptor(handle.desc, site, line)
        }

        private fun addConstant(value: Any?, site: Site, line: Int?) {
            when (value) {
                is Type -> addType(value, site, line)
                is Handle -> addHandle(value, site, line)
                is ConstantDynamic -> {
                    addDescriptor(value.descriptor, site, line)
                    addHandle(value.bootstrapMethod, site, line)
                    for (i in 0 until value.bootstrapMethodArgumentCount) addConstant(value.getBootstrapMethodArgument(i), site, line)
                }
                else -> Unit
            }
        }

        private fun addSignature(signature: String?, site: Site, line: Int?, typeOnly: Boolean) {
            if (signature == null) return
            val visitor = SignatureCollector(site, line)
            val reader = SignatureReader(signature)
            if (typeOnly) reader.acceptType(visitor) else reader.accept(visitor)
        }

        /** Collects every class type in a generic signature, nested class types included. */
        private inner class SignatureCollector(private val site: Site, private val line: Int?) : SignatureVisitor(ASM) {
            private val stack = ArrayDeque<String>()

            override fun visitClassType(name: String) {
                stack.addLast(name)
                addInternalName(name, site, line)
            }

            override fun visitInnerClassType(name: String) {
                val outer = stack.removeLastOrNull() ?: return
                val inner = "$outer\$$name"
                stack.addLast(inner)
                addInternalName(inner, site, line)
            }

            override fun visitEnd() {
                stack.removeLastOrNull()
            }

            override fun visitClassBound(): SignatureVisitor = this
            override fun visitInterfaceBound(): SignatureVisitor = this
            override fun visitSuperclass(): SignatureVisitor = this
            override fun visitInterface(): SignatureVisitor = this
            override fun visitParameterType(): SignatureVisitor = this
            override fun visitReturnType(): SignatureVisitor = this
            override fun visitExceptionType(): SignatureVisitor = this
            override fun visitArrayType(): SignatureVisitor = this
            override fun visitTypeArgument(wildcard: Char): SignatureVisitor = this
        }
    }
}
