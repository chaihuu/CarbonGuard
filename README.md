# CarbonGuard

[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)

Paper 26.2 综合管控插件：基岩版真实 IP 接入、XUID 身份、单 IP 登录限制、IP 关联封禁、QQ 邮箱绑定、Web 复核面板。

- 项目名：`CarbonGuard`
- Java 包名：`xyz.chaihu.carbonguard`
- 主类：`xyz.chaihu.carbonguard.CarbonGuardPlugin`
- 目标平台：Paper 26.2（Adventure 5.2）
- 构建产物：`build/libs/CarbonGuard-1.3.0.jar`
- 源码仓库：https://github.com/chaihuu/CarbonGuard
- 许可证：GPL-3.0-only，完整文本见 [LICENSE](LICENSE)

## 1. 功能

| 模块 | 行为 |
| --- | --- |
| Geyser 真实 IP | 依赖 Geyser/Paper 的 PROXY protocol 配置；插件读取 Paper 提供的真实连接地址 |
| 身份 | Floodgate 存在时以 XUID 为主键；Geyser 直连且无 Floodgate 时以 UUID 降级并标记 `UUID_FALLBACK`；Java 玩家使用 UUID |
| 单 IP 限制 | 在线会话 + 正在登录预留计数，支持宽限阈值、IP/CIDR 白名单、玩家白名单、`REJECT` / `WARN_ONLY`；基岩版（Geyser）玩家默认自动豁免 |
| IP 关联封禁 | 封禁时记录当前与历史 IP，按 `REJECT` / `PENDING_REVIEW` / `LOG_ONLY` 处理；TTL 衰减 |
| 邮箱绑定 | 玩家访问 `http://你的域名/bind` 输入 ID，服务器按 UUID/XUID/游戏名自动匹配账号，显示 QQ 头像，验证码只发往 `<ID>@qq.com` |
| 入服拦截 | 默认开启；未绑定邮箱时拒绝登录，踢出消息给出 `/bind` 地址与玩家 ID |
| 玩家数据 | 记录初次加入、绑定时间、最近登录、登录次数、累计游玩时长与最近 IP |
| Web 面板 | 绑定验证、绑定管理/解绑、封禁查询、玩家数据、统计概览；默认端口 9981 |
| 复核 | 通过后按 (类型, 身份, IP) 抑制重复待审，可配置有效期 |
| 网络粒度 | IPv4 精确匹配；IPv6 默认按 /64 聚合，避免隐私地址轮换绕过限制 |
| 匿名统计 | 可选 bStats，上报聚合模块状态，不上报玩家/IP/邮箱/密钥 |
| 自动更新 | 异步检查 GitHub Releases，下载 jar 到 `plugins/update/`，重启后生效 |
| 数据层 | SQLite + HikariCP，双连接池隔离登录侧与 Web 查询；登录判定只读预热缓存 |

## 2. 安全边界（请先读）

### 2.1 Geyser 真实 IP

必须同时开启以下三处，缺一不可：

`Geyser/config.yml`：

```yaml
bedrock:
  enable-proxy-protocol: true
  proxy-protocol-whitelisted-ips: [ "127.0.0.1", "172.18.0.0/16" ]

remote:
  use-proxy-protocol: true
```

`Paper/paper-global.yml`：

```yaml
proxies:
  proxy-protocol: true
```

`proxy-protocol-whitelisted-ips` 是安全边界，不是可选装饰。只开启 `enable-proxy-protocol` 而不限制来源，任何直连 Geyser 的客户端都能伪造 PROXY header，插件拿到的真实 IP 会被污染，单 IP 限制和 IP 关联封禁会同时失效。

生产白名单至少包含实际代理的出口 IP/CIDR；空列表表示不限制来源，不推荐生产使用。

另外，`enable-proxy-protocol: true` 且白名单非空时，Geyser 会整体关闭 RakNet 包限流，因为它假设上游代理已经承担抗攻击职责。这是预期行为，但部署时应知晓，并在上游做连接频率/DDoS 防护。

### 2.2 全局白名单与代理

`whitelist.ips` 会绕过入服拦截、单 IP 限制和 IP 关联封禁动作（直接按 XUID/UUID 封禁仍然生效）。

默认 `whitelist.ips` 为空。**不要**在 PROXY protocol 未生效时把 `127.0.0.1` 放进白名单：如果 Paper/Geyser 后的所有玩家都显示为 `127.0.0.1`，这会让所有玩家绕过未绑定、单 IP 和 IP 关联管控。上线前必须确认插件看到的是玩家真实 IP。

