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
- **Live** – unfolded bodies refresh when you edit the caller or the callee and are restored after a restart.

## Requirements

IntelliJ IDEA 2026.2 (build 262) or later, with the Java plugin. Kotlin support is optional.

## Usage

Click the `▶ name(params)` hint after a call. Settings live under **Settings > Editor > Function Inline Viewer**;
the hint can be toggled under **Settings > Editor > Inlay Hints > Other**.

## Building

```sh
./gradlew buildPlugin        # build/distributions/*.zip
./gradlew test               # tests
./gradlew runIde             # sandbox IDE opening ./sample
./gradlew verifyPlugin       # Plugin Verifier
```

Performance measurement: `./gradlew runIde -Pperf -PopenProject=<path>` records `perf` debug logs and a JFR file under
`build/perf/`; summarize the log with `scripts/perf-summary.py`.

## Notes on internal APIs

The editor's declarative hints offer no public way to identify a hint's provider or to read which arrow (▶/▼) is displayed,
so `DeclarativeHint.kt` reads a few `@ApiStatus.Internal` classes (read-only). All internal API use is confined to that file
and degrades gracefully if the classes change.

## License

[Apache License 2.0](LICENSE)
