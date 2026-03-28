cmake_minimum_required(VERSION 3.22.1)
project(mcp_native)

set(MCP_NODE_HEADERS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libnode/include/node)
set(MCP_NODE_LIBRARY ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)
set(MCP_HAS_NODE_RUNTIME 0)

if (EXISTS ${MCP_NODE_HEADERS_DIR}/node.h AND EXISTS ${MCP_NODE_LIBRARY})
    set(MCP_HAS_NODE_RUNTIME 1)
    message(STATUS "mcp_node will link against bundled libnode.so for ${ANDROID_ABI}")
else()
    message(
            STATUS
            "mcp_node will be built in stub mode for ${ANDROID_ABI}; add app/libnode/include/node/node.h "
            "and app/src/main/jniLibs/${ANDROID_ABI}/libnode.so to enable embedded Node.js."
    )
endif()

add_library(
        mcp_native
        SHARED
        native_processor.cpp
)

add_library(
        mcp_node
        SHARED
        node_runtime_bridge.cpp
)

find_library(log-lib log)

if (MCP_HAS_NODE_RUNTIME)
    add_library(libnode SHARED IMPORTED)
    set_target_properties(
            libnode
            PROPERTIES IMPORTED_LOCATION ${MCP_NODE_LIBRARY}
    )
    target_include_directories(mcp_node PRIVATE ${MCP_NODE_HEADERS_DIR})
    target_compile_definitions(mcp_node PRIVATE MCP_HAS_NODE_RUNTIME=1)
    target_link_libraries(
            mcp_node
            libnode
            ${log-lib}
    )
else()
    target_compile_definitions(mcp_node PRIVATE MCP_HAS_NODE_RUNTIME=0)
    target_link_libraries(
            mcp_node
            ${log-lib}
    )
endif()

target_link_libraries(
        mcp_native
        ${log-lib}
)