### 2.3 入服拦截（默认开启）

`email-verify.require-binding` 默认是 `true`：未完成邮箱绑定的玩家会被拒绝入服，踢出消息给出绑定页面地址（默认 `http://你的域名/bind`）与玩家 ID。

要让它真正生效且不误伤，部署前必须满足以下条件：

1. Web 面板启动成功；
2. `web.admin-key` 强度达标；
3. `web.public-url` 是远程可达的非回环地址（建议 HTTPS）；
4. `email-verify.enable` 与 `binding.enable` 为 true。

第 1–4 项缺失时，插件会在启动日志中给出 ERROR，并**自动降级关闭入服拦截**，避免配置错误把全体玩家锁在服务器外；修复后执行 `/carbonguard reload`。

SMTP 不再参与拦截开关：绑定页面向 `<ID>@qq.com` 发送验证码，SMTP 不可用时只有未绑定玩家无法获取验证码，**已绑定玩家不受影响**，日志会给出警告。

SMTP 配置（host/from，以及 username 有值时 password 不为空）仍需完整，否则玩家无法完成绑定：

```yaml
smtp:
  enable: true
  host: "smtp.qq.com"
  port: 587
  username: "123456789@qq.com"
  password: "QQ邮箱授权码"
  from: "123456789@qq.com"
```

> 首次安装且未填写 `admin-key`/`public-url` 时，拦截会处于“已配置但未生效”的降级状态；`/carbonguard status` 会显示“入服拦截: 关闭”。完成第 3 节配置后重新执行 `/carbonguard reload` 即正式开启。

### 2.4 配置文件自动升级

`config.yml` 带有 `config-version`。启动或 `/carbonguard reload` 时，若文件版本低于插件版本，会：

1. 在内存中迁移（删除已废弃的键、按默认值补齐新增的 `binding.*`、更新 `config-version`）；
2. 把原文件备份为 `config.<时间戳>.yml`；
3. 一次性写回 `config.yml`（自动生成的管理密钥与迁移结果同一次写入，不会互相覆盖）；
4. 在启动日志与 `/carbonguard reload` 的提示中说明升级结果。

迁移不会覆盖已有的运营配置，只在键缺失时填入默认值。当前版本为 `config-version: 2`。

## 3. 快速开始

1. 安装 Paper 26.2，Java 25。
2. 把 `CarbonGuard-1.3.0.jar` 放入 `plugins/`。
3. 启动一次服务器，生成 `plugins/CarbonGuard/config.yml` 与 `messages.yml`。
4. 管理密钥：**`web.admin-key` 留空即可**。首次启动会从安全随机源生成 64 字符密钥、写入 `config.yml`，并在启动日志打印一次；`/carbonguard status` 也会显示“自动生成”的密钥（仅对 `carbonguard.admin` 可见）。日志中的密钥请立即保存。

   如果希望密钥不落盘，也可以自行生成并写入 `web.admin-key`，或用环境变量：

   Linux/macOS：

   ```bash
   openssl rand -base64 48
   ```

   Windows PowerShell：

   ```powershell
   $b = New-Object byte[] 48
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
   [Convert]::ToBase64String($b)
   ```

   ```yaml
   web:
     admin-key: "${ENV:CARBONGUARD_WEB_KEY}"
   ```

   注意：一旦 `config.yml` 中已有非空 `admin-key`，插件不会再生成新密钥；修改后需要 `/carbonguard reload` 或重启生效。

5. 配置 QQ 邮箱 SMTP。QQ 邮箱必须填**授权码**，不是 QQ 登录密码：

   ```yaml
   smtp:
     enable: true
     host: "smtp.qq.com"
     port: 587
     username: "123456789@qq.com"
     password: "QQ邮箱授权码"
     from: "123456789@qq.com"
     enable_ssl: false
   ```

   465 端口时使用 `enable_ssl: true`，587 端口时使用 `enable_ssl: false` + STARTTLS。端口由服务商决定，配置中可改，插件不硬编码 587。

6. 公网访问时设置真实地址并置于 HTTPS 反向代理后：

   ```yaml
   web:
     bind-address: "127.0.0.1"
     port: 9981
     public-url: "https://carbonguard.example.com"
   ```

   修改 `config.yml` 后执行 `/carbonguard reload`；`/carbonguard status` 会显示管理密钥来源（自动生成/已配置）和入服拦截状态。

