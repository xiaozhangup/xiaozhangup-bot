#!/usr/bin/python3
"""Transfer files across the bot/container boundary without resolving container symlinks on the host."""
import pathlib
import shutil
import sys

root = pathlib.Path('/workspace').resolve()
operation, requested = sys.argv[1:3]
path = pathlib.Path(requested)
path = (path if path.is_absolute() else root / path).resolve()
if not path.is_relative_to(root):
    raise SystemExit('只能访问当前群 /workspace 内的文件')
if operation == 'put':
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as output:
        shutil.copyfileobj(sys.stdin.buffer, output)
    print(path)
elif operation == 'get':
    if not path.is_file():
        raise SystemExit('文件不存在或不是普通文件')
    with path.open('rb') as source:
        shutil.copyfileobj(source, sys.stdout.buffer)
else:
    raise SystemExit('未知文件操作')
