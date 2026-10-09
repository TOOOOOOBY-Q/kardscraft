# KardsCraft：Minecraft × KARDS

Windows 上的 Minecraft 1.21.1 Fabric 模组和 KARDS UE4SS Lua 桥接：把史蒂夫、方块和生物叠加到 KARDS 的 AI 训练牌桌。

项目以 [MIT 协议](LICENSE) 开源。第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

牌桌叠加和内存 setter 扣血已有实测记录；完整亡语、总部原生胜负结算、跨回合保留以及镜头跟随仍需逐项验证。发布包里的 `dist/verification.json` 记录自动检查结果，自动检查不能代替游戏实测。

## 下载与环境

在 [GitHub Releases](https://github.com/TOOOOOOBY-Q/kardscraft/releases) 下载完整 ZIP 并解压，或克隆本仓库。完整 ZIP 包含源码、脚本、Fabric 模组 JAR 和 Lua 后端；不包含游戏、UE4SS、存档或依赖缓存。

需要 Windows、Java 21 **JDK**、Minecraft 1.21.1、Fabric Loader / Fabric API，以及已安装 UE4SS 的 KARDS。自行安装 UE4SS；本项目安装脚本要求它已能正常加载。首次构建和启动需要联网下载 Gradle、Fabric 与 Minecraft 运行依赖。

## 一次性启动

下载中断时，`Run-Minecraft.cmd` 会重新检查并补齐缺少的资源；资源下载不等于游戏已启动或验收通过。

1. 关闭 KARDS，双击 `Install-KardsBridge.cmd`。它将五个 Lua 文件安装到现有 UE4SS，备份原文件，并配置双方使用本项目的 `bridge` 目录。默认游戏路径是 `E:\SteamLibrary\steamapps\common\KARDS`；其他安装位置请执行 `powershell -ExecutionPolicy Bypass -File .\Install-KardsBridge.ps1 -KardsRoot '你的 KARDS 安装目录'`。
2. 自己从 Steam 启动 KARDS，进入对 AI 的训练对局，使用窗口化或无边框最大化。
3. 双击 `Run-Minecraft.cmd`。使用本地 Java 21、Minecraft 1.21.1 和 Fabric 开发客户端；首次打开后创建**创造模式、允许指令、难度普通的超平坦专用世界**。Minecraft 使用普通窗口模式。
4. 在 MC 聊天栏输入 `/kardscraft enable`，再输入 `/kardscraft status`。理想状态为 `board=true, ready=true, busy=false, geometryReady=true`，并有卡牌数量。对局动作执行期间短暂 `busy=true` 会暂停实体。
5. 按 F9 切换操控 KARDS / 史蒂夫，开始下面的验收。Windows 若未允许自动切换焦点，点击叠加层后操控。

不要在重要旧存档首次启用。棋盘以 `(0.5, 100, 0.5)` 为中心，默认铺设 81×81 屏障地板；首次启用会检查区域占用，拒绝覆盖已有建筑。此后停用再启用会保留自己的建筑。

## 集中人工验收

| 步骤 | 操作 | 通过标准 |
| --- | --- | --- |
| 1. 连接 | 完成启动，在自己回合查看 `/kardscraft status` | 桥接持续更新，无关键字段缺失；能看到 HQ 与场上单位 |
| 2. 定位与输入 | F9 切到史蒂夫，在卡牌附近走动、跳跃、放方块；E 打开背包，1–9 换快捷栏 | 史蒂夫、地板、卡牌代理位置合理；操作 MC 时不会点到 KARDS；切回 KARDS 后可正常打牌 |
| 3. 真实伤害 | 用钻石剑砍一张有攻击力的敌方单位，记下原始防御值 | 实际防御减少 3（特殊效果可能改变）；MC 收到确认后按单位攻击力反击，每点攻击对应一颗心；失败会显示拒绝原因 |
| 4. 销毁与总部 | 杀死普通单位，再砍总部；可另试有死亡效果的单位 | 单位真正离场；特殊死亡效果与 KARDS 状态一致；总部不反击 |
| 5. 服务器保留 | 对同一受伤单位结束回合，等下一回合并继续对局到结算 | 伤害未回滚，没有不同步提示；这是判断“真实伤害已完成”的关键步骤 |
| 6. 生物与范围伤害 | 放僵尸、骷髅、苦力怕，安排敌我卡牌靠近爆炸 | 怪物追最近敌方卡牌；爆炸范围内敌我牌均提交伤害；仅确认的近战命中反击 |
| 7. 回合暂停 | 在箭飞行、苦力怕将爆或史蒂夫跳起时交回合 | 敌方回合玩家、生物、箭和引信暂停；恢复自己回合后继续，不能提前偷行动 |
| 8. 死亡与视角 | 让创造模式史蒂夫被反击致死并手动重生；按 F10 两次 | 确实死亡并回己方总部附近；F10 打开 MC 第一人称重建视角，再按返回牌桌叠加 |
| 9. 清理与重连 | 退出训练、开始新训练；退出 MC 世界重进；停用后再启用 | 无旧伤害重放、重复反击或残留卡牌；新对局重新定位，原方块保留 |

如某项失败，记下步骤编号、现象和 `/kardscraft status`，再双击 `Collect-Diagnostics.cmd`。诊断只保存到本地 `tools\diagnostics\时间戳`，包含交换状态、最近原生伤害结果和两端日志尾部；不会启动游戏或上传文件。

## 按键与命令

- F9：切换操控对象。F10：牌桌叠加 / MC 重建视角。
- F8：KARDS 立即刷新状态。旧版 F6 试验扣血已移除。
- 安装脚本把 UE4SS ConsoleEnabler 的 F10 改为 F11，原文件已备份。
- 叠加操控：WASD、空格、Shift、左右键；E 背包、T 聊天、1–9 快捷栏、Esc 返回 MC 窗口。
- `/kardscraft status`：诊断状态；`/kardscraft disable`：停用并保留方块；`/kardscraft enable`：启用或重新进入棋盘。

## 已知边界

- KARDS 后端默认使用本局卡牌对象的防御 setter，只有真实总防御下降才确认。UI、亡语、HQ 胜负结算及跨回合保留分别验证；内存模式不等于完整原生伤害规则。
- F10 第一人称会请求 Lua 后端跟随史蒂夫镜头，实际画面对齐仍需实机验收；当前没有双引擎共享的深度遮挡。
- 叠加层采用独立 Win32 窗口、CPU 像素回读和品红色键抠除，默认 20 FPS。边缘、品红色实物、缩放、DPI 和真实帧率需验收；失败时回到 MC 重建视角。
- 只支持本机 Windows、集成单人世界和已确认的 AI 对局。桥接失联、状态不完整、对手回合或动作忙碌时暂停棋盘实体。
- 断网弹窗自动关闭不在本实现中。没有修改联网、校验或反作弊机制。

## 构建、文件与恢复

```powershell
.\build.ps1 -Task build      # Java 编译、测试、重映射 JAR
.\build.ps1 -Task prepare    # 仅预下载运行依赖，不启动游戏
python tools/package-release.py  # 构建成功后生成 dist 发布包及 SHA-256 校验和
python tools/test-kards-bridge.py
python tools/test-kards-memory.py
python tools/test-kards-camera.py
python tools/test-kards-java-interop.py  # Lua / Java 双向真实协议测试
python tools/test-installer.py
```

Lua 测试需要 Python 3 和 Lupa：执行 `python -m pip install --target tools/lua-test-runtime lupa==2.8`。构建脚本从已安装的 Minecraft 运行时寻找 JDK；缺失时需配置 Java 21 的 `JAVA_HOME`。Java/Lua 互操作测试还需要 `java` 和 `javac` 在 PATH 中；先运行一次构建下载 Gson。

- `dist\kardscraft-0.1.0.jar`：编译后的 Fabric 模组。手动放入其他启动器时，需要 Minecraft 1.21.1、Fabric Loader 与对应 Fabric API，同时把 `bridgeDir` 配置为本项目的 `bridge` 目录。
- `kards-mod\KardsBridge\README.md`：后端协议、SDK 依据和诊断字段。
- `tools\install-backups\时间戳`：每次安装的原文件备份。恢复时先关闭 KARDS，执行：

```powershell
.\Restore-KardsBridge.ps1 -BackupDirectory '这里填安装时显示的完整备份目录'
```

恢复仅处理安装脚本涉及的 UE4SS 文件，不删除整套 UE4SS、不删除 MC 存档。MC 的 `bridgeDir` 配置会保留。

普通配置在 `minecraft-mod\run\config\kardscraft.properties`；按键和叠加帧率在 `kardscraft-client.properties`。若设置了 `KARDSCRAFT_BRIDGE_DIR` 环境变量，它会覆盖 KARDS 端路径，应与 MC 的 `bridgeDir` 保持一致。
