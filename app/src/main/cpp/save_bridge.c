#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <unwind.h>

enum { SAVE_RAM = 0, RTC = 1, MAX_CART_SAVE = 128 * 1024 }; // Public Libretro memory ABI.
typedef struct { void* handle; void* bytes; size_t size; } CoreMemory;

static void fail(JNIEnv* env, const char* message) {
    if (!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (type) { (*env)->ThrowNew(env, type, message); (*env)->DeleteLocalRef(env, type); }
    }
}

// Every packaged core reports the video frames it really produced: the shared shim, or mGBA's and
// PCSX's own frontend extensions.
static uint64_t (*frameCounter(void* handle))(void) {
    static const char* const names[] = { "cartridge_video_frames", "cartridge_mgba_video_frames", "cartridge_pcsx_video_frames" };
    for (size_t n = 0; n < sizeof names / sizeof *names; ++n) {
        void* symbol = dlsym(handle, names[n]);
        if (symbol) return (uint64_t (*)(void))symbol;
    }
    return NULL;
}

// The thread the core ran on, when its session ends: a core that draws for itself is unloaded there, before the
// host unloads it from the main thread. Nothing happens for a core without the hardware shim or one not loaded.
JNIEXPORT void JNICALL
Java_dev_cartridge_emulation_SaveBridge_unload(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    const char* utf = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (!utf) return;
    void* handle = dlopen(utf, RTLD_NOW | RTLD_NOLOAD);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    if (!handle) return;
    void (*unload)(void) = (void (*)(void))dlsym(handle, "cartridge_unload");
    if (unload) unload();
    dlclose(handle);
}

// Diagnostic for an engine that has stopped answering: where each of its threads is. A device without root gives
// no other view into a process, and an engine with five threads of its own can wait on itself. Each thread is
// interrupted in turn and writes its own native stack to the log, as library and offset, for threads that are
// inside a core or the host; the offsets are read against the unstripped library of the same build.
struct stack { uintptr_t frames[40]; int count; };

static _Unwind_Reason_Code frame(struct _Unwind_Context* context, void* argument) {
    struct stack* stack = argument;
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc && stack->count < 40) stack->frames[stack->count++] = pc;
    return stack->count < 40 ? _URC_NO_REASON : _URC_END_OF_STACK;
}

static void reportStack(int number, siginfo_t* info, void* context) {
    (void)number; (void)info; (void)context;
    struct stack stack = {0};
    _Unwind_Backtrace(frame, &stack);
    char name[32] = "?";
    prctl(PR_GET_NAME, name);
    // The thread the host runs a core on is reported wherever it is: it may be waiting outside both.
    bool engine = strncmp(name, "GLThread", 8) == 0;
    Dl_info where[40];
    for (int n = 0; n < stack.count; ++n) {
        memset(&where[n], 0, sizeof where[n]);
        if (dladdr((void*)stack.frames[n], &where[n]) && where[n].dli_fname && strstr(where[n].dli_fname, "libretro")) engine = true;
    }
    if (!engine) return;
    int thread = (int)syscall(SYS_gettid);
    for (int n = 0; n < stack.count; ++n) {
        const char* file = where[n].dli_fname ? where[n].dli_fname : "?";
        const char* base = strrchr(file, '/');
        __android_log_print(ANDROID_LOG_INFO, "CartridgeStacks", "%s/%d #%02d %s+0x%lx %s", name, thread, n, base ? base + 1 : file,
            (unsigned long)(stack.frames[n] - (uintptr_t)where[n].dli_fbase), where[n].dli_sname ? where[n].dli_sname : "");
    }
}

