// libmodmenu.so - JNI backend of PatchTool (com.patchtool.MainActivity)
//
// Exported JNI functions:
//   String getElfArchitecture(String path)
//   String getElfInfo(String path)
//   String processFile(String action, String inputFile, String outputFile, String iniFile,
//                      String offsetHex, String hexPayload, String asmPayload, String arch)
//   String dumpLibraryFile(String inputFile, String outputFile)
//
// ASM payloads need Keystone. It is optional: put the headers into jni/keystone/include/keystone
// and libkeystone.a into jni/keystone/libs/<abi>/ and Android.mk enables it (-DUSE_KEYSTONE).
// Without Keystone everything works except "patch_asm" / the ASM field (HEX still works).

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <cxxabi.h>
#include <elf.h>
#include <map>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifdef USE_KEYSTONE
#include <Keystone/keystone.h>
#endif

namespace {

typedef std::vector<uint8_t> Bytes;

struct Err : std::runtime_error {
    explicit Err(const std::string &m) : std::runtime_error(m) {}
};

// ----------------------------------------------------------------------------
// small helpers
// ----------------------------------------------------------------------------

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return std::string();
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

jstring mk(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

std::string trim(const std::string &s) {
    size_t b = 0, e = s.size();
    while (b < e && isspace((unsigned char) s[b])) b++;
    while (e > b && isspace((unsigned char) s[e - 1])) e--;
    return s.substr(b, e - b);
}

std::string lower(std::string s) {
    for (size_t i = 0; i < s.size(); i++) s[i] = (char) tolower((unsigned char) s[i]);
    return s;
}

std::string baseName(const std::string &p) {
    size_t i = p.find_last_of('/');
    return i == std::string::npos ? p : p.substr(i + 1);
}

std::string hexNum(uint64_t v) {
    char b[32];
    snprintf(b, sizeof(b), "%llx", (unsigned long long) v);
    return b;
}

Bytes readFile(const std::string &path) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) throw Err("Cannot open file: " + path);
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); throw Err("Error reading file: " + path); }
    long n = ftell(f);
    if (n < 0 || fseek(f, 0, SEEK_SET) != 0) { fclose(f); throw Err("Error reading file: " + path); }
    Bytes d((size_t) n);
    if (n > 0 && fread(d.data(), 1, (size_t) n, f) != (size_t) n) {
        fclose(f);
        throw Err("Error reading file: " + path);
    }
    fclose(f);
    return d;
}

void writeFile(const std::string &path, const uint8_t *d, size_t n) {
    FILE *f = fopen(path.c_str(), "wb");
    if (!f) throw Err("Cannot create output file: " + path);
    bool ok = n == 0 || fwrite(d, 1, n, f) == n;
    ok = (fclose(f) == 0) && ok;
    if (!ok) throw Err("Error writing file: " + path);
}

void writeText(const std::string &path, const std::string &t) {
    writeFile(path, (const uint8_t *) t.data(), t.size());
}

// Accepts "90 90 CC", "9090cc", "0x90,0x90" ...
Bytes parseHex(const std::string &s) {
    std::string h;
    for (size_t i = 0; i < s.size(); i++) {
        char c = s[i];
        if (isspace((unsigned char) c) || c == ',') continue;
        if (c == '0' && i + 1 < s.size() && (s[i + 1] == 'x' || s[i + 1] == 'X')) { i++; continue; }
        if (!isxdigit((unsigned char) c)) throw Err("Invalid hex string provided.");
        h += c;
    }
    if (h.empty() || (h.size() & 1)) throw Err("Invalid hex string provided.");
    Bytes out;
    for (size_t i = 0; i < h.size(); i += 2) {
        char t[3] = {h[i], h[i + 1], 0};
        out.push_back((uint8_t) strtoul(t, nullptr, 16));
    }
    return out;
}

