// swift-tools-version: 6.2
import PackageDescription

// Pure Swift layers of x_radar, free of UIKit/SwiftUI/CoreLocation so every rule
// is unit-testable without a simulator:
//   XRadarCore — domain models, geometry, route maths, report relevance,
//                guidance text, sun cycle.
//   XRadarData — the single backend API client, DTOs, repositories, fixtures.
let package = Package(
    name: "XRadarKit",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "XRadarCore", targets: ["XRadarCore"]),
        .library(name: "XRadarData", targets: ["XRadarData"]),
    ],
    targets: [
        .target(name: "XRadarCore"),
        .target(name: "XRadarData", dependencies: ["XRadarCore"]),
        .testTarget(name: "XRadarDataTests", dependencies: ["XRadarData"]),
    ]
)
