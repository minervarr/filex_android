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

target_link_libraries(filex ${log-lib})
