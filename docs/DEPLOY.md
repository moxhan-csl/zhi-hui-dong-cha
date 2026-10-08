# 部署手册（Docker Compose · Linux 服务器）

面向"照着一条条敲"的执行方式。每一步都标了 **[本机]**（你的 Windows 开发机，PowerShell）还是 **[服务器]**（Linux，bash）。
命令后面用 `# →` 注释这条命令干什么、期望看到什么。

**服务器上不落一行源码。** 部署包里只有编译产物（jar / dist）+ Dockerfile + compose 文件 + `.env.example` 模板；
编译全部发生在你的 Windows 开发机，服务器只做"把产物塞进镜像再运行"。密钥不进包、不进镜像：`.env` 在服务器上现填。

**只有应用进容器。** MySQL / Redis / pgvector 用服务器上已经跑着的那套实例，compose 不建它们的容器、不碰它们的数据。

> **本手册已在 2026-09-28 按此路线真实跑通**（服务器 `127.0.0.1`，`/opt/dongcha`）：本机打 jar + dist
> → scp 部署包 → 服务器 `docker compose build` → `up -d` → 两个容器 healthy → 浏览器经 80 访问。

> **关于本仓库里的 `127.0.0.1`（2026-10-08 按用户要求做的替换）**：原先写着服务器真实地址的地方，
> 现在一律是 `127.0.0.1`，共 34 处（本手册 28 处、`README.md` 1 处、`scripts/` 里 5 处注释）。两种含义要分清：
> **[本机] 的 `root@127.0.0.1`（`scp` / `ssh`）是占位**，敲之前必须换成你那台服务器的真实地址——留着不动的结果是
> 把部署包复制到你自己的 Windows 开发机上，`ssh` 则连本机、后面每一步都在错机上跑；
> **[服务器] 一侧本来就写 `127.0.0.1` 的（`mysql -h127.0.0.1`、端口映射 `127.0.0.1:8080`、健康检查里的 `curl`）是字面意义**，别改。
> 第三种情形：**[本机] 浏览器或 `curl` 里写 `http://127.0.0.1` 看站点**也要换成真实地址（或先做一条到服务器的 80 端口转发），
> 否则会连到自己机器上那个没在跑 nginx 的回环口，然后误判成"部署没起来"。
> 同理，上面那条 2026-09-28 的跑通记录、13.5 节的"实测输出"表格、13.4 节那段 `$env:` 示例，
> 都是**当时的真实结果，只是地址被替换成了 `127.0.0.1`**——照它们判断"服务器在内网/是本机"会得出错结论。
> 真实地址只存在于服务器上的 `/opt/dongcha/.env` 和你自己的记录里，仓库不再保存。

```
你的 Windows 开发机                              服务器 127.0.0.1
┌─────────────────────────────┐  scp 约 93MB   ┌──────────────────────────────────────┐
│ mvn package  → jar (100MB)  │ ───────────────▶│ docker compose build  ← 纯打包，秒级  │
│ npm run build→ dist         │                 │ docker compose up -d                 │
└─────────────────────────────┘                 │ frontend(nginx) → backend(Spring)    │
                                                │   └─ host.docker.internal →          │
                                                │      宿主机 MySQL/Redis/PG           │
                                                └──────────────────────────────────────┘
```

| 端口 | 绑定位置 | 用途 |
|---|---|---|
| `${HTTP_PORT}`（默认 80） | 0.0.0.0，对外 | 唯一入口：页面 + `/api` 反代 |
| `${API_PORT}`（默认 8080） | 只绑 127.0.0.1 | 服务器本机调试、验收脚本直连；不对公网开放 |
| 3306 / 6379 / 5432 | 由现有实例决定 | 本手册完全不涉及 |

---

## 0. 谁在哪台机器干什么

| 机器 | 要装什么 | 做什么 |
|---|---|---|
| 本机（Windows） | JDK 17 + Maven + Node + scp（你已经有，见第 1 节的坑） | 编译 jar 和 dist，scp 上去 |
| 服务器（Linux） | 只要 Docker | 解包 → 填 `.env` → `docker compose build`（纯打包）→ `up -d` |

服务器**不跑任何编译**：构建只做"把 jar / dist 塞进基础镜像"，30 秒内完成；
对外只需要能拉到 Docker Hub 的两个基础镜像（约 250 MB）。

> **基础镜像拉不动/太慢怎么办**：按第 3 节那个阶梯往下走——先换 `registry-mirrors` 数组（可整段照抄），
> 再"带前缀拉取 + `docker tag` 回原名"（绕过 mirrors，成功率最高），最后才考虑阿里云加速器
> （**个人版对公共镜像常年返回 `not found`，别在它身上耗**）。
> 本手册所有 `[服务器]` 命令都只在 Docker 路线下成立，没有第二条路线。

---

## 1. [本机] 编译并打包

在 **PowerShell** 里逐行执行（不要把 `&&` 写进命令，Windows PowerShell 5.1 不认这个分隔符）。

```powershell
cd F:\moxhan\ai-project\zhi-hui-dong-cha\backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"    # 必须先设，只对当前窗口有效
mvn -DskipTests clean package                        # → BUILD SUCCESS（一定要带 clean，原因见第 6 条坑）

ls target\zhihu-dongcha-backend.jar                  # → 存在，约 100 MB

cd ..\frontend
npm ci                                               # → 依赖装好（报 EPERM/unlink 见下方第 5 条坑；找不到 npm 见第 4 条）
npm run build                                        # → dist\ 目录生成
ls dist\index.html                                   # → 存在即成功
```

后端整个部署包只需要那一个 jar，前端只需要 `dist\`，所以下面**不打包** `dongcha-deploy.tar.gz`
（93 MB 的整包只适合第一次全新部署；日常换一端用第 10 节的单独传产物，几秒到几十秒）。

> **本机这六个坑都实测踩过**：
> 1. **`mvn` 默认挂在 JDK 8 上**（本机 `C:\Program Files\Java\jdk1.8.0_161`）。不先设 `$env:JAVA_HOME`
>    就会报一大片"需要 class, interface 或 enum""非法字符"——源码里的 record / 文本块被 JDK 8 当成了语法错误，
>    **看起来像源码损坏，其实只是版本问题**。
> 2. **8080 上常驻的开发实例会锁住 `target\*.jar`**，`repackage` 阶段直接失败。先关掉那个 java 进程
>    （`Get-Process java` 看有没有，`Stop-Process -Id <PID>`）。
> 3. **PowerShell 5.1 没有 `&&`**，续行符是反引号 `` ` `` 且必须是行尾**最后一个字符**（后面不能有空格）。
> 4. **`npm` / `node` 不在 PATH** 时报 `'"node"' 不是内部或外部命令`。本机 Node 装在 D 盘，
>    用 `$env:Path = "D:\nodejs;$env:Path"` 先进 PATH 再跑 `npm`。
> 5. **`npm ci` 报 `EPERM ... unlink ...\@rollup\rollup-win32-x64-msvc\rollup.win32-x64-msvc.node`**
>    ——不是权限问题，也不是要管理员，是**有 vite 进程还开着把这个原生 dll 锁住了**（本机验前端时
>    起的 `vite --port 80` 最容易忘停）。`npm ci` 第一步就是清空 `node_modules`，删不掉就整个失败，
>    而它已经删掉了一半，所以紧接着 `npm run build` 会报 `'vite' 不是内部或外部命令`——**这两条报错是同一个原因**。
>    先看谁占着：`Get-NetTCPConnection -LocalPort 80 -State Listen | Select-Object OwningProcess`
>    再去 `Get-CimInstance Win32_Process -Filter "ProcessId=<那个PID>"` 看 CommandLine 确认是 vite
>    （**别看见 node.exe 就杀**，编辑器和其它工具也是 node），然后停掉它、重装依赖：
>    `npm ci && npm run build`（实测：杀掉之后 `npm ci` 44 秒装完 115 包，`npm run build` 7 秒出 `dist\`）。
> 6. **`mvn package` 不带 `clean` 会把已经删掉的资源继续打进 jar**。Maven 只把 `src/main/resources`
>    拷进 `target\classes`，**不会清理上次构建留下的文件**：演示语料 `seed\*.md`（21 篇）早从源码里删掉了，
>    但 `target\classes\seed\` 还在，于是 `repackage` 又把它们塞进 `BOOT-INF\classes\seed\`——
>    线上 jar 里躺着一批已下线的演示文档（代码不读它们，所以不会自己种回去，但包体白胖、也妨碍审计）。
>    查证：`jar tf target\zhihu-dongcha-backend.jar | Select-String "seed/"`；带 `clean` 重新打包后应为空。
>
> `mvn` 本身报"'mvn' 不是内部或外部命令" → Maven 不在 PATH，用绝对路径
> `& "C:\path\to\apache-maven-3.8.6\bin\mvn.cmd" -DskipTests clean package`。

### 全新部署：打完整包并上传

```powershell
cd F:\moxhan\ai-project\zhi-hui-dong-cha
tar -czf dongcha-deploy.tar.gz docker-compose.yml .env.example backend/Dockerfile.prebuilt backend/target/zhihu-dongcha-backend.jar frontend/Dockerfile.prebuilt frontend/nginx.conf frontend/dist
ls .\dongcha-deploy.tar.gz                               # → 约 93 MB

scp .\dongcha-deploy.tar.gz root@127.0.0.1:/opt/
```

> - `tar` 的**输出路径别带盘符**：`C:\Users\...` 里的冒号会被 tar 当成"远程主机"语法，报 `Cannot connect to C:`。
>   用上面的相对路径，或写成正斜杠的 `C:/Users/...`。
> - 报 `'scp' 不是内部或外部命令` → 设置 → 应用 → 可选功能 → 添加「OpenSSH 客户端」；
>   或直接用 Git 自带的：`& "C:\Program Files\Git\usr\bin\scp.exe" <本地文件> root@127.0.0.1:/opt/`。
> - 第一次连会问 `Are you sure you want to continue connecting`，输 `yes`（记录主机指纹，属正常）。
> - 用别的账号（如 `deploy`）就把 `root@` 换掉；下文所有 `[服务器]` 命令都要在那个账号能用 docker 的前提下执行。

---

## 2. [服务器] 装 Docker（已装可跳到第 3 节）

```bash
ssh root@127.0.0.1

