# 构建 / Building

使用 JDK 17，把 `JAVA_HOME` 指向自己的 JDK；Gradle Wrapper 会下载 Gradle，构建依赖优先使用大陆镜像。
Use JDK 17 (`JAVA_HOME`) and the included Gradle Wrapper. Mainland China mirrors are used where available.

先从上游下载 Let Me Feed You! 1.0.0、生活调味料胡萝卜版 1.15.1、Curtain 1.3.2，将对应 JAR 放到 `references/`。准确文件名、上游地址与 SHA-256 见 [依赖清单](gradle/reference-dependencies.json)。清单中的文件仅供编译/开发；不随本仓库分发。
Download the exact upstream JARs listed in [the dependency manifest](gradle/reference-dependencies.json) into `references/` before building. Verify their SHA-256 checksums. These third-party mods are not redistributed here.

```sh
./gradlew build
```

Windows 使用 `gradlew.bat build`。产物位于 `build/libs/`；无需复制任何本机 Gradle 缓存。
On Windows use `gradlew.bat build`. Outputs are in `build/libs/`; no workstation Gradle cache is required.