uint64_t parseOffset(const std::string &s0) {
    std::string s = trim(s0);
    if (s.empty()) throw Err("stoull: no conversion");
    char *end = nullptr;
    unsigned long long v = strtoull(s.c_str(), &end, 16);   // base 16 also accepts a 0x prefix
    if (end == s.c_str()) throw Err("stoull: no conversion");
    return (uint64_t) v;
}

std::string toHexSpaced(const uint8_t *d, size_t n) {
    std::string r;
    char b[4];
    for (size_t i = 0; i < n; i++) {
        snprintf(b, sizeof(b), "%02X", d[i]);
        if (i) r += ' ';
        r += b;
    }
    return r;
}

// ----------------------------------------------------------------------------
// MD5 / SHA-256 (for the ELF summary)
// ----------------------------------------------------------------------------

inline uint32_t rol(uint32_t x, int c) { return (x << c) | (x >> (32 - c)); }
inline uint32_t ror(uint32_t x, int c) { return (x >> c) | (x << (32 - c)); }

std::string md5hex(const uint8_t *data, size_t len) {
    static const int S[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
                              5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
                              4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
                              6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};
    uint32_t K[64];
    for (int i = 0; i < 64; i++) K[i] = (uint32_t) (4294967296.0 * fabs(sin(i + 1.0)));
    uint32_t st[4] = {0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u};

    Bytes tail(data + (len / 64) * 64, data + len);
    tail.push_back(0x80);
    while (tail.size() % 64 != 56) tail.push_back(0);
    uint64_t bits = (uint64_t) len * 8;
    for (int i = 0; i < 8; i++) tail.push_back((uint8_t) (bits >> (8 * i)));

    size_t full = len / 64;
    size_t blocks = full + tail.size() / 64;
    for (size_t bi = 0; bi < blocks; bi++) {
        const uint8_t *p = bi < full ? data + bi * 64 : tail.data() + (bi - full) * 64;
        uint32_t M[16];
        for (int i = 0; i < 16; i++)
            M[i] = (uint32_t) p[i * 4] | ((uint32_t) p[i * 4 + 1] << 8) |
                   ((uint32_t) p[i * 4 + 2] << 16) | ((uint32_t) p[i * 4 + 3] << 24);
        uint32_t A = st[0], B = st[1], C = st[2], D = st[3];
        for (int i = 0; i < 64; i++) {
            uint32_t F;
            int g;
            if (i < 16) { F = (B & C) | (~B & D); g = i; }
            else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
            else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
            else { F = C ^ (B | ~D); g = (7 * i) % 16; }
            F = F + A + K[i] + M[g];
            A = D; D = C; C = B;
            B = B + rol(F, S[i]);
        }
        st[0] += A; st[1] += B; st[2] += C; st[3] += D;
    }
    char out[33];
    for (int i = 0; i < 16; i++) snprintf(out + i * 2, 3, "%02x", (st[i / 4] >> (8 * (i % 4))) & 0xff);
    return out;
}

