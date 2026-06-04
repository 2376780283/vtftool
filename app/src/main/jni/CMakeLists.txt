cmake_minimum_required(VERSION 3.10.2)

project("valvevtftool")

find_package(ZLIB REQUIRED)

# Add libpng subdirectory
add_subdirectory(libpng)

add_library(valvevtftool SHARED vtf_tool.c)

# Link against libpng (static) and zlib
target_link_libraries(valvevtftool png_static z jnigraphics log)
