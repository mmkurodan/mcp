cmake_minimum_required(VERSION 3.22.1)
project(mcp_native)

add_library(
        mcp_native
        SHARED
        native_processor.cpp
)

find_library(log-lib log)

target_link_libraries(
        mcp_native
        ${log-lib}
)