std::string sha256hex(const uint8_t *data, size_t len) {
    static const uint32_t K[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                     0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

    Bytes tail(data + (len / 64) * 64, data + len);
    tail.push_back(0x80);
    while (tail.size() % 64 != 56) tail.push_back(0);
    uint64_t bits = (uint64_t) len * 8;
    for (int i = 7; i >= 0; i--) tail.push_back((uint8_t) (bits >> (8 * i)));

    size_t full = len / 64;
    size_t blocks = full + tail.size() / 64;
    for (size_t bi = 0; bi < blocks; bi++) {
        const uint8_t *p = bi < full ? data + bi * 64 : tail.data() + (bi - full) * 64;
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = ((uint32_t) p[i * 4] << 24) | ((uint32_t) p[i * 4 + 1] << 16) |
                   ((uint32_t) p[i * 4 + 2] << 8) | (uint32_t) p[i * 4 + 3];
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
            uint32_t ch = (e & f) ^ (~e & g);
            uint32_t t1 = hh + S1 + ch + K[i] + w[i];
            uint32_t S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
            uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + mj;
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }
    char out[65];
    for (int i = 0; i < 8; i++) snprintf(out + i * 8, 9, "%08x", h[i]);
    return out;
}

// ----------------------------------------------------------------------------
// ELF parsing (ELF32 / ELF64, little endian)
// ----------------------------------------------------------------------------

struct Sym {
    std::string name;
    uint64_t value;
    uint64_t size;
    int type;
    int shndx;
};

struct ElfFile {
    bool is64 = false;
    uint16_t type = 0;
    uint16_t machine = 0;
    uint64_t entry = 0;
    uint32_t phnum = 0;
    uint32_t shnum = 0;
    size_t symTotal = 0;
    std::vector<Sym> syms;
};

template<class Eh, class Sh, class Sy>
void parseElfT(const Bytes &d, ElfFile &I) {
    if (d.size() < sizeof(Eh)) throw Err("Failed to load or parse the ELF file.");
    Eh eh;
    memcpy(&eh, d.data(), sizeof(eh));
    I.type = eh.e_type;
    I.machine = eh.e_machine;
    I.entry = eh.e_entry;
    I.phnum = eh.e_phnum;
    I.shnum = eh.e_shnum;

    if (!eh.e_shoff || eh.e_shentsize < sizeof(Sh) || !eh.e_shnum) return;
    uint64_t shoff = eh.e_shoff;
    uint64_t shtotal = (uint64_t) eh.e_shnum * eh.e_shentsize;
    if (shoff > d.size() || shtotal > d.size() - shoff) return;

    std::vector<Sh> secs(eh.e_shnum);
    for (size_t i = 0; i < secs.size(); i++)
        memcpy(&secs[i], d.data() + shoff + i * eh.e_shentsize, sizeof(Sh));

    // prefer .symtab, fall back to .dynsym (stripped libraries)
    // (symTotal counts both tables; the symbol list is taken from the first one found)
    int wanted[2] = {SHT_SYMTAB, SHT_DYNSYM};
    bool haveSyms = false;
    for (int w = 0; w < 2; w++) {
        bool found = false;
        for (size_t i = 0; i < secs.size(); i++) {
            const Sh &s = secs[i];
            if ((int) s.sh_type != wanted[w]) continue;
            if (s.sh_link >= secs.size()) continue;
            const Sh &str = secs[s.sh_link];
            uint64_t soff = s.sh_offset, ssz = s.sh_size;
            uint64_t stoff = str.sh_offset, stsz = str.sh_size;
            if (soff > d.size() || ssz > d.size() - soff) continue;
            if (stoff > d.size() || stsz > d.size() - stoff) continue;
            found = true;
            size_t cnt = (size_t) (ssz / sizeof(Sy));
            I.symTotal += cnt;
            if (haveSyms) continue;
            for (size_t k = 0; k < cnt; k++) {
                Sy sy;
                memcpy(&sy, d.data() + soff + k * sizeof(Sy), sizeof(sy));
                Sym o;
                o.value = sy.st_value;
                o.size = sy.st_size;
                o.type = ELF32_ST_TYPE(sy.st_info);   // same bit layout for ELF64
                o.shndx = sy.st_shndx;
                if (sy.st_name < stsz) {
                    const char *p = (const char *) d.data() + stoff + sy.st_name;
                    size_t maxn = (size_t) (stsz - sy.st_name);
                    o.name.assign(p, strnlen(p, maxn));
                }
                I.syms.push_back(o);
            }
        }
        if (found) haveSyms = true;
    }
}

ElfFile parseElf(const Bytes &d) {
    if (d.size() < EI_NIDENT || memcmp(d.data(), ELFMAG, SELFMAG) != 0)
        throw Err("Failed to load or parse the ELF file.");
    if (d[EI_DATA] != ELFDATA2LSB) throw Err("Big-endian ELF files are not supported.");
    ElfFile I;
    if (d[EI_CLASS] == ELFCLASS64) {
        I.is64 = true;
        parseElfT<Elf64_Ehdr, Elf64_Shdr, Elf64_Sym>(d, I);
    } else if (d[EI_CLASS] == ELFCLASS32) {
        I.is64 = false;
        parseElfT<Elf32_Ehdr, Elf32_Shdr, Elf32_Sym>(d, I);
    } else {
        throw Err("Failed to load or parse the ELF file.");
    }
    return I;
}

std::string archName(uint16_t machine) {
    switch (machine) {
        case EM_AARCH64: return "arm64";
        case EM_ARM: return "arm32";
        case EM_X86_64: return "x86_64";
        case EM_386: return "x86";
        default: return "unsupported";
    }
}

std::string machineName(uint16_t m) {
    switch (m) {
        case EM_AARCH64: return "AArch64";
        case EM_ARM: return "ARM";
        case EM_X86_64: return "Advanced Micro Devices X86-64";
        case EM_386: return "Intel 80386";
        default: return "Unknown (0x" + hexNum(m) + ")";
    }
}

std::string typeName(uint16_t t) {
    switch (t) {
        case ET_REL: return "REL (Relocatable file)";
        case ET_EXEC: return "EXEC (Executable file)";
        case ET_DYN: return "DYN (Shared object file)";
        case ET_CORE: return "CORE (Core file)";
        default: return "Unknown (0x" + hexNum(t) + ")";
    }
}

std::vector<Sym> functionsOf(const ElfFile &I) {
    std::vector<Sym> f;
    for (size_t i = 0; i < I.syms.size(); i++) {
        const Sym &s = I.syms[i];
        if (s.type == STT_FUNC && s.size > 0 && s.shndx != SHN_UNDEF && !s.name.empty()) f.push_back(s);
    }
    std::sort(f.begin(), f.end(), [](const Sym &a, const Sym &b) { return a.value < b.value; });
    return f;
}

std::string demangle(const std::string &n) {
    int st = 0;
    char *r = abi::__cxa_demangle(n.c_str(), nullptr, nullptr, &st);
    std::string out = (st == 0 && r) ? r : n;
    free(r);
    return out;
}

// ----------------------------------------------------------------------------
// assembler (Keystone, optional)
// ----------------------------------------------------------------------------

bool archKnown(const std::string &a) {
    return a == "arm64" || a == "arm32" || a == "x86" || a == "x86_64";
}

Bytes assemble(const std::string &text, const std::string &arch) {
    if (!archKnown(arch)) throw Err("Unsupported architecture: " + arch);
#ifdef USE_KEYSTONE
    ks_arch a;
    int mode;
    if (arch == "arm64") { a = KS_ARCH_ARM64; mode = KS_MODE_LITTLE_ENDIAN; }
    else if (arch == "arm32") { a = KS_ARCH_ARM; mode = KS_MODE_ARM; }
    else if (arch == "x86") { a = KS_ARCH_X86; mode = KS_MODE_32; }
    else { a = KS_ARCH_X86; mode = KS_MODE_64; }

    ks_engine *ks = nullptr;
    ks_err e = ks_open(a, mode, &ks);
    if (e != KS_ERR_OK) throw Err(std::string("ks_open failed: ") + ks_strerror(e));

    unsigned char *enc = nullptr;
    size_t size = 0, count = 0;
    if (ks_asm(ks, text.c_str(), 0, &enc, &size, &count) != 0) {
        ks_err ee = ks_errno(ks);
        ks_close(ks);
        throw Err(std::string("ks_asm failed: ") + ks_strerror(ee));
    }
    Bytes out(enc, enc + size);
    ks_free(enc);
    ks_close(ks);
    if (out.empty()) throw Err("ks_asm failed: nothing was assembled");
    return out;
#else
    (void) text;
    throw Err("ASM is not available: libmodmenu was built without Keystone. Use a HEX payload.");
#endif
}

// ----------------------------------------------------------------------------
// INI
// ----------------------------------------------------------------------------

struct IniSection {
    std::string name;
    std::map<std::string, std::string> kv;
};

std::vector<IniSection> parseIni(const std::string &path) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) throw Err("Could not open INI file: " + path);
    fclose(f);
    Bytes raw = readFile(path);
    std::string text(raw.begin(), raw.end());

    std::vector<IniSection> out;
    size_t pos = 0;
    while (pos <= text.size()) {
        size_t nl = text.find('\n', pos);
        std::string line = text.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
        pos = nl == std::string::npos ? text.size() + 1 : nl + 1;

        line = trim(line);   // also removes '\r'
        if (line.empty() || line[0] == '#' || line[0] == ';') continue;
        if (line[0] == '[' && line[line.size() - 1] == ']') {
            IniSection s;
            s.name = trim(line.substr(1, line.size() - 2));
            out.push_back(s);
            continue;
        }
        size_t eq = line.find('=');
        if (eq == std::string::npos || out.empty()) continue;
        out.back().kv[lower(trim(line.substr(0, eq)))] = trim(line.substr(eq + 1));
    }
    return out;
}