7. 可选：配置 bStats 与自动更新：

   ```yaml
   bstats:
     enable: true
     plugin-id: 0          # 在 bStats 注册后填写；0 = 关闭
   update-check:
     enable: true
     endpoint: "https://api.github.com/repos/chaihuu/CarbonGuard/releases/latest"
     interval-minutes: 360
     download: true        # 下载到 plugins/update/，重启后生效
   ```

8. 执行 `/carbonguard reload` 或重启服务器。

首次默认配置中 `web.admin-key` 为空、SMTP 未填完整，因此 Web 面板不会启动，入服拦截会自动降级关闭。这是刻意的安全行为，不会把服务器锁死；填完 `admin-key`、SMTP 与 `public-url` 并 `/carbonguard reload` 后即按默认配置正式拦截未绑定玩家。

## 4. 身份模型

### 4.1 Floodgate 存在

```java
FloodgateApi api = FloodgateApi.getInstance();
if (api.isFloodgatePlayer(uuid)) {
    FloodgatePlayer fp = api.getPlayer(uuid);
    String xuid = fp.getXuid();
}
```

`isFloodgatePlayer` 与 `getPlayer` 在 `AsyncPlayerPreLoginEvent` 中可用，无需等玩家上线。基岩版身份键为 `xuid:<XUID>`，`identity_source = FLOODGATE`。Java 玩家 `isFloodgatePlayer` 返回 false，按 Java UUID 处理。

### 4.2 无 Floodgate

Geyser 直连且没有 Floodgate 时，无法通过 Floodgate API 获取 XUID。插件通过 Geyser API 判断 Bedrock 连接，用 Bukkit UUID 暂存：

- `identity_key = uuid:<UUID>`
- `identity_source = UUID_FALLBACK`
- 不从 UUID 反推 XUID，也不把猜测的 XUID 作为业务主键

插件会把 Geyser 连接提供的权威 XUID 记录为 `observed_xuid`。之后安装 Floodgate 并重启，启动阶段会在缓存预热前把 `UUID_FALLBACK` 记录迁移到 `xuid:<XUID>`，同时迁移绑定、IP 历史、封禁、待审和统计记录。旧键写入 `identity_aliases` 供审计。绝不根据 UUID 格式反推 XUID。

## 5. 登录判定顺序

`AsyncPlayerPreLoginEvent`（HIGHEST）中按顺序执行，全部只读内存缓存：

1. 解析身份与真实 IP
2. 直接封禁（XUID/UUID）→ 立即拒绝
3. 入服拦截（默认开启）→ 未绑定玩家被拒绝，踢出消息给出 `/bind` 页面地址与玩家 ID
4. IP 关联封禁动作
   - `REJECT` → 拒绝
   - `PENDING_REVIEW` → 放行并写待审
   - `LOG_ONLY` → 仅记录
5. 单 IP 限制
   - 活跃数达到 `max-accounts-per-ip + grace-threshold` 后触发
   - `REJECT` → 拒绝
   - `WARN_ONLY` → 放行并写待审
6. 放行并创建 90 秒登录预留；`PlayerJoinEvent` 转为在线会话，退出/连接关闭时释放

并发登录的预留机制保证多个连接同时完成 pre-login 时不会一起绕过限制。

### 5.1 共享 IP 误伤

NAT、校园网、公司出口、移动网络 CGNAT、VPN/代理会让大量合法玩家共用同一公网 IP。

- 默认 `ip-limit.mode: REJECT`，但建议配合 `grace-threshold`；
- `ip-limit.exempt-bedrock: true`（默认）时，经 Geyser 连接的基岩版玩家自动豁免单 IP 限制，不占用也不消耗名额；Java 玩家、以及无法通过 Geyser 确认为在线基岩版的连接仍正常计数；
- `ip-association.action` 默认 `PENDING_REVIEW`，而不是 `REJECT`；
- `WARN_ONLY` / `PENDING_REVIEW` 的登录会进入 Web 待审队列；
- 关联 IP 默认 168 小时衰减，避免历史 IP 永久累积；
- `whitelist-ips` 支持 CIDR，`whitelist-players` 支持 XUID/UUID。

> 基岩版豁免能让同一家庭出口下的多个手机/主机玩家正常登录，但也意味着限制只约束 Java 端；如需对基岩版同样计数，把 `ip-limit.exempt-bedrock` 设为 `false`。

## 6. 邮箱与绑定流程

