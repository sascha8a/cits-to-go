{ pkgs ? import <nixpkgs> {
    config = {
      android_sdk.accept_license = true;
      allowUnfree = true;
    };
  }
}:

let
  buildToolsVersion = "34.0.0";

  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" "35" ];
    buildToolsVersions = [ "34.0.0" "35.0.0" ];

    # Useful for Gradle / adb
    includeEmulator = true;
    includeSystemImages = false;
    includeSources = false;
    includeNDK = false;
  };

  androidSdk = androidComposition.androidsdk;
in

pkgs.mkShell {
  packages = with pkgs; [
    android-studio
    androidSdk
    gradle
    jdk17
  ];

  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";

  GRADLE_OPTS =
    "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";

  shellHook = ''
    export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/tools:$ANDROID_HOME/tools/bin:$PATH"

    echo "Android SDK: $ANDROID_HOME"
    echo "Build tools available:"
    ls "$ANDROID_HOME/build-tools" || true
    echo ""
    echo "Run:"
    echo "  ./gradlew assembleDebug"
  '';
}
