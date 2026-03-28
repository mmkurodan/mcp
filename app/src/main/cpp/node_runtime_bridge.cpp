#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#if defined(MCP_HAS_NODE_RUNTIME) && MCP_HAS_NODE_RUNTIME
#include "node.h"
#endif

namespace {

constexpr const char* kTag = "mcp_node";

void throwException(JNIEnv* env, const char* className, const char* message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

void throwIllegalArgumentException(JNIEnv* env, const char* message) {
    throwException(env, "java/lang/IllegalArgumentException", message);
}

void throwIllegalStateException(JNIEnv* env, const char* message) {
    throwException(env, "java/lang/IllegalStateException", message);
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_com_micklab_mcp_runtime_NodeJS_nativeStartWithArguments(
        JNIEnv* env,
        jclass /* clazz */,
        jobjectArray arguments) {
    if (arguments == nullptr) {
        throwIllegalArgumentException(env, "Node.js launch arguments are required.");
        return -1;
    }

    jsize argumentCount = env->GetArrayLength(arguments);
    if (argumentCount <= 0) {
        throwIllegalArgumentException(env, "Node.js launch arguments are required.");
        return -1;
    }

#if defined(MCP_HAS_NODE_RUNTIME) && MCP_HAS_NODE_RUNTIME
    std::vector<std::string> utf8Arguments;
    utf8Arguments.reserve(static_cast<size_t>(argumentCount));

    size_t totalBytes = 0;
    for (jsize index = 0; index < argumentCount; ++index) {
        jstring argument = static_cast<jstring>(env->GetObjectArrayElement(arguments, index));
        if (argument == nullptr) {
            throwIllegalArgumentException(env, "Node.js launch arguments may not contain null entries.");
            return -1;
        }

        const char* currentArgument = env->GetStringUTFChars(argument, nullptr);
        if (currentArgument == nullptr) {
            env->DeleteLocalRef(argument);
            return -1;
        }
        utf8Arguments.emplace_back(currentArgument);
        totalBytes += utf8Arguments.back().size() + 1;
        env->ReleaseStringUTFChars(argument, currentArgument);
        env->DeleteLocalRef(argument);
    }

    std::vector<char> contiguousArguments(totalBytes);
    std::vector<char*> argv(static_cast<size_t>(argumentCount));
    char* cursor = contiguousArguments.data();
    for (jsize index = 0; index < argumentCount; ++index) {
        const std::string& argument = utf8Arguments[static_cast<size_t>(index)];
        std::memcpy(cursor, argument.c_str(), argument.size() + 1);
        argv[static_cast<size_t>(index)] = cursor;
        cursor += argument.size() + 1;
    }

    __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "Starting bundled Node.js runtime with %d arguments",
            static_cast<int>(argumentCount)
    );
    return static_cast<jint>(node::Start(argumentCount, argv.data()));
#else
    throwIllegalStateException(
            env,
            "Node.js Mobile headers/libs are not compiled into libmcp_node.so. Commit "
            "app/libnode/include/node/node.h and ABI-matched app/src/main/jniLibs/<abi>/libnode.so files."
    );
    return -1;
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_mcp_runtime_NodeJS_nativeRuntimeInfo(
        JNIEnv* env,
        jclass /* clazz */) {
#if defined(MCP_HAS_NODE_RUNTIME) && MCP_HAS_NODE_RUNTIME
    std::string payload = "compiledNodeRuntime=true, starter=node::Start(argc, argv)";
#else
    std::string payload =
            "compiledNodeRuntime=false, starter=stub, action=commit app/libnode/include/node/node.h "
            "and app/src/main/jniLibs/<abi>/libnode.so";
#endif
    return env->NewStringUTF(payload.c_str());
}
