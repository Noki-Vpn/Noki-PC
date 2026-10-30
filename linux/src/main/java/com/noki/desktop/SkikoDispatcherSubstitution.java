package com.noki.desktop;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.Dispatchers;

// Skiko's separate dispatcher otherwise starts Swing even inside a Tao scene.
@TargetClass(className = "org.jetbrains.skiko.MainUIDispatcher_awtKt")
final class SkikoDispatcherSubstitution {
    @Substitute
    public static CoroutineDispatcher getMainUIDispatcher() {
        return Dispatchers.getMain();
    }
}
