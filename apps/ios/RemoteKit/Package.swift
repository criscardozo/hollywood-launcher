// swift-tools-version: 6.0
import PackageDescription

// The Google TV "Remote v2" protocol (the one the Google TV phone app uses): a hand-written protobuf
// subset, a self-signed client certificate, and TLS through SwiftNIO SSL. Not Network.framework: the
// TV's certificate (RSA key, ecdsa-with-SHA256 signature) fails Apple's parser with -9808 before any
// verify block runs; NIO's BoringSSL takes it when verification is off (pairing is the trust).
// `atvremote` is a Mac command-line client used to test it against the Google TV emulator.
let package = Package(
    name: "RemoteKit",
    platforms: [.iOS(.v17), .macOS(.v15)],
    products: [
        .library(name: "RemoteKit", targets: ["RemoteKit"]),
        .executable(name: "atvremote", targets: ["atvremote"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.104.0"),
        .package(url: "https://github.com/apple/swift-nio-ssl.git", from: "2.37.5"),
    ],
    targets: [
        .target(name: "RemoteKit", dependencies: [
            .product(name: "NIOCore", package: "swift-nio"),
            .product(name: "NIOPosix", package: "swift-nio"),
            .product(name: "NIOTLS", package: "swift-nio"),
            .product(name: "NIOSSL", package: "swift-nio-ssl"),
        ]),
        .executableTarget(name: "atvremote", dependencies: ["RemoteKit"]),
        .testTarget(name: "RemoteKitTests", dependencies: ["RemoteKit"]),
    ]
)
