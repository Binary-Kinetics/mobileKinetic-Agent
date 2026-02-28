cmake_minimum_required(VERSION 3.22.1)

project(mobilekinetic-terminal C)

add_library(mobilekinetic-terminal SHARED
    termux.c
)

target_link_libraries(mobilekinetic-terminal
    log
)

# 16KB page size alignment for Android 15+ (Pixel device etc.)
target_link_options(mobilekinetic-terminal PRIVATE "-Wl,-z,max-page-size=16384")

# W^X execve() wrapper - intercepts execve() to route ELF binaries in /data/
# through /system/bin/linker64, bypassing Android 15+ W^X restrictions.
# Loaded via LD_PRELOAD in the shell environment.
add_library(termux-exec SHARED
    termux-exec.c
)

target_link_libraries(termux-exec
    dl
)

# Same 16KB page alignment for Pixel device compatibility
target_link_options(termux-exec PRIVATE "-Wl,-z,max-page-size=16384")
