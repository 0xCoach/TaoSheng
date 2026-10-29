#!/bin/bash
# 默认会联网下载；由调用方按当前环境执行单次提权审批。
set -euo pipefail
cd "$(dirname "$0")"
PYTHON_BIN="${AUDIO_TAG_PYTHON:-$HOME/.venvs/audiotag/bin/python}"
DATA_ROOT="${AUDIO_TAG_DATA:-$HOME/Datasets/taosheng-audio}"
MODEL_ROOT="${AUDIO_TAG_MODELS:-$HOME/.claude/jobs/3b8dcfaa/tmp/at}"
if [[ "${1:-}" != "--offline" ]]; then "$PYTHON_BIN" download_061.py --root "$DATA_ROOT"; fi
if [[ ! -f "$DATA_ROOT/manifest.json" ]]; then "$PYTHON_BIN" prepare_061.py --root "$DATA_ROOT"; fi
"$PYTHON_BIN" evaluate_061.py --root "$DATA_ROOT" --models "$MODEL_ROOT"
if [[ ! -f "$DATA_ROOT/results/frozen-config.json" ]]; then "$PYTHON_BIN" tune_061.py --root "$DATA_ROOT"; fi
if [[ ! -f "$DATA_ROOT/results/validation-summary.json" ]]; then "$PYTHON_BIN" tune_061.py --root "$DATA_ROOT" --validate; fi
"$PYTHON_BIN" report_061.py --root "$DATA_ROOT"
"$PYTHON_BIN" verify_061.py --root "$DATA_ROOT"
