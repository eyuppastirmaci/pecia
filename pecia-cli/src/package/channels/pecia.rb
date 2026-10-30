# Homebrew formula for the eyuppastirmaci/pecia tap, rendered by the release workflow for each version.
class Pecia < Formula
  desc "Offline lexical and semantic search for documents and source code"
  homepage "https://github.com/eyuppastirmaci/pecia"
  version "${version}"
  license "MIT"

  on_macos do
    # ONNX Runtime has no Intel macOS build, so only Apple silicon has a package.
    depends_on arch: :arm64

    url "https://github.com/eyuppastirmaci/pecia/releases/download/v${version}/pecia-${version}-osx-aarch64.tar.gz"
    sha256 "${sha256_osx_aarch64}"
  end

  on_linux do
    on_intel do
      url "https://github.com/eyuppastirmaci/pecia/releases/download/v${version}/pecia-${version}-linux-x64.tar.gz"
      sha256 "${sha256_linux_x64}"
    end

    on_arm do
      url "https://github.com/eyuppastirmaci/pecia/releases/download/v${version}/pecia-${version}-linux-aarch64.tar.gz"
      sha256 "${sha256_linux_aarch64}"
    end
  end

  def install
    # The package bundles its own Java runtime; its launcher finds it through this link.
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/pecia"
  end

  test do
    assert_match "pecia #{version}", shell_output("#{bin}/pecia --version")

    (testpath/"refunds.md").write "# Refunds\n\nMoney goes back to the original card.\n"
    system bin/"pecia", "init"
    system bin/"pecia", "index", "--progress=none"
    assert_match "refunds.md", shell_output("#{bin}/pecia query refunds")
  end
end
