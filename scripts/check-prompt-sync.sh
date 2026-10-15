#!/usr/bin/env bash
#
# 提示词副本同步检查。
#
#   scripts/check-prompt-sync.sh
#
# 仓库里提示词有**两份**：
#   prompts/**                                ← 可编辑的源
#   kmp/app/androidApp/src/main/assets/**     ← 随 APK 打包的副本（AndroidPromptSource 读取）
#
# 只改其中一处就会出现「改了提示词但运行时不生效」这种极难排查的问题。
#
# 检查是**双向**的：
#   正向：prompts/** 的每个文件必须在打包副本里逐字节一致；
#   反向：打包副本里不能有 prompts/** 没有的文件（孤儿）。
# 反向检查来自一次真实事故：distill 下有 6 个被提示词实际引用的文件
# （output_schema / style_differ / logic_constraint / validation_policy /
#   distill_prompt / relation_prompt）只存在于打包副本里，没有可审阅的源。
#
# 为什么从「三份」变成「两份」（2026 单模块重构）
# ------------------------------------------------
# 原先还有两处副本，随模块合并一并消失，现在只剩打包 assets 一处消费方：
#   * kmp/server/src/androidMain/assets/**  → 内容已迁到 app/androidApp/src/main/assets/**
#     （原先的 :server 模块已并入单模块，assets 源集不再是独立模块属性）
#   * kmp/app/shared/.../composeResources/files/prompts/** → 已删除
#     （不再使用 Compose Resources；唯一消费者 ResPromptSource 是死代码，已删。
#      桌面端不复存在，原本"桌面经 classpath 读 Compose Resources"这条路也不需要了）
#
# 不参与同步的：`prompts/AGENTS.md`、`prompts/README.md`、Python 文件（`*.py`）
# 以及 Python 字节码缓存（`__pycache__/`、`*.pyc`）——后者是本地跑测试的副产物，
# 不该被当成需要同步的提示词。
#
# 退出码：0 = 两份一致；1 = 有缺失或不一致。

set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SRC="${ROOT}/prompts"
DEST_ASSETS="${ROOT}/kmp/app/androidApp/src/main/assets"

if [ ! -d "${SRC}" ]; then
  echo "  ⚠️  找不到 ${SRC}，跳过提示词同步检查"
  exit 0
fi

if [ ! -d "${DEST_ASSETS}" ]; then
  echo "  ❌ 找不到打包副本目录：${DEST_ASSETS#"${ROOT}/"}"
  echo "     提示词随包副本是运行时唯一来源，缺了会导致提示词全部读不到。"
  exit 1
fi

FAIL=0
CHECKED=0

while IFS= read -r file; do
  rel="${file#"${SRC}/"}"

  # 文档、可选资源与 Python 字节码缓存不参与。
  # __pycache__ 必须排除：本地只要 import 过 prompts 就会生成 *.pyc，
  # 早期版本漏了这条排除，导致本地守卫误报「缺少副本」而 CI（全新 checkout）通过 ——
  # 一个只在开发者机器上出现的假失败。
  case "${rel}" in
    AGENTS.md|README.md|*.py|__pycache__/*|*.pyc) continue ;;
  esac

  CHECKED=$((CHECKED + 1))
  target="${DEST_ASSETS}/${rel}"

  if [ ! -f "${target}" ]; then
    printf '  ❌ [assets] 缺少副本：%s\n' "${target#"${ROOT}/"}"
    FAIL=1
  elif ! cmp -s "${file}" "${target}"; then
    printf '  ❌ [assets] 与源不一致：%s\n' "${target#"${ROOT}/"}"
    printf '        源：prompts/%s\n' "${rel}"
    FAIL=1
  fi
done < <(find "${SRC}" -type f | sort)

# 反向：打包副本里不能有 prompts/ 没有的文件（孤儿）。
# 这类文件只能在副本里改、没有可审阅的源。
ORPHANS=0
while IFS= read -r target; do
  rel="${target#"${DEST_ASSETS}/"}"
  case "${rel}" in
    AGENTS.md|README.md|*.py|__pycache__/*|*.pyc) continue ;;
  esac
  if [ ! -f "${SRC}/${rel}" ]; then
    printf '  ❌ [assets] 孤儿文件（prompts/ 里没有对应源）：%s\n' "${target#"${ROOT}/"}"
    printf '        修法：cp 到 prompts/%s，让它成为可审阅的源\n' "${rel}"
    ORPHANS=$((ORPHANS + 1))
    FAIL=1
  fi
done < <(find "${DEST_ASSETS}" -type f | sort)

if [ "${FAIL}" -eq 0 ]; then
  printf '  ✅ %d 个提示词文件在源与打包副本中完全一致，且副本中无孤儿\n' "${CHECKED}"
else
  echo
  echo "     修法：以 prompts/** 为准，把内容同步到打包副本，例如"
  echo "       cp prompts/dialogue/director.yaml \\"
  echo "          kmp/app/androidApp/src/main/assets/dialogue/director.yaml"
fi

exit "${FAIL}"
