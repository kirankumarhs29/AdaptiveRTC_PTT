cmake_minimum_required(VERSION 3.18)
project(mesh_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mesh_engine SHARED
    mesh_engine.cpp
    routing.cpp
)

find_library(log-lib log)

target_include_directories(mesh_engine PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/../../../../include
)

target_link_libraries(mesh_engine PRIVATE ${log-lib})
