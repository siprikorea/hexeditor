package com.siprikorea.hexeditor.template

sealed class TType {
    abstract val name: String
}

class PrimitiveType(
    override val name: String,
    val size: Int,
    val signed: Boolean,
    val isFloat: Boolean = false,
    val isChar: Boolean = false,
) : TType() {

    companion object {
        private fun group(size: Int, signed: Boolean, vararg names: String) =
            names.map { PrimitiveType(it, size, signed, isChar = it in CHAR_NAMES) }

        private val CHAR_NAMES = setOf("char", "CHAR")

        private val TYPES: List<PrimitiveType> =
            group(1, true, "char", "byte", "CHAR", "BYTE", "int8", "INT8") +
                group(1, false, "uchar", "ubyte", "UCHAR", "UBYTE", "uint8", "UINT8", "bool") +
                group(2, true, "short", "int16", "SHORT", "INT16") +
                group(2, false, "ushort", "uint16", "USHORT", "UINT16", "WORD") +
                group(4, true, "int", "int32", "long", "INT", "INT32", "LONG") +
                group(4, false, "uint", "uint32", "ulong", "UINT", "UINT32", "ULONG", "DWORD") +
                group(8, true, "int64", "quad", "QUAD", "INT64", "__int64") +
                group(8, false, "uint64", "uquad", "UQUAD", "UINT64", "QWORD", "__uint64") +
                listOf(
                    PrimitiveType("float", 4, true, isFloat = true),
                    PrimitiveType("FLOAT", 4, true, isFloat = true),
                    PrimitiveType("double", 8, true, isFloat = true),
                    PrimitiveType("DOUBLE", 8, true, isFloat = true),
                )

        val BY_NAME: Map<String, PrimitiveType> = TYPES.associateBy { it.name }
        val ALL_NAMES: Set<String> = BY_NAME.keys

        /** `unsigned char` -> `uchar`, `unsigned int` -> `uint`, ... */
        fun unsignedVariant(name: String): String? = when (name) {
            "char" -> "uchar"
            "byte" -> "ubyte"
            "short" -> "ushort"
            "int" -> "uint"
            "long" -> "ulong"
            "int64", "quad", "__int64" -> "uint64"
            else -> null
        }
    }
}

class StringType(val wide: Boolean) : TType() {
    override val name: String get() = if (wide) "wstring" else "string"
}

class StructType(val def: Stmt.StructDef) : TType() {
    override val name: String get() = def.name
    val isUnion: Boolean get() = def.isUnion
}

class EnumType(override val name: String, val base: PrimitiveType, val names: Map<Long, String>) : TType()

/** `typedef target alias[size];` – the alias keeps its own name for display. */
class AliasType(override val name: String, val target: TType, val arraySize: Expr?) : TType()

object VoidType : TType() {
    override val name = "void"
}

/** Runtime values produced by template expressions. */
sealed class Value {
    data class Int(val v: Long) : Value()
    data class Float(val v: Double) : Value()
    data class Str(val v: String) : Value()
    /** A struct or (non-char) array read from the file. */
    data class Node(val node: TemplateNode) : Value()
    /** A `local` array. */
    class Array(val items: MutableList<Value>) : Value()
    object Void : Value()
}

enum class NodeKind { PRIMITIVE, ENUM, STRING, STRUCT, ARRAY }

enum class DisplayFormat { DECIMAL, HEX, BINARY, OCTAL }

/** One variable mapped onto the file: the result of running a template. */
class TemplateNode(
    var name: String,
    val typeName: String,
    val offset: Long,
    var size: Long,
    val kind: NodeKind,
) {
    // Allocated lazily: most nodes are leaves, and templates may produce a million of them.
    private var childList: ArrayList<TemplateNode>? = null
    private var fields: HashMap<String, TemplateNode>? = null

    val children: List<TemplateNode> get() = childList ?: emptyList()
    internal val fieldsByName: Map<String, TemplateNode> get() = fields ?: emptyMap()

    internal fun addChild(child: TemplateNode) {
        (childList ?: ArrayList<TemplateNode>(4).also { childList = it }).add(child)
    }

    internal fun replaceChild(old: TemplateNode, new: TemplateNode) {
        val list = childList ?: return
        list[list.indexOf(old)] = new
    }

    internal fun putField(name: String, node: TemplateNode) {
        (fields ?: HashMap<String, TemplateNode>().also { fields = it })[name] = node
    }

    internal fun copyStructureFrom(other: TemplateNode) {
        other.childList?.let { childList = ArrayList(it) }
        other.fields?.let { fields = HashMap(it) }
    }

    var value: Value = Value.Void
    var display: String = ""
    var comment: String? = null

    /** For arrays of primitives: elements are not materialised, they're decoded on demand. */
    var elementType: PrimitiveType? = null
    var elementCount: Int = 0
    var littleEndian: Boolean = true
    var format: DisplayFormat = DisplayFormat.DECIMAL

    /** For bitfields: the field's width and its position (0 = least significant bit of the storage unit). */
    var bitWidth: Int = 0
    var bitOffset: Int = 0
    val isBitfield: Boolean get() = bitWidth > 0

    /** True for arrays formed by declaring the same field name repeatedly. */
    internal var isDuplicateArray = false

    val end: Long get() = offset + size

    val childCount: Int get() = if (elementType != null) elementCount else children.size

    override fun toString(): String = "$typeName $name @$offset+$size = $display"
}
