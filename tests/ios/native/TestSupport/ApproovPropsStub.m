#import "ios/ApproovProps.h"

@implementation ApproovProps

+ (instancetype)sharedProps {
  static ApproovProps *sharedProps = nil;
  static dispatch_once_t onceToken = 0;
  dispatch_once(&onceToken, ^{
    sharedProps = [[ApproovProps alloc] init];
  });
  return sharedProps;
}

- (NSString *)valueForKey:(NSString *)key {
  (void)key;
  return nil;
}

@end