// ----------------------------------------------------------------------------
// patching
// ----------------------------------------------------------------------------

void applyPatch(Bytes &d, uint64_t off, const Bytes &p) {
    if (p.empty() || off > d.size() || p.size() > d.size() - (size_t) off)
        throw Err("Patch is out of bounds.");
    memcpy(d.data() + off, p.data(), p.size());
}

std::string doProcess(const std::string &action, const std::string &input, const std::string &output,
                      const std::string &ini, const std::string &offsetHex, const std::string &hexPayload,
                      const std::string &asmPayload, const std::string &arch) {
    std::string log;
    log += "[*] Action: " + action + "\n";
    log += "[*] Input File: " + input + "\n";

    if (action == "PATCH_SINGLE") {
        Bytes data = readFile(input);
        uint64_t off = parseOffset(offsetHex);
        Bytes payload = asmPayload.empty() ? parseHex(hexPayload) : assemble(asmPayload, arch);
        applyPatch(data, off, payload);
        writeFile(output, data.data(), data.size());
        log += "[+] Single patch applied successfully.\n";
        log += "[*] Patched " + std::to_string(payload.size()) + " bytes at offset 0x" + hexNum(off) + ".\n";
        log += "[+] Output saved to: " + output + "\n";
        return log;
    }

    if (action == "PATCH_FROM_INI") {
        log += "[*] Using config file: " + ini + "\n";
        std::vector<IniSection> secs = parseIni(ini);
        Bytes data = readFile(input);
        std::string backup;
        int done = 0;

        for (size_t i = 0; i < secs.size(); i++) {
            const IniSection &s = secs[i];
            try {
                std::map<std::string, std::string>::const_iterator o = s.kv.find("offset");
                if (o == s.kv.end()) throw Err("missing 'offset'.");
                uint64_t off = parseOffset(o->second);

                Bytes payload;
                std::map<std::string, std::string>::const_iterator a = s.kv.find("patch_asm");
                if (a != s.kv.end() && !a->second.empty()) {
                    std::map<std::string, std::string>::const_iterator ar = s.kv.find("arch");
                    if (ar == s.kv.end()) throw Err("'patch_asm' needs an 'arch' key.");
                    payload = assemble(a->second, ar->second);
                } else {
                    std::map<std::string, std::string>::const_iterator h = s.kv.find("patch_hex");
                    if (h == s.kv.end()) throw Err("missing 'patch_hex'.");
                    payload = parseHex(h->second);
                }

                if (payload.empty() || off > data.size() || payload.size() > data.size() - (size_t) off)
                    throw Err("Out of bounds.");
                Bytes orig(data.begin() + off, data.begin() + off + payload.size());
                memcpy(data.data() + off, payload.data(), payload.size());

                done++;
                backup += "[Patch_" + std::to_string(done) + "]\n";
                backup += "offset=" + trim(o->second) + "\n";   // written exactly as in the INI
                backup += "backup_hex=" + toHexSpaced(orig.data(), orig.size()) + "\n\n";
            } catch (const Err &e) {
                log += "[-] Skipping patch " + s.name + ": " + e.what() + "\n";
            }
        }

        if (done == 0) {
            log += "\n[-] No valid patches found or applied from INI file.\n";
            return log;
        }
        std::string bak = input + ".bak.ini";
        writeText(bak, backup);
        log += "[+] Backup file created at: " + bak + "\n";
        writeFile(output, data.data(), data.size());
        log += "[+] Success! Processed " + std::to_string(done) + " patches.\n";
        log += "[+] Output saved to: " + output + "\n";
        return log;
    }

    if (action == "RESTORE_FROM_INI") {
        log += "[*] Using config file: " + ini + "\n";
        std::vector<IniSection> secs = parseIni(ini);
        Bytes data = readFile(input);
        int done = 0;

        for (size_t i = 0; i < secs.size(); i++) {
            const IniSection &s = secs[i];
            try {
                std::map<std::string, std::string>::const_iterator o = s.kv.find("offset");
                std::map<std::string, std::string>::const_iterator b = s.kv.find("backup_hex");
                if (o == s.kv.end() || b == s.kv.end()) throw Err("missing 'offset' or 'backup_hex'.");
                uint64_t off = parseOffset(o->second);
                Bytes orig = parseHex(b->second);
                if (off > data.size() || orig.size() > data.size() - (size_t) off) throw Err("Out of bounds.");
                memcpy(data.data() + off, orig.data(), orig.size());
                done++;
            } catch (const Err &e) {
                log += "[-] Skipping patch " + s.name + ": " + e.what() + "\n";
            }
        }

        if (done == 0) {
            log += "\n[-] No valid patches found or applied from INI file.\n";
            return log;
        }
        writeFile(output, data.data(), data.size());
        log += "[+] Success! Processed " + std::to_string(done) + " patches.\n";
        log += "[+] Output saved to: " + output + "\n";
        return log;
    }

    throw Err("Unknown action: " + action);
}

