package com.helloworld;

import android.app.Application;

import com.facebook.react.PackageList;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactHost;
import com.facebook.react.ReactPackage;
import com.facebook.react.defaults.DefaultReactHost;

import java.util.List;

public class MainApplication extends Application implements ReactApplication {

  @Override
  public ReactHost getReactHost() {
    List<ReactPackage> packages = new PackageList(this).getPackages();
    return DefaultReactHost.getDefaultReactHost(getApplicationContext(), packages);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative(this);
  }
}
