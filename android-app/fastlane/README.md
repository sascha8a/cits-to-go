fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android release

```sh
[bundle exec] fastlane android release
```

Build a signed release APK for attaching to a GitHub Release

### android unsigned_release

```sh
[bundle exec] fastlane android unsigned_release
```

Build an unsigned release APK for local testing only

### android debug

```sh
[bundle exec] fastlane android debug
```

Build the debug APK

### android github_release_notes

```sh
[bundle exec] fastlane android github_release_notes
```

Create release notes text for a GitHub Release

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