// ----------------------------------------------------------------------------
// ELF summary / dump
// ----------------------------------------------------------------------------

std::string doInfo(const std::string &path) {
    Bytes d = readFile(path);
    ElfFile I = parseElf(d);
    std::vector<Sym> funcs = functionsOf(I);

    const std::string line(50, '-');
    char buf[64];
    std::string r;
    r += "File                : " + baseName(path) + "\n";
    snprintf(buf, sizeof(buf), "%zu", d.size());
    r += std::string("Size                : ") + buf + " Bytes\n";
    r += "MD5                 : " + md5hex(d.data(), d.size()) + "\n";
    r += "SHA256              : " + sha256hex(d.data(), d.size()) + "\n";
    r += line + "\nELF Header\n" + line + "\n";
    r += std::string("Class               : ") + (I.is64 ? "ELF64" : "ELF32") + "\n";
    r += "Data                : 2's complement, little endian\n";
    r += "Type                : " + typeName(I.type) + "\n";
    r += "Machine             : " + machineName(I.machine) + "\n";
    r += "Entry Point Address : 0x" + hexNum(I.entry) + "\n";
    r += line + "\nStructure Summary\n" + line + "\n";
    r += "# of Program Headers          : " + std::to_string(I.phnum) + "\n";
    r += "# of Sections                 : " + std::to_string(I.shnum) + "\n";
    r += "# of Symbols (Total)          : " + std::to_string(I.symTotal) + "\n";
    r += "# of Instructions (.text)     : N/A (Requires Capstone)\n";
    r += "# of Functions (Filtered)     : " + std::to_string(funcs.size()) + "\n";
    return r;
}

