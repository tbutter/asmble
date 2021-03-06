package asmble.cli

import asmble.ast.Script
import asmble.compile.jvm.AstToAsm
import asmble.compile.jvm.ClsContext
import asmble.compile.jvm.withComputedFramesAndMaxs
import java.io.FileOutputStream

open class Compile : Command<Compile.Args>() {

    override val name = "compile"
    override val desc = "Compile WebAssembly to class file"

    override fun args(bld: Command.ArgsBuilder) = Args(
        inFile = bld.arg(
            name = "inFile",
            desc = "The wast or wasm WebAssembly file name. Can be '--' to read from stdin."
        ),
        inFormat = bld.arg(
            name = "inFormat",
            opt = "format",
            desc = "Either 'wast' or 'wasm' to describe format.",
            default = "<use file extension>"
        ),
        outClass = bld.arg(
            name = "outClass",
            desc = "The fully qualified class name."
        ),
        outFile = bld.arg(
            name = "outFile",
            opt = "out",
            desc = "The file name to output to. Can be '--' to write to stdout.",
            default = "<outClass.class>"
        )
    ).also { bld.done() }

    override fun run(args: Args) {
        // Get format
        val inFormat =
            if (args.inFormat != "<use file extension>") args.inFormat
            else args.inFile.substringAfterLast('.', "<unknown>")
        val script = Translate.inToAst(args.inFile, inFormat)
        val mod = (script.commands.firstOrNull() as? Script.Cmd.Module)?.module ?:
            error("Only a single sexpr for (module) allowed")
        val outStream = when (args.outFile) {
            "<outClass.class>" -> FileOutputStream(args.outClass.substringAfterLast('.') + ".class")
            "--" -> System.out
            else -> FileOutputStream(args.outFile)
        }
        outStream.use { outStream ->
            val ctx = ClsContext(
                packageName = if (!args.outClass.contains('.')) "" else args.outClass.substringBeforeLast('.'),
                className = args.outClass.substringAfterLast('.'),
                mod = mod,
                logger = logger
            )
            AstToAsm.fromModule(ctx)
            outStream.write(ctx.cls.withComputedFramesAndMaxs())
        }
    }

    data class Args(
        val inFile: String,
        val inFormat: String,
        val outClass: String,
        val outFile: String
    )

    companion object : Compile()
}