## OpenCode Server API Study Notes

> Sources:
> - Official documentation: https://opencode.ai/docs/server/
> - JS SDK: `sdk/js/src/gen/types.gen.ts`, `sdk/js/src/gen/sdk.gen.ts`

---

## Overview

- Server provides OpenAPI 3.1 (`/doc`), with a generated JS SDK.
- SSE events are subscribable via `/event?directory=`, or `/global/event` for global events.
- Session-related APIs are under `/session`, with `messageID` being the key link between diffs and session operations.

---

## Sessions API (Core)

### Session List & Status

- `GET /session?directory=`
  - Returns `Session[]`, includes `time.updated` for finding the latest session.
- `GET /session/status?directory=`
  - Returns `{ [sessionID]: SessionStatus }`, for identifying Busy/Idle.
- `GET /session/:id?directory=`
  - Get single session details.

### Diff

- `GET /session/:id/diff?directory=&messageID?`
  - `messageID` is **optional but important**, used to locate the diff produced by a specific message.
  - Without `messageID`, may return historical diffs for that session (causing false positives).

### Other Key Session Operations

- `POST /session`: Create session
- `PATCH /session/:id`: Update title
- `DELETE /session/:id`: Delete session
- `POST /session/:id/abort`: Abort session
- `POST /session/:id/revert`, `POST /session/:id/unrevert`
- `POST /session/:id/permissions/:permissionID`

---

## Messages / Commands API (messageID Association)

- `POST /session/:id/command`
  - Execute commands like `/mystatus`, returns Message (with `id`).
- `GET /session/:id/message?limit?`
  - Returns `{ info: Message, parts: Part[] }[]`
- `POST /session/:id/message`
  - Returns `{ info: Message, parts: Part[] }`

**Message Structure Key Points (SDK)**
- `Message.id` = `messageID`
- `Message.sessionID` links to session
- `Message.role` = `user` or `assistant`

---

## SSE Events (SDK Reference)

### Event Subscription

- `/event?directory=` (corresponds to SDK `EventSubscribeData`)
- `/global/event` (SDK Global event)

### Events of Interest for This Plugin

- `session.status` → `{ sessionID, status }`
- `session.idle` → `{ sessionID }`
- `session.diff` → `{ sessionID, diff: FileDiff[] }`
- `file.edited` → `{ file }`
- `message.updated` → `{ info: Message }` (Message contains `id`, `sessionID`, `role`)
- `command.executed` → `{ name, sessionID, arguments, messageID }`

**SDK Definition Reference**
- `EventMessageUpdated.properties.info` (not `messageID`)
- `EventCommandExecuted.properties.name/arguments/messageID` (not a `command` field)

---

## Key Conclusions / Usage Recommendations

1. **Always include `messageID` when fetching diffs**
   - Without it, historical diffs may be returned (deleted files can appear as "new changes").
2. **`message.updated` events must parse `info.id` as messageID**
   - The SDK clearly places this field in `info`, not directly in `properties`.
3. **`command.executed` events provide a reliable messageID**
   - Especially important for `/mystatus` and similar command scenarios.
4. **`message.part.updated` can serve as a fallback source**
   - Part objects contain `sessionID` and `messageID`, useful when `message.updated` is missing.
5. **Idempotently clean state when session enters `busy`**
   - Only clear `turnMessageIds` etc. when `status.isBusy() && changed` is true, preventing re-sent busy signals from wiping mid-arrival `file.edited` events.
6. **Fetch Priority Strategy**
   - 1. `messageID`-linked API result (most accurate); 2. SSE-pushed `session.diff` (fallback); 3. `Session Summary` (last resort).

---

## Plugin's Current API Usage Points (Reviewed)

- `/event?directory=`: SSE event subscription
- `/global/health`: Health check
- `/session/status`: Find busy session
- `/session`: Get latest session
- `/session/:id`: Get session summary (diff fallback)
- `/session/:id/diff?messageID?`: Display diff
- `/tui/append-prompt`: Append command to TUI
