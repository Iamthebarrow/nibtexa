# Secrets scan

[gitleaks](https://github.com/gitleaks/gitleaks) (MIT) over the working
tree, with its default rules. [gitleaks.toml](gitleaks.toml) skips only build
outputs, caches, the local Python venv and the vendored minified MathLive.

The scan runs offline and locked down. Nothing is installed on the host, and
the tool can't change the repo:

1. Download the release binary and check it against the release's checksums.
   Keep the download in a folder of its own:

   ```sh
   V=8.30.1
   D=../nibtexa-tools/gitleaks-$V && mkdir -p "$D/bin" && cd "$D"
   B=https://github.com/gitleaks/gitleaks/releases/download/v$V
   curl -sLO "$B/gitleaks_${V}_linux_x64.tar.gz" && curl -sLO "$B/gitleaks_${V}_checksums.txt"
   grep linux_x64.tar.gz gitleaks_${V}_checksums.txt | sha256sum -c -   # must print OK
   tar -xzf gitleaks_${V}_linux_x64.tar.gz -C bin && cd -
   ```

   On 2026-10-27, v8.30.1 `linux_x64` was
   `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`.

2. Run it in the pinned Alpine image, from the repo root in Git Bash. The
   container has no network, a read-only file system, no capabilities and an
   unprivileged user, and the repo is mounted read-only:

   ```sh
   MSYS_NO_PATHCONV=1 docker run --rm --network none --read-only --cap-drop ALL \
     --security-opt no-new-privileges --user 65534:65534 \
     -v "$(cygpath -w "$PWD"):/scan:ro" \
     -v "$(cygpath -w "$PWD/$D/bin"):/tool:ro" \
     -v "$(cygpath -w "$PWD/tools/secrets-scan"):/cfg:ro" \
     alpine@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f \
     /tool/gitleaks dir /scan --config /cfg/gitleaks.toml --no-banner --redact=100 --exit-code 1
   ```

`dir` mode scans files, not history. The history so far is 5 commits of
documentation, and those files are all in the working tree. Once the code is
committed, also run `gitleaks git` on a clone.

Known test values, which are not secrets:

- the Git test remote's fixed credentials (`tools/git-test-remote/`);
- made-up tokens in the sync tests. The one gitleaks flags carries an inline
  `gitleaks:allow` marker.

Result, 2026-10-27: no leaks found in about 941 KB.
