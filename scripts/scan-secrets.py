# -*- coding: utf-8 -*-
"""提交前扫描明文凭据：能直接花你钱的字符串，不许进仓库。

用法：
  python scripts/scan-secrets.py            # 只扫「已暂存」的文件（pre-commit 用）
  python scripts/scan-secrets.py --all      # 扫整个工作区（排查用）
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PATTERNS = [
    (re.compile(r"sk-[A-Za-z0-9]{20,}"), "API Key（sk-...）"),
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"), "GitHub Token"),
    (re.compile(r"AKID[A-Za-z0-9]{10,}"), "云厂商 AccessKey"),
    (re.compile(r"AIza[0-9A-Za-z_\-]{30,}"), "Google API Key"),
    (re.compile(r"(?i)(api[-_.]?key|secret|token|passwd|password)\s*[=:]\s*[\"']?([A-Za-z0-9_\-]{8,})[\"']?\s*$"),
     "明文凭据"),
]

# 这些是示例 / 占位 / 取变量，不算泄露
ALLOW = re.compile(
    r"(xxxx|your[-_]|<your|\$\{|placeholder|example|YOUR_|_KEY\b|"
    r"newPassword|oldPassword|currentPassword|confirmPassword|this\.|f\.|"
    r"getToken|setToken|String |password\(\))",
    re.I,
)

SKIP_EXT = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".zip", ".jar", ".exe", ".dll",
            ".woff", ".woff2", ".ttf", ".pdf", ".docx", ".xlsx", ".pak", ".dat"}
SKIP_PATH = ("node_modules/", "/target/", "/dist/", "/.git/", "package-lock.json", "/jre/")


def staged_files():
    out = subprocess.run(["git", "-C", ROOT, "diff", "--cached", "--name-only", "--diff-filter=ACM"],
                         capture_output=True, text=True).stdout
    return [p.strip() for p in out.splitlines() if p.strip()]


def all_files():
    for dp, dns, fns in os.walk(ROOT):
        dns[:] = [d for d in dns if d not in (".git", "node_modules", "target", "dist", "jre")]
        for f in fns:
            yield os.path.relpath(os.path.join(dp, f), ROOT)


def scan(paths):
    hits = []
    for rel in paths:
        if os.path.splitext(rel)[1].lower() in SKIP_EXT:
            continue
        if any(s in "/" + rel for s in SKIP_PATH):
            continue
        try:
            lines = open(os.path.join(ROOT, rel), encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for i, line in enumerate(lines, 1):
            if "scan-secrets:allow" in line or ALLOW.search(line):
                continue
            for rx, label in PATTERNS:
                if rx.search(line):
                    shown = line.strip()[:90]
                    hits.append((rel, i, label, shown))
                    break
    return hits


def main():
    paths = all_files() if "--all" in sys.argv else staged_files()
    hits = scan(paths)
    if not hits:
        print("[secrets] 没发现明文凭据 OK")
        return 0
    print("\n[secrets] 发现 %d 处疑似明文凭据，已拦截提交：\n" % len(hits))
    for rel, line, label, shown in hits:
        print("  %s:%d  [%s]\n      %s" % (rel, line, label, shown))
    print("\n处理方式：改成环境变量（如 ${AI_API_KEY}）、占位符，或把该文件加入 .gitignore。")
    print("确认是误报：在该行加注释 `scan-secrets:allow` 后重试。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
