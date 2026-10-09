# KardsBridge v2 后端

当前默认模式为 `memory_setter`：修改本局真实卡牌对象的防御，再读回确认。**2026-10-08 10:56–10:58 实测已确认扣血有效，包括 TNT 对卡 41 的 11→7。** 本轮总部终局门禁、视觉退场和镜头跟随仍需新的实机验收；不能用既有扣血成功替代这些验收。旧 `DamageCard` 路径无效的历史证据仍保留。

## 文件与运行条件

- `Scripts/main.lua`：16 ms 调度镜头更新，命令处理和状态发布每六次回调进行一次（约 96 ms）；全部 UObject 操作在游戏线程；F8 可立即刷新。
- `Scripts/bridge_core.lua`：协议 v2、输入限制、按客户端去重、ACK、JSON 编码。
- `Scripts/kards_adapter.lua`：发现本局关联对象，读取真实 getter、场上位置与相机，按选定模式处理一次伤害。
- `Scripts/camera_follow.lua`：AI 局专用镜头、请求校验、500 ms 看门狗和原 view target 恢复；不修改输入或回合。

安装时需要完整复制 `Scripts` 下五个文件。`bridge_settings.lua` 默认 `damageMode="memory_setter"`，安装脚本在部署副本填入 `directory`。显式设置 `damageMode="native"` 可恢复旧原生调用进行比较；两个模式不会自动互相回退。需要已能正常加载的 UE4SS。

交换目录优先级为环境变量 `KARDSCRAFT_BRIDGE_DIR` → `bridge_settings.lua` 的 `directory` → KARDS 进程的 `%TEMP%`。Minecraft 的 `bridgeDir` 必须指向同一目录。目录必须已经存在。

## 协议

状态：`KardsBridge_state.json`。保留原有场上状态字段，并包含：

| 字段 | 含义 |
| --- | --- |
| `protocol` / `end` | 固定 `2` / `1`，`end` 最后写入 |
| `sessionId` | Lua 本次加载标识；重载后改变 |
| `matchId` | 本局稳定标识；状态对象、服务器 match_id 或回合重置时改变 |
| `clientId` / `lastCmd` | 最近处理客户端及其最高已处理序号 |
| `results` | 最近 256 条 ACK：clientId、seq、matchId、accepted、damage、attack、reason |
| `ready` / `busy` | 必需字段已可读 / 游戏正在处理动作等不可交互状态 |
| `geometryReady` | 已读到双方总部位置和当前玩家相机 |
| `unreadableCards` / `unpositionedCards` | 无法读取数值 / 真实位置的场上卡牌数 |
| `missingFields` / `referenceIssues` | 缺失的必要字段或对象关联检查失败的具体名称 |
| `busyReasons` | 正在处理的动作标记或非空队列及长度 |
| `lastDamage` | 最近一次原生调用前后总防御、解密防御、raw 字段及错误诊断 |
| `damageMode` | 本次启动选定的 `memory_setter` 或 `native` |
| `serverPersistenceVerified` | 恒为 `false`，本后端不把瞬时变化标记为服务器确认 |

`cards` 只包含有真实位置和有效原生 getter 的卡牌；不以加密 raw 字段或猜测坐标代替。`atk`、`def` 优先使用 `getTotalAttack` / `getTotalDefense`，兼容回退到解密 getter。每张卡保留 `decryptedAttack` / `decryptedDefense` / `rawAttack` / `rawDefense` 供验收对照。

命令：`KardsBridge_cmd.txt`，UTF-8，每行如下，**必须带换行符**：

```text
CLIENT SEQ dmg MATCH TURN CARD AMOUNT
```

- `CLIENT`、`MATCH`：1–128 字符，只允许英文字母、数字、下划线、连字符；使用当前状态中的 MATCH。
- 同一 CLIENT 的 SEQ 为递增正整数，最大 `2147483647`，文件必须按 SEQ 升序。重启 Minecraft 应生成新 CLIENT。
- TURN 必须与当前 KARDS 回合一致；CARD 为场上卡牌 ID；AMOUNT 为 1–100 整数。
- 最大文件 32 KiB、最多 64 行；每次轮询最多处理 4 个新请求。超过限制的文件不执行。
- Minecraft 独占写命令文件；KARDS 只读，保留高水位保证重复读取不重复执行。ACK 已包含在后续状态中。
- 状态先完整写临时文件再重命名；Windows 上替换已有文件可能有极短的缺失窗口，读端应重试。

