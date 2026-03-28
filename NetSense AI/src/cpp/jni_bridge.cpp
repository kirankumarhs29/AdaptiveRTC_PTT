#include "netsense_mesh/mesh_engine.h"
#include <jni.h>
#include <memory>
#include <string>

using namespace netsense;

static JavaVM* gJvm = nullptr;
static jobject gMeshCallback = nullptr;
static std::unique_ptr<MeshEngine> gEngine;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

static jobject createConnectionStateObject(JNIEnv* env, NodeState state) {
    jclass stateClass = env->FindClass("net/sense/mesh/ConnectionState");
    if (!stateClass) return nullptr;

    const char* fieldName = "Disconnected";
    switch (state) {
        case NodeState::Disconnected: fieldName = "Disconnected"; break;
        case NodeState::Connecting: fieldName = "Connecting"; break;
        case NodeState::Handshake: fieldName = "Connecting"; break;
        case NodeState::Connected: fieldName = "Connected"; break;
        case NodeState::Failed: fieldName = "Error"; break;
    }

    jfieldID fid = env->GetStaticFieldID(stateClass, fieldName, "Lnet/sense/mesh/ConnectionState;");
    if (!fid) return nullptr;
    return env->GetStaticObjectField(stateClass, fid);
}

static void invokeMeshCallbackPeerDiscovered(const std::string& peerId,
                                             const std::string& peerName,
                                             int rssi) {
    if (!gJvm || !gMeshCallback) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        gJvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    jclass callbackClass = env->GetObjectClass(gMeshCallback);
    jmethodID methodId = env->GetMethodID(callbackClass, "onPeerDiscovered", "(Ljava/lang/String;Ljava/lang/String;I)V");
    if (!methodId) goto clean;

    jstring jPeerId = env->NewStringUTF(peerId.c_str());
    jstring jPeerName = env->NewStringUTF(peerName.c_str());
    env->CallVoidMethod(gMeshCallback, methodId, jPeerId, jPeerName, rssi);
    env->DeleteLocalRef(jPeerId);
    env->DeleteLocalRef(jPeerName);

clean:
    if (attached) {
        gJvm->DetachCurrentThread();
    }
}

static void invokeMeshCallbackMessageReceived(const std::string& source,
                                              const std::string& destination,
                                              const std::string& payload) {
    if (!gJvm || !gMeshCallback) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        gJvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    jclass callbackClass = env->GetObjectClass(gMeshCallback);
    jmethodID methodId = env->GetMethodID(callbackClass, "onMessageReceived", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!methodId) goto clean;

    jstring jSource = env->NewStringUTF(source.c_str());
    jstring jDestination = env->NewStringUTF(destination.c_str());
    jstring jPayload = env->NewStringUTF(payload.c_str());
    env->CallVoidMethod(gMeshCallback, methodId, jSource, jDestination, jPayload);
    env->DeleteLocalRef(jSource);
    env->DeleteLocalRef(jDestination);
    env->DeleteLocalRef(jPayload);

clean:
    if (attached) {
        gJvm->DetachCurrentThread();
    }
}

