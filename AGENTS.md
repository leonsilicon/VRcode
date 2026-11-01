# Repository scripting

- Use Bun and TypeScript for every repository-owned script and script test. Never use Python,
  including inline code, generated code, subprocesses, or Blender scripting.
- Run scripts with `bun scripts/<name>.ts`, package tasks with `bun run <task>`, and tests with
  `bun test`. Use `bun install` / `bun add` for dependencies and `bunx --bun` for package CLIs.
- Bun scripts may invoke native tools such as Swift, Gradle, adb, codesign, and Blender's
  ordinary rendering CLI. Keep the upstream Gradle wrapper launchers intact.
- Treat the checked-in `.blend` files as editable asset sources; do not add scripted Blender
  construction or export through its embedded interpreter.
- Run `bun test` and `bun run assets:validate` after changing repository tooling.

# Quest app

- Always reinstall the app on the connected Quest after changing anything that ships in it
  (`app/`, `settings-ui/`, `connection/`, native code, bundled assets, or the xmux dependency).
  A passing build or unit test is not enough: the headset keeps running the old APK.
- Find the headset with `adb devices -l` (`model:Quest_3`; over Wi-Fi its serial is `ip:5555`),
  then run `ANDROID_SERIAL=<serial> ./gradlew :app:installDebug` and relaunch
  `dev.improve.vrcode` (Argent `restart-app`, or `adb shell am start -n
  dev.improve.vrcode/.MainActivity`).
- Confirm the new build is running before reporting: `adb shell dumpsys package dev.improve.vrcode
  | grep lastUpdateTime`, plus `adb logcat --pid=$(adb shell pidof dev.improve.vrcode)` for crashes
  and the feature's own log lines. If no headset is connected, say that the change is not yet
  installed.
- Several agents share this tree and headset. Reinstalling restarts the app, so mention it in your
  report, and install from a tree that builds.

Browser automation follows the inherited user instructions: headless `agent-browser`, a managed
browser, an isolated named session, and no focus stealing or personal browser attachment.
