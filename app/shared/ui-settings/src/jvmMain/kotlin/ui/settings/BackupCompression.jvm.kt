/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal actual fun compressBackup(content: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
    GZIPOutputStream(output).use { it.write(content) }
}.toByteArray()

internal actual fun decompressBackup(content: ByteArray, maxBytes: Int): ByteArray =
    GZIPInputStream(ByteArrayInputStream(content)).use { input ->
        input.readNBytes(maxBytes + 1).also {
            require(it.size <= maxBytes) { "Backup expands beyond $maxBytes bytes" }
        }
    }
