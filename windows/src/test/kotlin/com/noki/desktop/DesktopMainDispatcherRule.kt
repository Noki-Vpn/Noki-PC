package com.noki.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Offscreen JVM tests have no Tao event loop; their existing scene host runs on Swing. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopMainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(Dispatchers.Swing) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
