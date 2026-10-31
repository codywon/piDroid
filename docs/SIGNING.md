# 发布签名与密钥管理

## 背景

仓库历史上把 `keystore/release.jks` 和它的密码（明文写在 `app/build.gradle.kts`
与 CI workflow 里）一起提交了。任何人只要拿到仓库读权限，就能签出一个 Android
认作「piDroid 官方升级」的包。配合应用内自动更新（下载即安装），这是一条完整的
供应链攻击路径。

**该密钥已作废。** 它已从 git 跟踪中移除，密码不再出现在任何源码或 CI 配置里。

> 由于换用新密钥意味着签名证书变更，**旧版本用户无法覆盖升级**，必须卸载后重装
> 一次。这是为消除该风险主动接受的代价。

## 一次性操作：生成新的发布密钥

在**离线、可信**的机器上执行（不要放在仓库目录内执行完之后再提交）：

```bash
keytool -genkeypair \
  -keystore release.jks \
  -alias pidroid \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$(openssl rand -base64 24)" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=piDroid, OU=Mobile, O=piDroid, L=NA, S=NA, C=CN"
```

要点：

- `-validity` 给足（10000 天 ≈ 27 年）。密钥过期后无法再发布可覆盖升级的包。
- 4096 位 RSA；不要用默认 2048。
- 密码用密码管理器保存，**不要**写进任何会被提交的文件。

## 本地构建发布包

```bash
export KEYSTORE_PASSWORD='…'
export KEY_ALIAS='pidroid'
export KEY_PASSWORD='…'
mkdir -p keystore
cp /path/to/release.jks keystore/release.jks

./gradlew assembleRelease
```

`keystore/` 已在 `.gitignore` 中。缺少 keystore 或任一环境变量时，
构建会在 `validateSigningRelease` 阶段直接失败，不会静默产出未签名 APK。

## 备份

`release.jks` 丢失且无备份 ⇒ **永久失去发布覆盖升级的能力**，只能换包名重新上架。
请至少保留两份离线备份（例如加密 U 盘 + 密码管理器附件）。

## CI 配置

在 GitHub 仓库 `Settings → Secrets and variables → Actions` 添加：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks \| pbcopy` 的结果 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | `pidroid` |
| `KEY_PASSWORD` | key 密码 |

workflow 会把它解码到 `keystore/release.jks` 再构建；任一 secret 缺失都会让
构建在 `Decode Release Keystore` 或 `Build Release APK` 步骤直接失败。

## 应用内的双重校验

发布链路的下游还有两道闸门（见 `app/src/main/java/com/pi/app/updater/AppUpdateManager.kt`）：

1. **SHA-256**：CI 随 Release 发布 `checksums.txt`（`sha256sum` 格式），
   应用优先用 GitHub asset 的 `digest` 字段，否则拉取该清单比对。
2. **签名校验**：安装前用 `PackageManager` 读待安装包的签名证书，
   与当前已安装应用不一致就拒绝安装。

两者同时通过才会把包交给系统安装器。

---

## 密钥轮换记录（2026-11-01）

| 项 | 内容 |
| --- | --- |
| 触发原因 | 旧 release keystore（私钥）与其密码曾进入**公开**仓库历史；审查确认它与 1.7.x 正式包签名一致，视为泄露 |
| 处置 | 生成新 PKCS12（RSA 4096 / 10000 天 / alias `pidroid`），更新 4 个 CI Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`），旧密钥永久作废 |
| 新证书 SHA-256 | `88:9F:3E:92:79:80:11:E6:E5:47:E4:19:C0:C2:B4:F6:E9:D1:56:96:FC:3B:B5:57:76:A6:33:ED:E7:FD:AB:61` |
| 旧证书 SHA-256（已拉黑） | `07:B4:1E:74:8B:B9:85:4A:13:F5:33:38:BC:57:5D:AF:22:A8:02:72:79:44:15:8D:E2:6B:BD:9C:86:0A:70:C7` |
| 用户影响 | 1.7.x 无法覆盖升级到 1.8.0：必须卸载重装，并重新扫码配对（本地数据会清空） |
| 备份要求 | 新 keystore 与密码离线保存两份（密码管理器 + 加密介质）；**不得**入库、不得写入 workflow/gradle |

> `build-and-release.yml` 会在解码 keystore 后核对证书指纹：命中上表旧指纹即中断构建。
> 历史改写（`git filter-repo`）只清掉了可克隆的历史，**不能**让已经泄露过的私钥变安全——
> 真正起作用的是这次轮换。
