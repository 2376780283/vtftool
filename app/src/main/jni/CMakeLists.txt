cmake_minimum_required(VERSION 3.10.2)

project("valvevtftool")

find_package(ZLIB REQUIRED)

add_subdirectory(libpng)
# add_subdirectory(libjpeg)
# add_subdirectory(libwebp)

add_library(valvevtftool SHARED vtf_tool.c)

target_link_libraries(valvevtftool png_static z jnigraphics log)
