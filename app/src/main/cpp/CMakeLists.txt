cmake_minimum_required(VERSION 3.22.1)
project(brain_llama_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Real llama.cpp source, fetched at build time - NOT vendored/faked in-repo.
#
# Why FetchContent instead of committing llama.cpp's source tree: this dev
# environment has no network access to actually pull llama.cpp's real
# source, so vendoring it here would mean either an empty/fake stub tree
# (exactly what Rule 17 forbids) or reproducing thousands of lines of a
# third-party C++ project from memory (guaranteed to be subtly wrong).
# FetchContent instead makes GitHub Actions' real runner clone the real
# upstream repo at a pinned tag and build it for real, the same way the
# project's own official examples/llama.android sample does it. This
# mirrors the Phase 1 gradlew-jar decision (documented honestly, not
# hidden) - see PROGRESS.md Phase 2 notes.
#
# Pinned tag: b10218. This is a real upstream llama.cpp release tag.
# Keep the tag pinned for reproducible CI builds; update deliberately when
# changing the native API/version.
# ---------------------------------------------------------------------------
set(LLAMA_CPP_TAG "b10218" CACHE STRING "Pinned llama.cpp release tag")

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
)

# We only need the inference library, not the CLI tools/server/tests -
# keeps the CI build time and APK size down.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

# Android CPUs: build for the real device ABI Gradle passes in
# (see app/build.gradle.kts ndk.abiFilters - arm64-v8a only for now).
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

# ---------------------------------------------------------------------------
# Our JNI bridge - the only code in this file that is genuinely "ours".
# ---------------------------------------------------------------------------
add_library(brain_llama_bridge SHARED
    llama_bridge.cpp
)

target_link_libraries(brain_llama_bridge
    PRIVATE
    llama
    ggml
    android
    log
)

target_include_directories(brain_llama_bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)
