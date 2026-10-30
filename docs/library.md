# Using pecia-core from Java

`pecia-core` holds everything the CLI does (discovery, chunking, storage, embeddings, and search) behind a Java 21 API, so another program can index and search a folder without starting the CLI. It never writes to standard output or error and never exits the JVM; the caller owns presentation and process lifetime.

v0.1.0 does not publish `pecia-core` to a Maven repository, and its API may change before 1.0.

## Adding it to a Gradle build

Clone Pecia next to your project and include its build. Gradle then builds `pecia-core` from source and brings in its dependencies:

```kotlin
// settings.gradle.kts
includeBuild("../pecia")
```

```kotlin
// build.gradle.kts
repositories {
    mavenCentral() // for pecia-core's own dependencies
}

dependencies {
    implementation("dev.eyuppastirmaci:pecia-core")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}
```

`pecia-core` reads `.gitignore` files with JGit, which logs through SLF4J. Without an SLF4J binding in your program, SLF4J prints a three-line notice on the first run; add one, such as `org.slf4j:slf4j-nop`, to silence it.

`./gradlew :pecia-core:javadoc` in the Pecia repository builds the API documentation into `pecia-core/build/docs/javadoc`.

## Indexing and searching

This example is the same code that `LibraryGuideTest` runs:

```java
PeciaConfigLoader configLoader = new PeciaConfigLoader(new PeciaConfigParser());
new PeciaConfigFile().writeDefault(project); // like `pecia init`; keeps an existing .pecia.toml

IndexResult indexed = new IndexService(configLoader).index(project);
System.out.println(indexed.status() + ": " + indexed.indexedFiles() + " files indexed");

QueryResult found = new QueryService(configLoader).query(project, new SearchRequest("refund policy", 5));
for (SearchHit hit : found.hits()) {
    System.out.println(hit.sourcePath() + " (" + hit.score().value() + "): " + hit.snippet());
}
```

`project` is the folder to index. The services follow the CLI's rules: the project is found from the nearest `.pecia.toml` or Git root, the index is `.pecia/index.db` there, and settings come from `.pecia.toml` (see [Configuration](cli.md#configuration)).

## Options and results

| To | Use |
| --- | --- |
| Turn semantic indexing on or off for one run | `index(project, IndexOptions.semantic(true))` |
| Follow progress, or stop a run | `IndexOptions.withProgressListener(…)`, `IndexOptions.withCancellation(…)` |
| See what a run would change, writing nothing | `IndexService.preview(project)` |
| Search in one mode | `query(project, request, QueryOptions.mode(SearchMode.HYBRID))` |

Results are records. `IndexResult` reports counts, a `status` (`COMPLETE`, `PARTIAL`, or why the run stopped early, such as `CANCELLED` or `MODEL_UNAVAILABLE`), and every file or folder that was left out with its reason. `QueryResult` holds the hits, the mode that ran, why `auto` fell back to lexical search, and notes such as a query cut to the model's input. Each `SearchHit` has the path, line range, score, heading path, snippet, match ranges, and a stable chunk ID.

Failures are exceptions with a reason the caller can act on: `IndexException` and `QueryException` for the index, `IOException` for unreadable paths, and `IllegalArgumentException` for invalid settings or requests.

## Semantic search

The first semantic run downloads the model into the [model cache](cli.md#model-cache), which `PECIA_CACHE_DIR` can move. Queries never download it. The dependency carries ONNX Runtime for every supported platform; semantic search runs on Linux x64 and arm64, macOS on Apple silicon, and Windows x64, and lexical search wherever Java 21 runs. Each semantic or hybrid query loads the model, which takes about a second.