1. 未绑定玩家被踢出，消息包含绑定页面与 ID：
   ```
   请打开下面的绑定页面完成邮箱绑定
   https://面板/bind
   你的 ID: <UUID>
   ```
2. 玩家打开 `https://面板/bind`，输入账号 ID（游戏内 UUID、基岩版 XUID 或游戏名）。
3. 服务端用 `identities` 索引匹配账号（UUID 有/无连字符、XUID、游戏名），返回玩家名、UUID 与 QQ 头像，页面确认后再继续。
4. 绑定邮箱固定为 `<ID>@qq.com`，页面只读展示，玩家无法改成其他地址。
5. 服务端向该邮箱发送 6 位验证码，返回一次性 `requestToken`（浏览器只持有它，验证码以 PBKDF2 存储）。
6. 玩家提交验证码；事务内校验、立即消费验证码、写入绑定。
7. 验证码最多尝试 5 次，成功后立即失效，不能重放；同一 ID 重新发送有冷却时间。

没有一次性 ticket：玩家随时可以打开 `/bind` 自助绑定，踢出消息不再携带临时令牌。旧的 `/?bind=<ticket>` 链接会被重定向到 `/bind` 页面，不再生效。

> 头像来自 `https://q1.qlogo.cn/g?b=qq&nk=<QQ号>&s=100`，只对纯数字 QQ 号显示；其他 ID 显示首字母占位。头像加载失败会自动回退占位图，不影响绑定。可用 `binding.show-avatars: false` 关闭。

## 7. 命令

| 命令 | 说明 |
| --- | --- |
| `/carbonguard help` / `/cg help` / `/carbon help` | 帮助 |
| `/carbonguard status [玩家]` | 模块状态或玩家身份/绑定/封禁状态 |
| `/carbonguard ban <玩家> [时长] [原因]` | 封禁并记录当前+历史 IP；省略时长=永久；时长如 `1h`、`7d`、`12h30m` |
| `/carbonguard unban <玩家\|#编号>` | 解封并清除该封禁的 IP 关联 |
| `/carbonguard unbind <玩家\|xuid:...\|uuid:...>` | 解除邮箱绑定，并作废该账号未使用的验证码 |
| `/carbonguard reload` | 重载配置（含旧配置自动升级）、重启 Web、刷新缓存 |

权限：`carbonguard.admin`（默认 OP）。所有子命令也可使用 `/cg` 或 `/carbon` 前缀，例如 `/cg status`。数据库操作全部异步，不阻塞主线程。

## 8. Web 面板

页面：

- **绑定验证**：`/bind`（或 `/#bind`），输入 ID → 匹配账号与头像 → 只读确认 `<ID>@qq.com` → 验证码
- **绑定管理**：按玩家名/ID/XUID/UUID/邮箱查询绑定，可解除绑定
- **封禁查询**：按玩家名/XUID/UUID/封禁编号/IP 查询；待审队列通过（记录并在 `review.approval-ttl-hours` 内抑制同一 类型/身份/IP 的重复待审）、拒绝（踢出当前在线会话）、解封（解除封禁并清除 IP 关联）
- **玩家数据**：按玩家名/XUID/UUID/邮箱/QQ 或 IP 查询初次加入、绑定时间、最近登录、登录次数、累计游玩时长与最近 IP
- **统计概览**：在线、身份、绑定、封禁、24h 登录/拒绝、7 天趋势、模块状态

安全措施：

- 默认监听 `127.0.0.1:9981`；
- 管理接口要求 `X-CarbonGuard-Key`、`key` 查询参数或 `Authorization: Bearer`；
- 密钥至少 24 字符、10 种不同字符，常数时间比较；
- 管理接口每 IP 每分钟限速，公开接口单独限速；
- 请求体上限 16 KiB；
- `CSP`、`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`；
- 无 CORS、无 Cookie、无内联脚本/样式；
- `X-Forwarded-For` 默认不信任，只有 `web.trust-forwarded-for: true` 才读取。

