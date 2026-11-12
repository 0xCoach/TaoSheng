#!/bin/zsh
# iOS 编译检查：把 :shared 和 :devices 的共用代码编成 iOS 真机 + 模拟器版本，证明没有只在安卓能用的写法。
# 用法：仓库根目录执行 zsh tools/check_ios.sh
# 需要已安装 Xcode（任意 /Applications/Xcode*.app）；不需要改系统的 xcode-select。
# 编完自动删除两个模块 build/ 下的 iOS 中间产物（Kotlin/Native 工具链 ~/.konan 保留，以后编译要用）。
set -u
cd "$(dirname "$0")/.." || exit 1
xcode=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort | tail -1)
if [ -z "$xcode" ]; then echo "没找到 Xcode，跳过 iOS 编译检查"; exit 1; fi
export DEVELOPER_DIR="$xcode/Contents/Developer"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
# 单独登记后台进程，避免并行任务的 ./gradlew --stop 把本次编译停掉
reg="${TAOSHENG_GRADLE_REGISTRY:-${TMPDIR:-/tmp}/taosheng-ios-gradle}"
mkdir -p "$reg"
./gradlew -Dorg.gradle.daemon.registry.base="$reg" -Ptaosheng.ios=true \
  :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 \
  :devices:compileKotlinIosArm64 :devices:compileKotlinIosSimulatorArm64 -q
code=$?
for m in shared devices; do
  for t in iosArm64 iosSimulatorArm64; do
    [ -d "$m/build/classes/kotlin/$t" ] && rm -rf -- "$m/build/classes/kotlin/$t"
    [ -d "$m/build/kotlin/compile${(C)t}" ] && rm -rf -- "$m/build/kotlin/compile${(C)t}"
  done
done
if [ $code -eq 0 ]; then echo "iOS 编译检查通过（shared + devices，真机与模拟器），中间产物已清理"; else echo "iOS 编译检查失败"; fi
exit $code
