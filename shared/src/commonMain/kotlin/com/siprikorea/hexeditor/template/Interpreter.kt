package com.siprikorea.hexeditor.template

import com.siprikorea.hexeditor.core.ByteSource
import com.siprikorea.hexeditor.core.CachedReader
import com.siprikorea.hexeditor.core.signExtend
import kotlin.math.sign

class TemplateResult(
    val root: TemplateNode,
    val error: TemplateException?,
    val log: String,
    /** Snapshot of the bytes the template ran against; lazily decoded array elements read from it. */
    val data: ByteSource,
)

/**
 * Executes a parsed template against [data], producing a tree of [TemplateNode]s.
 *
 * Declaring a (non-`local`) variable reads it from the current file position and advances the
 * position, exactly like 010 Editor. Scoping is dynamic: a struct body can see the fields of the
 * structs that contain it, and functions see their caller's variables.
 */
class Interpreter(
    source: ByteSource,
    /** Called periodically; throw from it (e.g. a coroutine's ensureActive) to cancel the run. */
    private val checkpoint: () -> Unit = {},
) {
    private val data = source
    private val reader = CachedReader(source)
    private val fileSize = source.size
    private var pos = 0L
    private var littleEndian = true

    /** The storage unit bitfields are currently being allocated from, if any. */
    private class BitUnit(val offset: Long, val size: Int, val littleEndian: Boolean) {
        var used = 0
    }

    private var bitUnit: BitUnit? = null
    /** null = follow the endianness (right-to-left for little-endian), like 010's default. */
    private var bitsLeftToRight: Boolean? = null
    private var steps = 0L
    private var nodeCount = 0
    private val log = StringBuilder()

    private val types = HashMap<String, TType>()
    private val functions = HashMap<String, Stmt.FuncDef>()
    private val enumConstants = HashMap<String, Long>()

    private val root = TemplateNode("File", "", 0, fileSize, NodeKind.STRUCT)
    private val globalFrame = Frame(root, null)

    private class LocalVar(var value: Value, val type: TType?)

    private class Frame(val node: TemplateNode, val parent: Frame?) {
        val locals = HashMap<String, LocalVar>()
        /** Non-null while executing a union body: fields all start here. */
        var unionStart: Long? = null
        var unionMaxEnd = 0L
    }

    private enum class Flow { NORMAL, BREAK, CONTINUE, RETURN }

    private var returnValue: Value = Value.Void

    private class ExitSignal : RuntimeException()

    init {
        for ((name, t) in PrimitiveType.BY_NAME) types[name] = t
        types["string"] = StringType(false)
        types["wstring"] = StringType(true)
        types["void"] = VoidType
    }

    fun run(program: List<Stmt>): TemplateResult {
        var error: TemplateException? = null
        try {
            execList(program, globalFrame)
        } catch (e: TemplateException) {
            error = e
        } catch (_: ExitSignal) {
        } catch (e: IndexOutOfBoundsException) {
            error = TemplateException("Internal error: ${e.message}", 0)
        }
        root.size = fileSize
        return TemplateResult(root, error, log.toString(), data)
    }

    // ---------------------------------------------------------------- statements

    private fun execList(stmts: List<Stmt>, frame: Frame): Flow {
        for (s in stmts) {
            val flow = exec(s, frame)
            if (flow != Flow.NORMAL) return flow
        }
        return Flow.NORMAL
    }

    private fun exec(s: Stmt, frame: Frame): Flow {
        if ((++steps and 0xFFFF) == 0L) checkpoint()
        if (steps > MAX_STEPS) throw TemplateException("Execution limit exceeded (infinite loop?)", s.line)
        when (s) {
            is Stmt.Block -> return execList(s.body, frame)
            is Stmt.Multi -> return execList(s.body, frame)
            is Stmt.Empty -> Unit
            is Stmt.ExprStmt -> eval(s.expr, frame)
            is Stmt.VarDecl -> declare(s, frame)
            is Stmt.StructDef -> types[s.name] = StructType(s)
            is Stmt.EnumDef -> defineEnum(s, frame)
            is Stmt.Typedef -> types[s.name] = AliasType(s.name, resolveType(s.target), s.arraySize)
            is Stmt.FuncDef -> functions[s.name] = s
            is Stmt.If -> return when {
                truthy(eval(s.cond, frame)) -> exec(s.then, frame)
                s.otherwise != null -> exec(s.otherwise, frame)
                else -> Flow.NORMAL
            }
            is Stmt.While -> while (truthy(eval(s.cond, frame))) {
                when (exec(s.body, frame)) {
                    Flow.BREAK -> break
                    Flow.RETURN -> return Flow.RETURN
                    else -> Unit
                }
            }
            is Stmt.DoWhile -> do {
                when (exec(s.body, frame)) {
                    Flow.BREAK -> break
                    Flow.RETURN -> return Flow.RETURN
                    else -> Unit
                }
            } while (truthy(eval(s.cond, frame)))
            is Stmt.For -> {
                s.init?.let { exec(it, frame) }
                while (s.cond == null || truthy(eval(s.cond, frame))) {
                    when (exec(s.body, frame)) {
                        Flow.BREAK -> break
                        Flow.RETURN -> return Flow.RETURN
                        else -> Unit
                    }
                    s.step?.let { eval(it, frame) }
                }
            }
            is Stmt.Switch -> {
                val v = eval(s.value, frame)
                var start = s.cases.indexOfFirst { c -> c.values.any { equal(v, eval(it, frame)) } }
                if (start < 0) start = s.cases.indexOfFirst { it.isDefault }
                if (start >= 0) {
                    for (i in start until s.cases.size) {
                        when (execList(s.cases[i].body, frame)) {
                            Flow.BREAK -> return Flow.NORMAL
                            Flow.CONTINUE -> return Flow.CONTINUE
                            Flow.RETURN -> return Flow.RETURN
                            Flow.NORMAL -> Unit
                        }
                    }
                }
            }
            is Stmt.Break -> return Flow.BREAK
            is Stmt.Continue -> return Flow.CONTINUE
            is Stmt.Return -> {
                returnValue = s.value?.let { eval(it, frame) } ?: Value.Void
                return Flow.RETURN
            }
        }
        return Flow.NORMAL
    }

    private fun defineEnum(s: Stmt.EnumDef, frame: Frame) {
        val base = resolveType(s.baseType).let { unalias(it) } as? PrimitiveType
            ?: throw TemplateException("Enum base type must be an integer type", s.line)
        val names = LinkedHashMap<Long, String>()
        var next = 0L
        for (e in s.entries) {
            val v = e.value?.let { toLong(eval(it, frame), s.line) } ?: next
            enumConstants[e.name] = v
            if (v !in names) names[v] = e.name
            next = v + 1
        }
        types[s.name] = EnumType(s.name, base, names)
    }

    private fun resolveType(ref: TypeRef): TType =
        types[ref.name] ?: throw TemplateException("Unknown type '${ref.name}'", ref.line)

    private fun unalias(t: TType): TType = if (t is AliasType && t.arraySize == null) unalias(t.target) else t

    // ---------------------------------------------------------------- declarations

    private fun declare(d: Stmt.VarDecl, frame: Frame) {
        val type = resolveType(d.type)
        if (d.isLocal) {
            declareLocal(d, type, frame)
            return
        }
        val unionStart = frame.unionStart
        if (unionStart != null) pos = unionStart

        if (d.bitWidth != null) {
            val node = readBitfield(d, type, toInt(eval(d.bitWidth, frame), d.line))
            if (node != null && d.name.isNotEmpty()) {
                applyAttributes(node, d, frame)
                addField(frame.node, node)
            }
            if (unionStart != null) {
                frame.unionMaxEnd = maxOf(frame.unionMaxEnd, pos)
                pos = unionStart
            }
            return
        }
        // Any ordinary variable closes the current bitfield storage unit.
        bitUnit = null

        val node = if (d.arraySize != null) {
            readArray(d.name, type, toInt(eval(d.arraySize, frame), d.line), d, frame)
        } else {
            readValue(d.name, type, d, frame)
        }
        applyAttributes(node, d, frame)
        addField(frame.node, node)

        if (unionStart != null) {
            frame.unionMaxEnd = maxOf(frame.unionMaxEnd, pos)
            pos = unionStart
        }
    }

    /**
     * Reads `type name : width`. Bits are taken from a storage unit the size of [type]; consecutive
     * bitfields share the unit until it runs out of bits, the type size changes or another variable
     * is declared. A width of 0 just closes the current unit. Returns null for zero-width fields.
     */
    private fun readBitfield(d: Stmt.VarDecl, type: TType, width: Int): TemplateNode? {
        val resolved = unalias(type)
        val prim = when (resolved) {
            is PrimitiveType -> resolved
            is EnumType -> resolved.base
            else -> null
        }
        if (prim == null || prim.isFloat) {
            throw TemplateException("Bitfield '${d.name}' must have an integer or enum type", d.line)
        }
        val unitBits = prim.size * 8
        if (width < 0 || width > unitBits) {
            throw TemplateException("Bitfield width $width out of range for ${prim.name} (0..$unitBits)", d.line)
        }
        if (width == 0) {
            bitUnit = null
            return null
        }
        var unit = bitUnit
        if (unit == null || unit.size != prim.size || unit.used + width > unitBits || pos != unit.offset + unit.size) {
            ensureAvailable(prim.size.toLong(), d.line)
            unit = BitUnit(pos, prim.size, littleEndian)
            bitUnit = unit
            pos += prim.size
        }
        val leftToRight = bitsLeftToRight ?: !unit.littleEndian
        val shift = if (leftToRight) unitBits - unit.used - width else unit.used
        unit.used += width

        val raw = reader.readRaw(unit.offset, unit.size, unit.littleEndian)
        val mask = if (width == 64) -1L else (1L shl width) - 1
        val bits = (raw ushr shift) and mask
        val v = if (prim.signed) (bits shl (64 - width)) shr (64 - width) else bits

        countNode(d)
        val kind = if (resolved is EnumType) NodeKind.ENUM else NodeKind.PRIMITIVE
        val node = TemplateNode(d.name, type.name, unit.offset, unit.size.toLong(), kind)
        node.littleEndian = unit.littleEndian
        node.bitWidth = width
        node.bitOffset = shift
        node.value = Value.Int(v)
        node.display = formatPrimitive(node.value, prim, DisplayFormat.DECIMAL, width)
        if (resolved is EnumType) node.display = resolved.names[v]?.let { "$it (${node.display})" } ?: node.display
        return node
    }

    private fun declareLocal(d: Stmt.VarDecl, type: TType, frame: Frame) {
        val value = if (d.arraySize != null) {
            val n = toInt(eval(d.arraySize, frame), d.line)
            if (n < 0 || n > MAX_LOCAL_ARRAY) throw TemplateException("Invalid local array size $n", d.line)
            Value.Array(MutableList(n) { defaultValue(type) })
        } else {
            d.init?.let { convert(eval(it, frame), type, d.line) } ?: defaultValue(type)
        }
        frame.locals[d.name] = LocalVar(value, if (d.arraySize != null) null else type)
    }

    private fun defaultValue(type: TType): Value = when (val t = unalias(type)) {
        is StringType -> Value.Str("")
        is PrimitiveType -> if (t.isFloat) Value.Float(0.0) else Value.Int(0)
        else -> Value.Int(0)
    }

    /** Reads one value of [type] at the current position. */
    private fun readValue(name: String, type: TType, d: Stmt.VarDecl?, frame: Frame): TemplateNode {
        countNode(d)
        return when (type) {
            is PrimitiveType -> readPrimitive(name, type, type.name, d?.line ?: 0)
            is EnumType -> {
                val node = readPrimitive(name, type.base, type.name, d?.line ?: 0, NodeKind.ENUM)
                val v = (node.value as Value.Int).v
                node.display = type.names[v]?.let { "$it (${node.display})" } ?: node.display
                node
            }
            is StringType -> readString(name, type)
            is StructType -> readStruct(name, type, d?.args.orEmpty(), frame, d?.line ?: 0)
            is AliasType -> if (type.arraySize != null) {
                readArray(name, type.target, toInt(eval(type.arraySize, frame), d?.line ?: 0), d, frame, type.name)
            } else {
                val inner = readValue(name, type.target, d, frame)
                // Re-wrap so the tree shows the typedef name rather than the underlying type.
                retype(inner, type.name)
            }
            VoidType -> throw TemplateException("Cannot declare a void variable", d?.line ?: 0)
        }
    }

    private fun retype(n: TemplateNode, typeName: String): TemplateNode {
        val copy = TemplateNode(n.name, typeName, n.offset, n.size, n.kind)
        copy.copyStructureFrom(n)
        copy.value = n.value
        copy.display = n.display
        copy.elementType = n.elementType
        copy.elementCount = n.elementCount
        copy.littleEndian = n.littleEndian
        return copy
    }

    private fun ensureAvailable(size: Long, line: Int) {
        if (pos < 0 || pos + size > fileSize) {
            throw TemplateException("Read of $size byte(s) at offset $pos is past end of file (size $fileSize)", line)
        }
    }

    private fun readPrimitive(
        name: String,
        t: PrimitiveType,
        typeName: String,
        line: Int,
        kind: NodeKind = NodeKind.PRIMITIVE,
    ): TemplateNode {
        ensureAvailable(t.size.toLong(), line)
        val node = TemplateNode(name, typeName, pos, t.size.toLong(), kind)
        node.littleEndian = littleEndian
        node.value = decodePrimitive(reader, pos, t, littleEndian)
        node.display = formatPrimitive(node.value, t, DisplayFormat.DECIMAL)
        pos += t.size
        return node
    }

    private fun readString(name: String, t: StringType): TemplateNode {
        val start = pos
        val sb = StringBuilder()
        val unit = if (t.wide) 2 else 1
        while (true) {
            if (pos + unit > fileSize) break
            val c = reader.readRaw(pos, unit, littleEndian).toInt()
            pos += unit
            if (c == 0) break
            if (sb.length < MAX_STRING) sb.append(if (t.wide) c.toChar() else (c and 0xFF).toChar())
        }
        val node = TemplateNode(name, t.name, start, pos - start, NodeKind.STRING)
        node.value = Value.Str(sb.toString())
        node.display = quote(sb.toString())
        return node
    }

    private fun readStruct(name: String, type: StructType, args: List<Expr>, caller: Frame, line: Int): TemplateNode {
        val def = type.def
        if (args.size != def.params.size) {
            throw TemplateException("Struct ${def.name} expects ${def.params.size} argument(s), got ${args.size}", line)
        }
        bitUnit = null
        val node = TemplateNode(name, def.name, pos, 0, NodeKind.STRUCT)
        node.littleEndian = littleEndian
        val frame = Frame(node, caller)
        for ((p, a) in def.params.zip(args)) {
            frame.locals[p.name] = LocalVar(eval(a, caller), resolveType(p.type))
        }
        if (def.isUnion) {
            frame.unionStart = pos
            frame.unionMaxEnd = pos
        }
        try {
            execList(def.body, frame)
        } finally {
            if (def.isUnion) pos = frame.unionMaxEnd
            bitUnit = null
            node.size = maxOf(0, pos - node.offset)
        }
        return node
    }

    private fun readArray(
        name: String,
        elemType: TType,
        count: Int,
        d: Stmt.VarDecl?,
        frame: Frame,
        typeName: String? = null,
    ): TemplateNode {
        val line = d?.line ?: 0
        if (count < 0) throw TemplateException("Negative array size $count for '$name'", line)
        val base = unalias(elemType)
        val arrayTypeName = typeName ?: "${elemType.name}[$count]"
        if (base is PrimitiveType) {
            val total = count.toLong() * base.size
            ensureAvailable(total, line)
            val node = TemplateNode(name, arrayTypeName, pos, total, NodeKind.ARRAY)
            node.elementType = base
            node.elementCount = count
            node.littleEndian = littleEndian
            if (base.isChar) {
                val s = decodeChars(reader, pos, count)
                node.value = Value.Str(s)
                node.display = quote(s)
            } else {
                node.value = Value.Node(node)
                node.display = previewArray(node, reader)
            }
            pos += total
            countNode(d)
            return node
        }
        val node = TemplateNode(name, arrayTypeName, pos, 0, NodeKind.ARRAY)
        node.value = Value.Node(node)
        try {
            for (i in 0 until count) {
                val child = readValue("$name[$i]", elemType, d, frame)
                node.addChild(child)
                node.size = maxOf(node.size, child.end - node.offset)
            }
        } finally {
            node.display = "${node.children.size} element(s)"
        }
        return node
    }

    private fun countNode(d: Stmt.VarDecl?) {
        if (++nodeCount > MAX_NODES) throw TemplateException("Too many variables (limit $MAX_NODES)", d?.line ?: 0)
    }

    private fun applyAttributes(node: TemplateNode, d: Stmt.VarDecl, frame: Frame) {
        for (a in d.attributes) {
            when (a.name) {
                "format" -> {
                    val f = (a.value as? Expr.Ident)?.name ?: continue
                    node.format = when (f) {
                        "hex" -> DisplayFormat.HEX
                        "binary" -> DisplayFormat.BINARY
                        "octal" -> DisplayFormat.OCTAL
                        else -> DisplayFormat.DECIMAL
                    }
                    reformat(node)
                }
                "comment" -> node.comment = when (val v = (a.value as? Expr.Ident)?.let { functions[it.name] }) {
                    // `comment=FuncName` calls a function with the variable as its argument.
                    null -> toStr(eval(a.value, frame))
                    else -> toStr(callUser(v, listOf(nodeValue(node)), frame, d.line))
                }
                "name" -> node.name = toStr(eval(a.value, frame))
                // Colors, read/write callbacks, etc. are accepted but ignored.
            }
        }
    }

    private fun reformat(node: TemplateNode) {
        val t = node.elementType
        if (node.kind == NodeKind.PRIMITIVE) {
            val prim = PrimitiveType.BY_NAME[node.typeName] ?: return
            node.display = formatPrimitive(node.value, prim, node.format, node.bitWidth.takeIf { it > 0 } ?: prim.size * 8)
        } else if (t != null && !t.isChar) {
            node.display = previewArray(node, reader)
        }
        node.children.forEach { it.format = node.format; reformat(it) }
    }

    /** Adds [child] to [parent]; redeclaring a name turns the field into an array, like 010. */
    private fun addField(parent: TemplateNode, child: TemplateNode) {
        val existing = parent.fieldsByName[child.name]
        when {
            existing == null -> {
                parent.addChild(child)
                parent.putField(child.name, child)
            }
            existing.isDuplicateArray -> {
                child.name = "${existing.name}[${existing.children.size}]"
                existing.addChild(child)
                existing.size = maxOf(existing.size, child.end - existing.offset)
                existing.display = "${existing.children.size} element(s)"
            }
            else -> {
                val name = child.name
                val array = TemplateNode(name, "${existing.typeName}[]", existing.offset, 0, NodeKind.ARRAY)
                array.isDuplicateArray = true
                array.value = Value.Node(array)
                existing.name = "$name[0]"
                child.name = "$name[1]"
                array.addChild(existing)
                array.addChild(child)
                array.size = maxOf(existing.end, child.end) - array.offset
                array.display = "2 element(s)"
                parent.replaceChild(existing, array)
                parent.putField(name, array)
            }
        }
    }

    // ---------------------------------------------------------------- expressions

    private fun eval(e: Expr, frame: Frame): Value = when (e) {
        is Expr.IntLit -> Value.Int(e.value)
        is Expr.FloatLit -> Value.Float(e.value)
        is Expr.StrLit -> Value.Str(e.value)
        is Expr.Ident -> lookup(e.name, frame, e.line)
        is Expr.Member -> member(eval(e.target, frame), e.name, e.line)
        is Expr.Index -> index(eval(e.target, frame), toInt(eval(e.index, frame), e.line), e.line)
        is Expr.Call -> call(e, frame)
        is Expr.Unary -> unary(e.op, eval(e.operand, frame), e.line)
        is Expr.Binary -> when (e.op) {
            "&&" -> Value.Int(if (truthy(eval(e.left, frame)) && truthy(eval(e.right, frame))) 1 else 0)
            "||" -> Value.Int(if (truthy(eval(e.left, frame)) || truthy(eval(e.right, frame))) 1 else 0)
            "," -> { eval(e.left, frame); eval(e.right, frame) }
            else -> binary(e.op, eval(e.left, frame), eval(e.right, frame), e.line)
        }
        is Expr.Ternary -> if (truthy(eval(e.cond, frame))) eval(e.then, frame) else eval(e.otherwise, frame)
        is Expr.Assign -> {
            val rhs = eval(e.value, frame)
            val newValue = if (e.op == "=") rhs else binary(e.op.dropLast(1), eval(e.target, frame), rhs, e.line)
            assign(e.target, newValue, frame)
        }
        is Expr.IncDec -> {
            val old = eval(e.target, frame)
            val new = binary(if (e.op == "++") "+" else "-", old, Value.Int(1), e.line)
            val stored = assign(e.target, new, frame)
            if (e.prefix) stored else old
        }
        is Expr.SizeOfType -> Value.Int(sizeOfType(resolveType(e.type), e.line).toLong())
        is Expr.Cast -> convert(eval(e.operand, frame), resolveType(e.type), e.line)
    }

    private fun lookup(name: String, frame: Frame, line: Int): Value {
        findVar(name, frame)?.let { return it }
        enumConstants[name]?.let { return Value.Int(it) }
        throw TemplateException("Undefined variable '$name'", line)
    }

    private fun findVar(name: String, frame: Frame): Value? {
        var f: Frame? = frame
        while (f != null) {
            f.locals[name]?.let { return it.value }
            f.node.fieldsByName[name]?.let { return nodeValue(it) }
            f = f.parent
        }
        return null
    }

    private fun nodeValue(n: TemplateNode): Value = when (n.kind) {
        NodeKind.PRIMITIVE, NodeKind.ENUM, NodeKind.STRING -> n.value
        NodeKind.STRUCT -> Value.Node(n)
        NodeKind.ARRAY -> n.value
    }

    /** Used without an index, a duplicate array stands for its most recently declared element. */
    private fun latest(v: Value): Value =
        if (v is Value.Node && v.node.isDuplicateArray) nodeValue(v.node.children.last()) else v

    private fun member(target: Value, name: String, line: Int): Value {
        val node = (latest(target) as? Value.Node)?.node
            ?: throw TemplateException("'.$name' applied to a non-struct value", line)
        val field = node.fieldsByName[name] ?: throw TemplateException("No field '$name' in ${node.name}", line)
        return nodeValue(field)
    }

    private fun index(target: Value, i: Int, line: Int): Value = when (target) {
        is Value.Str -> {
            if (i !in target.v.indices) throw TemplateException("Index $i out of range", line)
            Value.Int(target.v[i].code.toLong())
        }
        is Value.Array -> target.items.getOrNull(i) ?: throw TemplateException("Index $i out of range", line)
        is Value.Node -> {
            val n = target.node
            val et = n.elementType
            when {
                et != null -> {
                    if (i !in 0 until n.elementCount) throw TemplateException("Index $i out of range for ${n.name}", line)
                    decodePrimitive(reader, n.offset + i.toLong() * et.size, et, n.littleEndian)
                }
                n.kind == NodeKind.ARRAY -> nodeValue(
                    n.children.getOrNull(i) ?: throw TemplateException("Index $i out of range for ${n.name}", line)
                )
                else -> throw TemplateException("${n.name} is not an array", line)
            }
        }
        else -> throw TemplateException("Value is not an array", line)
    }

    private fun assign(target: Expr, value: Value, frame: Frame): Value {
        when (target) {
            is Expr.Ident -> {
                var f: Frame? = frame
                while (f != null) {
                    val local = f.locals[target.name]
                    if (local != null) {
                        local.value = local.type?.let { convert(value, it, target.line) } ?: value
                        return local.value
                    }
                    if (f.node.fieldsByName.containsKey(target.name)) {
                        throw TemplateException("Cannot assign to file variable '${target.name}'", target.line)
                    }
                    f = f.parent
                }
                throw TemplateException("Undefined variable '${target.name}'", target.line)
            }
            is Expr.Index -> {
                val arr = eval(target.target, frame) as? Value.Array
                    ?: throw TemplateException("Only local arrays can be assigned", target.line)
                val i = toInt(eval(target.index, frame), target.line)
                if (i !in arr.items.indices) throw TemplateException("Index $i out of range", target.line)
                arr.items[i] = value
                return value
            }
            else -> throw TemplateException("Invalid assignment target", target.line)
        }
    }

    private fun unary(op: String, v: Value, line: Int): Value = when (op) {
        "-" -> if (v is Value.Float) Value.Float(-v.v) else Value.Int(-toLong(v, line))
        "+" -> v
        "!" -> Value.Int(if (truthy(v)) 0 else 1)
        "~" -> Value.Int(toLong(v, line).inv())
        else -> throw TemplateException("Unknown operator $op", line)
    }

    private fun binary(op: String, left: Value, right: Value, line: Int): Value {
        val a = latest(left)
        val b = latest(right)
        if (a is Value.Str || b is Value.Str) {
            return when (op) {
                "+" -> Value.Str(toStr(a) + if (b is Value.Int) b.v.toInt().toChar().toString() else toStr(b))
                "==" -> bool(toStr(a) == toStr(b))
                "!=" -> bool(toStr(a) != toStr(b))
                "<" -> bool(toStr(a) < toStr(b))
                ">" -> bool(toStr(a) > toStr(b))
                "<=" -> bool(toStr(a) <= toStr(b))
                ">=" -> bool(toStr(a) >= toStr(b))
                else -> throw TemplateException("Operator '$op' not supported on strings", line)
            }
        }
        if (a is Value.Float || b is Value.Float) {
            val x = toDouble(a, line)
            val y = toDouble(b, line)
            return when (op) {
                "+" -> Value.Float(x + y)
                "-" -> Value.Float(x - y)
                "*" -> Value.Float(x * y)
                "/" -> Value.Float(x / y)
                "==" -> bool(x == y)
                "!=" -> bool(x != y)
                "<" -> bool(x < y)
                ">" -> bool(x > y)
                "<=" -> bool(x <= y)
                ">=" -> bool(x >= y)
                else -> throw TemplateException("Operator '$op' not supported on floats", line)
            }
        }
        val x = toLong(a, line)
        val y = toLong(b, line)
        return when (op) {
            "+" -> Value.Int(x + y)
            "-" -> Value.Int(x - y)
            "*" -> Value.Int(x * y)
            "/" -> if (y == 0L) throw TemplateException("Division by zero", line) else Value.Int(x / y)
            "%" -> if (y == 0L) throw TemplateException("Division by zero", line) else Value.Int(x % y)
            "&" -> Value.Int(x and y)
            "|" -> Value.Int(x or y)
            "^" -> Value.Int(x xor y)
            "<<" -> Value.Int(x shl y.toInt())
            ">>" -> Value.Int(x shr y.toInt())
            "==" -> bool(x == y)
            "!=" -> bool(x != y)
            "<" -> bool(x < y)
            ">" -> bool(x > y)
            "<=" -> bool(x <= y)
            ">=" -> bool(x >= y)
            else -> throw TemplateException("Unknown operator $op", line)
        }
    }

    private fun equal(a: Value, b: Value) = truthy(binary("==", a, b, 0))

    private fun bool(b: Boolean) = Value.Int(if (b) 1 else 0)

    private fun truthy(v: Value): Boolean = when (v) {
        is Value.Int -> v.v != 0L
        is Value.Float -> v.v != 0.0
        is Value.Str -> v.v.isNotEmpty()
        is Value.Node, is Value.Array -> true
        Value.Void -> false
    }

    private fun toLong(value: Value, line: Int): Long = when (val v = latest(value)) {
        is Value.Int -> v.v
        is Value.Float -> v.v.toLong()
        is Value.Str -> if (v.v.length == 1) v.v[0].code.toLong() else
            throw TemplateException("Expected a number but got string \"${v.v}\"", line)
        is Value.Node -> throw TemplateException("Expected a number but got ${v.node.typeName} ${v.node.name}", line)
        else -> throw TemplateException("Expected a number", line)
    }

    private fun toInt(v: Value, line: Int): Int {
        val l = toLong(v, line)
        if (l < Int.MIN_VALUE || l > Int.MAX_VALUE) throw TemplateException("Value $l out of range", line)
        return l.toInt()
    }

    private fun toDouble(v: Value, line: Int): Double = latest(v).let { if (it is Value.Float) it.v else toLong(it, line).toDouble() }

    private fun toStr(v: Value): String = when (v) {
        is Value.Str -> v.v
        is Value.Int -> v.v.toString()
        is Value.Float -> v.v.toString()
        is Value.Node -> v.node.display
        is Value.Array -> v.items.joinToString(", ", "[", "]") { toStr(it) }
        Value.Void -> ""
    }

    /** Converts [v] to [type], truncating integers to the type's width like C does. */
    private fun convert(v: Value, type: TType, line: Int): Value = when (val t = unalias(type)) {
        is PrimitiveType -> when {
            t.isFloat -> Value.Float(toDouble(v, line).let { if (t.size == 4) it.toFloat().toDouble() else it })
            else -> {
                val raw = toLong(v, line)
                Value.Int(if (t.signed) signExtend(raw, t.size) else truncate(raw, t.size))
            }
        }
        is EnumType -> Value.Int(toLong(v, line))
        is StringType -> Value.Str(toStr(v))
        else -> v
    }

    private fun sizeOfType(t: TType, line: Int): Int = when (t) {
        is PrimitiveType -> t.size
        is EnumType -> t.base.size
        is AliasType -> sizeOfType(t.target, line) * (t.arraySize?.let { toInt(eval(it, globalFrame), line) } ?: 1)
        else -> throw TemplateException("sizeof(${t.name}) requires a fixed-size type", line)
    }

    // ---------------------------------------------------------------- functions

    private fun call(e: Expr.Call, frame: Frame): Value {
        functions[e.name]?.let { f -> return callUser(f, e.args.map { eval(it, frame) }, frame, e.line) }

        // Built-ins whose argument is a variable rather than a value.
        when (e.name) {
            "sizeof", "startof", "exists" -> {
                val arg = e.args.singleOrNull() ?: throw TemplateException("${e.name} expects 1 argument", e.line)
                if (e.name == "exists") {
                    return bool(runCatching { eval(arg, frame) }.isSuccess)
                }
                val node = resolveNode(arg, frame)
                if (node != null) return Value.Int(if (e.name == "sizeof") node.size else node.offset)
                if (e.name == "sizeof") {
                    if (arg is Expr.Ident && arg.name in types) return Value.Int(sizeOfType(types.getValue(arg.name), e.line).toLong())
                    return when (val v = eval(arg, frame)) {
                        is Value.Str -> Value.Int(v.v.length.toLong())
                        is Value.Float -> Value.Int(8)
                        else -> Value.Int(4)
                    }
                }
                throw TemplateException("startof() requires a file variable", e.line)
            }
        }

        val args = e.args.map { eval(it, frame) }
        fun arg(i: Int): Value = args.getOrNull(i) ?: throw TemplateException("${e.name}: missing argument ${i + 1}", e.line)
        fun posArg(i: Int): Long = if (args.size > i) toLong(args[i], e.line) else pos
        fun readAt(type: String): Value {
            val t = PrimitiveType.BY_NAME.getValue(type)
            val p = posArg(0)
            if (p < 0 || p + t.size > fileSize) throw TemplateException("${e.name}: offset $p past end of file", e.line)
            return decodePrimitive(reader, p, t, littleEndian)
        }

        return when (e.name) {
            "LittleEndian" -> { littleEndian = true; Value.Void }
            "BigEndian" -> { littleEndian = false; Value.Void }
            "IsLittleEndian" -> bool(littleEndian)
            "IsBigEndian" -> bool(!littleEndian)
            "FTell" -> Value.Int(pos)
            "FSeek" -> {
                val p = toLong(arg(0), e.line)
                if (p < 0 || p > fileSize) Value.Int(-1) else { pos = p; bitUnit = null; Value.Int(0) }
            }
            "FSkip" -> {
                val p = pos + toLong(arg(0), e.line)
                if (p < 0 || p > fileSize) Value.Int(-1) else { pos = p; bitUnit = null; Value.Int(0) }
            }
            "FileSize" -> Value.Int(fileSize)
            "FEof" -> bool(pos >= fileSize)
            "ReadByte", "ReadChar" -> readAt("char")
            "ReadUByte", "ReadUChar" -> readAt("uchar")
            "ReadShort" -> readAt("short")
            "ReadUShort" -> readAt("ushort")
            "ReadInt" -> readAt("int")
            "ReadUInt" -> readAt("uint")
            "ReadInt64", "ReadQuad" -> readAt("int64")
            "ReadUInt64", "ReadUQuad" -> readAt("uint64")
            "ReadFloat" -> readAt("float")
            "ReadDouble" -> readAt("double")
            "ReadString" -> {
                val p = posArg(0)
                val max = if (args.size > 1) toInt(args[1], e.line) else -1
                val sb = StringBuilder()
                var i = p
                while (i < fileSize && reader.byte(i) != 0 && (max < 0 || sb.length < max) && sb.length < MAX_STRING) {
                    sb.append(reader.byte(i).toChar())
                    i++
                }
                Value.Str(sb.toString())
            }
            "Printf" -> { log.append(sprintf(toStr(arg(0)), args.drop(1))); Value.Void }
            "Warning" -> { log.append("Warning: ").append(sprintf(toStr(arg(0)), args.drop(1))).append('\n'); Value.Void }
            "Str", "SPrintf" -> Value.Str(sprintf(toStr(arg(0)), args.drop(1)))
            "Strlen" -> Value.Int(toStr(arg(0)).length.toLong())
            "Strcmp" -> Value.Int(toStr(arg(0)).compareTo(toStr(arg(1))).sign.toLong())
            "Stricmp" -> Value.Int(toStr(arg(0)).compareTo(toStr(arg(1)), ignoreCase = true).sign.toLong())
            "Strstr" -> Value.Int(toStr(arg(0)).indexOf(toStr(arg(1))).toLong())
            "SubStr" -> {
                val s = toStr(arg(0))
                val start = toInt(arg(1), e.line).coerceIn(0, s.length)
                val count = if (args.size > 2) toInt(args[2], e.line) else -1
                Value.Str(if (count < 0) s.substring(start) else s.substring(start, minOf(s.length, start + count)))
            }
            "ToUpper" -> Value.Str(toStr(arg(0)).uppercase())
            "ToLower" -> Value.Str(toStr(arg(0)).lowercase())
            "Abs" -> arg(0).let { if (it is Value.Float) Value.Float(kotlin.math.abs(it.v)) else Value.Int(kotlin.math.abs(toLong(it, e.line))) }
            "Min" -> if (toDouble(arg(0), e.line) <= toDouble(arg(1), e.line)) arg(0) else arg(1)
            "Max" -> if (toDouble(arg(0), e.line) >= toDouble(arg(1), e.line)) arg(0) else arg(1)
            "Assert" -> {
                if (!truthy(arg(0))) {
                    throw TemplateException(if (args.size > 1) toStr(args[1]) else "Assertion failed", e.line)
                }
                Value.Void
            }
            "Exit" -> throw ExitSignal()
            // Accepted for compatibility with existing templates; no visual effect here.
            "SetBackColor", "SetForeColor", "SetColor", "DisplayFormatHex", "DisplayFormatDecimal",
            "RequiresVersion", "BitfieldDisablePadding", "BitfieldEnablePadding" -> Value.Void
            "BitfieldLeftToRight" -> { bitsLeftToRight = true; Value.Void }
            "BitfieldRightToLeft" -> { bitsLeftToRight = false; Value.Void }
            else -> throw TemplateException("Unknown function '${e.name}'", e.line)
        }
    }

    private fun callUser(f: Stmt.FuncDef, args: List<Value>, caller: Frame, line: Int): Value {
        if (args.size != f.params.size) {
            throw TemplateException("${f.name} expects ${f.params.size} argument(s), got ${args.size}", line)
        }
        val frame = Frame(caller.node, caller)
        for ((p, a) in f.params.zip(args)) {
            val type = resolveType(p.type)
            frame.locals[p.name] = LocalVar(if (a is Value.Node || a is Value.Array) a else convert(a, type, line), null)
        }
        returnValue = Value.Void
        execList(f.body.body, frame)
        val r = returnValue
        returnValue = Value.Void
        return if (f.returnType.name == "void") Value.Void else r
    }

    /** Resolves an expression naming a file variable (`a`, `a.b`, `a[3]`) to its node. */
    private fun resolveNode(e: Expr, frame: Frame): TemplateNode? = when (e) {
        is Expr.Ident -> {
            var f: Frame? = frame
            var found: TemplateNode? = null
            while (f != null && found == null) {
                if (f.locals.containsKey(e.name)) break
                found = f.node.fieldsByName[e.name]
                f = f.parent
            }
            found
        }
        is Expr.Member -> resolveNode(e.target, frame)?.fieldsByName?.get(e.name)
        is Expr.Index -> resolveNode(e.target, frame)?.let { n ->
            val i = toInt(eval(e.index, frame), e.line)
            val et = n.elementType
            when {
                et != null && i in 0 until n.elementCount -> elementNode(n, i)
                else -> n.children.getOrNull(i)
            }
        }
        else -> null
    }

    private fun sprintf(fmt: String, args: List<Value>): String {
        val sb = StringBuilder()
        var argi = 0
        var i = 0
        while (i < fmt.length) {
            val c = fmt[i++]
            if (c != '%') { sb.append(c); continue }
            if (i < fmt.length && fmt[i] == '%') { sb.append('%'); i++; continue }
            val specStart = i
            while (i < fmt.length && (fmt[i] in "-+ 0#." || fmt[i].isDigit())) i++
            while (i < fmt.length && fmt[i] in "lhLI") i++ // length modifiers
            if (i >= fmt.length) break
            val conv = fmt[i++]
            val flags = fmt.substring(specStart, i - 1).filter { it !in "lhLI" }
            val zeroPad = flags.startsWith("0")
            val leftAlign = flags.startsWith("-")
            val precision = flags.substringAfter('.', "").toIntOrNull()
            val width = flags.substringBefore('.').trimStart('-', '0', '+', ' ', '#').toIntOrNull() ?: 0
            val v = args.getOrNull(argi++) ?: Value.Void
            var text = when (conv) {
                'd', 'i' -> toLong(v, 0).toString()
                'u' -> toLong(v, 0).toULong().toString()
                'x' -> toLong(v, 0).toULong().toString(16)
                'X' -> toLong(v, 0).toULong().toString(16).uppercase()
                'o' -> toLong(v, 0).toULong().toString(8)
                'c' -> toLong(v, 0).toInt().toChar().toString()
                's' -> toStr(v)
                'f', 'g', 'e' -> formatFixed(toDouble(v, 0), precision ?: 6)
                else -> "%$conv"
            }
            if (text.length < width) {
                text = when {
                    leftAlign -> text.padEnd(width)
                    zeroPad && conv != 's' -> if (text.startsWith("-")) "-" + text.drop(1).padStart(width - 1, '0') else text.padStart(width, '0')
                    else -> text.padStart(width)
                }
            }
            sb.append(text)
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_STEPS = 20_000_000L
        private const val MAX_NODES = 1_000_000
        private const val MAX_LOCAL_ARRAY = 10_000_000
        /** Strings longer than this are truncated in values and display (the file range is exact). */
        internal const val MAX_STRING = 65_536

        fun run(source: String, data: ByteArray): TemplateResult =
            run(source, com.siprikorea.hexeditor.core.ArrayByteSource(data))

        fun run(source: String, data: ByteSource, checkpoint: () -> Unit = {}): TemplateResult {
            val program = try {
                Parser.parse(source)
            } catch (e: TemplateException) {
                return TemplateResult(TemplateNode("File", "", 0, data.size, NodeKind.STRUCT), e, "", data)
            }
            return Interpreter(data, checkpoint).run(program)
        }
    }
}

// ---------------------------------------------------------------- decoding helpers

internal fun truncate(raw: Long, size: Int): Long = if (size >= 8) raw else raw and ((1L shl (size * 8)) - 1)

internal fun decodePrimitive(reader: CachedReader, offset: Long, t: PrimitiveType, littleEndian: Boolean): Value {
    val raw = reader.readRaw(offset, t.size, littleEndian)
    return when {
        t.isFloat && t.size == 4 -> Value.Float(Float.fromBits(raw.toInt()).toDouble())
        t.isFloat -> Value.Float(Double.fromBits(raw))
        t.signed -> Value.Int(signExtend(raw, t.size))
        else -> Value.Int(raw)
    }
}

internal fun decodeChars(reader: CachedReader, offset: Long, count: Int): String {
    val sb = StringBuilder()
    for (i in 0 until minOf(count, Interpreter.MAX_STRING)) {
        val c = reader.byte(offset + i)
        if (c == 0) break
        sb.append(c.toChar())
    }
    return sb.toString()
}

/** [bits] is the field width; it differs from the type's size only for bitfields. */
internal fun formatPrimitive(v: Value, t: PrimitiveType, format: DisplayFormat, bits: Int = t.size * 8): String {
    if (v is Value.Float) {
        val f = v.v
        return if (t.size == 4) f.toFloat().toString() else f.toString()
    }
    val raw = (v as? Value.Int)?.v ?: return ""
    val unsignedBits = if (bits >= 64) raw else raw and ((1L shl bits) - 1)
    return when (format) {
        DisplayFormat.HEX -> "0x" + unsignedBits.toULong().toString(16).uppercase().padStart((bits + 3) / 4, '0')
        DisplayFormat.BINARY -> unsignedBits.toULong().toString(2).padStart(bits, '0')
        DisplayFormat.OCTAL -> "0" + unsignedBits.toULong().toString(8)
        DisplayFormat.DECIMAL -> {
            val num = if (!t.signed && t.size == 8) raw.toULong().toString() else raw.toString()
            if (bits == 8 && unsignedBits in 0x20..0x7E) "$num '${unsignedBits.toInt().toChar()}'" else num
        }
    }
}

/** A transient node for element [i] of a lazily-decoded primitive array. */
fun elementNode(array: TemplateNode, i: Int, reader: CachedReader? = null): TemplateNode {
    val t = array.elementType ?: error("not a primitive array")
    val n = TemplateNode("${array.name}[$i]", t.name, array.offset + i.toLong() * t.size, t.size.toLong(), NodeKind.PRIMITIVE)
    n.littleEndian = array.littleEndian
    n.format = array.format
    if (reader != null) {
        n.value = decodePrimitive(reader, n.offset, t, array.littleEndian)
        n.display = formatPrimitive(n.value, t, array.format)
    }
    return n
}

private fun quote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 || c.code in 0x7F..0x9F -> sb.append("\\x").append(c.code.toString(16).padStart(2, '0'))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

/** Short preview of the first few elements, shown as the array's value. */
private fun previewArray(node: TemplateNode, reader: CachedReader): String {
    val t = node.elementType!!
    val shown = minOf(node.elementCount, 8)
    val parts = (0 until shown).map {
        formatPrimitive(decodePrimitive(reader, node.offset + it.toLong() * t.size, t, node.littleEndian), t, node.format)
            .substringBefore(' ')
    }
    return parts.joinToString(" ") + if (node.elementCount > shown) " …" else ""
}

private fun formatFixed(d: Double, precision: Int): String {
    if (d.isNaN() || d.isInfinite()) return d.toString()
    val neg = d < 0
    var scale = 1.0
    repeat(precision) { scale *= 10 }
    val rounded = kotlin.math.round(kotlin.math.abs(d) * scale)
    val intPart = (rounded / scale).toLong()
    val frac = (rounded - intPart * scale).toLong()
    val s = if (precision == 0) intPart.toString() else "$intPart.${frac.toString().padStart(precision, '0')}"
    return if (neg) "-$s" else s
}
