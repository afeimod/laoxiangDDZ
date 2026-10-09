# 老乡斗地主 🃏

> 咱村儿的牌桌 —— 一副牌、仨老乡、一块 WiFi，开干！
>
> 原生安卓棋牌合集：**Kotlin + Jetpack Compose**，斗地主完整规则 + 
> **大众麻将 / 癞子麻将 / 四川麻将** 三件套，
> 支持单人（三档 AI）与**本地区域网联机**（自动发现房间 / IP 直连 / AI 补位）。

---

## ✨ 功能一览

### 完整斗地主玩法
- **54 张标准牌**（含大小王），发牌 17×3 + 3 张底牌
- **叫地主 / 抢地主**流程：抢一次倍数翻一番，无人叫自动流局重发
- **全牌型支持**：单牌、对子、三张、三带一、三带二、顺子（≥5）、连对（≥3）、
  飞机（含带单翅 / 带对翅）、四带二单、四带两对、炸弹、王炸
- **倍数体系**：抢地主 ×2/次、每个炸弹/王炸 ×2、春天/反春（闷牌）×2
- **计分**：底分 100 × 倍数，地主赢 +2 倍、两家农民各 −1 倍

### 麻将三件套（v22 新增）

| 玩法 | 牌数 | 核心规则 | 计分 |
|------|------|----------|------|
| **大众麻将** | 136 张（万筒条+风箭） | 带番推倒胡 · 吃碰杠 · 点炮/自摸 · 一炮多响 | 底分×番数：自摸三家各付，点炮放炮者全包 |
| **癞子麻将** | 136 张 | 4 张红中当癞子（不可打出、不可吃碰杠用）· 癞子翻番（×2/张，封顶×8） | 同大众，胡牌总番 ×2^癞子数 |
| **四川麻将** | 108 张（万筒条） | 血战到底 · 定缺+有缺必打 · 换三张 · 只许自摸 · 刮风下雨即付 · 流局查叫查花猪 | 多赢家分别结算：花猪赔 8×/家、大叫赔 4×/家 |

- **胡牌判定**：标准 4 面子 + 1 对 / 七对（含豪华、龙七对）/ 十三幺，癞子可补任意牌
- **番型**：平胡1 · 对对胡4 · 混一色4 · 清一色8 · 七对6 · 豪华七对16 · 小三元8 ·
  大三元16 · 字一色16 · 清么九16 · 十三幺16 · 天胡16 · 地胡8；附加 自摸+1 ·
  杠上开花+2 · 抢杠胡+2 · 海底捞月+2
- **川麻番型（乘制）**：平胡×1 · 对对×2 · 清一色×4 · 七对×4 · 龙七对×8 ·
  金钩钩×4 · 十八罗汉×8 · 天/地胡×16 · 杠上花×2 · 每根×2
- **杠**：明杠（点杠）/ 补杠（可被抢杠胡）/ 暗杠；杠后从牌墙尾部摸牌
- **AI 三档**：简单=散漫练手 · 中等=向听最优 · 困难=向听+进张数双重评估，
  定缺/换三张/吃碰杠胡全决策
- **语音**：每张牌独立女声播报（一万~九万/一筒~九筒/一条~九条/东南西北/红中发财白板），
  吃碰杠胡自摸定缺换三张流局男声，胡牌报番

### 单人模式（三档电脑）
| 难度 | 风格 |
|------|------|
| 简单 | 随机散漫，常放过，练手友好 |
| 中等 | 最小代价压牌、不拆炸弹、粗略配合队友 |
| 困难 | 记牌算牌、压上家防下家、保炸弹时机、残局送终 |

### 局域网联机（同一 WiFi）
- **自动发现**：房主 UDP 广播房间，串门列表实时刷新（2 秒一跳）
- **IP 直连**：房主屏幕显示本机 IP，手动输入即可连接
- **AI 补位**：凑不齐 3 个真人？空位电脑顶上，难度房主可选
- **掉线托管**：中途掉线自动转 AI，牌局不散
- 房主权威模式：所有牌局逻辑跑在房主手机上，防作弊