docker --version && docker compose version      # 先看有没有装、版本多新
```

没装（或只有老的 `docker-compose` v1）才装最新版。**机器上已经有 docker 命令的话别再跑安装脚本**：
脚本会警告 `the "docker" command appears to already exist` 并等 20 秒，这时就 Ctrl+C 退出——
继续跑会重置已有安装的软件源配置，而你的 compose 插件偏老完全不影响本手册（见下方版本说明）。

```bash
curl -fsSL https://get.docker.com | sh          # → 安装 Docker Engine + compose 插件
systemctl enable --now docker                   # → 开机自启并立即启动
docker --version && docker compose version      # → 期望 Docker 24+ / Compose v2 开头
df -h /var/lib/docker                           # → 剩余空间 ≥ 6 GB
free -m                                         # → 可用内存 ≥ 1500 MB
```

> 已装但 `docker compose version` 显示的版本**低于 v2.19**：手册里的命令都做了兼容，不用非升级不可；
> 但建议顺手升一下（Ubuntu/Debian：`apt-get update && apt-get install --only-upgrade docker-compose-plugin`，CentOS 把包名换成 `docker-compose-plugin` 用 yum）。
> 如果 `docker compose` 报 `unknown command` 而机器上只有 `docker-compose`（带横线）的 v1，必须走上面的安装脚本——v1 跑不了本手册的编排文件。
> 这台服务器实测插件偏老：`docker compose config --quiet` 会报 `unknown flag`（`--quiet` 是 v2.19+ 才有的），
> 所以本文档验证配置一律写 `docker compose config > /dev/null && echo OK`。

---

## 3. [服务器] 基础镜像：先确保两个镜像在本地

本方案只需要两个 Docker Hub 基础镜像（`nginx:1.27-alpine` 前端、`eclipse-temurin:17-jre` 后端），
外加一个用来做第 6 节预检查的 `alpine`。目标服务器上 2026-09-28 三个都已经拉到并构建成功。

**① 直接拉，通就什么都不用做：**

```bash
docker pull nginx:1.27-alpine                   # → 有进度条并最终 Digest: ... 即成功
```

**② 超时或 30 秒后报 `not found` → 配公共加速源**（下面这串地址是可以直接照抄的完整值，不需要替换；
数组按顺序尝试，第一个不通自动走下一个）。改完必须重启 daemon：

```bash
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{ "registry-mirrors": ["https://docker.1ms.run", "https://docker.1panel.live", "https://docker.m.daocloud.io"] }
EOF
systemctl daemon-reload && systemctl restart docker
docker pull nginx:1.27-alpine
```

> 如果你要用阿里云自己的加速器：地址长这样 `https://<12位随机串>.mirror.aliyuncs.com`，
> **必须先去控制台「容器镜像服务 → 镜像加速器」把那一串复制下来**再编辑文件。
> 把尖括号原样写进去是最常见的翻车点——DNS 会去解析字面量，报
> `lookup <你的加速ID>.mirror.aliyuncs.com: no such host`，看到这条就是占位符没换。
> 另外阿里云的**个人版**加速器对公共镜像常年返回 `not found`，别在它身上耗时间。
>
> 数组里哪个源快是时效性的事，实测一下再定顺序：
> `time curl -s -o /dev/null -m 8 https://docker.1ms.run/v2/`（返回 401 就算通，超时就是不通）。

**③ 换源也不行就用这招，成功率最高**：带前缀拉取，再把名字改回来。compose build 认本地同名镜像，不再联网：

```bash
docker pull docker.1ms.run/library/nginx:1.27-alpine
docker tag  docker.1ms.run/library/nginx:1.27-alpine nginx:1.27-alpine

docker pull docker.1ms.run/library/eclipse-temurin:17-jre
docker tag  docker.1ms.run/library/eclipse-temurin:17-jre eclipse-temurin:17-jre

docker pull docker.1ms.run/library/alpine:latest
docker tag  docker.1ms.run/library/alpine:latest alpine:latest    # 第 6 节预检查要用
```

> 前缀里的 `docker.1ms.run` 换成你实测最快的那个源，其余照旧。
> `docker images | head` 应该能看到三个不带前缀的原名镜像 —— 有它们 `docker compose build` 就不会再联网拉。

---

## 4. [服务器] 解开部署包

全新部署才需要这一节（日常更新见第 10 节，直接 scp 单个产物）。

```bash
mkdir -p /opt/dongcha && cd /opt/dongcha
tar -xzf /opt/dongcha-deploy.tar.gz -C /opt/dongcha
```

> **解包是覆盖式的，注意包里 `frontend/Dockerfile.prebuilt` 的版本。** 2026-09-28 之前打的包带的是
> `COPY dist /usr/share/nginx/html`（少一个斜杠，页面会变成 Welcome to nginx），解开就把服务器上已修好的那份覆盖回去。
> 拿到包先 `grep -n "COPY dist" /opt/dongcha/frontend/Dockerfile.prebuilt` 确认是 `COPY dist/ ...`；
> 不是就在本机重新 `tar` 一次再传（第 1 节那条命令）。

```bash
ls -R /opt/dongcha      # → docker-compose.yml .env.example
                        #   backend/{Dockerfile.prebuilt, target/zhihu-dongcha-backend.jar}
                        #   frontend/{Dockerfile.prebuilt, nginx.conf, dist/...}
                        #   （没有 src、没有 .java —— 服务器上没有任何源码）
docker compose config > /dev/null && echo OK     # → 打印 OK 说明 compose 文件语法没问题（缺 .env 变量时会报错，属正常，下一步就配）
```

---

## 5. [服务器] 配置 `.env`（唯一需要你填东西的地方）

```bash
cd /opt/dongcha
cp .env.example .env
chmod 600 .env          # → .env 里有数据库口令和模型 key，别让别人读到
openssl rand -base64 48 | tr -d '=+/' | cut -c1-48      # → 生成一串 JWT_SECRET，抄进 .env
vi .env
```

每一项的含义（**加粗**的必须改，其余保持默认即可）：

| 变量 | 填什么 | 填错会怎样 |
|---|---|---|
| **`LLM_API_KEY`** | 你的 DashScope key（`sk-` 开头） | 留空 → compose 直接拒绝启动（有意拦截）。若绕过它（裸跑 jar 不注入），问答退回本地模板，而**文档入库会直接失败**并停在 `FAILED`，报"当前没有可用的真实 embedding 服务"——哈希假向量和真向量同为 1024 维，写进去不报错却会静默污染检索质量，所以默认拒收（D-14） |
| **`JWT_SECRET`** | 上一步生成的 48 位串 | 短于 32 字节 → 后端启动即崩；换值 → 所有人重新登录 |
| **`MYSQL_USER` / `MYSQL_PASSWORD`** | 服务器上那套 MySQL 的账号口令 | 连不上 → backend 反复重启，日志 `Access denied` 或 `Communications link failure` |
| **`PG_USER` / `PG_PASSWORD` / `PG_DATABASE`** | 那套 PostgreSQL 的账号口令、库名（默认 `kbqa`） | 连不上不影响启动，但向量检索会降级成内存实现（`/api/health` 里 `vectorStore` 变 `memory`） |
| **`REDIS_PASSWORD`** | Redis 的 `requirepass` 值 | 不一致 → 日志 `NOAUTH`；登录冷却/缓存退化为进程内实现 |
| `MYSQL_DATABASE` | 业务库名，默认 `zhihu_dongcha` | 换个名字=换一套数据（JDBC 带 `createDatabaseIfNotExist`，账号需建库权限） |
| `MYSQL_HOST` / `REDIS_HOST` / `PG_HOST` | 保持 `host.docker.internal` | 写成 `localhost`/`127.0.0.1` → 容器连的是它自己，必然 refused |
| `HTTP_PORT` | 对外的 Web 端口，默认 80 | 服务器上 80 已被占用（`ss -lntp \| grep ':80 '`）就改 8081 |
| `API_PORT` | 后端在本机的调试端口，默认 8080 | 与已有服务撞了就改 |
| `LLM_PROVIDER` | `openai`（默认）或 `mock` | 填 `mock` 只能出本地模板回答，**问答与入库都会因为拿不到真向量而失败**（见下一行）；线上不要用它 |
| `LLM_ALLOW_HASH_VECTORS` | 默认 `false`，**只在本机调试时才设 `true`** | `false` = 没有真实 embedding 时拒绝写入哈希假向量，入库直接 `FAILED`、问答检索报错（这是 D-14 的有意行为）。设 `true` 后流程能跑通，但向量是 1024 维哈希值、与真向量同维且事后无法区分，检索分数全部不可信，且每次写入一条 WARN |
| `LLM_BASE_URL` / `LLM_CHAT_MODEL` | 默认 DashScope 兼容端点 + `qwen-plus` | 换模型商时改 |
| `CHAT_CONTEXT_TURNS` | 进模型的会话上下文轮数，默认 `3`（≈最近 6 条消息）；`0` = 关闭多轮 | 这是 D-11 唯一可调的旋钮。调大 → 每次合成的 prompt 变长、**费用与首 Token 延迟一起涨**；调小或 0 → 追问"上面那条再说一遍"退化成独立问题。上下文由服务端从会话记录里取，**请求体不带**，所以客户端无法伪造（改回 0 也不会影响缓存正确性，窗口为空即与旧 key 同形） |
| `CHAT_QUALITY_RETRIES` | 忠实度判定未通过时的**额外重写次数**，默认 `1`；实现里钳制在 `0-2`（写 5 也只按 2 生效） | D-1 的旋钮。设 `0` 回到旧行为（低质答案只标记不重写）。每 +1 = 最坏多一次合成 + 一次判定的真金白银，所以它和 `CHAT_CONTEXT_TURNS` 一样会推高费用与整体耗时（**首 Token 延迟不受影响**，重写发生在答案已经流出来之后）。三条硬边界：**只重写不改检索**（资料里本来就没有答案的题，重试多少次都不过）；**LLM 降级到 mock 时不重试**（模板同输入同输出）；**未通过校验的答案不回填缓存**，所以同一道低质题反复问会反复付费，这是刻意的。线上表现：答案会**先流出来再整段被替换成新版**，气泡上标"上一版未过质量校验，已重写" |
| `CHAT_MAX_INFLIGHT_PER_USER` / `CHAT_MAX_INFLIGHT_TOTAL` / `CHAT_DAILY_REQUEST_LIMIT` / `CHAT_COST_BREAKER_YUAN` | 问答护栏，默认 `2` / `24` / `200` / `800` | 第一批（D-31/D-32）加的四个闸门：在途并发超上限、当日次数超限、当日**估算**费用达熔断线时，问答被拒并以 SSE `error` 事件下发（HTTP 状态仍是 200，事件头在此之前已提交）。**熔断金额走的是 `price-per-1k-tokens` 的估算口径，不是账单**——单价配错则熔断线跟着偏；这三个数（64/24/2）是按"LLM 往返才是瓶颈"推算的，**未经并发实测** |
| `STORE_USER_CACHE_TTL` | users 鉴权镜像的重载周期，默认 `60`（秒） | 第八批（D-30）之后它驱动两件事：① 停用/删除账号最迟 60s 在鉴权侧生效；② `/api/health` 里 `documents` 那个读数的刷新周期（**仪表值，最多滞后一个周期**）。业务表不在这条链上——知识库/文档/会话/评测/审计现在每请求直读 MySQL，所以调它**不会**让共享变更或文档列表变快或变慢，别拿它当性能旋钮 |
| `CORS_ORIGINS` | 同源部署下不生效，保持默认 | 只有"前端另地部署、跨域调 API"才需要填前端 origin |
| `NPM_REGISTRY` | 本机打前端包用的 npm 源，服务器用不到 | 只影响你本机 `npm ci`，服务器上改它没有任何效果 |
| `JAVA_OPTS` | 默认 `-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8` | 删掉 `-Dfile.encoding=UTF-8` 中文日志可能变问号 |

检查（不打印明文）：

```bash
docker compose config > /dev/null && echo "配置可用"     # → 有变量缺失会在这里报，例如 required variable LLM_API_KEY is missing
docker compose config | grep -E "MYSQL_HOST|PG_HOST|REDIS_HOST|LLM_PROVIDER|HTTP_PORT"   # → 目视确认主机名不是 localhost
```

> **只有上表这些变量会真的进到容器里。** compose 的 `environment:` 是一份白名单，写在 `.env` 里但没被
> `docker-compose.yml` 列出的变量（例如 `LLM_EMBED_MODEL`、`LLM_EMBED_DIM`、`LLM_TIMEOUT`、`LLM_PROBE`、
> `PG_REPROBE_MS`、`CHAT_CACHE_TTL`、`EVAL_SAMPLE_LIMIT`）容器读不到——不是"填了就生效"。
> 全部保持默认（`application.yml` 里已给好，embedding 是 `text-embedding-v3` / 1024 维）就行；
> 确实要调，就在 `docker-compose.yml` 的 `backend.environment:` 下加一行 `LLM_EMBED_MODEL: ${LLM_EMBED_MODEL:text-embedding-v3}`。
> **改维度前先想清楚**：`LLM_EMBED_DIM` 一变，`chunks` 表里已有的向量维度全对不上，后端启动时会 `TRUNCATE chunks` 清空重灌。
> 另有一个 `PG_ENABLED=false`：它走的是 `app.pg.enabled` 这个 Spring 属性，而 compose 白名单里没有 `PG_ENABLED`，
> 加在 `.env` 里不会传进容器；要关向量库得直接在 `docker-compose.yml` 里给 backend 加环境变量（正常部署不需要动它）。

