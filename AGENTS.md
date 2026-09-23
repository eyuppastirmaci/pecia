# Project instructions

Pecia uses Java 21 and Maven. Keep indexing, storage, and search behavior in `pecia-core`; keep command parsing and terminal output in `pecia-cli`.

## Java code style

Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) for production code and tests, within Java 21 language support, with the indentation and line-length exceptions below. Key rules:

- Use UTF-8, spaces rather than tabs, and four-space block indentation. Four spaces are a project exception to Google's two-space indentation.
- Use K&R braces, including braces around single-statement control-flow bodies.
- Use a 120-character column limit instead of Google's 100-character limit, retaining the guide's documented exceptions. Let the formatter determine continuation indentation and line wrapping. Do not manually reformat formatter-produced code.
- Do not use wildcard imports. Put static imports first, then non-static imports, separated by one blank line. Sort names in each group in ASCII order, without additional import groups.
- Separate methods and other members with a blank line; adjacent fields may be grouped. Use blank lines within methods to separate logical steps, not mechanically after every opening brace.
- Keep overloads together, declare local variables near their first use, and use one statement per line.
- Use `UpperCamelCase` for classes, `lowerCamelCase` for methods and variables, and `UPPER_SNAKE_CASE` for constants.
- Follow the guide's public API Javadoc requirements and exceptions for self-explanatory members and overrides. Use traditional `/** ... */` Javadoc. Do not use `///` Markdown documentation comments because Pecia targets Java 21.
- Use `palantir-java-format` for Java source and test code with four-space indentation and a 120-character line width. Spotless enforces this formatting, import order, trailing-whitespace removal, and final newlines during `verify`. It covers only `src/main/java` and `src/test/java`; preserve the exact contents of test fixtures and other resources.
- Compile without warnings. The build uses `-Xlint:all` (except `serial`, because Pecia never uses Java serialization) with `-Werror`. Fix warnings instead of suppressing them; when suppression is unavoidable, apply it at the narrowest scope with a comment stating why.

### Record documentation

Keep record Javadocs concise: usually one or two summary sentences, plus only the component documentation needed to explain non-obvious meaning, units, nullability, or invariants. Do not repeat obvious component names or implementation details, but retain information callers need to use the record correctly.

### Readability and scope

- Use descriptive names and focused helpers for distinct responsibilities. Avoid unnecessary abstractions and deeply nested construction expressions.
- Choose explicit local types or `var` according to readability; keep the type understandable from the surrounding code.
- Write concise English comments explaining intent, constraints, or non-obvious decisions.
- Apply the style to new and changed code. Do not reformat unrelated code or perform a repository-wide style migration unless requested.

## Null validation

- Validate mandatory object fields in constructors so invalid objects cannot be created with null fields.
- Validate required inputs before filesystem, database, or other side effects.
- Avoid redundant `Objects.requireNonNull` calls immediately before dereferencing the same parameter or within already validated internal flows.
- Preserve fail-fast behavior and existing null contracts; retain checks that enforce an invariant or prevent failure after side effects.

## Verification

- Preserve Maven test discovery: use `*Test` for unit tests and `*IT` for packaged acceptance tests. The `*IT` suffix is a project exception to Google's test-class naming convention.
- Add or update meaningful tests when behavior changes. Use relevant existing tests for behavior-preserving refactors; documentation and formatting edits do not require new tests.
- Run verification from the repository root with JDK 21 and the Maven wrapper, never a system Maven. Maven Enforcer requires JDK 21 and the wrapper's Maven version because the packaged offline tests install a `SecurityManager`, which JDK 24+ cannot install. The shipped JAR still runs on Java 21+:

```sh
bash ./mvnw -B -ntp spotless:apply             # format Java sources and tests
bash ./mvnw -B -ntp test                       # unit tests
bash ./mvnw -B -ntp verify                     # unit and packaged acceptance tests, then formatting check
bash ./mvnw -B -ntp -pl pecia-core -am verify  # independent core verification
```

- Choose checks according to the change. Include packaged acceptance tests when changing CLI behavior, packaging, or public core integration; use `clean verify` when a clean build is needed.
- On macOS, add `-DargLine=-Djava.io.tmpdir=/private/tmp` to avoid the `/var` symlink conflicting with path-validation tests. On Windows, use `.\mvnw.cmd` instead of `bash ./mvnw`.
- Check whitespace with `git diff --check` and report which checks ran and their actual results. Do not rerun a passing suite without a relevant change or unresolved concern.