JNIEXPORT void JNICALL
Java_dev_cartridge_emulation_SaveBridge_dumpThreads(JNIEnv* env, jobject receiver) {
    (void)env; (void)receiver;
    struct sigaction report, previous;
    memset(&report, 0, sizeof report);
    report.sa_sigaction = reportStack;
    report.sa_flags = SA_SIGINFO | SA_RESTART;
    if (sigaction(SIGUSR2, &report, &previous) != 0) return;
    DIR* tasks = opendir("/proc/self/task");
    if (tasks) {
        int self = (int)syscall(SYS_gettid);
        for (struct dirent* task; (task = readdir(tasks)) != NULL;) {
            int thread = atoi(task->d_name);
            if (thread <= 0 || thread == self) continue;
            syscall(SYS_tgkill, getpid(), thread, SIGUSR2);
            usleep(15000);
        }
        closedir(tasks);
    }
    usleep(200000);
    sigaction(SIGUSR2, &previous, NULL);
}

// Diagnostic: the shared shim's counters for the loaded core, empty for a core without them.
JNIEXPORT jlongArray JNICALL
Java_dev_cartridge_emulation_SaveBridge_stats(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    uint64_t values[16] = {0};
    unsigned count = 0;
    const char* utf = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (utf) {
        void* handle = dlopen(utf, RTLD_NOW | RTLD_NOLOAD);
        (*env)->ReleaseStringUTFChars(env, path, utf);
        if (handle) {
            unsigned (*read)(uint64_t*, unsigned) = (unsigned (*)(uint64_t*, unsigned))dlsym(handle, "cartridge_stats");
            if (read) count = read(values, 16);
            dlclose(handle);
        }
    }
    jlongArray result = (*env)->NewLongArray(env, (jsize)count);
    if (result && count) (*env)->SetLongArrayRegion(env, result, 0, (jsize)count, (const jlong*)values);
    return result;
}

// GL-thread only. NOLOAD references the existing core; never initializes a second instance.
JNIEXPORT jlong JNICALL
Java_dev_cartridge_emulation_SaveBridge_frameCount(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    if (!path) { fail(env, "Missing core path"); return 0; }
    const char* utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) return 0;
    void* handle = dlopen(utf, RTLD_NOW | RTLD_NOLOAD);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    if (!handle) { fail(env, "The emulator core is not loaded"); return 0; }
    uint64_t (*count)(void) = frameCounter(handle);
    jlong frames = count ? (jlong)count() : 0;
    if (!count) fail(env, "The core cannot report video readiness");
    dlclose(handle);
    return frames;
}

static bool openMemory(JNIEnv* env, jstring path, CoreMemory* memory, unsigned kind) {
    if (kind != SAVE_RAM && kind != RTC) { fail(env, "Unsupported cartridge memory"); return false; }
    if (!path) { fail(env, "Missing core path"); return false; }
    const char* utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) return false;
    memory->handle = dlopen(utf, RTLD_NOW | RTLD_NOLOAD);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    if (!memory->handle) { fail(env, "The emulator core is not loaded"); return false; }
    size_t (*getSize)(unsigned) = (size_t (*)(unsigned))dlsym(memory->handle, "retro_get_memory_size");
    void* (*getData)(unsigned) = (void* (*)(unsigned))dlsym(memory->handle, "retro_get_memory_data");
    if (!getSize || !getData) {
        dlclose(memory->handle); fail(env, "The core has no Libretro save memory ABI"); return false;
    }
    void (*syncSavedata)(void) = (void (*)(void))dlsym(memory->handle, "cartridge_mgba_sync_savedata");
    // mGBA needs its VFile synchronization extension; the other packaged cores expose live memory directly.
    if (syncSavedata) syncSavedata();
    else if (!frameCounter(memory->handle)) {
        dlclose(memory->handle); fail(env, "The core cannot synchronize save memory"); return false;
    }
    memory->size = getSize(kind);
    memory->bytes = getData(kind);
    if ((kind != SAVE_RAM && kind != RTC) || memory->size > (kind == RTC ? 4096 : MAX_CART_SAVE) || (memory->size && !memory->bytes)) {
        dlclose(memory->handle); fail(env, "Invalid cartridge save memory"); return false;
    }
    return true;
}

