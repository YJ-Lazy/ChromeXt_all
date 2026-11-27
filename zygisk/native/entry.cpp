#include <sys/types.h>
#include <zygisk.hpp>
#include <jni.h>
#include <android/log.h>
#include <dobby.h>
#include <lsplant.hpp>
#include "xdl.h"
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <array>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

namespace {
constexpr const char* TAG = "ChromeXt-Zygisk";
constexpr std::array targets{"app.vanadium.browser", "com.android.browser", "com.android.chrome", "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly", "com.chrome.beta", "com.chrome.canary", "com.chrome.dev", "com.coccoc.trinhduyet", "com.coccoc.trinhduyet_beta", "com.herond.android.browser", "com.kiwibrowser.browser", "com.mi.globalbrowser", "com.quark.browser", "com.naver.whale", "com.vivaldi.browser", "com.vivaldi.browser.snapshot", "io.github.jqssun.helium", "org.axpos.aosmium", "org.bromite.bromite", "org.chromium.chrome", "org.chromium.thorium", "org.cromite.cromite", "org.greatfire.freebrowser", "org.triple.banana", "us.spotco.mulch", "com.qihoo.contents"};
std::vector<std::vector<unsigned char>> dexes;
std::vector<unsigned char> resources, library;
void* art = nullptr;
bool ready = false;
bool error(JNIEnv* env, const char* stage) {
    if (!env->ExceptionCheck()) return false;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "[CX-ZYGISK] JNI failure: %s", stage);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}
