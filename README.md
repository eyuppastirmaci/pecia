# Pecia

Pecia is a local command-line tool that turns a folder of documents and source code into a searchable vector index. It walks a directory, splits text into coherent chunks, embeds them with a small model running in the same process, and stores everything in a single SQLite file next to your project.

## Status

**Early development.**

## Building and testing

Requires JDK 21+. The Maven Wrapper is included, so no Maven installation is needed.

```
./mvnw test           # run the test suite
./mvnw clean package  # build the runnable JAR at target/pecia.jar
```

On Windows use `.\mvnw.cmd` instead of `./mvnw`.

## Commands

```
java -jar target/pecia.jar --help                # list commands
java -jar target/pecia.jar --version             # print version
java -jar target/pecia.jar init                  # write a default .pecia.toml into the current directory
java -jar target/pecia.jar index . --dry-run     # list the files that would be indexed, without embedding
```

## Bundled tokenizer assets

Offline token counting uses the 30,522-entry `vocab.txt` from [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41), bundled in the JAR (about 226 KiB). The vocabulary is pinned and SHA-256-verified; token counting requires no model download or network access.

The [tokenizer asset directory](src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/) includes `NOTICE.txt` with the source, revision, and checksum, and `LICENSE.txt` with the Apache 2.0 license for the vocabulary.

## License

Pecia's own code is licensed under [MIT](LICENSE). The bundled vocabulary is licensed separately under [Apache 2.0](src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/LICENSE.txt).
