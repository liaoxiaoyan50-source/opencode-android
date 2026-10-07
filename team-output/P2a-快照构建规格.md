# P2a — 快照构建规格（OpenCode Android · Phase 2）

> 产出人：快照构建工程师 陈镜成（snapshot-builder）· Phase 2 交付
> 契约依据：`team-output/P1-架构总纲.md` §3.1（C1 manifest schema）、§5.1（本角色输入摘要）、D3（体积门禁）、§3.5（目录与 bind）；配套脚本 `code/build-snapshot.sh`（本规格与其同步定稿）。
> 效力声明：本规格是对 P1 契约的实现细化，不引入任何契约变更；对契约原文的观察与请终裁事项集中在 §6。

---

## 1. 快照构成与体积预算

### 1.1 总预算表

口径：P1 D3 定稿 —— **压缩包 120–160MB 预算，160MB 为 CI 硬门禁（超限 fail，禁止静默放行）；解压后 ≤ 320MB**。

| # | 内容 | 版本策略 | 解压后（估） | 压缩后（估） | 预算备注 |
|---|------|----------|--------------|--------------|----------|
| 1 | Ubuntu Base 24.04.2 arm64（minbase，cdimage 直链） | 锁定 `UBUNTU_BASE_VER=24.04.2` | ~95MB | ~30MB | README §4.1 基线口径 |
| 2 | opencode linux-arm64 单文件二进制（glibc 版） | 锁定 `OC_VERSION=1.18.34`，直链 release 资产 `opencode-linux-arm64.tar.gz` | ~105–115MB | ~50MB | 任务书口径 40–50MB 与 README ~55MB 的中值；Bun 二进制压缩比高，**以首版 CI 实测回填** |
| 3 | 工具链 `PKGS`（13 组，骨架清单冻结） | apt（24.04 源，`--no-install-recommends`） | ~75–85MB | ~28–32MB | 逐项分解见 §1.2 |
| 4 | 预配置（resolv.conf、profile.d、opencode.json 模板、locale C.UTF-8） | 随脚本 | <1MB | <1MB | |
| — | **合计** | | **~280MB** | **~110–120MB** | 解压 ≤320 ✓；压缩落在 120–160 预算内，**距 160MB 门禁余量约 40–50MB** |

余量用途：opencode 后续版本二进制膨胀（Bun 版本升级常态）+ 工具链安全更新。若余量被吃穿，按 §1.3 裁剪。

### 1.2 工具链逐项估算（PKGS，均为量级估算）

| 包 | 解压后（估） | 压缩后（估） | 备注 |
|----|--------------|--------------|------|
| git（含 git-man、libcurl4/libexpat/libpcre2 依赖闭包） | ~35MB | ~15MB | 工具链最大头；git-man ~9MB 随 §1.3 第 1 步可结构性剔除 |
| openssh-client | ~5.5MB | ~2.2MB | git ssh 远程依赖 |
| locales（仅生成 C.UTF-8） | ~3.3MB | ~1MB | 24.04 的 C.UTF-8 为 glibc 内置，生成物极小 |
| ripgrep | ~2.2MB | ~0.9MB | opencode 内置搜索工具依赖 |
| procps | ~1.6MB | ~0.6MB | ps/free 诊断 |
| tmux（+libevent） | ~1.6MB | ~0.7MB | |
| curl（+libcurl4） | ~2.4MB | ~1MB | |
| jq（+libjq+libonig） | ~1.6MB | ~0.6MB | |
| wget | ~1.1MB | ~0.5MB | |
| unzip + zip | ~1.0MB | ~0.5MB | unzip 为 agent 常用解包；opencode 本体已改用 tar.gz 直链，不再依赖 |
| ca-certificates | ~0.6MB | ~0.3MB | TLS 信任锚，**不可裁** |
| less | ~0.4MB | ~0.2MB | |
| fd-find | ~0.15MB | ~0.1MB | |