拒绝原因包括原有协议门禁，以及 `memory_api_unavailable`、`memory_api_mismatch`、`memory_context_mismatch`、`memory_keys_error`、`memory_keys_unavailable`、`memory_total_defense_unavailable`、`memory_readback_unavailable`、`memory_write_error`、`memory_no_effect`。被拒绝的序号同样消耗，不能用相同序号重试。

## 原生调用的边界

后端从当前 `BP_OnlineMatch_C` 的 `GameStateRef`、`cardFunctions`、`Logic` 取引用，并核对各自关联的同一 GameState 和当前世界的 GameMode。当前控制器通过 `MatchControllerV2.GetMatchController(context)` 获取。不会分别 `FindFirstOf` 一个无关的 CardFunctions 或 GameState。

只有已确认 AI 对局、我方回合、回合号一致、非终局、非观战或回放、动作队列空闲才尝试伤害。仅 `GameState.MatchFinished` 或 `Logic.matchHasEnded` 为真时判定终局。总部总防御归零只标记 `hqZeroPending`，保持本局与桥接，不伪造 KARDS 的胜负结算。

内存模式的核心顺序为（实际实现还验证完整函数路径、对象类型、参数与读回值）：

```lua
provider(cardFunctions, key1Out, key2Out, frameOut)
setter(target, beforeDecryptedDefense - math.min(amount, beforeTotalDefense),
    namedOutput({key1Out,key2Out,frameOut}, "key1"),
    namedOutput({key1Out,key2Out,frameOut}, "key2"),
    namedOutput({key1Out,key2Out,frameOut}, "FrameCount"))
```

`provider` 是 `/Game/Blueprints/Cards/BP_CardFunctions.BP_CardFunctions_C:provideKeysAndFrameCount`；`setter` 是 `/Script/kards.BaseCardObject:setAndEncryptDefense`。三个 out table 相互独立，值必须是 signed int32。参数来自游戏，使用后不缓存、不输出到日志；不写固定内存偏移或 raw defense。

安装的 UE4SS `5418fa4e` 可能将所有 out 字段写进第一个 table，因此必须在全部输出表中按参数名联合读取；兼容大小写，同名冲突则拒绝，不按位置或默认值猜测。`GetVisualBoardCardCardFromID` 同样处理。`keyOutputFields` 仅记录字段名称与类型，便于核对真实布局。

写入前后必须成功读取总防御，以真实差值生成 `memory_damage_observed` ACK。不能把总防御和解密基础防御混算。setter 若写入后抛错，但读回已证明扣血，仍确认实际变化，并单独保留脱敏错误；没有读回证据则拒绝，始终不重试。ACK 的 `attack` 为受击单位写入前攻击力，总部为 0。

成功后，按当前棋盘 ID 和 GameState 找到视觉卡牌，调用 `SetDefense` 刷新。`uiRefresh=call_completed` 只表示调用完成，不是截图验收。普通单位归零后尝试一次逻辑 `DestroyCard`；只有数据离场后才清理事先捕获的视觉卡。视觉卡使用自身 `DestroyCard()`，延迟观察后必要时仅一次 `K2_DestroyActor()` 兜底，同时核对明确关联的独立 `BoardCardWidget`。`deathResolution=resolved` 要求数据离场且捕获的场景对象确实无效；隐藏、索引中找不到、销毁调用完成均不算成功。不重做扣血或逻辑销毁；视觉清理失败不改变已有真实扣血 ACK。HQ 归零标记 `hq_pending`。这仍不等于完整复刻护甲、免疫及各种受伤触发器。

`KardsBridge_damage.jsonl` 按请求保留诊断，1 MiB 轮换，留一份 `.1`；日志失败不改变 ACK。不调用通知来伪装扣血，不伪造服务器动作、不修改校验或联网行为。`serverPersistenceVerified` 恒为 false；后续回合保留和游戏结束仍需实测。

