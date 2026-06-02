cmake_minimum_required(VERSION 3.22.1)
project(filex LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(
    filex
    SHARED
    filex_core.cpp
)

# Release-only optimizations: aggressive opt, thin LTO, hidden symbols and
# dead-code stripping for smaller, faster .so files. Debug stays untouched.
target_compile_options(filex PRIVATE
    $<$<CONFIG:Release>:-O3 -flto=thin -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections -fomit-frame-pointer>
)
target_link_options(filex PRIVATE
    $<$<CONFIG:Release>:-flto=thin -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--strip-all>
)

target_link_libraries(filex ${log-lib})
