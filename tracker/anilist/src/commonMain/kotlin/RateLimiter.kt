/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration

/**
 * 固定窗口限流器: 每个 [window] 最多放行 [permits] 次 [acquire]. 窗口到期时一次性把许可补满到 [permits],
 * 而不是逐个按比例恢复 -- 更简单, 且不会因为跨越多个空闲窗口而把许可数无限叠加.
 *
 * 只依赖 [delay], 不读取系统时钟, 因此在 [kotlinx.coroutines.test.runTest] 的虚拟时间下可被完整、快速地测试.
 *
 * 这是固定窗口而非滑动窗口, 在窗口边界上最坏情况下允许短时间内出现 2x [permits] 的突发流量.
 * 对 AniList 25/min (相对其 90/min 上限留有约 3.6x 余量) 来说这个余量足够安全.
 */
class RateLimiter(
    private val permits: Int,
    private val window: Duration,
    scope: CoroutineScope,
) {
    private val semaphore = Semaphore(permits)

    init {
        scope.launch {
            while (isActive) {
                delay(window)
                val toRelease = permits - semaphore.availablePermits
                repeat(toRelease) { semaphore.release() }
            }
        }
    }

    suspend fun acquire() {
        semaphore.acquire()
    }
}
