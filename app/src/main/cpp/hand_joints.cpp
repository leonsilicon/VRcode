// Left-hand joint positions for vrcode's strict fist check (hands/FistShape.kt) and for holding the
// voice prop in the fist (hands/FistGrip.kt). Its own XR_EXT_hand_tracking tracker, separate from
// the palm menu's, so neither depends on the other. It also reports the head (VIEW) pose in the same
// space, which lets the app map these LOCAL-space joints into the Spatial SDK scene.
#include <jni.h>
#include <openxr/openxr.h>
#include <android/log.h>

namespace {
struct HandJoints {
  XrHandTrackerEXT tracker = XR_NULL_HANDLE;
  XrSpace space = XR_NULL_HANDLE;
  XrSpace view = XR_NULL_HANDLE;
  PFN_xrLocateHandJointsEXT locate = nullptr;
  PFN_xrLocateSpace locateSpace = nullptr;
  PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
  PFN_xrDestroySpace destroySpace = nullptr;

  ~HandJoints() {
    if (tracker != XR_NULL_HANDLE) destroyTracker(tracker);
    if (space != XR_NULL_HANDLE) destroySpace(space);
    if (view != XR_NULL_HANDLE) destroySpace(view);
  }
};

template <typename T>
bool load(PFN_xrGetInstanceProcAddr getProc, XrInstance instance, const char* name, T& fn) {
  return XR_SUCCEEDED(getProc(instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&fn))) && fn;
}

constexpr XrSpaceLocationFlags kPositionValid = XR_SPACE_LOCATION_POSITION_VALID_BIT;
constexpr XrSpaceLocationFlags kPoseValid = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
constexpr int kJointsValid = 1;
constexpr int kViewValid = 2;
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_improve_vrcode_hands_LeftHandJoints_create(JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procHandle) {
  if (!instanceHandle || !sessionHandle || !procHandle) return 0;
  auto instance = reinterpret_cast<XrInstance>(instanceHandle);
  auto session = reinterpret_cast<XrSession>(sessionHandle);
  auto getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(procHandle);
  PFN_xrCreateHandTrackerEXT createTracker = nullptr;
  PFN_xrCreateReferenceSpace createSpace = nullptr;
  auto* hand = new HandJoints();
  if (!load(getProc, instance, "xrCreateHandTrackerEXT", createTracker) ||
      !load(getProc, instance, "xrLocateHandJointsEXT", hand->locate) ||
      !load(getProc, instance, "xrDestroyHandTrackerEXT", hand->destroyTracker) ||
      !load(getProc, instance, "xrCreateReferenceSpace", createSpace) ||
      !load(getProc, instance, "xrLocateSpace", hand->locateSpace) ||
      !load(getProc, instance, "xrDestroySpace", hand->destroySpace)) {
    delete hand;
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
  XrReferenceSpaceCreateInfo viewInfo = spaceInfo;
  viewInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
  if (XR_FAILED(createTracker(session, &trackerInfo, &hand->tracker)) ||
      XR_FAILED(createSpace(session, &spaceInfo, &hand->space))) {
    __android_log_print(ANDROID_LOG_WARN, "vrcode", "Left hand joints unavailable; fist falls back to the SDK's isFist");
    delete hand;
    return 0;
  }
  if (XR_FAILED(createSpace(session, &viewInfo, &hand->view))) {
    // Joints still drive the fist check; the voice prop falls back to the hand's aim pose.
    __android_log_print(ANDROID_LOG_WARN, "vrcode", "View space unavailable; the voice prop cannot follow the fist's joints");
    hand->view = XR_NULL_HANDLE;
  }
  __android_log_print(ANDROID_LOG_INFO, "vrcode", "Left hand joint tracker ready");
  return reinterpret_cast<jlong>(hand);
}

// Fills [joints] with XR_HAND_JOINT_COUNT_EXT x (x, y, z) positions in metres and [view] with the
// head pose (px, py, pz, qw, qx, qy, qz), both in the tracker's LOCAL space at [time]. Returns
// kJointsValid | kViewValid for what was written; an invalid part leaves its array untouched.
extern "C" JNIEXPORT jint JNICALL
Java_dev_improve_vrcode_hands_LeftHandJoints_poll(JNIEnv* env, jobject, jlong handle, jlong time, jfloatArray out, jfloatArray viewOut) {
  auto* hand = reinterpret_cast<HandJoints*>(handle);
  if (!hand || time <= 0 || env->GetArrayLength(out) < XR_HAND_JOINT_COUNT_EXT * 3 || env->GetArrayLength(viewOut) < 7) return 0;
  int valid = 0;
  XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT]{};
  XrHandJointLocationsEXT locations{};
  locations.type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
  locations.jointCount = XR_HAND_JOINT_COUNT_EXT;
  locations.jointLocations = joints;
  XrHandJointsLocateInfoEXT info{};
  info.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
  info.baseSpace = hand->space;
  info.time = time;
  if (XR_SUCCEEDED(hand->locate(hand->tracker, &info, &locations)) && locations.isActive) {
    bool all = true;
    for (int i = 0; i < XR_HAND_JOINT_COUNT_EXT && all; i++) all = (joints[i].locationFlags & kPositionValid) != 0;
    if (all) {
      jfloat positions[XR_HAND_JOINT_COUNT_EXT * 3];
      for (int i = 0; i < XR_HAND_JOINT_COUNT_EXT; i++) {
        positions[i * 3] = joints[i].pose.position.x;
        positions[i * 3 + 1] = joints[i].pose.position.y;
        positions[i * 3 + 2] = joints[i].pose.position.z;
      }
      env->SetFloatArrayRegion(out, 0, XR_HAND_JOINT_COUNT_EXT * 3, positions);
      valid |= kJointsValid;
    }
  }
  if (hand->view != XR_NULL_HANDLE) {
    XrSpaceLocation head{};
    head.type = XR_TYPE_SPACE_LOCATION;
    if (XR_SUCCEEDED(hand->locateSpace(hand->view, hand->space, time, &head)) && (head.locationFlags & kPoseValid) == kPoseValid) {
      const XrPosef& p = head.pose;
      jfloat pose[7] = {p.position.x, p.position.y, p.position.z, p.orientation.w, p.orientation.x, p.orientation.y, p.orientation.z};
      env->SetFloatArrayRegion(viewOut, 0, 7, pose);
      valid |= kViewValid;
    }
  }
  return valid;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_improve_vrcode_hands_LeftHandJoints_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<HandJoints*>(handle);
}
