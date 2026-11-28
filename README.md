# LinkNote 中期答辩原型

LinkNote 是一个用 Java 21 和 JavaFX 21 开发的本地 Markdown 笔记工具。打开一篇 `.md` 后，左侧列出同一文件夹下的笔记，中间编辑标题和 Markdown，右侧实时预览，下方的「引用此笔记」可展开或收起。

## 启动

在 IntelliJ IDEA 打开项目根目录的 `pom.xml`，使用 JDK 21。同步 Maven 后，在右侧 Maven 面板运行「插件 → javafx → javafx:run」。若插件项没有展开，可在 Maven 面板使用「执行 Maven 目标」，输入 `javafx:run`。已安装 Maven 的终端也可在项目根目录运行 `mvn javafx:run`。

首次运行需要联网下载 Maven 依赖。公式使用随 Maven 依赖下载的 KaTeX 和字体。 [KaTeX 文档](https://katex.org/docs/api)

## 三分钟演示

1. 启动后从空白页点「打开示例」。打开《矩阵分块》，展示列表、Markdown 源文、表格和公式预览。
2. 点「新建笔记」，输入标题，例如「分块学习记录」。写入一段 Markdown 和一条 `[[缓存局部性]]`，按 `Ctrl+S` 保存。
3. 点预览里的「缓存局部性」，再点目标笔记下方「引用此笔记」里的「分块学习记录」，回到刚才的笔记。
4. 退出应用，重新启动后点「打开文件」，选中刚才保存的 `.md`。检查标题、正文及双链仍可用。

## 亲手验收

| 操作 | 预期 |
| --- | --- |
| 从空白页打开普通 UTF-8 `.md` | 没有 YAML 的笔记也能打开，默认标题取文件名 |
| 新建笔记并编辑正文 | 可预览标题、加粗、列表、代码、表格、`$...$` 行内公式和 `$$...$$` 独立公式。保存后修改状态消失 |
| 从 A 保存 `[[B]]` 并点开，使用 B 的反链返回 | B 的反链列出 A。删除 A 的链接并保存后，B 的对应反链消失。代码中的双链保持普通文本 |
| 在左侧搜索「缓存」，再添加一个关键词 | 标题命中排在正文命中前，多个词都匹配才显示结果。列表展示文件名和正文片段，点击结果打开笔记。保存后立即更新 |
| 打开 B，点「重命名」，输入标题和文件名并查看影响 | 确认后保留 B 的 UUID，将当前目录中已解析的引用改为新路径，例如 `[[./new-name]]`。重开后仍可以往返跳转，反链与图谱指向改名后的笔记 |
| 编辑后尝试切换笔记或关闭应用 | 可选「保存」「放弃修改」「继续编辑」。保存失败时草稿仍留在编辑区 |
| 应用打开时，用其他编辑器修改当前文件，再从 LinkNote 保存 | 出现磁盘修改提示，磁盘外部改动和编辑区草稿都保留 |
| 完全退出再启动，并手动打开上次保存的文件 | 可读到已保存的标题和正文，依靠磁盘中的 Markdown 重新构建反链 |

重命名备份和操作记录保存在笔记目录的 `.linknote/rename_operations/`。重命名中断后，再次打开该目录中的笔记会提示恢复。遇到外部改动冲突时，按提示查看相应的原文件和备份。

## 答辩时的关键断点

| 从哪里进入 | 跟读顺序 | 要能讲清的状态 |
| --- | --- | --- |
| 打开文件 | `LinkNoteApp.openFile` → `MarkdownNoteRepository.scanFolder` → `showFolder` | `Note` 是磁盘快照，JavaFX 控件保存编辑草稿。普通 Markdown 可用文件名作为标题 |
| 保存 | `LinkNoteApp.saveCurrentNote` → `MarkdownNoteRepository.save` → `replaceListedNote` | 写入临时文件并核对原文件指纹，成功替换后才刷新已保存状态和反链 |
| 双链与反链 | `MarkdownPreview` → `LinkNoteApp.followLink` → `NoteCatalog.resolve` → `openNote`，反链按钮直接打开 `LinkIndex` 给出的来源笔记 | 预览与反链共用相同的 Markdown 解析规则。前向跳转解析当前链接，反链从已保存的正文计算 |
| 搜索 | `LinkNoteApp.refreshNoteList` → `NoteSearch.find` | 查当前目录的保存快照，依次按标题匹配词数、正文匹配词数和文件路径排列 |
| 重命名 | `LinkNoteApp.chooseRename` → `NoteRenameService.prepare` / `commit` → `RenameJournal` | 先从已保存的正文生成影响清单，再备份并逐文件校验指纹。提交成功后从磁盘重建关系 |

## 测试

运行 `mvn test` 验证文件读写、冲突保护、链接索引及重命名恢复。Windows 桌面可以运行 `mvn '-Dlinknote.desktopSmoke=true' test`，验证真实 JavaFX 窗口的新建、表格和公式渲染、保存、双链与反链、重命名后更新引用，以及关闭窗口后重新创建窗口并打开文件。

中期范围限于本地笔记和链接，单文件最多 2 MiB。普通 Markdown 仅改正文时保留无 YAML 的文件形式，首次改标题时将标题与 UUID 写入 YAML。正式阶段仍参照 [V1.2 开发计划书](docs/linknote_v1_2_development_plan.md)。
