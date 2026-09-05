# 术语表 / 一词多义记录

采集阶段发现的、需要在全项目范围内**统一译名**或**特别注意语境**的词,记在这里。
不需要现在给出中文译名,先把"这个词有坑"记下来,翻译阶段统一决策。

格式:

```
### 词 / 短语

- 出现场景: ...
- 含义 1: ...(出现在哪些文件)
- 含义 2: ...(出现在哪些文件)
- 备注: ...
```

---

## 这里面有什么

- **全局规则与排版**（10 条）：NPC 对白的口语与人情味、SkyBlock、感叹号后面加空格、空岛时钟、空岛日期、空岛季节名、"Nx" 倍率加成的翻译方式、全大写的消息前缀、原版 Minecraft 材料名一律用官方中文译名、稀有度词
- **什么翻、什么不翻**（7 条）：Rank / Boosters / Gems、NPC 的"职务型名字"翻译,"人名"不翻译、物品名、怪物名、带专有人名的地名、重铸名、技能名 Mining vs 属性前缀 Mining
- **货币与经济**（5 条）：货币名、Cost / Price、Fame / Fame Rank、The Hex、Bazaar
- **属性词条与物品 Lore**（10 条）：Fortune、Block Fortune / Mining Fortune、Breaking Power、Mining Spread、Tiered Bonus / Mineralworks、Reforge / Soulbound / Accessory Power / Accessory Bag、Soulbound 标记行的三种变体、物品 Lore 里的固定词汇表、Titanium、Fuel
- **Mining 玩法专有词**（14 条）：Commission、Collector、冰河隧道矿物材料名、冰封尸体的类型名、宝石品质等级、宝石品质第五档 Flawless、HOTM Exp、山之心天赋名 / 限时活动名、Token of the Mountain、挖矿限时活动名、镐子技能名、Drill Mechanic、方块速查手册里的方块名、极冰狂人
- **Mining 地名与 NPC**（8 条）：Crystal Hollows、Hanging Court、Lapis Quarry、Dwarven Mine Co.、Don、Bal、Keeper of ___ / Professor Robot、"Keeper of ___" 里的材料名改为翻译
- **跨玩法机制**（9 条）：公历月份名、账号与存档升级名、限时活动名、Garden Visitor、Fairy Soul、Loadouts / Loadout、Bingo、Chum / Bait、Pity / pity counter
- **剧情线与彩蛋**（3 条）：陨落之星教团剧情线固定译名、Tal Ker 独白里的地名 / 世界观词汇、Tal Ker 独白里的其他一次性梗 / 彩蛋
- **语料库建设记录**（1 条）：共享片段库 `_shared/` 建设情况

## 全局规则与排版

### NPC 对白 —— 先像人在说话,再像游戏说明

- 用户于 2026-09-05 明确要求: **NPC 的发言要有人情味,避免无意译成中二宣言或正式说明书。**
  默认采用自然的搭话、邀请、抱怨或调侃口吻; 不照搬英文的书面句式。
  例如 `Do you thirst for adventure?` 在 Maddox 的招呼里译作「想出去冒个险吗?」,
  比「你渴望冒险吗?」更像面对玩家说话。
- **口语化不等于所有 NPC 都一个性格。** 保留角色原本的情绪和身份; 剧情确实在演说、威胁或故意夸张时,
  可以保留相应语气。不要为制造亲切感硬塞网络梗、方言、称兄道弟或原文没有的人物关系。
- **语气可以放松,机制不能改。** 任务名、条件、数量、因果、概率与奖励方式仍须准确;
  「解锁奖励」不能润色成「打一次就必掉好东西」。专有名词和颜色分段仍按既有规则处理。

### SkyBlock(玩法名本身)

- 出现场景: 几乎所有分类,以及大量尚未翻译的英文句子里。
- 译名: 全称"空岛生存",复合词里用简称"空岛"(空岛菜单 / 空岛等级 / 空岛经验)。
- 备注: **不要在各条目的 `zh` 里自己决定写全称还是简称**,照常写英文 "SkyBlock" 即可——
  它受配置项 `translateSkyBlockName` 控制,关闭时要能还原成英文,所以必须由代码在渲染时替换。
  译名、简称、以及"什么时候用简称"的规则都在 `_shared/SkyBlock_Name.json`,改那个文件即可,
  不需要动代码。译文里按中文排版习惯在 SkyBlock 前后留空格是**正确的**,替换成中文后代码会自己
  判断该不该保留(两侧都成汉字就删,一侧是数字/字母就留)。

### 感叹号后面加空格(全局排版规则)

- **译文里的半角 `!` 后面如果还有字,必须跟一个空格**:`纯净! 你额外获得了…`,不是`纯净!你额外…`。
- 行尾的 `!`、连写的 `!!!!`、以及 `!?`/`!,`/`!)` 这类紧跟标点的情况**不加**空格。

### 空岛时钟 —— 12 小时制 + 中文时段词

- 出现场景: 计分板侧边栏第三行(`3:30pm ☀`)。
- 规则: 保留 SkyBlock 的 12 小时制,把 am/pm 换成中文的时段词:
  凌晨(12am–5am)/ 上午(6am–11am)/ 中午(12pm)/ 下午(1pm–5pm)/ 晚上(6pm–11pm)。
  写成「下午 3:30」,时段词和时间之间留一个空格。
- 理由: 英文 12 小时制在 12 点那一格有歧义(12:30am 是半夜),中文的时段词各自只覆盖一段,
  没有这个问题,读起来也比 24 小时制自然。2026-08-21 曾一度改成 24 小时制,已按用户要求改回。
- 备注: 引擎是查表不是算术,换算做不到运行时,所以**小时和时段写死在模板里、一小时一条记录**
  (`Hub_General/ScoreBoard/Common_Sidebar.json`,am/pm × 带不带图标 × 12 小时 = 48 条);
  分钟和白天/夜晚图标仍是占位符,图标因此保留服务器给它的颜色(黄日 / 青月)。

### 空岛日期 —— 「节气 N 日」,数字两边留空

- 写法: `夏至 20 日`,不是 `夏至20日`。侧边栏是屏幕上最密的一块文字,
  三个汉字和两位数字挨着写会糊成一团。节气对照表见上面「空岛季节名」那一条。

### 空岛季节名 —— 用二十四节气

空岛一年十二个月,英文是 `Early / (无前缀) / Late` × 春夏秋冬。中文**用二十四节气里对应的那一个**,
不用"初春/仲春/暮春"这种自造词:季首取四立、季中取二分二至、季末取该季最后一个节气。

| 原文 | 译名 | | 原文 | 译名 | | 原文 | 译名 | | 原文 | 译名 |
|---|---|---|---|---|---|---|---|---|---|---|
| Early Spring | 立春 | | Early Summer | 立夏 | | Early Autumn | 立秋 | | Early Winter | 立冬 |
| Spring | 春分 | | Summer | 夏至 | | Autumn | 秋分 | | Winter | 冬至 |
| Late Spring | 谷雨 | | Late Summer | 大暑 | | Late Autumn | 霜降 | | Late Winter | 大寒 |

日期整行是"节气 + 第几天":`Early Autumn 20th` → `立秋20日`。

### "Nx" 倍率加成的翻译方式(避免"叠加后封顶"的歧义)

- 出现场景: Sky_Mall_Bonuses.json 等描述"概率/掉落量变成原来的 N 倍"的加成行
  (英文写法通常是 "Nx chance/drops")。
- **已确定译法**: 用"提升 (N-1) 倍"而不是"提升至 N 倍"。原因:这类加成能否和其他
  来源叠加通常没有查实到确切机制(见 Golden Goblin 词条 wiki 页,只写了哪些来源会
  影响概率,没给出叠加公式);"提升至 N 倍"容易被误读成"叠加后封顶 N 倍",而中文
  "提升 X 倍" 的严格数学含义是"变成原来的 (1+X) 倍",所以写"提升 (N-1) 倍"在
  "能叠加"和"不能叠加"两种情况下都成立、不会被误解成有上限。以后遇到同类 "Nx" 写法
  直接套用这个换算,不用重新讨论。

### 全大写的消息前缀(PRISTINE! / COMPACT! / MAYHEM! ...)

SkyBlock 的很多提示是"全大写标签 + 一句话",标签点出这句话是哪个机制触发的。
标签也翻译,按机制的既定译名走:

| 原文 | 译名 | 机制 |
|---|---|---|
| PRISTINE! | 纯净! | 纯净(Pristine)属性额外掉宝石 |
| COMPACT! | 压缩! | 压缩(Compact)天赋 |
| MAYHEM! | 矿井狂乱! | 矿井狂乱(Mineshaft Mayhem)天赋 |
| EXCAVATOR! | 挖掘! | 挖到可疑残料 |
| MINESHAFT! | 矿井! | 附近刷出矿井传送门 |
| MINES! | 地雷! | 矿井"地雷"修正 |
| BYE! | 再见! | 阵亡前离开矿井的补偿 |
| WOW! | 哇哦! | 发现矿井传送门 |
| LUCKY! | 好运! | 资源利用天赋省下钥匙 |
| BRRR! | 好冷! | 寒冷值阶段提示 |
| FROSTBITE! | 警告: 冻伤! | 寒冷值满、开始掉血 |

