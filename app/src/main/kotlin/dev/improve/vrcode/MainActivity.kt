package dev.improve.vrcode

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.voice.VoiceFeature
import dev.improve.vrcode.dock.DockFeature
import dev.improve.vrcode.hands.RightClickFeature
import dev.improve.vrcode.cmux.CmuxFeature
import dev.improve.vrcode.cmux.TerminalGridSizing
import dev.improve.vrcode.pie.HandMenusFeature
import dev.improve.vrcode.connection.ConnectionController
import dev.improve.vrcode.connection.ConnectionPanel
import dev.improve.xmux.client.BridgeAdvert
import dev.improve.xmux.client.BridgeTarget
import dev.improve.xmux.client.ConnectionState
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.client.SessionEvent
import dev.improve.xmux.client.XmuxClient
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.connect.panel.BridgeChoice
import dev.improve.xmux.connect.panel.ConnectStatus
import dev.improve.xmux.connect.panel.parseAddress
import dev.improve.xmux.spatial.SpatialConfig
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.vrcode.dock.DockGesture
import dev.improve.vrcode.dock.DockRecall
import dev.improve.vrcode.windows.EdgeResize
import dev.improve.vrcode.windows.KeyBar
import dev.improve.vrcode.windows.LeftHandMove
import dev.improve.vrcode.windows.TerminalWindows
import dev.improve.vrcode.windows.WindowShadows
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppSystemActivity() {
  private val preferences by lazy { getSharedPreferences("vrcode", MODE_PRIVATE) }
  private val store by lazy { SettingsStore(preferences) }
  private var settings by mutableStateOf(AppSettings())
  /** Mirrors Settings into VRcode Mac; a change made there arrives exactly like one made in the panel. */
  private val settingsLink by lazy {
    dev.improve.vrcode.speech.SettingsLink(scope, android.os.Build.MODEL ?: "Quest", { settings },
      onValues = { applySettings(SettingsSync.apply(settings, it)) },
      onAction = { if (it == SettingsSync.ARRANGE_WINDOWS && sceneReady) arrangeWindows() })
  }
  // Only the right hand (or the A button) presses inside a terminal window; the left hand moves windows (LeftHandMove).
  private val xmux by lazy { XmuxSpatial(this, SpatialConfig(enablePassthrough = false, resizeGrids = true, edgeGrabHandles = false,
    windowClickButtons = ButtonBits.ButtonTriggerR or ButtonBits.ButtonA)) }
  private val client by lazy { XmuxClient(this) }
  private val failures = FailureLog(TAG)
  /** A failed background job must not take down the headset app with it. */
  private val scope = MainScope() + CoroutineExceptionHandler { _, error -> failures.report("background job", error) }
  private val boundary = BoundaryVisibility()
  private val menuButton = MenuButtonGate()
  private val terminalAppearance = TerminalAppearanceController()
  /** A drop shadow behind each window, larger under the focused one (see windows/WindowShadows.kt). */
  private val windowShadows by lazy { WindowShadows(this, xmux) { focus.focusedScreen == it } }
  private val terminalPointer by lazy { TerminalPointerInput(xmux) }
  private val rightHandPointer by lazy { RightHandPointer(this, xmux, ::updateHeaderHover) }
  /** Title-bar drags follow their holding hand once per scene frame. */
  private val titleBarDepth by lazy {
    TitleBarDepthDrag(this, xmux) { screen, pose -> if (!terminalWindows.isFullscreen(screen)) terminalWindows.move(screen, pose) }
  }
  /** Dragging a window's edge changes its columns or rows; its corners scale it (windows/EdgeResize.kt). */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val edgeResize by lazy {
    EdgeResize(this, xmux, predictedTimeNs = { spatial.getFrameTiming().predictedDisplayTimeNs },
      resizable = { w -> w.screen?.let { !terminalWindows.isFullscreen(it) } == true },
      onStart = { window -> terminalPointer.cancel(); window.screen?.let(terminalWindows::stopGlide); xmux.focus(window) })
  }
  /** The left hand's pinch only moves windows, from anywhere on them; it never clicks or resizes (windows/LeftHandMove.kt). */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val leftHandMove by lazy {
    LeftHandMove(this, xmux, predictedTimeNs = { spatial.getFrameTiming().predictedDisplayTimeNs },
      movable = { w -> w.screen?.let { !terminalWindows.isFullscreen(it) } == true },
      move = { screen, pose -> if (!terminalWindows.isFullscreen(screen)) terminalWindows.move(screen, pose) },
      onStart = { window -> terminalPointer.cancel(); dockRecall.cancel(); xmux.focus(window) },
      cursorSize = { settings.pointerCursorSize })
  }
  /** Meta's laser past every panel, where the SDK draws none, while no mic is live (FreeLaser.kt). */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val freeLasers by lazy {
    FreeLasers(this, predictedTimeNs = { spatial.getFrameTiming().predictedDisplayTimeNs }) { origin, direction ->
      dock.hitDistance(origin, direction)
    }
  }
  private val cmuxControls: CmuxFeature by lazy { CmuxFeature(this, xmux, scope, terminalWindows) { settings.defaultTerminalSize } }
  /** Resizing a terminal window sets its terminals' columns and rows in cmux (cmux/TerminalGridSizing.kt). */
  private val gridSizing by lazy { TerminalGridSizing(this, xmux, cmuxControls, scope) }
  /** Where each terminal window is, its mode, minimized and fullscreen: kept in cmux, drawn from it (windows/TerminalWindows.kt). */
  private val terminalWindows by lazy {
    TerminalWindows(xmux, scope, android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown",
      head = ::headPose, defaultMode = { settings.defaultWindowMode }, distanceM = { settings.windowDistanceM },
      frameTimeNs = ::frameTimeNs)
  }
  /** Keeps a terminal window focused whenever one is open, so voice and keys always have a target. */
  private val focus by lazy { FocusKeeper(xmux) }
  /** Esc, Tab, arrows, Enter and more under the focused window (windows/KeyBar.kt). */
  private val keyBar by lazy { KeyBar(xmux) { handMenu.toggleKeyboard() } }
  /** Voice mode: a fist dictates, opening it types into the focused pane (see voice/VoiceFeature.kt). */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val voice: VoiceFeature by lazy {
    VoiceFeature(this, xmux, scope, focus,
      overlayHitDistance = { origin, direction ->
        listOfNotNull(dock.hitDistance(origin, direction), keyBar.hitDistance(origin, direction)).minOrNull()
      },
      dockPose = { dock.currentPose() }, terminalInfoHeight = cmuxControls::infoHeight, terminalBarHeight = keyBar::occupiedBelow) {
      spatial.getFrameTiming().predictedDisplayTimeNs
    }
  }
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val rightClick: RightClickFeature by lazy { RightClickFeature(this, xmux,
    predictedTimeNs = { spatial.getFrameTiming().predictedDisplayTimeNs },
    onContextMenu = { hit -> terminalPointer.cancel(); cmuxControls.open(hit) }) }
  /** Palm toward the user + middle pinch: left = new/close terminal, Codex/Claude and bring forward, right = keyboard and keys. */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val handMenu: HandMenusFeature by lazy {
    HandMenusFeature(this, xmux, voice, cmuxControls, bringForward = ::bringFocusedForward,
      togglePath = { terminalPointer.cancel(); dockRecall.cancel(); terminalWindows.togglePath() },
      pathCleared = { terminalWindows.pathCleared },
      quickInputs = { settings.quickInputs }) { spatial.getFrameTiming().predictedDisplayTimeNs }
  }
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val dock: DockFeature by lazy {
    DockFeature(xmux, scene = { scene }, predictedTimeNs = { spatial.getFrameTiming().predictedDisplayTimeNs },
      onWindow = ::showDockWindow, onKeyboard = { handMenu.toggleKeyboard() },
      onArrange = ::arrangeWindows, onSettings = { openSettings() }, onConnection = { openSettings(SettingsPage.Connection) },
      panelLayer = { entity ->
        (systemManager.tryFindSystem<SceneObjectSystem>()?.getSceneObject(entity)?.getNow(null) as? PanelSceneObject)?.getLayer()
      })
  }
  /** Touching a window in the dock glides it to just above the dock; Bring forward uses the same glide. */
  private val dockRecall = DockRecall()
  private val linkController: ConnectionController by lazy {
    ConnectionController(this, scope,
      onConnected = { bridgePort, speechPort -> connectBridge("127.0.0.1:$bridgePort", "127.0.0.1", speechPort) },
      onDisconnected = { disconnectBridge(); voice.disconnectSpeech() })
  }
  private var status by mutableStateOf<ConnectStatus>(ConnectStatus.Idle)
  private val bridges = mutableStateListOf<BridgeChoice>()
  private val adverts = mutableMapOf<String, BridgeAdvert>()
  private var session: XmuxSession? = null
  private var sessionJob: Job? = null
  /** The link to the Mac lags (slow or overdue ping replies): every window's header shows a spinner. */
  private val linkLag = LinkLag()
  private var linkLagging by mutableStateOf(false)
  private var lagJob: Job? = null
  private var linkAttention: Job? = null
  private var discovery: Job? = null
  private var settingsPage by mutableStateOf(SettingsPage.Workspace)
  private var customizationTab by mutableStateOf(CustomizationTab.Pointer)
  private var settingsPanel: Entity? = null
  private var settingsOpen = false
  private var placeSettings = false
  private var sceneReady = false
  private var setupConnection = false
  private var resumed = false
  private val windowOverrides = mutableStateMapOf<ScreenId, TerminalWindowSettings>()
  private var windowSettingsScreen by mutableStateOf<ScreenId?>(null)
  private val windowHeaderHover = mutableMapOf<ScreenId, TerminalHeaderHover>()

  override fun onCreate(savedInstanceState: Bundle?) {
    settings = store.load()
    super.onCreate(savedInstanceState)
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }
  }

  override fun registerFeatures(): List<SpatialFeature> = xmux.registerFeatures()
  override fun registerPanels(): List<PanelRegistration> = xmux.registerPanels()
  override fun registerRequiredOpenXRExtensions(): List<String> =
    (super.registerRequiredOpenXRExtensions() + listOf("XR_EXT_hand_tracking", "XR_FB_hand_tracking_aim", BoundaryVisibility.EXTENSION)).distinct()

  override fun onSceneReady() {
    super.onSceneReady()
    xmux.onSceneReady()
    // The dock comes first: it is the visible sign that VRcode loaded, even if a later feature fails.
    guarded("dock setup") { dock.onSceneReady() }
    guarded("voice setup") { voice.onSceneReady() }
    guarded("hand menu setup") { handMenu.onSceneReady() }
    guarded("grid sizing") { gridSizing.install() }
    sceneReady = true
    applySettings(settings)
    Log.i(TAG, "New terminal default: ${settings.defaultTerminalSize.cols}x${settings.defaultTerminalSize.rows}")
    // Nothing opens at launch: the Connection page appears only for a problem the user must fix
    // (below) or a pairing code, and CmuxFeature opens a terminal if the session has none.
    startDiscovery()
    // Also imports a key provisioned by `quest-link pair` and connects with it, without a tap.
    val address = intent?.getStringExtra("address")
    linkController.start(autoConnect = address == null && settings.autoConnect)
    if (address != null) connect(address)
    // Losing the Mac unmounts every terminal window. Show why (for example "Make sure the VRcode
    // Mac app is running") once per outage; the page closes itself when the terminals return.
    linkAttention?.cancel()
    linkAttention = scope.launch {
      var shown = false
      linkController.status.collect { status ->
        if (status.attention && !shown && sceneReady && !settingsOpen) { setupConnection = true; openSettings(SettingsPage.Connection) }
        shown = status.attention
      }
    }
  }

  override fun onSceneTick() {
    super.onSceneTick()
    guarded("xmux tick") { xmux.onSceneTick() }
    if (!sceneReady) return
    // The dock and settings placement run first and on their own: they show the user VRcode loaded
    // and let them recover a lost panel, even while another feature is failing.
    guarded("dock tick") { tickDock() }
    guarded("settings placement") {
      if (placeSettings) headPose()?.let { head ->
        // High enough that its bottom row stays clear of the dock, which is closer and always shown.
        settingsPanel?.setComponent(Transform(panelPose(head, 1.05f, -0.12f)))
        placeSettings = false
      }
    }
    guarded("feature tick") { tickFeatures() }
  }

  private inline fun guarded(site: String, block: () -> Unit) {
    try {
      block()
    } catch (error: Exception) {
      failures.report(site, error)
    }
  }

  /**
   * Each hand's pointer ray (left, right; null when that hand is not tracked), for the dock's bulge
   * and for pulling its bar up along the ray, with its trigger for controllers (tracked hands' pinches
   * come from their joints). Touching the raised dock reaches it through the Interaction SDK like any panel.
   */
  private fun dockPointers(): List<DockGesture.Pointer?> {
    val body = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody() ?: return listOf(null, null)
    return listOf(body.leftHand to ButtonBits.ButtonTriggerL, body.rightHand to ButtonBits.ButtonTriggerR).map { (hand, trigger) ->
      val controller = hand.tryGetComponent<Controller>()?.takeIf { it.isActive } ?: return@map null
      val pose = hand.tryGetComponent<Transform>()?.transform ?: return@map null
      DockGesture.Pointer(pose.t, pose.forward(), pressed = controller.buttonState and trigger != 0)
    }
  }

  private fun tickDock() {
    dock.onSceneTick(headPose(), visible = resumed,
      connected = session?.state?.value == ConnectionState.Online,
      keyboardShown = runCatching { handMenu.keyboardShown }.getOrDefault(false),
      pointers = dockPointers())
    dockRecall.tick(frameTimeNs() / 1_000_000L)?.let { (screen, pose) ->
      val window = xmux.windows.firstOrNull { it.screen?.value == screen } ?: return@let
      terminalWindows.move(window.screen ?: return@let, pose)
    }
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun tickFeatures() {
    if (windowSettingsScreen != null && xmux.windows.none { it.screen == windowSettingsScreen && it.visible }) closeWindowSettings()
    terminalAppearance.update(xmux.windows, focus.focusedScreen) { effectiveSettings(it.screen) }
    windowShadows.tick()
    if (resumed) boundary.update(scene, settings.passthrough)
    if (resumed) {
      // Only a real controller's menu button toggles Settings. With hand tracking the same press is
      // the left palm-facing index pinch, which opens the quick inputs ring (HandMenusFeature).
      val sdkDown = Query.where { has(Controller.id, AvatarAttachment.id) }.filter { isLocal() }.eval().any { entity ->
        val side = entity.getComponent<AvatarAttachment>().type
        val controller = entity.getComponent<Controller>()
        (side == "left_hand" || side == "left_controller") && controller.type != ControllerType.HAND &&
          controller.isDown(ButtonBits.ButtonMenu)
      }
      if (menuButton.update(sdkDown, SystemClock.uptimeMillis())) toggleSettings()
    }
    terminalWindows.tick(resumed)
    titleBarDepth.tick(headPose(), allowed = resumed && !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen && !handMenu.busy)
    // Apply visibility before edge pointers draw, so a live mic hides that hand's edge dot too.
    val micHand = voice.micHand
    showLasers(left = micHand != VoiceFeature.VoiceHand.Left, right = micHand != VoiceFeature.VoiceHand.Right)
    freeLasers.tick(shown = resumed && micHand == null)
    edgeResize.tick(allowed = resumed && !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen && !handMenu.busy) { origin, direction ->
      dock.blocksRay(origin, direction) || keyBar.blocksRay(origin, direction)
    }
    leftHandMove.tick(headPose(), allowed = resumed && !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen &&
      !handMenu.leftBusy && micHand != VoiceFeature.VoiceHand.Left) { origin, direction ->
      dock.blocksRay(origin, direction) || keyBar.blocksRay(origin, direction)
    }
    windowHeaderHover.keys.retainAll(xmux.windows.mapNotNull { it.screen }.toSet())
    focus.tick(headPose())
    cmuxControls.tick(visible = resumed)
    // After the moves and focus above, so the bar sits under the focused window where it is drawn this frame.
    keyBar.tick(focus.focused.takeIf { resumed && windowSettingsScreen == null }, headPose())
    val right = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()?.rightHand
    val rightRay = right?.takeIf { it.tryGetComponent<Controller>()?.isActive == true }?.tryGetComponent<Transform>()?.transform
    val overDock = rightRay?.let { dock.blocksRay(it.t, it.forward()) || keyBar.blocksRay(it.t, it.forward()) } == true
    terminalPointer.update(allowInput = resumed && !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen,
      drag = settings.pinchDrag)
    if (rightHandPointer.update(visible = resumed && !settingsOpen && !cmuxControls.isOpen && !overDock &&
        micHand != VoiceFeature.VoiceHand.Right,
        style = settings.pointerCursor, size = settings.pointerCursorSize)) terminalPointer.cancel()
    voice.onSceneTick(allowGestures = !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen,
      previewMic = customizationTab.mic.takeIf { resumed && settingsOpen && settingsPage == SettingsPage.Customization })
    handMenu.onSceneTick(enabled = resumed && !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen)
    // A palm-facing middle pinch belongs to the pie; the right-click is the palm-away one.
    rightClick.onSceneTick(enabled = resumed && !settingsOpen && windowSettingsScreen == null && !cmuxControls.isOpen && !handMenu.busy && !overDock)
  }

  /**
   * Each hand's Quest dot and laser (drawn by the Interaction SDK's cursor system, which skips a
   * hand whose [Controller.laserEnabled] is off). Only the drawing stops: that hand's ISDK input keeps
   * working. Nothing in the SDK writes the flag, so it is only written here, and only on a change.
   * Hands and controllers only: the eye-gaze input is a Controller too, with its laser off.
   */
  private fun showLasers(left: Boolean, right: Boolean) {
    Query.where { has(Controller.id, AvatarAttachment.id) }.filter { isLocal() }.eval().forEach { entity ->
      val type = entity.getComponent<AvatarAttachment>().type
      val show = when (type) {
        "left_hand", "left_controller" -> left
        "right_hand", "right_controller" -> right
        else -> return@forEach
      }
      val controller = entity.getComponent<Controller>()
      if (controller.laserEnabled == show) return@forEach
      controller.laserEnabled = show
      entity.setComponent(controller)
      Log.i(TAG, "Quest laser ${if (show) "shown" else "hidden"} on $type")
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    // Some runtime versions deliver the app-menu action as an Android menu key instead. From a
    // tracked left hand that is the palm pinch, which belongs to the quick inputs ring.
    if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
      if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !leftIsHand() &&
        menuButton.request(SystemClock.uptimeMillis())) toggleSettings()
      return true
    }
    if (cmuxControls.isOpen) {
      if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
        if (event.action == KeyEvent.ACTION_UP) cmuxControls.hide()
        return true
      }
      return super.dispatchKeyEvent(event)
    }
    if (settingsOpen) {
      if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
        if (event.action == KeyEvent.ACTION_UP) closeSettings()
        return true
      }
      // Let fields/virtual keyboard use keys without typing them into a focused terminal.
      return super.dispatchKeyEvent(event)
    }
    if (windowSettingsScreen != null) {
      if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
        if (event.action == KeyEvent.ACTION_UP) closeWindowSettings()
        return true
      }
      return super.dispatchKeyEvent(event)
    }
    if (event.action == KeyEvent.ACTION_DOWN) terminalPointer.clear()
    return xmux.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (sceneReady) intent.getStringExtra("address")?.let { openSettings(SettingsPage.Connection); connect(it) }
  }

  /** The left hand's SDK input is a tracked hand rather than a controller. */
  private fun leftIsHand(): Boolean {
    val body = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody() ?: return false
    val controller = body.leftHand.tryGetComponent<Controller>() ?: return false
    return controller.isActive && controller.type == ControllerType.HAND
  }

  private fun toggleSettings() {
    if (settingsOpen) closeSettings() else openSettings()
  }

  private fun openSettings(page: SettingsPage = SettingsPage.Workspace) {
    if (!sceneReady) return
    closeWindowSettings()
    cmuxControls.hide()
    settingsPage = page
    if (settingsPanel == null) {
      settingsPanel = xmux.mountPanel(1000, 760) {
        SettingsPanel(settings, settingsPage, status, bridges,
          onPage = { settingsPage = it }, onChange = ::applySettings, onArrange = ::arrangeWindows,
          onConnect = ::connect, onDisconnect = ::disconnect, onClose = ::closeSettings,
          customizationTab = customizationTab, onCustomizationTab = {
            customizationTab = it
            Log.i(TAG, "Settings customization tab: ${it.name}")
          },
          onTerminalActions = { closeSettings(); cmuxControls.open(xmux.focused ?: xmux.windows.firstOrNull()) },
          connectionContent = {
            ConnectionPanel(linkController, when (val bridge = status) {
              is ConnectStatus.Pairing -> "Terminal pairing code: ${bridge.code}. On your Mac, open VRcode Mac from the menu bar and enter this code under Headset pairing."
              is ConnectStatus.Failed -> "Terminal connection: ${bridge.reason}"
              is ConnectStatus.Online -> "${bridge.session} · ${bridge.screens} terminal screens"
              is ConnectStatus.Connecting -> "Connecting to the terminal session…"
              else -> null
            })
          })
      }
    }
    settingsPanel?.setComponent(Visible(true))
    settingsOpen = true
    terminalPointer.update(allowInput = false)
    rightHandPointer.hide()
    placeSettings = true
    Log.i(TAG, "Settings opened: ${page.name}")
  }

  private fun closeSettings() {
    settingsPanel?.setComponent(Visible(false))
    settingsOpen = false
    setupConnection = false
    Log.i(TAG, "Settings closed")
  }

  private fun applySettings(value: AppSettings) {
    val s = value.sanitized()
    settings = s
    store.save(s)
    settingsLink.publish(s)
    if (!sceneReady) return
    xmux.setFontSize(s.terminalFontSize)
    terminalAppearance.update(xmux.windows, focus.focusedScreen) { effectiveSettings(it.screen) }
    voice.setVoiceProps(s.textMicProp, s.aiMicProp, s.aiModel(), s.aiThinking)
    // Restore boundary visibility before removing the view of the physical room.
    if (!s.passthrough) boundary.update(scene, false)
    scene.enablePassthrough(s.passthrough)
  }

  private fun headPose(): Pose? = Query.where { has(AvatarAttachment.id) }
    .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }.eval()
    .firstOrNull()?.tryGetComponent<Transform>()?.transform?.takeIf(WindowLayout::isUsableHeadPose)

  /**
   * When the frame being built will be shown, in nanoseconds on the monotonic clock. Window motion
   * (Follow, glides) steps by this, so each frame moves by exactly one display interval.
   */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun frameTimeNs(): Long =
    runCatching { spatial.getFrameTiming().predictedDisplayTimeNs }.getOrDefault(0L).takeIf { it > 0L } ?: System.nanoTime()

  private fun panelPose(head: Pose, distance: Float, heightOffset: Float): Pose {
    val forward = head.forward()
    val horizontal = Vector3(forward.x, 0f, forward.z)
    val direction = if (horizontal.length() > 0.1f) horizontal.normalize() else Vector3(0f, 0f, 1f)
    val offset = direction * distance
    return Pose(head.t + offset + Vector3(0f, heightOffset, 0f), Quaternion.lookRotation(offset))
  }

  private fun arrangeWindows() = terminalWindows.arrange()

  /** Touching a window in the dock recalls the existing window to just above the dock and focuses its live pane in cmux as well. */
  private fun showDockWindow(id: Int) {
    if (!resumed || settingsOpen || cmuxControls.isOpen || session?.state?.value != ConnectionState.Online) return
    val window = xmux.windows.firstOrNull { it.screen?.value == id } ?: return
    val pane = window.focusedPane ?: return
    val head = headPose() ?: return
    val screen = window.screen ?: return
    // Dock recall always returns to the normal workspace, including a minimized fullscreen panel.
    terminalWindows.show(screen)
    val distance = settings.windowDistanceM.coerceAtLeast(window.panelSize.widthM / 2f + 0.1f)
    val dockPose = dock.currentPose()
    val from = window.pose()
    if (dockPose != null && from != null) {
      dockRecall.start(id, from, DockRecall.targetPose(head.t, dockPose, window.panelSize.heightM, distance), frameTimeNs() / 1_000_000L)
    } else {
      terminalWindows.move(screen, WindowLayout.inFrontOfHead(head, distance))
    }
    xmux.focus(window, pane)
    // The local focusPane can be a no-op when this was already this screen's selected pane.
    // Switching screens must still update the Mac's active pane.
    window.session?.focusPane(pane)
  }

  /**
   * The left palm menu's Bring forward: glides the focused window from wherever it is to straight
   * ahead of the user at eye height and the window distance, facing them, where Arrange would put a
   * single window. Only the head's yaw counts, since the head is tilted down toward the palm while
   * picking the item. A minimized window is shown; a fullscreen one is already in front.
   */
  private fun bringFocusedForward() {
    val window = focus.focused
    if (window == null) {
      Log.i("VrcodeWindow", "bring forward: no focused window")
      return
    }
    val screen = window.screen ?: return
    val head = headPose() ?: return
    if (terminalWindows.isFullscreen(screen)) return
    terminalWindows.show(screen)
    val spec = window.panelSize
    val target = WindowLayout.arc(head, 0, 1, spec.widthM, spec.heightM,
      settings.windowDistanceM.coerceAtLeast(spec.widthM / 2f + 0.1f))
    val from = window.pose()
    if (from != null) dockRecall.start(screen.value, from, target, frameTimeNs() / 1_000_000L) else terminalWindows.move(screen, target)
    Log.i("VrcodeWindow", "bring forward: screen ${screen.value} (\"${window.state.title}\")")
  }

  private fun updateHeaderHover(screen: ScreenId?, point: Offset?) {
    for ((id, hover) in windowHeaderHover) hover.updatePointer(point.takeIf { id == screen })
  }

  private fun minimizeWindow(screen: ScreenId) {
    terminalPointer.cancel()
    dockRecall.cancel()
    terminalWindows.minimize(screen)
    xmux.windows.firstOrNull { it.visible }?.let { xmux.focus(it, it.focusedPane) }
    Log.i("VrcodeWindow", "minimized screen ${screen.value} to dock")
  }

  private fun toggleFullscreen(screen: ScreenId) {
    val window = xmux.windows.firstOrNull { it.screen == screen } ?: return
    terminalPointer.cancel()
    dockRecall.cancel()
    terminalWindows.toggleFullscreen(screen)
    xmux.focus(window, window.focusedPane)
    Log.i("VrcodeWindow", "screen ${screen.value} fullscreen=${terminalWindows.isFullscreen(screen)}")
  }

  private fun toggleWindowMode(screen: ScreenId) =
    setWindowMode(screen, if (terminalWindows.mode(screen) == WindowMode.Fixed) WindowMode.Follow else WindowMode.Fixed)

  private fun setWindowMode(screen: ScreenId, mode: WindowMode) = terminalWindows.setMode(screen, mode)

  private fun effectiveSettings(screen: ScreenId?): AppSettings = windowOverrides[screen]?.resolve(settings) ?: settings

  private fun toggleWindowSettings(screen: ScreenId) {
    if (windowSettingsScreen == screen) { closeWindowSettings(); return }
    val window = xmux.windows.firstOrNull { it.screen == screen && it.visible } ?: return
    closeWindowSettings()
    cmuxControls.hide()
    terminalPointer.cancel()
    xmux.focus(window)
    windowSettingsScreen = screen
    window.inputEnabled = false
    terminalPointer.update(allowInput = false)
    Log.i("VrcodeWindow", "Window settings opened: screen ${screen.value}")
  }

  private fun closeWindowSettings() {
    val screen = windowSettingsScreen ?: return
    xmux.windows.firstOrNull { it.screen == screen }?.inputEnabled = true
    windowSettingsScreen = null
    Log.i("VrcodeWindow", "Window settings closed: screen ${screen.value}")
  }

  private fun applyWindowSettings(screen: ScreenId, value: TerminalWindowSettings) {
    val window = xmux.windows.firstOrNull { it.screen == screen } ?: return
    val previous = windowOverrides[screen] ?: TerminalWindowSettings()
    val next = value.sanitized()
    windowOverrides[screen] = next
    if (previous.fontSize != next.fontSize) {
      terminalPointer.cancel()
      xmux.setFontSize(window, next.fontSize)
    }
    terminalAppearance.update(xmux.windows, focus.focusedScreen) { effectiveSettings(it.screen) }
    Log.i("VrcodeWindow", "Window settings changed: screen ${screen.value} $next")
  }

  private fun dragTerminal(screen: ScreenId, anchor: Offset, position: Offset) {
    if (!sceneReady || !resumed || settingsOpen || windowSettingsScreen != null || cmuxControls.isOpen || handMenu.busy) return
    val window = xmux.windows.firstOrNull { it.screen == screen } ?: return
    if (terminalWindows.isFullscreen(screen)) return
    // Events only authorize a held press. Its current hand ray owns lateral and depth motion in
    // tickFeatures; applying delayed panel coordinates here feeds the panel's own motion back in.
    if (!titleBarDepth.dragged(screen, anchor, position, headPose())) return
    dockRecall.cancel()
    xmux.focus(window)
  }

  private fun startDiscovery() {
    preferences.getString(LAST_ADDRESS, null)?.let { bridges.add(BridgeChoice("Last used", it)) }
    discovery = scope.launch {
      runCatching {
        client.discover().collect { advert ->
          val address = "${advert.host}:${advert.port}"
          adverts[address] = advert
          bridges.removeAll { it.address == address }
          bridges.add(0, BridgeChoice(advert.name, address, advert.session?.let { "cmux session $it" } ?: ""))
        }
      }.onFailure { Log.w(TAG, "Bridge discovery unavailable", it) }
    }
  }

  private fun connect(address: String) {
    // Explicit legacy development intents remain supported. Normal Settings uses the
    // authenticated relay and never falls back to unencrypted LAN speech.
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
      status = ConnectStatus.Failed("Add your Mac in Connection settings to use a secure connection.")
      return
    }
    linkController.disconnect()
    connectBridge(address)
  }

  private fun connectBridge(address: String, speechHost: String? = null, speechPort: Int = 7273) {
    val parsed = parseAddress(address, BridgeTarget.DEFAULT_PORT)
    if (parsed == null || parsed.first.isBlank() || parsed.second !in 1..65535) {
      status = ConnectStatus.Failed("Enter a Mac address and a port between 1 and 65535.")
      return
    }
    disconnectBridge()
    preferences.edit().putString(LAST_ADDRESS, address).apply()
    // The vrcode Mac service runs next to the bridge (over USB: adb reverse tcp:7273 tcp:7273).
    voice.connectSpeech(speechHost ?: intent?.getStringExtra("speech_host") ?: parsed.first, speechPort)
    cmuxControls.connect(speechHost ?: intent?.getStringExtra("speech_host") ?: parsed.first, speechPort)
    terminalWindows.connect(speechHost ?: intent?.getStringExtra("speech_host") ?: parsed.first, speechPort)
    settingsLink.connect(speechHost ?: intent?.getStringExtra("speech_host") ?: parsed.first, speechPort)
    val s = client.connect(BridgeTarget(parsed.first, parsed.second, adverts[address]?.fingerprint), scope)
    session = s
    terminalWindows.follow(s) { screen ->
      Log.i("VrcodeWindow", "mounted controls for screen ${screen.value}")
      windowOverrides.remove(screen)
      val hover = TerminalHeaderHover().also { windowHeaderHover[screen] = it }
      val onDrag: (Offset, Offset) -> Unit = { anchor, position -> dragTerminal(screen, anchor, position) }
      WindowChrome(
        // Clear: the screen view paints the body at the window's opacity (TerminalAppearanceController),
        // and an opaque frame behind it would hide the room.
        bodyColor = 0x00000000,
        onClose = { terminalPointer.cancel(); dockRecall.cancel(); xmux.windows.firstOrNull { it.screen == screen }?.let(cmuxControls::closeScreen) },
        onMinimize = { minimizeWindow(screen) },
        onZoom = { toggleFullscreen(screen) },
        trailingSlot = {
          val theme = effectiveSettings(screen).terminalTheme
          Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = {
              terminalPointer.cancel()
              cmuxControls.open(xmux.windows.firstOrNull { it.screen == screen })
            }) { androidx.compose.material3.Text("Actions") }
            WindowModeButton(terminalWindows.mode(screen), theme) { toggleWindowMode(screen) }
            WindowSettingsButton(tint = androidx.compose.ui.graphics.Color(theme.accent)) { toggleWindowSettings(screen) }
          }
        },
        header = { state, chrome ->
          val appearance = effectiveSettings(screen)
          val fullscreen = terminalWindows.isFullscreen(screen)
          VrcodeWindowHeader(state, appearance.windowStyle, chrome.withTerminalTheme(appearance.terminalTheme, appearance.windowOpacity),
            onDrag = onDrag.takeUnless { fullscreen }, fullscreen = fullscreen, hoverState = hover,
            focused = focus.focusedScreen == screen, badge = { voice.TargetBadge(screen) },
            onActivate = { if (focus.focusedScreen != screen) xmux.windows.firstOrNull { it.screen == screen }?.let { xmux.focus(it, it.focusedPane) } },
            lagging = linkLagging)
        },
        outline = { windowOutline(focus.focusedScreen == screen, effectiveSettings(screen).terminalTheme) },
        overlay = {
          if (windowSettingsScreen == screen) {
            val title = xmux.windows.firstOrNull { it.screen == screen }?.state?.title ?: "Terminal"
            TerminalWindowSettingsSheet(title, windowOverrides[screen] ?: TerminalWindowSettings(), settings, terminalWindows.mode(screen),
              onChange = { applyWindowSettings(screen, it) }, onMode = { setWindowMode(screen, it) },
              onReset = { applyWindowSettings(screen, TerminalWindowSettings()); setWindowMode(screen, settings.defaultWindowMode) },
              onClose = ::closeWindowSettings)
          }
        },
      )
    }
    status = ConnectStatus.Connecting(address)
    lagJob = scope.launch {
      linkLag.start(SystemClock.uptimeMillis())
      launch { s.events.collect { if (it is SessionEvent.Latency) linkLag.onReply(it.rttMs, SystemClock.uptimeMillis()) } }
      var wasLagging = false
      while (isActive) {
        val now = SystemClock.uptimeMillis()
        val state = s.state.value
        // Before the link is up, the windows' own attach spinner covers the wait.
        if (state !is ConnectionState.Online && state !is ConnectionState.Stalled) linkLag.start(now)
        val lagging = linkLag.lagging(now)
        if (lagging != wasLagging) Log.i(TAG, if (lagging) "link to the Mac is lagging" else "link to the Mac recovered")
        wasLagging = lagging
        linkLagging = lagging
        delay(LAG_CHECK_MS)
      }
    }
    sessionJob = scope.launch {
      var pairing = false
      s.state.combine(s.topology) { state, topology -> state to topology.screens.size }.collect { (state, screens) ->
        // The code must be entered in VRcode Mac, so show it once each time pairing starts.
        if (state is ConnectionState.Pairing && !pairing && !settingsOpen) { setupConnection = true; openSettings(SettingsPage.Connection) }
        pairing = state is ConnectionState.Pairing
        status = when (state) {
          ConnectionState.Connecting -> ConnectStatus.Connecting(address)
          is ConnectionState.Pairing -> ConnectStatus.Pairing(state.code)
          ConnectionState.Online, is ConnectionState.Stalled -> ConnectStatus.Online(s.sessionName ?: "cmux", screens)
          is ConnectionState.Reconnecting -> ConnectStatus.Reconnecting(state.attempt, state.reason)
          is ConnectionState.Closed -> state.reason?.let { ConnectStatus.Failed(it) } ?: ConnectStatus.Idle
        }
        // Debug builds only: lets the code be read from logcat and approved in VRcode Mac without wearing the headset.
        if (state is ConnectionState.Pairing && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
          Log.i(TAG, "Pairing code ${state.code}")
        }
        if (state is ConnectionState.Online) Log.i(TAG, "Bridge online: ${s.sessionName}, $screens screens")
        if (state == ConnectionState.Online && screens > 0 && setupConnection) closeSettings()
      }
    }
  }

  private fun disconnect() {
    linkController.disconnect()
  }

  private fun disconnectBridge() {
    closeWindowSettings()
    cmuxControls.disconnect()
    settingsLink.close()
    terminalPointer.clear()
    rightHandPointer.hide()
    titleBarDepth.cancel()
    edgeResize.cancel()
    leftHandMove.cancel()
    sessionJob?.cancel()
    lagJob?.cancel()
    lagJob = null
    linkLagging = false
    sessionJob = null
    terminalWindows.disconnect()
    for (window in xmux.windows.toList()) xmux.unmount(window)
    windowOverrides.clear()
    windowHeaderHover.clear()
    dockRecall.cancel()
    session?.close()
    session = null
    status = ConnectStatus.Idle
  }

  override fun onResume() {
    super.onResume()
    boundary.reset()
    resumed = true
    // The user may have turned or walked while away; bring open settings back in front of them.
    if (settingsOpen) placeSettings = true
    // Waking the headset often drops Wi-Fi briefly; retry the Mac now rather than after the backoff.
    if (sceneReady) linkController.kick()
  }

  /** Settings placed from a pre-recenter or off-head pose would otherwise stay out of view. */
  override fun onRecenter(isUserInitiated: Boolean) {
    super.onRecenter(isUserInitiated)
    if (settingsOpen) placeSettings = true
  }

  override fun onHMDMounted() {
    super.onHMDMounted()
    if (settingsOpen) placeSettings = true
  }

  override fun onPause() {
    resumed = false
    edgeResize.cancel()
    leftHandMove.cancel()
    dock.hide()
    rightClick.cancel()
    terminalPointer.update(allowInput = false)
    rightHandPointer.hide()
    store.save(settings)
    // Horizon OS can kill a paused app without onDestroy: save window placements to cmux now.
    terminalWindows.flush()
    super.onPause()
  }

  override fun onSpatialShutdown() {
    edgeResize.close()
    leftHandMove.close()
    freeLasers.close()
    dock.close()
    keyBar.close()
    cmuxControls.releasePanel()
    rightClick.close()
    terminalPointer.close()
    rightHandPointer.hide()
    boundary.reset()
    sceneReady = false
    super.onSpatialShutdown()
  }

  override fun onDestroy() {
    edgeResize.close()
    leftHandMove.close()
    freeLasers.close()
    dock.close()
    gridSizing.dispose()
    cmuxControls.close()
    terminalWindows.close()
    settingsLink.close()
    rightClick.close()
    terminalPointer.close()
    rightHandPointer.hide()
    discovery?.cancel()
    linkController.close()
    voice.close()
    handMenu.release()
    scope.cancel()
    settingsPanel?.destroy()
    super.onDestroy()
  }

  private companion object {
    const val TAG = "Vrcode"
    const val LAST_ADDRESS = "last_address"
    /** How often the windows' lag spinner is re-evaluated. */
    const val LAG_CHECK_MS = 250L
    const val REQUEST_MIC = 1
  }
}
