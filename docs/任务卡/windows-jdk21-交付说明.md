# Windows / JDK 21 构建兼容

删除 gradle.properties 内机器专属 Homebrew JDK 路径，使用 Android Studio 的 Gradle JDK 或 JAVA_HOME 选择 JDK 21。README 中英文及 AGENTS 只同步构建说明，不改变协作规则或依赖版本。

新增 gradlew.bat，来源为 Gradle v8.14.2 标准脚本，保留 Apache-2.0 许可、路径引号与错误退出处理。仓库既有 gradle-wrapper.jar 没有 Main-Class 清单，因此使用 classpath + org.gradle.wrapper.GradleWrapperMain 启动，不替换二进制 JAR。

验证：Windows、JDK 21，隔离工作区执行 gradlew.bat assembleDebug，BUILD SUCCESSFUL（4 分 29 秒）。未运行 macOS/Linux 构建；Gradle、AGP、SDK 与应用版本保持不变。开发者本机 SDK/JDK 绝对路径不提交。
