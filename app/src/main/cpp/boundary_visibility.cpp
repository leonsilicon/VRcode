#include <jni.h>
#include <openxr/openxr.h>

// Use the Spatial SDK's instance and session; it still owns the OpenXR frame/event loop.
extern "C" JNIEXPORT jint JNICALL
Java_dev_improve_vrcode_BoundaryVisibility_requestNative(
    JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procHandle, jboolean suppress) {
  if (!instanceHandle || !sessionHandle || !procHandle) return XR_ERROR_HANDLE_INVALID;
  auto instance = reinterpret_cast<XrInstance>(instanceHandle);
  auto session = reinterpret_cast<XrSession>(sessionHandle);
  auto getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(procHandle);
  PFN_xrRequestBoundaryVisibilityMETA request = nullptr;
  const auto loaded = getProc(instance, "xrRequestBoundaryVisibilityMETA",
                             reinterpret_cast<PFN_xrVoidFunction*>(&request));
  if (XR_FAILED(loaded)) return loaded;
  if (!request) return XR_ERROR_FUNCTION_UNSUPPORTED;
  return request(session, suppress ? XR_BOUNDARY_VISIBILITY_SUPPRESSED_META
                                   : XR_BOUNDARY_VISIBILITY_NOT_SUPPRESSED_META);
}
