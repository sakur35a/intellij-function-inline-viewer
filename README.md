# Function Inline Viewer

An IntelliJ IDEA plugin that lets you read a called function **in place**. A small gray hint follows every call to a function
defined in your project. Click it and the function's body unfolds right below the call; click again to fold it.

<!-- Add a screenshot or GIF here before publishing: docs/demo.gif -->

## Features

- **Unfold at the call site** – no popup, no navigation. Library/JDK calls are ignored, only project sources are shown.
- **Nested unfolding** – calls inside an unfolded body have their own hints (max depth configurable, default 4).
- **Highlighted code** – lexer colors plus semantic colors (parameters, fields, methods, classes), Cmd/Ctrl+click to navigate.
- **Interfaces and overrides** – unfold an abstract or overridable method and load its implementations/overrides on demand
  (paged with `next N` / `all`).
- **Method chains** `a.foo().bar()` on one line collapse into one hint; multiple calls on a line get distinct colors
  (Settings > Editor > Color Scheme > Function Inline Viewer).
- **Kotlin** – top-level and extension functions, lambdas, and property access with custom `get()`/`set()`.
- **Fold everything** – `Ctrl+Cmd+-` (macOS) / `Ctrl+Alt+-` folds every unfolded body in the editor (also in the editor's context menu).
- **Live** – unfolded bodies refresh when you edit the caller or the callee and are restored after a restart.

## Requirements

IntelliJ IDEA 2026.2 (build 262) or later, with the Java plugin. Kotlin support is optional.

## Usage

Click the `▶ name(params)` hint after a call. Settings, including switching the hints off, live under
**Settings > Editor > Function Inline Viewer**. Unfolded bodies are restored when a file is reopened.

## Building

```sh
./gradlew buildPlugin        # build/distributions/*.zip
./gradlew test               # tests
./gradlew runIde             # sandbox IDE opening ./sample
./gradlew verifyPlugin       # Plugin Verifier
```

Performance measurement: `./gradlew runIde -Pperf -PopenProject=<path>` records `perf` debug logs and a JFR file under
`build/perf/`; summarize the log with `scripts/perf-summary.py`.

## Verification

`./gradlew verifyPlugin` runs the Plugin Verifier against IntelliJ IDEA 2026.2 and fails on compatibility problems and on any
use of internal, deprecated or experimental API. The plugin uses public API only: the call hints are inline inlays added by a
highlighting pass (`CallHints`), not the platform's declarative inlay hints, whose state cannot be read through public API.

## Versioning and releases

Commits follow [Conventional Commits](https://www.conventionalcommits.org/). The version is computed by
[git-semver-plugin](https://github.com/jmongard/Git.SemVersioning.Gradle) from the latest `vX.Y.Z` tag and the commits after
it: `fix:` bumps the patch, `feat:` the minor, `feat!:` / `BREAKING CHANGE:` the major version. Builds between releases get a
`-SNAPSHOT` suffix. The plugin's change notes are generated at build time from the `feat:`, `fix:` and `perf:` commits since
the previous release tag, so `plugin.xml` has no `<change-notes>`.

```bash
./gradlew printVersion      # version of the current checkout
./gradlew printChangeLog    # changes since the last release
./gradlew release           # "release: vX.Y.Z" commit + vX.Y.Z tag
./gradlew buildPlugin       # then build the release zip
git push --follow-tags
```

`release` runs the `git` command, so commit signing (`commit.gpgsign`) works through gpg-agent. The plugin's own
`releaseVersion` commits through JGit, which cannot use gpg-agent and fails when commits are signed.

Pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml`: it checks that the computed version matches the tag, runs the
tests and the Plugin Verifier, publishes to JetBrains Marketplace, and creates a GitHub release with the zip. Repository
secrets:

| Secret | |
|---|---|
| `PUBLISH_TOKEN` | Marketplace token (profile → My Tokens) |
| `CERTIFICATE_CHAIN` | Plugin signing certificate chain, Base64 (`base64 < chain.crt`) — optional |
| `PRIVATE_KEY` | Encrypted signing key, Base64 (`base64 < private_encrypted.pem`) — optional |
| `PRIVATE_KEY_PASSWORD` | Password of the signing key — optional |

Without the signing secrets the plugin is published unsigned. To create a key and a self-signed certificate
([Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Keep the key files out of the repository.

The certificate expires after the `-days` given above (check with `openssl x509 -in chain.crt -noout -enddate`); the key
and its password do not. To renew, create a new certificate from the same key and replace only the `CERTIFICATE_CHAIN`
secret:

```bash
openssl rsa -in private_encrypted.pem -out private.pem   # only if private.pem was deleted
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
base64 < chain.crt                                       # new value of CERTIFICATE_CHAIN
```

Versions published before the certificate expired are not affected.

## License

[Apache License 2.0](LICENSE)