---

## 6. [服务器] 预检查：容器能不能摸到那三个服务

**这一步别跳过**——90% 的"起不来"都是这里的问题，而且 30 秒就能查完。

```bash
docker run --rm --add-host=host.docker.internal:host-gateway alpine \
  sh -c 'for p in 3306 5432 6379; do
           if nc -w3 host.docker.internal $p </dev/null >/dev/null 2>&1; then echo "$p 可连"; else echo "$p 连不上"; fi
         done'
```

期望三行都是 `… 可连`。（用退出码判断而不是 `nc -z`：alpine 的 busybox nc 不一定带 `-z`。）

| 现象 | 原因 | 怎么修 |
|---|---|---|
| `connection refused` | 该服务只监听 `127.0.0.1` | 改它自己的 `bind-address` / `listen_addresses` / Redis `bind`；或不想动配置就用第 13.1 节的 `network_mode: host` 兜底 |
| 端口 open 但应用日志报 `Access denied for user 'mysql'@'172.17.0.1'` | MySQL 账号只授权了本机 | `CREATE USER 'mysql'@'%' IDENTIFIED BY '…'; GRANT … ON zhihu_dongcha.* TO 'mysql'@'%';` |
| 日志报 `no pg_hba.conf entry for host "172.x"` | PG 没放行 docker 网段 | `pg_hba.conf` 加一行 `host all all 172.16.0.0/12 scram-sha-256`，然后 `systemctl reload postgresql` |
| 日志报 `NOAUTH Authentication required` | `.env` 里 `REDIS_PASSWORD` 与实际不符 | 用 `redis-cli -a '实际口令' ping` 在服务器上验一次 |

> 顺带确认 LLM 出网：`curl -s -o /dev/null -w '%{http_code}\n' https://dashscope.aliyuncs.com/compatible-mode/v1/models` → 非 000（401/404 都算网络通，只是没带 key）。

---

## 7. [服务器] 构建并启动

```bash
cd /opt/dongcha
docker compose build
```

`docker compose build` → 构建两个应用镜像。compose 已指向 `Dockerfile.prebuilt`：
不装 JDK/Node、不跑任何编译，只把 jar / dist 塞进基础镜像，30 秒内完成（主要耗时在拉两个基础镜像，第 3 节做完了这里就不联网）。

```bash
docker compose up -d
```

`up -d` → 后台创建并启动容器。期望输出两行 `Container zhihu-dongcha-backend-1 Started` /
`Container zhihu-dongcha-frontend-1 Started`（容器名前缀 `zhihu-dongcha-` 来自 compose 里的 `name:`，不用记，看 `ps` 就有）。

```bash
docker compose ps
```

等 1–2 分钟（后端要探测外部服务），期望：

```
NAME                       STATUS                    PORTS
zhihu-dongcha-backend-1    Up About a minute (healthy)   127.0.0.1:8080->8080/tcp
zhihu-dongcha-frontend-1   Up About a minute (healthy)   0.0.0.0:80->80/tcp
```

`(healthy)` 才算真起来；`starting` 继续等，`Restarting` 看第 11 节。

> **PORTS 列看着是空的，别急着怀疑没映射。** 终端列宽不够时 compose 会把这一列截掉（实测发生过）。
> 要看真实映射用不受截断影响的两种写法：
> ```bash
> docker compose port frontend 80                 # → 输出 0.0.0.0:80
> docker inspect -f '{{json .NetworkSettings.Ports}}' zhihu-dongcha-frontend-1
> ```

```bash
curl -s http://127.0.0.1/ | head -3               # → 前端首页 HTML
docker compose exec frontend ls /usr/share/nginx/html
```

第一条**不能**是 `Welcome to nginx`（那是静态产物没落到站点根，见第 11 节那条）；
第二条应该直接列出 `index.html` 和 `assets/`，中间**不该有 `dist` 这一层**。

```bash
docker compose logs backend | tail -20
```

期望最后几行里有：

```
[health] 外部依赖: llm=openai-compatible, vectors=pgvector, redis=redis | mysql=ok (users=4, docs=22) | vector-store=pgvector | redis=online
Started Application in 3x.xxx seconds
```

因为是复用已有实例，正常会看到一行 `业务数据: users=N kbs=N docs=N chunks=N`（库里已有数据时才会打这行），
以及 `[health] 外部依赖: llm=…, vectors=…, redis=… | mysql=ok (users=N, docs=N) | vector-store=pgvector | redis=online`。
括号里的数字只是示例，以你库里实际有多少为准，对不上不算问题；种子语料已下线，全新库里 `docs=0 chunks=0` 是正常的，不再是"首次启动播种 20 篇"。
要盯的是 `llm=` 不能是 `mock`、`vectors=` 不能是 `in-memory`、`vector-store=` 不能是 `memory`、`redis=` 不能是 `offline`。
（日志若在你 Windows 终端里显示乱码，是正常的：容器内输出是 UTF-8，SSH 里看没问题。）

---

## 8. [服务器 / 浏览器] 上线验证

```bash
curl -s http://127.0.0.1/api/health | python3 -m json.tool
```

```json
{"status":"UP","llm":"openai-compatible","vectorStore":"pgvector","pendingVectorOps":0,
 "redis":"redis","users":4,"documents":22}
```

`status` 为 `UP` 即三项外部依赖都正常。`DEGRADED` 表示某项退回内存实现（向量库会在 30 秒内自己重探恢复，一般不用管）。
刚启动几秒读到 `users:0/documents:0` 是正常的：数据装载发生在端口就绪之后。

浏览器再走一遍：

| # | 动作 | 期望 |
|---|---|---|
| 1 | 打开 `http://127.0.0.1` | 登录页（打不开 → 安全组没放行 80，查第 11 节"浏览器打不开"那一行；看到的是 Welcome to nginx 则查同名那一行） |
| 2 | 用库里已有账号登录 | 成功进入工作台。**没有演示账号可给你**（演示数据早于 2026-09-29 已清空，`docs/API.md` 也已在 2026-09-30 删除，别再去找它）：可用的账号是首次启动播种的那个系统管理员（口令见第 13.3 节那段，一次性随机口令只在当时的启动日志里打印过）与你后续在"设置 → 用户"里自建的那些 |
| 3 | 问一个知识库内的问题 | **逐字流式**出现（整段一次性蹦出来 → 第 11 节 SSE 行） |
| 4 | 点回答里的引用标记 | 能定位到文档分块原文 |
| 5 | 上传一篇 PDF/DOCX | 进度推到 READY，分块预览有内容 |
| 6 | 权限差分的问题（员工问机密库） | 回答"未检索到相关内容" |

想跑自动化验收（会在共享库里建临时数据、默认跑完自动清理）：

```powershell
# [本机] 在仓库根目录跑（脚本在 scripts/ 下；上传到服务器的部署包里没带它们）
# --base 里的 127.0.0.1 是占位：跑之前换成服务器的真实地址，照抄等于打自己开发机，AC-1~AC-6 会全部以连接失败收场
npm run accept -- --base=http://127.0.0.1/api
npm run accept:eval -- --base=http://127.0.0.1/api   # 追加 AC-6 全量评测，会产生模型费用
```

---

## 9. 日常操作速查（都在 `/opt/dongcha` 下执行）

| 我要做什么 | 命令 | 说明 |
|---|---|---|
| 看整体状态 | `docker compose ps` | STATUS 列看 healthy |
| 看后端日志 | `docker compose logs -f backend` | `Ctrl+C` 退出，不影响容器 |
| 只看最近 1 小时 | `docker compose logs --since=1h backend` | |
| 只重启后端 | `docker compose restart backend` | 不动数据，秒级 |
| 改 `.env` 后生效 | `docker compose up -d` | 会按新配置重建变更的容器 |
| 停服（保留一切） | `docker compose stop` | |
| 删容器（保留卷） | `docker compose down` | 数据都在宿主的 MySQL/PG/Redis 里，安全 |
| **危险** | `docker compose down -v` | 会删掉 uploads 命名卷（上传的原始文件全没），日常别用 |
| 进容器里看文件 | `docker compose exec backend sh` | `ls /app/data/uploads` |
| 看占用 | `docker system df` | 构建缓存大就 `docker builder prune -f` |

---

## 10. 更新发版

**前端和后端可以单独更新，互不干扰。** 两端各占 compose 里的一个服务、一个镜像，
`docker compose build <服务>` + `up -d <服务>` 只会重建那一个容器，另一端容器的 `Up` 时间是连续的。

**不需要事先备份镜像。** 镜像是从本机传上来的产物纯打包出来的，出问题时把上一版产物重新传一遍重建就是回滚（见 10.4，
留的是本机的 jar / dist，不是服务器上的镜像）。
`docker compose build` 会把旧镜像变成 `<none>` 悬空层，占空间但不影响运行，嫌大就 `docker image prune -f`。

前提：服务器已经跑过第 4~7 节，`/opt/dongcha` 下的 `docker-compose.yml`、`.env`、两个 `Dockerfile.prebuilt` 都还在。

### 10.1 只更新后端（改了 Java 代码）

```powershell
# [本机] 只打 jar，不用 tar 整包
cd F:\moxhan\ai-project\zhi-hui-dong-cha\backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
mvn -DskipTests clean package                            # → BUILD SUCCESS；必须带 clean（第 6 条坑）；若报 jar 被占用，先停本机 8080 上的 java 进程

scp target\zhihu-dongcha-backend.jar root@127.0.0.1:/opt/dongcha/backend/target/zhihu-dongcha-backend.jar
```

```bash
# [服务器] 只重建后端镜像和后端容器
cd /opt/dongcha
docker compose build backend
docker compose up -d backend
docker compose ps           # → backend 回到 (healthy)；frontend 那行的 Up 时间是连续的，说明前端没被动过
```

`up -d backend` 里带服务名的地方都只影响那一个服务；前端容器全程不停。
约 1 分钟内后端会重新探测外部依赖，`docker compose logs --tail=20 backend` 里应该又出现一次 `[health] 外部依赖: …`。

> **这次后端改动里有一条会改变数据表现**（2026-10-01，第七批 D-11 多轮上下文，缺陷文档 §5.7）：
> ① `.env` 可以不动——`CHAT_CONTEXT_TURNS` 在 compose 里有默认值 `3`；要关掉多轮才加一行 `CHAT_CONTEXT_TURNS=0`。
> ② **回答缓存的 key 变了**（加入了会话历史窗口）。发版后此前缓存的答案一律读不到，表现为**首日缓存命中率掉下来、首 Token 变慢**——
> 这是各 key 等自己 TTL（默认 600s）过期的正常现象，**不是缓存坏了，不要为此重启或清 Redis**。
> ③ 多轮的上下文由**服务端从会话记录里取**，请求体不再接受客户端上传 `history`（原字段已删）。
> 所以**只发后端就能让多轮生效**；前端那批改动（删拼装代码 + 类型）不改渲染行为，可以按 10.2 另发。
> 老前端配新后端也不会报错（Spring Boot 默认忽略未知字段），但那样拿不到多轮之外的差别。
> ④ 带上下文会**抬高每次合成的 token 数**，`/api/monitor` 的当日估算费用随之上升；同时成本估算的基数已改为"全部消息（含历史）"，
> 新旧两天的数字**不同口径**，别把发版当天的涨幅当成异常。要回到旧花费就设 0。
> ⑤ **历史不参与检索**：追问"它多少钱"这类指代问题的召回仍只按当前问题字面算，答不上来是能力边界不是发版失败。

