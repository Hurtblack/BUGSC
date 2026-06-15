# Decisions: scmbot-agent

> 本文件记录 explore 阶段的所有需求决策。
> 由 edc-sdd:explore 自动维护，propose 阶段将读取本文件作为输入。

## Decision 1: 功能发布范围
**Status:** [DECIDED]
**Options:**
- A. 放入 1.0.5 发布范围
- B. 单独开分支，暂不进入 1.0.5
**Choice:** B
**Reason:** 用户明确表示“1.0.5 就不弄这个了”，当前已在 `codex/scmbot-agent` 分支隔离推进。

## Decision 2: SCMBOT 的系统定位
**Status:** [DECIDED]
**Options:**
- A. 把 SCMBOT 做成 SCM 后端中的机器人用户
- B. 把 SCMBOT 做成 App 本地 Agent Runtime，SCM 聊天页只作为可复用 UI 参考
**Choice:** B
**Reason:** 用户没有自有后端，SCM 交易和聊天不是用户维护的后端；SCMBOT 应由 App 本地管理会话、Skill、模型调用和历史。

## Decision 3: 入口位置
**Status:** [DECIDED]
**Options:**
- A. 放在个人信息页
- B. 放在工具页，点击后直接进入聊天
**Choice:** B
**Reason:** 用户明确要求“这个 scmbot 就放在工具这里吧，点进去就可以直接聊天”。

## Decision 4: 第一版模型供应商
**Status:** [DECIDED]
**Options:**
- A. 第一版只支持 DeepSeek
- B. 第一版支持多个预设供应商
- C. 第一版只支持自定义 OpenAI-compatible
**Choice:** A
**Reason:** 用户确认“第一版用 deepseek 吧”。内部仍可保留 provider 抽象，避免后续扩展困难。

## Decision 5: DeepSeek 配置方式
**Status:** [DECIDED]
**Options:**
- A. App 内置开发者 Key
- B. 用户在 App 内填写自己的 DeepSeek API Key
- C. 通过自有后端代理 DeepSeek
**Choice:** B
**Reason:** 用户没有自有后端；API Key 应由用户自己配置，保存在本机。

## Decision 6: 聊天 UI 实现边界
**Status:** [DECIDED]
**Options:**
- A. 直接改造现有 SCM ChatFragment，同一个 Fragment 同时处理 SCM 私聊和 SCMBOT
- B. 新建 ScmBotChatFragment，复用现有聊天页视觉风格，避免影响 SCM 私聊
**Choice:** B
**Reason:** 现有 ChatFragment 强绑定 SCM 登录、REST 历史、WebSocket 和公开资料接口；SCMBOT 不应影响 SCM 私聊、未读数和 WebSocket。

## Decision 7: Agent 执行策略
**Status:** [DECIDED]
**Options:**
- A. 用户问题直接交给模型回答
- B. App 先调用 Skill 查询结构化数据，再把工具结果交给模型分析整理
**Choice:** B
**Reason:** 用户希望 App 内置 Skill，查本地数据库或 API 后再让 AI 分析；这样答案更可控，减少模型凭空编造。

## Decision 8: Skill 数据源范围
**Status:** [DECIDED]
**Options:**
- A. 只查本地 assets
- B. 查本地 assets 和 SCM API
- C. 查本地 assets、当前已知查询 API 和 SCM API
**Choice:** C
**Reason:** 用户补充“skill 还可以用目前已知的查询 api”，并确认 SCM 那边的 API 可用于某些数据查询。

## Decision 9: DeepSeek 未配置时的入口行为
**Status:** [DECIDED]
**Options:**
- A. 未配置 DeepSeek 时隐藏 SCMBOT 入口
- B. 未配置 DeepSeek 时仍可进入 SCMBOT 聊天页，由聊天页引导配置
**Choice:** B
**Reason:** 用户要求工具页点击即可直接聊天；入口不应被配置状态阻断，配置引导放在聊天页更符合用户路径。

## Decision 10: SCM 登录态 API 使用范围
**Status:** [DECIDED]
**Options:**
- A. 第一版只使用无需登录的 SCM 公开查询 API
- B. 第一版允许在用户已登录 SCM 时使用带 token 的 SCM 查询 API，但不强制登录
**Choice:** B
**Reason:** 用户修正选择为 B。SCM 登录态接口只是可选查询工具源；SCMBOT 不强制 SCM 登录，未登录时仍可使用本地数据和公开查询 API。

## Decision 11: Agent 命名与 SCM 品牌关系
**Status:** [DECIDED]
**Options:**
- A. 固定命名为 SCMBOT，并绑定 SCM 品牌语义
- B. 第一版可暂用 SCMBOT 作为工作名，但架构和文案避免绑定 SCM，后续允许改名
**Choice:** B
**Reason:** 用户说明“scmbot 与 scm 没关系，后面都可能改名”。实现中应避免把 Agent 能力与 SCM 账号、SCM 聊天或 SCM 品牌强耦合。

## Decision 12: Agent 人物卡
**Status:** [DECIDED]
**Options:**
- A. 不做人物卡，只在聊天页显示普通标题
- B. 做本地 Agent 人物卡，用于聊天页展示、自我介绍和 system prompt
**Choice:** B
**Reason:** 用户提出“可以给这个 agent 一个人物卡一样的东西”。人物卡可以统一角色身份、能力边界、隐私说明和后续改名成本。
