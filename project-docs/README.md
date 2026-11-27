# Velora 项目文档

> 本目录是 **Velora 项目的长期知识库（当前基线 v2.9.4 / 36）**，面向后续开发者、模型和 Harness。
> 最后核对时间：**2026-11-27**（v2.9.4 / 36 基线 + 零百整合/辅助 + 开源加固工作区；详见 `PRODUCT_REQUIREMENTS.md` §2.10 与实施台账 §19/§21/§22）。
> 依据：当前工作区代码 + `git log/status` + `docs/VELORA_MAP_SPEC_2.9.2.md` + 2.9.x 专项记录。
> 原则：**代码是实现的最终依据；开发日志和旧文档只作线索。改过代码 ≠ 已验证；工作区有代码 ≠ 已进入主线。**

---

## 0. UI 演示与截图

- **交互式 UI 演示**：[`../docs/index.html`](../docs/index.html)。单文件、零依赖；桌面左右分栏，手机上下自适应；场景含正常巡航、800m 电子眼、超速预警、区间测速、多方向红绿灯。
- **GitHub Pages**：仓库 Settings → Pages → Source 选择 `main` 分支的 `/docs` 目录，访问 `https://<user>.github.io/<repo>/`。
- **真机截图占位**：`../docs/images/cruise.png`、`camera.png`、`traffic-light.png`、`interval.png`；说明见 `../docs/images/README.md`。
- 演示页与截图不得包含真实车牌、住址、账号或密钥。

## 1. 项目是什么

Velora（包名 `com.zouhu.speedlimit`）是一个独立 Android GPS 限速巡航/测速 App，核心是：

- 当前道路限速；
- 前方测速电子眼、区间测速；
- 前方路口红绿灯（多方向灯态 + 倒计时）；
- 车道提示（巡航车道受百度车信授权限制；导航态有 SDK drawable 车道）；
- 地图与导航（百度地图/导航 SDK，非自研地图）。

产品视觉强调克制、无框无阴影、深/浅跟随系统；主页/地图/导航的具体行为见 `PRODUCT_REQUIREMENTS.md`。

## 2. 当前版本与状态（重要）

| 项 | 值 |
|---|---|
| 当前版本 | **2.9.4 / versionCode 36**（**v2.9.4 已封版**，2026-11-22；2.10.0 跳过） |
| 平台配置 | `compileSdk 36`、`targetSdk 34`；`pageSizeCompat="enabled"` 兼容百度 4 KB 导航库；非调试包已验证不弹框，调试包仍有开发者提示 |
| 当前分支 | `main` |
| 基线 tag | **`v2.9.4`**（当前稳定基线）；历史基线 `v2.9.2`、`v2.9.3` 保留 |
| 2.9.2 代码基线 commit | **`bbdc2a6`** |
| 当前 HEAD | **`16797e8`**（2.9.4 代码；最终文档提交后 tag `v2.9.4` 指向收官 commit） |
| 当前开发批次 | **B5 完成；v2.9.4 / 36 为当前阶段最终稳定基线，开发暂告一段落** |
| 2.9.4 代码状态 | **已封版**；当前工作区另有未提交的开源加固（§2.4/§19），后续仅凭实车道路日志重启道路项 |
| 旧版本 | 产物已删除；旧分支/tag 已清理；仅保留 2.9.2 APK + 2.10.0 实验源码归档（git 历史未重写）；**2.10.0 不再作为后续版本号** |
| 工作区 | 含开源脱敏/双 AK/降级改动，未提交；旧文档已归档到 `docs/legacy/2026-11-19/`；无 remote |
| `dist` APK | `dist/Velora-preview-debug.apk`，**2.9.4/36 封版**；SHA-256 `e0b34d1e72998a4dbeb6b201199bd514026d5e3c942d423935991de55d165ee6` |
| 当前可安装包 | `dist/Velora-preview-debug.apk`（2.9.4/36 封版）；`<workspace>/Velora-2.9.4-debug.apk` 为同源候选 |
| 当前单测 | **198 / 0 失败 / 2 live skipped**（设置收口/辅助/UI 减法后）；instrumentation 4/4 为 2.9.4 历史记录；`lintDebug` 0 errors |
| 地图验收记录 | `MAP_RESTORE_ACCEPTANCE.md`：MAP-02/05 已验证；MAP-01/04 已按 SDK 边界关闭；MAP-03/06 保留历史残余 |

> 读文档时先记住：**当前稳定基线是 v2.9.4 / 36（tag `v2.9.4`）。** 2.9.2/2.9.3 是历史基线；任何“已提交/已封版”的说法以 git tag/commit 为准。

### 2.1 2026-11-19 真机结论（2.9.2 历史）