> 数字为包级估算（Ubuntu noble/arm64 量级），**首版 CI 构建成功后以 `tar -tzf | xargs du` 实测值回填本表**，作为后续裁剪决策的依据。

### 1.3 超门禁裁剪优先级（P1 D3：先裁工具链）

门禁触发时按序执行，**每裁一步重跑构建核对门禁**；错误信息已在脚本中指向本节：

| 序 | 动作 | 预计压缩后节省 | 功能影响 |
|----|------|----------------|----------|
| 1 | **结构性裁剪**（不动 PKGS 清单）：tar 追加 `--exclude='./usr/share/doc' --exclude='./usr/share/man' --exclude='./usr/share/info'` | ~6–10MB | 零功能损失：chroot/apt/agent 均不读 doc/man；git-man 一并随 man 剔除 |
| 2 | 裁 `wget` | ~0.5MB | `curl` 全覆盖 |
| 3 | 裁 `zip` | ~0.3MB | 保留 `unzip`；打包场景 tar 可替代 |
| 4 | 裁 `less` | ~0.2MB | pager 低频，`more` 顶替 |
| 5 | 裁 `procps` | ~0.6MB | 诊断期可 `/proc` 直读 |
| 6 | 裁 `fd-find` | ~0.1MB | `ripgrep` 覆盖大多数搜索；**裁前跑一轮 agent 工作流冒烟**（确认 opencode agent 的 bash 工具链无 fd 硬依赖） |
| 7 | 裁 `tmux` | ~0.7MB | `opencode serve` 无头模式不需要；仅 TUI 多窗场景使用 |
| 8 | 裁 `openssh-client` | ~2.2MB | git https 工作流可裁；ssh 远程失效，需评估 agent 用户群 |
| 9 | 上报主理人 | — | 以上仍超限 → 预算/方案重议（候选：opencode musl 变体，但需 M0 级 glibc 兼容性验证，不默认推荐） |

裁剪动作 = 改 `build-snapshot.sh` 的 `PKGS` / tar 参数 → **必须同步更新 P2a §1.2 表格与回传主理人**，不允许脚本单方面悄悄改。

### 1.4 门禁口径（定稿）

- **160MB ≡ 160 MiB ≡ 167772160 字节**；实际字节数**严格大于**门禁即 fail（等于放行）。
- 脚本内判断：`SIZE > 160*1024*1024`（`build-snapshot.sh` §[6/6]）；CI 侧独立复核同口径（§3.1）。
- 该口径为我的实现定稿，请主理人终裁确认（§6-1）；如改用 10 进制 160,000,000 口径，只影响 1.5% 的余量，两端同步改一行即可。

---

## 2. manifest.json 生成逻辑

### 2.1 生成流水线（`build-snapshot.sh` §[6/6]，4 步）

1. **哈希**：`sha256sum dist/oc-ubuntu-arm64.tar.gz` → 断言 `^[0-9a-f]{64}$`（64 位小写 hex，防换工具后大小写/格式漂移）。
2. **体积**：`stat -c%s` 取精确字节数 → 过 P1 D3 门禁（超限 exit 1，**此时不产出 manifest**，超限快照不可能进入发布流程）。
3. **excludes JSON 化**：`printf '%s\n' "${EXCLUDE_TAR[@]}" | jq -R . | jq -s .` —— **tar 排除清单与 manifest.excludes 由同一个 bash 数组 `EXCLUDE_TAR` 驱动**，结构上杜绝「打包排除了一份、manifest 写了另一份」的两端漂移。
4. **jq -n 组装**：全部字段经 `--arg`/`--argjson` 注入生成（`size` 走 `--argjson` 保证 JSON number 类型，非字符串）；生成后用 `jq -e` 对 C1 schema 全量自检断言，不过即 die（防 jq 参数拼装回归）。

### 2.2 字段来源对照表（逐字段 ↔ P1 §3.1）

