cmake_minimum_required(VERSION 3.18.1)
project(NetSenseMesh LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED  ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include_directories(${CMAKE_SOURCE_DIR}/include)

add_library(netsense_mesh SHARED
    src/cpp/mesh_engine.cpp
    src/cpp/node.cpp
    src/cpp/routing_table.cpp
    src/cpp/state_machine.cpp
    src/cpp/jni_bridge.cpp
)

target_compile_definitions(netsense_mesh PRIVATE NETSENSE_MESH_EXPORT)

target_link_libraries(netsense_mesh
    ${CMAKE_THREAD_LIBS_INIT}
)

# Android-specific:
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(netsense_mesh ${log-lib})
endif()

# ============================================================================
# ECS ENGINE JNI LIBRARY
# Wraps AdaptiveRTC (RTTTracker + ECSDetector + RateController) for Kotlin.
# Produces: adaptive_rtc_jni.so  loaded by EcsBridge via System.loadLibrary.
# ============================================================================
set(ADAPTIVE_RTC_DIR "${CMAKE_SOURCE_DIR}/../AdaptiveRTC")

add_library(adaptive_rtc_jni SHARED
    # JNI bridge (new)
    src/cpp/ecs_bridge.cpp

    # C++ core logger (shared by ecs_bridge and future native modules)
    src/cpp/CoreLogger.cpp

    # AdaptiveRTC engine sources (compiled directly — no pre-built .a needed)
    ${ADAPTIVE_RTC_DIR}/src/rtt_tracker.cpp
    ${ADAPTIVE_RTC_DIR}/src/ecs_detector.cpp
    ${ADAPTIVE_RTC_DIR}/src/rate_controller.cpp
    ${ADAPTIVE_RTC_DIR}/src/packet.cpp
    ${ADAPTIVE_RTC_DIR}/src/jitter_buffer.cpp
    ${ADAPTIVE_RTC_DIR}/src/adaptive_jitter_buffer.cpp
    ${ADAPTIVE_RTC_DIR}/src/metrics_logger.cpp
    ${ADAPTIVE_RTC_DIR}/src/receiver.cpp
    ${ADAPTIVE_RTC_DIR}/src/sender.cpp
    ${ADAPTIVE_RTC_DIR}/src/network_simulator.cpp
)

target_include_directories(adaptive_rtc_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${ADAPTIVE_RTC_DIR}/include
    ${CMAKE_SOURCE_DIR}/include        # for netsense_mesh/CoreLogger.h
)

target_compile_options(adaptive_rtc_jni PRIVATE -Wall -Wextra)

target_link_libraries(adaptive_rtc_jni
    ${CMAKE_THREAD_LIBS_INIT}
)

if(ANDROID)
    target_link_libraries(adaptive_rtc_jni ${log-lib})
endif()