用户已给出一轮 2.9.2 真机结论，据此对 **2.9.2 导航显示项** 进入 **“冻结修补、只做报告”**（不代表整个项目停止开发）：

| 项 | 结果 |
|---|---|
| 退出导航残留 | ❌ 仍存在 |
| 导航默认比例尺 | ❌ 仍太大、无法显示车道级 |
| 导航红绿灯 | ❌ 仍不行；地图无原生红绿灯（用户接受） |
| 只显主路线 | ❌ 仍显示备选路线 |
| 导航 HUD 尺寸 | ✅ 基本合适 |
| 跨城搜索 | ✅ 可以 |
| 搜索排序/精准度 | ❌ 附近结果被远城/泛相关结果压后 |
| 连续第二次导航 | ❌ 退出导航但不退 App，再次导航极卡；重启 App 恢复 |
| 车信车道 | ⛔ 商业收费，用户不再讨论/不采购 |
| 个性化地图 | ⏸ 未排期，用户认可当前样式 |

> 上表是 2.9.2 阶段结论；2.9.3/2.9.4 的地图/主路线状态见 §2.2。
>
> 用户表示导航“有点想放弃”；这不是正式删除导航的决议。**冻结范围**只覆盖上表的 2.9.2 导航显示/搜索排序问题；post-2.9.2 的 VA 审查修复（B1 起）已由用户另行授权并持续推进。详见 `BUG_TRACKER.md` §0 / `HANDOFF.md` §4。

### 2.2 2.9.3/2.9.4 已知 SDK 边界

- **退出导航后的底图 POI / 单指拖动不恢复**：`BaiduMapSDK_Map-Navi:9.0.2` 在 `ADD_MAP=false` 共享引擎下，`setNaviMapMode(NORMAL)`、`handleMapThemeAndScene(1)`、`resetMapOverlays()`、`setMapPoiScene(true)` 均返回成功，但 POI 与手势仍不恢复。日志见 `nav-diag-20261122-145202.txt`；按总报告 §12.2 **不重建 TextureMapView**，接受为 SDK 边界。
- **导航中只显示主路线**：`BNRouteGuider.setIsOnlyDisplayMainRoute` 的 Java 方法存在，但 `carnavi:9.0.2` 的 .so 未实现 JNI，调用抛 `UnsatisfiedLinkError`；已移除调用，接受现状。
- **MAP-02/MAP-05 已验证**：退出后绿边/路线/“大路多、走文宗路”气泡消失；退出后立即二次导航正常。
- 16 KB：`libapp_BaiduNaviApplib.so` 仍 4 KB；非调试包 `pageSizeCompat` 已验证不弹框，调试包仍显示开发者提示。

### 2.3 2.9.4 封版（2026-11-22）

- F10：guide `onStart/onResume` 前要求宿主 `RESUMED`；非 RESUMED/布局超时走 abort，不再推进 SDK 生命周期。
- F01：320ms 启动窗口到点再检查 `showSearch/live`，拒绝时永久失效旧 token。
- F02：`NavModePolicy` 保证真实导航与演示互斥；真实激活自动清 demo。
- G03：Horizon `loc.ts` 保留真实采样时间；超 60s/未来 5s 拒绝，envelope `ts` 仍为发送时间。
- 以上均有 JVM 单测；装机冒烟通过（主页定位/限速、演示、真实导航启停正常）。极窄后台/320ms/1.2s 与真实道路 `loc.ts` 留日常顺路观察，不阻塞封版。
- **2026-11-22 起进入稳定基线维护**：不主动改代码；只有拿到真实道路日志后才重启 C01/G01/L01/G03 等道路项。

### 2.4 开源加固（2026-11-26，工作区）

- 全仓/历史扫描发现旧文档含真实百度 AK/SK 与 MiMo Key；当前工作区已脱敏，**Git 历史尚未重写**，等用户确认后清理。
- 新增双 AK 配置：Android 应用端 AK（map/navi/search）与服务端 AK/SK（Horizon/HTTP/lamp）分离；设置页可加密保存并脱敏显示。
- 服务端 AK 为空时不创建 Horizon/HTTP/lamp 请求；鉴权失败/服务到期进入长退避并在设置页温和提示，不崩溃、不伪造限速或红绿灯。
- 地图 AK 使用 `SDKInitializer.setApiKey` 在初始化前注入，保存后需重启 App；`local.properties` 仍是编译期回退。
- 详单见 `POST_2.9.2_IMPLEMENTATION_STATUS.md` §19；`VELORA_TECHNICAL_AUDIT_MASTER_REPORT_2.9.2.md` 保持只读未修改。

## 3. 技术栈

