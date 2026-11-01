# ADR-0006: Git sync

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

The README promises that `.tex` exports can be committed and pushed to any
Git-compatible host. That requires:

- explicit user control over destination, content and push;
- never overwriting remote work silently;
- an offline notes workflow;
- secrets that are never logged, exported or committed.

The v0.1 proposal added safety rules:

- commit only paths the app manages;
- use a dedicated branch or folder;
- never force-push;
- keep the export locally if the remote moved on;
- ask the user about generated files that were edited elsewhere.

## Decision

### Library: JGit 7.8.0

| | |
|---|---|
| Artifact | `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r`, the latest release on Maven Central (2026-09-01), checked on 2026-10-27 |
| Licence | Eclipse Distribution License 1.0, `SPDX-License-Identifier: BSD-3-Clause`, from the release's parent POM. Compatible with GPL-3.0. |
| Transitive dependencies | JavaEWAH (Apache-2.0), slf4j-api (MIT), commons-codec (Apache-2.0) |
| Bytecode | Java 17 (`java.version` 17 in the parent POM) |
| Android | Works on the API 37 AVD. Clone, fetch, commit, push and rebase all ran against a real smart-HTTP server (`SyncTest`). minSdk 26 itself is **not tested**: there's no API 26 image. |

Alternatives:

- **libgit2 through JNI:** rejected. It means a native build for every ABI,
  and the project rule is "no native code without a measured need".
- **A host's REST API:** rejected. It breaks "any Git host".
- **Running the `git` binary:** rejected. Android doesn't ship it.

### Module: `:core:sync`

A pure Kotlin JVM module. `GitSync` holds the Git work and the pure `Planner`
holds the safety rules. Both are tested on the JVM against local bare
repositories.

### The working copy is bare, and commits are built from objects

The app keeps one bare repository in `noBackupFilesDir/git`. It never checks
out files. To commit, the app:

1. reads the base tree;
2. replaces blobs under the booklet folder with the export's bytes;
3. writes a tree and a commit;
4. updates the local branch.

Consequences:

- The repository's `.gitattributes` (line endings), `.gitignore` and the
  device's file system can't change what is committed. Exported bytes are
  committed exactly, which the hash record below relies on. Tested with CRLF
  text and a `.gitignore` that ignores `*.png`.
- There are no checkout, hardlink or symlink problems on Android.
- Before writing a commit, the app checks that it changes nothing outside the
  booklet folder (`assertOnlyChanged`).

### Layout and ownership

- Each booklet goes to `<destination>/<booklet-id>/` on `<branch>`. The
  defaults are folder `nibtexa` and branch `nibtexa-export`. A branch that
  doesn't exist yet starts from the remote's default branch, or from an empty
  history if the remote has none.
- `<booklet-folder>/.nibtexa-sync.json` records the SHA-256 of every file the
  app wrote, and the files the user chose to keep. It is deterministic, so an
  unchanged booklet commits nothing.
- Each sync compares the remote branch with that record:
  - A file the app wrote and nobody changed may be replaced or deleted.
  - A file the app wrote that was **edited** or **deleted** elsewhere is a
    conflict.
  - A file the app is about to write, that exists already and that the app
    didn't write, is also a conflict.
  - Any other file in the folder is left alone.
- Conflicts are resolved by the user, for all conflicting files at once:
  - **Keep:** their version stays, and the app stops generating the file.
  - **Regenerate:** the app's version replaces theirs.
  - **Copy:** their version stays, and the app writes its own as
    `name.nibtexa.ext` beside it.
  The default is Keep. Kept files stay kept until the user ticks "Generate
  the kept files again".
- The optional `source/booklet.json` (`BookletJson`: pages, ink and regions,
  deterministic) is off by default.

### Pushing

- The refspec is `refs/heads/<b>:refs/heads/<b>`, never forced.
- The app fetches before it plans, so the commit is built on the current
  remote tip.
- If the push is still rejected (someone pushed in between), the commit
  **stays local** and the user is told. The app offers **Rebase and push**
  only when none of the new remote commits touch `<destination>`. Otherwise
  the user can only discard the local commit; the notebook is untouched, and
  the next sync recreates the export on top of the remote.
- A commit that was never pushed, for example because the app died, is found
  on the next start and shown with Push and Discard.

### Credentials and network

- HTTPS with a username and a personal access token. SSH is out of scope.
- The token is encrypted with an AES-256-GCM key that is created in, and
  never leaves, the Android Keystore. Only the ciphertext is stored, in
  `noBackupFilesDir`, and app backup is disabled anyway.
- The token is never put in URLs: a URL with credentials in it is refused. It
  never reaches the working copy's config or a log line. Error text passes
  through `Redactor`.
- JGit is isolated from user and system Git config. A custom `SystemReader`
  returns empty user, system and JGit configs.
- The app pins `http.sslVerify=true` in the working copy and removes every
  `[http]` subsection. JGit's trust-all manager, flagged by lint
  `TrustAllX509TrustManager` and suppressed for the JGit jar only in
  `app/lint.xml`, is reachable only when that setting is false.
- The manifest gains `INTERNET`.
- The network security config:
  - **release:** HTTPS only, system certificate authorities only;
  - **debug:** also plain HTTP to `127.0.0.1`, for the test remote reached
    through `adb reverse`.
  The URL policy matches it: release builds accept only `https`.

### Test remote

`tools/git-test-remote/` builds a Docker image:

- Alpine 3.22, pinned by digest;
- git 2.49.1 with `git-http-backend`;
- lighttpd 1.4.85 with Basic auth;
- fixed, published test credentials.

It is reached from the AVD through `adb reverse tcp:18080 tcp:18080`. Tests
never use a real user repository.

## Consequences

- Notes, recognition and export remain offline. Only an explicit sync uses the
  network.
- The math WebView no longer gets defense in depth from a missing `INTERNET`
  permission. Its own controls (`blockNetworkLoads`, the request filter and
  the CSP) now carry it alone. They were re-tested with the permission
  granted (`docs/design/MATH_BRIDGE.md`).
- **API 37 blocks network access for apps in the background** (netpolicy
  `APP_BACKGROUND`). A sync interrupted by leaving the app fails and keeps
  any local commit for later; it never leaves a half-pushed state. Running
  sync in a foreground service is a possible Phase 8 improvement.
- Debug APK size: 94.3 MB, up from about 92 MB in Phase 5.
- A rebase only happens if the destination folder is untouched remotely. Any
  concurrent change to the folder means discarding the local commit and
  syncing again.

## Not verified

- minSdk 26: no API 26 image was available.
- Real hosts other than GitHub (GitLab, Gitea, Codeberg). **GitHub was
  verified in Phase 8** (`RealHostSyncTest`), over HTTPS with a fine-grained
  token: first push, nothing to commit, a rejected push and rebase, a wrong
  token, and no token leak.
- Large repositories. The app fetches only the one branch, but with full
  history.
- Behaviour on hardware.