公开端点（玩家自助绑定，无 ticket）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/public/lookup?id=` | 按 UUID/XUID/游戏名匹配账号，返回玩家名、UUID、`<ID>@qq.com` 与 QQ 头像 |
| POST | `/api/public/request-code` | `{playerId,email}`，向 `<playerId>@qq.com` 发送验证码（服务器决定邮箱） |
| POST | `/api/public/verify` | `{requestToken,code}`，完成绑定 |

管理端点（均需密钥）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/stats` | 统计概览 |
| GET | `/api/admin/bans?q=&limit=` | 封禁查询 |
| GET | `/api/admin/reviews?status=PENDING` | 待审列表 |
| POST | `/api/admin/reviews/{id}` | `{action:"APPROVE"\|"REJECT"}` |
| GET | `/api/admin/bindings?q=&limit=` | 绑定查询 |
| POST | `/api/admin/bindings/unbind` | `{identity:"xuid:..."}`，解除绑定 |
| GET | `/api/admin/players?q=&limit=` | 玩家数据查询 |
| POST | `/api/admin/bans/{id}/unban` | 解封并清除关联 |

### 8.1 反向代理

Nginx 示例：

```nginx
server {
    listen 443 ssl http2;
    server_name carbonguard.example.com;

    ssl_certificate     /etc/letsencrypt/live/carbonguard.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/carbonguard.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:9981;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

同时设置：

```yaml
web:
  bind-address: "127.0.0.1"
  public-url: "https://carbonguard.example.com"
  trust-forwarded-for: true
```

只应在可信反向代理之后开启 `trust-forwarded-for`。直连时开启会允许伪造来源 IP，破坏限速。

`key` 查询参数为兼容需求保留；它会进入代理访问日志。生产建议使用 Header，浏览器面板默认使用 Header。

## 9. 数据与异步约束

数据文件：`plugins/CarbonGuard/carbonguard.db`（SQLite，WAL）。

表：`identities`、`bindings`、`ip_history`、`bans`、`ban_ip_assoc`、`reviews`、`review_allowlist`、`player_profiles`、`login_events`、`verification_codes`、`identity_aliases`、`schema_meta`，以及旧版本遗留的 `bind_tickets` / `email_challenges`。

数据库 schema 当前为 v4：相对 v3 新增 `identities.player_id`、`bindings.email_key` 与 `verification_codes` 表。升级时会在既有数据库上就地补列，旧绑定数据保留（邮箱键按现有地址回填）。

异步设计：

- `AsyncPlayerPreLoginEvent` 内**不执行 JDBC**；
- 绑定、封禁、IP 关联、在线会话数预热到内存，登录只读缓存；
- 所有数据库操作走 HikariCP，写操作单线程串行化（SQLite 单写者）；
- 登录侧与 Web 面板使用两个独立连接池和线程池；
- 缓存每 60 秒（可配）后台刷新；绑定/封禁写入后立即更新内存缓存；
- 统计/清理在异步定时任务执行。

SQLite 适合单 Paper 节点。该版本不包含 MySQL 支持；如需多节点共享数据库，需要另行扩展 `xyz.chaihu.carbonguard.data`。

备份：停止服务器后复制 `plugins/CarbonGuard/carbonguard.db`，或使用 SQLite 在线备份工具。WAL 模式下请一并处理 `-wal` / `-shm` 文件。

## 10. 构建与测试

详见 [BUILDING.md](BUILDING.md)。

```bash
./gradlew clean build
```

Windows：

```powershell
.\gradlew.bat clean build
```

测试：

```bash
./gradlew test
```

当前测试覆盖 CIDR（含负前缀/端口拒绝）、令牌/PBKDF2、时长解析、SQLite schema/事务/唯一约束、封禁与 IP 关联缓存、UUID_FALLBACK → XUID 迁移（含目标已存在）、身份 source 不降级、IPv6 /64 聚合、复核放行抑制、解绑、会话生命周期与 IPv6 计数、自动更新版本解析、玩家初次加入/绑定时间/游玩时长记录与历史补齐、`/bind` 绑定流程（按 UUID/XUID/游戏名匹配、邮箱固定为 `<ID>@qq.com`、验证码单次消费）、旧版 `config.yml` 自动迁移与备份。

## 11. 目录

```text
src/main/java/xyz/chaihu/carbonguard/
  CarbonGuardPlugin.java
  command/       # /carbonguard
  config/        # config.yml / messages.yml 读取与校验
  data/          # SQLite、Hikari、缓存、repository
  data/identity/ # Floodgate / Geyser 隔离桥
  listener/      # 异步登录、会话生命周期
  mail/          # Jakarta Mail，SMTP SSL/STARTTLS
  service/       # 登录门禁、绑定、封禁、复核、统计
  update/        # GitHub Releases 版本解析与异步下载
  util/          # CIDR、Token、时长、IP
  web/           # JDK HttpServer、JSON API、静态资源