> **这次后端改动里有第二条会改变数据表现**（2026-10-01，第七批 D-1 质量未通过时重生成，缺陷文档 §5.7）：
> ① `.env` 仍然可以不动——`CHAT_QUALITY_RETRIES` 在 compose 里有默认值 `1`；要回到"只标记不重写"的旧行为才加一行 `CHAT_QUALITY_RETRIES=0`。
> ② **这一条必须前后端一起发**，和上面 D-11 的"只发后端就生效"相反。原因：重写前后端会发一个 `revision` 事件声明"上一版作废"，
> 老前端不认识这个事件（`useChatStream.ts` 的 switch 有 `default: break`，不会报错也不会崩），
> 结果就是**旧版正文不清空、新版直接拼在后面，用户看到一条两段拼接的答案**。发版后如果有人报"回答重复/拼接"，先查前端容器是不是还在跑旧 dist，不要先怀疑模型。
> ③ 新表现是**答案先流出来、再整段被替换**，气泡上会显示"上一版未过质量校验，已重写"（悬停给出判定原因）。这是设计行为，不是重复回答、也不是模型抽风。
> ④ **未通过质量校验的答案不再回填缓存**（以前过不过都写）。连带两个可见变化：
> 同一道低质题反复问会**反复调用模型、反复花钱**（这是刻意的，别当故障修回去）；整体缓存命中率会比发版前低一些，
> 叠加上面 ② 的 key 变更，**首日的命中率数字不能与发版前直接比**。
> ⑤ 花费只在判定真的没过时才增加（默认最坏多一次合成 + 一次判定），线索在 `done` 事件的 `attempts` 字段里；
> **监控没有按 attempts 分桶**，所以只能从 `/api/monitor` 的当日估算总额间接看出来，别指望监控页出现"重试次数"。
> ⑥ **发版后"没看到重写发生"不等于重试没生效**：触发它需要 Judge 真的判出低分，而本轮修复在本地一次也没被运行时观测过
> （`mvn clean compile` + `vue-tsc` + `node --check` 是唯一证据）。想确认协议本身，就抓一次问答的 SSE 帧看有没有 `revision`、`done.attempts` 是否 ≥1。

> **这次后端改动里有一条会改变数据表现**（2026-10-01，第八批 D-30 内存镜像改直读 MySQL，缺陷文档 §5.8）：
> ① `.env` 不用动，也**不用出 dist**（这一批前端与脚本零改动，只发后端即可）。唯一自动发生的事是建列：
> 发版后第一次启动，`ddl-auto: update` 会给 `documents` 补一个可空列 `stage`。**要看的**：`docker compose logs --tail=60 backend` 里若出现
> `SchemaManagement`/`ALTER command denied to user` 之类，说明那台共享 MySQL 的账号没有 ALTER 权限——这时后端**起不来**（不是静默跳过），
> 按 13.3 补授权再 `docker compose up -d backend`。核对列已存在（只读，不动数据；`-u/-p` 那两段照抄 13.2 的取法）：
> `[服务器] mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" zhihu_dongcha -e "SHOW COLUMNS FROM documents LIKE 'stage';"` → 返回一行即成功。
> ② **发版前入库的文档那一列是 NULL**，前端进度条的阶段名会显示成"处理中"，重新解析后才有真值。**这不是脏数据，不要去"修"它**。
> ③ **共享/取消共享知识库从此立即生效**（以前知识库镜像只在启动装载一次，改完不重启看不见）。
> 代价是可见性判定变成每请求一次 `knowledge_bases` 读——一次问答至少两次。
> ④ **行为口径变化，容易被误报成发版把系统搞坏了**：业务表现在每请求直读 MySQL，
> 所以 MySQL 抖一下时知识库页/文档列表/问答会**直接报错**，而 `/api/health` 仍是 200（探针刻意不碰库）。
> 以前那种"MySQL 挂了但页面还能读出旧镜像数据"的现象不会再有。反查：`[服务器] docker compose logs --since=5m backend | grep -i -E "Communications|HikariPool|Unable to acquire"`。
> ⑤ `/api/health` 的 `documents` 改成了**按 `STORE_USER_CACHE_TTL`（默认 60s）刷新的仪表读数**，最多滞后一个周期、MySQL 读失败时保留旧值。
> 所以清库核对别再拿它当精确计数：删完立刻 curl 可能还显示旧数字，等 60s 或以 `/api/documents` 的 `total` 为准。
> ⑥ **解析途中删文档不再"复活"元数据行**（以前摄取线程会把已删文档 `save` 回去，留下一行 READY 却没有向量与文件的空壳）。
> 残余窗口：`existsById` 与写之间被删仍可能复活一行，真撞上了就在文档列表看到一行打不开的文档，删除它即可（这是已知边界 §5.8 ④）。
> ⑦ **启动对账三项现在各查一次库**：停在 PARSING/PENDING 的文档收敛成 FAILED（并写 `stage=failed`）、停在 running 的评测收敛成 failed、
> 孤儿向量按 `SELECT id FROM documents` 做基准清理。**读不到基准清单时这一项整体跳过**并打 WARN——那行 WARN 是保护，不是故障。
> ⑧ **本批一条运行时都没跑**（本机无 `.env` 凭据，也连不到那套共享 MySQL），证据只有 `mvn -DskipTests clean package`。
> 发版后建议实测三件事：上传→解析中途删除、追问一轮看多轮是否仍正常、`/api/health` 的 `documents` 是否随删随滞后变化。

> **这次后端改动里有一条会改变数据表现**（2026-10-01，PDF 改逐页抽取，缺陷文档 D-4）：
> ① 不需要动 `.env` 或 compose——PDFBox 已经打进 jar；
> ② **发版后新入库（或重新解析）的 PDF** 才有真实页码，引用卡片显示"第 N 页"；**发版前入库的旧 PDF 不会自动补页码**，
> 它的卡片继续显示"分块 #N"，这是如实结果不是坏了。要补只能**删除后重传**（重解析要求原始文件在容器里可读，路径修正见 13.2；
> 那里原先 `WHERE seed_resource IS NULL` 引用了一个**表里根本不存在的列**，照抄必报错，2026-10-01 已改为按路径前缀筛）；
> ③ 同一篇 PDF 重新入库后**分块数一般会变多**（块不再跨页），`chunk_count` 与分块预览以重解析后为准，别拿新旧两轮对比当回归结论；
> ④ 前端这轮只改了类型声明和一个判空写法（`undefined > 0` 与 `v-if="c.page"` 结果相同），**渲染行为不变，可以不出 dist**——
> 想连着更新前端就照常走 10.2，两者顺序无所谓。