### 体验细节
- **出牌提示**：循环提示能压过上家的最小牌型（炸弹最后提示）
- **记牌器**：3~2、王外界剩余张数实时统计
- **快捷聊天**：「快点吧我等到花儿都谢了」等经典喊话 + 气泡
- **炸弹特效**：屏幕震动 + 红光闪烁 + 大字炸屏，王炸更猛
- **飞机特效**：飞机起飞提示 + 专属音效
- **胜负结算**：春天 / 反春标识、倍数统计、赢牌金元宝雨
- **横竖屏自适应**：竖屏单手搓，横屏大视野
- **圆桌 / 方桌**：海景圆桌与红木金边方桌随心换
- **全套语音音效**：叫地主、抢地主、不抢、要不起、王炸、飞机、洗牌、胜负结算、
  背景音乐（33 秒循环）全部接入
- 「老乡斗地主」**篆刻水印**烙在每一张背景上

---

## 📱 运行环境

| 项 | 要求 |
|----|------|
| 最低系统 | Android 5.0（API 21） |
| 目标系统 | Android 15（API 35） |
| 构建 | Android Studio Koala+ / JDK 17+ |
| 语言 | Kotlin 2.0 + Jetpack Compose (Material 3) |

## 🚀 快速开始

### 方法一：Android Studio（推荐）

```bash
git clone https://github.com/你的用户名/LaoXiangDouDizhu.git
```

1. 打开 Android Studio → **Open** → 选择 `LaoXiangDouDizhu` 目录
2. 等待 Gradle Sync 完成（首次会下载依赖，请保持网络畅通）
3. 连接手机（开 USB 调试）或启动模拟器
4. 点 **Run ▶** 即可

> Gradle Wrapper（`gradlew` + `gradle-wrapper.jar`）已随仓库提供，首次构建自动下载 Gradle 8.9。

### 方法二：命令行

```bash
./gradlew assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # 运行核心规则单元测试（300 场 AI 全自动对局）
```

### 方法三：GitHub Actions 云构建（免本地环境）

仓库自带 [`.github/workflows/build.yml`](.github/workflows/build.yml)。
**仅手动触发**，不会在 push / PR 时自动跑，不消耗免费额度：

1. 把仓库推到 GitHub 后，进入仓库 **Actions** 标签
2. 左侧选择 **Build APK** → 点右侧 **Run workflow**
3. 选择构建类型：`debug` / `release` / `all`（默认 all）→ 点绿色按钮
4. 等约 5–10 分钟跑完，点进该次运行，在页面底部 **Artifacts** 下载 APK

> 没有 Android Studio、不想装 JDK？用这个方式直接出安装包。
> release 包默认用 debug 签名（能直接安装）；如需正式签名，在仓库
> **Settings → Secrets and variables → Actions** 配置 4 个 Secrets：
> `RELEASE_KEYSTORE_BASE64`（`base64 -w0 xxx.jks` 的输出）、
> `RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`，
> 配好后再手动触发即出正式签名包（见 yml 文件头部注释）。

## 🎮 玩法说明

1. **大厅**：点头像换形象（12 位 Q 版老乡），填诨名，选难度
2. **单人**：选难度 → 开打
3. **棋牌合集**：斗地主 / 跑得快 / 锄大地 / 掼蛋 / 升级 / 大众麻将 / 癞子麻将 / 四川麻将
4. **麻将**：点牌选中（再点一次或点「打出」弃牌）；吃碰杠胡按钮弹出时选择；
   四川局先定缺再换三张（选同花色 3 张）；胡牌自动报番结算
5. **联机**：
   - 房主：点「我是房主」→ 选玩法（含三种麻将）→ 屏幕显示 IP → 等人 → 空位 AI 补位 → 开局
   - 串门：点「串门」→ 自动列表选房 → 或手动输房主 IP → 等房主开局
6. **牌局**：斗地主叫地主→抢地主→出牌；轮到你时可用 **提示** 找牌，**不出** 要不起

## 🗂️ 工程结构

