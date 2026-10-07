#!/usr/bin/env bash
# =============================================================================
# native/check-page-align.sh — 16KB page 对齐校验（P1 §3.3 C3 第 1 项）
#
# 契约：所有 LOAD 段 Align >= 0x4000 (16384)，任一不达标即 exit 1 并列出不合规段。
# D7 背景：2025-11 起 Google Play 强制 16KB page size 支持；4KB 对齐 ELF 在
#          16KB 内核上加载失败。构建侧 -Wl,-z,max-page-size=16384 决定该值。
#
# P1 §7 缺陷 3 修复说明：release.yml 原校验 `grep -E 'LOAD' | grep -q '0x4000'`
# 存在三重脆弱性——① 匹配整行任意位置，Align 列混在行尾无列定位；
# ② "Section to Segment mapping" 区域同样含 LOAD 字样会被误判；
# ③ 只要任一行含 0x4000 即通过，其余 LOAD 段不达标也放行。
# 本脚本改为：llvm-readelf -lW → 截取 Program Headers 区 → 逐条 LOAD 行
# 取末字段（Align 列）→ 逐段数值比较，全部达标才通过。
#
# 用法：
#   bash native/check-page-align.sh <file1.so> [file2.so ...]
#   READELF=/path/to/llvm-readelf bash native/check-page-align.sh app/src/main/jniLibs/arm64-v8a/*.so
# 选项：
#   -m, --min-align <HEX|DEC>   最小对齐值（默认 0x4000）
#   -r, --readelf <PATH>        指定 readelf 可执行文件（默认自动定位 NDK llvm-readelf）
#       --no-name-check         跳过 jniLibs 命名断言（C3 第 2 项：lib*.so）
#   -h, --help
# =============================================================================
set -euo pipefail

MIN_ALIGN="0x4000"
READELF="${READELF:-}"
NAME_CHECK=1

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    -m|--min-align) MIN_ALIGN="$2"; shift 2 ;;
    -r|--readelf)   READELF="$2";   shift 2 ;;
    --no-name-check) NAME_CHECK=0;  shift ;;
    -h|--help)      usage ;;
    -*) echo "未知选项: $1" >&2; usage >&2; exit 2 ;;
    *)  break ;;
  esac
done
[[ $# -ge 1 ]] || { echo "用法: check-page-align.sh [-m 0x4000] [-r <readelf>] <file.so> ..." >&2; exit 2; }

die() { printf '\033[1;31m[check-page-align][FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

# ───────────────────────────── readelf 自动定位 ──────────────────────────────
if [[ -z "$READELF" ]]; then
  for c in "${NDK_ROOT:-}" "${ANDROID_NDK_HOME:-}"; do
    [[ -n "$c" ]] || continue
    cand="$(find "$c/toolchains/llvm/prebuilt" -name llvm-readelf -type f 2>/dev/null | head -n 1 || true)"
    if [[ -n "$cand" ]]; then READELF="$cand"; break; fi
  done
fi
if [[ -z "$READELF" ]]; then
  READELF="$(command -v llvm-readelf || command -v readelf || true)"
fi
[[ -n "$READELF" ]] || die "未定位到 readelf/llvm-readelf；请用 -r 指定或导出 NDK_ROOT"
[[ -x "$READELF" || -n "$(command -v "$READELF")" ]] || die "readelf 不可执行: $READELF"

# 最小对齐值解析：接受 0x… / 十进制 / 裸十六进制
if [[ "$MIN_ALIGN" == 0x* || "$MIN_ALIGN" == 0X* ]]; then
  MIN_VAL=$((MIN_ALIGN))
else
  MIN_VAL=$((16#$MIN_ALIGN))
fi
[[ $MIN_VAL -gt 0 ]] || die "非法 --min-align: $MIN_ALIGN"

# ───────────────────────────── 逐文件校验 ────────────────────────────────────
FAIL_TOTAL=0
for f in "$@"; do
  [[ -f "$f" ]] || die "文件不存在: $f"
  fail=0

  echo "==> 校验 $f (min align: $(printf '0x%X' "$MIN_VAL") = $MIN_VAL)"

  # C3 第 2 项：jniLibs 打包只认 lib*.so 命名
  if [[ "$NAME_CHECK" == 1 ]]; then
    base="$(basename -- "$f")"
    if [[ ! "$base" =~ ^lib[A-Za-z0-9._-]+\.so$ ]]; then
      echo "  [FAIL] 文件名不符合 jniLibs 约束 'lib*.so': $base"
      fail=1
    fi
  fi

  # 精确解析：
  #   1) 仅在 "Program Headers:" 与 "Section to Segment mapping:" 之间扫描，
  #      排除后者中同名 "LOAD" 误匹配（原 grep 缺陷 ②）；
  #   2) 行首字段必须严格等于 LOAD（$1=="LOAD"，非任意位置子串）；
  #   3) Align 取行末字段（$NF，即 Program Headers 表最后一列），
  #      每条 LOAD 段单独判定，逐段全部达标（修复原缺陷 ①③）。
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    offset="${line%% *}" 
    align_raw="${line##* }"
    if [[ "$align_raw" == 0x* || "$align_raw" == 0X* ]]; then
      align_val=$((align_raw))
    else
      align_val=$((16#$align_raw))
    fi
    if [[ $align_val -lt $MIN_VAL ]]; then
      printf '  [FAIL] LOAD 段 offset=%s Align=%s (%d) < %d (16384)\n' \
             "$offset" "$align_raw" "$align_val" "$MIN_VAL"
      fail=1
    fi
  done < <("$READELF" -lW "$f" 2>/dev/null | awk '
    /^Program Headers:/              { in_ph = 1; next }
    /^ *Section to Segment mapping:/ { in_ph = 0 }
    in_ph && $1 == "LOAD"            { print $2, $NF }
  ')

  load_cnt="$EREADELF_PLACEHOLDER" 2>/dev/null | awk '
    /^Program Headers:/              { in_ph = 1; next }
    /^ *Section to Segment mapping:/ { in_ph = 0 }
    in_ph && $1 == "LOAD"            { n++ } END { print n+0 }
  ')"
  if [[ "$load_cnt" -eq 0 ]]; then
    echo "  [FAIL] 未解析到任何 LOAD 段（文件非 ELF 或 readelf 输出格式不符）"
    fail=1
  fi

  if [[ $fail -eq 0 ]]; then
    echo "  [PASS] ${load_cnt} 个 LOAD 段全部 Align >= $(printf '0x%X' "$MIN_VAL")"
  else
    FAIL_TOTAL=$((FAIL_TOTAL + 1))
  fi
done

if [[ $FAIL_TOTAL -gt 0 ]]; then
  die "$FAIL_TOTAL 个文件未通过 16KB 对齐校验（C3 硬性门禁，禁止放行）"
fi
echo "全部通过：16KB 对齐校验 OK（$# 个文件）"
