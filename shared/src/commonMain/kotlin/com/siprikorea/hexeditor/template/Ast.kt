package com.siprikorea.hexeditor.template

sealed class Expr {
    abstract val line: Int

    data class IntLit(val value: Long, override val line: Int) : Expr()
    data class FloatLit(val value: Double, override val line: Int) : Expr()
    data class StrLit(val value: String, override val line: Int) : Expr()
    data class Ident(val name: String, override val line: Int) : Expr()
    data class Member(val target: Expr, val name: String, override val line: Int) : Expr()
    data class Index(val target: Expr, val index: Expr, override val line: Int) : Expr()
    data class Call(val name: String, val args: List<Expr>, override val line: Int) : Expr()
    data class Unary(val op: String, val operand: Expr, override val line: Int) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr, override val line: Int) : Expr()
    data class Ternary(val cond: Expr, val then: Expr, val otherwise: Expr, override val line: Int) : Expr()
    data class Assign(val op: String, val target: Expr, val value: Expr, override val line: Int) : Expr()
    /** `++x` / `x++` style updates; [prefix] decides which value is returned. */
    data class IncDec(val op: String, val target: Expr, val prefix: Boolean, override val line: Int) : Expr()
    /** `sizeof(type)` where the argument is a type name rather than a value. */
    data class SizeOfType(val type: TypeRef, override val line: Int) : Expr()
    data class Cast(val type: TypeRef, val operand: Expr, override val line: Int) : Expr()
}

/** A reference to a type as written in the source, e.g. `unsigned int` or `HEADER`. */
data class TypeRef(val name: String, val line: Int)

data class Attribute(val name: String, val value: Expr)

data class EnumEntry(val name: String, val value: Expr?)

sealed class Stmt {
    abstract val line: Int

    data class Block(val body: List<Stmt>, override val line: Int) : Stmt()
    /** Several statements from one source statement (e.g. `struct A {..} a, b;`); no new scope. */
    data class Multi(val body: List<Stmt>, override val line: Int) : Stmt()
    data class ExprStmt(val expr: Expr, override val line: Int) : Stmt()

    /** Declares a file-mapped variable (or a `local` variable when [isLocal]). */
    data class VarDecl(
        val type: TypeRef,
        val name: String,
        val arraySize: Expr?,
        val isLocal: Boolean,
        val isConst: Boolean,
        val init: Expr?,
        val attributes: List<Attribute>,
        val args: List<Expr>,
        override val line: Int,
        /** `type name : width;` declares a bitfield. */
        val bitWidth: Expr? = null,
    ) : Stmt()

    data class StructDef(
        val name: String,
        val isUnion: Boolean,
        val params: List<Param>,
        val body: List<Stmt>,
        override val line: Int,
    ) : Stmt()

    data class EnumDef(
        val name: String,
        val baseType: TypeRef,
        val entries: List<EnumEntry>,
        override val line: Int,
    ) : Stmt()

    data class Typedef(val name: String, val target: TypeRef, val arraySize: Expr?, override val line: Int) : Stmt()

    data class FuncDef(
        val name: String,
        val returnType: TypeRef,
        val params: List<Param>,
        val body: Block,
        override val line: Int,
    ) : Stmt()

    data class If(val cond: Expr, val then: Stmt, val otherwise: Stmt?, override val line: Int) : Stmt()
    data class While(val cond: Expr, val body: Stmt, override val line: Int) : Stmt()
    data class DoWhile(val body: Stmt, val cond: Expr, override val line: Int) : Stmt()
    data class For(val init: Stmt?, val cond: Expr?, val step: Expr?, val body: Stmt, override val line: Int) : Stmt()
    data class Switch(val value: Expr, val cases: List<Case>, override val line: Int) : Stmt()
    data class Break(override val line: Int) : Stmt()
    data class Continue(override val line: Int) : Stmt()
    data class Return(val value: Expr?, override val line: Int) : Stmt()
    data class Empty(override val line: Int) : Stmt()
}

data class Param(val type: TypeRef, val name: String, val byRef: Boolean)

/** A `case` label group; [values] is empty for `default`. */
data class Case(val values: List<Expr>, val isDefault: Boolean, val body: List<Stmt>)
