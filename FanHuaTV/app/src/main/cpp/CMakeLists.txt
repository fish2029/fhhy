cmake_minimum_required(VERSION 3.22.1)

project("fanhua-security")

add_library(
        fanhua-native
        SHARED
        native-core.cpp
)

find_library(log-lib log)

target_link_libraries(
        fanhua-native
        ${log-lib}
)
