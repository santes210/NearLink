// Top-level build file.
//
// NOTA AGP 9: Kotlin viene integrado en el propio Android Gradle Plugin, asi
// que NO se declara el plugin org.jetbrains.kotlin.android. El unico plugin de
// Kotlin que sigue haciendo falta es el del compilador de Compose, y su version
// debe coincidir con la de Kotlin que traiga embebida esta version de AGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
