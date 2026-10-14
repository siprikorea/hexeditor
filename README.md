# Hex Editor

A Kotlin Multiplatform (Compose Multiplatform) hex editor, inspired by 010 Editor, for Desktop (JVM) and Web (JS / Wasm).
It edits binary files and parses them into structures with C-like **binary templates**.

**Try it in the browser: https://siprikorea.github.io/hexeditor/** (files stay on your device; needs a browser with
WebAssembly GC, e.g. recent Chrome, Firefox or Safari 18.2+)

## Features

- **Simple layout**: the hex view and a side panel (Structure / Inspector) on rounded sheets; the template
  editor opens only when you want to write a template (Edit, or the ⋯ menu)
- **Open** with the Open button, Ctrl/Cmd+O, or by **dragging a file onto the window** (desktop and web)
- **Hex/ASCII editing**: overwrite and insert modes (`Insert` key), delete/backspace, unlimited undo/redo
- **Navigation**: arrow keys, PageUp/Down, Home/End (Ctrl+Home/End), click/drag/shift-click selection, Tab to switch panes
- **Go to offset** (`0x1F`, `1Fh`, `31`; Ctrl/Cmd+G), **find** text or hex (`50 4B 03 04`; Ctrl/Cmd+F) forward and
  backward (Enter / Shift+Enter, F3 / Shift+F3)
- **Copy/paste as hex** (Ctrl/Cmd+C, Ctrl/Cmd+V)
- **Data inspector**: int8–uint64, float, double, binary, char, UTF-16, DOS date/time and time_t, in LE or BE
- **Binary templates**: parse the file into a tree of structs. Clicking a node selects its bytes, and fields are
  color-highlighted in the hex view. Templates re-run automatically when the data or the template changes.
- Built-in templates: PNG, BMP, ZIP, ELF, WAV and a syntax example. The template is picked from the file extension;
  files with no matching template just show their bytes until you pick or write one.
- Open/save `.bt` template files

## Large files

- Files are never loaded whole: the desktop reads them on demand (positional reads + an LRU page cache), so a
  multi-GB file opens instantly. Offsets are 64-bit, so files larger than 4 GB work too.
- Edits use a **piece table**: insert, delete and undo only swap a few pieces, so they are fast at any file size.
- Saving streams the data to a temporary file and swaps it into place. Unchanged ranges are copied
  kernel-side, and undo still works after a save.
- Templates and search run in the background on a zero-copy snapshot. Starting a new run or search cancels
  the previous one, and Esc cancels too.
- The results tree shows children 10,000 at a time; the cursor-to-field lookup uses binary search.
- Measured on a 5 GB file (JVM): open 2 ms, full-file search 2 s, 10,000 scattered inserts 17 ms.
- Web: Wasm keeps the file on the JS side and copies it in chunks; JS uses it directly without copying. Browser
  memory limits (about 2–4 GB) apply, and a template run blocks the UI until it finishes because there is only one thread.

## Template language (a subset of 010 Editor)

```c
LittleEndian();

typedef struct {
    char   magic[4];
    ushort version;
    ushort count;
} HEADER;

struct RECORD {
    ubyte type;
    ubyte length;
    if (length > 0)
        uchar data[length] <format=hex>;
};

enum <ubyte> KIND { A = 1, B, C = 10 };

HEADER header;
local int i;
for (i = 0; i < header.count && !FEof(); i++)
    RECORD record;          // repeated declarations become record[0], record[1], ...
```

- Types: `char uchar byte short ushort int uint int64 uint64 float double string wstring`, the Windows
  aliases (`WORD DWORD QWORD ...`), `unsigned`, `struct`, `union`, `enum <type>`, `typedef`, and structs with parameters
- Bitfields: `uint flag : 3;`, unnamed padding `uint : 2;`, and `: 0` to close the current storage unit.
  Bits are packed into a unit the size of the declared type, filled from the low bit in little-endian and
  from the high bit in big-endian. `BitfieldLeftToRight()` / `BitfieldRightToLeft()` override the direction.
- Statements: `if/else`, `while`, `do-while`, `for`, `switch`, `break/continue/return`, `local` variables,
  user-defined functions
- Attributes: `<format=hex|decimal|binary|octal, comment="..." or comment=Func, name="...">`
- Built-in functions: `LittleEndian BigEndian FSeek FSkip FTell FEof FileSize ReadUByte/ReadUShort/ReadUInt/...`,
  `ReadString sizeof startof exists Printf Warning Str Strlen Strcmp SubStr Min Max Abs Assert Exit`
- Not supported yet: bitfields spanning storage units (`BitfieldDisablePadding`), unsized arrays (`x[]`), and
  writing to file variables

## Structure

- `shared/src/commonMain/.../core`: `ByteSource`/`CachedReader` (on-demand reads), `HexDocument` (piece table +
  undo/redo), data inspector, streaming search
- `shared/src/commonMain/.../template`: lexer, parser, interpreter, and built-in templates
- `shared/src/commonMain/.../ui`: Compose UI (Canvas-based hex view, inspector, template editor/results)
- `shared/src/{jvm,js,wasmJs}Main/.../platform`: file open/save (Desktop: `FileChannel`-based on-demand reads and atomic
  save, Web: upload/download)

## Running

- Desktop: `./gradlew :desktopApp:run` (open a file at start-up with `--args="/path/to/file"`)
- Web (Wasm): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
- Web (JS): `./gradlew :webApp:jsBrowserDevelopmentRun`
- Tests: `./gradlew :shared:jvmTest` (also `:shared:jsTest` and `:shared:wasmJsTest`)

On the web, saving downloads the file. Every push to `main` deploys the Wasm build to GitHub Pages
(`.github/workflows/pages.yml`).

The app icon is drawn in `desktopApp/icons/icon.svg`; `desktopApp/icons/render.sh` regenerates the PNG, `.icns`,
`.ico` and the web favicons from it (macOS, needs Chrome).