> **这次后端改动里有第三条会改变数据表现**（2026-10-01，第九批 D-2 热点读索引 + 代码层业务判重，缺陷文档 §5.9）：
> ① `.env` 不用动、compose 不用动，**也不用出 dist**（本批前端与验收脚本零改动）。但**这次发版会动一次库结构**：
> 七个实体上新声明的 12 条索引，会在新 jar 第一次启动时由 `ddl-auto: update` 对**共享生产库**执行。
> ② **建议发版前先把这 12 条自己跑一遍**，脚本是 `scripts/add-hot-read-indexes.mysql.sql`（它不是迁移工具，仓库里没有 Flyway/Liquibase）。
> 两种方式最终结果一样（表接近空时耗时也一样），选它只是因为"发版前你自己看过这 12 条 `CREATE INDEX`"和
> "启动那一刻顺手改了生产库结构"是两种可控程度。**先跑过脚本，启动时 Hibernate 按索引名判存在就会跳过，不会重复建**。
> 部署包里不带 `scripts/`（第 8 节那句"脚本在 scripts/ 下、没带上去"对它同样成立），所以要先单独传一次：
>
> ```powershell
> # [本机] 仓库根目录执行，把索引脚本传到部署目录
> scp scripts\add-hot-read-indexes.mysql.sql root@127.0.0.1:/opt/dongcha/
> ```
>
> ```bash
> # [服务器] 必须在 /opt/dongcha 下执行：账号与口令从 .env 里取，不要手输、不要把结果贴到任何聊天/工单里
> cd /opt/dongcha
> mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
>   zhihu_dongcha < add-hot-read-indexes.mysql.sql
> ```
>
> 报错了怎么对号（脚本头部也写了，这里只挑发版时会碰到的两种）：`1045`/`1049` = `.env` 里账号或库名不对，此时**一条 DDL 都没执行**，改正再跑；
> `1061 Duplicate key name` = 该索引已存在（**重复跑整个脚本必现**，不是失败，跳过即可）。新库首次发版**不要跑它**：库里还没建表，会报 `1146 Table doesn't exist`，让启动自己建。
> 跑完或启动完，核对索引真的在生产库上（只读；**这是唯一可信的核对方式——不要凭实体注解里有没有写来判断库里有什么**）：
>
> ```bash
> # [服务器] 期望：12 个 idx_ 名字都在，另外 kb_members / conversation_kb_ids 各有一个非 PRIMARY 的索引（InnoDB 随外键约束自动建，名字不是 idx_ 开头）
> mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" zhihu_dongcha \
>   -e "SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) cols FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name LIKE 'idx_%' GROUP BY table_name, index_name ORDER BY table_name, index_name;"
> ```
>
> ③ **发版后会有四类"用户以为是 bug"的拒绝**，都是刻意加的代码层判重（口径：去首尾空格、忽略大小写后相等即视为同名）：
> 同一知识库里重传同名文档 → 400 `DOC_NAME_DUPLICATE`，要替换只能先删旧文档（删除会一并清掉向量与磁盘文件，不可恢复）；
> 建库或改库名撞上已有库名 → 400 `KB_NAME_DUPLICATE`；手工新建/改题干撞上已有题干 → 400；
> 建号或改工号时账号/工号与别人交叉撞车 → 400，文案会点名"这个登录标识已经是「某某」的"。
> **上传失败时界面上显示的是一整段 JSON**（`上传失败：{"code":"DOC_NAME_DUPLICATE","message":"…"}`）——
> element-plus 的上传组件把整个响应体当错误文案，体积超限本来就是这副样子，不是这次改坏的。
> ④ **一条 UNIQUE 约束都没加，别"顺手补一个"**：这套库与开发共用同一实例，存量行里可能已经有重名/撞号，
> 约束一上去写入直接失败、启动期 schema 迁移连带受影响。判重的代价如实记着：**读后写、不原子**，
> 两个人同时提交同名仍可能双双通过（事后删掉多余一条即可纠正）；而且**它只拦"以后"，不追"以前"**。
> ⑤ **存量撞车不会被自动发现**，发版后跑这四段只读 SQL 看一眼（返回 0 行才是干净；有行就先处理，别加约束）：
>
> ```bash
> # [服务器] 以下四条都是 SELECT，不写任何东西
> # a) 同一个工号被多人占用 → "按工号登录"那条查询会命中多行，登录直接 500
> mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" zhihu_dongcha \
>   -e "SELECT LOWER(TRIM(emp_no)) emp, COUNT(*) c, GROUP_CONCAT(CONCAT(name,'/',account)) who FROM users WHERE emp_no IS NOT NULL AND emp_no<>'' GROUP BY 1 HAVING c>1;"
> # b) 某人的登录账号正好是别人的工号 → 工号那条查询先命中谁，另一个人就无声地登不进来
> mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" zhihu_dongcha \
>   -e "SELECT a.account AS holder_of_account, b.name AS blocked_user, b.account AS their_account FROM users a JOIN users b ON LOWER(TRIM(b.emp_no))=LOWER(TRIM(a.account)) AND a.id<>b.id WHERE b.emp_no IS NOT NULL AND b.emp_no<>'';"
> # c) 同名知识库 / d) 同一库内同名文档（引用卡片只显示名字，分不出来源）
> mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" zhihu_dongcha \
>   -e "SELECT LOWER(TRIM(name)) k, COUNT(*) c, GROUP_CONCAT(id) ids FROM knowledge_bases GROUP BY 1 HAVING c>1;
>       SELECT kb_id, LOWER(TRIM(name)) k, COUNT(*) c, GROUP_CONCAT(id) ids FROM documents GROUP BY 1,2 HAVING c>1;"
> ```
>
> 纠正方式是改数据而不是改代码：重名的库/文档留一个、其余改名或删除；撞车的工号改成不冲突的值（改的时候系统会走新的判重，正好把这条脏数据洗掉）。
> ⑥ **索引不改变任何返回内容**，只改执行计划。写路径多维护 12 个二级索引，当前表行数接近 0，快慢都无从测起——
> 别把"发版后没人抱怨变慢"当成收益的证据，也别把"没人抱怨变快"当成没生效的证据（缺陷文档 §0 约定 2：收益大小仍是推断）。
> ⑦ **本批运行时一条没跑**：本机没有 `.env` 凭据、也连不到那套共享 MySQL，证据只有 `mvn -o -DskipTests clean compile`（87 文件全量重编）
> 与一次**离线** DDL 生成核对（做法见下，2026-10-02 又按下面那一段重跑过，13/12/1 与逐条 diff 仍然成立）。发版后值得实测的就三件事：传两个同名文件看第二次是不是 400、建两个同名库、给一个已有工号改成别人的工号。
>
> ```bash
> # [本机·可选] 发版前看一眼 Hibernate 会生成哪些 DDL：不连任何数据库，只把语句打到 target/ddl-verify.sql
> # 这一版是 2026-10-02 在 Git Bash 里整段真跑通的写法，逐行照抄即可。三个坑写在注释里，都是这次踩出来的
> cd /f/moxhan/ai-project/zhi-hui-dong-cha/backend
> export JAVA_HOME='C:\Program Files\Java\jdk-17'      # 必须 Windows 反斜杠写法；POSIX 风格 (/c/...) 的 mvn 不认，会拿到低版本 JDK 的一堆假语法错
> # 离线仓库里没有 maven-dependency-plugin（dependency:build-classpath 拉不到它自己的依赖），编译类路径只能从 -X 的调试输出里取
> mvn -o -X clean compile 2>&1 | grep -o '(f) compilePath = \[.*\]' | head -1 > /tmp/cp.txt
> wc -c /tmp/cp.txt                                     # → 一万多字节才说明抓到了；是 0 就去翻 mvn -X 的输出看那行长什么样
> CP=$(sed -e 's/^(f) compilePath = \[//' -e 's/\] *$//' /tmp/cp.txt | tr -d '\r' | sed 's/, /;/g')
> # 坑①：javac 必须带 -encoding UTF-8。这台机器平台编码是 GBK，而 DdlGen.java 里有中文注释，
> #      不带就报"错误: 编码 GBK 的不可映射字符"、class 一个都不产出，接着 java 报 ClassNotFoundException: DdlGen（看着像没编译成功，其实是编码）
> "/c/Program Files/Java/jdk-17/bin/javac" -encoding UTF-8 -cp "$CP;target/classes" -d target/ddlcheck tools/DdlGen.java
> # 坑②：compilePath 只有编译期依赖，Hibernate 运行期那四个 jar 不在里面，不补就在 buildMetadata() 处 NoClassDefFoundError
> #      （缺谁就依次卡在 org/jboss/logging/BasicLogger → hibernate/annotations/common/... → net/bytebuddy/...）
> # 坑③：补进去的路径要写成 D:/mavenLib/... 这种 Windows 形式；写成 /d/mavenLib/... 时 Windows 的 java.exe 解析不到，照样报 ClassNotFound
> # 下面两行就是把这四个 jar 挑出来：find 用 POSIX 路径找，sed 把开头的 /d/ 换成 D:/（离线仓库不在 D 盘就改这两处的盘符）
> N() { find /d/mavenLib -name "$1" 2>/dev/null | grep -v sources | tail -1 | sed 's|^/\([a-z]\)/|\U\1:/|'; }
> RUNTIME="$(N 'jboss-logging-3*.jar');$(N 'hibernate-commons-annotations-7*.jar');$(N 'classmate-1.7*.jar');$(N 'byte-buddy-1.14*.jar')"
> echo "$RUNTIME" | tr ';' '\n'    # 期望四行都以 D:/mavenLib/ 开头且文件真存在；若打印成 /d/... 说明第③个坑没绕过去
> "/c/Program Files/Java/jdk-17/bin/java" -cp "$CP;target/classes;target/ddlcheck;$RUNTIME" DdlGen target/ddl-verify.sql
> # 期望最后一行打印：DDL generated without touching any database: target/ddl-verify.sql
> grep -c '^create table ' target/ddl-verify.sql        # → 13（11 张实体表 + kb_members/conversation_kb_ids 两张集合表）
> grep -c '^create index ' target/ddl-verify.sql        # → 12（就是那 12 条 idx_）
> grep -i unique target/ddl-verify.sql                  # → 只有一条：alter table users add constraint UK… unique (account)，历史遗留
> # 与运维脚本逐条比（无输出＝同名同表同列序完全一致，先跑脚本不会重复建）：
> diff <(grep '^create index ' target/ddl-verify.sql | tr 'A-Z' 'a-z' | tr -d ' ' | sort) \
>      <(grep '^CREATE INDEX' ../scripts/add-hot-read-indexes.mysql.sql | tr 'A-Z' 'a-z' | tr -d ' ' | sort) && echo IDENTICAL
> ```
>
> ```powershell
> # [本机·可选] 同上的 PowerShell 等价写法。2026-10-02 真跑通的是上面那版 Git Bash，**这一版没有重跑过**：
> # 抓不到 compilePath 那行就直接去看 mvn -X 的输出按格式取路径；四个运行期 jar 少了任何一个都跑不起来（见上面坑②）
> cd F:\moxhan\ai-project\zhi-hui-dong-cha\backend
> $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
> mvn -o -X clean compile
> $cp = ((mvn -o -X compile 2>&1 | Select-String -Pattern '\(f\) compilePath = \[(.*)\]' | Select-Object -First 1).Matches[0].Groups[1].Value) -replace ', ', ';'
> $rt = @('org\jboss\logging\jboss-logging','org\hibernate\common\hibernate-commons-annotations','com\fasterxml\classmate','net\bytebuddy\byte-buddy') |
>   ForEach-Object { (Get-ChildItem "D:\mavenLib\$_" -Recurse -Filter '*.jar' | Where-Object Name -notmatch 'sources' | Select-Object -Last 1).FullName }
> & "$env:JAVA_HOME\bin\javac" -encoding UTF-8 -cp "$cp;target\classes" -d target\ddlcheck tools\DdlGen.java
> & "$env:JAVA_HOME\bin\java"  -cp "$cp;target\classes;target\ddlcheck;$($rt -join ';')" DdlGen target\ddl-verify.sql
> (Select-String -Path target\ddl-verify.sql -Pattern '^create table ').Count    # → 13
> (Select-String -Path target\ddl-verify.sql -Pattern '^create index ').Count    # → 12
> ```
>
> 另外三个坑：① `mvn clean` 会连 `target\ddlcheck` 一起删掉，重跑要先 `javac`；② 它**只证明语句长什么样**，不证明生产库上建成了什么，
> 执行后仍要按 ② 的 `information_schema` 查询核对；③ `DdlGen` 在 `backend/tools/` 下、**不在应用的主源码里**（不会被打进 jar，也不受 `ddl-auto` 影响）。

### 10.2 只更新前端（改了 Vue 代码，或 `nginx.conf`）

```powershell
# [本机] 只打 dist
cd F:\moxhan\ai-project\zhi-hui-dong-cha\frontend
npm run build                                            # → dist\ 重新生成（Vite 产物文件名带 hash）
ls dist\index.html                                       # → 存在即成功

scp -r dist root@127.0.0.1:/opt/dongcha/frontend/
scp nginx.conf Dockerfile.prebuilt root@127.0.0.1:/opt/dongcha/frontend/
```

```bash
# [服务器] 只重建前端镜像和前端容器
cd /opt/dongcha
docker compose build frontend
docker compose up -d frontend
curl -s http://127.0.0.1/ | head -3     # → 首页 HTML，不能是 Welcome to nginx
docker compose ps                        # → frontend (healthy)，backend 的 Up 时间连续
```

> - 第二个 `scp` 是为了防"只传 dist 但 nginx.conf 是旧的"：改过 SSE 缓冲、上传大小限制都在 `nginx.conf` 里。
>   这两个文件只有几 KB，每次一起传最省事。
> - `scp -r dist` 是**覆盖式**的：旧 hash 文件会残留在服务器上，但 `index.html` 只引用新 hash，
>   浏览器不会加载到旧代码，无害。想清干净就先 `ssh root@… "rm -rf /opt/dongcha/frontend/dist"` 再传。
> - 用户反馈"页面还是旧的"时：镜像已更新但浏览器缓存了 `index.html`，先 Ctrl+F5。

### 10.3 两端都更新

按 10.1 + 10.2 各传各的产物，然后一次性收口：

```bash
cd /opt/dongcha
docker compose build           # 两端镜像都重建
docker compose up -d           # 只重建有变更的容器（此时两端都有变更，两个都重建）
docker compose ps              # → 两个都是 (healthy)
```

或者干脆打整包走第 1 节末尾 + 第 4 节（`tar` → `scp` → 解包 → `build` → `up -d`）；
整包慢在传 93 MB，换来的是顺手把 `docker-compose.yml`、`.env.example` 也同步了——
**只有改了 compose 文件本身时才值得走整包**。

### 10.4 回滚 = 把上一版产物重新传一遍

不依赖任何镜像备份标签，也不依赖 git——**上一版产物就是你自己在打新版之前拷出来的那两个目录**。
所以流程里只多一步：每次发新版前，先把当前正在用的产物拷到一个带日期的文件夹存着。

```powershell
# [本机] 每次编译新版之前先做这一步（把"现在线上跑的这版"留一份底）
cd F:\moxhan\ai-project\zhi-hui-dong-cha
$keep = "F:\moxhan\dongcha-artifacts\$(Get-Date -Format yyyyMMdd)"
New-Item -ItemType Directory -Force -Path $keep | Out-Null
Copy-Item backend\target\zhihu-dongcha-backend.jar "$keep\" -Force
Copy-Item frontend\dist "$keep\dist" -Recurse -Force
ls $keep                                                   # → 一个 jar + 一个 dist 目录
```

新版出问题时，把那份底原样覆盖回服务器的产物路径再重建，就是回滚：

```powershell
# [本机] 路径换成你上面留底的那个日期目录
scp F:\moxhan\dongcha-artifacts\20260928\zhihu-dongcha-backend.jar root@127.0.0.1:/opt/dongcha/backend/target/zhihu-dongcha-backend.jar
```

```bash
# [服务器] 只回滚后端；前端就按第 10.2 节传旧的 dist 目录，命令把 backend 换成 frontend
cd /opt/dongcha
docker compose build backend
docker compose up -d --force-recreate backend
docker compose ps                # → backend 回到 (healthy)
```

`--force-recreate` 在这里是必须的：`docker compose build` 之后镜像标签没变（还是 `:latest`），
compose 有时会判定"无变更"而跳过重建容器，加了它才会强制用新镜像换掉正在跑的容器。

镜像回滚不会回滚表结构：JPA 是 `ddl-auto: update`，只加列不改不删列，所以旧镜像通常照样能跑。

---

## 11. 出问题了按这个顺序查

先跑这三条，90% 的问题在第 2、3 条就露出来了：

```bash
docker compose ps                                  # ① 谁不正常
docker compose logs --tail=60 backend              # ② 第一处 ERROR 是什么
curl -s http://127.0.0.1:8080/api/health; echo     # ③ 依赖到底连上没（只绑 127.0.0.1，从服务器本机访问）
```

