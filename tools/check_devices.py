#!/usr/bin/env python3
"""设备层边界检查；仓库根目录执行 python3 tools/check_devices.py，无第三方依赖。"""
import json
import re
from collections import Counter
from pathlib import Path

COMMON = "devices/src/commonMain/"
PLUGIN_ROOTS = (
    COMMON + "kotlin/app/resound/devices/plugins/",
    "app/src/main/java/app/resound/devices/plugins/",
)
EXTRA_BRANDS = {"d3200", "sonicloud", "omi", "adeus", "folotoy", "plaud", "limitless", "bee"}
# 已知待清理（卡 140）：精确到文件及整行，每项只豁免一次；不放过同文件的新增引用。
KNOWN_BRANDS_140 = {
    ("app/src/main/java/app/resound/devices/AndroidDevices.kt",
     "import app.resound.devices.plugins.sonicloud.*"),
    ("app/src/main/java/app/resound/devices/AndroidDevices.kt",
     'val protocol = if (card.filePlugin == "sonicloud") SonicloudLive() else object : LiveProtocol {}'),
    (COMMON + "kotlin/app/resound/devices/driver/DeviceDriverContract.kt",
     "/** 驱动编号：小写字母、数字、横线、冒号，如 taoecho、card:omi、d3200 */"),
    (COMMON + "kotlin/app/resound/devices/driver/DeviceDriverContract.kt",
     "/** 显示名，如“安克 / 飞书录音豆 D3200” */"),
}
ALLOWED_LIBRARIES = {
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
}
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "node_modules", "__pycache__"}


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
            if source not in {"commonTest", "jvmMain", "jvmTest", "androidMain", "androidUnitTest", "androidInstrumentedTest"}:
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

    def error(path, line, message):
        errors.append(f"{path}:{line}: {message}")

    brands = set(EXTRA_BRANDS)
    for file in sorted((root / "app/src/main/assets/devices").glob("*.json")):
        try:
            card_id = json.loads(file.read_text(encoding="utf-8"))["id"]
            if not isinstance(card_id, str) or not card_id:
                raise ValueError("id 必须为非空字符串")
            brands.add(card_id)
        except (ValueError, KeyError, TypeError) as exc:
            error(file.relative_to(root), 1, f"无法读取说明卡 id：{exc}")
    brand_pattern = re.compile(r"\b(?:" + "|".join(re.escape(b) for b in sorted(brands)) + r")\b", re.I)
    exemptions = Counter({key: 1 for key in KNOWN_BRANDS_140})
    for file in sorted(root.rglob("*.kt")):
        relative = file.relative_to(root)
        if any(part in SKIP_DIRS for part in relative.parts):
            continue
        path = relative.as_posix()
        text = file.read_text(encoding="utf-8")
        code = mask_noncode(text)
        if path.startswith(COMMON):
            for match in re.finditer(r"\b(?:android\s*\.|java\s*\.|javax\s*\.|org\s*\.\s*json\b|okhttp3\b)", code):
                error(path, line_at(text, match.start()), "共用层禁止平台依赖：" + match[0])
        plugin_root = next((p for p in PLUGIN_ROOTS if path.startswith(p)), None)
        is_test = any(part.lower() == "tests" or part.lower().endswith("test") for part in relative.parts[:-1])
        if not plugin_root and not is_test and not path.startswith(("docs/", "app/src/main/assets/devices/")):
            for number, line in enumerate(text.splitlines(), 1):
                if brand_pattern.search(line):
                    key = (path, line.strip())
                    if exemptions[key]:
                        exemptions[key] -= 1
                    else:
                        error(path, number, "品牌名只能出现在插件、设备数据、文档或测试目录")
        if plugin_root:
            own = path[len(plugin_root):].split("/")[0]
            for match in re.finditer(r"\bplugins\s*\.\s*([A-Za-z_][A-Za-z_0-9]*)", code):
                if match[1] != own:
                    error(path, line_at(text, match.start()), f"插件 {own} 不得引用插件 {match[1]}")
    build = root / "devices/build.gradle.kts"
    if not build.is_file():
        error("devices/build.gradle.kts", 1, "缺少共用模块构建文件")
    else:
        for line, message in dependency_errors(build.read_text(encoding="utf-8")):
            error("devices/build.gradle.kts", line, message)
    return errors


def main():
    errors = check(Path(__file__).resolve().parents[1])
    for error in errors:
        print(error)
    if not errors:
        print("设备边界检查通过（卡 140 的 4 条已知引用按整行豁免）")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
