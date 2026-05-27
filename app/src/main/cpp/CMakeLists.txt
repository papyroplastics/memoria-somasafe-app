cmake_minimum_required(VERSION 3.22)
project(somasafe_ml)

add_subdirectory(litert)

add_library(somasafe_ml SHARED jni_bridge.cpp)

target_link_libraries(somasafe_ml
    PRIVATE
        litert_training
        litert_accelerator
        log
)
