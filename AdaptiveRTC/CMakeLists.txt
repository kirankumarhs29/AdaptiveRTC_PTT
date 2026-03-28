cmake_minimum_required(VERSION 3.16)
project(AdaptiveRTC VERSION 1.0.0 LANGUAGES CXX)

# ============================================================================
# PROJECT CONFIGURATION
# ============================================================================
# Why C++17?
# - std::optional for optional values (cleaner than pointers)
# - std::variant for type-safe unions (cleaner packet handling)
# - std::filesystem for file operations (metrics logging)
# - std::any is also available if needed
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Output directories configuration
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)

# ============================================================================
# MAIN LIBRARY
# ============================================================================
# Core library containing all real-time components
add_library(adaptive_rtc STATIC
    # Core components
    src/packet.cpp
    src/network_simulator.cpp
    src/rtt_tracker.cpp
    src/ecs_detector.cpp
    src/rate_controller.cpp
    src/jitter_buffer.cpp
    src/adaptive_jitter_buffer.cpp
    src/sender.cpp
    src/receiver.cpp
    
    # Metrics and logging
    src/metrics_logger.cpp
)

# Public headers directory
target_include_directories(adaptive_rtc 
    PUBLIC 
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# ============================================================================
# COMPILER FLAGS
# ============================================================================
# Enable all warnings for clean code
if(MSVC)
    # Windows/Visual Studio
    target_compile_options(adaptive_rtc PRIVATE /W4 /WX)
    # Enable optimization for Release builds
    target_compile_options(adaptive_rtc PRIVATE $<$<CONFIG:Release>:/O2>)
else()
    # GCC/Clang
    target_compile_options(adaptive_rtc PRIVATE -Wall -Wextra -Wpedantic)
    # Catch more potential issues
    target_compile_options(adaptive_rtc PRIVATE -Wconversion -Wsign-compare)
endif()

# ============================================================================
# SIMULATION EXECUTABLE
# ============================================================================
# Main application for system simulation and testing
add_executable(rtc_simulator
    src/main_simulator.cpp
)

target_link_libraries(rtc_simulator PRIVATE adaptive_rtc)

# ============================================================================
# TESTS
# ============================================================================
# Enable CTest for running tests
enable_testing()

# Each test source has its own main(), so each must be a separate executable.
set(RTC_TEST_SOURCES
    test/test_packet.cpp
    test/test_rtt_tracker.cpp
    test/test_ecs_detector.cpp
    test/test_jitter_buffer.cpp
    test/test_rate_controller.cpp
    test/test_network_simulator.cpp
    test/test_adaptive_jitter_buffer.cpp
    test/integration_test.cpp
)

foreach(TEST_SRC IN LISTS RTC_TEST_SOURCES)
    get_filename_component(TEST_NAME ${TEST_SRC} NAME_WE)
    add_executable(${TEST_NAME} ${TEST_SRC})
    target_link_libraries(${TEST_NAME} PRIVATE adaptive_rtc)
    add_test(NAME ${TEST_NAME} COMMAND ${TEST_NAME})
endforeach()

# ============================================================================
# BUILD OUTPUT SUMMARY
# ============================================================================
message(STATUS "AdaptiveRTC Build Configuration:")
message(STATUS "  C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "  Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  Main Library: adaptive_rtc")
message(STATUS "  Simulator: rtc_simulator")
message(STATUS "  Tests: ${RTC_TEST_SOURCES}")
