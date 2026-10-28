# BSDIFF40 测试夹具

这三个文件是本卡在 macOS 上用标准 `bsdiff` 命令生成的合成数据，没有真实安装包或用户数据。
工具从 FreeBSD 官方 `releng/9.3/usr.bin/bsdiff/bsdiff/bsdiff.c` 临时编译（Colin Percival 的 bsdiff 4 格式），未安装到系统，也未随项目分发。
来源：https://github.com/freebsd/freebsd-src/blob/releng/9.3/usr.bin/bsdiff/bsdiff/bsdiff.c

生成方法（Python 3，当前目录为本目录，`bsdiff` 指向临时编译好的工具）：

```python
import random, subprocess
from pathlib import Path
r = random.Random(51)
old = bytes(r.randrange(256) for _ in range(98304))
new = old[32768:65536] + b'TaoSheng update fixture\n' + bytes((v + 1) % 256 for v in old[:32768]) + old[65536:] + b'new tail\n'
Path('old.bin').write_bytes(old)
Path('new.bin').write_bytes(new)
subprocess.run(['bsdiff', 'old.bin', 'new.bin', 'update.patch'], check=True)
```

- old.bin：98,304 字节；SHA-256 `686c917b9581a182d1c639a11b98bcdf08b0224ed7e4c67616e898eae7644090`
- new.bin：98,337 字节；SHA-256 `d3782014e1578fa705566884a0afe5d4c4b3634fffea6a2fc628b78675660530`
- update.patch：33,419 字节；SHA-256 `872b7704f6f8c8f4d4a8b17af95b09c4faffa8ae80fe45105fd70ee3c605298c`

控制流含首条零长度跳转及负数 seek；还原测试核对全部字节及摘要，坏包测试使用截断副本。