**注意**:这些标签在游戏里是整行的第一段颜色(通常加粗),记录必须把标签写进 `text`
并单独分一段颜色——引擎不会替你剥掉它(只有 `[NPC] 名字: ` 这种说话人前缀会被剥)。

`FROSTBITE!` 是这张表里唯一一条**译文比原文多一个词**的:它不是聊天里的"标签 + 一句话",
而是屏幕正中央单独弹出的大字,只写"冻伤"两个字读不出这是个警报,而它出现时玩家已经在掉血了。

### 原版 Minecraft 材料名一律用官方中文译名

- `Enchanted Redstone Dust` → 附魔红石粉,`Enchanted Iron Ingot` → 附魔铁锭,
  `Enchanted Coal` → 附魔煤炭,`Enchanted Emerald` → 附魔绿宝石,
  `Enchanted Lapis Lazuli` → 附魔青金石,`Enchanted Diamond` → 附魔钻石,
  `Enchanted Gold Ingot` → 附魔金锭,`Golden Carrot` → 金胡萝卜。
- 理由:这些词的中文是 Minecraft 官方语言文件里就有的,玩家在原版界面里天天看见,
  保留英文反而制造陌生感。**"Enchanted" 前缀译"附魔"**,和原版一致。
- 边界:这条只管**原版就有的材料**;SkyBlock 自造物品名的规则见下一条。

### 稀有度词(Rarity)

- 出现场景: 所有物品 Lore 最后一行,如 "RARE DRILL" "EPIC HELMET" "COMMON ACCESSORY" "MYTHIC DRILL"
- 含义: COMMON/UNCOMMON/RARE/EPIC/LEGENDARY/MYTHIC/SPECIAL/VERY SPECIAL/DIVINE/ADMIN 等固定稀有度等级词,和物品类型标签(DRILL/HELMET/ACCESSORY 等)拼在一起。
- 备注: 必须做成一套**全项目共享**的稀有度词表,所有玩法分类共用同一套译名,不能在不同物品文件里各译各的。

## 什么翻、什么不翻

### Rank / Boosters / Gems —— 一律保留英文,不翻译

- 出现场景: Tab 列表页眉页脚、计分板侧边栏、社区中心相关界面。
- **全项目硬性规则**: `Rank`/`Ranks`、`Booster`/`Boosters`、`Gems` 三个词**一律保留英文原样**,
  因为 Hypixel 官方的中文语料本身就不翻译这三个词,翻了反而和玩家在别处看到的对不上。
- **`Gems` ≠ `Gemstone`,这是本项目真实发生过的 bug**。`Gems` 是充值/付费货币;
  `Gemstone` 是 Mining 玩法的矿物材料,译"宝石"。两者一度都被译成"宝石",于是
  `Tab_Widget_Enum.json` 里的 `Gems:` 和 `Gemstone:` 在屏幕上长得一模一样,
  `Common_Sidebar.json` 的 `sb_gems` 同样中招。已更正:`Gems` 保留英文,`Gemstone` 继续译"宝石"。
- **例外: `Booster Cookie` 要翻译**,译"增益曲奇"。那是 SkyBlock 的一件道具名,
  和商店里卖的网络加成 `Boosters` 不是一个东西;它带来的 `Cookie Buff` 译"曲奇增益"。
  三个词摆在一起容易混,记住:道具=增益曲奇,它给的加成=曲奇增益,商店的网络加成=Boosters(不译)。
- **`Fame Rank` 也不是付费 Rank**,而是社区中心里的声望等级,译"声望等级"。只有单独表示
  Hypixel 付费身份等级的 `Rank` / `Ranks` 保留英文。

### NPC 的"职务型名字"翻译,"人名"不翻译

同一个 `[NPC] xxx` 标签里可能既有职务又有人名,处理方式不同:

- **纯职务**→ 翻译:`Lift Operator` → 升降梯管理员,`Station Master` → 站长,
  `Ticket Master` → 售票员,`Mining Merchant` → 挖矿商人,`Lazy Miner` → 懒惰矿工,
  `Lapis Miner` → 青金石矿工,`Blacksmith` → 铁匠,`Iron Forger` → 熔铁匠,
  `Gold Forger` → 熔金匠(后两个刻意避开"铁匠",免得和 Blacksmith 撞名),
  `Royal Resident` → 皇家住户,`Tribe Member` → 部族成员。
- **人名**→ 保持英文:Rhys、Bubu、Gemma、Dalir、Bomin……
- **职务 + 人名**→ 只翻职务:`Guard Gornum` → "卫兵 Gornum",
  "Rhys, the Dwarven Emissary" → "矮人使节 Rhys"。
- 注意:NPC **文件名**一律保持英文(和 `npc` 字段一致),这条规则只管台词正文里出现的称呼。

### 物品名:通用词组成的翻译,自造专名和型号保留

标准样例是 **`Mithril Drill SX-R226` → "秘银钻头 SX-R226"**:材质名和品类词翻译,型号照抄。

| 原文 | 译名 | 为什么 |
|---|---|---|
| `Mineral Helmet` | 矿物头盔 | 全由通用词组成 |
| `King Talisman` | 国王护符 | 同上(Talisman 统一译"护符") |
| `Speckled Teacup` | 斑纹茶杯 | 同上 |
| `Royal Pigeon` | 皇家信鸽 | 同上 |
| `Powder Pie` | 粉末派 | 同上 |
| `Pure Mithril Gem` | 纯秘银宝石 | 同上,Mithril 用既定译名 |
| `Mithril-Plated Drill Engine` | 镀秘银钻头引擎 | 同上 |
| `Titanium Drill DR-X455` | 钛钻头 DR-X455 | 型号 `DR-X455` 照抄 |
| `Divan's Drill` | Divan 钻头 | `Divan` 是不翻译的人名,只翻品类词 |
| `Robotron Reflector` | Robotron 反射器 | `Robotron` 是自造词 |
| `Abiphone` / `Abiphone Basic` | 原样 | 整个词都是自造的品牌名 |
| `Silex` | 原样 | 自造物品专名 |
| `FTX 3070` | 原样 | 纯型号 |

判断顺序:先看这个词**是不是英语里本来就有的普通词**——是就翻,不是(自造词、人名、型号、
品牌)就保留;一个名字里两种都有时逐段处理,保留的部分和汉字之间的空格由引擎自动补
(写死在 `zh` 里的要自己写)。

**怪物名暂不适用这条**,目前一律保持英文(`Grubber`、`Thyst`、`Yog`、`Mithril Grubber`),
见 `_shared/Terms.json` 的 todo。

### 怪物名:和物品名同一套规则

2026-08-19 确认怪物名按物品名那套规则处理(通用词组成的翻译,自造专名保留):

- **翻译**: `Grubber` → 掘虫,`Mithril Grubber` → 秘银掘虫,`Golden Goblin` → 黄金哥布林,
  `Diamond Goblin` → 钻石哥布林,`Glacite Walker` → 极冰行者,`Treasure Hoarder` → 藏宝贼,
  `Star Sentry` → 星辰哨兵,`Automaton` → 机关傀儡,`Sludge` → 软泥怪,`Goblin` → 哥布林。
- **保留英文**: `Thyst`、`Yog`、`Zog`、`Boss Corleone` —— 都是自造词或专有角色名。
- 判断顺序和物品名完全一致:这个词是不是英语里本来就有的普通词。

### 带专有人名的地名:只翻通用词

- `Khazad-dûm` → **凯萨督姆**(托尔金《魔戒》矮人语地名,用中文版通行音译,不是意译)。
- `Mines of Divan` → **Divan 矿场**;`Divan's Gateway` → **Divan 之门**。
  `Divan` 本身是不翻译的专有人名(见 `Bulvar.json` 的 gloss),只翻它周围的通用词。

### 重铸名(Reforge)—— 按通用词规则翻译

重铸名会同时出现在重铸石的 Lore 里和物品名前缀上,必须全项目统一:

| 原文 | 译名 | 来源物品 |
|---|---|---|
| Fleet | 迅捷 | Diamonite |
| Auspicious | 吉兆 | Dwarven Geode |
| Jaded | 翠泽 | Jaderald(词根来自 Jade,译名保留"翠"字呼应) |
| Dimensional | 异次元 | Titanium Tesseract |
| Mithraic | 秘银之力 | Pure Mithril |
| Stellar | 星辉 | Petrified Starfall |
| Scraped | 刮蚀 | Pocket Iceberg |

**注意**:重铸名作为**物品名前缀**出现时(如 "Fleet Titanium Pickaxe"),目前引擎不会翻译
——物品名记录是整名匹配的,加了前缀就对不上。见 TODO.md。

