package asmble.run.jvm

import asmble.AsmErr
import java.lang.invoke.WrongMethodTypeException
import java.nio.BufferOverflowException

open class ExceptionTranslator {
    fun translate(ex: Throwable): List<String> = when (ex) {
        is ArithmeticException -> when (ex.message) {
            "/ by zero", "BigInteger divide by zero" -> listOf("integer divide by zero")
            else -> listOf(ex.message!!.decapitalize())
        }
        is ArrayIndexOutOfBoundsException -> listOf("undefined element", "elements segment does not fit")
        is AsmErr -> ex.asmErrStrings
        is IndexOutOfBoundsException -> listOf("out of bounds memory access")
        is NoSuchMethodException -> listOf("unknown import", "type mismatch")
        is NullPointerException -> listOf("undefined element", "uninitialized element")
        is StackOverflowError -> listOf("call stack exhausted")
        is UnsupportedOperationException -> listOf("unreachable executed")
        is WrongMethodTypeException -> listOf("indirect call signature mismatch")
        else -> emptyList()
    }

    companion object : ExceptionTranslator()
}