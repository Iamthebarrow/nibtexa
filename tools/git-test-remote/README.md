# Git test remote

A throwaway Git smart-HTTP server for the sync tests in `:app`. It serves one
repository, `notes.git`, behind HTTP Basic auth with fixed test credentials:

- username `nibtexa`
- token `nibtexa-test-token-not-a-secret`

These are test values and protect nothing. Keep the server on the developer
machine. It uses plain HTTP, which only debug builds accept, and only for the
device's loopback address `127.0.0.1`.

Pinned contents: Alpine 3.22 by digest, git 2.49.1 (GPL-2.0, used as a
separate program) and lighttpd 1.4.85 (BSD-3-Clause). Nothing from the image
ships in the app.

## Run

From the repo root, in Git Bash:

```sh
docker build -t nibtexa-git-test-remote tools/git-test-remote
docker run -d --rm --name nibtexa-git-test-remote -p 18080:8080 nibtexa-git-test-remote
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" reverse tcp:18080 tcp:18080
```

The app then reaches it at `http://127.0.0.1:18080/git/notes.git`, and so does
the host. `adb reverse` lasts until the emulator or the adb server restarts,
so run it again after either.

Why not `10.0.2.2`: on the API 37 image, apps reach the network through the
emulated Wi-Fi, and from there `10.0.2.2` isn't the host. Only the shell's
`eth0` route reaches it. This was found with `run-as` and `nc` on
2026-10-27.

The instrumented sync tests skip themselves when the server can't be reached.
Each test run uses its own branch, so there's no need to reset the server
between runs. To start from an empty repository, stop the container (it's
started with `--rm`) and run it again.

```sh
docker stop nibtexa-git-test-remote
```
