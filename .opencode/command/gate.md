---
description: 只跑门禁体检——核对四道 CI gate、编译断言与契约一致性，不改代码
agent: build
---

执行只读门禁体检，判断「当前代码是否真能过门禁、能发布」。

## 步骤

1. **先验证能否编译**（最高优先级）
   - `./gradlew :app:compileFullDebugKotlin :app:compileLiteDebugKotlin`
   - 若环境缺 SDK 组件，明确报告「未能编译验证」及缺失项，不要含糊

2. 核对 `.github/workflows/release.yml` 各 step 有效性：
   - Gate 1/1b（16KB 对齐）、Gate 2（体积）、Gate 3（manifest）、Gate 4（extractNativeLibs）
   - **Compile assertion**（编译断言）与 **Check gradle wrapper committed**（wrapper 硬断言）
   - 逐 step 检查有没有被 `continue-on-error` 或宽松 `if` 架空

3. 核对 shell 退出码传递：所有 `| tee` 是否都配了 `set -o pipefail` 或 `${PIPESTATUS[0]}`

4. 核对 `OC_VERSION` 双处一致性（workflow env vs snapshot 脚本）

5. 核对发布路径硬阻断：tag 缺签名 Secrets 是否硬失败；debug 签名是否可能漏到 Release

6. 核对 `native/build-qemu-user-static.sh` 四个第三方 SHA256 非空

7. 对照 `.opencode/KNOWN_BUGS.md`，标注哪些已知缺陷会逃过现有门禁

## 输出
```markdown
# 门禁体检
## 编译状态
## 各 step 有效性（含证据 文件:行号）
## 逃过门禁的已知缺陷
## 结论
```

## 纪律
只读，不改任何文件。所有判定必须有 `文件:行号` 证据。