### 技能名 Mining vs 属性前缀 Mining(两种译法并存,不是不一致)

- **技能等级、经验、活动**用"挖矿":`Mining Level V` → "挖矿等级 5 级",`Mining Skill menu` →
  "挖矿技能菜单",`Mining Experience` / `Mining Exp` → "挖矿经验",`Mining Events` → "挖矿活动"。
- **属性词条前缀**用"挖掘":`Mining Speed` → 挖掘速度,`Mining Fortune` → 挖掘时运,
  `Mining Spread` → 挖掘扩散,`Mining Wisdom` → 挖掘智慧。
- 这不是笔误,是有意的分工:属性词条是装备上的数值,读作"挖掘";技能体系和活动是玩法名,
  读作"挖矿"。两边各自内部一致即可,不要为了统一而互相改。
- 十二个技能名的译名在 `Hub_General/ActionBar/Common_HUD.json` 和
  `Mining/TabList/Tab_Widget_Enum.json` 里,`_shared/Terms.json` 还没抄全,见 TODO.md。
- 属性行里数值后面那个私用区图标字符**必须原样留在译文里**(`+100⸕ 挖掘速度`),
  引擎会把它换回服务器当场画的那个字形,见 `text/Glyphs.java`。

## 货币与经济

### 货币名: Coin(s) / Purse / Bits

- 出现场景: 几乎所有分类。
- **Coin(s) → "硬币"**(不是"金币")。此前 `Banker_Broadjaw.json`/`Gwendolyn.json` 一度
  误用"金币",已更正统一为"硬币"。
- **Purse → "硬币"**。Purse 指身上带着的那笔钱(和存在 Bank 里的分开),中文不另造"钱包"一词,
  计分板那一行直接写"硬币: 7,825,468"——玩家看这一行要的就是身上有多少钱。
  和 Coins 用同一个词不会混:银行那一行有自己的"银行"标签。
- **Bits → "点券"**。SkyBlock 的第二货币,在 Bits 商店(→"点券商店")兑换东西。
  不音译"比特"(会和计算机的 bit 撞车),也不保留英文。
  2026-08-26 由"点数"改为"点券":"点数"在中文里更像"点数值/几点",不像一种可以花的钱。
  注意 `Professor_Robot.json` 里的"有点数糊涂了"是"有点"+"数糊涂"的巧合,不是货币,不要跟着改。

### Cost / Price —— 「花费」和「价格」

- 出现场景: 菜单格子 Lore 里「要付出什么」那一节的小标题(NPC 商店、山之心天赋树、
  Gwendolyn 的通行证),以及集市里的挂单价格。
- **已确定译名**: `Cost` → **花费**,`Price` → **价格**。
- 理由: `Cost` 那一节下面列的不一定是钱——商店里不少东西是「低级物品 + 货币」一起换,
  「价格」只说得通其中一种,「花费」两种都涵盖。集市那种纯粹是钱换货的地方才叫「价格」。
- 备注: 这个标题在整个游戏里只写一份,放在 `_shared/Menu_Common.json#menu_cost_label`,
  各菜单用 `ref` 引用(2026-08-22 之前 Gwendolyn 和山之心各写了一条、译名还不一样,
  屏幕上永远只有排前面那个文件的那条生效)。

### Fame / Fame Rank —— 不是 Hypixel 付费 Rank

- 出现场景: `Hub_General/GUI_Item/Booster_Cookie.json`(声望等级格、点券倍率说明),
  社区商店 / Elizabeth / 市长选举票数相关文本。
- **已确定译名**: `Fame` → **声望**,`Fame Rank` → **声望等级**。同一格里的小写 rank
  (`Your rank:` / `Next rank:`)指的也是这个,一并译作「声望等级 / 下一等级」。
- 理由: Fame Rank 是社区商店的一条进阶阶梯,靠给城市项目捐献、在社区商店消费升级,
  决定点券倍率和市长选举票数。它和上面「Rank 一律不翻译」那条说的 Rank(Hypixel 付费购买的
  VIP/MVP 等级)是两回事,按 README.md 5.5「玩法体系名一律翻译」处理。
- **具体等级名(Ambassador、Senator、Paragon 等)暂不入 `Terms.json`,保持英文**。原因有二:
  一是这份 24 级名单尚未逐条在游戏内核对;二是其中的 `Minister` 会和已经定好的
  **市长选举里的 Minister →「部长」**(`Clerk_Seraphine.json`、`Mayor_Election.json`,
  Tab 列表还有 `Minister: Marina` 一行)撞车——`Terms.json` 是全局整值匹配,加进去就会
  让选举那一行也跟着改译,正是本项目禁止的「一个词两个意思」。要补这份名单的话,
  得先解决 Minister 的分歧,不能只补另外 23 个(同一条阶梯里一半中文一半英文更糟)。

### The Hex

- 出现场景: `Mining/GUI_Title/Reforge.json` 的重铸菜单标题 "The Hex ➤ Reforges"
- 含义: **已核实**——The Hex 是 Hub 岛博物馆(Museum)内 Hexatorum 房间里的一个
  重铸功能站(需要博物馆里程碑 8 解锁),通过 NPC The Handler 访问,是 Blacksmith
  之外的另一个重铸入口,不是人名。
- 备注: 按 GUI 标题一律翻译的规则,建议翻译(类似 The Forge 的处理方式),
  具体译名留给翻译阶段。

### Bazaar(集市功能 vs 同名 NPC 角色梗)

- 出现场景: `Mining/NPC_Message/Tal_Ker.json` 第二次对话独白(`tal_ker_second_059/060/061`)
- 含义: 这里的 "Bazaar" **不是**指游戏经济系统里的集市(Bazaar)交易功能,而是一个把自己
  改名叫 "Bazaar" 的 NPC 角色(台词原文明确说 "Bazaar isn't even his real name",是个玩笑梗)。
- 备注: 翻译时要把这个角色名和集市功能的译名(通常会译"集市")区分开,避免读者误以为
  Tal Ker 在说游戏机制。建议这个角色名保留英文 "Bazaar" 不翻译(当专有绰号处理),
  和集市功能本身翻译成"集市"分开决策,不要用同一个词。

## 属性词条与物品 Lore

### Fortune

- 出现场景: 各采集类玩法(Mining Fortune / Farming Fortune / Foraging Fortune),以及
  Dwarven Metal Fortune、Block Fortune 等同家族词条。
- 含义: 增加额外掉落数量的词条,不是"财富/运气"的字面意思。
- **已确定译名**: 统一译"时运"(如 Mining Fortune → 挖掘时运,Dwarven Metal Fortune →
  矮人金属时运),**必须在 Mining/Farming/Foraging 三个玩法下保持完全一致**,以后遇到
  Farming Fortune / Foraging Fortune / Block Fortune 等同家族词条直接套用"XX时运"命名,
  不用重新讨论要不要换词。2026-08-18 前一度用"财富",随后短暂用过"运势"作为译名(如
  `Mining/GUI_Item/Mithril_Drill_SX-R226.json`),现已全项目统一替换为"时运"。

### Block Fortune / Mining Fortune

- 出现场景: Mineral_Helmet/Chestplate/Leggings/Boots.json(Block Fortune)、各钻头/矿镐 Lore(Mining Fortune)
- 含义: 两个不同但相关的挖矿"额外掉落"词条,分别对应挖方块和挖掘速度/矿石维度的加成。
- **已确定译名**: 按 Fortune 词条家族统一原则(见本文件 "Fortune" 条目),Mining Fortune →
  挖掘时运,Block Fortune → 方块时运,两者靠"挖掘/方块"区分具体对象,靠"时运"体现同一家族。

### Breaking Power

- 出现场景: 所有挖矿工具(钻头/矿镐)Lore 第一行
- 含义: 工具能挖开某些矿石/方块的等级门槛,专有属性名。
- 备注: 不要直译成"破坏力量"(容易被误解为伤害),应体现"能挖开多硬的方块"这个含义。

### Mining Spread

- 出现场景: Mineral_Helmet/Chestplate/Leggings/Boots.json 套装加成效果说明、
  `Mining/TabList/Stats_Menu_And_Tablist.json` 属性标签
- **已查证**(hypixelskyblock.minecraft.wiki/w/Mining_Spread,2026-08-18):挖到方块/矿石/
  矮人金属时,有一定概率连带挖开相邻方块(数值≤100 表示百分比概率,>100 时前几位表示保底
  连带挖开的方块数,其余表示额外概率)。
- **已确定译名**: "挖掘扩散",沿用"挖矿X"系列命名习惯(挖掘速度/挖掘时运/挖掘智慧)。

### Tiered Bonus / Mineralworks

