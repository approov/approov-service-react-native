require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "approov-service-react-native"
  s.module_name  = "approov_service_react_native"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = <<-DESC
                  approov-service-react-native
                   DESC
  s.homepage     = "https://github.com/approov/approov-service-react-native"
  # brief license entry:
  s.license      = "MIT"
  # optional - use expanded license entry instead:
  # s.license    = { :type => "MIT", :file => "LICENSE" }
  s.authors      = { "CriticalBlue, Ltd." => "support@approov.io" }
  s.platform     = :ios
  s.source       = { :git => "https://github.com/approov/approov-service-react-native.git", :tag => "#{s.version}" }
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.swift_version = "5.0"
  # s.exclude_files = "ios/Approov.xcframework/**/*"
  s.requires_arc = true
  s.static_framework = true
  s.pod_target_xcconfig = { 
    'DEFINES_MODULE' => 'YES',
    'FRAMEWORK_SEARCH_PATHS' => '$(PODS_CONFIGURATION_BUILD_DIR)/approov-ios-sdk',
    'SWIFT_INCLUDE_PATHS' => '$(PODS_ROOT)/Target Support Files/swift-http-structured-headers'
  }
  # s.resources = "ios/approov.{config,plist}"

  # s.ios.vendored_frameworks = "ios/Approov.xcframework"
  s.ios.deployment_target  = '11.0'

  # Dependency on the Approov SDK
  s.dependency 'approov-ios-sdk', '~> 3.5.3'
  s.dependency 'swift-http-structured-headers', '~> 1.4.0'
  s.frameworks = 'Approov'

  s.dependency "React-Core"
end