std::string doDump(const std::string &input, const std::string &output) {
    std::string log = "[*] Starting Advanced ELF Dumper...\n";
    log += "[*] Input File: " + input + "\n";

    Bytes d;
    ElfFile I;
    try {
        d = readFile(input);
        I = parseElf(d);
    } catch (const Err &) {
        return log + "[-] FATAL ERROR: Failed to load ELF file.\n";
    }
    log += "[+] ELF file loaded successfully.\n";
    log += "[*] Analyzing symbols...\n";

    std::vector<Sym> funcs = functionsOf(I);
    log += "[*] Found " + std::to_string(funcs.size()) + " functions after filtering.\n";
    log += "[*] Writing to output file...\n";

    char tbuf[32] = "";
    time_t now = time(nullptr);
    struct tm tmv;
    localtime_r(&now, &tmv);
    strftime(tbuf, sizeof(tbuf), "%Y-%m-%d %H:%M:%S", &tmv);

    std::string o;
    o += "/*======================================================================\n";
    o += " *  Advanced ELF Dumper (Generated by PatchTool)\n";
    o += " *  Inspired by Soufaine's script\n";
    o += " *----------------------------------------------------------------------\n";
    o += " *  Target   : " + baseName(input) + "\n";
    o += " *  Size     : " + std::to_string(d.size()) + " bytes\n";
    o += std::string(" *  Arch     : ") + (I.is64 ? "64-bit" : "32-bit") + " (" + archName(I.machine) + ")\n";
    o += " *  Functions: " + std::to_string(funcs.size()) + " (filtered)\n";
    o += std::string(" *  Generated: ") + tbuf + "\n";
    o += " *======================================================================*/\n\n";

    for (size_t i = 0; i < funcs.size(); i++) {
        const Sym &s = funcs[i];
        // ARM/Thumb marks Thumb functions with bit 0 set in st_value
        uint64_t rva = (I.machine == EM_ARM) ? (s.value & ~1ULL) : s.value;
        std::string dm = demangle(s.name);
        o += "// RVA: 0x" + hexNum(rva) + "  Size: 0x" + hexNum(s.size) + "\n";
        if (dm != s.name) o += "// " + dm + "\n";
        o += "void " + s.name + "();\n\n";
    }

    try {
        writeText(output, o);
    } catch (const Err &e) {
        return log + "[-] FATAL ERROR: " + e.what() + "\n";
    }
    log += "[+] Success! Dump file created.\n";
    log += "[+] Output saved to: " + output + "\n";
    return log;
}

}  // namespace

