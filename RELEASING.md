# Releasing to Maven Central

No artifacts have been published yet. The PR workflow builds unsigned publications in Maven Local;
it never uploads to Central. Only pushing a release tag starts a signed Central release.

## One-time setup

The repository owner must complete these steps before the first release:

1. Create a [Central Portal account](https://central.sonatype.com/) and register `ai.mobilerun`.
   Verify ownership of `mobilerun.ai` by adding the DNS TXT record requested by the portal.
2. Generate a password-protected GPG signing key (`gpg --full-generate-key`). Publish its **public**
   key to a supported keyserver, such as `keyserver.ubuntu.com`, so Central can verify signatures.
   Keep the private key and its password in a password manager.
3. Generate a Central Portal **user token**. Its username/password are publishing credentials,
   distinct from the account login.
4. Add the following repository Actions secrets. Transfer each value directly through the clipboard
   into the secret field; never paste values into chat, terminal output, command arguments or files
   in the repository. Clear the clipboard after each transfer. Export the full ASCII-armored private
   signing key directly to the clipboard using a secure local export flow, without printing it.

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
   | `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
   | `SIGNING_IN_MEMORY_KEY` | Complete ASCII-armored private GPG key, including header/footer |
   | `SIGNING_IN_MEMORY_KEY_PASSWORD` | GPG private-key password |

5. Make the repository public and confirm the source, Apache-2.0 license and SCM URL are accessible.
   Review repository contents and history before changing visibility.

Missing secrets cause the tag workflow to fail before any build or upload. A tag is an instruction
to publish: do not push one until namespace ownership, signing and visibility are ready.

## Each release

1. Set `VERSION_NAME` in `gradle.properties` to a release version such as `0.1.0` (no `-SNAPSHOT`).
   All modules use this single version.
2. For deliberate public API changes, run `./gradlew apiDump`, inspect the dump diff and commit it
   with the change. `./gradlew apiCheck` compares the public signatures with the committed baseline.
   Declarations marked `InternalStreamApi` are excluded from this contract.
3. Run `./gradlew check apiCheck publishToMavenLocal --no-configuration-cache`.
   Inspect the artifacts under the Maven Local `ai/mobilerun` group. Merge the release-version PR
   only after CI and its unsigned publication dry run pass.
4. From the merged commit on `main`, create and push the matching tag:

   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```

   The workflow rejects a tag that differs from `VERSION_NAME` or contains a snapshot/prerelease
   version. It checks the API, tests and lint, signs all publications, then runs
   `./gradlew publishAndReleaseToMavenCentral --no-configuration-cache`.
5. Check the Actions run and Central Portal deployment. Central may take time to index the release.
   Verify resolution from `mavenCentral()` before announcing it. Published versions are immutable;
   diagnose a failed upload in the portal before retrying, and never overwrite a released version.
6. Set `VERSION_NAME` to the next development version ending in `-SNAPSHOT` in a follow-up PR.

## Coordinates and artifacts

| Module | Maven coordinate (append `:<version>`) | Packaging |
|---|---|---|
| `:protocol` | `ai.mobilerun:mobilerun-stream-protocol` | Kotlin Multiplatform metadata + `-jvm` JAR |
| `:stream-core` | `ai.mobilerun:mobilerun-stream-core` | Kotlin Multiplatform metadata + `-jvm` JAR |
| `:stream-android` | `ai.mobilerun:mobilerun-stream-android` | release AAR, minSdk 24 |
| `:stream-compose` | `ai.mobilerun:mobilerun-stream-compose` | release AAR, minSdk 24 |

Gradle consumers use the unsuffixed coordinates and select the JVM/Android variant through Gradle
module metadata. JVM-only Maven consumers use the `-jvm` coordinates for protocol/core. Each
publication includes a POM, sources and a javadoc JAR (the JVM modules use an empty placeholder).
The Android AAR includes WebRTC consumer keep rules; OkHttp supplies its own consumer rules.
The plugin uses the Central Portal API, not the retired OSSRH staging service.
