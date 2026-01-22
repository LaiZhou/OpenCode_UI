## OpenCode Server API 学习笔记

> 参考来源：
> - 官方文档：https://opencode.ai/docs/server/
> - JS SDK：`sdk/js/src/gen/types.gen.ts`、`sdk/js/src/gen/sdk.gen.ts`

---

## 总览

- Server 提供 OpenAPI 3.1（`/doc`），同时生成 JS SDK。
- SSE 事件可通过 `/event?directory=` 订阅，或者 `/global/event` 获取全局事件。
- 会话相关 API 位于 `/session`，其中 `messageID` 是 diff 与 session 操作的重要关联点。

---

## Sessions API（核心）

### 会话列表与状态

- `GET /session?directory=`
  - 返回 `Session[]`，包含 `time.updated`，用于找到最近会话。
- `GET /session/status?directory=`
  - 返回 `{ [sessionID]: SessionStatus }`，用于识别 Busy/Idle。
- `GET /session/:id?directory=`
  - 获取单个会话详情。

### Diff

- `GET /session/:id/diff?directory=&messageID?`
  - `messageID` **可选但重要**，用于定位某次消息产生的 diff。
  - 未传 `messageID` 时，可能返回该 session 的历史 diff（导致误报）。

### 其他关键 Session 操作

- `POST /session`：创建会话
- `PATCH /session/:id`：更新 title
- `DELETE /session/:id`：删除会话
- `POST /session/:id/abort`：中断会话
- `POST /session/:id/revert`、`POST /session/:id/unrevert`
- `POST /session/:id/permissions/:permissionID`

---

## Messages / Commands API（与 messageID 关联）

- `POST /session/:id/command`
  - 执行 `/mystatus` 等命令，返回 Message（含 `id`）。
- `GET /session/:id/message?limit?`
  - 返回 `{ info: Message, parts: Part[] }[]`
- `POST /session/:id/message`
  - 返回 `{ info: Message, parts: Part[] }`

**Message 结构要点（SDK）**
- `Message.id` = `messageID`
- `Message.sessionID` 关联 session
- `Message.role` = `user` 或 `assistant`

---

## SSE 事件（SDK 对照）

### 事件订阅

- `/event?directory=`（对应 SDK `EventSubscribeData`）
- `/global/event`（SDK Global event）

### 本插件关注的事件

- `session.status` → `{ sessionID, status }`
- `session.idle` → `{ sessionID }`
- `session.diff` → `{ sessionID, diff: FileDiff[] }`
- `file.edited` → `{ file }`
- `message.updated` → `{ info: Message }`（Message 中带 `id`, `sessionID`, `role`）
- `command.executed` → `{ name, sessionID, arguments, messageID }`

**SDK 定义对照**
- `EventMessageUpdated.properties.info`（不是 `messageID`）
- `EventCommandExecuted.properties.name/arguments/messageID`（不是 `command` 字段）

---

## 重要结论 / 使用建议

1. **获取 diff 必须尽量携带 `messageID`**
   - 缺失时会返回历史 diff（已删除文件也会被当成“新变化”）。
2. **`message.updated` 事件必须解析 `info.id` 作为 messageID**
   - SDK 明确该字段在 `info` 中，而不是直接在 `properties`。
3. **`command.executed` 事件提供可靠的 messageID**
   - 对 `/mystatus` 等命令场景尤为重要。
4. **`message.part.updated` 可作为兜底来源**
   - Part 对象包含 `sessionID` 和 `messageID`，可在 `message.updated` 缺失时补齐。
5. **会话进入 `busy` 时应幂等清理状态**
   - 仅在 `status.isBusy() && changed` 为真时清理 `turnMessageIds` 等状态，防止重发的 busy 信号抹掉中途到达的 `file.edited` 事件。
6. **Fetch 优先级策略**
   - 1. `messageID` 关联的 API 结果 (最准)；2. SSE 推送的 `session.diff` (兜底)；3. `Session Summary` (最后手段)。

---

## 本插件当前 API 使用点（复核）

- `/event?directory=`：SSE 事件订阅
- `/global/health`：健康检查
- `/session/status`：寻找 busy session
- `/session`：获取最新 session
- `/session/:id`：获取 session summary（diff fallback）
- `/session/:id/diff?messageID?`：展示 diff
- `/tui/append-prompt`：向 TUI 追加命令

