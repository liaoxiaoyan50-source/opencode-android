#!/usr/bin/env bash
# =============================================================================
# build-snapshot.sh — OpenCode Android 引擎快照构建脚本（P2 定稿）
#
# 契约依据: team-output/P1-架构总纲.md
#   §3.1  C1 manifest schema（字段级规则，本脚本逐字段遵守）
#   §5.1  snapshot-builder 输入摘要（锁版本 / 原生 chroot / PKGS / tar 参数 / EXCLUDE_TAR）
#   D3    体积硬门禁（tar.gz > 160MB 即 fail，先裁工具链，禁止静默放行）
#   §7.4  骨架缺陷清单第 4 项（manifest 缺 schemaVersion/excludes/minAppVersion → 本版补齐）
#   契约未做任何变更；实现中发现的问题一律上报主理人终裁（见 P2a §6）。
#
# 产出:
#   ${OUT_DIR}/oc-ubuntu-arm64.tar.gz   引擎快照（Ubuntu 24.04 arm64 rootfs + opencode + 工具链）
#   ${OUT_DIR}/manifest.json            C1 schema，与 tar.gz 同发 GitHub Release（P1 §5.5）
#
# 构建方式: ubuntu-24.04-arm runner 上「原生 chroot」（arm64 host + arm64 rootfs，
#           无 qemu 仿真）。非 root 环境自动以 sudo 免密重新执行自身。
#
# 用法:   ./build-snapshot.sh [OC_VERSION]     # 版本优先级: 位置参数 > 环境变量 > 默认值
#         OUT_DIR=dist ./build-snapshot.sh     # 产物目录可覆盖，CI 用 dist/
#
# 依赖:   bash curl tar gzip jq sha256sum stat find mount chroot（需 root 或免密 sudo）
# =============================================================================
set -Eeuo pipefail

# ───────────────────────── [A] 版本锁定（唯一事实来源） ─────────────────────────
# 与 code/release.yml env.OC_VERSION 同一口径；升级 opencode 时两处必须同步修改。
OC_VERSION="${1:-${OC_VERSION:-1.18.34}}"

# ubuntu-base 小版本：cdimage 直链防漂移（README 附录 B）。升级时同步更新 P2a 预算表。
UBUNTU_BASE_VER="24.04.2"
UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_BASE_VER}-base-arm64.tar.gz"

# opencode 官方 linux-arm64 单文件二进制（glibc 版）。资产名已于 v1.18.34 实证：
# release 附件含 opencode-linux-arm64.tar.gz 与 opencode-linux-arm64-musl.tar.gz，
# 取 glibc 版（proot Ubuntu 为 glibc 环境）；直链锁定，不用 pipe-to-bash 安装脚本
# （防安装脚本未来改版漂移）。升级 OC_VERSION 时须核对 releases 页资产名仍在。
OPENCODE_ASSET="opencode-linux-arm64.tar.gz"
OPENCODE_RELEASE_URL="https://github.com/anomalyco/opencode/releases/download/v${OC_VERSION}/${OPENCODE_ASSET}"
OPENCODE_RELEASES_PAGE="https://github.com/anomalyco/opencode/releases"

# ───────────────────────── [B] 产物 / schema 常量 / 清单 ─────────────────────────
OUT_DIR="${OUT_DIR:-dist}"
# 构建工作目录：rootfs 解包 / chroot 现场（[1/6]–[5/6] 与 cleanup 共用，仅本处定义）。
# cleanup（[C] 节 EXIT trap）触发时才展开本变量，故定义必须早于 trap 注册——
# 放 [B] 节路径常量区与 OUT_DIR 相邻，正确性与可读性同时满足。支持环境覆盖
# （sudo -E 自提升透传；两次执行 cwd 不变，$(pwd) 派生值稳定一致）。
ROOTFS="${ROOTFS:-$(pwd)/.rootfs-build}"
SNAP_NAME="oc-ubuntu-arm64.tar.gz"
# P1 §3.1: snapshotVersion 格式 YYYYMMDD-oc<ocVersion>，比较为纯字典序；
# date -u 固定 UTC，消除 runner 时区漂移对字典序单调性的影响。
SNAPSHOT_VERSION="$(date -u +%Y%m%d)-oc${OC_VERSION}"