| 症状 | 原因 | 处置 |
|---|---|---|
| `required variable LLM_API_KEY is missing` | `.env` 没填 / 没在 `/opt/dongcha` 下执行 | 补 `.env`（第 5 节）；这是有意的拦截，别改成默认值 |
| backend `Restarting` + 日志 `Communications link failure` | 连不上宿主 MySQL | 回到第 6 节预检查；确认 `.env` 里 `MYSQL_HOST=host.docker.internal` |
| backend 起来又退出，日志 `Key length ... must be >= 256 bits` | `JWT_SECRET` 太短 | 用第 5 节的 openssl 命令重新生成 |
| `vectorStore":"memory"`、`pendingVectorOps>0` | pgvector 连接抖动，向量检索临时降级 | **不用重启**：每 30 秒后台重探，探通自动切回并回放降级期排队的写；持续不恢复才查 PG 本身 |
| 回答像本地模板、没有模型生成感，`"llm":"mock"` | key 无效 / 出网被拦 / `LLM_PROVIDER=mock` | 核对 key；`curl` 验 dashscope 可达 |
| 前端整段文字一次性出现，不是逐字流 | nginx 缓冲了 SSE | `docker compose exec frontend nginx -T \| grep -A3 proxy_buffering` → 必须是 `off` |
| 上传大文件返回 413 | 超过 nginx 的 `client_max_body_size 50m` | 改 `frontend/nginx.conf` 后按第 10.2 节只更新前端 |
| 页面 404 / 刷新子路由白屏 | 静态包里 `try_files` 丢了 | 确认用了本仓库的 `frontend/nginx.conf`（有 SPA 回落） |
| 某文档显示"解析失败：服务重启时解析尚未完成" | 重启掐断了进程内解析，启动时自动收敛成 FAILED（否则会永远转圈且无法重试） | 正常收敛，不是丢数据：文档页点"重试"；若接着报"上传文件不存在"见第 13.2 |
| `/api/health` 是 200、容器 `(healthy)`，但知识库页/文档列表/问答**全都报错** | 第八批（D-30）起业务表每请求直读 MySQL，**没有内存镜像可以再兜一层**；探活刻意不碰库，所以它照常 200。这不是发版把系统弄坏了，是口径变了 | `docker compose logs --tail=60 backend \| grep -i -E "Communications|HikariPool|Unable to acquire"`；回到第 6 节预检查确认 MySQL 可达。注意共享实例被别的服务打满时也会这样 |
| 删完文档立刻 `curl /api/health`，`documents` 还是旧数字 | 该字段第八批起是按 `STORE_USER_CACHE_TTL`（默认 60s）刷新的**仪表值**，不是实时 COUNT | 等一个周期再看，或直接以 `/api/documents` 的 `total` 为准（那个每请求直读）。别为它清 Redis 或重启容器 |
| 上传文件报"上传失败：{"code":"DOC_NAME_DUPLICATE",…}"（界面显示一整段 JSON） | 第九批 D-2 起，**同一知识库里不允许同名文档**（去空格、忽略大小写后相等即同名）。JSON 形态是 element-plus 把整个响应体当错误文案，体积超限本来就长这样，不是前端坏了 | 不是故障：要么先删旧文档（删除会一并清向量与磁盘文件，不可恢复）要么改名再传。想"替换"就走删+传两步，别去关判重 |
| 建库/改库名被拒 `KB_NAME_DUPLICATE`，或改某个用户的工号被拒"账号或工号与其它用户重复" | 同上，代码层判重。**后者可能是存量脏数据**：这条检查只拦"以后"，不追"以前"，而工号与账号都能登录，撞车会让其中一个人登不进来 | 按提示换一个名字/工号。如果确认是存量撞车（没人最近改过），跑 10.1 ⑤ 的那四段只读 SQL 找到冲突行，**改数据**（改工号或删掉多余的库/文档），不要给共享生产库补 UNIQUE 约束 |
| 某人突然登不进来，后端日志有 `query did not return a unique result` 或 `NonUniqueResultException` | 两个用户共用了同一个工号（或某人的账号正好是别人的工号）。`users.emp_no` **没有唯一约束**，防线只有应用层判重，存量脏数据仍然能触发 | 立刻按 10.1 ⑤ 的 a/b 两条 SELECT 定位，把工号改成不冲突的值。这是数据问题，重启与回滚 jar 都不会修好 |
| 新 jar 启动日志里出现 `create index` 相关的 ERROR/WARN（schema 相关），但容器仍然起来了 | `ddl-auto: update` 建索引失败（权限不足、或库里已有**同名异形**的索引）时不一定会阻断启动 | `SHOW INDEX FROM documents;` + 10.1 ② 那条 `information_schema` 查询核对缺了哪条；只补缺的那条即可，**不要重复跑整脚本之外的东西**，`1061 Duplicate key name` 是无害的 |
| 调一个不存在的接口路径拿到 **500**（`{"code":"INTERNAL_ERROR","message":"404 NOT_FOUND \"No static resource api/xxx.\""}`），看着像后端坏了 | 当前线上产物就是这样：WebFlux 下未映射路径由 `ResourceWebHandler` 抛 `ResponseStatusException(404)`，被 `ApiExceptionHandler` 的 `Exception` 兜底吃成 500。**代码已改（D-37，改成 `404 {"code":"NOT_FOUND","message":"接口不存在"}`），随下一批发版** | 先怀疑路径写错，别去重启或回滚。核对真实路由看后端代码里的 `@*Mapping`；发版后再拿同一个路径测，应该变成 404 |
| **上传文档全部失败**，界面/脚本拿到 `500 {"code":"INTERNAL_ERROR","message":"400 BAD_REQUEST \"Required query parameter 'kbId' is not present.\""}` | **这是当前线上产物已知的坏状态（D-39），不是你的部署配错了、也不是 nginx 或磁盘问题**。`DocumentController.upload` 的 `kbId` 被绑成了 `@RequestParam`（只从 URL query 取），而前端 `el-upload` 与验收脚本把它作为 **multipart 表单字段**发出，WebFlux 不会回退去读表单字段 → 400 被兜底包成 500。**代码已改回 `@RequestPart`，随下一批发版**；发版前若必须先传上去，临时办法是把库 id 放进 URL：`POST /api/documents/upload?kbId=<库id>` | 别回滚、别重启（都不改变结果）。确认是不是这一条：`docker compose exec backend sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X POST -F "file=@/tmp/t.txt" -F "kbId=<库id>" http://localhost:8080/api/documents/upload'` 拿 500，而把 `-F kbId=` 换成 URL 上的 `?kbId=` 就 201 → 就是它。**发版后同一个命令应回到 201**，届时这条从本表删掉 |
| `上传文件不存在: F:\moxhan\...` | 历史元数据记的是开发机绝对路径 | 见第 13.2 |
| 浏览器打不开 `http://IP/` | 安全组没放行 80 / `HTTP_PORT` 不是 80 | 阿里云控制台放行入站端口；`ss -lntp \| grep ':80 '` 确认容器在听 |
| `up -d` 报 `port is already allocated`（80 或 8080） | 宿主上已有别的东西占着同名端口。**注意占 80 的经常就是本项目自己**——上一次 `up -d` 留下的 frontend 容器，`docker compose ps -a` 能看到 | 先 `ss -lntp \| grep ':80 '` 看是哪个进程；是自己的旧容器就 `docker compose down && docker compose up -d`，是别人的服务就在 `.env` 里 `HTTP_PORT=8081` 换端口（并放行安全组），别下线别人的站。8080 被占则改 `API_PORT` |
| `docker compose exec backend curl ...` 报 `couldn't fully execute` | 容器还在 starting | 等 `ps` 显示 healthy 再试 |
| 后端 Exit 137 | 堆超出容器可用内存被 OOMKill | `.env` 里 `JAVA_OPTS` 把 `MaxRAMPercentage` 降到 50 |
| 打开 `http://IP/` 看到的是 **Welcome to nginx** 而不是本项目页面（`/nginx-health` 却返回 ok） | 静态产物没落到站点根：`Dockerfile.prebuilt` 里写成了 `COPY dist /usr/share/nginx/html`（不带尾斜杠 + 目标目录基础镜像已有 → 变成 `html/dist/`，官方默认页 `index.html` 留在原地被优先返回） | `docker compose exec frontend ls /usr/share/nginx/html` → 应该直接看到 `index.html` 和 `assets/`，**不该有 `dist` 子目录**；有就说明用了旧 Dockerfile，按第 4 节的 grep 确认后重新 scp 该文件，再 `docker compose build frontend && docker compose up -d frontend` |
| `docker compose ps` 里 frontend 的 PORTS 一列是空的 | 多半只是表格被终端列宽截断；也可能是 `.env` 里 `HTTP_PORT` 不是 80 | `docker inspect -f '{{json .NetworkSettings.Ports}}' zhihu-dongcha-frontend-1`（不受截断影响）+ `grep -n HTTP_PORT .env` |
| 构建卡在拉基础镜像（`eclipse-temurin:17-jre` / `nginx:1.27-alpine`），30 秒后报 `not found` | 加速器没配好或不稳（拉不到元数据，兜底也失败就报成 not found；阿里云个人加速器对公共镜像常年抽风） | 按第 3 节那个阶梯往下走：改 `registry-mirrors` 数组（`systemctl restart docker` 后重试）→ 带前缀拉取再 `docker tag` 回原名。公共源候选（时效性强，用 `time curl -s -o /dev/null -m 8 https://源/v2/` 测延迟）：`docker.1ms.run`、`docker.1panel.live`、`docker.xuanyuan.me`、`dockerproxy.net`、`hub.rat.dev`、`docker.m.daocloud.io` |
| `up -d backend` 跑完但 `ps` 里 frontend 的 `Up` 时间也重置了 | 命令里漏了服务名，实际执行的是全量 `up -d` | 带服务名重跑（`up -d backend`）不算坏事，只是前端也一起重启了一次，页面会短暂 502/白屏 |

---

## 12. 备份与恢复

数据库是那套现有实例，备份按你原来的做法走就行；这里补上 **uploads 卷**（原始文件只在这里有一份）：

> 下面用到宿主上的 `mysqldump` / `pg_dump` 客户端。没装的话换成容器执行（版本要对上服务端大版本）：
> `docker run --rm --network host -e PGPASSWORD=… postgres:16 pg_dump …`，MySQL 侧同理用 `mysql:8` 镜像跑 `mysqldump`。

```bash
# [服务器] 备份（建议 crontab 每日）
cd /opt/dongcha && mkdir -p backup
mysqldump -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
  --single-transaction zhihu_dongcha | gzip > backup/mysql-$(date +%F).sql.gz
PGPASSWORD="$(grep ^PG_PASSWORD .env | cut -d= -f2)" pg_dump -h127.0.0.1 -U"$(grep ^PG_USER .env | cut -d= -f2)" \
  -Fc kbqa > backup/kbqa-$(date +%F).dump

UPVOL=$(docker volume ls -q | grep uploads); echo "卷名: $UPVOL"
docker run --rm -v "$UPVOL:/v:ro" alpine tar -cf - -C /v . | gzip > backup/uploads-$(date +%F).tar.gz
ls -lh backup/
```

`documents` 表和 uploads 卷必须**成对**恢复：只恢复库不恢复文件，历史文档能检索但不能重新解析。

```bash
# [服务器] 恢复 uploads（$UPVOL 同上先取一次）
docker compose stop backend
gunzip -c backup/uploads-日期.tar.gz | docker run --rm -i -v "$UPVOL:/v" alpine tar -xf - -C /v
docker compose up -d backend
```

恢复库：`gunzip -c backup/mysql-日期.sql.gz | mysql -h127.0.0.1 -u… -p… zhihu_dongcha`（先停后端），
`pg_restore` 用 `--clean` 覆盖回 `kbqa`。

---

## 13. 附录

### 13.1 那三个服务只监听 127.0.0.1，不想改它们的配置

把后端容器改成直接用宿主网络（此时 `host.docker.internal` 就不需要了）：

```bash
cd /opt/dongcha && cat > docker-compose.override.yml <<'EOF'
services:
  backend:
    network_mode: host
    extra_hosts: []
    ports: []
EOF
```

