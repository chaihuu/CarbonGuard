# CarbonGuard 构建说明

## 1. 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 25（Paper 26.2 API 的 class 文件版本为 69） |
| Gradle | 9.8.0（仓库自带 wrapper） |
| Paper API | `io.papermc.paper:paper-api:26.2.build.134-stable` |
| Adventure | 5.2.0（由 Paper 26.2 提供） |
| Java 编译目标 | 25 |

确认 Java：

```bash
java -version
# openjdk version "25..." / java version "25..."
```

Gradle wrapper 会自行下载 Gradle 9.8.0，不需要系统安装 Gradle。构建需要访问 Maven Central、PaperMC 仓库和 OpenCollab 仓库。

## 2. 构建

Linux/macOS（首次使用如提示权限不足，先执行 `chmod +x gradlew`）：

```bash
./gradlew clean build
```

Windows PowerShell：

```powershell
.\gradlew.bat clean build
```

产物：

```text
build/libs/CarbonGuard-1.0.0.jar
```

这是一个包含运行时依赖的 shaded jar，直接放入 Paper 的 `plugins/` 即可。不要把 `-sources`、`-javadoc` 或其他 classifier 当成插件 jar。

只编译不打包：

```bash
./gradlew compileJava
```

运行测试：

```bash
./gradlew test
```

测试报告：`build/reports/tests/test/index.html`。

## 3. 依赖与作用域

`build.gradle.kts` 中的关键依赖：

| 依赖 | 版本 | 作用域 | 是否打进 jar |
| --- | --- | --- | --- |
| `io.papermc.paper:paper-api` | `26.2.build.134-stable` | `compileOnly` | 否，服务器提供 |
| `org.geysermc.floodgate:api` | `2.2.5-SNAPSHOT` | `compileOnly` | 否，Floodgate 提供 |
| `org.geysermc.geyser:api` | `2.12.0-SNAPSHOT` | `compileOnly` | 否，Geyser 提供 |
| `com.zaxxer:HikariCP` | `7.1.0` | `implementation` | 是 |
| `org.xerial:sqlite-jdbc` | `3.53.4.0` | `implementation` | 是 |
| `com.sun.mail:jakarta.mail` | `2.0.2` | `implementation` | 是 |
| JUnit Jupiter | `6.1.3` | `testImplementation` | 否 |

说明：

- Paper 26.2 已包含 Adventure 5.2、Gson 和 SLF4J 2.x，插件不重复打包这些 API。HikariCP/SQLite 的 `slf4j-api` 传递依赖已显式排除。
- Floodgate/Geyser 是软依赖：`plugin.yml` 中声明 `softdepend: [floodgate, Geyser-Spigot, Geyser]`，入口通过类存在性检查隔离，未安装时不会出现 `NoClassDefFoundError`。
- HikariCP、SQLite JDBC、Jakarta Mail 被打进最终 jar。没有做 package relocation，因为 Paper 插件类加载器隔离；如果你在同一服务器上使用了自行打包同名类且发生冲突，可在 Shadow 配置中加入 `relocate`，但重定位 SQLite 时必须同时验证 JNI 原生库路径。
- Shadow 使用 `mergeServiceFiles()` 以保留 `java.sql.Driver` 与 `jakarta.mail.Provider` 服务文件。`duplicatesStrategy = INCLUDE` 避免 ServiceFileTransformer 前被去重。

### 3.1 关于 SNAPSHOT 依赖

Floodgate/Geyser 的 API 通过 OpenCollab snapshot 仓库发布。`2.2.5-SNAPSHOT` / `2.12.0-SNAPSHOT` 会在构建时解析到最新时间戳构建。若需要完全可复现构建，请把版本固定为仓库中的时间戳 artifact（例如 `2.2.5-20260917.145236-21`），或使用内部 Nexus 代理并锁定。

可先查看依赖树：

```bash
./gradlew dependencies --configuration compileClasspath
```

### 3.2 Paper 26.2 与 Adventure 5

插件刻意避免 Adventure 4.x 的旧组件构建器：

- 不使用 `BuildableComponent`、`ComponentBuilder` 等已移除 API；
- 不使用 `BookMeta` 的旧 `Book` 接口；
- 不使用已移除的 `ClickEvent` / `HoverEvent` 弃用用法；
- 文本统一用 `Component` + `LegacyComponentSerializer`（读取 `messages.yml` 的 `&` 颜色码）。

