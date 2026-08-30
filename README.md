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

## License

[MIT](LICENSE)
