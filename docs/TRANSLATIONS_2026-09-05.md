# 2026-09-05 日志补译清单

本清单覆盖本次任务及中断后的续做,以开始任务时的工作区副本为基线,不计入此前已有的未提交改动。

- 新增 **142 条翻译记录**、**1 条既有译文引用**、**8 条术语**。
- 修正／推广 **3 条既有记录**（Jotraeline 手机高亮、Pat 的沙砾译名、属性小部件启用区域）。
- 范围是 `logs/latest.log` 的聊天和 NPC 文本。日志仅提到采集数量的 GUI/Lore 等没有原始 JSON,本次不臆测补录。
- 文本以日志为准,社区 Wiki 普通页面用于查证语境; 未使用官方 Wiki 或 Raw 页面。
- 动态商品名、玩家名、NPC 人名和品牌按引擎既定规则保留。下表的 `%s` 等为模板,中英接缝空格由引擎补齐。
- 运行时引擎未改动。`TranslationHarness.java` 修正 `!]` 的间距误报,并新增颜色、语序、点击事件、字形、参数与不误译玩家名的回归测试。
- Minecraft 26.1 与 26.2 的翻译检查均为 **475 通过 / 0 失败**,采集检查均为 **233 通过 / 0 失败**; 术语交叉检查通过。
- 同一日志审计: **530 → 829** 条聊天命中,未覆盖种类 **312 → 95**。这里包含其他 Mod、大厅信息和分隔线,不是整个游戏的翻译覆盖率。

## 新增翻译