如果你修改代码，请以 [Paper 26.2 Javadocs](https://jd.papermc.io/paper/26.2/) 为准；不要使用 1.21.x 的 API 假设。

## 4. 连接池配置

`config.yml`：

```yaml
storage:
  sqlite-file: "carbonguard.db"
  pool:
    login-maximum-pool-size: 4
    web-maximum-pool-size: 4
    connection-timeout-ms: 5000
  cache-refresh-seconds: 60
```

实现：

- `carbonguard-core`：登录侧缓存刷新、后台写入、事务；
- `carbonguard-web`：Web 面板查询；
- 两个 Hikari 池物理隔离；
- 写操作由单线程执行器串行化，SQLite 只有一个写者；
- SQLite 开启 WAL、`busy_timeout=10000`、`foreign_keys=ON`。

调整建议：

- 玩家量大时优先增加 `cache-refresh-seconds` 以降低刷新频率，而不是无限扩大 SQLite 池；
- Web 查询慢时保持 `web-maximum-pool-size` 较小，避免 SQLite 读连接挤占写连接；
- 修改池大小或数据库路径需要重启服务器，`/carbonguard reload` 只对其他配置生效。

## 5. 构建后冒烟测试

```powershell
# 准备一个 Paper 26.2 测试服
java -Xms512M -Xmx1G -jar paper-26.2-134.jar --nogui

# 把 build/libs/CarbonGuard-1.0.0.jar 放入 plugins/
```

期望日志：

```text
[CarbonGuard] SQLite 数据层已就绪: .../plugins/CarbonGuard/carbonguard.db
[CarbonGuard] 登录缓存已预热: bindings=0, activeBans=0, ipAssociations=0
[CarbonGuard] Web 面板已启动: http://127.0.0.1:8080 ...
[CarbonGuard] CarbonGuard 已启用 | Paper 26.2 | Floodgate=... | 未绑定拦截=...
```

验证 Web：

```bash
curl -i http://127.0.0.1:8080/
curl -i http://127.0.0.1:8080/api/admin/stats \
  -H "X-CarbonGuard-Key: <你的密钥>"
```

无密钥应返回 `401`。

## 6. 常见构建问题

### `Unsupported class file major version 69`

JDK 低于 25。Paper 26.2 API 由 Java 25 编译，必须使用 JDK 25，且 Gradle toolchain 指向 25。

### 找不到 Floodgate / Geyser 依赖

检查仓库是否可达：

```bash
./gradlew dependencies --configuration compileClasspath
```

构建配置包含：

- `https://repo.papermc.io/repository/maven-public/`
- `https://repo.opencollab.dev/maven-releases/`
- `https://repo.opencollab.dev/maven-snapshots/`

### `NoClassDefFoundError: org/geysermc/...`

不要把 Floodgate/Geyser API 放到 `implementation`：它们是可选的，且只能 `compileOnly`。运行时应由服务器上的 Floodgate/Geyser 插件提供。插件已有类存在性检查；出现该错误通常说明修改了依赖作用域。

### `META-INF/services` 冲突

确认 `tasks.shadowJar` 中保留：

```kotlin
duplicatesStrategy = DuplicatesStrategy.INCLUDE
mergeServiceFiles()
```

### SQLite 原生库警告

Java 25 对 JNI 加载给出 restricted-method 警告：

```text
WARNING: A restricted method in java.lang.System has been called
```

这是提示而非失败。服务端启动参数可加 `--enable-native-access=ALL-UNNAMED` 消除警告，或等待 SQLite JDBC 更新；不影响功能。

## 7. GPLv3 与许可证检查

CarbonGuard 使用 **GPL-3.0-only**（不是 GPL-3.0-or-later）。仓库根目录包含：

```text
LICENSE                    # GNU GPL v3 完整正文
COPYING                    # GPL 指针
THIRD_PARTY_NOTICES.md     # 捆绑依赖与运行时依赖说明
LICENSES/Apache-2.0.txt    # HikariCP / SQLite JDBC
LICENSES/EPL-2.0.txt       # Jakarta Mail / Activation
```

每个 Java 源文件必须以 SPDX 头开始：

```java
/*
 * CarbonGuard
 * Copyright (C) 2026 chaihuu
 * ...
 * SPDX-License-Identifier: GPL-3.0-only
 */
```

构建时自动验证：

```bash
./gradlew verifyLicenseHeaders
```

该任务也挂到 `check`，因此 `./gradlew build` 会在缺少 SPDX 头或 GPL 正文缺失时失败。

构建会把 `LICENSE`、`COPYING`、`THIRD_PARTY_NOTICES.md`、`LICENSES/` 放入
插件 jar，并在 `MANIFEST.MF` 写入 `License: GPL-3.0-only` 与源码地址。

如果你再分发修改后的 jar，必须同时提供对应完整源代码；GPLv3 不允许附加额外限制。

## 8. 发布检查清单

- [ ] `./gradlew clean build` 成功
- [ ] `./gradlew verifyLicenseHeaders` 成功
- [ ] `build/libs/CarbonGuard-1.0.0.jar` 中包含 `plugin.yml`、`config.yml`、`messages.yml`、`web/*`
- [ ] jar 中包含 `LICENSE`、`COPYING`、`THIRD_PARTY_NOTICES.md`、`LICENSES/*`
- [ ] 在 Paper 26.2 + Java 25 上启动无 `NoClassDefFoundError`
- [ ] `web.admin-key` 为强随机值，或来自环境变量
- [ ] SMTP 用端口 465/587 实测发送
- [ ] Geyser/Paper 的 PROXY protocol 三处配置齐全，确认日志/IP 统计中是玩家真实 IP
- [ ] 反向代理启用 HTTPS，`trust-forwarded-for` 只在可信代理后启用
- [ ] 备份 `plugins/CarbonGuard/carbonguard.db`
- [ ] 生产环境不要把 `web.bind-address` 直接暴露到公网
