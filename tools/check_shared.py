#!/usr/bin/env python3
"""共用业务边界门禁（Python 标准库）。

用法：仓库根目录运行 python3 tools/check_shared.py；违规返回 1，通过返回 0。
检查 shared/src/commonMain 下 Kotlin 的 import 和正文，包括字符串模板表达式；
忽略注释和普通字符串。禁止平台包、系统时钟、锁和线程；目前无 JVM 注解豁免。
commonMain 依赖仅允许下面列出的库，使用可审计的直接坐标，变量/别名拒绝。
文本门禁不能替代编译，提交前还须运行 ./gradlew :shared:check（含 metadata）。
词法屏蔽和依赖块检查参照 check_devices.py；不修改设备门禁及其白名单。
"""
import re
from pathlib import Path

ALLOWED_LIBRARIES = {
    "androidx.room:room-runtime",
    "androidx.sqlite:sqlite",
    "io.ktor:ktor-client-core",
    "io.ktor:ktor-client-websockets",
    "io.ktor:ktor-client-okhttp",
    "io.ktor:ktor-client-darwin",
    "io.ktor:ktor-client-mock",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "org.jetbrains.kotlinx:kotlinx-datetime",
}

def mask_noncode(text, strings=True):
    """保留行号；屏蔽注释和字面量，但字符串模板内的 Kotlin 表达式仍要检查。"""
    result = list(text)

    def mask(start, end):
        for j in range(start, min(end, len(text))):
            if text[j] != "\n":
                result[j] = " "

    def code(i, in_braces=False):
        while i < len(text):
            start = i
            if text.startswith("//", i):
                i = text.find("\n", i)
                if i < 0:
                    i = len(text)
                mask(start, i)
            elif text.startswith("/*", i):
                i += 2
                depth = 1
                while i < len(text) and depth:
                    if text.startswith("/*", i):
                        depth += 1
                        i += 2
                    elif text.startswith("*/", i):
                        depth -= 1
                        i += 2
                    else:
                        i += 1
                mask(start, i)
            elif text[i] in "\"'":
                delimiter = '"""' if text.startswith('"""', i) else text[i]
                if strings:
                    mask(i, i + len(delimiter))
                i += len(delimiter)
                while i < len(text):
                    if text.startswith(delimiter, i):
                        if strings:
                            mask(i, i + len(delimiter))
                        i += len(delimiter)
                        break
                    if delimiter != "'" and text.startswith("${", i):
                        if strings:
                            mask(i, i + 2)
                        i = code(i + 2, in_braces=True)
                    else:
                        end = i + (2 if delimiter != '"""' and text[i] == "\\" else 1)
                        if strings:
                            mask(i, end)
                        i = end
            elif text[i] == "{":
                i = code(i + 1, in_braces=True)
            elif text[i] == "}" and in_braces:
                return i + 1
            else:
                i += 1
        return i

    code(0)
    return "".join(result)


def line_at(text, offset):
    return text.count("\n", 0, offset) + 1


