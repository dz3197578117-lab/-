// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 单模块化后只剩下 :app:androidApp（纯 Android），因此这里只声明它真正会用到的插件。
// 原先的 android.library / android.kmp.library / compose.multiplatform / kotlin.multiplatform
// 都随 KMP 模块一并移除了。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room3) apply false
}
