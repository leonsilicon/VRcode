#include <jni.h>
#include <openxr/openxr.h>
#include <android/log.h>

namespace {
struct HandMenu {
  XrHandTrackerEXT tracker = XR_NULL_HANDLE;
  XrSpace space = XR_NULL_HANDLE;
  PFN_xrLocateHandJointsEXT locate = nullptr;
  PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
  PFN_xrDestroySpace destroySpace = nullptr;

  ~HandMenu() {
    if (tracker != XR_NULL_HANDLE) destroyTracker(tracker);
    if (space != XR_NULL_HANDLE) destroySpace(space);
  }
};

template <typename T>
bool load(PFN_xrGetInstanceProcAddr getProc, XrInstance instance, const char* name, T& fn) {
  return XR_SUCCEEDED(getProc(instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&fn))) && fn;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_improve_vrcode_LeftPalmMenu_create(JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procHandle) {
  if (!instanceHandle || !sessionHandle || !procHandle) return 0;
  auto instance = reinterpret_cast<XrInstance>(instanceHandle);
  auto session = reinterpret_cast<XrSession>(sessionHandle);
  auto getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(procHandle);
  PFN_xrCreateHandTrackerEXT createTracker = nullptr;
  PFN_xrCreateReferenceSpace createSpace = nullptr;
  auto* menu = new HandMenu();
  if (!load(getProc, instance, "xrCreateHandTrackerEXT", createTracker) ||
      !load(getProc, instance, "xrLocateHandJointsEXT", menu->locate) ||
      !load(getProc, instance, "xrDestroyHandTrackerEXT", menu->destroyTracker) ||
      !load(getProc, instance, "xrCreateReferenceSpace", createSpace) ||
      !load(getProc, instance, "xrDestroySpace", menu->destroySpace)) {
    delete menu;
    return 0;
  }
  XrHandTrackerCreateInfoEXT trackerInfo{};
  trackerInfo.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
  trackerInfo.hand = XR_HAND_LEFT_EXT;
  trackerInfo.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
  XrReferenceSpaceCreateInfo spaceInfo{};
  spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
  spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
  spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
  if (XR_FAILED(createTracker(session, &trackerInfo, &menu->tracker)) ||
      XR_FAILED(createSpace(session, &spaceInfo, &menu->space))) {
    __android_log_print(ANDROID_LOG_WARN, "VrcodeMenu", "Palm tracker unavailable; SDK menu button remains available");
    delete menu;
    return 0;
  }
  __android_log_print(ANDROID_LOG_INFO, "VrcodeMenu", "Left palm menu tracker ready");
  return reinterpret_cast<jlong>(menu);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_improve_vrcode_LeftPalmMenu_poll(JNIEnv*, jobject, jlong handle, jlong time) {
  auto* menu = reinterpret_cast<HandMenu*>(handle);
  if (!menu || time <= 0) return JNI_FALSE;
  XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT]{};
  XrHandTrackingAimStateFB aim{};
  aim.type = XR_TYPE_HAND_TRACKING_AIM_STATE_FB;
  XrHandJointLocationsEXT locations{};
  locations.type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
  locations.next = &aim;
  locations.jointCount = XR_HAND_JOINT_COUNT_EXT;
  locations.jointLocations = joints;
  XrHandJointsLocateInfoEXT info{};
  info.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
  info.baseSpace = menu->space;
  info.time = time;
  if (XR_FAILED(menu->locate(menu->tracker, &info, &locations)) || !locations.isActive) return JNI_FALSE;
  // MENU_PRESSED is already runtime-qualified. SYSTEM_GESTURE may be set at the same time;
  // do not reject it, and never substitute an ordinary index pinch for the menu signal.
  return (aim.status & XR_HAND_TRACKING_AIM_MENU_PRESSED_BIT_FB) != 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_improve_vrcode_LeftPalmMenu_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<HandMenu*>(handle);
}
