// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FirmwareSimulation",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "FirmwareSimulation", targets: ["FirmwareSimulation"])],
    targets: [
        .target(name: "FirmwareSimulation"),
        .testTarget(name: "FirmwareSimulationTests", dependencies: ["FirmwareSimulation"]),
    ]
)