- Kotlin 2.2.20；Jetpack Compose（BOM 2024.06.00）；Material3。
- Android：`compileSdk 36`、`minSdk 26`、`targetSdk 34`；`arm64-v8a` 单 ABI。
- 地图/导航：`com.baidu.lbsyun:BaiduMapSDK_Map-Navi:9.0.2`（自带地图 SDK，**不要**再单独加 `BaiduMapSDK_Map`）。
- 搜索：`com.baidu.lbsyun:BaiduMapSDK_Search:9.0.0`。
- 导航崩溃修复必需：`io.reactivex:rxjava:1.3.8`（见 DECISIONS/BUG）。
- 网络：OkHttp 4.12；JSON：kotlinx.serialization；设置：DataStore Preferences + EncryptedSharedPreferences（AK/SK/TTS）。
- 音频：`MediaPlayer` + 小米 MiMo TTS（直连，不走网关）。
- 构建：Gradle wrapper；`build.ps1` / `test.ps1`（PowerShell，内部调用 `gradlew.bat`）。
- 后端：`backend/` 仅为历史资产，**当前 App 直连百度，不走网关**。

## 4. 开发环境

- Windows + PowerShell（脚本为 `.ps1`）；JDK 17。
- Android SDK（compileSdk 36 / build-tools 36）。
- 一台 `arm64-v8a` Android 真机（当前测试机：三星 SM-S9210 / Android 16 / sdk36）。
- 不需要 Android Studio 才能构建；有 Gradle wrapper 即可。

### 4.1 本地密钥与开源配置

`local.properties`（**gitignored，禁止提交/分发**）读取；模板见 `local.properties.example`：

```properties
baidu.map.ak=...      # Android 应用端 AK：地图/导航/检索，绑定包名 + SHA1
horizon.ak=...        # 服务端 AK：超视距 WebSocket + HTTP 巡航 + 红绿灯
horizon.sk=...        # 可选服务端 SK；当前百度接口以 AK 鉴权
mimo.api.key=...      # 小米 MiMo TTS
```

首次开发可复制模板：`Copy-Item local.properties.example local.properties`，再填入自己的值。

- `app/build.gradle` 将 `baidu.map.ak` 注入 Manifest 占位符和 `BuildConfig.BAIDU_MAP_AK`；
  `horizon.ak` 注入 `BuildConfig.HORIZON_AK`；`horizon.sk` 注入 `BuildConfig.HORIZON_SK`；
  `mimo.api.key` 注入 `BuildConfig.MIMO_API_KEY`。
- **Android 应用端 AK**：`BaiduMapSDK_Map-Navi:9.0.2` 的 `SDKInitializer.setApiKey(String)` 已核实存在；
  设置页保存的本机值会在地图 SDK 初始化前注入，**修改后需重启 App**。未填写时回退 `BuildConfig` / Manifest。
  如果百度子 SDK 仍只读取 Manifest，按上面的 `baidu.map.ak` 编译期配置。
- **申请方式**：Android 应用端 AK 需在百度控制台创建 Android 应用，填包名 `com.zouhu.speedlimit` 与本机签名 SHA-1，
  启用地图/导航/检索；服务端 AK 需创建服务端应用，启用 HTTP 巡航 API；`超视距巡航（WebSocket）` 与
  `红绿灯服务` 属于高级服务，**必须单独提交百度工单/权限申请**，普通地图 AK 不能替代。SK 仅作可选签名备用；
  MiMo Key 在小米 MiMo 控制台申请。
- **服务端 AK/SK、MiMo Key**：设置 → 数据服务中可输入、修改、重置；值保存在 `EncryptedSharedPreferences`
  （Android Keystore 主密钥），列表脱敏显示，不写入明文日志。服务端 AK 保存后立即重建 Provider 并重连；
  MiMo Key 在下一次播报生效；地图 AK 重启后生效。
- **两套 AK 不通用**：地图 AK 不能代替服务端 AK。超视距/红绿灯属于百度高级服务，需要控制台工单单独开通；
  未开通或到期时，巡航限速/红绿灯/电子眼数据会按“无有效数据”隐藏，不崩溃、不伪造。
- 地图 AK 为空且没有本机地图 AK → `BaiduMapSdk.available=false` → 走静态图/占位降级（`MapBackdrop` / `MapImageCache`）。
- 2.9.2 开发日志里写“`gradle.properties` 的 baidu.map.ak”，**与实际代码不符**：实际读取 `local.properties`（见 OPEN_QUESTIONS）。
- 开源构建默认不包含任何真实密钥；`dist/`、`*.apk`、`*.jks`、`*.keystore`、日志文件均已加入 `.gitignore`。

## 5. 构建与运行

在 `<workspace>` 下：

```powershell
# 单测
.\test.ps1

# 构建 debug APK
.\build.ps1
```

