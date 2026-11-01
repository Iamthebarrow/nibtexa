# Contributing

Bug reports and suggestions are welcome: please [open an issue](https://github.com/Iamthebarrow/nibtexa/issues). Say which tablet and Android version you're using, and
what you expected to happen. Don't attach notes you wouldn't want made public.

## Pull requests

By sending a pull request, you agree that your contribution is licensed on the same terms as the project: GPL-3.0 with the additional permission in [NOTICE.md](NOTICE.md).

Before sending one:

```sh
./gradlew assembleDebug :core:maths:test :core:model:test :core:export:test :core:recognition:test :core:sync:test testDebugUnitTest lintDebug
```

- Every new dependency, model and dataset must have a licence that works with the above, and be added to [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
- The core of the app must keep working offline.
- Never commit secrets, signing keys, local paths or build outputs. The scan is in [tools/secrets-scan/README.md](tools/secrets-scan/README.md).
- Third-party data, such as handwriting datasets, stays out of the repository.
- Changes to the math editor's WebView or bridge must keep [docs/design/MATH_BRIDGE.md](docs/design/MATH_BRIDGE.md) true.

Start with the [documentation index](docs/README.md), [Architecture](docs/development/ARCHITECTURE.md) and [Testing](docs/development/TESTING.md).
