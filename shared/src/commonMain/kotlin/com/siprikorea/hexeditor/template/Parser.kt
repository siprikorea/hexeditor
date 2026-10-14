package com.siprikorea.hexeditor.template

/**
 * Recursive-descent parser for binary templates.
 *
 * Like C, the grammar is ambiguous without knowing which identifiers are types, so the parser
 * tracks every type name declared so far (built-ins, structs, enums and typedefs).
 */
class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private val typeNames = PrimitiveType.ALL_NAMES.toMutableSet().apply {
        addAll(listOf("void", "string", "wstring", "bool"))
    }
    private var anonCounter = 0

    fun parseProgram(): List<Stmt> {
        val out = mutableListOf<Stmt>()
        while (peek.type != Tok.EOF) out += statement()
        return out
    }

    // ---- token helpers ----

    private val peek get() = tokens[pos]
    private fun peekAt(n: Int) = tokens[minOf(pos + n, tokens.size - 1)]
    private fun advance() = tokens[pos++]
    private fun isOp(text: String, t: Token = peek) = t.type == Tok.OP && t.text == text
    private fun isKw(text: String, t: Token = peek) = t.type == Tok.IDENT && t.text == text

    private fun acceptOp(text: String): Boolean {
        if (!isOp(text)) return false
        pos++
        return true
    }

    private fun expectOp(text: String): Token {
        if (!isOp(text)) error("Expected '$text' but found '${peek.text}'")
        return advance()
    }

    private fun expectIdent(): String {
        if (peek.type != Tok.IDENT) error("Expected identifier but found '${peek.text}'")
        return advance().text
    }

    private fun error(msg: String): Nothing = throw TemplateException(msg, peek.line)

    private fun isTypeStart(t: Token = peek, next: Token = peekAt(1)): Boolean {
        if (t.type != Tok.IDENT) return false
        if (t.text == "unsigned" || t.text == "signed") return true
        if (t.text == "struct" || t.text == "union" || t.text == "enum") return next.type == Tok.IDENT
        return t.text in typeNames
    }

    // ---- statements ----

    private fun statement(): Stmt {
        val line = peek.line
        if (isOp("{")) return block()
        if (acceptOp(";")) return Stmt.Empty(line)
        if (peek.type == Tok.IDENT) {
            when (peek.text) {
                "if" -> return ifStmt()
                "while" -> {
                    advance(); expectOp("(")
                    val cond = expression(); expectOp(")")
                    return Stmt.While(cond, statement(), line)
                }
                "do" -> {
                    advance()
                    val body = statement()
                    if (!isKw("while")) error("Expected 'while'")
                    advance(); expectOp("(")
                    val cond = expression(); expectOp(")"); expectOp(";")
                    return Stmt.DoWhile(body, cond, line)
                }
                "for" -> return forStmt()
                "switch" -> return switchStmt()
                "break" -> { advance(); expectOp(";"); return Stmt.Break(line) }
                "continue" -> { advance(); expectOp(";"); return Stmt.Continue(line) }
                "return" -> {
                    advance()
                    val v = if (isOp(";")) null else expression()
                    expectOp(";")
                    return Stmt.Return(v, line)
                }
                "typedef" -> return typedef()
                "struct", "union" -> if (peekAt(1).type != Tok.IDENT || isOp("{", peekAt(2)) || isOp("(", peekAt(2))) {
                    return structStatement()
                }
                "enum" -> if (peekAt(1).type != Tok.IDENT || isOp("{", peekAt(2)) || isOp("<", peekAt(1))) {
                    return enumStatement()
                }
                "local", "const" -> return declaration()
            }
            if (isTypeStart()) return declaration()
        }
        val e = expression()
        expectOp(";")
        return Stmt.ExprStmt(e, line)
    }

    private fun block(): Stmt.Block {
        val line = expectOp("{").line
        val body = mutableListOf<Stmt>()
        while (!isOp("}")) {
            if (peek.type == Tok.EOF) error("Missing '}'")
            body += statement()
        }
        advance()
        return Stmt.Block(body, line)
    }

    private fun ifStmt(): Stmt {
        val line = advance().line
        expectOp("(")
        val cond = expression()
        expectOp(")")
        val then = statement()
        val otherwise = if (isKw("else")) { advance(); statement() } else null
        return Stmt.If(cond, then, otherwise, line)
    }

    private fun forStmt(): Stmt {
        val line = advance().line
        expectOp("(")
        val init = when {
            isOp(";") -> { advance(); null }
            isKw("local") || isKw("const") || isTypeStart() -> declaration()
            else -> Stmt.ExprStmt(expression(), line).also { expectOp(";") }
        }
        val cond = if (isOp(";")) null else expression()
        expectOp(";")
        val step = if (isOp(")")) null else expression()
        expectOp(")")
        return Stmt.For(init, cond, step, statement(), line)
    }

    private fun switchStmt(): Stmt {
        val line = advance().line
        expectOp("(")
        val value = expression()
        expectOp(")")
        expectOp("{")
        val cases = mutableListOf<Case>()
        while (!acceptOp("}")) {
            val values = mutableListOf<Expr>()
            var isDefault = false
            // Several labels may share one body: `case 1: case 2: ...`.
            while (isKw("case") || isKw("default")) {
                if (advance().text == "default") isDefault = true else values += expression()
                expectOp(":")
            }
            if (values.isEmpty() && !isDefault) error("Expected 'case' or 'default'")
            val body = mutableListOf<Stmt>()
            while (!isKw("case") && !isKw("default") && !isOp("}")) body += statement()
            cases += Case(values, isDefault, body)
        }
        return Stmt.Switch(value, cases, line)
    }

    private fun typeRef(): TypeRef {
        val line = peek.line
        if (isKw("struct") || isKw("union") || isKw("enum")) advance()
        var signed: String? = null
        if (isKw("unsigned") || isKw("signed")) signed = advance().text
        val name = if (peek.type == Tok.IDENT && peek.text in typeNames) advance().text else {
            if (signed == null) error("Unknown type '${peek.text}'")
            "int"
        }
        val resolved = if (signed == "unsigned") PrimitiveType.unsignedVariant(name) ?: name else name
        return TypeRef(resolved, line)
    }

    /** `[local|const] Type name[size] <attrs> = init, name2 ...;` or a function definition. */
    private fun declaration(): Stmt {
        val line = peek.line
        var isLocal = false
        var isConst = false
        while (isKw("local") || isKw("const")) {
            if (advance().text == "local") isLocal = true else isConst = true
        }
        val type = typeRef()
        return declarators(type, isLocal || isConst, isConst, line, allowFunction = !isLocal && !isConst)
    }

    private fun declarators(type: TypeRef, isLocal: Boolean, isConst: Boolean, line: Int, allowFunction: Boolean): Stmt {
        val decls = mutableListOf<Stmt>()
        do {
            // Pointer/reference markers are accepted and ignored.
            while (acceptOp("*") || acceptOp("&")) {}
            // Unnamed bitfields (`uint : 3;`) only skip bits.
            val name = if (!isLocal && isOp(":")) "" else expectIdent()
            if (allowFunction && decls.isEmpty() && isOp("(") && looksLikeFunctionDef()) {
                return functionDef(type, name, line)
            }
            val arraySize = if (acceptOp("[")) {
                if (isOp("]")) error("Arrays without a size are not supported")
                expression().also { expectOp("]") }
            } else null
            // Parsed below the relational level so a following `<attrs>` isn't read as `<`.
            val bitWidth = if (arraySize == null && !isLocal && acceptOp(":")) shift() else null
            if (name.isEmpty() && bitWidth == null) error("Expected identifier but found ':'")
            val args = if (acceptOp("(")) argList() else emptyList()
            val attrs = if (isOp("<")) attributes() else emptyList()
            val init = if (acceptOp("=")) assignment() else null
            decls += Stmt.VarDecl(type, name, arraySize, isLocal, isConst, init, attrs, args, line, bitWidth)
        } while (acceptOp(","))
        expectOp(";")
        return decls.singleOrNull() ?: Stmt.Multi(decls, line)
    }

    /** At `(`: a function definition iff the matching `)` is followed by `{`. */
    private fun looksLikeFunctionDef(): Boolean {
        var depth = 0
        var i = pos
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == Tok.EOF) return false
            if (isOp("(", t)) depth++
            if (isOp(")", t)) {
                depth--
                if (depth == 0) return isOp("{", tokens[i + 1])
            }
            i++
        }
        return false
    }

    private fun functionDef(returnType: TypeRef, name: String, line: Int): Stmt {
        val params = params()
        return Stmt.FuncDef(name, returnType, params, block(), line)
    }

    private fun params(): List<Param> {
        expectOp("(")
        val params = mutableListOf<Param>()
        if (isKw("void") && isOp(")", peekAt(1))) advance()
        while (!acceptOp(")")) {
            if (params.isNotEmpty()) expectOp(",")
            while (isKw("local") || isKw("const")) advance()
            val type = typeRef()
            var byRef = false
            while (isOp("&") || isOp("*")) { advance(); byRef = true }
            val name = expectIdent()
            if (acceptOp("[")) expectOp("]")
            params += Param(type, name, byRef)
        }
        return params
    }

    private fun attributes(): List<Attribute> {
        expectOp("<")
        val attrs = mutableListOf<Attribute>()
        while (!acceptOp(">")) {
            if (attrs.isNotEmpty()) expectOp(",")
            val name = expectIdent()
            // Values are parsed below the relational level so `>` closes the attribute list.
            val value = if (acceptOp("=")) shift() else Expr.IntLit(1, peek.line)
            attrs += Attribute(name, value)
        }
        return attrs
    }

    private fun structStatement(): Stmt {
        val line = peek.line
        val def = structDef(null)
        if (acceptOp(";")) return def
        val decl = declarators(TypeRef(def.name, line), isLocal = false, isConst = false, line, allowFunction = false)
        return Stmt.Multi(listOf(def, decl), line)
    }

    /** Parses `struct|union [Name] [(params)] { ... }` and registers the name as a type. */
    private fun structDef(fallbackName: String?): Stmt.StructDef {
        val line = peek.line
        val isUnion = advance().text == "union"
        val name = if (peek.type == Tok.IDENT) advance().text else fallbackName ?: "__anon${anonCounter++}"
        typeNames += name
        val params = if (isOp("(")) params() else emptyList()
        val body = block().body
        return Stmt.StructDef(name, isUnion, params, body, line)
    }

    private fun enumDef(fallbackName: String?): Stmt.EnumDef {
        val line = advance().line
        val base = if (acceptOp("<")) typeRef().also { expectOp(">") } else TypeRef("int", line)
        val name = if (peek.type == Tok.IDENT) advance().text else fallbackName ?: "__enum${anonCounter++}"
        typeNames += name
        expectOp("{")
        val entries = mutableListOf<EnumEntry>()
        while (!acceptOp("}")) {
            if (entries.isNotEmpty()) {
                expectOp(",")
                if (acceptOp("}")) break
            }
            val entryName = expectIdent()
            val value = if (acceptOp("=")) conditional() else null
            entries += EnumEntry(entryName, value)
        }
        return Stmt.EnumDef(name, base, entries, line)
    }

    private fun enumStatement(): Stmt {
        val line = peek.line
        val def = enumDef(null)
        if (acceptOp(";")) return def
        val decl = declarators(TypeRef(def.name, line), isLocal = false, isConst = false, line, allowFunction = false)
        return Stmt.Multi(listOf(def, decl), line)
    }

    private fun typedef(): Stmt {
        val line = advance().line
        if ((isKw("struct") || isKw("union")) && (isOp("{", peekAt(1)) || isOp("{", peekAt(2)) || isOp("(", peekAt(2)))) {
            val def = structDef(null)
            val alias = expectIdent()
            expectOp(";")
            typeNames += alias
            return Stmt.Multi(listOf(def, Stmt.Typedef(alias, TypeRef(def.name, line), null, line)), line)
        }
        if (isKw("enum")) {
            val def = enumDef(null)
            val alias = expectIdent()
            expectOp(";")
            typeNames += alias
            return Stmt.Multi(listOf(def, Stmt.Typedef(alias, TypeRef(def.name, line), null, line)), line)
        }
        val target = typeRef()
        val alias = expectIdent()
        val size = if (acceptOp("[")) expression().also { expectOp("]") } else null
        expectOp(";")
        typeNames += alias
        return Stmt.Typedef(alias, target, size, line)
    }

    // ---- expressions (C precedence) ----

    fun expression(): Expr {
        var e = assignment()
        while (isOp(",")) {
            // The comma operator only appears in `for` steps; evaluate left-to-right, keep the last.
            val line = advance().line
            e = Expr.Binary(",", e, assignment(), line)
        }
        return e
    }

    private fun assignment(): Expr {
        val left = conditional()
        val t = peek
        if (t.type == Tok.OP && t.text in ASSIGN_OPS) {
            advance()
            return Expr.Assign(t.text, left, assignment(), t.line)
        }
        return left
    }

    private fun conditional(): Expr {
        val cond = binary(0)
        if (!isOp("?")) return cond
        val line = advance().line
        val then = assignment()
        expectOp(":")
        return Expr.Ternary(cond, then, conditional(), line)
    }

    private fun binary(level: Int): Expr {
        if (level >= BINARY_LEVELS.size) return unary()
        var left = binary(level + 1)
        while (peek.type == Tok.OP && peek.text in BINARY_LEVELS[level]) {
            val t = advance()
            left = Expr.Binary(t.text, left, binary(level + 1), t.line)
        }
        return left
    }

    private fun shift(): Expr = binary(BINARY_LEVELS.indexOfFirst { "<<" in it })

    private fun unary(): Expr {
        val t = peek
        if (t.type == Tok.OP) {
            when (t.text) {
                "-", "+", "!", "~" -> { advance(); return Expr.Unary(t.text, unary(), t.line) }
                "++", "--" -> { advance(); return Expr.IncDec(t.text, unary(), prefix = true, t.line) }
                "(" -> if (isTypeStart(peekAt(1), peekAt(2)) && isCastClose()) {
                    advance()
                    val type = typeRef()
                    expectOp(")")
                    return Expr.Cast(type, unary(), t.line)
                }
            }
        }
        return postfix(primary())
    }

    /** `(Type)` is a cast only when the type is followed directly by `)`. */
    private fun isCastClose(): Boolean {
        var i = pos + 1
        if (isKw("unsigned", tokens[i]) || isKw("signed", tokens[i])) i++
        if (isKw("struct", tokens[i]) || isKw("enum", tokens[i])) i++
        return isOp(")", tokens[i + 1])
    }

    private fun postfix(start: Expr): Expr {
        var e = start
        while (true) {
            val t = peek
            e = when {
                isOp("[") -> { advance(); Expr.Index(e, expression(), t.line).also { expectOp("]") } }
                isOp(".") -> { advance(); Expr.Member(e, expectIdent(), t.line) }
                isOp("++") || isOp("--") -> { advance(); Expr.IncDec(t.text, e, prefix = false, t.line) }
                else -> return e
            }
        }
    }

    private fun primary(): Expr {
        val t = advance()
        return when (t.type) {
            Tok.INT, Tok.CHAR -> Expr.IntLit(t.intValue, t.line)
            Tok.FLOAT -> Expr.FloatLit(t.floatValue, t.line)
            Tok.STRING -> {
                // Adjacent string literals concatenate, as in C.
                val sb = StringBuilder(t.text)
                while (peek.type == Tok.STRING) sb.append(advance().text)
                Expr.StrLit(sb.toString(), t.line)
            }
            Tok.IDENT -> when {
                t.text == "true" -> Expr.IntLit(1, t.line)
                t.text == "false" -> Expr.IntLit(0, t.line)
                t.text == "sizeof" && isOp("(") && isTypeStart(peekAt(1), peekAt(2)) && isCastClose() -> {
                    advance()
                    val type = typeRef()
                    expectOp(")")
                    Expr.SizeOfType(type, t.line)
                }
                isOp("(") -> { advance(); Expr.Call(t.text, argList(), t.line) }
                else -> Expr.Ident(t.text, t.line)
            }
            Tok.OP -> if (t.text == "(") expression().also { expectOp(")") } else {
                throw TemplateException("Unexpected '${t.text}'", t.line)
            }
            Tok.EOF -> throw TemplateException("Unexpected end of template", t.line)
        }
    }

    /** Arguments after an already-consumed `(`. */
    private fun argList(): List<Expr> {
        val args = mutableListOf<Expr>()
        while (!acceptOp(")")) {
            if (args.isNotEmpty()) expectOp(",")
            args += assignment()
        }
        return args
    }

    companion object {
        private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")
        private val BINARY_LEVELS = listOf(
            setOf("||"), setOf("&&"), setOf("|"), setOf("^"), setOf("&"),
            setOf("==", "!="), setOf("<", ">", "<=", ">="), setOf("<<", ">>"),
            setOf("+", "-"), setOf("*", "/", "%"),
        )

        fun parse(source: String): List<Stmt> = Parser(Lexer(source).tokenize()).parseProgram()
    }
}

