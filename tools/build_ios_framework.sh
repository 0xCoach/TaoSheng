#!/bin/zsh
# 产出 iOS 用的共用层静态库 TaoShengCore.xcframework（真机 + 模拟器）。
# 用法：zsh tools/build_ios_framework.sh [debug|release] [输出目录]
# 默认 debug，输出到 shared/build/XCFrameworks/<类型>/。需要已安装 Xcode；TAOSHENG_XCODE 可指定 .app 路径。
set -eu
cd "$(dirname "$0")/.."
kind="${1:-debug}"
case "$kind" in debug) task=Debug ;; release) task=Release ;; *) echo "类型只能是 debug 或 release"; exit 1 ;; esac
xcode="${TAOSHENG_XCODE:-$(printf '%s\n' /Applications/Xcode*.app(N) | sort | tail -1)}"
[ -d "$xcode/Contents/Developer" ] || { echo "没找到 Xcode"; exit 1; }
export DEVELOPER_DIR="$xcode/Contents/Developer"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
./gradlew -Ptaosheng.ios=true -PskipSigningSecrets=true ":shared:assembleTaoShengCore${task}XCFramework" -q
src="shared/build/XCFrameworks/$kind/TaoShengCore.xcframework"
if [ $# -ge 2 ]; then
  mkdir -p "$2"; rm -rf -- "$2/TaoShengCore.xcframework"; cp -R "$src" "$2/"
  echo "已输出到 $2/TaoShengCore.xcframework"
else
  echo "已输出到 $src"
fi
