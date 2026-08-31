# 数据来源说明

## 现状(截至 2026-08-18)

Hypixel 官方 Wiki(wiki.hypixel.net)已于 **2026 年 7 月正式关闭**
(官方公告:https://hypixel.net/threads/end-of-the-official-hypixel-wiki-july-2026.6112020/)。
游戏内所有指向 Wiki 的链接已被移除。Hypixel 官方表示不会指定/背书任何社区 Wiki。

社区维护的 Wiki 在官方 Wiki 关闭前就已存在,且被认为比官方版本更新更及时,继续可用:

- **主要来源**: https://hypixelskyblock.minecraft.wiki/
  (该社区 Wiki 于 2026-04-20 从 Fandom 迁移至 minecraft.wiki/Weird Gloop 平台,内容延续,
  URL 结构与旧 Fandom 版基本一致,只是域名换了。旧 Fandom 域名
  `hypixel-skyblock.fandom.com` 目前部分页面仍可访问但可能是旧快照,优先用新域名。)

## 按文本类型的可信来源优先级

不同类型的文本,Wiki 的覆盖程度差别很大,采集时按下表优先级选择来源:

| 文本类型 | 首选来源 | 说明 |
|---|---|---|
| NPC_Message | 社区 Wiki NPC 页面 | 大部分 NPC 有专门页面逐句记录对话,质量较高 |
| GUI_Item(物品 Lore) | 社区 Wiki 物品页面「Tooltip Text / Stats」区块 | 一般较完整,含数值占位符时页面会写清楚公式 |
| GUI_Title | 社区 Wiki 对应功能页面 | **经常缺失**,只有页面结构截图,没有逐条 UI 文本 |
| ScoreBoard / ActionBar / BossBar | Wiki 基本不记录逐条原文 | 这类"屏幕装饰性文字"很少被 Wiki 当作百科条目收录 |

## ScoreBoard / ActionBar / BossBar / 部分 GUI 文本的更好来源(待接入)

这几类文本 Wiki 覆盖很差,采集时如果 Wiki 查不到,**不要凑合翻译总结性描述充数**,
应转向以下开源项目(这些 Mod 需要用正则表达式解析游戏内屏幕文本才能工作,
它们的源码里天然包含了大量"原文格式 + 占位符位置"现成信息,比 Wiki 更适合本项目):

- **SkyHanni**(开源 SkyBlock 辅助 Mod,Fabric/Forge,Kotlin):
  仓库里的 `*.repo.json` / regex pattern 常量,覆盖 ScoreBoard、ActionBar、
  ChatMessage、BossBar 的解析正则,基本等价于"官方屏幕文本模板 + 占位符分组"。
  这是本项目**最值得优先接入**的数据源,应在下一阶段用 WebFetch/克隆仓库方式采集。
- **NotEnoughUpdates-REPO**(NEU 的物品数据仓库):
  逐个物品的完整 NBT/Lore JSON 数组,比 Wiki 的"Tooltip Text"摘要更精确、更完整,
  适合替代/校对 GUI_Item 类目。
- 游戏内实测截图:对于以上开源项目也没覆盖到的边角文本,最终仍需要人工进玩家账号
  截图核对,`verified_ingame` 字段就是为此设计的。

## 采集时的一般原则

1. 每次采集一个页面就在对应 JSON 的 `source` / `fetched_at` 字段留痕。
2. Wiki 用词、格式可能因玩法版本更新过时,凡是 Wiki 页面自身提示"过时/待更新"的,
   在文件里加一条 `"stale_warning"` 说明。
3. 采集不到逐字原文、只能拿到"总结性描述"的情况,不要把总结文字当成 `text` 硬凑进去
   ——这会污染数据。应该把该条目跳过并在文件顶层加 `"todo"` 数组说明缺什么,
   等接入 SkyHanni/NEU 数据源或实测后再补。

## 社区 Wiki 的 `/UI` 子页 —— GUI 文本的正经来源(2026-08-19 找到)

本文件上面写着"GUI_Title 经常缺失,只有页面结构截图",这句话**过时了**。社区 Wiki 把每个界面的
逐格内容单独放在一个子页里,页面上用 `{{/UI}}` 转写进来,分类是
[Category:UI Subpages](https://hypixelskyblock.minecraft.wiki/wiki/Category:UI_Subpages),
一共 700 多页。子页里的每一格长这样:

```
|3, 5=Minecart, none, &3Join the Crystal Hollows, /&7This pass grants you access to the/&2Crystal Hollows&7 for &66 hours&7.
      物品           跳转  物品名(带颜色码)          Lore,"/" 是换行,"//" 是空行
```

也就是说 **箱子标题、格子里的物品名、完整 Lore、颜色码全都有**,格式还是机器可读的。已经据此补齐的:委托板(`Emissaries/UI`)、
Gwendolyn 的水晶残核通行证(`Gwendolyn/UI`)、方块速查手册主界面和四个子菜单(`Fragilis/UI`)。
还没做但同样有子页的:`Heart of the Mountain/UI`(整棵天赋树的 Lore)、`Fossil Excavator/UI`、
`The Forge/UI`、`Blacksmith/UI`、`Mining Merchant/UI`、`Lift Operator/UI`、
`Glacite Mineshafts/UI`、各种 Sack/Collection 界面。

注意:子页里的图标字符两种写法都有(`☘` 和私用区的 `U+E053`),照抄通用符号即可,
引擎会折算(见 `original_text/README.md` 的"属性图标写通用符号"一节)。

## 玩家实测客户端日志(logs/)

`logs/latest.log` 里 Minecraft 会把收到的每一条聊天消息逐字写下来,是 ChatMessage / NPC_Message
最准的来源——比 wiki 准,因为它就是这个版本的服务器发的。颜色大多会在写日志时丢掉
(日志打的是拍平后的字符串),所以**文本以日志为准、颜色以 SkyHanni 的正则常量或 wiki 的
`{{Dialogue}}` 为准**,两边配合。

用法:

```bash
./gradlew auditLog                       # 读 logs/ 下所有日志
./gradlew auditLog -Plog=logs/latest.log # 只读一个
```

它把日志里的聊天行喂给真正的匹配引擎,列出**没有任何记录应答的行**(按出现次数排序)。
2026-08-19 第一次跑:3,255 行里只认出 116 行;补完这一轮之后是 1,801 行。

**日志只有聊天。** 界面标题、物品名、Lore、计分板、BossBar、动作栏、Tab 列表都不写进日志,
那几类要靠下面的运行时采集。

## 运行时采集(2026-08-20 接入,默认关闭)

上面所有来源加起来仍然有一块补不上的空白:**NPC 打开的那个界面里,每一格物品叫什么、Lore 写了什么**。
wiki 的 `/UI` 子页只覆盖了一部分菜单,NEU-REPO 只有物品本体不含菜单布局,日志里根本没有。
这块内容只存在于"有人正在玩的那块屏幕上"。

Mod 里因此有一个默认关闭的开关 `captureUntranslated`(`config/skyzh.json` 或 Mod Menu),
打开之后把语料答不上来的服务器原文写到游戏目录下的 `skyzh-capture/`,分 `untranslated/`、`mixed/`、
`colour/` 三堆,各自按本目录的结构(玩法 / 渲染面 / 名字)分好。**采到的记录就是本文件规定的记录格式**——
颜色码原样保留、变色的行自动切好 `segments`、`zh` 留空——搬进来只需要删掉 `_capture` 块、
核对占位符、填 `zh`。

`colour/` 那一堆是 2026-08-22 加的,专门管**颜色失真**(需求文档里的已知问题 3):记录答上来了、
每个字都是中文,但游戏里这一行中途换色而语料记成了一整行,整段译文被刷成最前面那个颜色。
这一类是这个项目最难自己发现的一种——语料没毛病、日志只说得出"哪条记录压平了颜色",
说不出**颜色是在哪儿换的**。采集文件说得出来:它写下的 `segments` 就是按服务器实际发的边界切的,
抄进 `_capture.matched_record` 指的那条记录即可。

**有一个面采不到:`Hologram/`。** NPC 头顶的文字来自实体元数据包,不走上面挂钩子的那几个函数;
就算挂上去,怪物血条("Glacite Walker 1.2M❤")每帧都在变,采下来全是噪音。那个目录只能进游戏照抄。

关键的一点是**挂钩子的位置**:全部挂在网络包处理函数或只有网络包写得进去的状态上
(`handleSystemChat` / `handleOpenScreen` / `handleContainerContent` / `ItemStack` 的 `LORE` 组件 /
`Scoreboard` 对象 / `BossHealthOverlay#update`),**不是**挂在渲染上。别的 Mod 自己发的聊天走
`ChatComponent#addMessage`、自己加的 Lore 走 `getTooltipLines()`,两条路都不经过这些函数,
所以它们的文本不是被过滤掉的,而是根本进不来。上一次做这个功能是挂在渲染上的,结果采了一堆
SkyHanni / SkyBlocker 的界面文字,事后再也分不出来——**错的是位置,不是过滤规则**。

用法和注意事项见 `../docs/TECHNICAL_zh-CN.md` 的「运行时采集」一节;自检跑 `./gradlew checkCapture`。
