# 044 声纹离线诊断

所有真实数据、逐条结果、向量、音频和日志必须留在本目录 `private/`；该目录已忽略，禁止强制添加 Git。脚本不读取转写正文，不访问网络，不操作手机，公开报告只写聚合数字。

输入：`private/capture.db`、同目录 WAL、`private/features/`、`private/segments/`。读取前复制 DB/WAL 到私有临时目录，再以 SQLite 只读及 query_only 打开；不对原副本 checkpoint，也不以 immutable 忽略 WAL。

使用本机**已经安装**的 Python、NumPy、ONNX Runtime 和 ffmpeg。缺依赖就停止，不安装、不下载。模型直接读取仓库 `app/src/main/assets/voiceprint/campplus.onnx`。

本机复现：

```sh
OPENBLAS_NUM_THREADS=1 /private/tmp/resound-026-probe-venv/bin/python tools/voiceprint-diag/diag.py > tools/voiceprint-diag/private/run.log 2>&1
OPENBLAS_NUM_THREADS=1 /private/tmp/resound-026-probe-venv/bin/python tools/voiceprint-diag/evaluate.py
```

第一条生成私有 `summary.json`、`evaluation.json`、输入指纹及 `windows/*.npz`；第二条生成私有 `production-evaluation.json`。后者对齐生产的候选就绪条件、排除同段样本、Top-3、多人差距、源句重叠投票，以及没有有效窗口的失败项。终端只输出聚合统计。

`diag.py` 按生产格式解析大小端：DB 向量小端 float32，特征缓存大端版本、时长、存在标记及 192 维向量；按生产顺序重建缓存键。重新解码 16k 单声道 PCM，在句时间并集上切连续 3 秒窗，20ms RMS 裁剪，有效语音至少 1.5 秒。NumPy 前端与仓库 KaldiFbank 公式一致，并逐值核对已提交的非私人合成夹具。模型输入做全窗 CMN，输出单位化。

首次运行保存的窗口缓存供后续统计复用；**更换输入、模型或切窗/特征公式时，先移走或删除 private/windows 再重算**。缓存不用于独立证明模型无误；对应整组重算与手机旧特征的统计另见报告。输入 DB、WAL、特征和录音执行前后 SHA-256 必须一致，否则停止。输入指纹永不写入公开报告。

评估标签以手动样本确认记录为准；排除记录可作为负例，本快照为零。其他自动命名不作真值。播放标记只是负例代理，单独报告；已有 say 合成夹具单独报告。手动组标签不是逐窗口听辨真值。开发/留出拆分是事后稳定性检查，不能冒充未见数据测试。

离线定向测试和 APK 编译（12 个测试，不跑其他类）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ROBOLECTRIC_JARS=/Users/coach/.m2/repository/org/robolectric/android-all-instrumented/10-robolectric-5803371-i7 \
./gradlew --offline -I tools/voiceprint-diag/offline-tests.gradle \
  testDebugUnitTest --tests 'app.resound.voiceprint.Voiceprint044Test' assembleDebug \
  -Dorg.gradle.java.installations.auto-download=false
```

Gradle 和 Robolectric 分别强制离线；SDK、JDK、jar 路径须替换成已有本地路径。测试只用程序生成的 PCM、向量及临时数据库，不读取 private。沙箱若阻止写 Gradle 缓存，按项目规则申请这条离线命令的单次提权。
