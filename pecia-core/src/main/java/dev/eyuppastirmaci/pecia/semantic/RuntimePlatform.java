package dev.eyuppastirmaci.pecia.semantic;

import java.util.Locale;
import java.util.Optional;

/** Finds the ONNX Runtime native library for a platform without loading any ONNX Runtime class. */
final class RuntimePlatform {

    static final String SUPPORTED = "Linux on x64 or arm64, macOS on Apple silicon, and Windows on x64";

    private RuntimePlatform() {}

    /**
     * Returns the platform's name as ONNX Runtime names its native directories, such as {@code osx-aarch64}, detecting
     * the operating system and architecture as ONNX Runtime does, or nothing for a platform it does not recognize.
     */
    static Optional<String> platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ENGLISH);
        String arch = osArch.toLowerCase(Locale.ENGLISH);
        String system;

        if (os.contains("mac") || os.contains("darwin")) {
            system = "osx";
        } else if (os.contains("win")) {
            system = "win";
        } else if (os.contains("nux")) {
            system = "linux";
        } else {
            return Optional.empty();
        }

        if (arch.startsWith("amd64") || arch.startsWith("x86_64")) {
            return Optional.of(system + "-x64");
        } else if (arch.startsWith("aarch64")) {
            return Optional.of(system + "-aarch64");
        }

        return Optional.empty();
    }

    /** Returns the resource path of the runtime's native library for a platform it recognizes. */
    static Optional<String> nativeLibrary(String osName, String osArch) {
        return platform(osName, osArch).map(platform -> {
            String library =
                    switch (platform.substring(0, platform.indexOf('-'))) {
                        case "osx" -> "libonnxruntime.dylib";
                        case "win" -> "onnxruntime.dll";
                        default -> "libonnxruntime.so";
                    };

            return "ai/onnxruntime/native/" + platform + "/" + library;
        });
    }

    /** Returns whether the runtime on the classpath carries its native library for the platform. */
    static boolean bundled(String osName, String osArch) {
        return nativeLibrary(osName, osArch)
                .map(library -> RuntimePlatform.class.getClassLoader().getResource(library) != null)
                .orElse(false);
    }
}
