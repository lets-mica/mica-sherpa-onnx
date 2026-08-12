// ============================================================================
// mica-sherpa-onnx build.gradle.kts
// ============================================================================
// 将 sherpa-onnx 的 Java 绑定（java-api）与各平台 native 库打包为一个 fat jar
// 通过 Central Portal 发布到 Maven Central
// ============================================================================

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

plugins {
    `java-library`
    `maven-publish`
    signing
    // Gradle Nexus Publish Plugin: 通过 Central Portal 发布到 Maven Central
    // 自动化管理 staging repository 的创建、关闭和发布
    // 文档: https://github.com/gradle-nexus/publish-plugin
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

// ============================================================================
// 项目坐标
// ============================================================================
group = "net.dreamlu"
// version 由 gradle.properties 中的 version 属性控制，可用 -Pversion=xxx 覆盖

// ============================================================================
// Central Portal (https://central.sonatype.com) 发布配置
// ============================================================================
// 使用 sonatype 仓库别名，覆盖 URL 指向新的 Central Portal staging API
// 凭据通过 sonatypeUsername / sonatypePassword 项目属性自动读取
//   - 在 ~/.gradle/gradle.properties 中设置
//   - 或通过环境变量 ORG_GRADLE_PROJECT_sonatypeUsername / ORG_GRADLE_PROJECT_sonatypePassword
// ============================================================================
nexusPublishing {
    repositories {
        sonatype {
            // Central Portal 的新 staging API 地址
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

// ============================================================================
// Java 编译配置 (Java 8+)
// ============================================================================
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    // 自动生成 sources jar 和 javadoc jar（Maven Central 要求）
    withSourcesJar()
    withJavadocJar()
}

// 强制使用 UTF-8 编码（sherpa-onnx 源码包含中文注释和特殊字符）
// 屏蔽 [removal] 警告: sherpa-onnx 绑定代码中使用了 finalize()（Java 18+ 标记为待删除），源码同步自上游不便修改
tasks.compileJava {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:-removal"))
}

repositories {
    mavenCentral()
}

// ============================================================================
// Javadoc 配置：sherpa-onnx 源码中有大量 native 方法声明，关闭严格检查
// ============================================================================
tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        // 设置字符集为 UTF-8（sherpa-onnx 源码包含中文注释）
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        // 忽略 native 方法导致的 javadoc 警告
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// ============================================================================
// Native 库平台定义
// ============================================================================
// 每个平台对应一个 GitHub Release 中的 native jar
// URL 格式: https://github.com/k2-fsa/sherpa-onnx/releases/download/v${version}/sherpa-onnx-native-lib-${platform}-${version}.jar
val nativePlatforms = listOf(
    "linux-x64",
    "linux-aarch64",
    "win-x64",
    "win-arm64",
    "osx-x64",
    "osx-aarch64"
)

// native jar 下载缓存目录
val nativeJarDir = layout.buildDirectory.dir("native-jars")
// native 库解压目录
val nativeLibDir = layout.buildDirectory.dir("native-libs")

// native jar 中的原生库文件扩展名
val nativeLibExtensions = setOf(".so", ".dll", ".dylib", ".jnilib")

// ============================================================================
// 任务: downloadNativeJars
// 从 GitHub Releases 下载各平台 native jar 到本地缓存（避免重复下载）
// ============================================================================
val downloadNativeJars by tasks.registering {
    group = "sherpa"
    description = "从 GitHub Releases 下载各平台 native jar 到本地缓存目录"

    val sherpaVersion = project.version.toString()
    val cacheDir = nativeJarDir.get().asFile

    inputs.property("sherpaVersion", sherpaVersion)
    outputs.dir(cacheDir)

    doLast {
        cacheDir.mkdirs()
        logger.lifecycle("========================================")
        logger.lifecycle("下载 sherpa-onnx native jars (v${sherpaVersion})")
        logger.lifecycle("========================================")

        nativePlatforms.forEach { platform ->
            val jarName = "sherpa-onnx-native-lib-${platform}-${sherpaVersion}.jar"
            val jarFile = File(cacheDir, jarName)

            if (jarFile.exists() && jarFile.length() > 0) {
                logger.lifecycle("  [缓存] ${jarName} 已存在，跳过下载")
                return@forEach
            }

            val downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${sherpaVersion}/${jarName}"
            logger.lifecycle("  [下载] ${downloadUrl}")

            downloadFile(downloadUrl, jarFile)

            val sizeMB = jarFile.length() / 1024 / 1024
            logger.lifecycle("  [完成] ${jarName} (${sizeMB} MB)")
        }

        logger.lifecycle("========================================")
        logger.lifecycle("所有 native jar 下载完成")
        logger.lifecycle("========================================")
    }
}

// ============================================================================
// 任务: extractNativeLibs
// 解压各平台 native jar，提取 .so/.dll/.dylib 文件
// 保持原始路径结构: sherpa-onnx/native/{platform}/{lib-file}
// ============================================================================
val extractNativeLibs by tasks.registering {
    group = "sherpa"
    description = "解压 native jar，提取原生库文件（.so/.dll/.dylib）"

    dependsOn(downloadNativeJars)

    val sherpaVersion = project.version.toString()
    val cacheDir = nativeJarDir.get().asFile
    val extractDir = nativeLibDir.get().asFile

    inputs.files(nativeJarDir)
    inputs.property("sherpaVersion", sherpaVersion)
    outputs.dir(extractDir)

    doLast {
        extractDir.deleteRecursively()
        extractDir.mkdirs()

        logger.lifecycle("========================================")
        logger.lifecycle("解压 native 库文件")
        logger.lifecycle("========================================")

        nativePlatforms.forEach { platform ->
            val jarName = "sherpa-onnx-native-lib-${platform}-${sherpaVersion}.jar"
            val jarFile = File(cacheDir, jarName)

            if (!jarFile.exists()) {
                logger.warn("  [跳过] native jar 不存在: ${jarName}")
                return@forEach
            }

            logger.lifecycle("  [解压] ${jarName}")

            ZipFile(jarFile).use { zip ->
                // 只提取原生库文件（.so/.dll/.dylib），跳过目录和其他文件
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { entry -> nativeLibExtensions.any { entry.name.endsWith(it) } }
                    .forEach { entry ->
                        val entryName = entry.name

                        // 保持原始路径结构 (sherpa-onnx/native/{platform}/{file})
                        val outFile = File(extractDir, entryName)
                        outFile.parentFile.mkdirs()

                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val sizeKB = outFile.length() / 1024
                        logger.lifecycle("    -> ${entryName} (${sizeKB} KB)")
                    }
            }
        }

        logger.lifecycle("========================================")
        logger.lifecycle("Native 库解压完成: ${extractDir.absolutePath}")
        logger.lifecycle("========================================")
    }
}

// ============================================================================
// 配置 jar 任务: 将 native 库打包进 fat jar
// fat jar 同时包含编译后的 Java 类和所有平台的原生库
// ============================================================================
tasks.jar {
    dependsOn(extractNativeLibs)

    // 将解压后的 native 库文件包含进 jar，保持原始路径结构
    from(nativeLibDir) {
        // 路径结构: sherpa-onnx/native/{platform}/{lib-file}
        // 这与 LibraryUtils.loadFromResourceInJar() 的查找路径一致
    }

    logger.lifecycle("配置 fat jar: 包含 Java 类 + 所有平台 native 库")
}

// ============================================================================
// 任务: cleanNativeLibs
// 清理下载的 native jar 和解压的 native 库
// ============================================================================
val cleanNativeLibs by tasks.registering(Delete::class) {
    group = "sherpa"
    description = "清理 native jar 缓存和解压的 native 库文件"
    delete(nativeJarDir)
    delete(nativeLibDir)
}

// 将 cleanNativeLibs 关联到标准 clean 任务
tasks.clean {
    dependsOn(cleanNativeLibs)
}

// ============================================================================
// 发布配置
// ============================================================================
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "mica-sherpa-onnx"

            // 不设置 classifier，fat jar 作为主 artifact

            pom {
                name.set("mica-sherpa-onnx")
                description.set("sherpa-onnx Java bindings with native libraries for all platforms (linux-x64, linux-aarch64, win-x64, osx-x64, osx-aarch64)")
                url.set("https://github.com/lets-mica/mica-sherpa-onnx")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    url.set("https://github.com/lets-mica/mica-sherpa-onnx")
                    connection.set("scm:git:git://github.com/lets-mica/mica-sherpa-onnx.git")
                    developerConnection.set("scm:git:ssh://github.com/lets-mica/mica-sherpa-onnx.git")
                }

                developers {
                    developer {
                        id.set("dreamlu")
                        name.set("ChunmengLu")
                        email.set("qq596392912@gmail.com")
                    }
                }
            }
        }
    }
}

