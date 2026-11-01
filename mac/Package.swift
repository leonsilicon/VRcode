// swift-tools-version: 6.0
import PackageDescription
import Foundation

let infoPlist = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Info.plist").path

let package = Package(
  name: "vrcode-mac",
  platforms: [.macOS("27.0")],
  targets: [
    .executableTarget(
      name: "vrcode-mac",
      path: "Sources/vrcode-mac",
      swiftSettings: [.swiftLanguageMode(.v5)],
      linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", infoPlist])]
    ),
  ]
)