同时 `.env` 里把 `MYSQL_HOST` / `REDIS_HOST` / `PG_HOST` 全改成 `127.0.0.1`，
并把 `frontend/nginx.conf` 里的 `http://backend:8080` 改成 `http://127.0.0.1:8080`，
然后 `docker compose up -d --build frontend`。代价：`API_PORT` 失效，后端固定占宿主 8080。

### 13.2 让历史上传文档在容器里也能重新解析

库里 `documents.file_path` 记的是开发机路径（`F:\moxhan\...\backend\data\uploads\xxx.pdf`）。
**不处理也不影响检索问答**（已入库的向量不读原文件），只影响 retry / 重新解析 / 取原件。要补齐：

```powershell
# [本机] 把 58 个历史原件传上去（Windows 自带 scp 支持 -r）
scp -r F:\moxhan\ai-project\zhi-hui-dong-cha\backend\data\uploads\* root@127.0.0.1:/tmp/uploads/
```

> `scp -r <目录>` 会在远端建出 `<目标>/uploads` 这一层，所以这里用 `\*` 展开成文件列表，
> 传完先 `ls /tmp/uploads | head` 确认文件直接在 `/tmp/uploads` 下、没有多一层目录。

```bash
# [服务器]
cd /opt/dongcha
docker compose cp /tmp/uploads/. backend:/app/data/uploads/

cat > fix-path.sql <<'SQL'
UPDATE documents
   SET file_path = REPLACE(file_path,
         'F:\\moxhan\\ai-project\\zhi-hui-dong-cha\\backend\\data\\uploads',
         '/app/data/uploads')
 WHERE file_path LIKE 'F:\\moxhan\\ai-project\\zhi-hui-dong-cha\\backend\\data\\uploads\\%';
SQL
mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
  zhihu_dongcha < fix-path.sql
docker compose restart backend     # 只在改完后端实体/列之后需要；第八批 D-30 起文档元数据每请求直读 MySQL，改路径本身不必再重启
```

SQL 里 Windows 路径的反斜杠必须写成 `\\`，MySQL 会把单个 `\` 当转义符。
`WHERE` 现在按**路径前缀**筛（只有还指向开发机的行才需要改）。这一条 2026-10-01 修正过：原先写的是
`WHERE seed_resource IS NULL`，而 `documents` 表**根本没有 `seed_resource` 列**（播种语料的代码早下线了），
照抄会直接报 `Unknown column 'seed_resource' in 'where clause'`。改前先跑一条只读的确认（把上面那条 `mysql` 的 `-u/-p` 与库名原样复用，只把 `< fix-path.sql` 换成 `-e`）：

```bash
# [服务器] 数一下路径还指向开发机的行数（应当等于历史上传的那批）
mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
  zhihu_dongcha -e "SELECT COUNT(*) FROM documents WHERE file_path LIKE 'F:\\\\moxhan%';"
```

> 这里要写四个反斜杠：bash 的双引号吃掉一层、MySQL 的字符串字面量再吃掉一层，落到比较里才是 `F:\moxhan%`。
> 只写两个会匹配 `F:\\moxhan`（两个反斜杠的字面量），结果是 `COUNT(*)=0`——那不是"没有脏数据"，是条件写错了。

**不要指望点"重试"来重建这批文档的向量**：`READY` 且向量齐全的文档走不到 retry（后端会拒），
而它们本来也不需要重解析——`file_path` 只影响取原件与重新解析。确实要让它们用上新的逐页抽取
（缺陷 D-4 的页码）就只能**删除后重传**：这会重跑 embedding、产生真实费用，且分块数会变多
（块不再跨页，新旧 `chunkCount` 不可比）。

### 13.3 已知限制

- **和开发环境共用同一套数据服务**：本手册默认复用服务器上现有实例，而开发机裸跑时按 13.4 注入的也是这套连接信息。
  改表结构、删数据会互相影响。要隔离就在同一实例里单独建库建账号，改 `.env` 即可：

  ```sql
  -- [服务器] mysql 里执行，给部署用一套独立数据
  CREATE DATABASE zhihu_dongcha_prod CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
  CREATE USER 'dongcha'@'%' IDENTIFIED BY '换成你自己的强口令';
  GRANT ALL PRIVILEGES ON zhihu_dongcha_prod.* TO 'dongcha'@'%';
  ```
  对应 `.env`：`MYSQL_DATABASE=zhihu_dongcha_prod`、`MYSQL_USER=dongcha`、`MYSQL_PASSWORD=…`。
  建表由后端启动时自己做（`ddl-auto: update`），所以 MySQL 侧给齐该库的 `ALL PRIVILEGES` 就够了。

  PG（向量库）侧单独建库：

  ```sql
  -- [服务器] psql 里以超级用户执行
  CREATE ROLE dongcha LOGIN PASSWORD '换成你自己的强口令';
  CREATE DATABASE kbqa_prod OWNER dongcha;
  \c kbqa_prod
  -- 扩展必须由超级用户建：pgvector 不是 trusted extension，普通账号建不了
  CREATE EXTENSION IF NOT EXISTS vector;
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
  -- PG 15+ 起普通账号对 public schema 没有 CREATE 权限，而后端启动要建 chunks 表和索引
  GRANT CREATE, USAGE ON SCHEMA public TO dongcha;
  ```
  对应 `.env`：`PG_DATABASE=kbqa_prod`、`PG_USER=dongcha`。
  上面漏掉任何一句，向量表就建不起来，后端会静默降级成内存检索：日志里是
  `vector-store=in-memory` 的警告 + 一句 `must be owner of extension "vector"` 或
  `permission denied for schema public`。降级不阻碍启动，很容易看漏，务必回查。
  注意：**新库是空的**，首次启动只会创建一个系统管理员账号 `admin@corp.com`（口令取 `.env` 的 `ADMIN_INIT_PASSWORD`；
  没配则由后端随机生成并只在启动日志打印一次），不会播种任何语料，也不会自动调用 embedding。
  语料要在文档管理页手动上传，上传后才会真的走 embedding 接口（`text-embedding-v3`，预期行为，按量花钱）。
- **启动时会做一次向量库对账**：后端比对 `chunks` 表里还存在的 doc_id 与 MySQL 的 `documents` 表，
  文档行已经没有了、向量却还留在库里的，按文档删掉并在日志留一条 WARN，形如
  `向量库对账: 文档 <doc_id>（<文档名（块数）>）已不存在，清掉其遗留的 N 个向量分块`。
  这些孤儿向量本来就检索不到（检索按可见知识库过滤），只会把总览的"总分块数"顶虚，清掉是安全的；
  以前靠手工 SQL 清，现在不用了。
  **唯一的例外**：读不到基准清单时这一步整体跳过（MySQL 载入失败，或那条 `SELECT id FROM documents` 本身失败），
  日志变成 `MySQL 未载入，跳过三项启动对账（中断解析 / 中断评测 / 向量孤儿）` 或 `文档 id 基准清单读取失败，跳过向量库对账: …`——
  此时清单是空的，照它清库等于删光全部向量。看到这两条就先修 MySQL 连接再重启，不要手工删 `chunks`。
  （第八批 D-30 之前基准清单是进程内文档镜像的 keySet，现在就是这条 SQL；同一句里的"读不到就跳过"逻辑不变。）
- **没有迁移工具，仓库里的 `.sql` 全是运维脚本**：`scripts/add-hot-read-indexes.mysql.sql` 也一样——它只是把实体上声明的那 12 条索引写成"发版前可以主动执行"的形式，不是迁移版本。列和索引都由实体声明、`ddl-auto: update` 在启动时对库执行；它只加列、加索引（按名字判存在），**从不删表、从不放宽已有的 NOT NULL**。
  库里唯一的 UNIQUE 是历史遗留的 `users.account`：**业务唯一性（库名、同库文档名、账号/工号、题干）由代码层判重，第九批 D-2 起生效**，代价是读后写不原子、且不会自动发现存量撞车（核对 SQL 见 10.1 ⑤）。别为了"重名"去共享生产库加约束。
- **没有 HTTPS**：裸 HTTP，JWT 与文档内容在公网明文传输。对外提供服务前在这台主机上叠一层 TLS
  （nginx/caddy 或云厂商 SLB 证书），再把 `CORS_ORIGINS` 换成 `https://` 域名。
  另外那三套数据服务目前可被公网直连，这是比本方案更大的风险，值得单独收一收（安全组限源或走内网）。
- **单机单实例**：后端是有状态的（解析用的进程内 2 线程队列 + 监控环形缓冲 + pgvector/Redis 降级时的内存副本 + users 鉴权镜像），水平扩容前需要先把上传目录换成共享存储。
  业务数据本身**不再算状态**（第八批 D-30 之后知识库/文档/会话/评测/审计每请求直读 MySQL），多副本间剩下的不一致点只有那个 60s 的 users 镜像与环形缓冲里的当日请求计数。
- **容器以 root 运行**：`/app/data/uploads` 是命名卷（root 属主）。已加 `no-new-privileges`；
  公网多租户主机建议改专用 uid（同时 chown 卷并在 compose 里加 `user:`）。
- 监控中心的 p95 把轻量请求和问答流混在同一环形缓冲里算百分位，**不能**用它判定 AC-5 的首 Token 指标（判据在 `scripts/acceptance/run.mjs`；原需求手册已于 2026-09-30 删除，别再去找它的数字）。
- MinIO/RabbitMQ、Prometheus/Grafana 仍以进程内实现替代。

### 13.4 [本机] 不开容器怎么调试

从 2026-10-01 起（缺陷 D-21/D-29 修复），`application.yml` 里**不再保留任何出口地址与凭据的兜底值**：
三个数据服务的 host 默认 `127.0.0.1`，账号/口令/JWT 密钥默认为空。
裸跑 jar 想连服务器那套实例，必须自己把这些变量注入进程——**这不是可选步骤，缺了就不启动**。

```powershell
cd F:\moxhan\ai-project\zhi-hui-dong-cha\backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

# 三个数据服务：填服务器实例的真实连接信息（与线上 .env 同一套）
$env:MYSQL_HOST = "127.0.0.1"; $env:MYSQL_USER = "mysql";  $env:MYSQL_PASSWORD = "root"
$env:REDIS_HOST = "127.0.0.1"; $env:REDIS_PASSWORD = "root"
$env:PG_HOST    = "127.0.0.1"; $env:PG_USER = "postgres";  $env:PG_PASSWORD = "root"

# HS256 密钥：至少 32 字节，本机每次换一个就等于换一批登录态，够用即可
$env:JWT_SECRET = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N").Substring(0, 16)

$env:LLM_API_KEY = "sk-你自己的key"
& "$env:JAVA_HOME\bin\java" -Dfile.encoding=UTF-8 -jar target\zhihu-dongcha-backend.jar

# 另开一个 PowerShell 窗口
cd F:\moxhan\ai-project\zhi-hui-dong-cha\frontend
npm run dev                                  # → http://localhost:5173，/api 已代理到 8080
```

填错会怎样，按报错对号：

| 现象 | 原因 |
|---|---|
| 启动即抛 `IllegalStateException: app.jwt.secret 未配置` | 没给 `JWT_SECRET`。这是有意的 fail-fast，兜底密钥等于任何人都能自签令牌 |
| `Access denied for user ''@'...'` / Hikari 起不来 | `MYSQL_USER`/`MYSQL_PASSWORD` 没给，正拿空凭据连库 |
| 日志 `Communications link failure`、连的是 `127.0.0.1:3306` | `MYSQL_HOST` 没给，走了"连本机"的默认值 |
| `/api/health` 的 `pg=false`、`vectors=in-memory` | `PG_*` 没给对。功能不中断，向量也仍是**真向量**（内存里算余弦），但重启即失、容量受堆限制，与 pgvector 的检索结果不保证逐条一致（见 13.3） |
| 文档上传后停在 `FAILED`，日志/错误信息写"当前没有可用的真实 embedding 服务" | 没注入 `LLM_API_KEY`（或 `LLM_PROVIDER=mock`）。D-14 起默认**拒绝**用 1024 维哈希假向量入库——它和真向量同维、入库不报错，却会静默污染检索质量且事后无法区分。本机确实要拿假向量跑通链路，再显式加 `$env:LLM_ALLOW_HASH_VECTORS = "true"`（伴随 WARN，别在服务器上做） |
| `/api/health` 的 `llm=mock` | 没注入 `LLM_API_KEY` 或探活失败，问答退回本地模板；此时检索向量化也被闸门拦住（上一行） |

