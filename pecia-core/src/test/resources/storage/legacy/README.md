# Legacy index fixtures

Each `schema-vN.db` is a SQLite index written by the code that produced schema version N, so migration tests start from real data instead of hand-written SQL. The files are binary and must never be edited: to cover a new version, add a new fixture.

**Before any schema change, add a fixture for the outgoing version** using the generator below, so the next migration is tested against data the old code actually wrote.

## Fixtures

All four fixtures index the same corpus (7 files, 14 chunks) with the default configuration.

| File | Schema / format | Written by | Size (bytes) | SHA-256 |
| --- | --- | --- | --- | --- |
| `schema-v1.db` | 1 / 1 | `8b56827` | 49152 | `3cb9fbbbe3305c7f9b82de6bedd168002b91ae628a50addba39a84d84731017b` |
| `schema-v2.db` | 2 / 2 | `e180141` | 86016 | `e91d44ba9b6e94175381e165a097fee6dbec6ecc8d8275a498624e38ced8ce89` |
| `schema-v3.db` | 3 / 3 | `5b55a5a` | 94208 | `c63c4c702d925ebf7dc65fffefaeff194c27bf4dd80a57547abe8955753662bd` |
| `schema-v4.db` | 4 / 4 | `596166d` | 118784 | `6b004fcc4c0a25f54b75b6e39f3a08c7998297295a4c4f9acefe937dedca6120` |

What each version adds:

- **V1:** files, chunks, chunk headings and attributes, and index metadata.
- **V2:** the `chunks_fts` full-text index.
- **V3:** `file_indexing_profiles`, one row per file.
- **V4:** `file_chunking_profiles` and `chunk_identities`, one identity per chunk; legacy indexing profiles are no longer written.

Each `schema-vN.sql` is a reviewable dump of its fixture, written by `generator/dump-fixture.py`: the schema in name order and every ordinary table's rows in primary-key order. FTS5 shadow tables are omitted because they are binary structures derived from `chunks`. Regenerate a dump whenever its fixture is added, and review it instead of the binary.

## Corpus

The corpus is the shared lexical test corpus (`dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus`): Markdown with nested headings and a fence, plain text, Java source with CRLF endings, a shell script with a UTF-8 BOM, YAML, an empty file, and a long multi-chunk document. Migration tests recreate the same files with that class and check that the stored content hashes match them, so changing the corpus requires regenerating every fixture.

## Project root

`index_metadata.project_root_uri` stores the absolute project root of the machine that wrote the index. These fixtures were written in `/tmp/pecia-legacy-fixture/vN/project` (stored as `file:///private/tmp/...` on macOS). Tests copy a fixture into a temporary project and rewrite only this value on the copy; the committed files are never opened directly.

## Regenerating

The fixtures were generated on macOS arm64 with Temurin 21.0.12.1 and sqlite-jdbc 3.53.4.0. Generating each fixture twice in the same directory produced byte-identical files, so a fixture regenerated under the same conditions must match the SHA-256 above. Tests verify these checksums before using a fixture.

1. Export the writing commit and build its core with runtime dependencies:

   ```sh
   git archive <commit> | tar -x -C <build>
   cd <build> && bash ./mvnw -B -ntp -pl pecia-core -am -DskipTests package
   bash ./mvnw -B -ntp -pl pecia-core dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=<deps>
   ```

2. Write the corpus with the current test classes (after `test-compile` in this repository):

   ```sh
   java -cp pecia-core/target/test-classes generator/WriteCorpus.java /tmp/pecia-legacy-fixture/vN/project
   ```

3. Index it with that commit's code. `8b56827` predates `IndexService.index`, so V1 uses its own generator:

   ```sh
   java -cp "<build>/pecia-core/target/pecia-core-0.1.0-SNAPSHOT.jar:<deps>/*" generator/GenerateV1Index.java /tmp/pecia-legacy-fixture/v1/project
   java -cp "<build>/pecia-core/target/pecia-core-0.1.0-SNAPSHOT.jar:<deps>/*" generator/GenerateIndex.java /tmp/pecia-legacy-fixture/vN/project
   ```

4. Copy `/tmp/pecia-legacy-fixture/vN/project/.pecia/index.db` to `schema-vN.db`, write its dump with `python3 -I generator/dump-fixture.py schema-vN.db > schema-vN.sql`, and record the commit, size, and SHA-256 in the table above.
