# Contributing to Pecia

## Building and testing

Building needs JDK 17 or newer to run Gradle; the build itself compiles and tests on JDK 21, which Gradle downloads if it is not installed. The Gradle Wrapper is included, so no Gradle installation is needed. The CLI JAR runs on Java 21+.

The project has two Gradle modules: `pecia-core` provides discovery, extraction, chunking, storage, embeddings, and search behind a Java API; `pecia-cli` provides the terminal commands and depends on `pecia-core`.

Run these commands from the repository root:

```
./gradlew test                  # run unit tests in both modules
./gradlew :pecia-cli:shadowJar  # build the CLI JAR without running tests
./gradlew build                 # run all tests and checks, including acceptance tests against the packaged JARs
./gradlew referenceTest         # check the real semantic model, through the core API and the CLI JAR
./gradlew :pecia-core:performanceTest  # measure indexing and query speed on a synthetic corpus
./gradlew :pecia-core:pitest           # run mutation testing on chunking, incremental indexing, and ranking
./gradlew :pecia-cli:packageArchive    # build this system's package in pecia-cli/build/package/dist
./gradlew :pecia-cli:packageTest       # extract that package and run it with no Java on PATH
```

On Windows use `gradlew.bat` instead of `./gradlew`.

`build` never downloads anything and uses a tiny test model. The other tasks run only on request:

| Task | Checks | Report |
| --- | --- | --- |
| `referenceTest` | The real model: embeddings, retrieval quality, and the CLI's semantic workflows | `pecia-core/build/reports/retrieval/` |
| `:pecia-core:performanceTest` | Indexing and query speed on a generated corpus; never fails on time | `pecia-core/build/reports/performance/index.md` |
| `:pecia-core:pitest` | Which code mutations the unit tests miss | `pecia-core/build/reports/pitest/` |

Retrieval quality is measured on a labelled corpus (`pecia-core/src/test/resources/retrieval`): lexical search in `test`, semantic and hybrid in `referenceTest`. A metric below its threshold fails the run.

`referenceTest` reads the model from `PECIA_CACHE_DIR` and may write there, so point it at a copy of your cache (see [Model cache](docs/cli.md#model-cache)). If no model is cached yet, `java -jar pecia-cli/build/libs/pecia.jar index --semantic <folder>` downloads it (about 90 MB) into the cache that the variable names.

```
export PECIA_CACHE_DIR=/tmp/pecia-reference-cache
mkdir -p "$PECIA_CACHE_DIR"
cp -R ~/Library/Caches/pecia/models "$PECIA_CACHE_DIR"   # macOS path
./gradlew referenceTest
```

A hanging test fails after five minutes with a thread dump in its XML report.

## Code style

[AGENTS.md](AGENTS.md) holds the project's rules: Java 21, the Google Java Style Guide with four-space indentation and a 120-character line limit, warnings as errors, and where tests go. `./gradlew spotlessApply` formats Java sources and tests; `./gradlew check` fails on unformatted code.

## Releases

Pushing a version tag such as `v0.2.0` runs the release workflow. It builds and checks the four platform packages, publishes the GitHub Release with their checksums, updates the Homebrew tap and the Scoop bucket, and then installs the release from both on fresh runners. Written notes in `docs/releases/<tag>.md`, when present, open the release description.

A tag with a suffix, such as `v0.2.0-rc.1`, publishes a pre-release. Pre-releases never reach the tap or the bucket; the install check uses the rendered formula and manifest instead. The Channels workflow can also be run by hand to check the published channels.