- 出现场景: Mineral_Helmet/Chestplate/Leggings/Boots.json 套装加成标题行
- 含义: "Tiered Bonus" 是通用套装机制词(按已穿戴件数分级生效的加成),"Mineralworks" 是 Mineral 套装这个加成效果的专有自造词名称。
- **已确定译名**: Tiered Bonus → "阶段加成"(套装机制通用词,以后其他套装遇到直接套用,
  不用重新讨论);Mineralworks → "矿物工坊"(仅 Mineral 套装专用的自造词译名,意译,
  取"-works"类似"XX工坊/XX厂"的构词方向)。四件套(头盔/胸甲/护腿/靴子)均已套用。

### Reforge / Soulbound / Accessory Power / Accessory Bag

- 出现场景: 几乎所有装备/饰品 Lore 的末尾通用行(如 "This item can be reforged!" "* Soulbound *" "Works while in Accessory Bag!" "Accessory Power: +N")
- 含义: 均为 SkyBlock 全局通用玩法机制专有词,不限于 Mining。
- 备注: 这些通用行在成百上千个物品文件里会重复出现,**强烈建议翻译阶段把这些行抽成一套共享片段库统一翻译**,不要在每个物品文件里各自翻译一遍导致不一致。分别建议的中文方向:
  Reforge=重铸,Soulbound=灵魂绑定,Accessory Power=饰品能力,Accessory Bag=饰品袋(具体定名留给翻译阶段)。

### Soulbound 标记行的三种变体

- 出现场景: `_shared/Item_Lore.json` 的 `soulbound_marker_allcaps`(护甲类,如
  Adaptive Boots,写法 "* SOULBOUND *" 全大写)、`item_soulbound`(饰品类,
  如 King Talisman,写法 "* Soulbound *" 首字母大写)、`item_co_op_soulbound`
  (写法 "* Co-op Soulbound *",目前见于 Fallen Star Helmet / Royal Compass / Royal Pigeon)。
- 含义: 前两个是同一个"灵魂绑定"机制的两种大小写/颜色呈现;第三个是**不同的机制**——
  **已查证**(hypixelskyblock.minecraft.wiki/w/Soulbound,2026-08-26):Co-op Soulbound
  绑定的是整个合作档案而不是某一个玩家,可以给合作队友、可以放进合作共用的私人岛屿箱子,
  只是不能上拍卖行/集市、不能交易给合作档案以外的人;普通 Soulbound 连岛上箱子都不让放。
- **已确定译名**: Soulbound / SOULBOUND = "灵魂绑定";Co-op Soulbound = "合作模式 - 灵魂绑定"。
  "Soulbound" 在三处共用同一译名,不存在一词两译;"Co-op" 在本项目统一译"合作",这里的
  "模式"是把中间的空格连字符读成范围限定("在合作模式下的灵魂绑定"),而不是"绑定给某位合作者"。
- 备注: 全大写/首字母大写并存的原因仍**待核实**,已分别保留共享条目不强行合并。

### 物品 Lore 里的固定词汇表

属性行(NEU 的精确原文里,数值前面还带一个私用区图标字符,译文必须原样保留):

| 原文 | 译名 | | 原文 | 译名 |
|---|---|---|---|---|
| Damage | 伤害 | | Mining Speed | 挖掘速度 |
| Defense | 防御力 | | Mining Fortune | 挖掘时运 |
| Health | 生命值 | | Mining Wisdom | 挖掘智慧 |
| Speed | 速度 | | Block Fortune | 方块时运 |
| Strength | 力量 | | Gemstone Fortune | 宝石时运 |
| Intelligence | 智力 | | Ore Fortune | 矿石时运 |
| True Defense | 真实防御 | | Dwarven Metal Fortune | 矮人金属时运 |
| Magic Find | 魔法寻宝 | | Mining Spread | 挖掘扩散 |
| Crit Damage | 暴击伤害 | | Gemstone Spread | 宝石扩散 |
| Pristine | 纯净 | | Heat / Cold Resistance | 抗热 / 抗寒 |
| Accessory Power | 饰品之力 | | Breaking Power | 开采等级 |
| Attack Speed | 攻击速度 | | Swing Range | 挥击范围 |
| Ability Damage | 技能伤害 | | | |

这四个属性名的各种排版(菜单里的「图标 属性名 数值」、Lore 里的「属性名: 数值 (加成)」、
增益里的「+N图标 属性名」)统一放在 `_shared/Stats.json`,不要在各菜单文件里各抄一遍。
后三条 2026-08-27 新增,查证过 wiki 的 `Infobox/Stat`:

- **Attack Speed** 提高的是每秒能砍几刀(上限 100),不是伤害,所以不译「攻速加成」。
- **Swing Range** 是近战武器能打到多远,数值就是格数(基础 3、上限 15),只影响剑和长剑,
  所以不译「攻击距离」——那个说法会被理解成包括远程武器。
- **Ability Damage** 指技能(Ability)造成的伤害加成,不是武器普攻。
- 挥击范围的图标 `U+E024` 没有对应的通用符号(它是 Hypixel 换用字形字体之后才加的属性),
  语料里只能原样写码点,这是 `text/Glyphs.java` 折算表之外的唯一一处例外,理由见该文件类注释。

物品类型标签(稀有度词见 `_shared/Rarity.json`,这里是跟在稀有度后面的类型):

镐 PICKAXE / 钻头 DRILL / 剑 SWORD / 护手 GAUNTLET / 长矛(见具体物品) /
头盔 HELMET / 胸甲 CHESTPLATE / 护腿 LEGGINGS / 靴子 BOOTS / 手套 GLOVES /
饰品 ACCESSORY / 重铸石 REFORGE STONE / 矮人金属 DWARVEN METAL / 能量石 POWER STONE /
部署物 DEPLOYABLE / 收纳袋 SACK / 凿子 CHISEL / 方块 BLOCK / 宠物道具 PET ITEM

其他反复出现的词:Orb Buff → 光球增益,Minion Upgrade → 小人升级,Quest Item → 任务物品,
Collection Item → 收藏品物品,Machine Fuel → 机器燃料,Brewing Ingredient → 酿造原料,
Combinable in Anvil → 可在铁砧中合并,Consumed on use → 使用后消耗,
Private Island → 私人岛屿,Mining Islands → 挖矿岛屿,Forge Timers → 熔炉计时。

### Titanium

- 出现场景: 矮人矿山材料/委托/天赋名多处(Titanium Miner、Titanium Insanium 等)。
- **已确定译名**: "钛"(不是"钛金")。此前 `Commissions.json`/`Sky_Mall_Bonuses.json` 一度
  误用"钛金",与已有的 "Titanium Insanium" → "疯钛" 不一致,已更正统一为"钛"。

### Fuel

- 出现场景: Mining / The Forge / 钻头(Drill)相关物品 Lore
- 含义: 钻头消耗的"燃料"资源,与"燃料桶"升级件相关。
- 备注: 语义单一,暂无歧义,先记录以便统计钻头类物品译名一致性。

## Mining 玩法专有词

### Commission(s)

- 出现场景: Dwarven Mines 委托系统(King 介绍语中出现 "complete commissions")
- 含义: SkyBlock 的"委托"任务系统,专有玩法名词。
- 备注: 不要直译成"佣金"(金钱佣金的意思),应统一译作"委托"。已在
  `Mining/NPC_Message/King.json` 的 `gloss` 字段标注。

### Collector(委托板"收集员"标签)

- 出现场景: `Mining/GUI_Item/Commissions.json` 的 "%s Collector" 行
- 含义: **已核实**——是委托板上的一种**委托任务类型名**(和 Mineshaft Explorer /
  Corpse Looter / Goblin Slayer 等并列),不是 NPC 头衔。`Commissions.json`
  已据此大幅扩充,收录了矮人矿山/水晶残核/冰河隧道三个区域已知的全部委托任务类型名。
- 备注: 按"功能性命名一律翻译"的原则(见本文件"山之心天赋名"条目)翻译。

### 冰河隧道矿物材料名(Glacite / Tungsten / Umber)

- 出现场景: 冰河隧道(Glacite Tunnels)/极冰矿井(Glacite Mineshafts)相关的粉末、地名、
  冰封尸体类型等,散见于 ScoreBoard/GUI_Item/ChatMessage 多个文件。
- **已确定译名**: Glacite → "极冰"(此前一度用"冰源",已全项目替换),Tungsten → "钨",
  Umber → "褐铁矿"。均按材料/矿物真实译名处理,不是不翻译的专有名词
  (此前 `Glacite_Mineshaft_Widget.json` 把 Tungsten/Umber Corpse 系列错误归为
  "专有材料/角色名不翻译" 一类,已更正为可翻译)。
- 备注: 冰封尸体四种类型里,Lapis(青金石)/Umber/Tungsten 均为可翻译的材料名,
  只有 Vanguard 按既有决定保留英文(见 `Heart_of_the_Mountain_Perks.json` 里
  "Vanguard Seeker" 天赋的处理)。翻译阶段如果要把 "Tungsten Corpse"/"Umber Corpse"
  这类复合词落到 `zh` 里,需要把目前的单一占位符模板拆成按类型分开的记录
  (现状是 4 种类型共用一个 `%s` 原样捕获,翻译无法只替换材料前缀而保留其余结构)。