SCHEMA_VERSION=1           # P1 §3.1: 固定 1，App 读到非 1 值拒绝安装
MIN_APP_VERSION="1.0.0"    # P1 §3.1: lite 变体下载前校验，不满足则阻断升级 App

# 运行期生成目录：打包剔除（P1 §5.1 EXCLUDE_TAR 冻结清单，精确为以下两项）。
# 与 manifest.excludes、engine-layer 升级搬移清单互为镜像，任何改动两端必须
# 同步维护（对照表与镜像关系详见 team-output/P2a-快照构建规格.md §5.1）。
EXCLUDE_TAR=( "./workspace" "./root/.local/share/opencode" )

# 预装工具链（P1 §5.1 冻结骨架清单；如确需增删，先在 P2a §1 做体积论证并回传主理人）
PKGS=(
  git curl wget ca-certificates locales
  ripgrep fd-find jq unzip zip less procps
  openssh-client tmux
)

# 体积硬门禁（P1 D3）。口径定义：160MB ≡ 160 MiB ≡ 167772160 字节；
# 实际字节数「严格大于」门禁即 fail（等于放行）。
MAX_SNAPSHOT_BYTES=$((160 * 1024 * 1024))

# ───────────────────────── [C] 诊断 / 清理 / 环境自检 ─────────────────────────
die() { echo "FATAL: $*" >&2; exit 1; }

# 任何命令失败：打印行号与失败命令后退出（set -e 收尾），错误信息可定位可诊断
trap 'echo "FATAL: 命令失败 @ ${BASH_SOURCE}:${LINENO}: ${BASH_COMMAND}" >&2' ERR

cleanup() {
  local rc=$?
  umount -l "${ROOTFS}/proc" "${ROOTFS}/sys" "${ROOTFS}/dev" 2>/dev/null || true
  if [[ ${rc} -eq 0 ]]; then
    rm -rf "${ROOTFS}"
  else
    # 失败保留现场：chroot 内 apt/解包日志可直接进 CI 调试；runner 结束自动回收
    echo "==> 构建失败，rootfs 现场保留于 ${ROOTFS}（供诊断；CI 结束自动回收）" >&2
  fi
  exit "${rc}"
}
trap cleanup EXIT

