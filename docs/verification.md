# Verification of v0.1.0

What was checked before Pecia v0.1.0 was released, on which systems, and with what results. Every pull request and every release runs these checks again in GitHub Actions; their results are in the repository's Actions tab.

## Platforms

| System | Package | Full test suite | Package checks | Installed through a channel | By hand |
| --- | --- | --- | --- | --- | --- |
| macOS, Apple silicon | `osx-aarch64` | CI and locally (macOS 26) | CI and locally | Homebrew, CI | Homebrew install and `brew test` |
| Linux x64 | `linux-x64` | CI (Ubuntu) | CI | Homebrew, CI | — |
| Linux arm64 | `linux-aarch64` | — | CI | — | — |
| Windows x64 | `win-x64` | CI | CI | Scoop, CI | — |
| macOS, Intel | none | — | — | — | — |

- **Full test suite:** `./gradlew check referenceTest`, every unit and packaged acceptance test, including the real model.
- **Package checks:** `packageTest` extracts the archive into an empty folder and runs the bundled launcher with no Java on `PATH`: `--version`, `--help`, offline lexical indexing and search with an empty model cache that stays empty, semantic and hybrid search, the licences, and only this platform's native libraries. On Windows it also checks that a query of characters outside every ANSI code page (`İstanbul ödeme 漢字 😀`) arrives intact. Release runs also download the model over HTTPS with the bundled runtime.
- **Installed through a channel:** the Channels workflow installs the release with Homebrew or Scoop on a fresh runner, runs `brew audit --strict` and `brew test` where Homebrew is used, checks `pecia --version`, and indexes and searches a file.
- CI uses GitHub's `macos-latest`, `ubuntu-latest`, `ubuntu-24.04-arm`, and `windows-latest` runners.

Not verified: Intel Macs (no package; the JAR runs lexical search with Java 21+), Linux distributions with musl such as Alpine, Windows on Arm, Windows versions older than the runner's, and the SmartScreen prompt for a browser download.

## Tests

Results of `./gradlew clean check --rerun-tasks` and `referenceTest` on the release commit, on macOS (Apple silicon) with JDK 21:

| Suite | Tests | Result |
| --- | --- | --- |
| Core unit tests | 2,725 | passed, 4 skipped on this system |
| Core packaged acceptance tests (the core JAR alone) | 23 | passed |
| CLI unit tests | 266 | passed |
| CLI packaged acceptance tests (`java -jar`, offline) | 50 | passed |
| Real-model tests, core (`referenceTest`) | 36 | passed |
| Real-model tests, CLI (`referenceTest`) | 6 | passed |
| Package checks (`packageTest`) | 5 | passed |

`./gradlew :pecia-core:check` also passes on its own, without building the CLI.

## Retrieval quality

40 labelled queries over a 60-file corpus (`pecia-core/src/test/resources/retrieval`), in five kinds of eight queries each. A metric below its committed threshold fails the build. The values are the same on Linux, macOS, and Windows.

| Mode | Recall@5 | Recall@10 | MRR | nDCG@10 |
| --- | --- | --- | --- | --- |
| Lexical | 0.388 | 0.388 | 0.338 | 0.342 |
| Semantic | 0.925 | 0.975 | 0.770 | 0.807 |
| Hybrid | 0.950 | 0.975 | 0.797 | 0.828 |

By kind, hybrid search reaches an MRR of 0.750 on identifiers, 0.875 on keywords, 0.833 on paraphrases, 0.917 on mixed queries, and 0.608 on Turkish ones. Lexical search finds every identifier in the top 5 but no paraphrase, which is why `auto` uses hybrid search when embeddings exist.
