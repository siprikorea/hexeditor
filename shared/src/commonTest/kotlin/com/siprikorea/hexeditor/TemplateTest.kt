package com.siprikorea.hexeditor

import com.siprikorea.hexeditor.core.ArrayByteSource
import com.siprikorea.hexeditor.template.Interpreter
import com.siprikorea.hexeditor.template.NodeKind
import com.siprikorea.hexeditor.template.TemplateNode
import com.siprikorea.hexeditor.template.TemplateResult
import com.siprikorea.hexeditor.template.Templates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun run(src: String, data: ByteArray): TemplateResult {
        val r = Interpreter.run(src, data)
        assertNull(r.error, "template error: ${r.error?.message} (line ${r.error?.line})")
        return r
    }

    private fun TemplateNode.child(path: String): TemplateNode =
        path.split('.').fold(this) { n, name -> n.children.first { it.name == name } }

    @Test
    fun primitivesAndEndianness() {
        val r = run(
            """
            LittleEndian();
            ushort a;
            BigEndian();
            ushort b;
            int c;
            """,
            bytes(0x34, 0x12, 0x12, 0x34, 0xFF, 0xFF, 0xFF, 0xFE),
        )
        assertEquals("4660", r.root.child("a").display)
        assertEquals("4660", r.root.child("b").display)
        assertEquals("-2", r.root.child("c").display)
        assertEquals(4, r.root.child("c").offset)
    }

    @Test
    fun structsArraysAndExpressions() {
        val r = run(
            """
            typedef struct {
                char magic[4];
                uint count;
            } HEADER;

            struct ENTRY {
                ubyte len;
                char name[len];
            };

            HEADER hdr;
            if (hdr.magic != "TEST") Warning("bad magic");
            ENTRY entries[hdr.count];
            """,
            bytes('T'.code, 'E'.code, 'S'.code, 'T'.code, 2, 0, 0, 0, 2, 'h'.code, 'i'.code, 1, 'x'.code),
        )
        assertEquals("", r.log)
        val hdr = r.root.child("hdr")
        assertEquals("HEADER", hdr.typeName)
        assertEquals("\"TEST\"", hdr.child("magic").display)
        val entries = r.root.child("entries")
        assertEquals(2, entries.children.size)
        assertEquals("\"hi\"", entries.children[0].child("name").display)
        assertEquals(11, entries.children[1].offset)
        assertEquals(13, entries.end)
    }

    @Test
    fun loopsLocalsAndDuplicateNamesBecomeArrays() {
        val r = run(
            """
            local int i;
            local int total = 0;
            for (i = 0; i < 3; i++) {
                ubyte v;
                total += v;
            }
            while (!FEof()) { ubyte rest; }
            Printf("total=%d hex=%04X\n", total, 255);
            """,
            bytes(1, 2, 3, 9, 9),
        )
        assertEquals("total=6 hex=00FF\n", r.log)
        val v = r.root.child("v")
        assertEquals(NodeKind.ARRAY, v.kind)
        assertEquals(listOf("v[0]", "v[1]", "v[2]"), v.children.map { it.name })
        assertEquals(2, r.root.child("rest").children.size)
    }

    @Test
    fun enumsSwitchFunctionsAndAttributes() {
        val r = run(
            """
            enum <ubyte> KIND { A = 1, B, C = 10 };
            int twice(int x) { return x * 2; }
            KIND k;
            ushort val <format=hex, comment="value">;
            local int out;
            switch (k) {
                case B: out = twice(val); break;
                default: out = -1;
            }
            Printf("%d", out);
            """,
            bytes(2, 0x10, 0x00),
        )
        assertEquals("B (2)", r.root.child("k").display)
        assertEquals("0x0010", r.root.child("val").display)
        assertEquals("value", r.root.child("val").comment)
        assertEquals("32", r.log)
    }

    @Test
    fun unionsAndSeek() {
        val r = run(
            """
            union U { uint whole; ubyte parts[4]; } u;
            FSeek(0);
            ubyte first;
            Printf("%d %d", sizeof(u), startof(first));
            """,
            bytes(1, 2, 3, 4),
        )
        assertEquals(4, r.root.child("u").size)
        assertEquals("4 0", r.log)
    }

    @Test
    fun readingPastEofReportsLine() {
        val r = Interpreter.run("uint a;\nuint b;", bytes(1, 2, 3, 4, 5))
        val e = assertNotNull(r.error)
        assertEquals(2, e.line)
        // Successfully parsed fields are kept.
        assertEquals(1, r.root.children.size)
    }

    @Test
    fun syntaxErrorReportsLine() {
        val r = Interpreter.run("uint a;\nuint b\nuint c;", bytes(0))
        assertEquals(3, r.error?.line)
    }

    @Test
    fun infiniteLoopIsStopped() {
        val r = Interpreter.run("while (true) {}", bytes(0))
        assertTrue(r.error!!.message!!.contains("limit"))
    }

    @Test
    fun builtInTemplatesParseTheirFormats() {
        val png = bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I'.code, 'H'.code, 'D'.code, 'R'.code,
            0, 0, 0, 1, 0, 0, 0, 2, 8, 6, 0, 0, 0,
            0x1F, 0x15, 0xC4, 0x89,
            0, 0, 0, 0, 'I'.code, 'E'.code, 'N'.code, 'D'.code, 0xAE, 0x42, 0x60, 0x82,
        )
        val r = run(Templates.byName("PNG").source, png)
        val chunks = r.root.child("chunk")
        assertEquals(2, chunks.children.size)
        assertEquals("2", chunks.children[0].child("ihdr.height").display)

        for (t in Templates.all) {
            // Every template must at least parse and run without crashing on arbitrary data.
            Interpreter.run(t.source, ByteArray(64) { it.toByte() })
        }
    }

    @Test
    fun bitfieldsLittleEndianAllocateFromLowBits() {
        val r = run(
            """
            LittleEndian();
            ubyte a : 3;
            ubyte b : 4;
            ubyte c : 1;
            ushort lo : 4;
            ushort hi : 12 <format=hex>;
            ubyte after;
            """,
            bytes(0xB5, 0x34, 0x12, 0x99),
        )
        val a = r.root.child("a")
        assertEquals("5", a.display)
        assertEquals(0, a.offset)
        assertEquals(0, a.bitOffset)
        assertEquals("6", r.root.child("b").display)
        assertEquals(3, r.root.child("b").bitOffset)
        assertEquals("1", r.root.child("c").display)
        assertEquals("4", r.root.child("lo").display)
        assertEquals(1, r.root.child("lo").offset)
        assertEquals("0x123", r.root.child("hi").display)
        assertEquals(3, r.root.child("after").offset)
    }

    @Test
    fun bitfieldsBigEndianAllocateFromHighBits() {
        val r = run(
            """
            BigEndian();
            ubyte a : 3;
            ubyte b : 4;
            ubyte c : 1;
            """,
            bytes(0xB5),
        )
        assertEquals("5", r.root.child("a").display)
        assertEquals(5, r.root.child("a").bitOffset)
        assertEquals("10", r.root.child("b").display)
        assertEquals("1", r.root.child("c").display)
    }

    @Test
    fun bitfieldUnitsOverflowAndClose() {
        val r = run(
            """
            enum <ubyte> MODE { OFF, ON, AUTO };
            ubyte x : 6;
            ubyte y : 4;      // does not fit in the remaining 2 bits -> next byte
            ubyte : 0;        // closes the unit
            MODE m : 2;
            char s : 4;
            uchar plain;      // ordinary variable also closes the unit
            ubyte z : 1;
            local int sum = y + m;
            Printf("%d", sum);
            """,
            bytes(0xFF, 0x0A, 0xF2, 0x00, 0x01),
        )
        assertEquals(0, r.root.child("x").offset)
        assertEquals(1, r.root.child("y").offset)
        assertEquals("10", r.root.child("y").display)
        assertEquals(2, r.root.child("m").offset)
        assertEquals("AUTO (2)", r.root.child("m").display)
        assertEquals("-4", r.root.child("s").display)
        assertEquals(3, r.root.child("plain").offset)
        assertEquals(4, r.root.child("z").offset)
        assertEquals("12", r.log)
    }

    @Test
    fun bitfieldInStructAndLeftToRightOverride() {
        val r = run(
            """
            BitfieldLeftToRight();
            struct FLAGS { ubyte hi : 4; ubyte lo : 4; } f;
            ubyte next;
            """,
            bytes(0xA5, 0x01),
        )
        assertEquals("10", r.root.child("f.hi").display)
        assertEquals("5", r.root.child("f.lo").display)
        assertEquals(1, r.root.child("f").size)
        assertEquals(1, r.root.child("next").offset)
    }

    @Test
    fun invalidBitfields() {
        assertEquals(1, Interpreter.run("ubyte a : 9;", bytes(0)).error?.line)
        assertEquals(1, Interpreter.run("float f : 3;", bytes(0, 0, 0, 0)).error?.line)
    }

    @Test
    fun templatesRunOnMultiGigabyteSources() {
        val src = SyntheticSource(6L shl 30)
        val r = Interpreter.run(
            """
            FSeek(0x140000000);
            uint marker;
            ubyte block[1000000000];
            Printf("%d", FTell() > 0xFFFFFFFF);
            """,
            src,
        )
        assertNull(r.error)
        assertEquals(0x140000000L, r.root.child("marker").offset)
        assertEquals(1_000_000_000L, r.root.child("block").size)
        assertEquals("1", r.log)
        // The large array is never read in full: only a few pages are touched.
        assertTrue(src.reads < 10, "reads=${src.reads}")
    }

    @Test
    fun runCanBeCancelledThroughCheckpoint() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Interpreter.run("while (true) {}", ArrayByteSource(bytes(0))) {
                if (++calls == 3) throw IllegalStateException("cancelled")
            }
        }
    }
}