### `original_text/Combat/ChatMessage/The_End.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 战斗／末地 · The End | Fight Endermen and Endermites. | 击败末影人和末影螨。 |
| 战斗／末地 · The End | Mine End Stone and Obsidian. | 开采末地石和黑曜石。 |
| 战斗／末地 · The End | Travel to the Dragon's Nest. | 前往龙巢。 |
| 战斗／末地 · The End | Summon the Ender Dragon! | 召唤末影龙! |
| 战斗／末地 · The End | Fight Zealots. | 击败狂信徒。 |
| 战斗／末地 · The End | You feel a tremor from beneath the earth! | 你感觉到地下传来一阵震动! |
| 战斗／末地 · The End | The ground begins to shake as an End Stone Protector rises from below! | 地面开始震动,末地石守护者正从地下升起! |
| 战斗／末地 · The End | BEWARE - An Endstone Protector has risen! | 小心 - 末地石守护者已经出现! |
| 战斗／末地 · The End | END STONE PROTECTOR DOWN! | 末地石守护者已被击败! |
| 战斗／末地 · The End | %s dealt the final blow. | %s完成了最后一击。 |
| 战斗／末地 · The End | %1$s Damager - %2$s - %3$s | 伤害第 %1$s 名 - %2$s - %3$s |
| 战斗／末地 · The End | %1$s %2$s dealt the final blow. | %1$s %2$s完成了最后一击。 |
| 战斗／末地 · The End | %1$s Damager - %2$s %3$s - %4$s | 伤害第 %1$s 名 - %2$s %3$s - %4$s |
| 战斗／末地 · The End | Your Damage: %1$s (Position #%2$s) | 你的伤害: %1$s (第 %2$s 名) |
| 战斗／末地 · The End | Zealots Contributed: %1$s/%2$s | 狂信徒击杀贡献: %1$s/%2$s |
| 战斗／末地 · The End | Kill nearby Endermen | 击杀附近的末影人 |
| 战斗／末地 · The End | Find Full Ender Set then talk to %s | 收齐末影套装后与%s交谈 |
| 战斗／末地 · The End | Reach the Dragon's Nest | 抵达龙巢 |
| 战斗／末地 · The End | Fight a Dragon | 挑战末影龙 |
| 战斗／末地 · The End | Be careful! Using Ender Pearls on this island will anger nearby Endermen! | 小心! 在这座岛上使用末影珍珠会激怒附近的末影人! |
| 战斗／末地 · The End | BE CAREFUL! You're below the recommended Combat Level for this zone! | 小心! 你的战斗等级低于此区域的建议等级! |
| 战斗／末地 · The End | The recommended level to enter %1$s is %2$s. You are Combat Level %3$s. | 进入%1$s的建议战斗等级为 %2$s 级,你目前为 %3$s 级。 |
| 战斗／末地 · The End | End Biome Stick Recipe | 末地生物群系棒配方 |

### `original_text/Combat/NPC_Message/Lone_Adventurer.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 战斗／末地 · Lone Adventurer | Exhausted? me? No no no. | 累了? 我? 不不不。 |
| 战斗／末地 · Lone Adventurer | I'm just taking a break. | 我只是在歇会儿。 |
| 战斗／末地 · Lone Adventurer | The End is a creepy place, but you get used to it! | 末地是有点瘆人,不过待久了就习惯了! |
| 战斗／末地 · Lone Adventurer | If you want a piece of advice, you should start by killing the Endermen up here. | 要我给点建议的话,你应该先练练手,去杀这上面的末影人。 |
| 战斗／末地 · Lone Adventurer | They sometimes drop important gear like the armor I'm wearing. | 它们有时会掉落很有用的装备,比如我身上这套护甲。 |
| 战斗／末地 · Lone Adventurer | Are you strong enough though? | 不过,你的实力够吗? |
| 战斗／末地 · Lone Adventurer | Try killing %s of them! | 先试着击杀 %s 只吧! |
| 战斗／末地 · Lone Adventurer | Alright, not bad, not bad! | 行啊,不错不错! |
| 战斗／末地 · Lone Adventurer | It took me a while to get that strong. | 我当初可是练了好一阵才有这身手。 |
| 战斗／末地 · Lone Adventurer | I use a Void Sword, it's a very powerful weapon. | 我用的是虚空之剑,这武器可厉害了。 |
| 战斗／末地 · Lone Adventurer | It gets stronger with each piece of Ender Armor you are wearing. | 你身上的末影护甲每多一件,它的威力就更强一分。 |
| 战斗／末地 · Lone Adventurer | I have an extremely strong emotional attachment to this item so... | 我对这把剑可是感情深厚,所以嘛…… |
| 战斗／末地 · Lone Adventurer | I'm willing to sell it to you for the modest sum of %s coins. | 我愿意卖给你,只收你区区 %s 硬币。 |
| 战斗／末地 · Lone Adventurer | What do you say? | 你意下如何? |
| 战斗／末地 · Lone Adventurer | I think you are making a mistake, perhaps the biggest mistake of your life! | 我觉得你这决定可不明智,说不定会后悔一辈子! |
| 战斗／末地 · Lone Adventurer | Are you sure you don't want that sword? | 你确定不要这把剑? |
| 战斗／末地 · Lone Adventurer | Happy dealing with you, I hope this sword will help you as much as it helped me. | 成交,合作愉快! 希望这把剑也能像当初帮我一样帮到你。 |
| 战斗／末地 · Lone Adventurer | You should focus on getting all %s pieces of the Ender Armor now. | 接下来,你该专心收齐全套 %s 件末影套装了。 |
| 战斗／末地 · Lone Adventurer | Half of them are dropped by the Endermen on this layer, the other pieces are a little harder to obtain. | 其中一半会由这一层的末影人掉落,其余几件就没那么容易找了。 |
| 战斗／末地 · Lone Adventurer | I'm sure you'll figure it out. | 我相信你会找到办法的。 |
| 战斗／末地 · Lone Adventurer | My time here is over, I think I'll go down in the Dragon's Nest very soon. | 我在这儿待得差不多了,应该很快就会下到龙巢去。 |
| 战斗／末地 · Lone Adventurer | See you around! | 回头见! |

### `original_text/Combat/NPC_Message/Maddox.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 战斗／猎杀任务 · Maddox | Do you thirst for adventure? | 想出去冒个险吗? |
| 战斗／猎杀任务 · Maddox | Do you wish to slay the mightiest beasts of the land? | 要不要试着打倒这里最厉害的怪物? |
| 战斗／猎杀任务 · Maddox | Complete Slayer Quests and fight bosses to unlock exotic rewards! | 来做做猎杀任务吧! 挑战 Boss,还能解锁些稀罕的奖励。 |

同日按用户反馈将这三句润色为搭话式口吻,避免招募宣言和说明书腔; 英文匹配文本、任务名、解锁奖励的含义及颜色边界不变。

### `original_text/Combat/NPC_Message/Pearl_Dealer.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 战斗／末地 · Pearl Dealer | You have reached The End, though this is only the beginning. | 你虽已抵达末地,但这还只是个开始。 |
| 战斗／末地 · Pearl Dealer | I am the Pearl Dealer, and you are on dangerous ground. | 我是珍珠商人,你脚下这片土地可不太平。 |
| 战斗／末地 · Pearl Dealer | Be careful when using Ender Pearls on this island, their energy attracts Endermen! | 在这座岛上使用末影珍珠时要小心,它的能量会招来末影人! |
| 战斗／末地 · Pearl Dealer | The End also has many resources, including End Stone and Obsidian. | 末地也有丰富的资源,包括末地石和黑曜石。 |
| 战斗／末地 · Pearl Dealer | The deeper you go, the stranger the things you'll find! | 越往深处走,遇见的东西就越稀奇! |

### `original_text/Economy/ChatMessage/NPC_Shops.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 经济 · NPC Shops | You bought %1$s x%2$s for %3$s Coins! | 你购买了%1$s ×%2$s,金额为 %3$s 硬币! |
| 经济 · NPC Shops | You sold %1$s x%2$s for %3$s Coins! | 你出售了%1$s ×%2$s,金额为 %3$s 硬币! |
| 经济 · NPC Shops | You bought back %1$s x%2$s for %3$s Coins! | 你回购了%1$s ×%2$s,金额为 %3$s 硬币! |
| 经济 · NPC Shops | You sold ◆ %1$s Rune %2$s x%3$s for %4$s Coins! | 你出售了 ◆ %1$s 符文 %2$s ×%3$s,金额为 %4$s 硬币! |
| 经济 · NPC Shops | You don't have enough Coins! | 你的硬币不足! |
| 经济 · NPC Shops | You don't have enough Gems! | 你的 Gems 不足! |
| 经济 · NPC Shops | That item cannot be sold! | 这件物品无法出售! |

### `original_text/Economy/ChatMessage/Offline_Interest.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 经济 · Offline Interest | Since you've been away you earned %s coins as interest in your personal bank account! | 你离线期间,个人银行账户获得了 %s 硬币利息! |

### `original_text/Farming/NPC_Message/Jacob.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 农业 · Jacob | My contest has started! | 我的农业竞赛开始了! |

### `original_text/Fishing/ChatMessage/Fishing_Outpost.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 钓鱼 · Fishing Outpost | Buy fishing essentials from the Fishing Merchant. | 向钓鱼商人购买钓鱼必需品。 |
| 钓鱼 · Fishing Outpost | Talk to Fisherman Gerald and Captain Baha about your Ship. | 与渔夫 Gerald 和船长 Baha 交谈,了解你的船。 |
| 钓鱼 · Fishing Outpost | Learn about Fishing stats from Gwynnie. | 向 Gwynnie 了解钓鱼属性。 |

### `original_text/Hub_General/ChatMessage/Accessory_Powers.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · Accessory Powers | You selected the %s power for your Accessory Bag! | 你为饰品袋选择了%s能力! |
| 大厅 · Accessory Powers | You cannot put this item in the Accessory Bag! | 这件物品无法放入饰品袋! |

### `original_text/Hub_General/ChatMessage/Area_Discovery.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · Area Discovery | ⏣ %s | ⏣ %s |
| 大厅 · Area Discovery | ⏣ Abiphones & Co. | ⏣ Abiphone 公司 |
| 大厅 · Area Discovery | Learn about Abiphones from Alda. | 向 Alda 了解 Abiphone。 |
| 大厅 · Area Discovery | Purchase your first Abiphone! | 购买你的第一台 Abiphone! |
| 大厅 · Area Discovery | Talk to the Wizard. | 与巫师交谈。 |
| 大厅 · Area Discovery | Use the Wizard Portal. | 使用巫师传送门。 |
| 大厅 · Area Discovery | Talk to %s | 与%s交谈 |
| 大厅 · Area Discovery | Talk to %s Again | 再次与%s交谈 |
| 大厅 · Area Discovery | Talk to Emissary %s | 与使节 %s 交谈 |

### `original_text/Hub_General/ChatMessage/NPC_Options.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · NPC Options | Select an option: [What's an Abiphone?]  | 请选择: [Abiphone 是什么?]  |
| 大厅 · NPC Options | Select an option: [I guess.]  | 请选择: [算是吧。]  |
| 大厅 · NPC Options | Select an option: [Ok, then what?]  | 请选择: [好,然后呢?]  |
| 大厅 · NPC Options | Select an option: [Accessory Power?]  | 请选择: [饰品之力?]  |
| 大厅 · NPC Options | Select an option: [That's amazing!]  | 请选择: [这也太神奇了!]  |
| 大厅 · NPC Options | Select an option: [I am sure] [Fine I'll buy your sword]  | 请选择: [我确定] [好吧,我买你的剑]  |
| 大厅 · NPC Options | [GIVE ABIPHONE] | [交出 Abiphone] |

### `original_text/Hub_General/ChatMessage/Storage_and_Profiles.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · Storage and Profiles | One or more items didn't fit in your inventory and were added to your material stash! Click here to pick them up! | 背包放不下的物品已存入材料暂存区! 点此领取! |
| 大厅 · Storage and Profiles | Your inventory does not have enough free space to add all items! | 背包剩余空间不足,放不下所有物品! |
| 大厅 · Storage and Profiles | You need the Cookie Buff to use this feature! | 需要曲奇增益才能使用此功能! |
| 大厅 · Storage and Profiles | Obtain a Booster Cookie from the community shop in the hub! | 前往大厅的社区商店获取增益曲奇! |
| 大厅 · Storage and Profiles | You cannot access the Museum on a Bingo Profile! | 宾果档案无法使用博物馆! |
| 大厅 · Storage and Profiles | Switching to profile %s... | 正在切换至档案 %s…… |
| 大厅 · Storage and Profiles | Your profile was changed to: %s (Co-op) | 已切换至档案: %s (合作) |
| 大厅 · Storage and Profiles | [✆] You cannot add this NPC as a contact... | [✆] 无法将这位 NPC 添加为联系人…… |

### `original_text/Hub_General/NPC_Message/Alixer.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · Alixer | I'm here to help you with Bingo! | 我来助你完成宾果挑战! |
| 大厅 · Alixer | Come to me if you want some Potion Effects to help you in your journey! | 冒险时想要药水效果助你一臂之力,就来找我! |
| 大厅 · Alixer | I can give you better effects depending on your Bingo Level. | 你的宾果等级越高,我能提供的药水效果就越好。 |
| 大厅 · Alixer | Splish... | 哗啦…… |
| 大厅 · Alixer | Splash... | 哗啦啦…… |
| 大厅 · Alixer | Sploosh! | 哗! |
| 大厅 · Alixer | Good Luck!! | 祝你好运!! |
| 大厅 · Alixer | I gave you potions recently! Give me some time to prepare new ones! | 刚刚才给过你药水呢! 等我准备好新的一批再来吧! |

### `original_text/Hub_General/NPC_Message/George.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · George | Hey there, I'm George the Pet Collector! | 你好,我是宠物收藏家 George! |
| 大厅 · George | I travel the world searching far and wide for the rarest pets I can find! | 我四处游历,就是为了寻找世上最稀有的宠物! |
| 大厅 · George | If you have any pets which need a new home I'd be happy to buy them off you! | 如果你的宠物需要一个新家,我很乐意买下它们! |

### `original_text/Hub_General/NPC_Message/Maxwell.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · Maxwell | Accessories are X magical X pieces of gear. | 饰品是一种 X 神奇 X 的装备。 |
| 大厅 · Maxwell | To truly harness their power, collect as many as possible and store them in your Accessory Bag! | 要充分发挥它们的力量,就尽可能多地收集饰品,放进你的饰品袋! |
| 大厅 · Maxwell | On top of their existing abilities, each accessory makes your Accessory Bag more powerful! | 除了自带的能力,每件饰品还能让你的饰品袋变得更强! |
| 大厅 · Maxwell | Accessories add some ACCESSORY POWER to the bag depending on their rarity. | 饰品会根据自身的稀有度,为饰品袋增加一定的饰品之力。 |
| 大厅 · Maxwell | Yes! The more Accessory Power, the more stats like ❤ Health or ✎ Intelligence you get from your Accessory Bag. | 没错! 饰品之力越高,你的饰品袋提供的❤ 生命值、✎ 智力等属性就越多。 |
| 大厅 · Maxwell | No, it's magic! | 不,这是魔法! |
| 大厅 · Maxwell | Even better, YOU choose what stats you get! | 更棒的是,由你来决定获得哪些属性! |

### `original_text/Hub_General/NPC_Message/Taylor.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 大厅 · Taylor | Hey there adventurer! | 嘿,冒险者! |
| 大厅 · Taylor | You look like someone who values fashion as much as fighting! | 看样子,你和我一样,既重视战斗,也讲究时尚! |
| 大厅 · Taylor | Want to stand out while you slash and dash? | 想在挥剑冲锋时也能脱颖而出吗? |
| 大厅 · Taylor | Check out my cosmetic collection! | 来看看我这里的外观商品吧! |

### `original_text/Mining/ChatMessage/Jungle_Exploration.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · Jungle Exploration | Fight Sludges. | 击败软泥怪。 |
| 挖矿 · Jungle Exploration | Mine Amethyst Gemstones. | 开采 Amethyst 宝石。 |
| 挖矿 · Jungle Exploration | Meet the local tribe! | 拜访当地部族! |
| 挖矿 · Jungle Exploration | Place all Crystals at the statues. | 将所有水晶放到对应的雕像处。 |
| 挖矿 · Jungle Exploration | A vast and sprawling underground civilization. | 一个庞大而广阔的地下文明。 |
| 挖矿 · Jungle Exploration | Temples and puzzles hide their treasures. | 神庙与谜题之中藏着他们的宝藏。 |
| 挖矿 · Jungle Exploration | ...and deep secrets. | ……还有深藏的秘密。 |
| 挖矿 · Jungle Exploration | You are entering the jungle temple, your speed is reduced and your jump boost will not work! | 你正在进入丛林神庙,移动速度将降低,跳跃提升效果将失效! |
| 挖矿 · Jungle Exploration | A magical force surrounding this area prevents you from breaking blocks! | 一股魔法力量笼罩着这片区域,阻止你破坏方块! |
| 挖矿 · Jungle Exploration | The door is locked. | 门锁着。 |

### `original_text/Mining/ChatMessage/Progression_Live.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · Progression Live | HEART OF THE MOUNTAIN TIER %s | 山峦之心 %s 级 |
| 挖矿 · Progression Live | +%s Tokens of the Mountain | +%s 山心代币 |
| 挖矿 · Progression Live | +%s Forge Slots | +%s 个熔炉槽位 |
| 挖矿 · Progression Live | +New Forgeable Items | +解锁更多可锻造物品 |
| 挖矿 · Progression Live | Access to the %s | 解锁%s |
| 挖矿 · Progression Live | You do not have an active Crystal Hollows pass! | 你没有有效的水晶残核通行证! |
| 挖矿 · Progression Live | You may now Fast Travel to %s! | 你现在可以快速传送至%s了! |
| 挖矿 · Progression Live | COLLECTION UNLOCKED %s | 解锁收藏品 %s |
| 挖矿 · Progression Live | %s Minion Recipes | %s小人配方 |
| 挖矿 · Progression Live | %1$s %2$s %3$s Gemstone Recipes | %1$s %2$s %3$s 宝石配方 |
| 挖矿 · Progression Live | ESSENCE! You found some %s Essence! | 精华! 你找到了一些%s精华! |
| 挖矿 · Progression Live | Essence is a type of currency that is saved to your | 精华是一种保存在你当前档案中的货币。 |
| 挖矿 · Progression Live | Profile. Use it to upgrade Items or purchase Perks | 可用于升级物品,也可在精华商店中 |
| 挖矿 · Progression Live | from Essence Shops! | 购买天赋! |
| 挖矿 · Progression Live | Small Gemstone Sack Recipe | 小型宝石收纳袋配方 |

### `original_text/Mining/NPC_Message/Jotraeline_Greatforge.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · Jotraeline Greatforge | ✆ You have an Abiphone! I've always wanted an Abiphone Basic! | ✆ 你有一台 Abiphone! 我一直都想要一台 Abiphone Basic! |
| 挖矿 · Jotraeline Greatforge | Happy drilling! | 祝你挖矿愉快! |

### `original_text/Mining/NPC_Message/Kalhuiki_Door_Guardian.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · Kalhuiki Door Guardian | This temple is locked, you will need to bring me a key to open the door! | 这座神庙已经上锁,你得给我带一把钥匙来才能开门! |

### `original_text/Mining/NPC_Message/Pat.json`

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · Pat | My brother is mining the gravel from the Spider's Den! We are the Flint Bros! | 我兄弟正在蜘蛛巢穴挖沙砾! 我们是燧石兄弟! |

## 修改既有记录

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · `stats_enabled` | The Stats Widget is now ENABLED when in the %s. | 属性小部件现已在%s中启用。 |
| 挖矿 · `jotraeline_greatforge_hey_it_s_very_nice` | ✆ Hey it's very nice of you, but I can't take your ONLY Abiphone! | ✆ 嘿,你人是真好,可我不能收下你唯一的一台 Abiphone! |
| 挖矿 · `pat_my_brother_is_mining_the` | My brother is mining the gravel from the Spider's Den. We are the Flint Bros! | 我兄弟正在蜘蛛巢穴挖沙砾。我们是燧石兄弟! |

## 复用已有译文

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 挖矿 · 聊天奖励 | Ascension Rope | 升天绳索 |

## 新增术语

| 来源玩法 | 原文本 | 翻译后的文本 |
|---|---|---|
| 共享词表 | Dragon's Nest | 龙巢 |
| 共享词表 | Crystal Hollows - Entrance | 水晶残核 - 入口 |
| 共享词表 | Ender Pearl | 末影珍珠 |
| 共享词表 | Fortuitous | 幸运 |
| 共享词表 | Void Sword | 虚空之剑 |
| 共享词表 | Ender Armor | 末影护甲 |
| 共享词表 | End Stone Protector | 末地石守护者 |
| 共享词表 | Zealot | 狂信徒 |

## 保留原文与待核实项

- 其他 Mod 的聊天提示、玩家聊天、已经是中文的消息、分隔线和 Hypixel 大厅奖励不在本次补译范围。
- `Dwarven O's Ore Oats` 奖励名与 `Mmmm, for Karl!`: 已查到社区 Wiki 的麦片及 Deep Rock Galactic 彩蛋语境,但 Wiki 写 `Mmm`,查询最后更新时间时浏览器请求超时,本次暂不新增。
- `Hoppity's Hunt #167 Stats` 统计块来源尚未确认,不将可能由其他 Mod 生成的统计文字写入服务器语料。
- 撤离／挂机转移等少量通用提示暂未完成语境核对,继续保留原文。
- 自动化检查不代替实际客户端的字体折行、聊天居中和点击交互实测。

## 复查与分发构建

- 独立只读子 Agent 复查通过,无阻塞问题; 唯一的清单问题（Maddox 的来源被误标为末地）已改为猎杀任务。
- 复查后执行 `./gradlew dist --console=plain` 成功,两个目标的翻译、采集、命令、精简语料一致性与 Mixin 目标检查均通过。
- 已校验两个 jar 的 ZIP 完整性、Minecraft 版本范围,并确认本轮新增／改动的记录 ID 已打包,测试 Harness 未混入分发包。
- Mod 版本保持 `0.2.0-beta.1`。

同版本后续润色会重新生成以下同名文件,因此不在清单中固定记录大小。

| Minecraft | 分发文件 |
|---|---|
| 26.1.x | `build/dist/SkyBlockZH-0.2.0-Beta-26.1.jar` |
| 26.2 | `build/dist/SkyBlockZH-0.2.0-Beta-26.2.jar` |
