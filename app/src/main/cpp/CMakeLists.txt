cmake_minimum_required(VERSION 3.22.1)
project(vrcode_hand_menu LANGUAGES CXX)
find_package(OpenXR REQUIRED CONFIG)
add_library(vrcode-hand-menu SHARED left_palm_menu.cpp)
target_compile_definitions(vrcode-hand-menu PRIVATE XR_NO_PROTOTYPES)
target_compile_options(vrcode-hand-menu PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vrcode-hand-menu PRIVATE OpenXR::headers log)

add_library(vrcode-boundary SHARED boundary_visibility.cpp)
target_compile_definitions(vrcode-boundary PRIVATE XR_NO_PROTOTYPES)
target_compile_options(vrcode-boundary PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vrcode-boundary PRIVATE OpenXR::headers)

# Left-hand joints for the strict voice-mode fist (hands/LeftHandJoints.kt).
add_library(vrcode-hand-joints SHARED hand_joints.cpp)
target_compile_definitions(vrcode-hand-joints PRIVATE XR_NO_PROTOTYPES)
target_compile_options(vrcode-hand-joints PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vrcode-hand-joints PRIVATE OpenXR::headers log)

# Read Meta's runtime middle-pinch detector for the right hand (no gesture classifier).
add_library(vrcode-right-pinch SHARED right_middle_pinch.cpp)
target_compile_definitions(vrcode-right-pinch PRIVATE XR_NO_PROTOTYPES)
target_compile_options(vrcode-right-pinch PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vrcode-right-pinch PRIVATE OpenXR::headers log)

# Either hand: joints, palm pose and the runtime aim state for the palm-facing middle-pinch menus
# (hands/HandAim.kt).
add_library(vrcode-hand-aim SHARED hand_aim.cpp)
target_compile_definitions(vrcode-hand-aim PRIVATE XR_NO_PROTOTYPES)
target_compile_options(vrcode-hand-aim PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vrcode-hand-aim PRIVATE OpenXR::headers log)