| 字段 | 脚本来源 | 生成方式 | P1 §3.1 规则落点 |
|------|----------|----------|------------------|
| `schemaVersion` | 常量 `SCHEMA_VERSION=1` | `--argjson`（int） | 固定 1；App 读到非 1 拒绝安装 |
| `snapshotVersion` | `$(date -u +%Y%m%d)-oc${OC_VERSION}` | `--arg` | 格式 `YYYYMMDD-oc<ocVersion>`，纯字典序比较；`-u` 消除 runner 时区漂移 |
| `ocVersion` | `OC_VERSION`（默认 1.18.34，与 release.yml env 同口径） | `--arg` | 与 `GET /global/health` 的 version 前缀一致（chroot 冒烟已断言二进制 `--version` 含此值） |
| `ubuntuBase` | `UBUNTU_BASE_VER=24.04.2` | `--arg` | 诊断与追溯，不做逻辑判断 |
| `file` | `SNAP_NAME=oc-ubuntu-arm64.tar.gz` | `--arg` | 固定文件名，lite 变体按此拉取 |
| `sha256` | `sha256sum` 压缩包全文 | `--arg` | 64 位小写 hex；App 安装流式校验，不匹配整目录回滚 |
| `size` | `stat -c%s` 精确字节 | `--argjson`（number） | lite 下载进度与磁盘预检（可用空间 ≥ size + size×2.1） |
| `format` | 字面量 `"tar.gz"` | 字面量 | 诊断 |
| `layout` | 字面量 `"rootfs-at-tar-root"` | 字面量 | 诊断（tar 以 `.` 打包，rootfs 即压缩包根） |
| `excludes` | `EXCLUDE_TAR=(./workspace ./root/.local/share/opencode)` | `--argjson`（string[]） | 与 App 端升级搬移清单互为镜像，两端同步维护（§5.1） |
| `minAppVersion` | 常量 `MIN_APP_VERSION="1.0.0"` | `--arg` | semver；lite 下载前校验，不满足阻断并引导升级 App |

---

## 3. CI 门禁对接（release.yml `job_snapshot`）

### 3.1 可直接粘贴的 YAML 片段

插入位置：`job_snapshot` 内 `Build engine snapshot` step 之后、`upload-artifact` 之前（建议顺序：build → size gate → manifest verify → smoke → upload）。

```yaml
      # P1 D3: 快照体积硬门禁（与 build-snapshot.sh 内置门禁同口径: 160MB ≡ 160MiB ≡ 167772160 字节）
      - name: Enforce snapshot size gate
        run: |
          SIZE=$(stat -c%s dist/oc-ubuntu-arm64.tar.gz)
          MAX=167772160
          echo "snapshot size = ${SIZE} bytes (gate = ${MAX})"
          if [ "${SIZE}" -gt "${MAX}" ]; then
            echo "::error::快照超体积门禁 ${SIZE} > ${MAX} 字节。先裁工具链（P2a §1.3 裁剪优先级），禁止静默放行"
            exit 1
          fi

      # P1 §3.1/§5.5: manifest 一致性复核（sha256 / size / schemaVersion 三断言）
      - name: Verify manifest consistency
        run: |
          cd dist
          jq -e '.schemaVersion == 1' manifest.json > /dev/null \
            || { echo "::error::manifest schemaVersion != 1"; exit 1; }
          jq -e '.snapshotVersion | test("^[0-9]{8}-oc")' manifest.json > /dev/null \
            || { echo "::error::snapshotVersion 不符合 YYYYMMDD-oc<ver> 格式"; exit 1; }
          EXPECTED_SHA=$(jq -r '.sha256' manifest.json)
          ACTUAL_SHA=$(sha256sum oc-ubuntu-arm64.tar.gz | cut -d' ' -f1)
          if [ "${EXPECTED_SHA}" != "${ACTUAL_SHA}" ]; then
            echo "::error::sha256 不一致: manifest=${EXPECTED_SHA} actual=${ACTUAL_SHA}"
            exit 1
          fi
          EXPECTED_SIZE=$(jq -r '.size' manifest.json)
          ACTUAL_SIZE=$(stat -c%s oc-ubuntu-arm64.tar.gz)
          if [ "${EXPECTED_SIZE}" != "${ACTUAL_SIZE}" ]; then
            echo "::error::size 不一致: manifest=${EXPECTED_SIZE} actual=${ACTUAL_SIZE}"
            exit 1
          fi
          echo "manifest 一致性校验通过 (sha256=${ACTUAL_SHA}, size=${ACTUAL_SIZE})"
```

