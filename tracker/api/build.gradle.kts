/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:Suppress("UnstableApiUsage")

plugins {
    id("ani.kmp-library")
    alias(libs.plugins.kotlin.plugin.serialization)
    idea
}

kotlin {
    android {
        namespace = "me.him188.ani.app.tracker.api"
    }
    sourceSets.commonMain {
        dependencies {
            implementation(libs.kotlinx.serialization.core)
            api(libs.kotlinx.coroutines.core)
            api(projects.datasource.datasourceApi) // UnifiedCollectionType
        }
    }

    sourceSets.commonTest {
        dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.utils.testing)
        }
    }
}
