/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "javautils.h"
#include "jnistring.h"

namespace libretrodroid {

jint JavaUtils::throwRetroException(JNIEnv* env, int errorCode) {
    const char *className = "com/swordfish/libretrodroid/RetroException";
    jclass clazz = env->FindClass(className);
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "(I)V");
    auto exception = (jthrowable) env->NewObject(clazz, constructor, errorCode);
    return env->Throw(exception);
}

Variable JavaUtils::variableFromJava(JNIEnv *env, jobject obj) {
    jclass variableClass = env->FindClass("com/swordfish/libretrodroid/Variable");

    jfieldID jKeyField = env->GetFieldID(variableClass, "key", "Ljava/lang/String;");
    jfieldID jValueField = env->GetFieldID(variableClass, "value", "Ljava/lang/String;");

    auto key = JniString(env, (jstring) env->GetObjectField(obj, jKeyField));
    auto value = JniString(env, (jstring) env->GetObjectField(obj, jValueField));

    return Variable { key.stdString(), value.stdString() };
}

void JavaUtils::forEachOnJavaIterable(
    JNIEnv *env,
    jobject jList,
    const std::function<void(jobject)> &lambda
) {
    auto jIterableClass = env->GetObjectClass(jList);

    jobject jIterator = env->CallObjectMethod(
        jList,
        env->GetMethodID(jIterableClass, "iterator", "()Ljava/util/Iterator;")
    );
    jclass jIteratorClass = env->GetObjectClass(jIterator);

    jmethodID nextMethodID = env->GetMethodID(jIteratorClass, "next", "()Ljava/lang/Object;");
    jmethodID hasNextMethodID = env->GetMethodID(jIteratorClass, "hasNext", "()Z");

    while (env->CallBooleanMethod(jIterator, hasNextMethodID)) {
        lambda(env->CallObjectMethod(jIterator, nextMethodID));
    }
}

std::unordered_map<std::string, std::string> JavaUtils::stringMapFromJava(JNIEnv *env, jobject jMap) {
    jclass jMapClass = env->GetObjectClass(jMap);

    jmethodID keySetMethodId = env->GetMethodID(jMapClass, "keySet", "()Ljava/util/Set;");
    jmethodID getMethodId = env->GetMethodID (
        jMapClass,
        "get",
        "(Ljava/lang/Object;)Ljava/lang/Object;"
    );

    jobject jKeySet = env->CallObjectMethod(jMap, keySetMethodId);

    std::unordered_map<std::string, std::string> result;

    forEachOnJavaIterable(
        env, jKeySet, [&](jobject jKey) {
            jobject objValue = env->CallObjectMethod(jMap, getMethodId, jKey);

            std::string key = JniString(env, (jstring) jKey).stdString();
            std::string value = JniString(env, (jstring) objValue).stdString();
            result[key] = value;
        }
    );

    return result;
}

ShaderManager::Config JavaUtils::shaderFromJava(JNIEnv *env, jobject obj) {
    jclass jShaderClass = env->FindClass("com/swordfish/libretrodroid/GLRetroShader");

    jfieldID jTypeField = env->GetFieldID(jShaderClass, "type", "I");
    jfieldID jParamsField = env->GetFieldID(jShaderClass, "params", "Ljava/util/Map;");
    jfieldID jPresetField = env->GetFieldID(jShaderClass, "preset", "Lcom/swordfish/libretrodroid/ShaderPreset;");

    jobject jParams = env->GetObjectField(obj, jParamsField);

    int type = env->GetIntField(obj, jTypeField);
    std::unordered_map<std::string, std::string> params = stringMapFromJava(env, jParams);

    // Cartridge: a preset travels with its sources and images already read.
    jobject jPreset = env->GetObjectField(obj, jPresetField);
    std::shared_ptr<const PresetSpec> preset = jPreset != nullptr ? presetFromJava(env, jPreset) : nullptr;

    return ShaderManager::Config { ShaderManager::Type(type), params, preset };
}

