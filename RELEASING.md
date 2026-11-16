# Releasing to Maven Central

No artifacts have been published yet. The PR workflow builds unsigned publications in Maven Local;
it never uploads to Central. A release tag pointing to a commit on `main` starts a signed Central
upload; publication requires a separate approval in the Central Portal.

## One-time setup

The repository owner must complete these steps before the first release:

1. Create a [Central Portal account](https://central.sonatype.com/) and register `ai.mobilerun`.
   Verify ownership of `mobilerun.ai` by adding the DNS TXT record requested by the portal.
2. Generate a password-protected GPG signing key (`gpg --full-generate-key`). Publish its **public**
   key to a supported keyserver, such as `keyserver.ubuntu.com`, so Central can verify signatures.
   Keep the private key and its password in a password manager.
3. Generate a Central Portal **user token**. Its username/password are publishing credentials,
   distinct from the account login.
4. Add the four values under Settings → Secrets and variables → Actions; never commit them.

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
   | `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
   | `SIGNING_IN_MEMORY_KEY` | Complete ASCII-armored private GPG key, including header/footer |
   | `SIGNING_IN_MEMORY_KEY_PASSWORD` | GPG private-key password |

5. Recommended: create a protected `maven-central` environment with a release-tag rule and a
   required reviewer. Set `environment: maven-central` on the upload job when that environment is
   configured, and store its publishing secrets there to require approval before upload.
6. Decide whether to make the repository public. Repository visibility is the owner's decision,
   not a Maven Central requirement. Ensure the published POM's project/license/SCM links are suitable.

Missing secrets cause the tag workflow to fail before any build or upload. Do not push a release tag
until namespace ownership and signing are ready.

## Each release

1. Set `VERSION_NAME` in `gradle.properties` to a release version such as `0.1.0` (no `-SNAPSHOT`).
   All modules use this single version. Update the README installation examples to match.
   Replace every pre-release statement in the release PR:
   - README: “Quick start (from source)” and “External apps can add the published artifact after
     the first release; until then, use this source checkout.”
   - README: “Status: pre-release” and the sentence ending “nothing is published.”
   - README: “Installation (after first release)” and “These coordinates are reserved for the
     first Maven Central release; they are not available yet.”
   - TESTING: “Publishing has not happened; use the source checkout.”
   - RELEASING: “No artifacts have been published yet.”
   Finalize the `CHANGELOG.md` 0.1.0 entry with the release date and review its feature list.
2. For deliberate public API changes, run `./gradlew apiDump`, inspect the dump diff and commit it
   with the change. `check` compares public signatures with the committed baseline.
   Declarations marked `InternalStreamApi` are excluded from this contract.
3. Run `./gradlew check publishToMavenLocal`.
   Inspect the artifacts under the Maven Local `ai/mobilerun` group. Merge the release-version PR
   only after CI and its unsigned publication dry run pass.
4. From the merged commit on `main`, create and push the matching tag:

   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```

   The workflow rejects a tag that differs from `VERSION_NAME` or contains a snapshot/prerelease
   version. It checks the API, tests and lint, signs all publications, then runs
   `./gradlew publishToMavenCentral` to upload and validate without automatically releasing.
5. Check the Actions run and inspect the validated deployment in the Central Portal, including its
   coordinates, signatures and artifacts. Press **Publish** in the Central Portal when satisfied.
   Central may take time to index the release; verify resolution from `mavenCentral()` before
   announcing it. Published versions are immutable; diagnose failed uploads before retrying.
   Later releases may switch to `publishAndReleaseToMavenCentral` for automatic publication.

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