def dependency_errors(text):
    """只接受可直接审计的显式 sourceSet.dependencies；遇到间接写法要求展开。

    这不是 Gradle 解释器。无法确认的 commonMain 配置也拒绝，防止别名/变量悄悄加库。
    """
    code = mask_noncode(text)
    literal_code = mask_noncode(text, strings=False)
    covered = []
    common_count = 0
    for match in re.finditer(r"\b(?:(\w+)\s*\.\s*)?dependencies\s*\{", code):
        source = match[1]
        depth, end = 1, match.end()
        while end < len(code) and depth:
            depth += (code[end] == "{") - (code[end] == "}")
            end += 1
        if source != "commonMain":
            # 根依赖块仅供逐目标 KSP 处理器使用，不能往 commonMain 偷加依赖。
            if source is None and re.fullmatch(
                r'\s*(?:add\("ksp(?:Android|Jvm|IosArm64|IosSimulatorArm64)",\s*"androidx\.room:room-compiler:2\.8\.4"\)\s*'
                r'|if\s*\(providers\.gradleProperty\("taosheng\.ios"\)\.orNull\s*==\s*"true"\)\s*\{\s*'
                r'|\}\s*)*', literal_code[match.end():end - 1],
            ):
                continue
            if source not in {"iosMain", "iosArm64Main", "iosSimulatorArm64Main", "commonTest", "jvmMain", "jvmTest", "androidMain", "androidUnitTest", "androidInstrumentedTest"}:
                yield line_at(text, match.start()), "依赖块无法归属；请使用显式 sourceSet.dependencies"
            continue
        common_count += 1
        covered.append((match.start(), end))
        body_start, body_end = match.end(), end - 1
        body = literal_code[body_start:body_end]
        # 每条只允许 api/implementation 的完整坐标，或 kotlin("stdlib")。
        declaration = re.compile(
            r'\b(?:api|implementation)\s*\(\s*(?:"([^"$]+)"|kotlin\s*\(\s*"(stdlib)"\s*\))\s*\)'
        )
        remainder = list(body)
        for dep in declaration.finditer(body):
            coordinate = dep[1]
            if coordinate:
                parts = coordinate.split(":")
                if len(parts) != 3 or ":".join(parts[:2]) not in ALLOWED_LIBRARIES or not parts[2]:
                    yield line_at(text, body_start + dep.start()), f"commonMain 不允许依赖 {coordinate}"
            for i in range(dep.start(), dep.end()):
                if remainder[i] != "\n":
                    remainder[i] = " "
        for match_rest in re.finditer(r"[^\s;]+", "".join(remainder)):
            yield line_at(text, body_start + match_rest.start()), "commonMain 依赖须使用允许的直接坐标，不允许变量、别名或其他配置"
    if common_count != 1:
        yield 1, "必须有且只有一个显式 commonMain.dependencies 块"
    for match in re.finditer(r"\bcommonMain\w*\b", literal_code):
        if not any(start <= match.start() < end for start, end in covered):
            yield line_at(text, match.start()), "commonMain 配置不能绕过显式依赖块"


def check(root):
    errors = []
    common = root / "shared/src/commonMain"
    files = sorted(common.rglob("*.kt"))
    if not files:
        errors.append("shared/src/commonMain:1: 缺少共用 Kotlin 源码")
    forbidden = re.compile(
        r"\b(?:android\s*\.|androidx\s*\.(?!\s*(?:room|sqlite)\s*\.)|java\s*\.|javax\s*\.|"
        r"androidx\s*\.\s*sqlite\s*\.\s*db\b|SupportSQLite\w*\b|SimpleSQLiteQuery\b|openHelper\b|"
        r"androidx\s*\.\s*room\s*\.\s*withTransaction\b|"
        r"org\s*\.\s*json\b|okhttp3\b|kotlin\s*\.\s*jvm\s*\.|"
        r"System\s*\.\s*currentTimeMillis\b|synchronized\b|Thread\b|Synchronized\b)"
    )
    for file in files:
        text = file.read_text(encoding="utf-8")
        for match in forbidden.finditer(mask_noncode(text)):
            errors.append(f"{file.relative_to(root)}:{line_at(text, match.start())}: 共用层禁止平台依赖：{match[0]}")
    build = root / "shared/build.gradle.kts"
    if not build.is_file():
        errors.append("shared/build.gradle.kts:1: 缺少共用模块构建文件")
    else:
        for line, message in dependency_errors(build.read_text(encoding="utf-8")):
            errors.append(f"shared/build.gradle.kts:{line}: {message}")
    return errors


def main():
    errors = check(Path(__file__).resolve().parents[1])
    for error in errors:
        print(error)
    if not errors:
        print("共用业务边界检查通过（无平台依赖豁免）")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