static void invokeMeshCallbackConnectionStateChanged(NodeState state) {
    if (!gJvm || !gMeshCallback) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        gJvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    jclass callbackClass = env->GetObjectClass(gMeshCallback);
    jmethodID methodId = env->GetMethodID(callbackClass, "onConnectionStateChanged", "(Lnet/sense/mesh/ConnectionState;)V");
    if (!methodId) goto clean;

    jobject stateObject = createConnectionStateObject(env, state);
    if (stateObject) {
        env->CallVoidMethod(gMeshCallback, methodId, stateObject);
        env->DeleteLocalRef(stateObject);
    }

clean:
    if (attached) {
        gJvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativeInit(JNIEnv *env, jobject thiz, jstring localId) {
    const char* id = env->GetStringUTFChars(localId, JNI_FALSE);
    gEngine = std::make_unique<MeshEngine>(std::string(id));
    env->ReleaseStringUTFChars(localId, id);

    gEngine->setOnPeerDiscovered([](const std::string& peerId, const std::string& peerName, int rssi) {
        invokeMeshCallbackPeerDiscovered(peerId, peerName, rssi);
    });
    gEngine->setOnMessageReceived([](const std::string& source, const std::string& destination, const std::string& payload) {
        invokeMeshCallbackMessageReceived(source, destination, payload);
    });
    gEngine->setOnConnectionStateChanged([](NodeState state) {
        invokeMeshCallbackConnectionStateChanged(state);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativeSetCallback(JNIEnv* env, jobject thiz, jobject callback) {
    if (gMeshCallback) {
        env->DeleteGlobalRef(gMeshCallback);
        gMeshCallback = nullptr;
    }
    gMeshCallback = env->NewGlobalRef(callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativeShutdown(JNIEnv* env, jobject thiz) {
    gEngine.reset();
    if (gMeshCallback) {
        env->DeleteGlobalRef(gMeshCallback);
        gMeshCallback = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativePeerDiscovered(JNIEnv *env, jobject thiz,
                                                         jstring peerId,
                                                         jstring peerName,
                                                         jint rssi) {
    if (!gEngine) return;
    const char* peerIdC = env->GetStringUTFChars(peerId, JNI_FALSE);
    const char* peerNameC = env->GetStringUTFChars(peerName, JNI_FALSE);

    gEngine->onPeerDiscovered(peerIdC, peerNameC, static_cast<int>(rssi));

    env->ReleaseStringUTFChars(peerId, peerIdC);
    env->ReleaseStringUTFChars(peerName, peerNameC);
}

extern "C" JNIEXPORT void JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativeHandshake(JNIEnv *env, jobject thiz, jstring peerId) {
    if (!gEngine) return;
    const char* peerIdC = env->GetStringUTFChars(peerId, JNI_FALSE);
    gEngine->handshakeWithPeer(peerIdC);
    env->ReleaseStringUTFChars(peerId, peerIdC);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativeSendMessage(JNIEnv *env, jobject thiz,
                                                      jstring destination,
                                                      jstring payload,
                                                      jint ttl) {
    if (!gEngine || destination == nullptr || payload == nullptr) return JNI_FALSE;

    const char* destinationC = env->GetStringUTFChars(destination, JNI_FALSE);
    const char* payloadC = env->GetStringUTFChars(payload, JNI_FALSE);

    if (destinationC == nullptr || payloadC == nullptr) {
        if (destinationC) env->ReleaseStringUTFChars(destination, destinationC);
        if (payloadC) env->ReleaseStringUTFChars(payload, payloadC);
        return JNI_FALSE;
    }

    const std::string destinationStr(destinationC);
    const std::string payloadStr(payloadC);

    env->ReleaseStringUTFChars(destination, destinationC);
    env->ReleaseStringUTFChars(payload, payloadC);

    if (destinationStr.empty() || payloadStr.empty()) {
        return JNI_FALSE;
    }

    MeshMessage msg;
    msg.source = gEngine->getLocalNodeId();
    msg.destination = destinationStr;
    msg.payload = payloadStr;
    msg.ttl = ttl > 0 ? ttl : 8;
    msg.sequence = 1;

    SendResult result = gEngine->sendMessage(msg);
    if (result != SendResult::Success) {
        std::cout << "sendMessage failed with code " << static_cast<int>(result) << std::endl;
    }

    return result == SendResult::Success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_netsense_mesh_NetSenseMesh_nativeReceiveMessage(JNIEnv *env, jobject thiz,
                                                         jstring source,
                                                         jstring destination,
                                                         jstring payload) {
    if (!gEngine) return;
    const char* sourceC = env->GetStringUTFChars(source, JNI_FALSE);
    const char* destinationC = env->GetStringUTFChars(destination, JNI_FALSE);
    const char* payloadC = env->GetStringUTFChars(payload, JNI_FALSE);

    MeshMessage msg;
    msg.source = sourceC;
    msg.destination = destinationC;
    msg.payload = payloadC;
    msg.sequence = 0;

    gEngine->receiveMessage(msg);

    env->ReleaseStringUTFChars(source, sourceC);
    env->ReleaseStringUTFChars(destination, destinationC);
    env->ReleaseStringUTFChars(payload, payloadC);
}
