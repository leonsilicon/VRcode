// One hand's XR_EXT_hand_tracking joints plus Meta's XR_FB_hand_tracking_aim state, for the
// palm-facing middle-pinch menus (hands/HandAim.kt, hands/HandMenuGesture.kt). Each hand gets its
// own tracker, separate from the right-click's and the voice fist's, so none depends on another.
//
// The pinch itself is recognized by Meta's runtime (MIDDLE_PINCHING / INDEX_PINCHING and the
// per-finger pinch strengths); this bridge only copies those values out. Palm orientation is the
// runtime's palm joint pose, whose +Y axis the OpenXR spec defines as pointing out of the back of
// the hand, so no per-hand mirroring is needed. Joints are located in VIEW space: the head is the
// origin and -Z is forward (OpenXR is right-handed), so "palm toward the user" needs no head pose.
#include <jni.h>
#include <openxr/openxr.h>
#include <android/log.h>

namespace {
struct HandAim {
  XrHandTrackerEXT tracker = XR_NULL_HANDLE;
  XrSpace space = XR_NULL_HANDLE;
  PFN_xrLocateHandJointsEXT locate = nullptr;
  PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
  PFN_xrDestroySpace destroySpace = nullptr;

  ~HandAim() {
    if (tracker != XR_NULL_HANDLE) destroyTracker(tracker);
    if (space != XR_NULL_HANDLE) destroySpace(space);
  }
};

template <typename T>
bool load(PFN_xrGetInstanceProcAddr getProc, XrInstance instance, const char* name, T& fn) {
  return XR_SUCCEEDED(getProc(instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&fn))) && fn;
}

constexpr XrSpaceLocationFlags kPositionValid = XR_SPACE_LOCATION_POSITION_VALID_BIT;
constexpr XrSpaceLocationFlags kOrientationValid = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;

// Output layout (mirrored in HandAim.kt).
constexpr int kJoints = XR_HAND_JOINT_COUNT_EXT;
constexpr int kRadiiOffset = kJoints * 3;
constexpr int kPalmOffset = kRadiiOffset + kJoints;  // x, y, z, w
constexpr int kStrengthOffset = kPalmOffset + 4;     // index, middle
constexpr int kOutSize = kStrengthOffset + 2;

// Flags returned by poll (mirrored in HandAim.kt).
constexpr jint kAimValid = 1;
constexpr jint kIndexPinching = 2;
constexpr jint kMiddlePinching = 4;
constexpr jint kSystemGesture = 8;
constexpr jint kMenuPressed = 16;
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_improve_vrcode_hands_HandAim_create(JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procHandle, jboolean left) {
  if (!instanceHandle || !sessionHandle || !procHandle) return 0;
  auto instance = reinterpret_cast<XrInstance>(instanceHandle);
  auto session = reinterpret_cast<XrSession>(sessionHandle);
  auto getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(procHandle);
  PFN_xrCreateHandTrackerEXT createTracker = nullptr;
  PFN_xrCreateReferenceSpace createSpace = nullptr;
  auto* hand = new HandAim();
  if (!load(getProc, instance, "xrCreateHandTrackerEXT", createTracker) ||
      !load(getProc, instance, "xrLocateHandJointsEXT", hand->locate) ||
      !load(getProc, instance, "xrDestroyHandTrackerEXT", hand->destroyTracker) ||
      !load(getProc, instance, "xrCreateReferenceSpace", createSpace) ||
      !load(getProc, instance, "xrDestroySpace", hand->destroySpace)) {
    delete hand;
    return 0;
  }
  const char* side = left ? "left" : "right";
  XrHandTrackerCreateInfoEXT trackerInfo{};
  trackerInfo.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
  trackerInfo.hand = left ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT;
  trackerInfo.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
  XrReferenceSpaceCreateInfo spaceInfo{};
  spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
  spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
  spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
  if (XR_FAILED(createTracker(session, &trackerInfo, &hand->tracker)) ||
      XR_FAILED(createSpace(session, &spaceInfo, &hand->space))) {
    __android_log_print(ANDROID_LOG_WARN, "vrcode", "%s hand aim tracker unavailable; its palm menu is off", side);
    delete hand;
    return 0;
  }
  __android_log_print(ANDROID_LOG_INFO, "vrcode", "%s hand aim tracker ready (palm menu)", side);
  return reinterpret_cast<jlong>(hand);
}

// Fills [out] (see the layout above) in VIEW space and returns the flags above, or -1 when the
// hand is not tracked, a joint position is invalid or the palm orientation is invalid.
extern "C" JNIEXPORT jint JNICALL
Java_dev_improve_vrcode_hands_HandAim_poll(JNIEnv* env, jobject, jlong handle, jlong time, jfloatArray out) {
  auto* hand = reinterpret_cast<HandAim*>(handle);
  if (!hand || time <= 0 || env->GetArrayLength(out) < kOutSize) return -1;
  XrHandJointLocationEXT joints[kJoints]{};
  XrHandTrackingAimStateFB aim{};
  aim.type = XR_TYPE_HAND_TRACKING_AIM_STATE_FB;
  XrHandJointLocationsEXT locations{};
  locations.type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
  locations.next = &aim;
  locations.jointCount = kJoints;
  locations.jointLocations = joints;
  XrHandJointsLocateInfoEXT info{};
  info.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
  info.baseSpace = hand->space;
  info.time = time;
  if (XR_FAILED(hand->locate(hand->tracker, &info, &locations)) || !locations.isActive) return -1;
  if ((joints[XR_HAND_JOINT_PALM_EXT].locationFlags & kOrientationValid) == 0) return -1;
  jfloat values[kOutSize];
  for (int i = 0; i < kJoints; i++) {
    if ((joints[i].locationFlags & kPositionValid) == 0) return -1;
    values[i * 3] = joints[i].pose.position.x;
    values[i * 3 + 1] = joints[i].pose.position.y;
    values[i * 3 + 2] = joints[i].pose.position.z;
    values[kRadiiOffset + i] = joints[i].radius;
  }
  const XrQuaternionf& palm = joints[XR_HAND_JOINT_PALM_EXT].pose.orientation;
  values[kPalmOffset] = palm.x;
  values[kPalmOffset + 1] = palm.y;
  values[kPalmOffset + 2] = palm.z;
  values[kPalmOffset + 3] = palm.w;
  values[kStrengthOffset] = aim.pinchStrengthIndex;
  values[kStrengthOffset + 1] = aim.pinchStrengthMiddle;
  env->SetFloatArrayRegion(out, 0, kOutSize, values);
  jint flags = 0;
  if (aim.status & XR_HAND_TRACKING_AIM_VALID_BIT_FB) flags |= kAimValid;
  if (aim.status & XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB) flags |= kIndexPinching;
  if (aim.status & XR_HAND_TRACKING_AIM_MIDDLE_PINCHING_BIT_FB) flags |= kMiddlePinching;
  if (aim.status & XR_HAND_TRACKING_AIM_SYSTEM_GESTURE_BIT_FB) flags |= kSystemGesture;
  if (aim.status & XR_HAND_TRACKING_AIM_MENU_PRESSED_BIT_FB) flags |= kMenuPressed;
  return flags;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_improve_vrcode_hands_HandAim_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<HandAim*>(handle);
}
