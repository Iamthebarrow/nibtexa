# Vendored web assets

Third-party files under `src/main/assets/mathedit/` are copied unmodified from
the npm registry tarballs. They are served only from the APK (see
[docs/design/MATH_BRIDGE.md](../../docs/design/MATH_BRIDGE.md)).

| Package | Version | Licence | Tarball SHA-256 | Files copied |
|---|---|---|---|---|
| [mathlive](https://www.npmjs.com/package/mathlive) | 0.111.1 | MIT (`mathlive/LICENSE.txt`) | `f285cd7b8ab5cbd67d792305c753dd4b6444f1799e012c2aef701eb87fe95f8b` | `mathlive.min.js`, `fonts/*.woff2` |

File hashes, as copied:

| File | SHA-256 |
|---|---|
| `mathlive/mathlive.min.js` | `51e9ab5c4739209a2951a550042dcecb99ad3047269c1d791d09d7d613226eae` |

Notes:

- MathLive's fonts are the KaTeX fonts and ship under MathLive's MIT licence;
  the package carries no separate font licence file.
- KaTeX 0.19.1 was vendored for the Phase 4 comparison and removed in Phase 5
  (ADR-0004).
- MathLive's sound files are **not** copied. Sounds are disabled in
  `mathlive-config.js`, so they are never requested.
- Only `woff2` fonts are copied; WebView supports `woff2`.
- Source for the minified files is the matching version tag in each upstream
  repository (`arnog/mathlive`).

To update: `npm pack mathlive@<version>` in an empty scratch
directory, check the licence files, copy the same files, and update this table
and the version in ADR-0004.