// ============================================================================
// GPG 签名配置
// ============================================================================
// 凭据通过以下项目属性自动读取（在 ~/.gradle/gradle.properties 中设置）:
//   signing.keyId=YOUR_GPG_KEY_ID (最后8位)
//   signing.password=YOUR_GPG_PASSWORD
//   signing.secretKeyRingFile=/path/to/secring.gpg
// ============================================================================
signing {
    sign(publishing.publications["mavenJava"])
}

// ============================================================================
// 便捷发布任务: publishAllPublicationsToCentralPortal
// 一步完成: 发布所有 artifacts 到 Central Portal 并自动 close & release staging repo
// ============================================================================
tasks.register("publishAllPublicationsToCentralPortal") {
    group = "publishing"
    description = "发布所有 artifacts 到 Central Portal 并自动 close & release staging repository"

    // 1. 发布所有 publications 到 Sonatype staging repository
    dependsOn("publishToSonatype")

    // 2. 自动关闭并发布 staging repository
    finalizedBy("closeAndReleaseSonatypeStagingRepository")
}

// ============================================================================
// 辅助函数: 下载文件（支持重定向）
// ============================================================================
fun downloadFile(urlStr: String, dest: File) {
    val url = URL(urlStr)
    val conn = url.openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 30_000
    conn.readTimeout = 120_000
    conn.requestMethod = "GET"
    conn.connect()

    val responseCode = conn.responseCode
    if (responseCode !in 200..299) {
        conn.disconnect()
        throw GradleException("下载失败: HTTP $responseCode - $urlStr")
    }

    conn.inputStream.use { input ->
        dest.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    conn.disconnect()
}
