# libmodmenu.so — JNI source

`modmenu.cpp` is a clean re-implementation of the JNI backend that used to ship only as a prebuilt
`libmodmenu.so` (the original sources were not available). It exports exactly the same four functions
for `com.patchtool.MainActivity`:

| Java                                                                                         | What it does |
|----------------------------------------------------------------------------------------------|--------------|
| `String getElfArchitecture(String path)`                                                     | `arm64` / `arm32` / `x86_64` / `x86` / `unsupported` |
| `String getElfInfo(String path)`                                                             | ELF summary (size, MD5, SHA256, header, counts) |
| `String processFile(action, inputFile, outputFile, iniFile, offsetHex, hexPayload, asmPayload, arch)` | `PATCH_SINGLE`, `PATCH_FROM_INI`, `RESTORE_FROM_INI` |
| `String dumpLibraryFile(String inputFile, String outputFile)`                                | function list of an ELF as a `.cpp` text file |

Build: `externalNativeBuild { ndkBuild { path 'src/main/jni/Android.mk' } }` (already set in `app/build.gradle`).

## INI format

```
[Patch_1]
offset=0x1000
patch_hex=90 90 CC

[Patch_2]
offset=0x2000
patch_asm=mov x0, #1; ret     ; needs include.Keystone
arch=arm64                    ; required with patch_asm: arm64 | arm32 | x86 | x86_64
```

`PATCH_FROM_INI` writes `<input>.bak.ini` (`offset=` + `backup_hex=`) which `RESTORE_FROM_INI` takes back.

## ASM (include.Keystone) is optional

HEX patching, INI, restore, info and dump need nothing else. ASM payloads (the ASM field in the app and
`patch_asm` in INI) need include.Keystone. If it is missing the library still builds and ASM returns the message
"ASM is not available ... Use a HEX payload.".

To enable it, put these next to `Android.mk` and rebuild (`Android.mk` detects them automatically):

```
jni/keystone/include/keystone/*.h               (from keystone-engine/keystone, include/keystone)
jni/keystone/libs/arm64-v8a/libkeystone.a
jni/keystone/libs/armeabi-v7a/libkeystone.a
jni/keystone/libs/x86/libkeystone.a
jni/keystone/libs/x86_64/libkeystone.a
```

Static libraries per ABI can be built with the NDK's CMake toolchain, e.g.:

```
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
      -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_LIBS_ONLY=ON \
      -DLLVM_TARGETS_TO_BUILD="AArch64;ARM;X86" <keystone-src>
```

The old prebuilt libraries (which include include.Keystone) are kept in `<project>/prebuilt_backup/lib`.
