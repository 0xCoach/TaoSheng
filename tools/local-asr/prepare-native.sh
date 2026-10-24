#!/bin/sh
# 输入：已验证的 sherpa-onnx 1.13.8 QNN 官方 demo 的 arm64-v8a 原生库目录。
# 只复制运行库，不复制模型；需要 patchelf，避免与原有 ORT 1.23.2 冲突。
set -eu
source_dir="$1"
output_dir="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$output_dir"
for name in libsherpa-onnx-jni.so libQnnHtp.so libQnnSystem.so libQnnHtpV75Stub.so libQnnHtpV75Skel.so; do
  cp "$source_dir/$name" "$output_dir/$name"
done
cp "$source_dir/libonnxruntime.so" "$output_dir/libresound_asr_ort.so"
patchelf --set-soname libresound_asr_ort.so "$output_dir/libresound_asr_ort.so"
patchelf --replace-needed libonnxruntime.so libresound_asr_ort.so "$output_dir/libsherpa-onnx-jni.so"