// Diagnostic counts actual non-silent PCM delivered to the frontend, not acoustic output.
JNIEXPORT jlong JNICALL
Java_dev_cartridge_emulation_SaveBridge_audioFrames(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    const char* utf = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (!utf) { fail(env, "Missing core path"); return 0; }
    void* handle = dlopen(utf, RTLD_NOW | RTLD_NOLOAD);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    if (!handle) { fail(env, "The emulator core is not loaded"); return 0; }
    uint64_t (*count)(void) = (uint64_t (*)(void))dlsym(handle, "cartridge_audio_frames");
    if (!count) count = (uint64_t (*)(void))dlsym(handle, "cartridge_pcsx_audio_frames");
    jlong result = count ? (jlong)count() : 0;
    dlclose(handle); return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_cartridge_emulation_SaveBridge_read(JNIEnv* env, jobject receiver, jstring path, jint kind) {
    (void)receiver;
    CoreMemory memory = {0};
    if (!openMemory(env, path, &memory, (unsigned)kind)) return NULL;
    if (kind == RTC && memory.size) {
        // Only mGBA keeps its live clock outside the Libretro region.
        void (*syncRtc)(bool) = (void (*)(bool))dlsym(memory.handle, "cartridge_mgba_sync_rtc");
        if (syncRtc) syncRtc(false);
        else if (dlsym(memory.handle, "cartridge_mgba_sync_savedata")) { dlclose(memory.handle); fail(env, "The packaged core has no RTC synchronization extension"); return NULL; }
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)memory.size);
    if (result && memory.size) (*env)->SetByteArrayRegion(env, result, 0, (jsize)memory.size, memory.bytes);
    dlclose(memory.handle);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_cartridge_emulation_SaveBridge_restore(JNIEnv* env, jobject receiver, jstring path, jbyteArray saved, jint kind) {
    (void)receiver;
    CoreMemory memory = {0};
    if (!openMemory(env, path, &memory, (unsigned)kind)) return JNI_FALSE;
    jsize size = saved ? (*env)->GetArrayLength(env, saved) : 0;
    bool accepted = size > 0 && (size_t)size <= memory.size && (kind != RTC || (size_t)size == memory.size);
    if (accepted) (*env)->GetByteArrayRegion(env, saved, 0, size, memory.bytes);
    if (accepted && kind == RTC) {
        void (*syncRtc)(bool) = (void (*)(bool))dlsym(memory.handle, "cartridge_mgba_sync_rtc");
        if (syncRtc) syncRtc(true);
        else if (dlsym(memory.handle, "cartridge_mgba_sync_savedata")) { accepted = false; fail(env, "The core cannot restore the clock"); }
    }
    dlclose(memory.handle);
    return accepted && !(*env)->ExceptionCheck(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_cartridge_emulation_SaveBridge_captureState(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    CoreMemory memory = {0};
    if (!openMemory(env, path, &memory, SAVE_RAM)) return NULL;
    size_t (*getSize)(void) = (size_t (*)(void))dlsym(memory.handle, "retro_serialize_size");
    bool (*serialize)(void*, size_t) = (bool (*)(void*, size_t))dlsym(memory.handle, "retro_serialize");
    if (!getSize || !serialize) { dlclose(memory.handle); fail(env, "The core has no state serialization ABI"); return NULL; }
    size_t size = getSize();
    if (!size || size > 64 * 1024 * 1024) { dlclose(memory.handle); fail(env, "Invalid save-state size"); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    jbyte* bytes = result ? (*env)->GetByteArrayElements(env, result, NULL) : NULL;
    bool accepted = bytes && serialize(bytes, size);
    if (bytes) (*env)->ReleaseByteArrayElements(env, result, bytes, accepted ? 0 : JNI_ABORT);
    dlclose(memory.handle);
    if (!accepted) { fail(env, "The core could not serialize this state"); return NULL; }
    return result;
}
