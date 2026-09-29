// The tests inspect file-private service state. Compile the service and test
// in one translation unit so those symbols remain private to the test binary.
#import "ios/ApproovService.m"
#import "ApproovNativeTests.m"
