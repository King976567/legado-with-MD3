# NAS 书籍详情与书架详情的视觉统一

## 范围与归属

- 现有 `ui/main/nas` 仍是 NAS 业务 owner；本切片只替换详情弹窗，不迁移整个 NAS Feature。
- 使用现有 Navigation 3 `MainRouteNasLibrary` 内的详情状态，不新增 Activity、底部导航或第二套 ViewModel。
- NAS 与书架共同使用 `core/ui/book/BookDetailHeaderLayout` 和 `BookDetailTopBar`，后者保留原 MD3/Miuix 折叠、返回和菜单布局。原书架的私密脱敏、封面交互、共享元素及书名/作者操作仍由原页面传入。
- 新资源属于 NAS：`feature_nas_book_*`，提供默认英文和简体中文。

## 行为

- 点击书籍只补充详情，不下载、不导入本地书架。顶部封面/书名/作者/来源与书架同布局；标签、简介、文件信息整体上下滚动。
- 底部主动作是“下载并阅读”，沿用原有流式下载、临时文件、本地导入、阅读入口。未下载时不伪造目录、章节或阅读进度。
- 刷新、元数据编辑、移动分类、单本刮削集中到右上角菜单，权限及运行中限制保持不变。
- 详情失败保留列表中的元数据并提供重试；操作错误在详情内可见。
- 系统返回与顶部返回都回到 NAS 列表；列表 LazyListState 在同一状态宿主保留，查看详情时不触发自动分页。
- 关闭详情、切换书籍、切换服务配置会取消旧详情请求，并以 generation 防止旧结果重新打开或覆盖页面。
- 封面及背景使用相同的 NAS 路径范围鉴权请求，不将令牌写入 UI state 或导航参数。

## 验证

- `NasLibraryScreenTest`：全屏非弹窗、长简介滚动、紧凑/宽屏布局、权限、管理菜单、加载/失败重试、返回后列表位置。
- `NasBookDetailTest`：只读加载、失败重试、返回取消、切换书籍/配置。
- 回归执行全部 `*Nas*` JVM 测试和 arm64 debug assemble；真机系统栏、返回手势、封面网络及 NAS 实际下载需设备验收。

2026-09-25 本地验证：JDK 21 下 `:app:testAppDebugUnitTest --tests '*Nas*' --tests '*BookInfo*' --project-prop ksp.incremental=false` 共 73 项通过；架构检查和 arm64 `:app:assembleAppDebug` 通过。`adb devices` 无设备，本次未执行真机或完整 lint 验收。

## 边界与回滚

仅抽取被两处实际使用的无业务 UI；未新增模块、数据层协议或跨 Feature 实现依赖。原 NAS ViewModel 的 Android 本地导入适配仍是既有技术债，本次未扩大。进程死亡后详情仍沿用原行为回到列表，不承诺恢复未提交编辑草稿。需要回滚时一起回滚 NAS Screen、共享头部/工具栏及书架的调用替换；无需数据库迁移。
