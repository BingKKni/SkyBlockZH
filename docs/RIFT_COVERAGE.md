# 裂隙对白与任务物品覆盖范围

## 来源与本轮补全

沿用上次中断时保留的社区 Wiki **普通呈现页彩色缓存**，并通过 Playwright 补查物品页及关联菜单。没有使用官方 Wiki、Raw 页面或游戏采集日志；`verified_ingame` 均保持 `false`。

- 核对 123 个 NPC 页面，补齐此前未合入的侦探案、镜像世界、村庄支线、山巅主线等对白与回答选项。新增核对的 24 页包括 Eleanor I、II、V、VII、IX、X、五位 Shen、Old Timer、Oruo 及道路守门者。NPC 文件记录角色口吻，显示别名与人名仍按既有规则登记。
- 本次遗漏补译直接分析已保存的 33 份普通 HTML：除上述 NPC 外，还核对 Hocus-Pocus Cipher、Leggings of the Pack、Nothing-in-a-Bottle、Polarvoid Book、Transmission Tuner 及 Eleanor 四件衣服。网页页脚的最后编辑时间原样登记；这份缓存没有采集时间，不补造时间戳。
- 物品先通过真实引擎审计本地 NEU 服务器物品快照，再补缺失名称、任务描述、技能与使用反馈。已覆盖的名称、完整 Lore、通用提示和稀有度继续复用。
- 包括侦探扫描仪的逐项线索、符文收集与守护法术、猫咪探测器、裂隙药水、活体金属材料、吸血鬼讨伐物品、时光符和山巅奖励。
- 围巾、巫师面孔、两种护腿的颜色设置，以及面罩终端、炫富头盔的动物选择菜单均按实际格子采集，不只翻译入口。
- 新缓存完整嵌入的巫师面包屑、Eleanor 四个领奖菜单和 Oruo 的太阳壁虎精华商店一并收录；商店包括连击狂热、手里剑、宠物道具、超级水蛭改装件、时间祝福 I、谜魂和纹章，通用提示与已有说明通过 `ref` 复用。Leggings of the Pack 页面内的相关护甲和材料 tooltip 也按实际游戏文字核对。

页面清单、原始采集时间、最后编辑时间和 NEU 审计范围见 [`sources/rift-coverage.json`](sources/rift-coverage.json)。数量表示已收录语料，不代表全玩法实机覆盖率。

## 交互、颜色与变体

聊天选项的提示、左右括号、正文及间隔各自分段；不在语料中猜写点击指令。点击、悬浮、插入文本和真实颜色仍来自服务器组件。无括号的终端 `CLICK` 另有交互回归。

多行 Lore 使用完整逻辑句及原始 `samples`；数值、玩家名、进度和倒计时不固定为 Wiki 示例。Ubik 箱序数使用受限的 `ordinal_word`，而非对任意英文词进行替换。裂隙时间及法力回复的新旧图标会规范化匹配，再恢复输入实际使用的图标。

Wiki 与 NEU 不一致时保留可证变体，例如技能标题空格、不同的裂隙伤害图标、IQ Point 的两种属性说明、Flex Helmet 的硬币/微尘消耗、附魔书包的 `unwarp` / `unwrap`。Wiki 中的 `#` 等变量标记不冒充实机文本；需要实例化才能回放的样本明确标记为离线构造。

Lost Wizard 的正文保留反向阅读形式，倒写的时光符专名沿用现有保留规则。Eleanor V 要求玩家输入的 `Eleanor is the best!` 是任务口令，提示语翻译，口令保留英文。Timite、Youngite、Obsolite 和 Shen's Ringalia 不另造译名。Scavenger 获取量的 `Nx` 加成按现有倍率规则译为“提升 N−1 倍”，并保留附魔原名及数值颜色。

## 仍需游戏采集的边界

以下界面在核对的普通 Wiki 页面及 UI 索引中没有完整逐字文本，不补造：

| 来源 | 未记录部分 |
|---|---|
| Enigma | 购买后的披风升级菜单 |
| Dr. Emmett | 房间传送选择菜单 |
| Plumber Joe | Super Scorcher 的二级交易选项 |
| Porhtal | 眼睛平静后的菜单状态 |
| Maddox | Wiki 中仍锁定或未发布的首领格子 |
| Wizardman | Quad Link 对局棋盘 |
| Ubik | 平分/独吞对局、Cube Stats、已提交数字后的 Choice 状态 |
| Oruo | 时间密室入口与调整项菜单；时间祝福 II、III，以及商店锁定/购买后的状态（缓存仅嵌入精华商店与祝福 I） |
| Eleanor X | Shen's Ringalia 购买菜单（缓存未嵌入） |

Arora、Argofay Branchstrutter、Seskel、Tybalt 页面没有可采集的逐字对白；仅登记 NPC 身份，避免专名重复采集。Amog 页面中的六条模拟玩家聊天不建宽泛匹配规则，以免翻译真人发言。Free Will 在私人岛屿触发的小人独白不属于本次裂隙 NPC/任务物品说明范围。

## 验证

`checkTranslations`、`checkLore`、`checkChatInteractions` 检查真实匹配、原始颜色样本、跨行消费、动态取值与按钮事件；分发构建还执行所有 Minecraft 目标的完整检查。新增手写用例涵盖新旧图标、箱序数、侦探工具、谜魂进度和技能数值。

这些离线检查不能替代游戏内的字体换行、菜单状态和服务器实时消息验证。之后如有实测日志，应以实测为准修正 Wiki 差异。
