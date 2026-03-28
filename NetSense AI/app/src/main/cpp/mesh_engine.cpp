#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <android/log.h>
#include "routing_table.h"

#define LOG_TAG "mesh_engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace mesh {

struct Message {
    std::string source;
    std::string destination;
    std::string payload;
    int ttl = 8;
};

enum class SendResult { Success = 0, NoRoute = 1, InvalidNode = 2, TtlExpired = 3, LocalDestination = 4 };

class MeshEngine {
public:
    explicit MeshEngine(const std::string &localNodeId)
        : localNodeId(localNodeId), running(false) {}

    ~MeshEngine() { stop(); }

    void start() {
        std::lock_guard<std::mutex> lock(threadMutex);
        if (running) return;
        running = true;
        worker = std::thread(&MeshEngine::loop, this);
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(threadMutex);
            if (!running) return;
            running = false;
        }
        if (worker.joinable()) worker.join();
    }

    void setCallback(JNIEnv *env, jobject callbackObject) {
        std::lock_guard<std::mutex> lock(callbackMutex);
        if (this->callbackObj) {
            env->DeleteGlobalRef(this->callbackObj);
            this->callbackObj = nullptr;
        }
        this->callbackObj = env->NewGlobalRef(callbackObject);
    }

    void setJVM(JavaVM* vm) { javaVm = vm; }

    SendResult sendMessage(const Message &msg) {
        std::lock_guard<std::mutex> lock(routingMutex);
        if (msg.source != localNodeId) return SendResult::InvalidNode;
        if (msg.destination == localNodeId) return SendResult::LocalDestination;
        if (msg.ttl <= 0) return SendResult::TtlExpired;
        if (!routing.hasRoute(localNodeId, msg.destination)) return SendResult::NoRoute;

        std::string nextHop = routing.findNextHop(localNodeId, msg.destination);
        if (nextHop.empty()) return SendResult::NoRoute;

        // simulate transmission
        LOGI("sendMessage source=%s dest=%s nextHop=%s ttl=%d", msg.source.c_str(), msg.destination.c_str(), nextHop.c_str(), msg.ttl);
        pushEvent(msg);
        return SendResult::Success;
    }

    void addPeer(const std::string &peerId) {
        std::lock_guard<std::mutex> lock(routingMutex);
        routing.addNeighbor(localNodeId, peerId);
    }

    void receiveMessage(const Message &msg) {
        if (msg.ttl <= 0) {
            LOGE("Dropping message, TTL expired");
            return;
        }

        // simulate message received locally
        callOnMessageReceived(msg.source, msg.destination, msg.payload);
    }

private:
    void loop() {
        while (running) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            // periodic health or routing updates
        }
    }

    void pushEvent(const Message &msg) {
        callOnMessageReceived(msg.source, msg.destination, msg.payload);
    }

    void callOnMessageReceived(const std::string &source, const std::string &dest, const std::string &payload) {
        std::lock_guard<std::mutex> lock(callbackMutex);
        if (!javaVm || !callbackObj) return;

        JNIEnv *env;
        bool attached = false;
        if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            javaVm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }

        jclass cls = env->GetObjectClass(callbackObj);
        jmethodID mid = env->GetMethodID(cls, "onMeshMessage", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (mid) {
            jstring src = env->NewStringUTF(source.c_str());
            jstring dst = env->NewStringUTF(dest.c_str());
            jstring pl = env->NewStringUTF(payload.c_str());

            env->CallVoidMethod(callbackObj, mid, src, dst, pl);

            env->DeleteLocalRef(src);
            env->DeleteLocalRef(dst);
            env->DeleteLocalRef(pl);
        }

        if (attached) {
            javaVm->DetachCurrentThread();
        }
    }

    std::string localNodeId;
    std::atomic<bool> running;
    std::thread worker;
    std::mutex threadMutex;

    mesh::RoutingTable routing;
    std::mutex routingMutex;

    JavaVM* javaVm = nullptr;
    jobject callbackObj = nullptr;
    std::mutex callbackMutex;
};

static MeshEngine *gEngine = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mesh_MeshManager_nativeInit(JNIEnv* env, jobject thiz, jstring localId) {
    const char* cLocalId = env->GetStringUTFChars(localId, NULL);
    if (!cLocalId) return;

    if (gEngine) delete gEngine;
    gEngine = new MeshEngine(std::string(cLocalId));

    JavaVM* jvm = nullptr;
    if (env->GetJavaVM(&jvm) == JNI_OK && jvm != nullptr) {
        gEngine->setJVM(jvm);
    } else {
        LOGE("Failed to get JavaVM in nativeInit");
    }

    gEngine->setCallback(env, thiz);

    env->ReleaseStringUTFChars(localId, cLocalId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mesh_MeshManager_nativeStart(JNIEnv* env, jobject thiz) {
    if (!gEngine) {
        LOGE("nativeStart called before init");
        return;
    }
    gEngine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mesh_MeshManager_nativeStop(JNIEnv* env, jobject thiz) {
    if (!gEngine) return;
    gEngine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mesh_MeshManager_nativeAddPeer(JNIEnv* env, jobject thiz, jstring peerId) {
    if (!gEngine || !peerId) return;
    const char* cPeerId = env->GetStringUTFChars(peerId, NULL);
    if (!cPeerId) return;
    gEngine->addPeer(cPeerId);
    env->ReleaseStringUTFChars(peerId, cPeerId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mesh_MeshManager_nativeSendMessage(JNIEnv* env, jobject thiz,
                                                    jstring destination,
                                                    jstring payload,
                                                    jint ttl) {
    if (!gEngine || !destination || !payload) return JNI_FALSE;

    const char* cDest = env->GetStringUTFChars(destination, NULL);
    const char* cPayload = env->GetStringUTFChars(payload, NULL);
    if (!cDest || !cPayload) {
        if (cDest) env->ReleaseStringUTFChars(destination, cDest);
        if (cPayload) env->ReleaseStringUTFChars(payload, cPayload);
        return JNI_FALSE;
    }

    mesh::Message msg;
    msg.source = "local-node";
    msg.destination = cDest;
    msg.payload = cPayload;
    msg.ttl = ttl > 0 ? ttl : 8;

    env->ReleaseStringUTFChars(destination, cDest);
    env->ReleaseStringUTFChars(payload, cPayload);

    auto result = gEngine->sendMessage(msg);
    return result == mesh::SendResult::Success ? JNI_TRUE : JNI_FALSE;
}

}
