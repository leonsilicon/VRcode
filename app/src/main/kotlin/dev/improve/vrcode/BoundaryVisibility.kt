package dev.improve.vrcode

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.Scene

/** Requests contextual boundaryless passthrough, leaving the final decision to Horizon OS. */
@OptIn(SpatialSDKExperimentalAPI::class)
class BoundaryVisibility {
  private val requests = BoundaryRequest()
  private var lastReport: Pair<Boolean, Int>? = null

  fun update(scene: Scene, passthrough: Boolean) {
    val result = requests.update(scene.getOpenXrSessionHandle(), passthrough, SystemClock.uptimeMillis()) { suppress ->
      requestNative(scene.getOpenXrInstanceHandle(), scene.getOpenXrSessionHandle(),
        scene.getOpenXrGetInstanceProcAddrHandle(), suppress)
    } ?: return
    val report = passthrough to result
    if (lastReport == report) return
    lastReport = report
    if (result == 0) {
      Log.i(TAG, "Boundary ${if (passthrough) "suppression" else "restoration"} request accepted")
    } else {
      Log.w(TAG, "Boundary request not accepted: passthrough=$passthrough, XrResult=$result")
    }
  }

  fun reset() {
    requests.reset()
    lastReport = null
  }

  private external fun requestNative(instance: Long, session: Long, getProcAddr: Long, suppress: Boolean): Int

  companion object {
    const val EXTENSION = "XR_META_boundary_visibility"
    private const val TAG = "VrcodeBoundary"
    init { System.loadLibrary("vrcode-boundary") }
  }
}