### 冰封尸体的类型名 —— 和材料名不完全一样

- 出现场景: Tab 列表「冰封尸体」小节的四行,以及聊天里的 `%s CORPSE LOOT!` 横幅。
- 规则: 这几行说的是**尸体类型**,不是材料本身,所以译名以读得通为准,不照搬材料名。
  `Umber` 作为材料译「褐铁矿」,但「褐铁矿尸体」读起来像一具矿,所以尸体那一面写「褐铁」。
  `Lapis`(青金石)、`Tungsten`(钨)两个词本身就读得通,不用改;`Vanguard` 按既有决定保留英文。
- 备注: 词表里 `Umber Corpse` → 「褐铁尸体」,Tab 行的标签另有记录
  (`Mining/TabList/Tab_Widget_Enum.json` 里 `tab_row_frozen_corpse_*`)。

### 宝石品质等级(Gemstone Quality: Rough / Flawed / Fine / Perfect)

- 出现场景: `Mining/NPC_Message/Geo.json`(宝石系统入口 NPC 介绍台词),后续宝石相关
  文件(镶嵌界面、Lore 品质前缀等)会大量复用。
- **已确定译名**: Rough → "粗糙",Flawed → "有瑕",Fine → "精细",Perfect → "完美"。
- 备注: 这是宝石纯度/品质的固定四级词汇,后续遇到同一套词直接套用,不用重新决定。

### 宝石品质第五档 Flawless

- 已有四档是 Rough → 粗糙 / Flawed → 有瑕 / Fine → 精细 / Perfect → 完美(见"宝石品质等级"条目)。
- **Flawless → "无瑕"**(补充第五档,位于 Fine 和 Perfect 之间)。出现在 `Geo.json` 的
  Abiphone 排序小游戏台词里(`Flawless Ruby Gemstone` → "无瑕 Ruby 宝石")。

### HOTM Exp(缩写)

- 出现场景: 大量物品 Lore 里用缩写形式提及"山之心经验值"奖励(如 King Talisman 的
  `king_talisman_effect`:"Commissions grant +%s HOTM Exp.")。
- **已确定译名**: "山心经验",和 Heart of the Mountain 的既定简称"山心"(见"山之心天赋名"
  条目)保持一致,不展开成"山峦之心经验值"。后续遇到同一缩写直接套用,不用重新决定。

### 山之心天赋名(HotM Perk Names)/ 限时活动名 —— 已决定统一原则

- 出现场景: `Mining/GUI_Item/Heart_of_the_Mountain_Perks.json`(35 个天赋名如 "Mining Fortune"
  "Pickobulus" "Mole")、`Mining/BossBar/Mining_Events.json`、`Mining/ChatMessage/Mining_Events.json`、
  `Mining/ScoreBoard/Mining_Events_Widget.json`(Better Together / 2x Powder / Goblin Raid /
  Gone with the Wind / Mithril Gourmand / Raffle 等挖矿限时活动名)。
- 这类"功能性命名"(天赋名/技能名/限时活动名)**统一翻译**,不当作
  不翻译的专有名词处理(区别于 NPC 人名/物品自造专名)。这是一条**全项目统一原则**,
  以后 Farming/Foraging/... 等其他分类下同类的天赋树、技能、限时活动名都应套用同一原则
  ——遇到时不用再逐个重新讨论要不要翻译,只需要讨论具体怎么翻译。
  地名同样一律翻译(如 Glacite Mineshaft → 极冰矿井),不再和 NPC 人名/物品自造
  专名归为一类,详见 `README.md` 5.5。
- 已确定的具体译名(用户直接给出,后续翻译阶段直接采用,不用重新决定):
  - **Heart of the Mountain** → "山峦之心",简称"山心"。
  - **Pickobulus** → 维持翻译方向(具体中文措辞留给翻译阶段,不译作专有名词保留英文)。
  - **Goblin Raid** → "哥布林突袭",翻译阶段(`Mining/GUI_Item/Commissions.json` 的
    `mining_commission_task_goblin_raid` 等)已采用,后续遇到同一个词直接套用,不用重新决定。
- 备注: 山之心天赋树、限时活动列表目前的采集(`Heart_of_the_Mountain_Perks.json` 等)
  只覆盖了 Mining 出现的部分,采集阶段不需要因为这条原则回头重新采集,只是把
  `translate` 字段维持/确认为 `true` 即可。

### Token of the Mountain —— 已确定译名

- 出现场景: 山心界面的代币计数、重置山心的返还清单、奖励发放广播。
- **已确定译名**: "山心代币"(不是"山之心代币")。和 Heart of the Mountain 的简称"山心"配套,
  短一个字,在窄的界面行里更容易排下。

### 挖矿限时活动名 —— 已确定译名

BossBar 里是全大写(`GONE WITH THE WIND`)、计分板小部件里是正常大小写
(`Mining Event: Gone with the Wind`)、聊天横幅里又是全大写,中文没有大小写之分,
**一条词条同时管这三处**(译名统一写在 `_shared/Terms.json`,引擎查表不分大小写):

| 原文 | 译名 | 备注 |
|---|---|---|
| Gone with the Wind | 随风而逝 | 被动活动,《飘》的书名梗,顺风挖矿加速 |
| Better Together | 众志成城 | 被动活动,同区域人越多加成越高 |
| 2x Powder | 双倍粉末 | 被动活动 |
| Goblin Raid | 哥布林突袭 | 主动活动,沿用委托任务名的既定译法 |
| Mithril Gourmand | 秘银老饕 | 主动活动,给 Don Expresso 喂美味秘银 |
| Raffle | 抽奖箱 | 主动活动 |

### 镐子技能名(Pickaxe Ability)—— 已确定译名

技能名在聊天里由服务器塞进模板("You used your %s Pickaxe Ability!"),译名同样走
`_shared/Terms.json`,并且必须和山心天赋树里的名字一致:

| 原文 | 译名 |
|---|---|
| Mining Speed Boost | 挖掘速度爆发 |
| Pickobulus | 碎石飞弹 |
| Maniac Miner | 疯狂矿工 |
| Vein Seeker | 矿脉探寻 |
| Anomalous Desire | 异象渴求 |
| Gemstone Infusion | 宝石灌注 |

"Pickaxe Ability" 本身译"镐子技能"(不是"镐子能力"),和"技能冷却"这类说法配套。

### Drill Mechanic

- 出现场景: 几乎所有钻头及钻头配件(Fuel Tank/Drill Engine/Upgrade Module)Lore 里,提示玩家去哪里安装配件
- 含义: **已核实**——"Drill Mechanic" 是矮人矿山锻造炉(Forge Basin)专有 NPC
  Jotraeline Greatforge 的头衔/称号,不是泛称职业描述。
- 备注: 该头衔本身建议翻译(类似"钻头技师"这类头衔词,不是这位 NPC 的本名),
  但译法必须和 `Mining/NPC_Message/Jotraeline_Greatforge.json`(NPC 对话采集,进行中)
  里出现的同一称号保持一致,不要各自翻译出两个版本。已同步更新
  `Mining/GUI_Item/Mithril_Drill_SX-R226.json` 里对应条目的 gloss。

### 方块速查手册里的方块名

- 原版 Minecraft 方块用官方中文译名:Cobblestone → 圆石、Gravel → 沙砾、End Stone → 末地石、
  Mycelium → 菌丝、Nether Quartz Ore → 下界石英矿石。
- SkyBlock 自造方块按通用词翻译:Hard Stone → 硬石、Pure Coal → 纯净煤、Sulphur Ore → 硫磺矿石。
- 宝石方块保留宝石种类名:`Ruby Gemstone` → "Ruby 宝石"。
- Block Strength → "方块硬度",Breaking Power → "开采等级"(见上面的物品 Lore 词汇表)。

### 极冰狂人(Glacite Maniacs)—— 极冰矿井里的四种怪

- 出现场景: `Mining/TabList/Glacite_Mineshaft_Widget.json`(Tab 列表图鉴小节的等级行)、
  死亡广播、委托任务名 `Maniac Slayer`。
- **已查证**(hypixelskyblock.minecraft.wiki/w/Glacite_Maniacs,2026-08-21):这是极冰矿井
  生成时一起刷出来的一组怪(死了不重刷),共四种,各自有独立图鉴;委托 `Maniac Slayer`
  就是"随便打死其中 10 只"。
- **已确定译名**:

  | 原文 | 译名 | 行为 |
  |---|---|---|
  | Glacite Maniacs | 极冰狂人 | 四种的统称,和 `Maniac Slayer` → 狂人猎手 同一个"狂人" |
  | Glacite Bowman | 极冰弓手 | 远程射箭 |
  | Glacite Caver | 极冰穴居者 | 近战 |
  | Glacite Mage | 极冰法师 | 远程法术 |
  | Glacite Mutt | 极冰野狗 | 四个里唯一的动物类,冲上来近战 |

