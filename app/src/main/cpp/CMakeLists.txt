cmake_minimum_required(VERSION 3.22.1)
project(rubberband_jni)

# Vendored upstream source (breakfastquay/rubberband, v3.3.0, GPLv2+). Do not
# edit files under rubberband/ — treat that directory as a third-party import.
set(RB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rubberband)

# single/RubberBandSingle.cpp is upstream's own "embed without a separate
# build system" entry point: it #includes every .cpp it needs (built-in FFT +
# resampler, no external deps) as one compilation unit. We only add that file
# plus the official JNI bridge (src/jni/RubberBandStretcherJNI.cpp, also
# upstream-provided) — no custom bridge code needed.
add_library(rubberband-jni SHARED
    ${RB_DIR}/single/RubberBandSingle.cpp
    ${RB_DIR}/src/jni/RubberBandStretcherJNI.cpp
)

target_include_directories(rubberband-jni PRIVATE
    ${RB_DIR}
    ${RB_DIR}/src
)

set_target_properties(rubberband-jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_compile_options(rubberband-jni PRIVATE -O2 -ffast-math)