### 3.2 校验链路说明（双保险定位）

- **脚本内已内置同逻辑**（体积门禁 + manifest 自检断言）；CI step 是**独立于脚本的复核**——即使脚本被误改/回归，CI 仍会拦截。符合 P1 D3「门禁落点：release.yml `job_snapshot` 增加体积检查 step」与 §5.5 CI gate ②③。
- **sha256/size 一致性校验的完整链路（三个校验点）**：
  1. **生成时**：脚本从最终 tar.gz 计算 sha256/size 写入 manifest（单事实来源）；
  2. **发布前**：CI step 独立重算比对（§3.1 片段）；
  3. **安装时**：App 端 SnapshotInstaller 流式校验 sha256，不匹配整目录回滚（P1 §3.1，engine-layer 实现）。
- 三个校验点的哈希算法一致（`sha256sum` 全文哈希），任何一环字节变化都会被下游拦截。

### 3.3 路径落位映射（给 release-ops 的入仓说明）

`release.yml` 引用 `snapshot/build-snapshot.sh`，而本次定稿脚本在工作区 `code/build-snapshot.sh`——对应 README §7「code/ 下文件可直接入仓」与 §8 目录结构：**入仓时 `code/build-snapshot.sh` → 仓库 `snapshot/build-snapshot.sh`**（`code/` 为骨架工作副本区，`release.yml` 同理落 `.github/workflows/`）。脚本已做非 root 自提升（sudo 免密重执行），release.yml 现有调起方式 `OUT_DIR=dist ./snapshot/build-snapshot.sh "${OC_VERSION}"` **无需任何改动**。

---

## 4. 可复现性说明（内容寻址）

### 4.1 冻结参数逐条（P1 §5.1）

| 参数 | 消除的不确定性 |
|------|----------------|
| `--numeric-owner` | tar 头 uid/gid 以数字入档，不依赖构建机账户映射（root 恒为 0/0） |
| `--sort=name` | entry 顺序按名排序，消除文件系统遍历顺序不确定性 |
| `--mtime='UTC 2026-01-01'` | **全部 entry 的 mtime 冻结为常量**。这是最大非确定源：apt 安装的文件 mtime 每次构建必然不同，不冻结则同内容两次构建哈希必不一致 |
| `--anchored --exclude=...`（实现增强） | 排除集精确为 tar 根下两条路径及其子树，匹配行为确定（`--anchored` 必须置于 `--exclude` 之前） |
| `date -u`（snapshotVersion） | 构建日期 UTC 化，跨时区 runner 不影响格式单调性 |
| 构建后 `find ${ROOTFS}/tmp -mindepth 1 -delete` | guest /tmp 无随机残留进包 |

### 4.2 gzip 头时间戳实测（本规格的验证结论）

tar 经管道喂 gzip 时，gzip 头 mtime 字段**恒为 0**（无时间戳泄漏）。实测环境 GNU tar 1.35：同一内容间隔 1.2 秒两次 `tar --numeric-owner --sort=name --mtime='UTC 2026-01-01' -czf` 打包，**sha256 完全一致**，且 gzip 头 mtime 字段 = 0。**结论：契约冻结的三参数即足以保证内容寻址复现，无需追加 `GZIP=-n` 或改用两段管道。**