如果你本机自己装了 MySQL/Redis/pgvector，那就不用改 host，只填本机那套的账号口令即可——
默认值指的就是 `127.0.0.1`。

注意两点：不注入 `LLM_API_KEY` 也能起（日志有 ERROR，问答退回 mock），但**文档入库会被拒绝**，
除非你显式设 `LLM_ALLOW_HASH_VECTORS=true`；
而**这套连的库和线上容器是同一个**，你的测试写入与改表结构会直接影响生产，见 13.3 第一条。

### 13.5 [本机] Git 远端的明文 HTTP 与凭据处置方案（D-35）

**先定性：这一节是方案，不是操作记录。** 2026-10-01 你给的要求是"只出方案、不执行"，所以本轮
**没有**改任何 `git` 配置、**没有** push、**没有**生成密钥、**没有**吊销 token。
下面每条命令都由你自己决定何时敲。它写在部署文档里是因为处置动作跨本机与服务器两台，
且和 13.3 的"没有 HTTPS"是同一条根因（明文链路 + 单主机）。

**读这一节前的一个坑（2026-10-08 地址替换带来的）**：本节所有 `127.0.0.1` 都是**当时那台服务器真实地址的替代值**，
包括"实测输出"表格里的 URL 和键名。因此 `git config --global --unset credential.http://127.0.0.1:3000.provider`
照抄**必然失败**——那一节自己就写了"键名和输出里那行必须逐字一致"，而逐字一致的对象是你 `git config --get-regexp '^credential\.'`
的实际输出，不是这里印的字符串。`ssh -T`、`git remote set-url` 同理，先把地址换回真实的那台再敲。

#### 现在的实际形态（本机只读探测结果，2026-10-01）

| 探测命令（本机，只读，不改任何东西） | 实测输出 | 含义 |
|---|---|---|
| `git config --get-regexp '^remote\..*\.url$'` | `remote.origin.url http://127.0.0.1:3000/moxhan/zhi-hui-dong-cha.git` | scheme 是**明文 http**；3000 是 Gitea；主机与生产站点同一台 |
| 看同一条 URL 的 userinfo 段 | URL 里**不含** `user:token@` | 早先登记的"远端 URL 内嵌凭据"**不成立**，已在缺陷文档 D-35 更正 |
| `git config --get-regexp '^credential\.'` | `credential.helper manager`、`credential.http://127.0.0.1:3000.provider generic` | 凭据由 Windows Git Credential Manager 存在**系统凭据管理器**里，不在仓库文件里 |
| `Test-Path "$HOME\.git-credentials"` | `False` | 没有明文凭据文件；打包或备份仓库目录不会把 token 一起带走 |
| `git config --get http.sslbackend` | `schannel` | 走 Windows 的 TLS 栈——但当前 URL 是 http，这套 TLS 实际没被用上 |

所以风险不是"token 印在配置文件里"，而是这三条：

1. **传输明文**：Basic 认证在 http 上等于把 token 放在可读的报文里过公网，链路上任何一跳拿到就作废。
2. **一个秘密两个故障域**：Git 服务与生产应用同主机。主机失陷 = 站点和唯一远端副本一起没了（连带 D-26 的"异地"前提）。
3. **不知道这个 token 还被用在哪儿**：只要它出现在 CI、另一台机器的凭据管理器、脚本或服务器 `.env` 里，
   明文链路上的每一次抓取都要按"已暴露"算。

#### 方案 A（推荐）：改走 SSH，让 token 不再过 HTTP 链路

执行顺序是"先探路，再落地"，不要一上来就 `set-url`。

```powershell
# [本机] 1. 看本机有没有可用的 SSH 客户端（Windows 10/11 一般自带 OpenSSH）
ssh -V                                   # 期望输出形如 OpenSSH_for_Windows_8.x，直接打印版本号

# [服务器] 2. 只读探测：sshd 监听哪个端口。第一个排除"是不是我们自己的容器"
sudo ss -lntp | grep -E ':(22|2222) '   # 期望看到 sshd 进程名；docker-proxy 说明不是宿主 sshd
# 宿主上通常有 22。若这里是空，说明安全组没放，方案 A 先决条件不成立，直接走方案 B。

# [本机] 3. 用探到的端口试连（这一步只会提示"未授权"，不会改任何配置）
ssh -T git@127.0.0.1 -p 22            # 第一次会问 yes/no 记录指纹；看到 Permission denied (publickey) 是**正常**的
# 连不上时：Connection refused = 端口没开/安全组拦截；no matching host key type = 本机 OpenSSH 太旧，先装新版

# [本机] 4. 生成一把**只给 Git 用**的密钥（-f 指定文件名，避免覆盖你已有的 id_ed25519）
ssh-keygen -t ed25519 -C "git-zhihu-dongcha" -f "$HOME\.ssh\id_ed25519_gitea"
# 会问两次 passphrase：留空 = 之后每次 push 都不用输，但私钥文件被拿走就能直接用；
# 设了 = 每次输入，或交给 ssh-agent。你自己按"这台笔记本会不会丢"来判断。

# [本机] 5. 打印**公钥**（公钥可以公开，私钥文件 id_ed25519_gitea 千万不要粘贴到任何地方）
Get-Content "$HOME\.ssh\id_ed25519_gitea.pub"
```

```text
# [浏览器] 6. 把上一步输出的整行贴进 Gitea：头像 → Settings → Applications / SSH / GPG Keys → Add Key
#    保存后本机立刻验证身份（还是不改动仓库）：
```

```powershell
# [本机] 7. 身份验证：期望输出形如 "Hi there, moxhan! ... successfully authenticated"
ssh -T git@127.0.0.1 -p 22
# 仍是 Permission denied (publickey) = 公钥没保存成功，或保存到了别的账号；不要继续下一步

# [本机] 8. 最后一步才改仓库配置（端口用第 2 步探到的实际值，不要把下面这行的 22 当成模板照抄）
git remote set-url origin ssh://git@127.0.0.1:22/moxhan/zhi-hui-dong-cha.git

# [本机] 9. 复核：URL 已换、且不含任何凭据
git config --get-regexp '^remote\..*\.url$'    # 期望 ssh:// 开头，且没有 user:token@ 段
git ls-remote --heads origin                    # 能列出 master 就通了；这不需要任何 token
```

填错会怎样：

| 现象 | 原因与处理 |
|---|---|
| `git ls-remote` 报 `fatal: Could not read from remote repository` | 第 8 步路径写错（Gitea 的 URL 是 `ssh://git@host:port/<用户>/<仓库>.git`，`git@` 固定，用户是 moxhan）；先用 `git remote -v` 目视核对 |
| `ssh: connect to host ... port 22: Connection refused` | 第 2 步探到的端口不对或安全组没放 22；改回原 URL 不丢东西（`git remote set-url origin http://127.0.0.1:3000/moxhan/zhi-hui-dong-cha.git`） |
| `Host key verification failed` | 你知道的主机指纹没记录，重新 `ssh -T` 输 yes；**不要**用 `StrictHostKeyChecking=no` 绕过——那等于放弃"对端是不是你那台服务器"的校验 |
| 第 9 步 URL 里出现 `oauth2:xxxxxxxx@` | 你（或某个 GUI 工具）把凭据内嵌进了 URL，这正是 D-35 最初登记的形态，比现在的方案更糟（明文落在 `.git/config`，`git bundle`/目录打包都会带走）。用第 8 步的命令重设一次 |

#### 方案 B：必须留在 http(s) 路线时，先让服务端有 TLS

Gitea 现在是裸 http，任何"把 token 走 https"的说法都还没成立。顺序不能颠倒：

1. [服务器] 先按 13.3 "没有 HTTPS" 那条做：给这台主机叠一层 TLS（caddy/nginx 反代 Gitea 的 3000，或云厂商 SLB 证书），
   并确认 Gitea 的 `SSH_URL`/`DOMAIN` 配置同步改掉，否则网页里的 clone 地址仍是旧的。
2. [本机] 把 scheme 换成 https。**主机名必须换成证书 CN/SAN 里那个名字**——现在这条远端用的是 IP 地址，
   公网 CA 不会给裸 IP 签证书，所以第 1 步落的是"域名 + 证书"，这里就得填同一个域名（手工替换，别照抄示例）：
   ```powershell
   git remote get-url origin                    # 先记下现值，改坏了能原样设回去
   git remote set-url origin https://你的证书域名/moxhan/zhi-hui-dong-cha.git
   curl -v https://你的证书域名                 # 看是否 SSL certificate verify ok
   ```
   对号：报 `Could not resolve host: 你的证书域名` / `no such host` = 上面两处示例没替换成真域名
   （中文主机名连 DNS 都进不去）；这两条命令里的域名要一起换。
   报 `x509: certificate is valid for git.xxx.com, not 127.0.0.1` = 你保留了 IP 却用了域名证书；
   把 IP 换成证书里那个域名再试。报 `unable to verify the first certificate` = 自签证书没被本机信任，
   要么把 CA 装进系统信任链，要么在第 1 步改用受信任证书；**不要**用
   `git config --global http.sslVerify false` 硬过——那等于关掉唯一能证明"对端是你那台服务器"的环节。
3. 换完 URL 后，全局配置里那条 `credential.http://127.0.0.1:3000.provider generic` 就成了孤儿。
   `git config` 没有按正则批量删的选项，只能按完整键名 unset：
   先 `git config --get-regexp '^credential\.'` 留一份输出，再
   `git config --global --unset credential.http://127.0.0.1:3000.provider`（键名和输出里那行必须逐字一致，
   写错报 `section could not be removed as it was not found` 或什么也不删）。

#### 方案 C：换路线之后，旧 token 怎么处理（这步最容易被跳过）

判断树只有两问：

1. **能否证明这个 token 只在本机的凭据管理器里用过？** 能——那就先按 A/B 换路线，再重新签发一枚权限最小
   （只给这个仓库、只给 push/pull 权限、设过期时间）的 token，旧的那枚随即在 Gitea 的 Applications 页删除。
2. **不能证明？**（比如它抄进过 CI、粘进过聊天、或在服务器哪份配置里出现过）——**先吊销再换**，顺序反过来
   等于给自己留一段"旧凭据仍然有效"的窗口。吊销的具体位置：Gitea → Settings → Applications → 对应 token 右侧 Delete。

吊销后本机第一次 `git fetch` 会提示输入用户名/口令或弹 GCM 窗口，把**新** token 填进去即可；
如果它一直重试旧值，是系统凭据管理器里留了旧条目：`cmdkey /list` 只列目标名（不显示秘密），
看到 `git:http://127.0.0.1:3000` 就 `cmdkey /delete:git:http://127.0.0.1:3000` 清掉再试。

#### 不要做的事

- 不要把 token 写进远端 URL（哪怕"临时试一下"）：那会退回到 D-35 登记时最糟的形态，而且比现状更差。
- 不要在文档、commit message、issue 里粘贴 token 字面值；要指认它，用"哪台机器上哪个目标名"来描述。
- 不要用 `git credential fill` 去"验证凭据还在不在"——它会把秘密打印到标准输出。
- 不要顺手 `git push`。本轮的 24 项修复都在未提交的工作区（见 D-26），push 的时机由你决定。

