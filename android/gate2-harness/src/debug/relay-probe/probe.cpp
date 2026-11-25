#include <jni.h>
#include <string>
#include "node.h"

static std::string copyString(JNIEnv* env, jstring value) {
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (text == nullptr) {
        return {};
    }
    std::string copy(text);
    env->ReleaseStringUTFChars(value, text);
    return copy;
}

extern "C" JNIEXPORT jint JNICALL Java_com_symphonia_gate2_prototype_harness_Gate2RelayProbeActivity_runNode(
    JNIEnv* env, jclass, jstring scriptPath, jstring workerPath) {
    std::string script = copyString(env, scriptPath);
    std::string worker = copyString(env, workerPath);
    if (script.empty() || worker.empty()) {
        return 2;
    }
    char command[] = "node";
    char* argv[] = {command, script.data(), worker.data(), nullptr};
    return node::Start(3, argv);
}
