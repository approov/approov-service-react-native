// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "approov-service-react-native",
    platforms: [
        .iOS(.v11)
    ],
    products: [
        .library(
            name: "approov-service-react-native",
            targets: ["approov-service-react-native"]
        ),
    ],
    dependencies: [
        // Approov native SDK
        .package(url: "https://github.com/approov/approov-ios-sdk.git", from: "3.5.3"),
        // Structured headers
        .package(url: "https://github.com/apple/swift-http-structured-headers.git", from: "1.4.0")
    ],
    targets: [
        .target(
            name: "approov-service-react-native",
            dependencies: [
                .product(name: "Approov", package: "approov-ios-sdk"),
                .product(name: "StructuredHeaders", package: "swift-http-structured-headers")
            ],
            path: "ios",
            publicHeadersPath: "."
        )
    ]
)