### 4.3 确定性的边界（如实声明，避免下游误期待）

- 本规格保证的是「**同输入 → 同哈希**」的确定性构建：输入锁定点为 `UBUNTU_BASE_VER=24.04.2`（cdimage 直链）+ `OC_VERSION=1.18.34`（release 直链）+ 冻结的 PKGS 清单与 tar 参数。
- **`PKGS` 未 pin apt 修订号**：跨月重建可能因 Ubuntu security 更新导致哈希不同。这是权衡结果（pin 需要维护快照镜像、升级成本高）；P1 §5.1「内容寻址可复现」的契约意图定位在打包层确定性，本实现满足。
- 若未来需要月级/年级强复现（供应链审计强化），可引入 apt 快照镜像或修订号 pin —— 属新增工作量，**需主理人立项，P2 不做**。
- 用途：① CI 内容寻址——重建产物 sha256 相同即内容零漂移，可跳过重复发布；② 供应链审计——由哈希可溯源到「哪个 ubuntu-base + 哪个 opencode + 哪份 PKGS」。

---

## 5. 交给下游的注意点

### 5.1 → engine-layer（解压安装与 excludes 镜像关系）

**三方对照表（当前冻结基线，改动任一侧必须同步评估另两侧）：**

| 运行期数据路径 | manifest.excludes（构建侧，不进包） | P1 §3.5 保留目录（升级侧，搬移/防御） | 说明 |
|----------------|--------------------------------------|----------------------------------------|------|
| `./workspace` | ✅ | —（实体在宿主外部存储，不在 rootfs 内） | bind 挂载点，由 proot 虚拟层提供，无需搬移 |
| `./root/.local/share/opencode` | ✅ | ✅（搬移保留，会话库 opencode.db 在此） | **核心数据**：快照升级唯一必须搬移的目录 |
| `./root/.config/opencode` | ❌（模板随新快照走） | ✅（搬移仅为防御性兼容） | 运行期被 App 的 oc-auth bind 遮蔽（P1 §3.5 配置唯一写入者原则），快照内模板不生效 |

- 「互为镜像」的正确读法：**excludes = 构建侧剔除的运行期数据目录；保留/搬移清单 = 安装侧不覆盖的目录**。两侧来自同一份运行期数据语义，但清单**不必逐字相同**（workspace 与 config 的差集各有结构性理由，见表）。P1 §3.1 原文表述与此存在措辞级落差，已列 §6-2 请终裁，不阻塞实现。
- 解压/升级实现要求：SnapshotInstaller 流式解压时，entry 头命中 `./workspace`、`./root/.local/share/opencode` 两路径前缀时**跳过写盘**（不覆盖旧数据），而非删除；`.snapshot-meta.json` 写入时序按 P1 §3.1（原子 rename rootfs 之后才写 meta）。
- 首启磁盘预检用 `manifest.size`（可用空间 ≥ size + size×2.1，P1 §3.1）。
- 升级时以 `manifest.snapshotVersion` 字典序比较新旧（P1 §3.1，App 禁止解析内部结构）。

### 5.2 → release-ops（job_snapshot 产物清单与发布纪律）

1. **产物清单（缺一不可）**：`dist/oc-ubuntu-arm64.tar.gz` + `dist/manifest.json`。现 release.yml `upload-artifact name=snapshot, path: dist/` 已覆盖；②-c Release 附件两者均已列出，勿删。发布产物完整基线 = full APK + lite APK + tar.gz + manifest.json（P1 §5.5）。
2. **tar.gz 发布前禁止任何二次处理**：重压缩、重命名、转存改动字节，都会使 sha256 与 manifest 失配 → App 端安装校验失败整目录回滚（P1 §3.1）。manifest.json 文件名固定不变（lite 变体按固定名拉取）。
3. **snapshotVersion 的日期语义**：取构建当日 UTC。UTC 23:59 push tag 时构建日期可能与 tag 日期差 1 天——诊断字段层面可接受，字典序单调性不受影响（日期前缀仍单调）。
4. **门禁对接**：§3.1 两个 step 直接粘贴进 `job_snapshot`；口径与脚本内门禁一致（167772160 字节）。CI gate 四项中「快照体积 ≤160MB」「manifest schemaVersion==1」两项由本规格交付，其余两项（16KB 对齐、extractNativeLibs）归 native-builder/release-ops。
5. **脚本落位**：`code/build-snapshot.sh` 入仓至 `snapshot/build-snapshot.sh`（§3.3），release.yml 现有调起方式无需改动。