致死过程的瞬态负防御以 `def: 0` 发送，`decryptedDefense` 仍保留原始解密结果；这避免 Java 严格解析器把整个终局 ACK 丢弃。

## 牌桌第一人称镜头

读取 `KardsCraft_camera.json` 的 schema 1 平面 JSON。`follow` 必须带当前 `sessionId` / `matchId`、递增 `seq`、毫秒 Unix 时间 `sentAtMs`、Unreal 坐标 x/y/z、角度 pitch/yaw/roll 和水平 `fov`。仅已确认 AI 局允许接管；不要求是我方回合，也不修改任何输入开关。

使用本局专用 `CineCameraActor`，只修改它自己的近裁剪面（1 UE）、水平 FOV 和位置旋转，保存原 view target 后零过渡切换。CineCamera 通过固定 36 mm 传感器和焦距计算水平 FOV；不会修改原相机或全局裁剪参数。`mode="release"`、超过 500 ms 未更新、会话/对局不符、非 AI 局或错误时释放并恢复原镜头。已由游戏主动切换到其他镜头时不抢回旧视角。

`camFollow` 是执行诊断，不是画面成功证据。`cam` 始终来自真实 `GetPlayerCameraManager` 的位置、旋转、FOV；绝不回填请求坐标。Minecraft 使用这个真实读数确认跟随。新功能的实际视角、近裁剪和叠加对齐仍需实机验收。

异步单位退场可查看 `deathResolutions`（最近 64 条）。主卡、文字 Actor 及 SDK 中明确关联的独立特效/展示 Actor 都纳入观察；额外 Actor 的归属不足以安全清理时只报告 `residual_visual_unverified`，不会批量删除。用精确 `/Script/Engine.KismetSystemLibrary:IsValid` 原生函数检查，避开 Lua 包装器同名方法；仅引擎判无效时还会跨观察帧复核，销毁中的对象不再调用清理事件。

伤害 JSONL 保留该请求处理当时的结果，后续观察不会倒改历史日志；`lastDamage` 和 `deathResolutions` 更新后续销毁状态。`resolved` 表示已观察到逻辑与视觉离场，不表示全部亡语、服务器持久化或胜负流程均已验证。

## 自动检查

在项目根目录执行：

```powershell
python -m pip install --target tools/lua-test-runtime lupa==2.8
python tools/test-kards-bridge.py
python tools/test-kards-memory.py
python tools/test-kards-camera.py
python tools/test-kards-java-interop.py
python tools/test-installer.py
```

测试在独立临时目录内写交换文件，不访问游戏进程。覆盖请求幂等、ACK 保留、各项拒绝条件、SDK 命名 out 参数、加密 raw 不变时的解密读数、无变化与伪销毁成功的拒绝、match 标识更新、坐标缺失处理、游戏线程调度和 JSON 互通。

跨语言测试需要 PATH 中的 JDK，以及项目 Gradle 已下载的 Gson。它编译真实 `BridgeState` / `BridgeSession`，让 Java 生成命令、Lua 协议核心处理并返回状态，再由 Java 消费 ACK；覆盖重复请求/ACK、回合变化、后端重启、旧对局请求拒绝、终局 ACK 和负防御瞬态。

SDK 依据为本机 UE4SS 导出的 `kards.hpp`、`BP_OnlineMatch.hpp`、`BP_Logic.hpp`、`BP_GameState_Battle.hpp`、`BP_CardFunctions.hpp`、`BP_BaseCard.hpp`、`BP_Board.hpp`。游戏更新可能改变这些字段；本代码在关键字段缺失时拒绝伤害并输出诊断。

UE4SS 依据：[ExecuteInGameThread](https://docs.ue4ss.com/dev/lua-api/global-functions/executeingamethread.html)、[LoopAsync](https://docs.ue4ss.com/dev/lua-api/global-functions/loopasync.html)、[UObject / GetFullName](https://docs.ue4ss.com/dev/lua-api/classes/uobject.html)、[TMap / ForEach](https://docs.ue4ss.com/dev/lua-api/classes/tmap.html)。标量 out 参数的 table 与参数名规则已核对 [UE4SS 官方调用实现](https://github.com/UE4SS-RE/RE-UE4SS/blob/main/UE4SS/src/LuaType/LuaUObject.cpp)。
