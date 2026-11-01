# Security

Please report a security problem privately, through [GitHub's private vulnerability reporting](https://github.com/Iamthebarrow/nibtexa/security/advisories/new), rather than in a public
issue. Say what the problem is and how to reproduce it. This is a one-person project, so there's no promised response time, but reports are taken seriously.

What NibTeXa is designed to protect:

- **Git credentials:** the access token is encrypted with the Android Keystore, and is never put in a backup or export.
- **Imported files:** backups, LaTeX and Markdown projects are checked before anything changes, and nothing in them is run.
- **The math editor:** a locked-down local WebView, described in [docs/design/MATH_BRIDGE.md](docs/design/MATH_BRIDGE.md).

Backups aren't encrypted, so they're as private as wherever they are kept.
