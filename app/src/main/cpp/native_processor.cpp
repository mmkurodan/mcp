#include <android/log.h>
#include <jni.h>

#include <string>

namespace {

constexpr const char* kTag = "mcp_native";

void throwIllegalArgumentException(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

}  // namespace

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_micklab_mcp_runtime_NativeImageBridge_nativeInvertGrayscale(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray grayscalePixels,
        jint width,
        jint height) {
    if (width <= 0 || height <= 0) {
        throwIllegalArgumentException(env, "width and height must be positive");
        return nullptr;
    }

    const jsize inputLength = env->GetArrayLength(grayscalePixels);
    const jsize expectedLength = width * height;
    if (inputLength != expectedLength) {
        throwIllegalArgumentException(env, "pixel buffer length must equal width * height");
        return nullptr;
    }

    jbyte* inputBytes = env->GetByteArrayElements(grayscalePixels, nullptr);
    jbyteArray outputArray = env->NewByteArray(expectedLength);
    if (outputArray == nullptr) {
        env->ReleaseByteArrayElements(grayscalePixels, inputBytes, JNI_ABORT);
        return nullptr;
    }

    jbyte* outputBytes = env->GetByteArrayElements(outputArray, nullptr);
    for (jsize index = 0; index < expectedLength; ++index) {
        unsigned char pixel = static_cast<unsigned char>(inputBytes[index]);
        outputBytes[index] = static_cast<jbyte>(255 - pixel);
    }

    env->ReleaseByteArrayElements(grayscalePixels, inputBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(outputArray, outputBytes, 0);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Processed %d grayscale bytes", expectedLength);
    return outputArray;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_mcp_runtime_NativeImageBridge_nativeRuntimeInfo(
        JNIEnv* env,
        jobject /* this */) {
    std::string payload = R"({"library":"mcp_native","algorithm":"invert_grayscale","notes":"Replace with image/audio/ML JNI pipelines as needed."})";
    return env->NewStringUTF(payload.c_str());
}