- 备注: `Caver` 取"穴居者"而不是"探洞者"——它是矿井深处待疯了的那一类,不是来探险的。
  `Mutt` 本义"杂种狗",中文取"野狗"避开粗口味。Tab 列表那一行是**图鉴等级**
  (`Glacite Mage 15: MAX`),不是怪物等级。

## Mining 地名与 NPC

### Crystal Hollows

- 出现场景: 大量文件(Commissions.json、Crystal_Hollows.json、多个 Emissary/NPC 文件等)。
- **已确定译名**: "水晶残核"。早期采集阶段个别文件(Lumina.json、Gwendolyn.json、
  Emissary_Sisko.json、Emissary_Braum.json)的 `note`/`context`/`gloss` 里混用过
  "水晶残洞",已统一更正为"水晶残核",和项目内多数已用译名保持一致。

### Hanging Court(矮人矿山地点名)

- 出现场景: `Mining/NPC_Message/Gwendolyn.json`(山峦之心7级剧情引导台词),
  `Station_Master.json` 的 todo 里也提到但未采集逐字到站播报。
- 含义: 连接 Royal Palace 与 Aristocrat Passage/Great Ice Wall 的地点,NPC Dulin 在此
  开放 Glacite Tunnels 入口。
- **已确定译名**: "悬廷"。按"地名也一律翻译"的原则(见"山之心天赋名"条目)处理。

### Lapis Quarry(深层洞窟地点名)

- 出现场景: `Mining/NPC_Message/Lift_Operator.json`
- **已确定译名**: "青金石采石场"。按"地名也一律翻译"的原则处理。

### Dwarven Mine Co.(矮人矿车公司名)

- 出现场景: `Mining/NPC_Message/Station_Master.json`
- **已确定译名**: "矮人矿业公司"。虚构矿车运营公司名,按"功能性命名一律翻译"原则处理。

### Don(矮人矿山 NPC 简称)

- 出现场景: `Mining/ScoreBoard/Mining_Sidebar.json` 的 `mining_sb_give_mithril_to_don` 行,以及 `Mining/NPC_Message/Don_Expresso.json`
- 含义: **已核实**——"Don" 就是矿区限时活动 Mithril Gourmand 中的 NPC "Don Expresso" 本人的简称。
- 备注: 两处的译名(简称"Don" vs 全名"Don Expresso")需要保持逻辑一致(如全名译
  "唐·特浓",简称可译"唐"之类,具体译法留给翻译阶段决定,但两处必须联动更新)。

### Bal(水晶残核专属宠物)

- 出现场景: `Mining/ChatMessage/Crystal_Hollows.json` 的全服宠物获得广播
- 含义: 水晶残核(Crystal Nucleus)玩法专属的一个宠物的专有名称。
- 备注: 属于宠物专有名,不翻译,和 NPC 名/物品名同一处理原则。

### Keeper of ___(水晶残核"看守者"NPC 系列)/ Professor Robot —— 已纠正一处数据错误

- 出现场景: `Mining/NPC_Message/Keeper_of_the_Crystal.json`、`Mining/NPC_Message/Professor_Robot.json`
- **重要纠正**: 早前采集把两个完全不同的 NPC 对话混进了同一个文件——真正的
  "Keeper of ___"(共 4 位:Keeper of Lapis/Gold/Diamond/Emerald,Mines of Divan
  翻找碎片拼图,对应 Jade Crystal)和 Precursor Remnants 的 **Professor Robot**
  (收集 6 种 Automaton Parts 机器人组件,对应 Sapphire Crystal)是两个不相关的 NPC,
  只是 SkyHanni-REPO 正则数据把两者的"缺失物品提示"句式弄混了(那几条提到
  "components"/"Broken Component"/"Wait a minute. This will work just fine." 的台词
  其实全部属于 Professor Robot,已拆分到 `Professor_Robot.json` 并用 Wiki 补全核实)。
  `Keeper_of_the_Crystal.json` 现在只保留真正属于 Keepers of Divan 的对话。
- 备注: 这是一个提醒——SkyHanni-REPO 的正则 key 命名/分组不一定和 NPC 身份严格对应,
  之后其他分类如果用同一数据源采集,遇到"缺失物品/凑齐道具"类模板化对话时,
  最好用 Wiki 交叉核实一下具体是哪个 NPC,避免类似串门发生。
- **已确定**: "XX Crystal" 这类水晶碎片材料名,"Crystal" 统一翻译成"水晶",前面的材料名
  (Jade/Sapphire/Topaz/Amber 等)保持英文不翻译,例如 Jade Crystal → "Jade 水晶"、
  Sapphire Crystal → "Sapphire 水晶"。和 `Crystal_Hollows.json` 里同类"XX Crystal"材料名
  处理原则一致。
- **拉丁字母和汉字之间必须留一个空格**("Jade 水晶",不是"Jade水晶")。2026-08-19 更正:
  本条目此前写的示例是没有空格的"Jade水晶",那是错的,已订正。凡是保留英文的专有名词
  紧挨着汉字,都要空格——这是中文排版规则,不是可选风格。运行时占位符捕获到的英文值
  由引擎自动补空格(见 `TranslationEntry.Seam`),但**写死在 `zh` 里的英文必须译者自己
  写上空格**,引擎不会去改译者已经定稿的字符串。

### "Keeper of ___" 里的材料名改为翻译

之前本文件写的是"称号里的材料名(Lapis/Gold/Diamond/Emerald)是专有名词不翻译"。
2026-08-19 物品名规则确定之后这条已作废:这些都是原版 Minecraft 材料名,按规则要翻译。
现在是 `Keeper of Lapis` → **青金石守护者**,其余三位同理(黄金/钻石/绿宝石守护者)。

## 跨玩法机制

### 公历月份名 —— 要翻,写成「2026 年 9 月」

- 出现场景: 宾果活动的三处菜单文字(箱子标题 `Bingo - September 2026`、宾果卡/宾果商店左下角的
  返回箭头、活动格子的灰色副标题 `September 2026`)。以后任何带真实年月的活动名同理。
- **月份要翻译**。2026-08-30 一度决定保留英文,理由是「现实日期一律不翻」,那是把两件事弄混了:
  侧边栏那行 `08/30/26 m9CQ` 之所以不收,是因为它整行只有数字和服务器号、**没有词**;
  `September` 是一个有通行中文写法的英文单词,留着就是中英混杂,2026-08-31 的实测采集也确实
  把它报进了 mixed 堆。
- **语序**: 中文写「2026 年 9 月」,年份在前。所以带月份的模板必须用带编号的占位符
  (`%1$s` 月 / `%2$s` 年),在 `zh` 里把两个换位。
- **译名只写月份**(`September` → 「9 月」),年份由记录自己排在前面。译名写在两个地方,
  **改一边必须改另一边**:
  - `_shared/Terms.json` —— 管占位符捕到的值(菜单标题、返回箭头)。限定 `category_name`,
    不给 `raw`:`May` / `March` 这类词在宽松的 raw 位置上撞车的代价太大。
  - `_shared/Months.json` —— 管**整行**只有月份年份的那种行。那一行做不成模板(去掉占位符后
    一个字母都不剩,引擎会拒),所以按月份拆成十二条,是 README 第 4 节允许的「拆标签」。

### 账号与存档升级名(Account & Profile Upgrades)—— 一律走词表,不写进模板

- 出现场景: 社区中心 Elizabeth 的升级菜单。同一批名字(Minion Slots、Guests Limit、
  Island Size、Ender Chest Pages、Heart of the Mountain……共 16 种)会被服务器塞进
  **四类**模板:根菜单的状态行(`Profile: %s %s (%s Hours)`)、升级历史行
  (`%s ago %s started %s %s`)、Tab 页脚的倒计时行、开始/领取升级的聊天广播。
- **规则**: 升级名一律做成 `category_name` 占位符,译名只写在 `_shared/Terms.json` 一处。
  2026-08-31 之前是每处把某一个名字写死进模板(状态行写死 Minion Slots、聊天写死
  Ender Chest Pages、确认页写死 Heart of the Mountain、Tab 页脚写死五个),后果有两个:
  换一种升级整行掉回英文;以及同一个升级在不同文件里译得不一样
  (`Ender Chest Pages` 在菜单里是「末影箱页面」、在 Tab 和聊天里是「末影箱页数」,现统一为**页面**)。
- **为什么用 `category_name` 而不是 `raw`**: 名字后面紧跟等级罗马数字,`tier` 把右边界钉死;
  `category_name` 走 `Capture.NAME`(最多 5 个词、两端大写或数字开头),
  「Heart of the Mountain I」这种中间夹小写词的名字照样整段捕到,而半句 Lore 进不来。
