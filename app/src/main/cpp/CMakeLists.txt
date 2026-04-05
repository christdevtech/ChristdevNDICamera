cmake_minimum_required(VERSION 3.22.1)

project("ndi_wrapper")

# Include directory for NDI Headers
include_directories(include)

cmake_minimum_required(VERSION 3.22.1)

project("ndi_wrapper")

# Find the log library
find_library(log-lib log)

# Define the shared library
add_library(ndi_wrapper SHARED ndi_wrapper.cpp)

# Set include directories
target_include_directories(ndi_wrapper PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")

# Define the path to the NDI library based on the current ABI (e.g., arm64-v8a)
set(NDI_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so")

# Verify the library exists at configuration time
if(NOT EXISTS "${NDI_LIB_PATH}")
    message(FATAL_ERROR "NDI library not found at: ${NDI_LIB_PATH}")
endif()

# Link the libraries. Using the absolute path directly is the most reliable way
# to ensure it's included in the linker command line.
target_link_libraries(ndi_wrapper
        ${log-lib}
        android
        atomic
        m
        "${NDI_LIB_PATH}")
