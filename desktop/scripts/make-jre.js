/*
 * 用 jlink 裁一个自带 JRE 打包进桌面端。
 *
 * 为什么需要：exe 里的后端是 `java -jar` 跑的，而面试官/朋友的机器上不一定装过 Java，
 * 没装的话双击只会启动失败。这里把 JDK 裁到只留后端真正用到的模块（约 50MB），
 * 由 electron-builder 放进 resources/jre，main.js 优先使用它。
 *
 * 用法：npm run jre（npm run dist 会自动先跑一次）
 * 前置：本机有 JDK 11（JAVA_HOME 指向它，或 PATH 里有 jlink）
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

// 后端实际用到的模块，少一个就会在启动时 NoClassDefFoundError：
//   java.sql            JDBC
//   java.naming         会话存储
//   java.net.http       调大模型 / 联网检索
//   java.desktop        图形验证码（AWT）
//   java.xml            配置解析
//   java.logging / java.management    Spring Boot / Tomcat
//   java.security.jgss  Tomcat 启动
//   java.instrument     Spring 类加载工具
//   jdk.unsupported / jdk.crypto.ec  反射与 TLS
const MODULES = [
    'java.base',
    'java.logging',
    'java.sql',
    'java.naming',
    'java.net.http',
    'java.desktop',
    'java.xml',
    'java.management',
    'java.security.jgss',
    'java.instrument',
    'jdk.unsupported',
    'jdk.crypto.ec'
];

const isWin = process.platform === 'win32';
const outDir = path.join(__dirname, '..', 'build', 'jre');
const jlink = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', isWin ? 'jlink.exe' : 'jlink')
    : 'jlink';

console.log('[jre] 使用 jlink：' + jlink);
fs.rmSync(outDir, { recursive: true, force: true });

try {
    execFileSync(jlink, [
        '--add-modules', MODULES.join(','),
        '--output', outDir,
        '--strip-debug',
        '--no-header-files',
        '--no-man-pages',
        '--compress=2'
    ], { stdio: 'inherit' });
} catch (e) {
    console.error('[jre] 生成失败：请确认已安装 JDK 11 并设置 JAVA_HOME。');
    process.exit(1);
}

let bytes = 0;
(function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            walk(p);
        } else {
            bytes += fs.statSync(p).size;
        }
    }
})(outDir);

console.log('[jre] 已生成：' + outDir + '（' + (bytes / 1024 / 1024).toFixed(1) + ' MB）');