src/main/resources/
  config.yml
  messages.yml
  plugin.yml
  web/           # 单页静态前端（无构建链）
```

## 12. 已知边界

- 依赖 Paper 26.2 / Adventure 5.2；不要降到旧 Adventure 4.x。
- Floodgate/Geyser API 是可选的 `compileOnly` 依赖，未安装时插件正常启动。
- 真实 IP 的正确性最终取决于 Geyser/Paper 的 PROXY protocol 配置，插件无法在错误配置下“猜”出真实 IP。
- 入服拦截默认开启，但在 Web/密钥/public-url 不可用或绑定页面关闭时自动降级关闭，这是防锁死设计；SMTP 不可用只影响未绑定玩家获取验证码，不会关闭拦截。生产必须监控启动日志。
- 绑定邮箱固定为 `<ID>@qq.com`：ID 是 UUID/XUID 时页面向 `<UUID>@qq.com` 发送验证码，通常收不到。请引导玩家使用 QQ 号作为 ID（或由管理员解绑后改用 QQ 号绑定）。
- IPv6 默认按 /64 聚合计入单 IP 限制、IP 关联和 Web 限速；如需更粗/更细粒度，应修改 `IpUtil.networkKey` 并在测试中覆盖。
- 复核“通过”会抑制同一 (类型, 身份, IP) 的重复待审；不会自动放行封禁，也不会修改单 IP 上限。
- 登录判定现在 fail-closed：内部异常会拒绝本次登录，而不是放行。
- bStats 需要先在 bStats 注册插件并填写 `bstats.plugin-id`；`0` 表示关闭。修改 id 需要重启。
- 自动更新只接受 HTTPS endpoint/资产，下载在异步线程完成并写入 `plugins/update/`；不会自动重启服务器。
- 单节点 SQLite；多节点需要外部数据库扩展。
- 插件不提供 TLS 终止；公网必须使用 HTTPS 反向代理。

## 13. bStats 与自动更新

### 13.1 bStats

bStats 只收集插件/服务端版本、配置模块开关等聚合信息，不包含玩家名、IP、邮箱、密钥或聊天内容。先在 <https://bstats.org/> 注册 CarbonGuard，把分配的 `plugin-id` 写入：

```yaml
bstats:
  enable: true
  plugin-id: 12345
```

`plugin-id: 0` 时客户端不启动。运行 `/carbonguard status` 可在启动日志中看到 bStats 是否启用。

### 13.2 自动更新

默认每 6 小时在**异步线程**请求 GitHub Releases：

```yaml
update-check:
  enable: true
  endpoint: "https://api.github.com/repos/chaihuu/CarbonGuard/releases/latest"
  interval-minutes: 360
  download: true
  timeout-seconds: 20
```

- 只接受 HTTPS endpoint 和 HTTPS 下载地址；
- 只下载 release 中的 `.jar` 资产，校验 `PK` 头并限制大小；
- 写入 Paper 的 `plugins/update/CarbonGuard-<version>.jar`，重启服务器后由 Paper 更新机制应用；
- 下载失败/检查失败只记录日志和 `/carbonguard status` 状态，不影响登录；
- 不会在主线程执行网络或文件下载。

## 14. 许可证

Copyright (C) 2026 chaihuu。

CarbonGuard 是自由软件：你可以依据自由软件基金会发布的 **GNU General Public
License 第 3 版**（仅第 3 版，`GPL-3.0-only`）重新分发和/或修改它。

本程序分发时希望有用，但**不提供任何担保**，甚至不提供适销性或特定用途适用性的
默示担保。详见 [LICENSE](LICENSE) 中的完整 GPLv3 文本。

- 完整许可证：[LICENSE](LICENSE)
- 许可证指针：[COPYING](COPYING)
- 第三方组件与许可证：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Apache-2.0 / EPL-2.0 / MIT 全文：[LICENSES/](LICENSES/)
- 每个 Java 源文件都带有 `SPDX-License-Identifier: GPL-3.0-only` 头，构建时由
  `verifyLicenseHeaders` 强制检查。
- 发布的 jar 中包含 `LICENSE`、`COPYING`、`THIRD_PARTY_NOTICES.md` 与
  `LICENSES/`，满足随二进制分发许可证文本的要求。

GPLv3 允许商业使用、修改和再分发；如果你分发修改后的版本，必须按 GPLv3
提供对应的完整源代码和许可证文本，不得附加额外限制。CarbonGuard 的名称和
图标不随 GPL 授予商标许可。