bool selected(const std::string& process) {
    for (const char* name : targets) if (process == name) return true;
    return false;
}
// A root-owned, bounded allowlist is read before specialization. Missing or malformed
// configuration means no injection, never "all browsers".
bool scopeAllows(int dir, const std::string& process) {
    int fd = openat(dir, "injection.list", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != 0 ||
        (st.st_mode & 0022) || st.st_size < 0 || st.st_size > 8192) {
        close(fd);
        return false;
    }
    std::string data(static_cast<size_t>(st.st_size), '\0');
    size_t offset = 0;
    while (offset < data.size()) {
        ssize_t n = read(fd, data.data() + offset, data.size() - offset);
        if (n <= 0) { close(fd); return false; }
        offset += static_cast<size_t>(n);
    }
    close(fd);
    bool match = false;
    size_t start = 0;
    while (start < data.size()) {
        auto end = data.find('\n', start);
        auto line = data.substr(start, end == std::string::npos ? end : end - start);
        if (!selected(line)) return false;
        if (line == process) match = true;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return match;
}
bool readBlob(int dir, const char* name, std::vector<unsigned char>& bytes, size_t maximum) {
    int fd = openat(dir, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != 0 ||
        (st.st_mode & 0022) || st.st_size <= 0 || static_cast<size_t>(st.st_size) > maximum) {
        close(fd); return false;
    }
    bytes.resize(static_cast<size_t>(st.st_size));
    size_t offset = 0;
    while (offset < bytes.size()) {
        ssize_t n = read(fd, bytes.data() + offset, bytes.size() - offset);
        if (n <= 0) { close(fd); bytes.clear(); return false; }
        offset += static_cast<size_t>(n);
    }
    close(fd);
    return true;
}
jbyteArray byteArray(JNIEnv* env, const std::vector<unsigned char>& data) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(data.size()));
    if (arr && !data.empty()) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(data.size()),
        reinterpret_cast<const jbyte*>(data.data()));
    return arr;
}
bool readDexes(int dir) {
    size_t total = 0;
    for (int i = 1; i <= 16; ++i) {
        std::string name = "payload/classes" + (i == 1 ? std::string() : std::to_string(i)) + ".dex";
        int fd = openat(dir, name.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return i > 1 && !dexes.empty();
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size < 112 || st.st_size > 32 * 1024 * 1024 ||
            total + st.st_size > 64 * 1024 * 1024) { close(fd); return false; }
        std::vector<unsigned char> bytes(static_cast<size_t>(st.st_size));
        size_t offset = 0;
        while (offset < bytes.size()) {
            ssize_t n = read(fd, bytes.data() + offset, bytes.size() - offset);
            if (n <= 0) { close(fd); return false; }
            offset += static_cast<size_t>(n);
        }
        close(fd);
        if (memcmp(bytes.data(), "dex\n", 4) != 0) return false;
        total += bytes.size();
        dexes.push_back(std::move(bytes));
    }
    return !dexes.empty();
}
jboolean initNative(JNIEnv* env, jclass) {
    if (ready) return JNI_TRUE;
    art = xdl_open("libart.so", XDL_DEFAULT);
    if (!art) return JNI_FALSE;
    lsplant::InitInfo info{
        .inline_hooker = [](void* target, void* hooker) -> void* {
            dobby_dummy_func_t backup = nullptr;
            return DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(hooker), &backup) == 0
                ? reinterpret_cast<void*>(backup) : nullptr;
        },
        .inline_unhooker = [](void* target) { return DobbyDestroy(target) == 0; },
        .art_symbol_resolver = [](std::string_view symbol) -> void* {
            std::string name(symbol);
            void* result = xdl_sym(art, name.c_str(), nullptr);
            return result ? result : xdl_dsym(art, name.c_str(), nullptr);
        },
        .art_symbol_prefix_resolver = [](std::string_view symbol) -> void* {
            std::string name(symbol);
            return xdl_dsym_prefix(art, name.c_str(), nullptr);
        }
    };
    ready = lsplant::Init(env, info);
    __android_log_print(ready ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
                        "[CX-ZYGISK] LSPlant init=%d", ready);
    return ready ? JNI_TRUE : JNI_FALSE;
}
jobject hookNative(JNIEnv* env, jclass, jobject target, jobject record, jobject callback) {
    return ready ? lsplant::Hook(env, target, record, callback) : nullptr;
}
jobject attachNative(JNIEnv* env, jclass) {
    jclass app = env->FindClass("android/app/Application");
    if (!app || error(env, "Application")) return nullptr;
    jmethodID attach = env->GetMethodID(app, "attach", "(Landroid/content/Context;)V");
    if (!attach || error(env, "Application.attach")) return nullptr;
    return env->ToReflectedMethod(app, attach, JNI_FALSE);
}
jclass load(JNIEnv* env, jobject loader, jmethodID loadClass, const char* name) {
    jstring value = env->NewStringUTF(name);
    auto cls = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, value));
    env->DeleteLocalRef(value);
    if (error(env, name)) return nullptr;
    return cls;
}
void bootstrap(JNIEnv* env, const std::string& process) {
    jclass cl = env->FindClass("java/lang/ClassLoader");
    jclass buffer = env->FindClass("java/nio/ByteBuffer");
    jclass memory = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (error(env, "loader classes") || !cl || !buffer || !memory) return;
    jmethodID system = env->GetStaticMethodID(cl, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID parentMethod = env->GetMethodID(cl, "getParent", "()Ljava/lang/ClassLoader;");
    jmethodID loadClass = env->GetMethodID(cl, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jmethodID ctor = env->GetMethodID(memory, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (error(env, "loader methods") || !system || !parentMethod || !loadClass || !ctor) return;
    jobject systemLoader = env->CallStaticObjectMethod(cl, system);
    if (error(env, "system loader") || !systemLoader) return;
    jobject parent = env->CallObjectMethod(systemLoader, parentMethod);
    if (error(env, "boot parent")) return;
    jobjectArray buffers = env->NewObjectArray(static_cast<jsize>(dexes.size()), buffer, nullptr);
    if (error(env, "dex buffers") || !buffers) return;
    for (size_t i = 0; i < dexes.size(); ++i) {
        jobject b = env->NewDirectByteBuffer(dexes[i].data(), static_cast<jlong>(dexes[i].size()));
        if (error(env, "dex buffer") || !b) return;
        env->SetObjectArrayElement(buffers, static_cast<jsize>(i), b);
        env->DeleteLocalRef(b);
        if (error(env, "dex buffer array")) return;
    }
    jobject loader = env->NewObject(memory, ctor, buffers, parent);
    if (error(env, "in-memory loader") || !loader) return;
    jclass bridge = load(env, loader, loadClass, "de.robv.android.xposed.XposedBridge");
    if (!bridge) return;
    JNINativeMethod natives[] = {
        {const_cast<char*>("initializeNative"), const_cast<char*>("()Z"), reinterpret_cast<void*>(initNative)},
        {const_cast<char*>("hookNative"), const_cast<char*>("(Ljava/lang/reflect/Member;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;"), reinterpret_cast<void*>(hookNative)},
        {const_cast<char*>("applicationAttachNative"), const_cast<char*>("()Ljava/lang/reflect/Method;"), reinterpret_cast<void*>(attachNative)}
    };
    if (env->RegisterNatives(bridge, natives, 3) != JNI_OK || error(env, "register natives")) return;
    jclass entry = load(env, loader, loadClass, "org.matrix.chromext.zygisk.ZygiskEntry");
    if (!entry) return;
    jmethodID install = env->GetStaticMethodID(entry, "install", "(Ljava/lang/String;[B[B)Z");
    if (error(env, "entry method") || !install) return;
    jstring name = env->NewStringUTF(process.c_str());
    jbyteArray res = byteArray(env, resources);
    jbyteArray scripts = byteArray(env, library);
    if (error(env, "runtime resources") || !res || !scripts) return;
    jboolean ok = env->CallStaticBooleanMethod(entry, install, name, res, scripts);
    resources.clear(); library.clear();
    if (error(env, "entry install")) return;
    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
                        "[CX-ZYGISK] bootstrap=%d; process=%s", ok, process.c_str());
}
class Module : public zygisk::ModuleBase {
    zygisk::Api* api = nullptr;
    JNIEnv* env = nullptr;
    std::string process;
    bool enabled = false;
public:
    void onLoad(zygisk::Api* a, JNIEnv* e) override { api = a; env = e; }
    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        if (!args->nice_name) { api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return; }
        const char* raw = env->GetStringUTFChars(args->nice_name, nullptr);
        if (raw) { process = raw; env->ReleaseStringUTFChars(args->nice_name, raw); }
        if (error(env, "process name") || !selected(process)) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return;
        }
        int dir = api->getModuleDir();
        if (dir < 0 || !scopeAllows(dir, process)) {
            if (dir >= 0) close(dir);
            __android_log_print(ANDROID_LOG_DEBUG, TAG, "[CX-ZYGISK] scope skipped: %s", process.c_str());
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        enabled = readDexes(dir) &&
            readBlob(dir, "payload/resources.bin", resources, 32 * 1024 * 1024) &&
            readBlob(dir, "library.json", library, 16 * 1024 * 1024);
        close(dir);
        if (!enabled) {
            dexes.clear();
            __android_log_print(ANDROID_LOG_ERROR, TAG, "[CX-ZYGISK] payload missing or invalid");
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (enabled) bootstrap(env, process);
    }
    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }
};
}
REGISTER_ZYGISK_MODULE(Module)
