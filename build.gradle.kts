plugins {
    // Loading the plugins once at the root keeps their shared build services in one class loader for all modules.
    id("pecia.java-conventions") apply false
    alias(libs.plugins.shadow) apply false
}
