# Releasing a git tag through JitPack

Version `v0.1.0` is a git release, consumed through JitPack. Creating a GitHub Release is optional;
JitPack builds from the git tag. The repository must be public for anonymous installation.
Repository visibility, tag creation and the release announcement are separate owner decisions.

## Prepare the release commit

1. Update the README and package guide to the intended tag, and finalize its changelog entry.
   Review [third-party notices](THIRD_PARTY_NOTICES.md), dependency versions and redistribution
   requirements. Do not claim a JitPack build has succeeded until its published artifacts resolve.
2. For deliberate public API changes, run `./gradlew apiDump`, inspect the dump diff and commit it
   with the change. `check` compares signatures with the committed baseline; declarations marked
   `InternalStreamApi` are excluded from that contract.
3. With JDK 17 and Android SDK 36 installed, run:

   ```sh
   ./gradlew check :sample:assembleDebug --no-daemon
   ./gradlew publishToMavenLocal \
     -PGROUP=com.github.droidrun.mobilerun-android \
     -PVERSION_NAME=v0.1.0 -PsignAllPublications=false
   ```

   Inspect every POM and `.module` file under the corresponding Maven Local group. All intermodule
   dependencies must use the same group and tag version, including the JVM variants. Resolve
   `mobilerun-stream-compose` from Maven Local in a separate Android consumer to check transitive
   metadata. The normal PR workflow also checks unsigned publications with the development coordinates.
4. Merge the preparation PR only after CI is green. Select the exact green commit on `main`.
   Before making the repository public, verify release-tag protections, repository permissions and
   private vulnerability reporting, and review repository metadata and history for public suitability.

## Create and verify the tag

Only after release approval, create the tag at the selected commit and push it:

```sh
git tag v0.1.0 <release-commit>
git push origin v0.1.0
```

Treat release tags as immutable: fix a failed release in a new commit and version rather than moving
a published tag. Tag publication makes that source revision consumable immediately.

Request the tag build at [JitPack](https://jitpack.io/#droidrun/mobilerun-android/v0.1.0) and inspect its
build log and artifact listing. `jitpack.yml` selects JDK 17, installs Android platform 36/build tools
36.0.0, and publishes unsigned artifacts with group `com.github.droidrun.mobilerun-android`. JitPack's
`VERSION` environment variable supplies the requested git tag as `VERSION_NAME`; the development
`VERSION_NAME` in `gradle.properties` does not need to be changed to create the tag.

Verify a clean external Android app can resolve the [README install snippet](README.md#install)
from JitPack, including transitive dependencies. Test both Compose and View-only consumers before
announcing the release. Maven Local verification does not prove that JitPack's remote build image or
anonymous artifact resolution works. A GitHub Release may then link to the changelog and install guide.

## Coordinates and artifacts

All entries below use version `v0.1.0` for this release.

| Module | Maven coordinate (append `:v0.1.0`) | Packaging |
|---|---|---|
| `:protocol` | `com.github.droidrun.mobilerun-android:mobilerun-stream-protocol` | Kotlin Multiplatform metadata + `-jvm` JAR |
| `:stream-core` | `com.github.droidrun.mobilerun-android:mobilerun-stream-core` | Kotlin Multiplatform metadata + `-jvm` JAR |
| `:stream-android` | `com.github.droidrun.mobilerun-android:mobilerun-stream-android` | release AAR, minSdk 24 |
| `:stream-compose` | `com.github.droidrun.mobilerun-android:mobilerun-stream-compose` | release AAR, minSdk 24 |

Gradle consumers use unsuffixed coordinates and select the JVM/Android variant through Gradle module
metadata. JVM-only Maven consumers use the `-jvm` coordinates for protocol/core. Each publication
includes a POM, sources and a javadoc JAR (the JVM modules use an empty placeholder).
The Android AAR includes WebRTC consumer keep rules; OkHttp supplies its own consumer rules.

## Registry publishing

No registry upload is part of this git release. The retained Maven Central workflow is manual-only
and does not run on `v*` tag pushes. It still requires its existing release-version and signing
prerequisites; do not dispatch it as part of the JitPack checklist.