- 升级历史行里的「多久以前」用 `type: time`:引擎会把 `2m` / `4h` / `23d` 换成中文
  (见 `text/Capture.java` 的 `DURATION`),一条模板管所有单位。时长照项目既定写法**不加空格**
  (`2分前`、`35天`),这是全项目唯一不遵守「数字后面留空格」的地方,`checkTranslations` 的
  「多段时长中间不留空格」把它钉住了。

### 限时活动名 —— 一律翻译,写两个地方

- 出现场景: 「日历与活动」菜单的格子、计分板、Tab 列表、BossBar、活动开始/结束的聊天横幅。
- 规则: 按 §5.5「天赋名/技能名/限时活动名一律翻译」处理,不当专有名词。
  活动名里嵌的 **NPC 人名保留英文**(Jerry / Hoppity / Jacob / Udel),物品自造名同理(Abiphone)。
- **要写两份,而且必须一致**:
  - `_shared/Terms.json` —— 管占位符捕到的值(`%s STARTED!`、`Mining Event: %s`、BossBar 喊的名字)。
  - `_shared/Event_Names.json` —— 管菜单格子的**物品名**。词表对物品名不生效,引擎两条路不通用。
  `checkTranslations` 里的 `checkEventNamesAgree` 会拦住两边写歪的情况。
- 菜单里的活动名前面带**届数**(`88th Election Booth Opens`),占位符写 `type: ordinal`:
  渲染时后面两个字母会被去掉,中文写「第 %s 届……」。届数和后面的英文名之间要自己写一个空格
  (「第 3 届 Jerry 季」)。

### Garden Visitor(花园访客机制)

- 出现场景: 大量矮人矿山 NPC(Banker Broadjaw、Emissary 系列、Lumina 等)的部分台词标注为
  "The Garden"/"Completing"/"Denying" 等状态,内容却和银行、委托等本职无关。
- 含义: 这是 Farming 玩法“花园(Garden)”系统的“访客(Visitor)”机制——全岛各处的 NPC
  会随机作为“访客”出现在玩家自己的花园里,提出一个物品请求,完成/拒绝有不同台词,
  与该 NPC 本身在矮人矿山的职责无关,只是复用了同一个 NPC 模型和名字。
- 备注: 采集时如果看到某个 Mining NPC 的台词内容明显跳到别的主题(要某种蔬菜/物品),
  基本可以判断是 Garden Visitor 台词,应该归类到 Farming/NPC_Message 而不是 Mining
  (但因为是同一个 NPC 实体,建议在对应 Mining NPC 文件里保留一条 note 交叉引用,
  实际这段访客台词采集到 Farming 分类下的同名 NPC 文件里,避免同一段对话内容被
  分裂成两份还各自不完整)。本轮 Mining 采集里已经写入 Mining 文件的几条 Garden Visitor
  台词(如 Banker_Broadjaw.json 的 idle_1/2/3、Emissary 系列的 none/completing/denying)
  暂时先留在 Mining 文件内并加注说明,后续 Farming 阶段采集时再决定是否搬移/去重。

### Fairy Soul

- 出现场景: `Mining/NPC_Message/Tal_Ker.json`(首次对话独白第 96/98 句)
- **已确定译名**: "仙女之魂"(不是"妖精之魂",已订正)。SkyBlock 全图收集要素专有名词,
  后续任何分类遇到 Fairy Soul 都直接套用这个译名。

### Loadouts / Loadout —— 已确定译名

- `Loadouts` → **预设栏**(空岛菜单入口名、箱子标题 `(1/3) Loadouts`),
  `Loadout N` → **预设 N**(栏位里每一份)。
- 一格「预设栏」里装着若干份「预设」,和「仓库」「收纳袋」同属收纳类入口命名。
- 不译「装备预设」:一份预设除护甲/装备外还包含山峦之心、森林之心等设置,Lore 原文即
  "with other settings"。
- `Equipment Loadouts` **查无实据**:语料、运行时采集、Wiki 全文检索(`insource:"Equipment Loadouts"`,
  0 命中)都没有。`SkyBlock_Menu.json` 暂留一条译作「装备预设栏」兜底,确认不存在即可整条删掉。
- 已知未翻译: 箱子标题 `(1/3) Loadouts` 和格子名 `Loadout %1$s` 目前全无记录,整个预设界面还是英文。

### Bingo(宾果活动)—— 已确定译名

- 出现场景: 村庄 Bingo NPC 的三个菜单(`Hub_General/GUI_Item/Bingo_Event.json`、
  `Bingo_Card.json`、`Bingo_Shop.json`)与对应箱子标题(`Hub_General/GUI_Title/Bingo.json`)。
- **已确定译名**(按上面「限时活动名一律翻译」处理,`Bingo` → **宾果**已在
  `_shared/Event_Names.json` 与 `_shared/Terms.json` 两边写过,新增的都沿用它):

  | 原文 | 译名 | 说明 |
  |---|---|---|
  | `Bingo Card` | 宾果卡 | 五乘五的目标卡面 |
  | `Bingo Shop` | 宾果商店 | 花宾果点数的商店 |
  | `Bingo Points` | 宾果点数 | **不叫「点券」**,那是 Bits;宾果点数只在宾果商店里花 |
  | `Bingo Rank` | 宾果等级 | 共 I–IV 四级,前缀符号 `Ⓑ`(U+24B7)照抄 |
  | `Personal Goal` / `Community Goal` | 个人目标 / 社区目标 | 分类词走 `Terms.json` 的 `category_name` |
  | `Bingo Talisman` / `Ring` / `Artifact` / `Relic` | 宾果护符 / 戒指 / 神器 / 遗物 | 四级饰品链,沿用钛护符一族的既定译法 |
  | `Bingo Display` / `Collection Display` | 宾果展示牌 / 收藏品展示牌 | 可放置的装饰品 |
  | `Book of Stats` | 统计之书 | 这里的 Stats 是「统计数据」(计数器),**不是**力量/时运那种属性,所以不译「属性之书」 |
  | `Spring Boots` | 弹簧靴 | Spring 是「弹簧」不是「春天」:技能靠潜行蓄力弹跳 |
  | `Ditto Skull` / `Ditto Skin` | 仿制头颅 / 仿制皮肤 | `Ditto` 沿用点券商店 `Ditto Blob` → 仿制黏团 |
  | `Grappling Hook` | 抓钩 | 全由通用词组成,宾果等级 II 的赠品,语料首次出现 |
  | `Alixer` | 原样 | Hypixel 自造的 NPC/装置名,不译 |

- **卡面上的目标名每月一换**,不是固定语料,按当期卡面收。2026 年 9 月(第 58 期)的
  两个词值得记下来,以后再遇到直接套用:
  - `XX Collector`(收藏品类个人目标名)→ **XX 收集员**,沿用委托任务名那一条的既定译名。
    **不要**译「收藏家」——那个词已经给了 `Pet Hoarder` →「宠物收藏家」,两个英文词各留各的译名。
  - `Skilled`(社区目标名,条件是练技能拿经验)→ **技艺精湛**。词表里限定 `raw`。

- **不要做成 `Bingo %s` 模板**。采集器两次都把这一族推断成了那个模板,但 `Bingo` 后面
  跟的词各有各的译名(卡 / 商店 / 点数 / 等级 / 护符 / 活动),一个模板套下来必出中英混排;
  而且 raw 型占位符会把别处任何以 Bingo 开头的半句话吃进来(同第 4 节的 `Your %s` 事故)。
  一律写死成字面记录。
- `Scavenger` 作为宾果社区目标名出现时**保持英文**:它同时是空岛专属附魔名,
  按用户 2026-08-27 的决定那一族不翻译(见 `_shared/Enchantments.json` 的 scope)。
  给它加词表条目会让这个名字在附魔语境里也变中文,两边就对不上了。

### Chum / Bait —— 两种不同的物品,不能都叫「鱼饵」

- 出现场景: `Hub_General/GUI_Item/Bingo_Card.json#bingo_goal_deposit_chum`(宾果卡目标)、
  `_shared/Terms.json` 的 `Bait` 词条、`Hub_General/GUI_Item/Fishing_Merchant.json`。
- **已查证**(hypixelskyblock.minecraft.wiki/w/Chum,2026-08-30):`Chum` 是在放好的
  Chum Bucket 旁击杀海洋生物掉落的材料,拿去存进桶里换硬币和钓鱼经验,还能合成满桶;
  `Bait` 是挂在鱼竿上、提高钓鱼效果的另一类物品。两者机制不同,不是同一件东西。
- **已确定译名**: `Chum` → **碎鱼饵**,`Chum Bucket` → **碎鱼饵桶**,`Bait` → **鱼饵**。
  两个都译「鱼饵」会让宾果卡上那一格读成「向鱼饵桶中存入鱼饵」,看不出存的是什么。
- **未统一的遗留**: `Bait` 在语料里有两种写法——`Fishing_Merchant.json` 用「鱼饵」
  (`Fishing Bait`、`COMMON BAIT` → 普通鱼饵、`Minnow Bait` → 小鱼饵),
  而 `Farming/TabList/Tab_List.json#no_bait_none` 用「诱饵」。词表取了占多数的「鱼饵」,
  但那一处还没改,做 Farming 玩法时一并收拾。

