package asmble.run.jvm

import asmble.ast.Node
import asmble.compile.jvm.Mem
import asmble.compile.jvm.ref
import asmble.run.jvm.annotation.WasmName
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor

interface Module {
    fun bindMethod(ctx: ScriptContext, wasmName: String, javaName: String, type: MethodType): MethodHandle?

    data class Composite(val modules: List<Module>) : Module {
        override fun bindMethod(ctx: ScriptContext, wasmName: String, javaName: String, type: MethodType) =
            modules.asSequence().mapNotNull { it.bindMethod(ctx, wasmName, javaName, type) }.singleOrNull()
    }

    interface Instance : Module {
        val cls: Class<*>
        // Guaranteed to be the same instance when there is no error
        fun instance(ctx: ScriptContext): Any

        override fun bindMethod(ctx: ScriptContext, wasmName: String, javaName: String, type: MethodType) =
            try {
                MethodHandles.lookup().bind(instance(ctx), javaName, type)
            } catch (_: NoSuchMethodException) {
                // Try any method w/ the proper annotation
                cls.methods.mapNotNull { method ->
                    if (method.getAnnotation(WasmName::class.java)?.value != wasmName) null
                    else MethodHandles.lookup().unreflect(method).bindTo(instance(ctx)).takeIf { it.type() == type }
                }.singleOrNull()
            }
    }

    data class Native(override val cls: Class<*>, val inst: Any) : Instance {
        constructor(inst: Any) : this(inst::class.java, inst)

        override fun instance(ctx: ScriptContext) = inst
    }

    class Compiled(
        val mod: Node.Module,
        override val cls: Class<*>,
        val name: String?,
        val mem: Mem
    ) : Instance {
        private var inst: Any? = null
        override fun instance(ctx: ScriptContext) =
            synchronized(this) { inst ?: createInstance(ctx).also { inst = it } }

        private fun createInstance(ctx: ScriptContext): Any {
            // Find the constructor
            var constructorParams = emptyList<Any>()
            var constructor: Constructor<*>?

            // If there is a memory import, we have to get the one with the mem class as the first
            val memImport = mod.imports.find { it.kind is Node.Import.Kind.Memory }
            val memLimit = if (memImport != null) {
                constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull()?.ref == mem.memType }
                val memImportKind = memImport.kind as Node.Import.Kind.Memory
                val memInst = ctx.resolveImportMemory(memImport, memImportKind.type, mem)
                constructorParams += memInst
                val (memLimit, memCap) = mem.limitAndCapacity(memInst)
                if (memLimit < memImportKind.type.limits.initial * Mem.PAGE_SIZE)
                    throw RunErr.ImportMemoryLimitTooSmall(memImportKind.type.limits.initial * Mem.PAGE_SIZE, memLimit)
                memImportKind.type.limits.maximum?.let {
                    if (memCap > it * Mem.PAGE_SIZE)
                        throw RunErr.ImportMemoryCapacityTooLarge(it * Mem.PAGE_SIZE, memCap)
                }
                memLimit
            } else {
                // Find the constructor with no max mem amount (i.e. not int and not memory)
                constructor = cls.declaredConstructors.find {
                    val memClass = Class.forName(mem.memType.asm.className)
                    when (it.parameterTypes.firstOrNull()) {
                        Int::class.java, memClass -> false
                        else -> true
                    }
                }
                // If it is not there, find the one w/ the max mem amount
                val maybeMem = mod.memories.firstOrNull()
                if (constructor == null) {
                    val maxMem = Math.max(maybeMem?.limits?.initial ?: 0, ctx.defaultMaxMemPages)
                    constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull() == Int::class.java }
                    constructorParams += maxMem * Mem.PAGE_SIZE
                }
                maybeMem?.limits?.initial?.let { it * Mem.PAGE_SIZE }
            }
            if (constructor == null) error("Unable to find suitable module constructor")

            // Function imports
            constructorParams += mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Func) ctx.resolveImportFunc(it, mod.types[it.kind.typeIndex])
                else null
            }

            // Global imports
            val globalImports = mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Global) ctx.resolveImportGlobal(it, it.kind.type)
                else null
            }
            constructorParams += globalImports

            // Table imports
            val tableImport = mod.imports.find { it.kind is Node.Import.Kind.Table }
            val tableSize = if (tableImport != null) {
                val tableImportKind = tableImport.kind as Node.Import.Kind.Table
                val table = ctx.resolveImportTable(tableImport, tableImportKind.type)
                if (table.size < tableImportKind.type.limits.initial)
                    throw RunErr.ImportTableTooSmall(tableImportKind.type.limits.initial, table.size)
                tableImportKind.type.limits.maximum?.let {
                    if (table.size > it) throw RunErr.ImportTableTooLarge(it, table.size)
                }
                constructorParams = constructorParams.plusElement(table)
                table.size
            } else mod.tables.firstOrNull()?.limits?.initial

            // We need to validate that elems can fit in table and data can fit in mem
            fun constIntExpr(insns: List<Node.Instr>): Int? = insns.singleOrNull()?.let {
                when (it) {
                    is Node.Instr.I32Const -> it.value
                    is Node.Instr.GetGlobal ->
                        if (it.index < globalImports.size) {
                            // Imports we already have
                            if (globalImports[it.index].type().returnType() == Int::class.java) {
                                globalImports[it.index].invokeWithArguments() as Int
                            } else null
                        } else constIntExpr(mod.globals[it.index - globalImports.size].init)
                    else -> null
                }
            }
            if (tableSize != null) mod.elems.forEach { elem ->
                constIntExpr(elem.offset)?.let { offset ->
                    if (offset >= tableSize) throw RunErr.InvalidElemIndex(offset, tableSize)
                }
            }
            if (memLimit != null) mod.data.forEach { data ->
                constIntExpr(data.offset)?.let { offset ->
                    if (offset < 0 || offset + data.data.size > memLimit)
                        throw RunErr.InvalidDataIndex(offset, data.data.size, memLimit)
                }
            }

            // Construct
            ctx.debug { "Instantiating $cls using $constructor with params $constructorParams" }
            return constructor.newInstance(*constructorParams.toTypedArray())
        }
    }
}