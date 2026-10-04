# Jerry 的工坊采集范围

## 来源与边界

[原文档案](sources/jerrys-workshop-wiki.json)来自社区 Wiki 的 **120 个普通呈现页**，不是 Raw API 或实机日志。保留页面更新时间、对白颜色、Tooltip 名称和完整 Lore，以及菜单内嵌的后续子菜单。

- 岛屿清单中的 **18 位 NPC** 均已检查。NPC 页共有 **237 条对白样本**，包含重复、玩家选项、花园/电话分支，以及 Jerry 的其他场景对白；不是新增翻译条数。
- **1,699 份去重 Tooltip、80 个带标题的菜单样本**包含关联的银行、精华指南、Jerry 通用设置、配方材料及装饰品索引。另有 2 个无标题的配方格子展示，保留 `title: null`，不猜测界面标题。
- 工坊物品范围包括：鸡赛奖励、礼物及其奖励、冰川洞穴宝藏、赠礼里程碑、NPC 商店商品、冬季海洋生物材料、雪炮升级链、雪服/胡桃夹子装备、冬季宠物和相关升级材料。关联配方不等于物品都能在工坊掉落；商城皮肤也不冒充活动奖励。
- `minetip_text` 原样保存颜色码、换行分隔符、转义和 HTML 实体。解析时先区分未转义的换行 `/` 与数量中的 `\/`，再解码实体及颜色。NPC 参数标记只用于来源记录，运行时规则另行模板化。

## 本轮补充

新增 **181 条原文记录**：170 条译文、11 条明确保留英文的记录；另有 35 条引用复用五位洞穴居民的共用对白。既有的 St. Jerry、Einary、Terry、Frozen Alex、Gregory、Hendrik、Sherry 等对白及通用物品 Lore 不重复复制。

| 来源玩法 | 原文本示例 | 译文 |
|---|---|---|
| 冰川洞穴居民 | I wonder where Hendrik is now... | 不知道 Hendrik 现在在哪儿…… |
| 工坊防卫 | Welcome to the Jerry's Workshop. | 欢迎来到 Jerry 的工坊。 |
| 鸡赛 | Cluckpoint / Gift Compass / Fried Frozen Chicken | 咯咯检查点 / 礼物罗盘（复用）/ 油炸冰冻鸡 |
| 赠礼里程碑 | Gift Milestone / Gift of Learning | 赠礼里程碑 / 学识之礼 |
| Einary 联系人任务 | Einary's Red Hoodie | 按既有用户要求保留英文；补齐 Lore 描述 |
| 雪炮升级 | Fragmented Cryopowder | 碎裂寒冻粉 |
| 冰霜精华商店 | Cold Efficiency / Frozen Skin / Drake Piper | 寒冷效率 / 冰冻肌肤 / 龙之笛手 |
| 冬季物品 | Winter Fragment / True Ice / Walnut | 寒冬碎片（复用）/ 至寒之冰 / 核桃 |
| 属性碎片 | Winter's Serendipity / Happy Box | 冬日奇遇 / 惊喜礼盒 |

新 Lore 使用完整句匹配，保留语序重排所需的颜色段。经验、等级、礼物数量、概率、日期和玩家名不按样本写死；染料色号是物品固定属性，沿用已有精确白名单，不接受未知色号。修复 Jerry 的活动对白和 Gregory/Ophelia 共用箭矢数量规则的颜色分段。**没有修改运行时翻译引擎或版本号。**

## 未伪造的内容

- Banker Barry 页面没有对白，只登记身份。
- St. Jerry 的年内重复领礼对白含破损的 `sic` HTML；Generow 的 `(gifts} Unique Gifts! Wow!` 参数标记也有损坏。保留原貌待实机核对，不编造台词。
- Frozen Alex 的 Wiki 电话前缀缺少姓名与冒号；复用已有正文记录，不把坏前缀写成新台词。
- Green Gift 与 Einary's Red Hoodie 沿用既有 `gloss` 中用户指定的英文保留；NPC 人名、唱片曲名及已有专名保留规则不被批量采集覆盖。
- BossBar、Sidebar、ActionBar 缺少足够可靠的带色完整文本。本轮保留库内已有实机规则，不从机制说明推导 HUD 文案。
- 内嵌子菜单已归档，但 Wiki 未展示的商店批量购买、确认框和其他状态仍需实机采集。**采集数量不是全岛/全部关联菜单的翻译覆盖率。** 通用设置、精华指南其他分类、宝石槽及收藏品等外围页面未在本轮全部补译。

## 校验

- `./gradlew checkWorkshopSources`：检查来源内容哈希、18 位 NPC 清单、菜单引用，以及颜色/HTML 实体/数量斜杠的解析边界；已接入根项目 `check` 和分发构建。
- `checkTranslations`、`checkLore`、`checkChatInteractions`：检查模板、完整句、颜色和聊天交互边界。
- `src/harness/resources/workshop-cases.json`：独立预期验证变值、鸡赛计时、子菜单、道具升级对象、染料白名单和残缺句子不吞行。变值样例是离线回归，不冒称实机数据。

离线测试不能代替 Minecraft 内的字体折行、实际服务器文案和显示效果核对。