// ----------------------------------------------------------------------------
// JNI entry points
// ----------------------------------------------------------------------------

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_patchtool_MainActivity_getElfArchitecture(JNIEnv *env, jobject, jstring path) {
    try {
        Bytes d = readFile(jstr(env, path));
        return mk(env, archName(parseElf(d).machine));
    } catch (const std::exception &) {
        return mk(env, "unsupported");
    }
}

JNIEXPORT jstring JNICALL
Java_com_patchtool_MainActivity_getElfInfo(JNIEnv *env, jobject, jstring path) {
    try {
        return mk(env, doInfo(jstr(env, path)));
    } catch (const std::exception &) {
        return mk(env, "ERROR: Failed to load or parse the ELF file.");
    }
}

JNIEXPORT jstring JNICALL
Java_com_patchtool_MainActivity_processFile(JNIEnv *env, jobject, jstring action, jstring inputFile,
                                            jstring outputFile, jstring iniFile, jstring offsetHex,
                                            jstring hexPayload, jstring asmPayload, jstring arch) {
    std::string a = jstr(env, action), in = jstr(env, inputFile);
    try {
        return mk(env, doProcess(a, in, jstr(env, outputFile), jstr(env, iniFile), jstr(env, offsetHex),
                                 jstr(env, hexPayload), jstr(env, asmPayload), jstr(env, arch)));
    } catch (const std::exception &e) {
        return mk(env, "[*] Action: " + a + "\n[*] Input File: " + in + "\n\n[-] FATAL ERROR: " + e.what() + "\n");
    }
}

JNIEXPORT jstring JNICALL
Java_com_patchtool_MainActivity_dumpLibraryFile(JNIEnv *env, jobject, jstring inputFile, jstring outputFile) {
    try {
        return mk(env, doDump(jstr(env, inputFile), jstr(env, outputFile)));
    } catch (const std::exception &e) {
        return mk(env, std::string("[-] FATAL ERROR: ") + e.what() + "\n");
    }
}

}  // extern "C"
