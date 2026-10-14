package com.siprikorea.hexeditor.template

class BuiltinTemplate(val name: String, val extensions: List<String>, val source: String)

/** Ready-made templates for common formats; also serve as syntax examples. */
object Templates {

    val all: List<BuiltinTemplate> = listOf(
        BuiltinTemplate(
            "Example", emptyList(),
            """
            // Binary template: a C-like subset of 010 Editor's template language.
            // Declaring a variable reads it from the file at the current position.
            //
            //   types     char uchar short ushort int uint int64 uint64 float double string
            //             (also byte/WORD/DWORD/QWORD/...), struct, union, enum <type>, typedef
            //   locals    local int i = 0;   (not mapped to the file)
            //   bitfields uint flag : 3;      (packed into a storage unit of the declared type)
            //   control   if / else, while, do-while, for, switch, break, continue, functions
            //   attrs     <format=hex|decimal|binary, comment="...", name="...">
            //   builtins  LittleEndian() BigEndian() FSeek(p) FSkip(n) FTell() FEof() FileSize()
            //             ReadUByte/ReadUShort/ReadUInt/...(pos) ReadString(pos)
            //             sizeof(x) startof(x) exists(x) Printf(fmt, ...) Warning(fmt, ...)
            //
            // Declaring the same name repeatedly (e.g. in a loop) turns it into an array.

            LittleEndian();

            typedef struct {
                char   magic[4];
                ushort version;
                ushort count;
            } HEADER;

            struct RECORD {
                ubyte  type;
                ubyte  length;
                if (length > 0)
                    uchar data[length] <format=hex>;
            };

            HEADER header;
            Printf("magic = %s, version = %d\n", header.magic, header.version);

            local int i;
            for (i = 0; i < header.count && !FEof(); i++) {
                RECORD record;
            }
            """.trimIndent(),
        ),
        BuiltinTemplate(
            "PNG", listOf("png"),
            """
            // PNG image: 8-byte signature followed by length/type/data/crc chunks.
            BigEndian();

            typedef struct {
                uint  width;
                uint  height;
                ubyte bitDepth;
                ubyte colorType;
                ubyte compression;
                ubyte filter;
                ubyte interlace;
            } IHDR;

            typedef struct {
                uint length;
                char type[4];
                if (type == "IHDR")
                    IHDR ihdr;
                else if (type == "tEXt")
                    char text[length];
                else if (length > 0)
                    ubyte data[length];
                uint crc <format=hex>;
            } CHUNK;

            ubyte signature[8] <format=hex>;
            if (signature[1] != 'P' || signature[2] != 'N' || signature[3] != 'G')
                Warning("Not a PNG file\n");

            while (!FEof()) {
                CHUNK chunk;
            }
            """.trimIndent(),
        ),
        BuiltinTemplate(
            "BMP", listOf("bmp", "dib"),
            """
            // Windows bitmap.
            LittleEndian();

            typedef struct {
                char   bfType[2];
                uint   bfSize;
                ushort bfReserved1;
                ushort bfReserved2;
                uint   bfOffBits;
            } BITMAPFILEHEADER;

            typedef struct {
                uint   biSize;
                int    biWidth;
                int    biHeight;
                ushort biPlanes;
                ushort biBitCount;
                uint   biCompression;
                uint   biSizeImage;
                int    biXPelsPerMeter;
                int    biYPelsPerMeter;
                uint   biClrUsed;
                uint   biClrImportant;
            } BITMAPINFOHEADER;

            typedef struct {
                ubyte blue;
                ubyte green;
                ubyte red;
                ubyte reserved;
            } RGBQUAD;

            BITMAPFILEHEADER bmfh;
            BITMAPINFOHEADER bmih;

            if (bmih.biBitCount <= 8) {
                local int colors = bmih.biClrUsed ? bmih.biClrUsed : (1 << bmih.biBitCount);
                RGBQUAD palette[colors];
            }

            if (FSeek(bmfh.bfOffBits) == 0 && !FEof())
                ubyte pixels[FileSize() - FTell()];
            """.trimIndent(),
        ),
        BuiltinTemplate(
            "ZIP", listOf("zip", "jar", "apk", "docx", "xlsx", "pptx"),
            """
            // ZIP archive: local file headers, central directory and end record.
            LittleEndian();

            enum <ushort> COMPRESSION { STORED = 0, SHRUNK, REDUCED1, REDUCED2, REDUCED3, REDUCED4,
                                        IMPLODED, DEFLATED = 8, DEFLATE64, BZIP2 = 12, LZMA = 14 };

            typedef struct {
                uint        signature <format=hex>;
                ushort      version;
                ushort      flags <format=hex>;
                COMPRESSION compression;
                ushort      modTime;
                ushort      modDate;
                uint        crc32 <format=hex>;
                uint        compressedSize;
                uint        uncompressedSize;
                ushort      nameLength;
                ushort      extraLength;
                char        fileName[nameLength];
                if (extraLength > 0)
                    ubyte extra[extraLength];
                if (compressedSize > 0)
                    ubyte data[compressedSize];
            } LOCAL_FILE;

            typedef struct {
                uint        signature <format=hex>;
                ushort      versionMadeBy;
                ushort      versionNeeded;
                ushort      flags <format=hex>;
                COMPRESSION compression;
                ushort      modTime;
                ushort      modDate;
                uint        crc32 <format=hex>;
                uint        compressedSize;
                uint        uncompressedSize;
                ushort      nameLength;
                ushort      extraLength;
                ushort      commentLength;
                ushort      diskStart;
                ushort      internalAttributes;
                uint        externalAttributes <format=hex>;
                uint        localHeaderOffset <format=hex>;
                char        fileName[nameLength];
                if (extraLength > 0)
                    ubyte extra[extraLength];
                if (commentLength > 0)
                    char comment[commentLength];
            } CENTRAL_DIR;

            typedef struct {
                uint   signature <format=hex>;
                ushort diskNumber;
                ushort centralDirDisk;
                ushort entriesOnDisk;
                ushort totalEntries;
                uint   centralDirSize;
                uint   centralDirOffset <format=hex>;
                ushort commentLength;
                if (commentLength > 0)
                    char comment[commentLength];
            } END_OF_CENTRAL_DIR;

            local uint tag;
            while (FileSize() - FTell() >= 4) {
                tag = ReadUInt();
                if (tag == 0x04034b50)
                    LOCAL_FILE file;
                else if (tag == 0x02014b50)
                    CENTRAL_DIR dirEntry;
                else if (tag == 0x06054b50)
                    END_OF_CENTRAL_DIR endRecord;
                else
                    break;
            }
            """.trimIndent(),
        ),
        BuiltinTemplate(
            "ELF", listOf("elf", "so", "o"),
            """
            // ELF executable header and program headers (32/64-bit, either endianness).
            typedef struct {
                uchar magic[4] <format=hex>;
                uchar eiClass;
                uchar eiData;
                uchar eiVersion;
                uchar osAbi;
                uchar abiVersion;
                uchar pad[7];
            } E_IDENT;

            enum <ushort> E_TYPE { ET_NONE, ET_REL, ET_EXEC, ET_DYN, ET_CORE };

            E_IDENT ident;
            if (ident.magic[0] != 0x7F || ident.magic[1] != 'E')
                Warning("Not an ELF file\n");
            if (ident.eiData == 2) BigEndian(); else LittleEndian();

            E_TYPE type;
            ushort machine <format=hex>;
            uint   version;
            if (ident.eiClass == 2) {
                uint64 entry <format=hex>;
                uint64 phoff <format=hex>;
                uint64 shoff <format=hex>;
            } else {
                uint entry <format=hex>;
                uint phoff <format=hex>;
                uint shoff <format=hex>;
            }
            uint   flags <format=hex>;
            ushort ehsize;
            ushort phentsize;
            ushort phnum;
            ushort shentsize;
            ushort shnum;
            ushort shstrndx;

            typedef struct {
                uint p_type <format=hex>;
                if (ident.eiClass == 2) {
                    uint   p_flags <format=hex>;
                    uint64 p_offset <format=hex>;
                    uint64 p_vaddr <format=hex>;
                    uint64 p_paddr <format=hex>;
                    uint64 p_filesz;
                    uint64 p_memsz;
                    uint64 p_align;
                } else {
                    uint p_offset <format=hex>;
                    uint p_vaddr <format=hex>;
                    uint p_paddr <format=hex>;
                    uint p_filesz;
                    uint p_memsz;
                    uint p_flags <format=hex>;
                    uint p_align;
                }
            } PROGRAM_HEADER;

            if (phnum > 0 && phoff < FileSize()) {
                FSeek(phoff);
                PROGRAM_HEADER programHeaders[phnum];
            }
            """.trimIndent(),
        ),
        BuiltinTemplate(
            "WAV", listOf("wav"),
            """
            // RIFF WAVE audio.
            LittleEndian();

            typedef struct {
                char id[4];
                uint size;
                if (id == "fmt ") {
                    ushort audioFormat;
                    ushort channels;
                    uint   sampleRate;
                    uint   byteRate;
                    ushort blockAlign;
                    ushort bitsPerSample;
                    if (size > 16)
                        ubyte extra[size - 16];
                } else if (size > 0) {
                    ubyte data[Min(size, FileSize() - FTell())];
                }
                if (size % 2 == 1 && !FEof())
                    ubyte pad;
            } CHUNK;

            char riff[4];
            uint riffSize;
            char wave[4];
            while (FileSize() - FTell() >= 8) {
                CHUNK chunk;
            }
            """.trimIndent(),
        ),
    )

    fun byName(name: String): BuiltinTemplate = all.first { it.name == name }

    /** Picks a template by file extension, or null if none applies. */
    fun forFileName(fileName: String): BuiltinTemplate? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return all.firstOrNull { ext in it.extensions }
    }
}