- `build.ps1` → `app/build/outputs/apk/debug/app-debug.apk`，并复制到 `dist/Velora-preview-debug.apk`。
- 当前构建：`BUILD SUCCESSFUL`，**198 tests / 0 failures / 2 live skipped**（2026-11-27 设置收口/辅助/UI 减法后）。
- `dist/` 已替换为 **2.9.4(36) 封版**；`build.ps1` 会重新生成并覆盖同名 APK，发版后勿再覆盖旧包。
  开源开发只想验证编译时可用 `gradlew.bat assembleDebug`，避免覆盖封版 APK。
- 安装：`adb install -r dist/Velora-preview-debug.apk`（本机有 adb 时）；路测可不连电脑。
- 包名固定 `com.zouhu.speedlimit`；不要改 `applicationId` 或签名，否则百度 AK 失效。
- 当前分支没有 remote；仓库只有本机副本。开源上传前请先完成敏感历史清理（见 `POST_2.9.2_IMPLEMENTATION_STATUS.md` §19）。

## 6. 项目入口与目录

- 应用入口：`app/src/main/java/com/zouhu/speedlimit/ui/MainActivity.kt`
  - 初始化百度地图 SDK、`NavTrace`、定位权限、前台巡航服务；
  - `setContent { SpeedLimitMeterTheme { CruiseScreen(viewModel) } }`。
- 主界面：`ui/screen/CruiseScreen.kt`（主页 ↔ 展开地图、搜索、设置、导航 HUD 宿主）。
- 地图：`ui/screen/MapScreen.kt`（`MapLayer` / `LiveBaiduMap` / `MapChrome`）。
- 导航：`data/nav/BaiduNavSource.kt` + `ui/nav/NavHud.kt` + `ui/nav/NavSearchScreen.kt`。
- 数据：`provider/baidu/*`、`data/location/*`、`domain/*`。
- 关键文档：`docs/VELORA_MAP_SPEC_2.9.2.md`（本版地图开发日志，**与当前代码冲突处以代码为准**）。
- 备份：旧 `backups/` 已清理；2.9.2 以 git 基线 `v2.9.2` 为准，当前 APK 在 `dist/Velora-preview-debug.apk`。
- 源码快照：`<workspace>/archive/Velora-2.9.2-source.zip`（来自 tag `v2.9.2`，不含密钥）。

## 7. 本目录文档职责

| 文件 | 只负责 |
|---|---|
| `README.md` | 项目简介、技术栈、环境、构建运行、目录、文档索引 |
| `PRODUCT_REQUIREMENTS.md` | 产品定位、核心功能、预期行为、当前验收状态 |
| `ARCHITECTURE.md` | 真实架构、模块、依赖、数据流、关键实现 |
| `DECISIONS_AND_BOUNDARIES.md` | 已明确的技术/产品决策、边界、禁止事项 |
| `BUG_TRACKER.md` | 已知 Bug/限制、复现、影响、修复与验证状态 |
| `OPEN_QUESTIONS.md` | 未确定/待验证/待决策的问题 |
| `DEVELOPMENT_HISTORY.md` | 版本变化、架构演进、失败尝试、经验 |
| `HANDOFF.md` | 当前进度、进行中工作、未完成任务、交接顺序 |

## 8. 证据等级（全文统一）

- **【代码确认】**：当前工作区源码直接可见，未依赖运行。
- **【单测确认】**：`testDebugUnitTest` 覆盖；当前 **198 tests / 0 failures / 2 live skipped**（2.9.4 封版时为 166）。
- **【真机确认】**：有用户/真机反馈或日志；文档会注明版本/日期。
- **【未验证】**：代码存在，但没有真机/独立运行证据。
- **【阻塞】**：依赖百度授权/工单/外部条件。
- **【历史】**：旧版本/旧方案，不代表当前决策。

## 9. 重要提醒

1. **不要把 2.9.2 开发日志当成“全部已实现”**：本目录已逐项对照代码；至少有两处日志与当前代码不一致（导航地图红绿灯开关、备选路线开关，见 `BUG_TRACKER.md` / `OPEN_QUESTIONS.md`）。
2. **不要把“代码能编译”当成“功能已验证”**：2.9.2 的多项导航修复没有真机复验。
3. **不要改包名/签名**：百度 AK 绑定 `com.zouhu.speedlimit` + SHA1。
4. **不要把 APK 外发**：BuildConfig 内含 HORIZON_AK / MiMo key。
5. **根目录旧文档**（`HANDOFF.md`、`BUG_TRACKER.md` 等）是 2.3.9 前后的历史快照；本目录是 2.9.2 的当前知识库。
6. **开源上传前先清理 Git 历史并轮换密钥**：工作区脱敏不等于历史脱敏；旧 commit 仍可能检出真实 AK/SK。详见 `POST_2.9.2_IMPLEMENTATION_STATUS.md` §19。
