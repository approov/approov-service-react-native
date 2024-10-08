require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "approov-service-react-native"
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

  s.source_files = "ios/**/*.{h,c,m,swift}"
  s.exclude_files = "ios/Approov.xcframework/**/*"
  s.requires_arc = true
  s.resources = "ios/approov.{config,plist}"

  s.ios.vendored_frameworks = "ios/Approov.xcframework"
  s.ios.deployment_target  = '13.4'

  s.dependency "React"
end
