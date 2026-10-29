package dev.eyuppastirmaci.pecia.semantic;

import java.util.Locale;
import java.util.Optional;

/** Finds the ONNX Runtime native library for a platform without loading any ONNX Runtime class. */
final class RuntimePlatform {

    static final String SUPPORTED = "Linux on x64 or arm64, macOS on Apple silicon, and Windows on x64";

    private RuntimePlatform() {}

    /**
     * Returns the resource path of the runtime's native library for a platform, detecting the operating system and
     * architecture as ONNX Runtime does, or nothing for a platform it does not recognize.
     */
    static Optional<String> nativeLibrary(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ENGLISH);
        String arch = osArch.toLowerCase(Locale.ENGLISH);
        String directory;
        String library;

        if (os.contains("mac") || os.contains("darwin")) {
            directory = "osx";
            library = "libonnxruntime.dylib";
        } else if (os.contains("win")) {
            directory = "win";
            library = "onnxruntime.dll";
        } else if (os.contains("nux")) {
            directory = "linux";
            library = "libonnxruntime.so";
        } else {
            return Optional.empty();
        }

        if (arch.startsWith("amd64") || arch.startsWith("x86_64")) {
            directory += "-x64";
        } else if (arch.startsWith("aarch64")) {
            directory += "-aarch64";
        } else {
            return Optional.empty();
        }

        return Optional.of("ai/onnxruntime/native/" + directory + "/" + library);
    }

    /** Returns whether the runtime on the classpath carries its native library for the platform. */
    static boolean bundled(String osName, String osArch) {
        return nativeLibrary(osName, osArch)
                .map(library -> RuntimePlatform.class.getClassLoader().getResource(library) != null)
                .orElse(false);
    }
}
