# AGENTS.md

本文件面向在本仓库工作的 AI 编码代理与协作者，说明项目性质、不可违反的约束与操作流程。

## 项目是什么

`mica-sherpa-onnx` 把 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的 Java 绑定（`sherpa-onnx/java-api`）与 6 个平台的预编译 native 库，打包成**单个 fat jar**，通过 Central Portal 发布到 Maven Central。

```
mica-sherpa-onnx-<ver>.jar   约 57 MB
├── com/k2fsa/sherpa/onnx/**            # Java API（上游原样收录）
└── sherpa-onnx/native/{platform}/**    # 6 平台 native 库
```

Maven 坐标：`net.dreamlu:mica-sherpa-onnx`

## 最重要的不变量

**`src/main/java/com/k2fsa/sherpa/onnx/` 是上游 java-api 的逐字节镜像。**

- 不要手工编辑该目录下的任何文件。
- 不要"顺手修 bug"或"统一代码风格"——上游的怪癖（例如 `OnlineCtcFstDecoderConfig.getMaxActive()` 返回 `float` 而离线版返回 `int`）属于上游，不是本项目的缺陷。
- 该不变量必须可验证：任何时候都应能通过哈希比对确认与上游某个 tag 完全一致。

原因是 `native` 方法只有在名称与签名和 native 库完全匹配时才能链接。任何本地改动都会在下次同步时被覆盖，且可能悄悄破坏 JNI 约定。

## 版本号规则

`gradle.properties` 的 `version` **必须**与上游 release tag 严格一致（上游 `v2.0.0` → `version=2.0.0`）。

`build.gradle.kts` 用它拼接 native jar 下载地址：

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/v${version}/sherpa-onnx-native-lib-${platform}-${version}.jar
```

版本号写错 → 下载 404。支持的平台固定为：
`linux-x64`、`linux-aarch64`、`win-x64`、`win-arm64`、`osx-x64`、`osx-aarch64`

## 构建与验证

> Windows 环境：本机 Git Bash 缺 `ls/tail/sed/uname/xargs`，`./gradlew` 会在环境检测阶段失败。
> 一律用 PowerShell 调用 `gradlew.bat`，并将输出重定向到文件后读取（PowerShell 工具 stdout 常不回显）。

```powershell
# 编译
.\gradlew.bat compileJava --console=plain

# 完整打包（下载 6 平台 native jar，约 57 MB）
.\gradlew.bat build --console=plain

# 发布
.\gradlew.bat publishAllPublicationsToCentralPortal
```

### 强制重编（验证源码改动时必用）

`gradle.properties` 开启了 `org.gradle.caching=true`，改动源码后直接 `compileJava` 可能输出
`UP-TO-DATE` 或 `FROM-CACHE` 并显示 `BUILD SUCCESSFUL`——**这不代表新代码被编译过**。

```powershell
.\gradlew.bat clean compileJava --no-build-cache --rerun-tasks --console=plain
```

必须看到 `> Task :compileJava`（无后缀）才算真正编译。

### 三层验证

| 层 | 手段 | 验证目标 |
|---|---|---|
| 1 | 强制重编 | Java 源码自洽 |
| 2 | `build` | native jar 可下载、fat jar 组装正确 |
| 3 | JNI 冒烟测试 | **Java 声明与 native 实现真的对得上** |

第 3 层不可省略，它是唯一能暴露 `UnsatisfiedLinkError` 的手段：

```powershell
java -cp "build\libs\mica-sherpa-onnx-<ver>.jar" SmokeTest.java <ver>
```

期望 native 库自报版本等于 `<ver>`，并输出 `ALL CHECKS PASSED`。

## 同步上游新版本

完整流程见 `.workbuddy/skills/sherpa-onnx-sync/`（该目录被 gitignore，仅本机可用）。要点：

1. **先确认 tag 对齐**：在上游克隆里执行
   `git diff --stat vX.Y.Z..HEAD -- sherpa-onnx/java-api/`
   - 为空 → 可用当前 HEAD 同步
   - 非空 → **必须** `git checkout vX.Y.Z`，否则源码会领先 native 库，运行时崩

2. 比对本地与上游（比对前统一把 `\r\n` 归一化为 `\n`，否则 Windows 检出的 CRLF 会让所有文件看起来都变了）

3. 只复制新增/修改的文件，不整目录覆盖

4. 改 `gradle.properties` 版本号

5. 跑三层验证

6. 更新 `README.md`（版本对照表 + 破坏性变更表）与 `.workbuddy/memory/`

## 破坏性变更的记录义务

只要出现 `public` 签名变化，就必须同时更新：

1. `README.md` 的「版本对照」表
2. `README.md` 的「升级到 X 的破坏性变更」表（迁移写法需给出可运行示例）
3. 提交信息正文列出 breaking changes

**特别注意「静默失效」型变更**：被标 `@Deprecated` 但保留了 API 的配置项（如 v2.0.0 的 `dataDir` / `setDataDir()`）在运行时**被直接忽略**。用户升级后不会收到异常，只会得到错误结果。这类必须在 README 里显式警告。

## 代码与提交约定

- 源码编码 UTF-8，换行 LF。
- 通过 `-Xlint:-removal` 屏蔽上游 `finalize()` 带来的 Java 18+ 移除警告——这是有意为之，不要删。
- javadoc 用 `-Xdoclint:none`，因为上游有大量无注释的 native 声明；Maven Central 要求 javadoc jar，但放开严格检查是必要的妥协。
- 提交信息使用 Conventional Commits：

```
chore(version): 同步 1.13.6
chore(build): 1.13.7
feat(config): 增加离线解码器配置及相关功能支持
docs(readme): add status badges and update gradle wrapper
```

## 禁止事项

- 禁止手工修改 `src/main/java/com/k2fsa/sherpa/onnx/` 下的上游源码。
- 禁止在仓库内提交 GPG 私钥、Central Portal 凭据——这些只放在 `~/.gradle/gradle.properties`。
- 禁止修改 `nativePlatforms` 而不同步调整 README 的「特性」一节。
- 禁止在 JNI 冒烟测试未通过的情况下发布。