### Pity / pity counter —— 已确定译名

- 出现场景: `Mining/GUI_Item/Glacite_Tunnels_Pity.json`(极冰隧道保底进度物品)。同一机制还用在
  RNG 计量表和巧克力工厂,后续遇到直接套用,不用重新讨论。
- **已查证**(hypixelskyblock.minecraft.wiki/w/Glacite_Mineshafts,2026-08-28):计数从 2,000 开始,
  按所挖方块的 Block Quality 递减,到 0 时下一个方块必定刷出极冰矿井;但 Tab 列表和保底菜单里
  显示的是反过来的读数(从 0 涨到 2,000)。硬石算 0 点,所以"挖 2,000 个方块"这个说法只是原文
  自己的粗略讲法。
- **已确定译名**: `pity` / `pity counter` / 同一处语境里的 `value` 一律作 **保底进度**。
  原文对同一个数用了三个词,中文统一成一个,比原文更一致;`Progress:` 那一行是进度条读数,
  只作"进度",不重复"保底"二字。
- 不用"吉兆":`Auspicious` 已经占了这个词(见本文件重铸前缀一节)。

### 技能菜单的每级天赋名 —— 已确定译名

- 出现场景: 空岛菜单 → 技能 → 各技能子菜单(`Hub_General/GUI_Item/Your_Skills.json`),每级奖励的第一项。
- 按 §5.5「天赋名一律翻译」:Warrior → 战士,Farmhand → 农场帮手,Treasure Hunter → 寻宝猎人,
  Spelunker → 洞穴探险家,Logger → 伐木工,Conjurer → 咒术师,Brewer → 酿造师,Zoologist → 动物学家,
  Charming → 魅惑(狩猎技能的 Charm Chance 译「魅惑概率」,同一个词)。
- 同一菜单里 `Slayer` → 猎手(沿用委托 Goblin Slayer → 哥布林猎手),`Bestiary` → 生物图鉴,
  `Power Stone` → 能力石(沿用 Bazaar.json;本文件物品类型表里的「能量石」是老写法,以能力石为准)。
- 十二个技能名本身沿用动作栏经验条(`Common_HUD.json`)的译名:战斗 / 农业 / 钓鱼 / 挖矿 / 伐木 / 附魔 /
  炼药 / 木工 / 符文制作 / 驯养 / 社交 / 狩猎;等级写「战斗 XXVI 级」,罗马数字照抄。

### 银行账户档次(Bank Account Upgrades)—— 已确定译名

- 出现场景: `Economy/GUI_Item/Bank.json`,升级菜单每档的格子名 `%s Account`,取值走 `_shared/Terms.json`。
- Starter → 入门,Gold → 黄金,Deluxe → 豪华,Super Deluxe → 超级豪华,Premier → 尊享,Luxurious → 奢华,Palatial → 殿堂。
- 银行里的 `Million` / `Billion` 作 raw 值译「百万 / 十亿」(`Balance limit: 250 Million` → 「余额上限: 250 百万」),
  利率档位里带 M/B 后缀的金额(`10M`)照抄不动。`Personal Vault` → 个人保险箱。

### 末地入门任务与饰品能力 —— 2026-09-05 日志补译

- 社区 Wiki 语境来源: `Lone_Adventurer`、`Pearl_Dealer`、`Dragon%27s_Nest`、
  `End_Stone_Protector`、`Accessory_Powers/List_of_Accessory_Powers`。
- **Void Sword → 虚空之剑**、**Ender Armor → 末影护甲**。均由普通词组成,不属于品牌或型号。
  独行冒险者要求的 **8 pieces of Ender Armor** 实际包括四件护甲和四件装备,
  该任务整句用 **末影套装**; 不能写成八个护甲槽。NPC 姓名/品牌标识依然保留英文。
- **Lone Adventurer → 独行冒险者**、**Pearl Dealer → 珍珠商人**是职务型称呼;
  本轮新增台词正文遵循该规则。结构化 NPC 标签和 `npc_name` 捕获值仍遵循引擎的原名保留策略。
- **Dragon's Nest → 龙巢**。它在末地,不是水晶残核的 **Dragon's Lair**。
- **End Stone Protector → 末地石守护者**。Wiki 历史记载 2025-09-02 从
  `Endstone Protector` 改名,但日志的出现警报仍采用旧拼写; 不要擅自改写警报的 `text`。
- **Zealot → 狂信徒**,沿用 `Taming/GUI_Item/Pet_Perks.json` 中的既有译名,不另译成狂热者。
- **Fortuitous → 幸运**,是默认解锁的饰品袋能力,不是附魔或 NPC 人名。
  词表限定 `category_name`,不将这个英文词泛化成对任意正文的替换。

## 剧情线与彩蛋

### 陨落之星教团(Cult of the Fallen Star)剧情线固定译名

- `Cult of the Fallen Star` → 陨落之星教团;`Fallen Star` → 陨落之星;
  `Fallen Star Helmet` → 陨落之星头盔;`Fallen Star Lozenge` → 陨落之星糖锭。
- 剧情横跨 6 次例会,说话人是 Dalir / Brarnas / Thondin 三人,台词分别在三个文件里,
  但**是一段连续对话**,翻译时必须交叉对照(一个人的"又来了?"接的是另一个人的上一句)。
- 贯穿全线的梗要保持一致:打碎的盘子、丢猫、过夜聚会、"门多还是轮子多"、
  最后推导出"我们活在**模拟**里"(`simulation` 统一译"模拟",不要译成"仿真/虚拟世界")。
- `Royal_Resident.json` 结尾也有一段模拟论,和这条线呼应,用同一个词。

### Tal Ker 独白里的地名/世界观词汇(已翻译完整两段独白,共 283 句)

- 出现场景: `Mining/NPC_Message/Tal_Ker.json`
- **已确定译名**:
  - Sky Academy → "天空学院"(功能性机构名,首次出现,后续遇到同名要沿用)。
  - The Calamity → "大灾变"(SkyBlock 世界观里导致大陆碎裂成群岛的灾变事件专有名词,首次出现)。
  - Golden Mines → "黄金矿场"、Crimson Isle → "绯红岛"(地名,首次出现,均为暂定译名,
    后续 Nether/Combat 等分类如果重新采集到这两个地点,需要核对沿用这里的译法)。
  - dragon heart(末影龙之心)→ "末影龙之心",End island → "末地"/"末地岛",均为
    《我的世界》原版标准译名,直接沿用不重新发明。
  - Talcoins(Tal Ker 自创的玩笑货币名,由他自己的简称 "Tal" 构成)→ 保留英文
    "Talcoins" 不翻译,和 "Tal"/"TK" 本人简称专有名词不翻译的原则一致。

### Tal Ker 独白里的其他一次性梗/彩蛋(仅记录,不需要跨文件统一)

- 出现场景: `Mining/NPC_Message/Tal_Ker.json`
- 含义: "Crappox"(疑似谐音 "Maddox"管理员的玩笑称呼)、"Doubt [x]"(游戏梗,类似"按X表示怀疑"
  的调侃,原文就是这样写的)、"stonks"(刻意错拼的 "stocks",网络迷因拼法)、
  "That Warren guy"(疑似玩笑指代现实投资界人物)。
- 备注: 这些都是 Tal Ker 这一个 NPC 独白里的一次性文字梗,不会在其他文件重复出现,
  不需要全局统一译名,已在该文件对应条目的 `gloss` 字段里单独标注,翻译时保留玩笑/梗的语气
  而不是直译或纠正"错误拼写"。

## 语料库建设记录

### 共享片段库 `_shared/` 建设情况(Mining 完成)

- 出现场景: `_shared/Rarity.json`(10 个稀有度词,已确认统一含 §l 粗体前缀)、
  `_shared/Item_Lore.json`(重铸提示/灵魂绑定/饰品能力/饰品袋提示/钻头配件
  三大类共享行等)。
- 备注: Mining/GUI_Item/ 下所有钻头(6 个)、钻头配件(4 个,含新补的 Titanium-Plated
  Drill Engine)、Mineral Armor 全套(4 件)、King Talisman 均已改用 `ref` 引用共享库,
  不再各自重复整段翻译。稀有度+物品类型复合行(如 "RARE DRILL")因为是 Lore 数组里
  混合了共享词和物品专属类型标签的单独一行,不适合拆成 ref,改用 `rarity_ref` 字段
  指向 `_shared/Rarity.json` 做一致性核对,这是本项目对"复合行"的标准处理方式,
  其他玩法分类遇到同类复合行时应遵循同一约定。原先散落在
  `Mining/GUI_Item/_Shared_Item_Lore_Fragments.json` 的内容已并入 `_shared/Item_Lore.json`
  (该共享文件早期叫 `Item_Boilerplate.json`,现已改名),该本地文件已删除。
