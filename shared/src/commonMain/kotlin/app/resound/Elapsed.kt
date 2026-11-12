package app.resound

fun formatElapsed(ms: Long): String = ms.coerceAtLeast(0).let { "${it / 1000}.${((it % 1000) / 10).toString().padStart(2, '0')} 秒" }
