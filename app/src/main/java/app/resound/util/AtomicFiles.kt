package app.resound.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子替换目标文件：POSIX rename 语义（覆盖已存在目标）。
 * `File.renameTo` 在 Windows JVM 上目标已存在时直接失败，改用 Files.move 保持各平台一致。
 * tmp 与 target 必须同目录（调用方均已保证），因此同卷原子移动成立。
 */
internal fun atomicReplace(part: File, target: File) {
    try {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * 与 `android.util.AtomicFile` 同语义的安全写入：base→.bak、写 .new、原子替换、清 .bak，失败回滚。
 * Android 自带的 AtomicFile 在 Windows JVM 上会因 `File.renameTo` 不能覆盖已存在目标而静默丢写，
 * 设备上不受影响；为让桌面单测与设备行为一致，写路径统一走这里。读侧仍可用 AtomicFile.openRead（只吃 .bak）。
 */
internal fun atomicWriteFile(file: File, content: (java.io.FileOutputStream) -> Unit) {
    val bak = File(file.path + ".bak")
    val part = File(file.path + ".new")
    try {
        if (file.exists()) { bak.delete(); check(file.renameTo(bak)) { "原子备份失败" } }
        part.outputStream().use { out -> content(out); out.fd.sync() }
        atomicReplace(part, file)
        bak.delete()
    } catch (e: Exception) {
        part.delete()
        if (bak.exists() && !file.exists()) check(bak.renameTo(file))
        throw e
    }
}
