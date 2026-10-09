#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stddef.h>

enum { SAVE_RAM = 0, MAX_GBA_SAVE = 128 * 1024 }; // Public Libretro memory ABI.
typedef struct { void* handle; void* bytes; size_t size; } CoreMemory;

static void fail(JNIEnv* env, const char* message) {
    if (!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (type) { (*env)->ThrowNew(env, type, message); (*env)->DeleteLocalRef(env, type); }
    }
}

// GL-thread only. NOLOAD references the existing core; never initializes a second instance.
static bool openMemory(JNIEnv* env, jstring path, CoreMemory* memory) {
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
    memory->size = getSize(SAVE_RAM);
    memory->bytes = getData(SAVE_RAM);
    if (memory->size > MAX_GBA_SAVE || (memory->size && !memory->bytes)) {
        dlclose(memory->handle); fail(env, "Invalid GBA save memory"); return false;
    }
    return true;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_cartridge_emulation_SaveBridge_read(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    CoreMemory memory = {0};
    if (!openMemory(env, path, &memory)) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)memory.size);
    if (result && memory.size) (*env)->SetByteArrayRegion(env, result, 0, (jsize)memory.size, memory.bytes);
    dlclose(memory.handle);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_cartridge_emulation_SaveBridge_restore(JNIEnv* env, jobject receiver, jstring path, jbyteArray saved) {
    (void)receiver;
    CoreMemory memory = {0};
    if (!openMemory(env, path, &memory)) return JNI_FALSE;
    jsize size = saved ? (*env)->GetArrayLength(env, saved) : 0;
    bool accepted = size > 0 && (size_t)size <= memory.size;
    if (accepted) (*env)->GetByteArrayRegion(env, saved, 0, size, memory.bytes);
    dlclose(memory.handle);
    return accepted && !(*env)->ExceptionCheck(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_cartridge_emulation_SaveBridge_captureState(JNIEnv* env, jobject receiver, jstring path) {
    (void)receiver;
    CoreMemory memory = {0};
    if (!openMemory(env, path, &memory)) return NULL;
    size_t (*getSize)(void) = (size_t (*)(void))dlsym(memory.handle, "retro_serialize_size");
    bool (*serialize)(void*, size_t) = (bool (*)(void*, size_t))dlsym(memory.handle, "retro_serialize");
    if (!getSize || !serialize) { dlclose(memory.handle); fail(env, "The core has no state serialization ABI"); return NULL; }
    size_t size = getSize();
    if (!size || size > 16 * 1024 * 1024) { dlclose(memory.handle); fail(env, "Invalid save-state size"); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    jbyte* bytes = result ? (*env)->GetByteArrayElements(env, result, NULL) : NULL;
    bool accepted = bytes && serialize(bytes, size);
    if (bytes) (*env)->ReleaseByteArrayElements(env, result, bytes, accepted ? 0 : JNI_ABORT);
    dlclose(memory.handle);
    if (!accepted) { fail(env, "The core could not serialize this state"); return NULL; }
    return result;
}
