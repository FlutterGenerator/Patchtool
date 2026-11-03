# PatchTool for Beginners

## What is it?

PatchTool is an Android app that lets you **look inside `.so` files**
(libraries with code used by apps and games) and **change bytes in them**.

With it you can:

- 🔍 find out what a library is (architecture, size, number of functions);
- 📋 get a list of all functions in the library with their addresses;
- ✏️ replace a piece of code with your own (a patch);
- ↩️ restore everything if something goes wrong.

It all runs right on your phone, no computer needed.

> ⚠️ Only modify your own files, or files you are allowed to modify.
> Changing other people's apps and games may violate their terms.

---

## Before you start

1. **Give the app access to files.**
   Settings → Apps → PatchTool → Permissions → Files and media →
   "Allow management of all files".
   Without this the app cannot save results.
2. **Know where results go.** Everything the app creates is saved to the
   `PatchTool_Files` folder in the root of your storage
   (`/storage/emulated/0/PatchTool_Files/`).
3. **The original file is never changed.** Patching is done on a copy.

---

## Lesson 1. What is inside a library

1. Open PatchTool and choose a `.so` file.
2. The app detects the architecture automatically: `arm64`, `arm32`, `x86` or `x86_64`.
   (Most modern phones use `arm64`.)
3. Request the **file info**. You will see the size, checksums (MD5 and SHA256),
   the number of functions and the number of code instructions.

The `# of Instructions (.text)` line shows how many processor instructions the library's code contains.
If it says `N/A (Requires Capstone)` instead of a number, it is not an error: this build simply
has no module for counting instructions. Nothing else is affected.

---

## Lesson 2. List of functions (Dump)

Dump creates a text file listing the library's functions:

```
// RVA: 0x1a2b0  Size: 0x54
void _ZN7MyClass6updateEf();
```

What is what:

- **RVA**: the address of the function inside the library;
- **Size**: how many bytes it takes;
- the line below is the function name (if it is mangled, a readable version is shown above it).

The file is saved in `PatchTool_Files` and named `dumped_<library name>.so.cpp`.
You can open it with any text editor.

If the file is almost empty, the library is "stripped": most names have been removed from it.

---

## Lesson 3. Your first patch

A patch replaces a few bytes at a given position. You need three things:

1. **Offset**: where in the file the replacement starts (in hexadecimal, e.g. `1A2B0`).
2. **What to write**: bytes (HEX) or an assembly instruction (ASM).
3. The library's **architecture**.

### Example: make a function always return 1

The right bytes depend on the architecture:

| Architecture | HEX | Assembly |
|---|---|---|
| arm64 | `20 00 80 D2 C0 03 5F D6` | `mov x0, #1; ret` |
| arm32 | `01 00 A0 E3 1E FF 2F E1` | `mov r0, #1; bx lr` |
| x86 / x86_64 | `B8 01 00 00 00 C3` | `mov eax, 1; ret` |

Steps:

1. Choose the `.so`.
2. Enter the **offset** of the function start.
3. Enter **HEX** (or **ASM** if you want assembly).
4. Run the patch.
5. The result appears in `PatchTool_Files` as `<name>_patched.so`.

If both HEX and ASM are filled in, ASM is used.

> Offset is a position in the **file**. The RVA from a dump does not always match it.
> If a patch "did nothing", check this first.

---

## Lesson 4. Many patches at once (.ini file)

When you have many patches, it is easier to put them in a text file with the `.ini`
extension and apply them all at once.

Create a file `patches.ini`:

```ini
# Anything after a hash is a comment

[Patch_1]
offset=0x1A2B0
patch_hex=20 00 80 D2 C0 03 5F D6

[Patch_2]
offset=0x2000
patch_asm=mov x0, #1; ret
arch=arm64
```

Rules:

- each patch starts with a header in square brackets, the name can be anything;
- `offset` is required;
- give either `patch_hex` or `patch_asm`;
- if you use `patch_asm`, you must also add `arch` (`arm64`, `arm32`, `x86` or `x86_64`).

How to apply it:

1. Choose the `.so`.
2. In the menu choose **Import Patches (.ini)** and select your file.
3. Read the log: green `[+]` means success, red `[-]` means that patch was skipped.
4. The result is `PatchTool_Files/<name>_patched.so`.

A failed patch does not break the others: the app skips it and applies the rest.

---

## Lesson 5. How to undo everything

Every time you import an `.ini`, the app creates a backup file `<name>.so.bak.ini`
with the original bytes.

To roll back:

1. Choose the patched `.so`.
2. In the menu choose **Restore from Backup** and select the `.bak.ini` file.
3. You get `PatchTool_Files/<name>_restored.so`.

Tip: do not delete the `.bak.ini` until you are sure the patch works as intended.

---

## Troubleshooting

| What you see | What to do |
|---|---|
| No `PatchTool_Files` folder | Grant "All files access" permission and try again |
| `ASM is not available` | This build has no assembler module. Write the patch in HEX |
| `Invalid hex string provided.` | The HEX has extra characters or an odd number of digits |
| `Skipping patch ...: Out of bounds` | The offset is larger than the file |
| `'patch_asm' needs an 'arch' key.` | The `.ini` is missing the `arch=...` line |
| Dump is almost empty | The library is stripped (no function names) |
| Patch applied but nothing changed | Check that the offset is a file offset, not an RVA from the dump |

---

## Glossary

- **ELF / .so**: the library file format on Android and Linux.
- **Architecture**: the processor type: arm64, arm32, x86, x86_64.
- **Offset**: the position of a byte in the file.
- **RVA**: the address of a function when the library is loaded into memory.
- **HEX**: bytes written in hexadecimal (`90 90 C3`).
- **ASM**: human-readable processor instructions (`mov x0, #1`).
- **Patch**: replacing bytes in a file.
- **Stripped**: a library from which function names have been removed.
