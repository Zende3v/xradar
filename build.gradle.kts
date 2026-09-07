// Root build script. Plugin versions are declared here (apply false) and applied per-module.
// Keep this file free of module-specific configuration.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
