package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuntimePlatformTest {

    @ParameterizedTest
    @CsvSource({
        "Linux, amd64, ai/onnxruntime/native/linux-x64/libonnxruntime.so",
        "Linux, aarch64, ai/onnxruntime/native/linux-aarch64/libonnxruntime.so",
        "Mac OS X, aarch64, ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib",
        "Darwin, x86_64, ai/onnxruntime/native/osx-x64/libonnxruntime.dylib",
        "Windows 11, amd64, ai/onnxruntime/native/win-x64/onnxruntime.dll",
        "Windows Server 2022, aarch64, ai/onnxruntime/native/win-aarch64/onnxruntime.dll"
    })
    void namesTheNativeLibraryAsTheRuntimeLooksForIt(String osName, String osArch, String library) {
        assertEquals(Optional.of(library), RuntimePlatform.nativeLibrary(osName, osArch));
    }

    @ParameterizedTest
    @CsvSource({"SunOS, sparcv9", "FreeBSD, amd64", "Linux, ppc64le", "Linux, arm", "Mac OS X, arm64"})
    void recognizesNoOtherPlatform(String osName, String osArch) {
        assertEquals(Optional.empty(), RuntimePlatform.nativeLibrary(osName, osArch));
    }

    @ParameterizedTest
    @CsvSource({"Linux, amd64", "Linux, aarch64", "Mac OS X, aarch64", "Windows 11, amd64"})
    void theRuntimeCarriesTheSupportedPlatforms(String osName, String osArch) {
        assertTrue(RuntimePlatform.bundled(osName, osArch));
    }

    @ParameterizedTest
    @CsvSource({"Mac OS X, x86_64", "Windows 11, aarch64", "Linux, ppc64le", "SunOS, sparcv9"})
    void theRuntimeLacksOtherPlatforms(String osName, String osArch) {
        assertFalse(RuntimePlatform.bundled(osName, osArch));
    }

    @Test
    void theCurrentTestPlatformIsSupported() {
        assertTrue(RuntimePlatform.bundled(System.getProperty("os.name"), System.getProperty("os.arch")));
    }
}
