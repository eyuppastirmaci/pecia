# Installing Pecia

Pecia packages bundle their own Java runtime, so no Java installation is needed. A package is about 40 MB to download and about 57 MB installed. Semantic search downloads its model (about 90 MB) on first use; see [Model cache](cli.md#model-cache).

| System | Package |
| --- | --- |
| macOS on Apple silicon | Homebrew, or the `osx-aarch64` archive |
| Linux x64 and arm64 (glibc) | Homebrew, or the `linux-x64` and `linux-aarch64` archives |
| Windows 10 version 1903 or newer, x64 | Scoop, or the `win-x64` archive |

Intel Macs have no package, because ONNX Runtime, which runs the semantic model, does not support them.

## Homebrew (macOS and Linux)

```
brew install eyuppastirmaci/pecia/pecia
```

Homebrew adds the tap `eyuppastirmaci/pecia`, checks the archive's SHA-256, and puts `pecia` on `PATH`. Upgrade with `brew upgrade pecia`; remove with `brew uninstall pecia`, then `brew untap eyuppastirmaci/pecia`.

## Scoop (Windows)

```
scoop bucket add pecia https://github.com/eyuppastirmaci/scoop-pecia
scoop install pecia
```

Scoop checks the archive's SHA-256 and puts `pecia` on `PATH`. Upgrade with `scoop update pecia`; remove with `scoop uninstall pecia`, then `scoop bucket rm pecia`.

## Archives

Each [release](https://github.com/eyuppastirmaci/pecia/releases) has one archive per system and a `.sha256` file beside it.

1. Download the archive and its `.sha256` file into one folder.
2. Check the archive: `shasum -a 256 -c pecia-<version>-<system>.tar.gz.sha256` on macOS, `sha256sum -c …` on Linux. On Windows, compare `(Get-FileHash pecia-<version>-win-x64.zip).Hash` in PowerShell with the file's first word.
3. Extract it anywhere you keep programs.
4. Add its `bin` folder to `PATH`. On Windows, add the folder that holds `pecia.exe`.

To remove Pecia, delete the folder and its `PATH` entry.

### macOS security prompts

The bundled Java runtime is signed and notarized by its vendor, the Eclipse Foundation. Native libraries that Pecia loads are unpacked by Pecia itself at run time, so files from a browser download that macOS marks as quarantined are only files that Gatekeeper accepts. Checked with a quarantined archive extracted by `tar`: every executable file passed `codesign --check-notarization`, and lexical and semantic search ran.

If macOS still blocks Pecia, remove the quarantine mark from the extracted folder:

```
xattr -dr com.apple.quarantine pecia-<version>-osx-aarch64
```

### Windows security prompts

`pecia.exe` is not signed. Windows SmartScreen may warn about unsigned programs downloaded from the internet. This was not reproduced for v0.1.0. If Windows blocks the extracted program, run `Unblock-File` on it in PowerShell, or open the zip's Properties and select **Unblock** before extracting.

## The JAR, with Java 21 or newer

A source build produces `pecia-cli/build/libs/pecia.jar`, which runs on any system with Java 21 or newer; see [CONTRIBUTING](../CONTRIBUTING.md#building-and-testing). Run it as `java -jar pecia.jar …` in place of `pecia`. On Intel Macs and other systems without a package, lexical search works this way; semantic search needs a system that ONNX Runtime supports.

On Windows, `java.exe` reads its arguments in the system's ANSI code page, so query characters outside it are replaced: under Western European settings `İstanbul` arrives as `?stanbul`. The packaged `pecia.exe` has no such limit. With the JAR, enable **Beta: Use Unicode UTF-8 for worldwide language support** under **Settings → Time & language → Language & region → Administrative language settings → Change system locale**, then restart Windows.

## Windows command hints

When a command needs another run first, Pecia prints it, for example `Run: pecia index --semantic '<folder>'`. Paths in these hints use single quotes, which PowerShell understands. In `cmd`, replace them with double quotes.
