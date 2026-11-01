# Third-party notice files

Licence and notice texts that the app shows on its "Open-source licences"
screen, because the shipped artifacts don't carry them. They're copied as
published, unmodified. See also [docs/THIRD_PARTY.md](../docs/THIRD_PARTY.md).

| File | Source (fetched 2026-10-27) | SHA-256 |
|---|---|---|
| `onnxruntime/LICENSE` | `https://raw.githubusercontent.com/microsoft/onnxruntime/v1.31.1/LICENSE` | `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c` |
| `onnxruntime/ThirdPartyNotices.txt` | `https://raw.githubusercontent.com/microsoft/onnxruntime/v1.31.1/ThirdPartyNotices.txt` | `143764b952fdb1a7c69ce653bfba74a7744d6a8a573bfb73e235fba356c83de3` |

| `jgit/LICENSE` (EDL 1.0) | `https://raw.githubusercontent.com/eclipse-jgit/jgit/v7.8.0.202609011348-r/LICENSE` | `10f5ea95859c008c924854e945ef92e49088ba9abb8c818b525b80d960170cf8` |
| `slf4j/LICENSE.txt` (MIT) | `META-INF/LICENSE.txt` in `slf4j-api-2.0.18.jar` | `4e7f90c86ab51278228bce153122f1d8df30149d13ce9ef524c8444a84c32dcc` |
| `apache/LICENSE-2.0.txt` | `https://www.apache.org/licenses/LICENSE-2.0.txt` | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |

| `nunito/OFL.txt` (SIL OFL 1.1) | `https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/OFL.txt` (fetched 2026-10-28) | `580df76c95a1ec5ab878ceb25bb3d85c6a076804e9c970c8c6972aea775fdf65` |

The font itself is `app/src/main/res/font/nunito.ttf`, from
`https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/Nunito%5Bwght%5D.ttf`
(fetched 2026-10-28, SHA-256
`bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207`), unmodified.

| `baloo2/OFL.txt` (SIL OFL 1.1) | `https://github.com/google/fonts/raw/main/ofl/baloo2/OFL.txt` (fetched 2026-10-29) | `ad09b05dc8bc678c9daf7c4c5f7ef1f55e5726127f4330b2e98e40b9dffcb860` |

The font itself is `app/src/main/res/font/baloo2.ttf`, the NibTeXa title on the
home screen, from
`https://github.com/google/fonts/raw/main/ofl/baloo2/Baloo2%5Bwght%5D.ttf`
(fetched 2026-10-29, SHA-256
`d47a6852548059b1db49a1319d06d499d546c3fa2237cf9eee9c43c8abb025c2`), unmodified.

Update these files together with the versions in
`gradle/libs.versions.toml`.

`mlkit/NOTICE.md` is not a copied text. NibTeXa wrote it: a summary of the ML Kit terms with links, because Google publishes those terms only on the web. Apache-2.0 also covers ML Kit's open dependencies.
