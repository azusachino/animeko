/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

/**
 * TRK-05: 限流器只依赖 [kotlinx.coroutines.delay], 不读取真实系统时钟,
 * 因此 [kotlinx.coroutines.test.runTest] 的虚拟时间可以完整驱动测试, 无需真实等待.
 */
class RateLimiterTest {

    @Test
    fun `TRK-05 前25个请求立即通过第26个等待到下一个窗口才通过`() = runTest {
        val limiter = RateLimiter(permits = 25, window = 1.minutes, scope = backgroundScope)

        var completedBeforeWindow = 0
        val jobs = (1..26).map {
            async {
                limiter.acquire()
                completedBeforeWindow++
            }
        }

        // 前 25 个应立即完成, 无需推进时间; 第 26 个此时应仍在等待许可.
        advanceTimeBy(1) // 让所有已就绪的协程真正运行到挂起点
        assertEquals(25, completedBeforeWindow)

        advanceTimeBy(1.minutes)
        jobs.forEach { it.await() }
        assertEquals(26, completedBeforeWindow)
    }

    @Test
    fun `TRK-05 未达上限时acquire不等待`() = runTest {
        val limiter = RateLimiter(permits = 25, window = 1.minutes, scope = backgroundScope)

        var acquired = false
        val job = async {
            limiter.acquire()
            acquired = true
        }
        advanceTimeBy(1)

        assertEquals(true, acquired)
        job.await()
    }

    @Test
    fun `TRK-05 窗口重置后许可恢复到permits上限而不是无限累积`() = runTest {
        val limiter = RateLimiter(permits = 25, window = 1.minutes, scope = backgroundScope)

        // 消耗 1 个许可, 经过 3 个窗口后, 许可数应恢复为 25 (而不是因为 3 次重置叠加成 75).
        limiter.acquire()
        advanceTimeBy(3.minutes)

        var completed = 0
        val jobs = (1..25).map { async { limiter.acquire(); completed++ } }
        advanceTimeBy(1)
        assertEquals(25, completed)

        // 第 26 个此时不应立即通过.
        var extraCompleted = false
        val extra = async { limiter.acquire(); extraCompleted = true }
        advanceTimeBy(1)
        assertFalse(extraCompleted)

        advanceTimeBy(1.minutes)
        extra.await()
        jobs.forEach { it.await() }
    }
}
