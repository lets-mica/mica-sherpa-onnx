# mica-sherpa-onnx

![JDK 8](https://img.shields.io/badge/JDK-8+-brightgreen.svg)
[![Mica Maven release](https://img.shields.io/maven-central/v/net.dreamlu/mica-sherpa-onnx.svg?style=flat-square)](https://central.sonatype.com/artifact/net.dreamlu/mica-sherpa-onnx/versions)

将 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的 Java 绑定（java-api）与各平台预编译 native 库打包为一个 **fat jar**，通过 Central Portal 发布到 Maven Central。

## 特性

- **源码集成**：直接包含 sherpa-onnx java-api 全部 Java 源码（包名 `com.k2fsa.sherpa.onnx`）
- **全平台 native 库**：自动从 GitHub Releases 下载并打包 6 个平台的原生库
  - `linux-x64` (`.so`)
  - `linux-aarch64` (`.so`)
  - `win-x64` (`.dll`)
  - `win-arm64` (`.dll`)
  - `osx-x64` (`.dylib`)
  - `osx-aarch64` (`.dylib`)
- **单一 fat jar**：Java 类 + 所有平台 native 库合为一个 jar，无 classifier
- **Central Portal 发布**：使用 Gradle Nexus Publish Plugin 直接发布到 Maven Central

## Maven 坐标

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-sherpa-onnx</artifactId>
    <version>${version}</version>
</dependency>
```

```kotlin
implementation("net.dreamlu:mica-sherpa-onnx:${version}")
```

## 版本对照

本项目的版本号与 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的 release 版本号保持一致，Java 源码与对应 tag 的 `sherpa-onnx/java-api` 逐字节相同，native 库取自该 tag 的 Release 资产。

| mica-sherpa-onnx | sherpa-onnx | 说明 |
|---|---|---|
| 2.0.0 | v2.0.0 | 移除 espeak-ng / piper-phonemize，TTS 音素输入改由 `GenerationConfig` 提供 |
| 1.13.8 | v1.13.8 | 新增离线 CTC HLG 图解码 |
| 1.13.7 | v1.13.7 | |
| 1.13.6 | v1.13.6 | |

## 升级到 2.0.0 的破坏性变更

上游在 v2.0.0 移除了 espeak-ng 和 piper-phonemize，TTS 相关的 Java API 因此有若干**不兼容变更**，升级前请对照检查：

| 变更 | 影响 | 迁移方式 |
|---|---|---|
| `SpeechSegment.getStart()` 返回类型 `int` → `long` | 编译期不兼容 | 接收方变量改为 `long`；时间戳超 `Integer.MAX_VALUE` 毫秒（约 24.8 天）不再溢出 |
| `OfflineTtsVitsModelConfig` 等 TTS 配置的 `dataDir` / `setDataDir()` 标记 `@Deprecated` | 运行时**被忽略** | 改用 `setLexicon(...)`，或通过 `GenerationConfig.setPhonemeCodepoints(...)` 直接传音素 |
| 新增 `GenerationConfig.setPhonemeCodepoints(int[][])` / `setTokens(String[][])` | 新能力 | 自行做音素化（如 pypinyin）后传入，绕开内置 espeak |
| `OfflineTtsModelConfig.debug` 默认值 `true` → `false` | 行为变化 | 需要日志时显式 `setDebug(true)` |
| 新增 `OfflineTts.getLang()`、`generateWithConfig(String, GenerationConfig)` | 新能力 | 可直接获取模型语言（如 `"en-us"`），并在无文本（纯音素）场景下合成 |

```java
// 2.0.0 推荐的纯音素（无 espeak）合成方式
GenerationConfig config = new GenerationConfig();
config.setPhonemeCodepoints(new int[][]{{104, 101, 108, 108, 111}}); // "hello"
GeneratedAudio audio = tts.generateWithConfig("", config);
```

## 环境要求

- JDK 8+
- Gradle 8.x+（项目已包含 Gradle Wrapper）
- GPG 密钥（用于签名，发布时需要）
- Central Portal 账号（https://central.sonatype.com）

## 项目结构

```
mica-sherpa-onnx/
├── settings.gradle.kts          # 插件管理 + Central Portal 发布配置
├── build.gradle.kts             # 构建、native jar 处理、发布配置
├── gradle.properties            # 版本号 + 签名/凭据占位符
├── gradlew / gradlew.bat        # Gradle Wrapper 脚本
├── gradle/wrapper/              # Gradle Wrapper jar + properties
└── src/main/java/
    └── com/k2fsa/sherpa/onnx/   # sherpa-onnx Java API 源码 (102 个文件)
```

## 构建命令

### 编译并打包 fat jar

```bash
./gradlew build
```

构建产物 `build/libs/mica-sherpa-onnx-2.0.0.jar` 同时包含：
- 编译后的 Java 类
- 所有平台 native 库（路径: `sherpa-onnx/native/{platform}/`）

### 下载 native jar（单独执行）

```bash
./gradlew downloadNativeJars
```

native jar 缓存在 `build/native-jars/` 目录，避免重复下载。

### 清理

```bash
./gradlew clean          # 清理所有构建产物（包括 native jar 缓存）
./gradlew cleanNativeLibs  # 仅清理 native jar 缓存和解压文件
```

### 指定版本

```bash
./gradlew build -Pversion=1.14.0
```

## 发布到 Maven Central

### 1. 准备凭据

在 `~/.gradle/gradle.properties` 中配置（**不要**放在项目目录中）：

```properties
# GPG 签名
signing.keyId=ABCD1234
signing.password=your-gpg-password
signing.secretKeyRingFile=/path/to/secring.gpg

# Central Portal User Token（不是 Sonatype 账号密码）
# 登录 https://central.sonatype.com -> Account -> User Token -> Generate
sonatypeUsername=your-token-username
sonatypePassword=your-token-password
```

### 2. 发布

```bash
./gradlew publishAllPublicationsToCentralPortal
```

此命令会：
1. 编译 Java 源码
2. 下载并解压各平台 native jar
3. 打包 fat jar + sources jar + javadoc jar
4. GPG 签名所有 artifact
5. 发布到 Central Portal staging repository
6. 自动 close & release staging repository

发布后通常几分钟内即可在 Maven Central 搜索到。

### 分步发布（调试用）

```bash
# 仅发布到 staging（不自动 release）
./gradlew publishToSonatype

# 手动关闭并发布 staging repository
./gradlew closeAndReleaseSonatypeStagingRepository
```

## Native 库加载机制

fat jar 中的 native 库位于 `sherpa-onnx/native/{platform}/` 路径下，与 sherpa-onnx 原生的 `LibraryUtils.loadFromResourceInJar()` 加载逻辑完全兼容。

运行时，`LibraryUtils` 会：
1. 优先检查 `-Dsherpa_onnx.native.path` 系统属性
2. 从 jar 资源中提取当前平台的 native 库到临时目录并加载
3. 回退到 `-Djava.library.path`

因此用户只需在 classpath 中引入 fat jar，无需额外配置 native 库路径。

## 许可证

Apache License 2.0

## 致谢

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - K2-FSA 团队的语音识别/合成工具包
