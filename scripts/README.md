# Repository tooling

Use Bun 1.4.4 or newer for all repository-owned scripts and tests. No package install is needed;
the scripts use Bun and its built-in compatibility modules. The upstream `gradlew` / `gradlew.bat`
launchers remain unchanged and invoke the JVM for native builds.

| Task | Command |
| --- | --- |
| Quest USB / Wi-Fi setup | `bun run quest-link --help` |
| Build and run the background Mac app | `bun run mac:run --bluetooth` |
| Package the Mac app without launching | `bun run mac:run --package-only` |
| Script policy and unit tests | `bun run test` |
| Native TLS relay integration tests | `VRCODE_RELAY_BINARY="$PWD/mac/.build/debug/vrcode-mac" bun test scripts/tests/relay-integration.test.ts` |
| Background cmux integration tests | `VRCODE_CMUX_BINARY="$PWD/mac/.build/debug/vrcode-mac" bun test scripts/tests/cmux-integration.test.ts` |
| Kotlin / Swift integration on Android | `bun run check:relay:android --serial emulator-5554` |
| Regenerate the cmux action catalog | `bun run cmux:sync /path/to/cmux` |
| Check generated catalog freshness | `bun run cmux:sync /path/to/cmux --check` |
| Validate all delivered GLBs | `bun run assets:validate` |
| Render saved asset projects | `bun run assets:preview` |

`bun test` also checks the scripting policy. Native relay tests require the built Mac binary;
Android checks require a device, Android Platform Tools and the compiled instrumentation APK.
Bluetooth checks require a packaged Mac app and the relevant OS permissions and bonds. These
checks create isolated test credentials and stop only the service instance they start.

The asset renderer uses the standard Blender CLI, disables script auto-execution, and writes to
`artifacts/asset-previews/`. Editable `.blend` files are the asset sources; edit and export them
through Blender's UI. Asset validation prints current coverage as JSON and leaves the historical
Blender re-import reports intact.