std::shared_ptr<const PresetSpec> JavaUtils::presetFromJava(JNIEnv *env, jobject obj) {
    auto spec = std::make_shared<PresetSpec>();
    auto string = [env](jobject owner, jfieldID field) {
        auto value = (jstring) env->GetObjectField(owner, field);
        std::string result = value != nullptr ? JniString(env, value).stdString() : std::string();
        env->DeleteLocalRef(value);
        return result;
    };

    jclass presetClass = env->FindClass("com/swordfish/libretrodroid/ShaderPreset");
    spec->feedbackPass = env->GetIntField(obj, env->GetFieldID(presetClass, "feedbackPass", "I"));

    jclass passClass = env->FindClass("com/swordfish/libretrodroid/ShaderPreset$Pass");
    jfieldID source = env->GetFieldID(passClass, "source", "Ljava/lang/String;");
    jfieldID name = env->GetFieldID(passClass, "name", "Ljava/lang/String;");
    jfieldID filter = env->GetFieldID(passClass, "filter", "I");
    jfieldID mipmapInput = env->GetFieldID(passClass, "mipmapInput", "Z");
    jfieldID alias = env->GetFieldID(passClass, "alias", "Ljava/lang/String;");
    jfieldID floatFramebuffer = env->GetFieldID(passClass, "floatFramebuffer", "Z");
    jfieldID srgbFramebuffer = env->GetFieldID(passClass, "srgbFramebuffer", "Z");
    jfieldID frameCountMod = env->GetFieldID(passClass, "frameCountMod", "I");
    jfieldID scaleX = env->GetFieldID(passClass, "scaleX", "F");
    jfieldID scaleY = env->GetFieldID(passClass, "scaleY", "F");
    jfieldID modes = env->GetFieldID(passClass, "modes", "[I");
    jfieldID compatSource = env->GetFieldID(passClass, "compatSource", "Ljava/lang/String;");
    auto passes = (jobjectArray) env->GetObjectField(obj,
        env->GetFieldID(presetClass, "passArray", "[Lcom/swordfish/libretrodroid/ShaderPreset$Pass;"));
    for (jsize i = 0; i < env->GetArrayLength(passes); ++i) {
        jobject jPass = env->GetObjectArrayElement(passes, i);
        PresetSpec::Pass pass;
        pass.source = string(jPass, source);
        pass.name = string(jPass, name);
        pass.compatSource = string(jPass, compatSource);
        pass.filter = env->GetIntField(jPass, filter);
        pass.mipmapInput = env->GetBooleanField(jPass, mipmapInput);
        pass.alias = string(jPass, alias);
        pass.floatFramebuffer = env->GetBooleanField(jPass, floatFramebuffer);
        pass.srgbFramebuffer = env->GetBooleanField(jPass, srgbFramebuffer);
        pass.frameCountMod = (unsigned) std::max(0, (int) env->GetIntField(jPass, frameCountMod));
        pass.scaleX = env->GetFloatField(jPass, scaleX);
        pass.scaleY = env->GetFloatField(jPass, scaleY);
        auto jModes = (jintArray) env->GetObjectField(jPass, modes);
        jint values[3] = { 0, 0, 0 };
        env->GetIntArrayRegion(jModes, 0, 3, values);
        pass.scaleTypeX = values[0];
        pass.scaleTypeY = values[1];
        pass.wrap = values[2];
        env->DeleteLocalRef(jModes);
        env->DeleteLocalRef(jPass);
        spec->passes.push_back(std::move(pass));
    }
    env->DeleteLocalRef(passes);

    jclass textureClass = env->FindClass("com/swordfish/libretrodroid/ShaderPreset$Texture");
    jfieldID textureName = env->GetFieldID(textureClass, "name", "Ljava/lang/String;");
    jfieldID width = env->GetFieldID(textureClass, "width", "I");
    jfieldID height = env->GetFieldID(textureClass, "height", "I");
    jfieldID rgba = env->GetFieldID(textureClass, "rgba", "[B");
    jfieldID linear = env->GetFieldID(textureClass, "linear", "Z");
    jfieldID mipmap = env->GetFieldID(textureClass, "mipmap", "Z");
    jfieldID wrapMode = env->GetFieldID(textureClass, "wrapMode", "I");
    auto textures = (jobjectArray) env->GetObjectField(obj,
        env->GetFieldID(presetClass, "textureArray", "[Lcom/swordfish/libretrodroid/ShaderPreset$Texture;"));
    for (jsize i = 0; i < env->GetArrayLength(textures); ++i) {
        jobject jTexture = env->GetObjectArrayElement(textures, i);
        PresetSpec::Texture texture;
        texture.name = string(jTexture, textureName);
        texture.width = (unsigned) std::max(0, (int) env->GetIntField(jTexture, width));
        texture.height = (unsigned) std::max(0, (int) env->GetIntField(jTexture, height));
        texture.linear = env->GetBooleanField(jTexture, linear);
        texture.mipmap = env->GetBooleanField(jTexture, mipmap);
        texture.wrap = env->GetIntField(jTexture, wrapMode);
        auto jPixels = (jbyteArray) env->GetObjectField(jTexture, rgba);
        texture.rgba.resize((size_t) env->GetArrayLength(jPixels));
        env->GetByteArrayRegion(jPixels, 0, (jsize) texture.rgba.size(), (jbyte*) texture.rgba.data());
        env->DeleteLocalRef(jPixels);
        env->DeleteLocalRef(jTexture);
        spec->textures.push_back(std::move(texture));
    }
    env->DeleteLocalRef(textures);

    auto names = (jobjectArray) env->GetObjectField(obj, env->GetFieldID(presetClass, "parameterNames", "[Ljava/lang/String;"));
    auto values = (jfloatArray) env->GetObjectField(obj, env->GetFieldID(presetClass, "parameterValues", "[F"));
    jsize count = env->GetArrayLength(names);
    std::vector<jfloat> numbers((size_t) count);
    if (count > 0) env->GetFloatArrayRegion(values, 0, count, numbers.data());
    for (jsize i = 0; i < count; ++i) {
        auto jName = (jstring) env->GetObjectArrayElement(names, i);
        spec->parameters.emplace_back(JniString(env, jName).stdString(), numbers[(size_t) i]);
        env->DeleteLocalRef(jName);
    }
    env->DeleteLocalRef(names);
    env->DeleteLocalRef(values);
    return spec;
}

} //namespace libretrodroid