# chroot / mount 需要 root：非 root 时以 sudo 免密重新执行自身（GitHub runner 免密）。
# 注意 release.yml 以普通用户调起本脚本，此自提升保证其无需改动。
if [[ ${EUID} -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "本脚本需 root（chroot/mount），且当前环境无 sudo"
  sudo -n true 2>/dev/null || die "sudo 需要密码；CI runner 应为免密 sudo，请检查 runner 配置"
  echo "==> 非 root 环境，自动以 sudo 重新执行"
  exec sudo -E OC_VERSION="${OC_VERSION}" OUT_DIR="${OUT_DIR}" bash "$0" "$@"
fi

# 原生 arm64 约束（P1 §5.1：不用 qemu，x86 交叉不属本脚本职责）
[[ "$(uname -m)" == "aarch64" ]] || die "必须在 arm64(aarch64) 机器原生构建，当前为 $(uname -m)。P1 §5.1 禁止 qemu 仿真路径，请改用 ubuntu-24.04-arm runner"
command -v jq >/dev/null 2>&1 || die "缺少 jq（ubuntu-24.04 runner 自带）"
command -v curl >/dev/null 2>&1 || die "缺少 curl（ubuntu-24.04 runner 自带）"

echo "==> 快照版本 ${SNAPSHOT_VERSION} | opencode ${OC_VERSION} | ubuntu-base ${UBUNTU_BASE_VER}"

# ───────────────────────── [1/6] 解包 ubuntu-base ─────────────────────────
echo "==> [1/6] 解包 ubuntu-base ${UBUNTU_BASE_VER} arm64"
# 干净起点（幂等重跑）：CI runner 每次全新，但本地复跑同目录时，上次失败被
# cleanup 刻意保留的现场会以「覆盖合并」语义混入本次快照（旧二进制 / 旧 apt
# 状态不被清走）；且残留 bind 挂载若未 detach，rm -rf 遇挂载点报
# "Device or resource busy" 非 0 退出。先防御性 lazy umount（与 cleanup 同构），
# 再清空重建；tar -C 要求目录存在，mkdir -p 必须先于解包。
umount -l "${ROOTFS}/proc" "${ROOTFS}/sys" "${ROOTFS}/dev" 2>/dev/null || true
rm -rf "${ROOTFS}"
mkdir -p "${ROOTFS}"
# pipefail 下 curl 失败（含 404/断流）即整体失败；--retry 抗 cdimage 偶发抖动
curl -fsSL --retry 5 --retry-delay 3 "${UBUNTU_BASE_URL}" | tar xz -C "${ROOTFS}"

# ───────────────────────── [2/6] 基础配置注入 ─────────────────────────
echo "==> [2/6] 注入基础配置（DNS / 环境 / locale）"
# DNS：App 运行期 bind 清单不含 resolv.conf（P1 §3.5 定稿：/dev /proc /sys /workspace /
# /root/.config/opencode），快照内静态 DNS 是 guest 唯一解析来源，必须保留写死
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "${ROOTFS}/etc/resolv.conf"
# 交互 shell（M1 终端形态直跑 TUI）用；serve 模式由 App 按 P1 §3.6 env -i 全量注入，
# 两处取值一致（C6 契约）
cat > "${ROOTFS}/etc/profile.d/opencode-mobile.sh" <<'EOF'
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export PATH="/usr/local/bin:/usr/bin:/bin"
export XDG_DATA_HOME="$HOME/.local/share"
EOF
# locale：C.UTF-8 为 Ubuntu 24.04 glibc 内置，locale-gen 幂等兜底（失败不阻断）
sed -i 's/^# *\(C.UTF-8 UTF-8\)/\1/' "${ROOTFS}/etc/locale.gen" 2>/dev/null || true

# ───────────────────────── [3/6] chroot 预装工具链与 opencode ─────────────────────────
echo "==> [3/6] chroot 预装工具链 + opencode ${OC_VERSION}（arm64 原生执行，无 qemu）"
mount --bind /proc "${ROOTFS}/proc" 2>/dev/null || mount -t proc proc "${ROOTFS}/proc"
mount --bind /sys "${ROOTFS}/sys" || die "mount --bind /sys 失败（需 root/CAP_SYS_ADMIN）"
mount --bind /dev "${ROOTFS}/dev" || die "mount --bind /dev 失败（需 root/CAP_SYS_ADMIN）"

# chroot 内脚本：先写盘再执行（比 heredoc-inline 更可诊断，失败现场可查 /tmp/setup.sh）。
# 变量（PKGS / URL / 版本）在写入时由 host 侧注入；guest 侧求值的 $ 已转义。
cat > "${ROOTFS}/tmp/setup.sh" <<SETUP
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive HOME=/root
cd /
# 国内构建提速可按需解开（不影响产物内容寻址）：
# sed -i 's|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/ubuntu.sources
apt-get update -qq
apt-get install -y -qq --no-install-recommends ${PKGS[*]}
locale-gen C.UTF-8 >/dev/null 2>&1 || true

# opencode 官方 linux-arm64（glibc）单文件二进制：直链 release 资产锁定版本。
# 升级 OC_VERSION 前先核对资产名仍在: ${OPENCODE_RELEASES_PAGE}
curl -fsSL --retry 5 --retry-delay 3 -o /tmp/opencode.tar.gz "${OPENCODE_RELEASE_URL}"
mkdir -p /tmp/oc-unpack
tar xzf /tmp/opencode.tar.gz -C /tmp/oc-unpack
OC_BIN="\$(find /tmp/oc-unpack -type f -name opencode -print -quit)"
[[ -n "\${OC_BIN}" ]] || { echo "FATAL: 资产 ${OPENCODE_ASSET} 内未找到 opencode 二进制（升级版本时资产名可能已变更）" >&2; exit 1; }
install -m 755 "\${OC_BIN}" /usr/local/bin/opencode
rm -rf /tmp/oc-unpack /tmp/opencode.tar.gz

# 冒烟（P1 §5.1）：--version 失败、或实际版本号与锁定值不符，快照即作废（非 0 退出）
OC_OUT="\$(/usr/local/bin/opencode --version 2>&1)"
echo "opencode --version => \${OC_OUT}"
case "\${OC_OUT}" in
  *"${OC_VERSION}"*) : ;;
  *) echo "FATAL: opencode 版本不匹配：期望 ${OC_VERSION}，实际：\${OC_OUT}" >&2; exit 1 ;;
