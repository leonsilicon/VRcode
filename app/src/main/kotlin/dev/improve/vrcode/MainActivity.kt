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
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.voice.VoiceFeature
import dev.improve.vrcode.dock.DockFeature
import dev.improve.vrcode.hands.RightClickFeature
import dev.improve.vrcode.cmux.CmuxFeature
import dev.improve.vrcode.pie.HandMenusFeature
import dev.improve.vrcode.connection.ConnectionController
import dev.improve.vrcode.connection.ConnectionPanel
import dev.improve.xmux.client.BridgeAdvert
import dev.improve.xmux.client.BridgeTarget
import dev.improve.xmux.client.ConnectionState
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.client.XmuxClient
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.connect.panel.BridgeChoice
import dev.improve.xmux.connect.panel.ConnectStatus
import dev.improve.xmux.connect.panel.parseAddress
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.SpatialConfig
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.vrcode.dock.DockRay
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppSystemActivity() {
  private val preferences by lazy { getSharedPreferences("vrcode", MODE_PRIVATE) }
  private val store by lazy { SettingsStore(preferences) }
  private var settings by mutableStateOf(AppSettings())
  private val xmux by lazy { XmuxSpatial(this, SpatialConfig(enablePassthrough = false)) }
  private val client by lazy { XmuxClient(this) }
  private val failures = FailureLog(TAG)
  /** A failed background job must not take down the headset app with it. */
  private val scope = MainScope() + CoroutineExceptionHandler { _, error -> failures.report("background job", error) }
  private val palmMenu = LeftPalmMenu()
  private val boundary = BoundaryVisibility()
  private val menuButton = MenuButtonGate()
  private val terminalAppearance = TerminalAppearanceController()
  private val terminalPointer by lazy { TerminalPointerInput(xmux) }
  private val rightHandPointer by lazy { RightHandPointer(this, xmux) }
  private val cmuxControls: CmuxFeature by lazy { CmuxFeature(this, xmux, scope) }
  /** Voice mode: left fist dictates into a voice pane, left pinch pastes it (see voice/VoiceFeature.kt). */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val voice: VoiceFeature by lazy {
    VoiceFeature(this, xmux, scope, overlayHitDistance = { origin, direction -> dock.hitDistance(origin, direction) },
      dockPose = { dock.currentPose() }) {
      spatial.getFrameTiming().predictedDisplayTimeNs
    }
  }
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val rightClick: RightClickFeature by lazy { RightClickFeature(this, xmux,
    predictedTimeNs = { spatial.getFrameTiming().predictedDisplayTimeNs },
    onContextMenu = { hit -> terminalPointer.cancel(); cmuxControls.open(hit) }) }
  /** Palm toward the user + middle pinch: left = new terminal/Codex/Claude, right = keyboard and keys. */
  @OptIn(SpatialSDKExperimentalAPI::class)
  private val handMenu: HandMenusFeature by lazy { HandMenusFeature(this, xmux, voice, cmuxControls) { spatial.getFrameTiming().predictedDisplayTimeNs } }
  private val dock: DockFeature by lazy {
    DockFeature(xmux, onWindow = ::showDockWindow, onKeyboard = { handMenu.toggleKeyboard() },
      onArrange = ::arrangeWindows, onSettings = { openSettings() }, onConnection = { openSettings(SettingsPage.Connection) })
  }
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
  private var discovery: Job? = null
  private var settingsPage by mutableStateOf(SettingsPage.Workspace)
  private var customizationTab by mutableStateOf(CustomizationTab.Pointer)
  private var settingsPanel: Entity? = null
  private var settingsOpen = false
  private var placeSettings = false
  private var sceneReady = false
  private var setupConnection = false
  private var resumed = false
  private val windowMotion = mutableMapOf<ScreenId, WindowMotion>()

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
    sceneReady = true
    applySettings(settings)
    setupConnection = true
    openSettings(SettingsPage.Connection)
    startDiscovery()
    linkController.discover()
    val address = intent?.getStringExtra("address")
    if (address != null) connect(address)
    else if (settings.autoConnect && linkController.profile.value.paired) linkController.connect()
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

  /** Both hands' pointer poses, when tracked. */
  private fun activeHands(): List<Pose> {
    val body = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody() ?: return emptyList()
    return listOfNotNull(body.leftHand, body.rightHand)
      .filter { it.tryGetComponent<Controller>()?.isActive == true }
      .mapNotNull { it.tryGetComponent<Transform>()?.transform }
  }

  private fun tickDock() {
    val hands = activeHands()
    dock.onSceneTick(headPose(), visible = resumed,
      connected = session?.state?.value == ConnectionState.Online,
      keyboardShown = runCatching { handMenu.keyboardShown }.getOrDefault(false),
      rays = hands.map { DockRay(it.t, it.forward()) }, hands = hands.map { it.t })
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun tickFeatures() {
    terminalAppearance.update(xmux.windows, settings)
    if (resumed) boundary.update(scene, settings.passthrough)
    if (resumed) {
      val sdkDown = Query.where { has(Controller.id, AvatarAttachment.id) }.filter { isLocal() }.eval().any { entity ->
        val side = entity.getComponent<AvatarAttachment>().type
        (side == "left_hand" || side == "left_controller") && entity.getComponent<Controller>().isDown(ButtonBits.ButtonMenu)
      }
      val palmDown = palmMenu.isPressed(scene, spatial.getFrameTiming().predictedDisplayTimeNs)
      if (menuButton.update(sdkDown, palmDown, SystemClock.uptimeMillis())) toggleSettings()
    }
    updateWindowMotion()
    val right = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()?.rightHand
    val rightRay = right?.takeIf { it.tryGetComponent<Controller>()?.isActive == true }?.tryGetComponent<Transform>()?.transform
    val overDock = rightRay?.let { dock.blocksRay(it.t, it.forward()) } == true
    terminalPointer.update(allowInput = resumed && !settingsOpen && !cmuxControls.isOpen)
    if (rightHandPointer.update(visible = resumed && !settingsOpen && !cmuxControls.isOpen && !overDock,
        style = settings.pointerCursor, size = settings.pointerCursorSize)) terminalPointer.cancel()
    voice.onSceneTick(allowGestures = !settingsOpen && !cmuxControls.isOpen,
      previewOnLeftHand = resumed && settingsOpen && settingsPage == SettingsPage.Customization && customizationTab == CustomizationTab.Microphone)
    handMenu.onSceneTick(enabled = resumed && !settingsOpen && !cmuxControls.isOpen)
    // A palm-facing middle pinch belongs to the pie; the right-click is the palm-away one.
    rightClick.onSceneTick(enabled = resumed && !settingsOpen && !cmuxControls.isOpen && !handMenu.busy && !overDock)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    // Some runtime versions deliver the app-menu action as an Android menu key instead.
    if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
      if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && menuButton.request(SystemClock.uptimeMillis())) toggleSettings()
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
    if (event.action == KeyEvent.ACTION_DOWN) terminalPointer.clear()
    return xmux.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (sceneReady) intent.getStringExtra("address")?.let { openSettings(SettingsPage.Connection); connect(it) }
  }

  private fun toggleSettings() {
    if (settingsOpen) closeSettings() else openSettings()
  }

  private fun openSettings(page: SettingsPage = SettingsPage.Workspace) {
    if (!sceneReady) return
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
              is ConnectStatus.Pairing -> "Terminal pairing code: ${bridge.code}. On the Mac, run xmux pair and enter this code."
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
    if (!sceneReady) return
    xmux.setFontSize(s.terminalFontSize)
    terminalAppearance.update(xmux.windows, s)
    voice.setVoiceProp(s.voiceProp)
    // Restore boundary visibility before removing the view of the physical room.
    if (!s.passthrough) boundary.update(scene, false)
    scene.enablePassthrough(s.passthrough)
  }

  private fun headPose(): Pose? = Query.where { has(AvatarAttachment.id) }
    .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }.eval()
    .firstOrNull()?.tryGetComponent<Transform>()?.transform?.takeIf(WindowLayout::isUsableHeadPose)

  private fun panelPose(head: Pose, distance: Float, heightOffset: Float): Pose {
    val forward = head.forward()
    val horizontal = Vector3(forward.x, 0f, forward.z)
    val direction = if (horizontal.length() > 0.1f) horizontal.normalize() else Vector3(0f, 0f, 1f)
    val offset = direction * distance
    return Pose(head.t + offset + Vector3(0f, heightOffset, 0f), Quaternion.lookRotation(offset))
  }

  private fun terminalPlacement(index: Int, count: Int): Placement {
    val head = headPose() ?: return Placement.InFrontOfHead
    val window = xmux.windows.getOrNull(index)
    val width = window?.panelSize?.widthM ?: xmux.spec.widthM
    val height = window?.panelSize?.heightM ?: xmux.spec.heightM
    return Placement.Fixed(WindowLayout.arc(head, index, count, width, height, settings.windowDistanceM.coerceAtLeast(width / 2f + 0.1f)))
  }

  private fun arrangeWindows() {
    val head = headPose() ?: return
    val windows = xmux.windows.toList()
    for ((index, window) in windows.withIndex()) {
      (terminalPlacement(index, windows.size) as? Placement.Fixed)?.let {
        window.setPose(it.pose)
        windowMotion[window.screen]?.moved(head, it.pose)
      }
    }
  }

  /** A dock click recalls the existing window and focuses its live pane in cmux as well. */
  private fun showDockWindow(id: Int) {
    if (!resumed || settingsOpen || cmuxControls.isOpen || session?.state?.value != ConnectionState.Online) return
    val window = xmux.windows.firstOrNull { it.screen?.value == id } ?: return
    val pane = window.focusedPane ?: return
    val head = headPose() ?: return
    val pose = WindowLayout.inFrontOfHead(head, settings.windowDistanceM.coerceAtLeast(window.panelSize.widthM / 2f + 0.1f))
    window.setPose(pose)
    windowMotion[window.screen]?.moved(head, pose)
    xmux.focus(window, pane)
    // The local focusPane can be a no-op when this was already this screen's selected pane.
    // Switching screens must still update the Mac's active pane.
    window.session?.focusPane(pane)
  }

  private fun updateWindowMotion() {
    val windows = xmux.windows
    windowMotion.keys.retainAll(windows.mapNotNull { it.screen }.toSet())
    if (!resumed) return
    val head = headPose() ?: return
    for (window in windows) {
      val motion = windowMotion[window.screen] ?: continue
      val world = window.pose() ?: continue
      motion.poseForFrame(head, world)?.let(window::setPose)
    }
  }

  private fun toggleWindowMode(screen: ScreenId) {
    val motion = windowMotion[screen] ?: return
    val window = xmux.windows.firstOrNull { it.screen == screen } ?: return
    val next = if (motion.mode == WindowMode.Fixed) WindowMode.Follow else WindowMode.Fixed
    motion.setMode(next, headPose(), window.pose())
  }

  private fun dragTerminal(screen: ScreenId, anchor: Offset, position: Offset) {
    if (!sceneReady || settingsOpen) return
    val window = xmux.windows.firstOrNull { it.screen == screen } ?: return
    val pose = window.pose() ?: return
    val spec = window.panelSize
    val dimensions = window.entity?.tryGetComponent<IsdkPanelDimensions>()?.dimensions
    val head = headPose()
    // The header spans the panel's top edge, so its pointer coordinates are panel coordinates.
    val moved = titleBarDragPose(
      pose, anchor, position,
      centre = Offset(spec.widthPx / 2f, spec.heightPx / 2f),
      metresPerPixelX = (dimensions?.x ?: spec.widthM) / spec.widthPx,
      metresPerPixelY = (dimensions?.y ?: spec.heightM) / spec.heightPx,
      head = head?.t,
    )
    window.setPose(moved)
    windowMotion[screen]?.moved(head, moved)
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
    val s = client.connect(BridgeTarget(parsed.first, parsed.second, adverts[address]?.fingerprint), scope)
    session = s
    xmux.follow(s, placement = ::terminalPlacement, chrome = { screen ->
      val motion = WindowMotion(settings.defaultWindowMode).also { windowMotion[screen] = it }
      val onDrag: (Offset, Offset) -> Unit = { anchor, position -> dragTerminal(screen, anchor, position) }
      WindowChrome(
        trailingSlot = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = {
              terminalPointer.cancel()
              cmuxControls.open(xmux.windows.firstOrNull { it.screen == screen })
            }) { androidx.compose.material3.Text("Actions") }
            WindowModeButton(motion.mode, settings.terminalTheme) { toggleWindowMode(screen) }
            SettingsButton(tint = androidx.compose.ui.graphics.Color(settings.terminalTheme.accent)) { openSettings() }
          }
        },
        header = { state, chrome -> VrcodeWindowHeader(state, settings.windowStyle, chrome.withTerminalTheme(settings.terminalTheme), onDrag) },
      )
    })
    status = ConnectStatus.Connecting(address)
    sessionJob = scope.launch {
      s.state.combine(s.topology) { state, topology -> state to topology.screens.size }.collect { (state, screens) ->
        status = when (state) {
          ConnectionState.Connecting -> ConnectStatus.Connecting(address)
          is ConnectionState.Pairing -> ConnectStatus.Pairing(state.code)
          ConnectionState.Online, is ConnectionState.Stalled -> ConnectStatus.Online(s.sessionName ?: "cmux", screens)
          is ConnectionState.Reconnecting -> ConnectStatus.Reconnecting(state.attempt, state.reason)
          is ConnectionState.Closed -> state.reason?.let { ConnectStatus.Failed(it) } ?: ConnectStatus.Idle
        }
        // Debug builds only: lets `xmux pair <code>` run on the Mac without wearing the headset.
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
    cmuxControls.disconnect()
    terminalPointer.clear()
    rightHandPointer.hide()
    sessionJob?.cancel()
    sessionJob = null
    xmux.follow(null)
    for (window in xmux.windows.toList()) xmux.unmount(window)
    windowMotion.clear()
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
    dock.hide()
    rightClick.cancel()
    terminalPointer.update(allowInput = false)
    rightHandPointer.hide()
    store.save(settings)
    super.onPause()
  }

  override fun onSpatialShutdown() {
    dock.close()
    cmuxControls.releasePanel()
    rightClick.close()
    terminalPointer.close()
    rightHandPointer.hide()
    palmMenu.close()
    boundary.reset()
    sceneReady = false
    super.onSpatialShutdown()
  }

  override fun onDestroy() {
    dock.close()
    cmuxControls.close()
    rightClick.close()
    terminalPointer.close()
    rightHandPointer.hide()
    discovery?.cancel()
    linkController.close()
    voice.close()
    handMenu.release()
    scope.cancel()
    palmMenu.close()
    settingsPanel?.destroy()
    super.onDestroy()
  }

  private companion object {
    const val TAG = "Vrcode"
    const val LAST_ADDRESS = "last_address"
    const val REQUEST_MIC = 1
  }
}