---

## 6. 契约观察与请终裁事项（无阻塞项）

| # | 事项 | 我的处理 | 请主理人终裁 |
|---|------|----------|--------------|
| 1 | 门禁「160MB」P1 未定义进制口径 | 定稿为 160 MiB = 167772160 字节，脚本与 CI 两端同口径 | 确认口径；如需 10 进制 160,000,000，两端各改一行 |
| 2 | P1 §3.1 称 excludes「与 §3.5 保留目录互为镜像」，但两清单非逐字同集（差集：`./workspace`、`root/.config/opencode`） | 语义可自洽（见 §5.1 三方对照表），实现按语义落地 | 建议 P1 下一版补一行澄清「镜像指语义同源，非逐字相同」，避免 engine-layer 误搬移 |
| 3 | release.yml 以普通用户调起脚本，chroot/mount 需 root | 脚本内置非 root 自提升（sudo 免密重执行），release.yml 零改动 | 无需裁决，归档为工程适配 |
| 4 | opencode 注入方式：骨架用 pipe-to-bash 安装脚本，本版改为官方 release 直链 `opencode-linux-arm64.tar.gz`（v1.18.34 资产名已实证） | P1 未规定注入方式；直链比安装脚本更防漂移、且精确锁定 glibc 版 linux-arm64 单文件，契合任务书「锁定官方 linux-arm64 单文件二进制」 | 确认为实现选择（非契约变更）；升级 OC_VERSION 时需核对资产名（脚本错误信息已提示） |
| 5 | README §4.1 出现过的「150–250MB」旧口径 | 按 P1 D3 定稿执行（120–160 预算 / 160 硬门禁），不再引用旧口径 | 已由 P1 D3 裁决，无遗留 |

---

## 7. 相对骨架（code/build-snapshot.sh 原版）的变更清单（摘要）

| # | 变更 | 动因 |
|---|------|------|
| 1 | manifest 补齐 `schemaVersion`/`excludes`/`minAppVersion` 三字段 + jq 全量自检断言 | P1 §7-4 / §3.1 |
| 2 | 新增体积硬门禁（>160MiB exit 1，错误信息指向裁剪优先级） | P1 D3 / 任务书 |
| 3 | opencode 注入：install 脚本 → release 直链 `opencode-linux-arm64.tar.gz`，`--version` 冒烟升级为「退出码 + 版本号匹配」双断言 | 防漂移（§6-4） |
| 4 | 非 root 自提升（sudo 重执行）+ aarch64 架构自检 | release.yml 零改动跑通 + 契约「arm64 原生」前置拦截 |
| 5 | `set -Eeuo pipefail` + ERR trap（行号/命令） + EXIT trap 清理（失败保留现场可诊断） | 任务书脚本质量要求 |
| 6 | tar 增加 `--anchored` 前置（exclude 锚定根级）；`date -u`；构建后清 guest /tmp | 排除语义精确化 / 时区无关 / 无残留进包 |
| 7 | `EXCLUDE_TAR` 成为 tar 排除与 manifest.excludes 的共同唯一事实来源 | 结构性杜绝两端漂移（§2.1 第 3 步） |
| 8 | curl 下载加 `--retry 5`；mount /sys /dev 失败从静默改为硬错误；curl/jq 依赖预检 | 错误可诊断，禁止静默降级 |
