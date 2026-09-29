{ pkgs ? import <nixpkgs> {
    config = {
      android_sdk.accept_license = true;
      allowUnfree = true;
    };
  }
}:

let
  # AGP 8.7.3 wants its own default build tools (34.0.0) next to the compileSdk 35 platform, and
  # scripts/build-release.sh signs with $ANDROID_HOME/build-tools/35.0.0/apksigner. Dropping 34.0.0
  # makes Gradle try to install it into the read-only store, so both versions stay listed.
  buildToolsVersions = [ "34.0.0" "35.0.0" ];

  # The aapt2 binaries Google ships are not patchable on NixOS, so AGP is pointed at this SDK's copy.
  aapt2BuildToolsVersion = "35.0.0";

  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    inherit buildToolsVersions;

    # platform-tools (adb) is always part of the composition. AVDs are not needed: the app is
    # tested on real hardware, so the emulator and its system images stay out of the closure.
    includeEmulator = false;
    includeSystemImages = false;
    includeSources = false;
    includeNDK = false;
  };

  androidSdk = androidComposition.androidsdk;
in

pkgs.mkShell {
  packages = with pkgs; [
    androidSdk
    fastlane
    gradle
    jdk17
    wireshark-cli
  ];

  # AGP resolves the SDK from ANDROID_HOME, so nothing in the project pins a Nix store path.
  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";

  # The aapt2 binaries Google ships are not patchable on NixOS, so point AGP at this SDK's copy.
  GRADLE_OPTS =
    "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${aapt2BuildToolsVersion}/aapt2";

  shellHook = ''
    export PATH="$ANDROID_HOME/platform-tools:$PATH"

    echo "Android SDK: $ANDROID_HOME"
    echo "Build tools available:"
    ls "$ANDROID_HOME/build-tools" || true
    echo ""
    echo "Run:"
    echo "  ./gradlew assembleDebug"
  '';
}
