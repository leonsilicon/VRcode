// Meta's OpenXR runtime recognizes the pinch. This bridge only reads its discrete signal;
// it does not classify joints, measure fingertip distances, or choose pinch thresholds.
#include <jni.h>
#include <openxr/openxr.h>
#include <android/log.h>

namespace {
struct RightHandAim {
  XrHandTrackerEXT tracker = XR_NULL_HANDLE;
  XrSpace space = XR_NULL_HANDLE;
  PFN_xrLocateHandJointsEXT locate = nullptr;
  PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
  PFN_xrDestroySpace destroySpace = nullptr;

  ~RightHandAim() {
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
Java_dev_improve_vrcode_hands_RightMiddlePinch_create(JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procHandle) {
  if (!instanceHandle || !sessionHandle || !procHandle) return 0;
  auto instance = reinterpret_cast<XrInstance>(instanceHandle);
  auto session = reinterpret_cast<XrSession>(sessionHandle);
  auto getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(procHandle);
  PFN_xrCreateHandTrackerEXT createTracker = nullptr;
  PFN_xrCreateReferenceSpace createSpace = nullptr;
  auto* hand = new RightHandAim();
  if (!load(getProc, instance, "xrCreateHandTrackerEXT", createTracker) ||
      !load(getProc, instance, "xrLocateHandJointsEXT", hand->locate) ||
      !load(getProc, instance, "xrDestroyHandTrackerEXT", hand->destroyTracker) ||
      !load(getProc, instance, "xrCreateReferenceSpace", createSpace) ||
      !load(getProc, instance, "xrDestroySpace", hand->destroySpace)) {
    delete hand;
    return 0;
  }
  XrHandTrackerCreateInfoEXT trackerInfo{};
  trackerInfo.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
  trackerInfo.hand = XR_HAND_RIGHT_EXT;
  trackerInfo.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
  XrReferenceSpaceCreateInfo spaceInfo{};
  spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
  spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
  spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
  if (XR_FAILED(createTracker(session, &trackerInfo, &hand->tracker)) ||
      XR_FAILED(createSpace(session, &spaceInfo, &hand->space))) {
    __android_log_print(ANDROID_LOG_WARN, "vrcode", "Right middle pinch unavailable");
    delete hand;
    return 0;
  }
  __android_log_print(ANDROID_LOG_INFO, "vrcode", "Right middle pinch: Meta OpenXR aim tracker ready");
  return reinterpret_cast<jlong>(hand);
}

// -1 unavailable, 0 released, 1 middle pinch, 2 blocked (index pinch or system gesture).
extern "C" JNIEXPORT jint JNICALL
Java_dev_improve_vrcode_hands_RightMiddlePinch_poll(JNIEnv*, jobject, jlong handle, jlong time) {
  auto* hand = reinterpret_cast<RightHandAim*>(handle);
  if (!hand || time <= 0) return -1;
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
  info.baseSpace = hand->space;
  info.time = time;
  if (XR_FAILED(hand->locate(hand->tracker, &info, &locations)) || !locations.isActive ||
      (aim.status & XR_HAND_TRACKING_AIM_VALID_BIT_FB) == 0) return -1;
  if ((aim.status & (XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB |
                     XR_HAND_TRACKING_AIM_SYSTEM_GESTURE_BIT_FB)) != 0) return 2;
  return (aim.status & XR_HAND_TRACKING_AIM_MIDDLE_PINCHING_BIT_FB) != 0 ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_improve_vrcode_hands_RightMiddlePinch_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<RightHandAim*>(handle);
}
