#!/usr/bin/env python3
"""离线重建 Hchat 的 arm64 Rust BeanShell 库。"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target-dir', required=True, help='仓库外的 Cargo 构建目录')
    parser.add_argument('--cargo', default='cargo', help='带 Android 标准库的 Cargo 路径')
    parser.add_argument('--linker', help='Android API 27 或更高版本的 arm64 clang 包装器')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    source = repo / 'third_party/beanshell-rust'
    target_dir = Path(args.target_dir).resolve()
    env = os.environ.copy()
    env['CARGO_TARGET_DIR'] = str(target_dir)
    if args.linker:
        env['CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER'] = str(Path(args.linker).resolve())
    subprocess.run([args.cargo, 'build', '--locked', '--offline', '--release',
                    '--target', 'aarch64-linux-android', '-p', 'bsh-rs'],
                   cwd=source, env=env, check=True)
    library = target_dir / 'aarch64-linux-android/release/libbsh_rs.so'
    header = subprocess.check_output(['readelf', '-h', str(library)], text=True)
    if 'AArch64' not in header or 'DYN' not in header:
        raise RuntimeError('产物不是 arm64 共享库')
    symbols = subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(library)], text=True)
    # tryInvokeHook 是脚本插件 Hook ABI 的同步入口：宿主 interceptor chain 只能留在
    # 收到它的线程，所以它必须是不排队、拿不到执行权就返回 busy 的独立导出。
    for name in ['createEngine', 'destroyEngine', 'eval', 'evalValue', 'evalSource',
                 'invokeMethod', 'tryInvokeHook', 'setVariable', 'getVariable',
                 'unsetVariable', 'validate', 'compileAst', 'evalAst']:
        if 'Java_me_yun_bsh_rust_Native_' + name not in symbols:
            raise RuntimeError('JNI 导出缺失：' + name)
    program_headers = subprocess.check_output(['readelf', '-lW', str(library)], text=True)
    loads = [line.split() for line in program_headers.splitlines() if line.strip().startswith('LOAD ')]
    if not loads or any(int(line[-1], 16) < 16384 for line in loads):
        raise RuntimeError('共享库未满足 16KB 页对齐')
    output = repo / 'app/src/main/jniLibs/arm64-v8a/libbsh_rs.so'
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, output)
    print('已生成：', output)
    print('SHA-256：', hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