```
LaoXiangDouDizhu/
├── .github/workflows/build.yml    # GitHub Actions 手动云构建（workflow_dispatch）
├── gradlew / gradle/wrapper/      # Gradle Wrapper（8.9）
├── app/src/main/java/com/laoxiang/ddz/
│   ├── data/                      # 纯 Kotlin 游戏核心（可单测）
│   │   ├── Card.kt                #   扑克牌模型 + 牌堆
│   │   ├── CardType.kt            #   14 种牌型识别 + 比牌规则
│   │   ├── GameEngine.kt          #   斗地主状态机：发牌/叫抢/出牌/结算/记牌
│   │   ├── MoveGen.kt             #   招法生成（AI 与提示共用）+ 手牌分解
│   │   ├── AiPlayer.kt            #   斗地主三档难度 AI
│   │   ├── PdkEngine.kt / PdkAi.kt        # 跑得快
│   │   ├── BigTwoEngine.kt / BigTwoAi.kt  # 锄大地
│   │   ├── GuandanEngine.kt / GuandanAi.kt # 掼蛋
│   │   ├── ShengjiEngine.kt / ShengjiAi.kt # 升级
│   │   └── MjTile.kt / MjWin.kt   #   麻将：牌模型 + 胡牌判定/算番/向听（v22）
│   │       MjEngine.kt / MjAi.kt  #   麻将：三模式状态机 + AI（v22）
│   ├── net/                       # 局域网联机
│   │   ├── Protocol.kt            #   换行分隔 JSON 协议（密封类多态 + 麻将 mjact）
│   │   ├── Discovery.kt           #   UDP 广播发现（MulticastLock）
│   │   ├── LanHost.kt             #   斗地主房主权威服务器（TCP 38889）
│   │   ├── GameHost.kt            #   通用房主（掼蛋/升级/跑得快/锄大地/麻将）
│   │   ├── GameDrivers.kt         #   各游戏房主侧驱动
│   │   ├── MjDriver.kt            #   麻将房主侧驱动（v22）
│   │   └── LanClient.kt           #   客户端（心跳保活）
│   ├── audio/SoundManager.kt      # SoundPool 音效 + BGM 循环；VoiceMap 语音映射
│   ├── ui/
│   │   ├── common/Ui.kt           #   红金主题组件
│   │   ├── lobby/LobbyScreen.kt   #   大厅
│   │   ├── room/RoomScreen.kt     #   联机房间（房主/客人）
│   │   ├── game/                  #   牌局（横竖屏自适应）
│   │   │   ├── GameScreen.kt      #     斗地主布局 + 特效 + 交互
│   │   │   ├── PdkGameScreen.kt / GuandanGameScreen.kt / ...
│   │   │   ├── MjTileView.kt      #     麻将牌 Canvas 渲染（零图片资源，v22）
│   │   │   ├── MjGameScreen.kt    #     麻将桌面：吃碰杠胡/定缺/换三张/结算（v22）
│   │   │   └── MjViewModel.kt     #     麻将三模式编排（v22）
│   │   └── result/ResultScreen.kt #   结算
│   └── MainActivity.kt
├── app/src/test/                  # 核心规则单元测试（含 V22MahjongTest 90 场 AI 自战）
└── app/src/main/res/
    ├── drawable-nodpi/            # 54 张牌 + 12 头像 + 桌/背景/印章
    ├── drawable/mj_icon.png       # 麻将合集图标（v22）
    └── raw/                       # 音效 + 全套语音（含麻将 voice_mj_* 50 条）
```

## 🔍 设计要点

- **纯逻辑核心**：`data/` 目录零 Android 依赖，规则 100% 可单测；
  单机与联机共用同一 `GameEngine`，联机房主以权威模式驱动
- **协议安全**：房主只向每个客户端下发"本人视角"快照（他人手牌不可见），
  客户端输入一律在房主侧校验，防改包作弊
- **线程模型**：引擎单线程调度（`limitedParallelism(1)` 语义），
  网络读写全部 `Dispatchers.IO`，UI 状态经 `StateFlow` 单向流动
- **AI 记牌**：困难 AI 与记牌器共用「54 − 已出 − 我手牌」推算，
  判断出一手牌是否无人能压

## 🧪 测试

```bash
./gradlew test
```

覆盖：全牌型识别、比牌规则、快照隐私（他人手牌隐藏）、记牌器守恒、
**300 场三档 AI 全自动对局**（校验牌数守恒、每步合法性、必然终局）。

## 📄 许可

- 代码：[MIT](LICENSE)
- 美术 / 音效素材：由项目需求方提供或来源于公开网络，版权归原作者，
  仅供学习交流

## 🙏 鸣谢

- 扑克牌 / 人物 / 牌背素材：项目提供
- 牌桌 / 背景图：网络检索
- 其余桌面、水印、音效处理：本项目加工
- 麻将对局背景音乐：**"Ishikari Lore" — Kevin MacLeod (incompetech.com)**，
  Licensed under **Creative Commons: By Attribution 4.0**（应用内大厅页脚已署名）
