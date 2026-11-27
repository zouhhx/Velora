package com.zouhu.speedlimit.data.nav

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression guard for the 2026-11-15 navigation crash.
 *
 * Baidu's navi guide UI (`com.baidu.navisdk.module.newguide.widgets.RGBottomSheet`)
 * uses `rx.subjects.BehaviorSubject`. `com.baidu.lbsyun:carnavi:9.0.2` neither declares
 * rxjava in its POM nor bundles the `rx` package in the AAR, so without an explicit
 * RxJava 1.x dependency the first "start navigation" throws `NoClassDefFoundError` on
 * the guide thread. The SDK's own crash handler swallows that stack and calls
 * `System.exit(10)`, which looked like a mystery "闪退无栈" for several rounds.
 */
class NaviSdkDependencyTest {
    @Test
    fun `navi guide ui dependency rxjava is on the runtime classpath`() {
        assertNotNull(Class.forName("rx.subjects.BehaviorSubject"))
    }
}
