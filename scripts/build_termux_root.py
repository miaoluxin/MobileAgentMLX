#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_termux_root.py — 构建期打包完整 Linux 环境（Termux 发行形态）进 APK。

流程：
1. 下载 Termux 官方仓库 aarch64 包索引（Packages.gz，2906 包）
2. 从预置清单递归解析依赖（Depends 字段）
3. 下载 .deb → 解析 ar 归档 → 解出 data.tar.xz → 解包到 Termux 布局 files/usr/
4. xz 压缩整个根为单个归档 → assets/termux-root.tar.xz（APK 内置，手机端零下载）

用法: python scripts/build_termux_root.py [--cache DIR] [--skip-download]
"""
import gzip
import os
import re
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main"
ARCH = "aarch64"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(os.environ.get("TERMUX_DEB_CACHE", str(PROJECT_ROOT / "build" / "termux-debs")))
# 输出未压缩 tar（APK 的 zip 压缩已等效压缩；手机端解压零依赖零运行时库）
OUT_TAR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "termux-root.tar"
PREFIX = "data/data/com.termux/files"  # .deb 内路径前缀（Termux 安装布局）

# ---- 预置清单：环境底座（保证 apt 完整可用）+ 预装工具（开箱即用） ----
SEED_PACKAGES = [
    # 环境底座
    # termux-exec：W^X 执行限制（Android 10+ targetSdk≥29 禁止 execve app-data）的预加载绕过库，
    # 由 EmbeddedEnv 从 assets 单独注入 usr/lib/libtermux-exec-ld-preload.so（保持与重建一致）
    "termux-elf-cleaner", "libandroid-support", "bash", "busybox", "coreutils", "termux-exec",
    "apt", "dpkg", "termux-tools", "gpgv", "ncurses", "liblzma", "zlib", "libiconv",
    "libc++", "libgmp", "libmpfr", "readline", "pcre2", "bzip2", "libbz2",
    "libacl", "libandroid-glob", "libandroid-selinux", "libexpat", "libffi",
    "libsqlite", "libcurl", "c-ares", "ca-certificates", "openssl",
    # 预装工具（开箱即用；其余 2906 包 pkg install 按需）
    "git", "python", "curl", "tar", "xz-utils", "less", "sed", "gawk", "grep",
    "diffutils", "findutils", "vim", "nano",
    # 2026-08-09 实测修复：libzstd.so.1 缺失（apt 依赖解析取错替代项）+ zip/unzip（busybox 无 zip applet）
    "libzstd", "zstd", "zip", "unzip",
    # 文件生成/数据处理（termux 二进制包）
    "python-pillow", "python-lxml", "python-numpy", "python-pip",
]

# 纯 Python 包（pip 纯 wheel 打进 site-packages；无编译依赖，跨平台）
PIP_PURE_PACKAGES = ["openpyxl", "et_xmlfile", "python-docx", "reportlab", "plotly"]


def fetch(url: str) -> bytes:
    print(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "reasonix-build/1.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def parse_packages(data: bytes) -> dict:
    text = gzip.decompress(data).decode("utf-8", errors="ignore")
    pkgs = {}
    cur = None
    for line in text.split("\n"):
        if line.startswith("Package: "):
            cur = line.split(": ", 1)[1]
            pkgs[cur] = {"depends": "", "filename": "", "size": 0}
        elif line.startswith("Depends: ") and cur:
            pkgs[cur]["depends"] = line.split(": ", 1)[1]
        elif line.startswith("Filename: ") and cur:
            pkgs[cur]["filename"] = line.split(": ", 1)[1]
        elif line.startswith("Size: ") and cur:
            pkgs[cur]["size"] = int(line.split(": ", 1)[1])
    return pkgs


def split_depends(depends: str) -> list:
    """Depends: 'a (>= 1), b | c, d' → [["a"], ["b", "c"], ["d"]]（替代项候选组）"""
    out = []
    for group in depends.split(","):
        group = group.strip()
        if not group:
            continue
        candidates = [c.strip() for c in group.split("|")]
        names = [re.split(r"\s*\(", c)[0].strip() for c in candidates if re.split(r"\s*\(", c)[0].strip()]
        if names:
            out.append(names)
    return out


def resolve(pkgs: dict, seeds: list) -> list:
    """依赖解析：替代项（a | b）取仓库中存在的第一候选（缺失时回退下一候选，不再静默漏包）。"""
    resolved = []
    visited = set()
    queue = list(seeds)
    while queue:
        name = queue.pop(0)
        if name in visited:
            continue
        visited.add(name)
        p = pkgs.get(name)
        if not p:
            print(f"  !! 包不存在: {name}")
            continue
        resolved.append(name)
        for candidates in split_depends(p["depends"]):
            chosen = next((c for c in candidates if c in pkgs and c not in visited), None)
            if chosen is None:
                print(f"  !! 依赖组无可用候选: {' | '.join(candidates)}（{name}）")
                continue
            queue.append(chosen)
    return resolved


def extract_deb_to_tar(deb_path: Path, out_tf: tarfile.TarFile):
    """解析 ar → data.tar.xz → 把条目（含符号链接）直接写入最终 tar。
    符号链接保留为 tar SYMTYPE 条目 —— 绕开 Windows 文件系统无符号链接权限的限制，
    Android 端 MinimalTar 解压时正常创建。"""
    import io
    import lzma
    raw = deb_path.read_bytes()
    if raw[:8] != b"!<arch>\n":
        raise ValueError(f"不是 ar 归档: {deb_path.name}")
    pos = 8
    data_tar = None
    while pos < len(raw):
        header = raw[pos:pos + 60]
        if len(header) < 60:
            break
        size = int(header[48:58].decode().strip())
        data_start = pos + 60
        mname = header[:16].decode().strip()
        if mname.startswith("data.tar"):
            data_tar = raw[data_start:data_start + size]
        pos = data_start + size + (size % 2)
    if data_tar is None:
        raise ValueError(f"无 data.tar: {deb_path.name}")
    xz = lzma.decompress(data_tar)
    with tarfile.open(fileobj=io.BytesIO(xz), mode="r:") as tf:
        for member in tf.getmembers():
            name = member.name.lstrip("./")
            if not name.startswith(PREFIX):
                continue
            member.name = name  # 保留 data/data/com.termux/files/... 结构（运行时 PREFIX 对齐）
            if member.isfile():
                src = tf.extractfile(member)
                out_tf.addfile(member, fileobj=src)
            else:
                out_tf.addfile(member)  # 目录/符号链接/设备等：原样写入


def main():
    skip_download = "--skip-download" in sys.argv
    CACHE.mkdir(parents=True, exist_ok=True)

    print("== 1/4 下载包索引 ==")
    index = fetch(f"{REPO}/dists/stable/main/binary-{ARCH}/Packages.gz")
    pkgs = parse_packages(index)
    print(f"    仓库共 {len(pkgs)} 个包")

    print("== 2/4 依赖解析 ==")
    wanted = resolve(pkgs, SEED_PACKAGES)
    print(f"    解析出 {len(wanted)} 个包（含依赖）")
    total_size = sum(pkgs[n]["size"] for n in wanted)
    print(f"    合计下载体积 ≈ {total_size / 1024 / 1024:.0f} MB")

    print("== 3/4 下载 .deb ==")
    deb_files = []
    for name in wanted:
        p = pkgs[name]
        url = f"{REPO}/{p['filename']}"
        fname = url.rsplit("/", 1)[-1]
        target = CACHE / fname
        if not target.exists() and not skip_download:
            try:
                target.write_bytes(fetch(url))
            except Exception as e:
                print(f"  !! 下载失败 {name}: {e}")
                continue
        if target.exists():
            deb_files.append((name, target))
    print(f"    已就绪 {len(deb_files)}/{len(wanted)} 个 .deb")

    print("== 4/4 流式组装最终 tar（符号链接保留为 tar 条目）==")
    OUT_TAR.parent.mkdir(parents=True, exist_ok=True)
    if OUT_TAR.exists():
        OUT_TAR.unlink()
    ok_count = 0
    with tarfile.open(OUT_TAR, "w") as tf:
        for name, deb in deb_files:
            try:
                extract_deb_to_tar(deb, tf)
                ok_count += 1
            except Exception as e:
                print(f"  !! 写入失败 {name}: {e}")
    print(f"    成功写入 {ok_count}/{len(deb_files)} 个包")
    # 追加纯 Python 包（pip 纯 wheel → site-packages）
    pip_dir = CACHE / "pip-pure"
    pip_dir.mkdir(exist_ok=True)
    print("== 4.5 纯 Python 包（pip wheel 解压进 site-packages）==")
    subprocess.run(
        [sys.executable, "-m", "pip", "download", "--no-deps", "--only-binary", ":all:",
         "--platform", "any", "--implementation", "py", "--abi", "none", "--python-version", "3",
         "--dest", str(pip_dir)] + PIP_PURE_PACKAGES,
        check=False,
    )
    site_packages = None
    with tarfile.open(OUT_TAR, "r") as tf:
        for m in tf.getmembers():
            if m.isfile() and "/site-packages/" in m.name and "python3" in m.name:
                site_packages = m.name[: m.name.index("site-packages") + len("site-packages")]
                break
    if site_packages:
        print(f"    site-packages 路径: {site_packages}")
        import zipfile
        with tarfile.open(OUT_TAR, "a") as tf:
            for whl in sorted(pip_dir.glob("*.whl")):
                try:
                    with zipfile.ZipFile(whl) as zf:
                        for zi in zf.infolist():
                            if zi.is_dir():
                                continue
                            data = zf.read(zi.filename)
                            ti = tarfile.TarInfo(f"{site_packages}/{zi.filename}")
                            ti.size = len(data)
                            ti.mode = 0o644
                            ti.mtime = int(whl.stat().st_mtime)
                            tf.addfile(ti, io.BytesIO(data))
                    print(f"    + {whl.name} ({whl.stat().st_size / 1024:.0f}KB)")
                except Exception as e:
                    print(f"    !! {whl.name}: {e}")
    else:
        print("    !! 未找到 site-packages 路径（python 未装入？）")
    size_mb = OUT_TAR.stat().st_size / 1024 / 1024
    print(f"\n[DONE] termux-root.tar ({size_mb:.0f} MB, 未压缩) -> {OUT_TAR}")
    verify(OUT_TAR)


def verify(tar_path: Path):
    """构建期校验：关键文件缺失直接失败（防打包静默退化）。"""
    import fnmatch
    print("== 5 构建期校验 ==")
    names = set()
    with tarfile.open(tar_path, "r") as tf:
        names = {m.name for m in tf.getmembers()}
    # tar 条目名带 data/data/com.termux/files 前缀（PREFIX 保留）→ 模式统一加 * 前缀
    checks = [
        ("*usr/lib/python3.*/threading.py", "python 标准库（threading）"),
        ("*usr/lib/libzstd.so.1", "libzstd 动态库"),
        ("*usr/bin/zip", "zip"),
        ("*usr/bin/unzip", "unzip"),
        ("*usr/bin/python3", "python3"),
        ("*usr/bin/bash", "bash"),
        ("*usr/lib/apt/methods/*", "apt methods"),
    ]
    failures = []
    for pat, label in checks:
        hits = [n for n in names if fnmatch.fnmatch(n, pat)]
        if not hits:
            failures.append(f"缺失 {label}（匹配 {pat}）")
        else:
            print(f"    [OK] {label}: {hits[0]}")
    dyn = [n for n in names if "lib-dynload/" in n]
    if len(dyn) < 50:
        failures.append(f"lib-dynload 仅 {len(dyn)} 个文件，python 包不完整（预期 >50）")
    else:
        print(f"    [OK] lib-dynload: {len(dyn)} files")
    if failures:
        for f in failures:
            print(f"  !! {f}")
        print("  !! 构建校验失败 —— 建议删除 build/termux-debs/ 缓存重建（旧缓存可能含过期 deb）")
        raise SystemExit(1)
    print("    校验全部通过")


if __name__ == "__main__":
    main()
