package com.siprikorea.hexeditor.template

class TemplateException(message: String, val line: Int) : Exception(message)

enum class Tok { IDENT, INT, FLOAT, STRING, CHAR, OP, EOF }

data class Token(val type: Tok, val text: String, val line: Int, val intValue: Long = 0, val floatValue: Double = 0.0)

/** Tokenizer for the C-like binary template language (a subset of 010 Editor's syntax). */
class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            skipWhitespaceAndComments()
            if (pos >= src.length) {
                out += Token(Tok.EOF, "<eof>", line)
                return out
            }
            out += next()
        }
    }

    private fun skipWhitespaceAndComments() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\n' -> { line++; pos++ }
                c.isWhitespace() -> pos++
                src.startsWith("//", pos) -> while (pos < src.length && src[pos] != '\n') pos++
                src.startsWith("/*", pos) -> {
                    val end = src.indexOf("*/", pos + 2)
                    val stop = if (end < 0) src.length else end + 2
                    for (i in pos until stop) if (src[i] == '\n') line++
                    pos = stop
                }
                // Preprocessor lines (#include, #define, ...) are ignored.
                c == '#' -> while (pos < src.length && src[pos] != '\n') pos++
                else -> return
            }
        }
    }

    private fun next(): Token {
        val c = src[pos]
        return when {
            c.isLetter() || c == '_' -> {
                val start = pos
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                Token(Tok.IDENT, src.substring(start, pos), line)
            }
            c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) -> number()
            c == '"' -> Token(Tok.STRING, quoted('"'), line)
            c == '\'' -> {
                val s = quoted('\'')
                if (s.length != 1) throw TemplateException("Invalid char literal '$s'", line)
                Token(Tok.CHAR, s, line, intValue = s[0].code.toLong())
            }
            else -> {
                val op = OPERATORS.firstOrNull { src.startsWith(it, pos) }
                    ?: throw TemplateException("Unexpected character '$c'", line)
                pos += op.length
                Token(Tok.OP, op, line)
            }
        }
    }

    private fun number(): Token {
        val start = pos
        if (src.startsWith("0x", pos, ignoreCase = true)) {
            pos += 2
            while (pos < src.length && (src[pos].digitToIntOrNull(16) != null)) pos++
            val text = src.substring(start, pos)
            skipIntSuffix()
            return Token(Tok.INT, text, line, intValue = text.substring(2).toULong(16).toLong())
        }
        if (src.startsWith("0b", pos, ignoreCase = true)) {
            pos += 2
            while (pos < src.length && (src[pos] == '0' || src[pos] == '1')) pos++
            val text = src.substring(start, pos)
            skipIntSuffix()
            return Token(Tok.INT, text, line, intValue = text.substring(2).toULong(2).toLong())
        }
        var isFloat = false
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' ||
                    ((src[pos] == 'e' || src[pos] == 'E') && isFloat))
        ) {
            if (src[pos] == '.') isFloat = true
            pos++
        }
        val text = src.substring(start, pos)
        if (pos < src.length && (src[pos] == 'f' || src[pos] == 'F')) {
            pos++
            isFloat = true
        }
        return if (isFloat) {
            Token(Tok.FLOAT, text, line, floatValue = text.toDouble())
        } else {
            skipIntSuffix()
            Token(Tok.INT, text, line, intValue = text.toULong().toLong())
        }
    }

    private fun skipIntSuffix() {
        while (pos < src.length && src[pos] in "uUlL") pos++
    }

    private fun quoted(q: Char): String {
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length || src[pos] == '\n') throw TemplateException("Unterminated literal", line)
            val c = src[pos++]
            if (c == q) break
            if (c == '\\') {
                val e = src[pos++]
                when (e) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '0' -> sb.append('\u0000')
                    'x' -> {
                        val hex = src.substring(pos, minOf(pos + 2, src.length))
                        sb.append(hex.toInt(16).toChar())
                        pos += hex.length
                    }
                    else -> sb.append(e)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        // Longest operators first so that greedy matching works.
        private val OPERATORS = listOf(
            "<<=", ">>=",
            "==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "++", "--",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "+", "-", "*", "/", "%", "=", "<", ">", "!", "~", "&", "|", "^",
            "(", ")", "[", "]", "{", "}", ";", ",", ".", "?", ":",
        )
    }
}