esac

apt-get clean
rm -rf /var/lib/apt/lists/* /var/tmp/*
SETUP
chmod 755 "${ROOTFS}/tmp/setup.sh"
chroot "${ROOTFS}" /bin/bash /tmp/setup.sh
# 清空 guest /tmp（setup.sh 与下载残留不进包；注意不能在 setup.sh 尾部自删，bash 流式读脚本会断）
find "${ROOTFS}/tmp" -mindepth 1 -delete 2>/dev/null || true

# ───────────────────────── [4/6] 预置配置模板 ─────────────────────────
echo "==> [4/6] 预置 opencode 配置模板"
# P1 §3.5「配置唯一写入者原则」：此模板仅为离线参考，运行期被 App 的 oc-auth bind
# 遮蔽，不生效；保留无害，snapshot-builder 不改。
mkdir -p "${ROOTFS}/root/.config/opencode"
cat > "${ROOTFS}/root/.config/opencode/opencode.json" <<'EOF'
{
  "theme": "system",
  "autoupdate": false,
  "share": "disabled"
}
EOF
# 注：./workspace 与 ./root/.local/share/opencode 不预置目录 —— 两者均在 EXCLUDE_TAR
# 中被剔除，运行期分别由宿主 bind（proot 虚拟层自动建）与 opencode 首启自建。

# ───────────────────────── [5/6] 卸载 bind 并打包（参数冻结） ─────────────────────────
echo "==> [5/6] 卸载 bind 并打包（tar 参数冻结：P1 §5.1）"
# 必须先卸载再打包：bind 进来的 /proc /sys /dev 内容一旦进入 tar 即污染快照
umount -l "${ROOTFS}/proc" "${ROOTFS}/sys" "${ROOTFS}/dev" 2>/dev/null || true

mkdir -p "${OUT_DIR}"
# --anchored：exclude 模式锚定根级（必须置于 --exclude 之前），使 EXCLUDE_TAR 精确
# 匹配 tar 根下的两条路径及其子树，不误伤深层同名路径；与 manifest.excludes 语义对齐
TAR_EXCLUDES=(--anchored)
for p in "${EXCLUDE_TAR[@]}"; do TAR_EXCLUDES+=(--exclude="$p"); done
# 可复现三参数（P1 §5.1 冻结，实测 GNU tar 1.35 通过，见 P2a §4）：
#   --numeric-owner   uid/gid 以数字入档，不依赖构建机账户映射
#   --sort=name       entry 按名排序，消除文件系统遍历顺序不确定性
#   --mtime=...       全部 entry 时间戳冻结为常量，消除构建时刻泄漏
#                     （tar -czf 经管道喂 gzip，gzip 头 mtime 字段恒为 0，已实测）
tar -C "${ROOTFS}" -czf "${OUT_DIR}/${SNAP_NAME}" \
    --numeric-owner --sort=name --mtime='UTC 2026-01-01' \
    "${TAR_EXCLUDES[@]}" .

# ───────────────────────── [6/6] 体积门禁 + manifest.json（C1 schema） ─────────────────────────
echo "==> [6/6] 体积门禁 + manifest.json"
SHA256="$(sha256sum "${OUT_DIR}/${SNAP_NAME}" | cut -d' ' -f1)"
[[ "${SHA256}" =~ ^[0-9a-f]{64}$ ]] || die "sha256 异常（应为 64 位小写 hex）：${SHA256}"
SIZE="$(stat -c%s "${OUT_DIR}/${SNAP_NAME}")"

# P1 D3 硬门禁：> 160MB（167772160 字节，160MiB 口径）直接 fail，禁止静默放行
if (( SIZE > MAX_SNAPSHOT_BYTES )); then
  {
    echo "FATAL: 快照超体积门禁：${SIZE} 字节 > ${MAX_SNAPSHOT_BYTES} 字节（160MB ≡ 160MiB 口径）"
    echo "  P1 D3 对策：先裁工具链 —— 裁剪优先级见 team-output/P2a-快照构建规格.md §1.3"
    echo "  裁剪后如仍超限，上报主理人重议预算，不得放行超限快照进入发布流程"
  } >&2
  exit 1
fi

# excludes 以 EXCLUDE_TAR 数组为唯一事实来源构建 JSON 数组 —— tar 排除与
# manifest.excludes 由同一变量驱动，结构上杜绝两端漂移
EXCLUDES_JSON="$(printf '%s\n' "${EXCLUDE_TAR[@]}" | jq -R . | jq -s .)"

jq -n \
  --argjson schemaVersion  "${SCHEMA_VERSION}" \
  --arg    snapshotVersion "${SNAPSHOT_VERSION}" \
  --arg    ocVersion       "${OC_VERSION}" \
  --arg    ubuntuBase      "${UBUNTU_BASE_VER}" \
  --arg    file            "${SNAP_NAME}" \
  --arg    sha256          "${SHA256}" \
  --argjson size           "${SIZE}" \
  --argjson excludes       "${EXCLUDES_JSON}" \
  --arg    minAppVersion   "${MIN_APP_VERSION}" \
  '{
    schemaVersion:   $schemaVersion,
    snapshotVersion: $snapshotVersion,
    ocVersion:       $ocVersion,
    ubuntuBase:      $ubuntuBase,
    file:            $file,
    sha256:          $sha256,
    size:            $size,
    format:          "tar.gz",
    layout:          "rootfs-at-tar-root",
    excludes:        $excludes,
    minAppVersion:   $minAppVersion
  }' > "${OUT_DIR}/manifest.json"

# manifest 自检：C1 必需字段与取值规则全量断言，不过即作废（防 jq 参数拼装回归）
jq -e '
  .schemaVersion == 1
  and (.snapshotVersion | test("^[0-9]{8}-oc"))
  and (.ocVersion   | length > 0)
  and (.ubuntuBase  | length > 0)
  and (.file == "oc-ubuntu-arm64.tar.gz")
  and (.sha256 | test("^[0-9a-f]{64}$"))
  and (.size | type == "number" and . > 0)
  and (.format == "tar.gz")
  and (.layout == "rootfs-at-tar-root")
  and (.excludes == ["./workspace", "./root/.local/share/opencode"])
  and (.minAppVersion == "1.0.0")
' "${OUT_DIR}/manifest.json" >/dev/null \
  || die "manifest.json 未通过 C1 schema 自检（P1 §3.1）"

echo "==> 完成: ${OUT_DIR}/${SNAP_NAME}"
echo "    sha256 = ${SHA256}"
echo "    size   = ${SIZE} 字节（门禁 ${MAX_SNAPSHOT_BYTES}，余量 $((MAX_SNAPSHOT_BYTES - SIZE)) 字节）"
ls -lh "${OUT_DIR}"
cat "${OUT_DIR}/manifest.json"