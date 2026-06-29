cmake_minimum_required(VERSION 3.18.1)

project(camera_ffmpeg_player)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FFMPEG_ROOT ${CMAKE_SOURCE_DIR}/../../../../third_party/ffmpegAndroid/${ANDROID_ABI})
set(FFMPEG_INCLUDE_DIR ${FFMPEG_ROOT}/include)

include_directories(${FFMPEG_INCLUDE_DIR})

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_ROOT}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_ROOT}/libavformat.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_ROOT}/libavutil.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_ROOT}/libswscale.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_ROOT}/libswresample.so)

add_library(camera_ffmpeg_player SHARED
        ffmpeg_rtsp_player.cpp)

find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(camera_ffmpeg_player
        ${android-lib}
        ${log-lib}
        avcodec
        avformat
        avutil
        swscale
        swresample)
