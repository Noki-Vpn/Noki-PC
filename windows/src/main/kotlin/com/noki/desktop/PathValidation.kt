package com.noki.desktop

import java.nio.file.Path

internal object PathValidation {
    fun isExecutablePath(value: String): Boolean = runCatching {
        value.length in 4..32767 && '\u0000' !in value && Path.of(value).isAbsolute && (if (DesktopBuild.IS_LINUX) !value.endsWith('/') else value.endsWith(".exe", true))
    }.getOrDefault(false)
    fun isFolderPath(value: String): Boolean = runCatching {
        value.length in 4..32767 && '\u0000' !in value && (value.endsWith('\\') || value.endsWith('/')) &&
            Path.of(value).isAbsolute && Path.of(value).parent != null
    }.getOrDefault(false)
}
