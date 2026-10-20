// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HypemanKit",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "HypemanKit", targets: ["HypemanKit"]),
    ],
    targets: [
        .target(name: "HypemanKit", resources: [.process("Resources")]),
        .testTarget(name: "HypemanKitTests", dependencies: ["HypemanKit"]),
    ]
)
