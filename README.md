# intellij-debug-mcp

> **Control the IntelliJ IDEA debugger, build system, and test runner with Claude Code (or any MCP client) via natural language.**

An IntelliJ IDEA plugin that exposes the built-in Java debugger, compiler, and test runner as an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server. This lets AI coding assistants like **Claude Code** set breakpoints, step through code, inspect variables, evaluate expressions, build the project, and run tests — all without you touching the IDE manually.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ-2024.3+-blue.svg)](https://www.jetbrains.com/idea/)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-green.svg)](https://modelcontextprotocol.io)

---

## Why this exists

Debugging is one of the last things AI assistants couldn't do autonomously. They can read code, write code, run tests — but when a bug only shows up at runtime, the AI was blind.

**intellij-debug-mcp** bridges that gap. With this plugin, Claude Code can:
- Set a breakpoint, launch a debug session, and catch the program right where it fails
- Inspect local variables and evaluate arbitrary expressions at runtime
- Step through code line by line while narrating what it observes
- Find the root cause of a bug without you having to describe what you see in the debugger UI
- Build the project, read compiler errors, fix the code, and rebuild — all autonomously
- Run JUnit/TestNG tests, collect results with stack traces, and iterate until all tests pass

**When to use it:**
- You describe a bug to Claude Code and want it to investigate autonomously
- You want Claude to verify a hypothesis ("is `sum` really 60 here?") without context-switching to the IDE
- You're doing pair debugging — Claude watches the debugger while you read the code
- Automated debugging workflows where the AI runs, breaks, inspects, and patches in one go
- You want Claude to fix a compilation error end-to-end: build → read errors → patch → rebuild
- Full TDD loop: Claude writes a fix, runs the tests, reads failures, patches again — until green

---

## Requirements

- **IntelliJ IDEA** 2024.3 or later (Community or Ultimate)
- **Java 21+** (JVM toolchain)
- **Claude Code** CLI or any MCP-compatible client

---

## Installation

### Option 1 — Build from source (recommended for now)

```bash
git clone https://github.com/alexeyleping/intellij-debug-mcp.git
cd intellij-debug-mcp
./gradlew buildPlugin
```

The plugin zip will be at `build/distributions/intellij-debug-mcp-*.zip`.

In IntelliJ IDEA:
1. **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. Select the zip file
3. Restart IDE

### Option 2 — JetBrains Marketplace

> Coming soon.

---

## How it works

When you open any project in IntelliJ, the plugin starts a local HTTP server on **port 63820**. It exposes a JSON-RPC 2.0 endpoint at `http://localhost:63820/mcp` that implements the MCP `tools/list` and `tools/call` methods.

The plugin talks directly to IntelliJ's `XDebugger` API — the same API the IDE uses internally — so breakpoints, step commands, and variable inspection all work exactly as they do in the UI.

---

## How Claude Code calls the plugin

This plugin does **not** use the native MCP stdio/SSE transport that Claude Code configures via `mcpServers` in settings. Instead, it works through a simpler and more transparent mechanism:

1. You describe the tool in `~/.claude/CLAUDE.md` (a plain text file Claude reads at startup)
2. Claude Code reads that description and understands it can reach the debugger via HTTP
3. When it needs to debug something, Claude autonomously runs `curl` commands using its built-in **Bash tool** — the same terminal access it uses for `git`, `npm`, etc.

In other words, **Claude calls `curl` in your terminal** — you can see every request it makes. No background daemons, no special client configuration, no MCP handshake needed.

This approach works with any shell-capable AI assistant, not just Claude Code.

---

## Connecting Claude Code

### On this machine

Add the following to your global `~/.claude/CLAUDE.md` so Claude Code knows the tool is available in every project:

```markdown
## Available tools on this machine

### intellij-debug-mcp
IntelliJ IDEA plugin that exposes the Java debugger as an MCP server.

- **Endpoint**: `http://localhost:63820/mcp` (POST)
- **Protocol**: JSON-RPC 2.0 — no initialization needed, call `tools/call` directly

Available tools: `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`,
`start_debug`, `stop_debug`, `get_session_state`, `get_stack_frames`,
`get_variables`, `evaluate`, `step_over`, `step_into`, `step_out`,
`select_frame`, `resume`, `pause`, `list_run_configs`,
`build_project`, `get_build_errors`,
`run_tests`, `get_test_results`

Example:
```bash
curl -X POST http://localhost:63820/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_run_configs","arguments":{}}}'
```
```

### On a new machine (step by step)

1. Install the plugin (see [Installation](#installation))
2. Open any project in IntelliJ IDEA — the MCP server starts automatically
3. Open `~/.claude/CLAUDE.md` (create it if it doesn't exist)
4. Paste the block from the section above
5. Verify the server is running: `curl http://localhost:63820/mcp` should return a 404 (GET is not supported, but a response means the server is up)
6. Start a Claude Code session — it will now know about the debugger tools

> **Tip:** The server only runs while IntelliJ is open with a project loaded. If Claude reports the tool is unavailable, check that IntelliJ is running.

---

## Available tools

### Tests

| Tool | Description | Parameters |
|------|-------------|------------|
| `run_tests` | Run tests for a class (or single method) using an existing JUnit/TestNG run config | `className` (string, required), `methodName` (string, optional) |
| `get_test_results` | Return results of the last `run_tests` call: status per test, duration, error message and stack trace for failures | — |

### Build

| Tool | Description | Parameters |
|------|-------------|------------|
| `build_project` | Build the project (Make or Rebuild), returns status with errors and warnings | `rebuild` (boolean, optional — default `false`) |
| `get_build_errors` | Get errors and warnings from the last build: file, line, message | — |

### Breakpoints

| Tool | Description | Parameters |
|------|-------------|------------|
| `set_breakpoint` | Set a breakpoint | `file` (full path or filename), `line` (int) |
| `remove_breakpoint` | Remove a breakpoint | `file`, `line` |
| `list_breakpoints` | List all active breakpoints with type info | — |

### Session management

| Tool | Description | Parameters |
|------|-------------|------------|
| `list_run_configs` | List all run configurations in the project | — |
| `start_debug` | Start a debug session by run config name | `name` (string) |
| `stop_debug` | Stop the current debug session | — |
| `get_session_state` | Get session status and current paused position | — |

### Execution control

| Tool | Description | Parameters |
|------|-------------|------------|
| `resume` | Resume execution until next breakpoint | — |
| `pause` | Pause a running session | — |
| `step_over` | Execute current line, step to next | — |
| `step_into` | Step into the method call on current line | — |
| `step_out` | Step out of the current method | — |

### Inspection

| Tool | Description | Parameters |
|------|-------------|------------|
| `get_stack_frames` | Get the full call stack with frame indices | — |
| `select_frame` | Switch to a specific stack frame | `index` (int) |
| `get_variables` | Get all variables in the current frame | — |
| `evaluate` | Evaluate any expression in the current frame | `expression` (string) |

---

## Usage examples

### Debugging session

```
You: "The average() method returns wrong results. Debug it."

Claude:
1. list_run_configs           → finds "BuggyCalculator"
2. set_breakpoint line 14     → breakpoint set [JavaLineBreakpointType]
3. start_debug "BuggyCalculator"
4. get_session_state          → Paused at BuggyCalculator.java:14
5. get_variables              → sum=60, numbers=ArrayList(size=3)
6. evaluate "numbers.size()"  → 3
7. evaluate "(double)sum / (numbers.size()-1)" → 30.0

"Found the bug: dividing by (size-1) instead of size.
 Fix: change line 14 to `return (double) sum / numbers.size();`"
```

### Build loop (autonomous fix cycle)

```
You: "Fix all compilation errors."

Claude:
1. build_project              → Build FAILED: 2 error(s), 0 warning(s)
                                  Errors:
                                    Calculator.java:42 — cannot find symbol: method summ()
                                    Calculator.java:57 — incompatible types: int, String
2. [edits Calculator.java lines 42 and 57]
3. build_project              → Build SUCCESS: 0 error(s), 0 warning(s)

"Both errors fixed. Build is clean."
```

### TDD loop (autonomous test–fix cycle)

```
You: "Make all tests in CalculatorTest pass."

Claude:
1. run_tests "com.example.CalculatorTest"
   → Tests: 3 total — 1 passed, 2 failed, 0 errors, 0 ignored
     [FAILED] testAverage (12ms)
       Message: expected: <30.0> but was: <20.0>
       at CalculatorTest.java:24
     [FAILED] testEdgeCase (5ms)
       Message: expected: <0.0> but was: <NaN>
2. [edits Calculator.java based on the failures]
3. build_project              → Build SUCCESS
4. run_tests "com.example.CalculatorTest"
   → Tests: 3 total — 3 passed, 0 failed ...
   → All tests passed!

"Both failures fixed. All 3 tests are green."
```

---

## Architecture

```
Claude Code (curl / MCP client)
        │  JSON-RPC 2.0
        ▼
http://localhost:63820/mcp          ← Ktor HTTP server (plugin, APP-level)
        │
        ▼
  McpServerServiceImpl              ← routes tools/list, tools/call
        │                              (tracks active project dynamically)
        ├──▶ DebugToolHandler       ← XDebuggerManager, XBreakpointManager
        │         │
        │         ▼
        │    IntelliJ XDebugger / JDWP   ← actual JVM debugger
        │
        ├──▶ BuildToolHandler       ← CompilerManager
        │         │
        │         ▼
        │    IntelliJ Compiler      ← Make / Rebuild
        │
        └──▶ TestToolHandler        ← RunManager, ExecutionEnvironmentBuilder
                  │
                  ▼
             JUnit / TestNG runner  ← SMTestProxy (SM Test Framework)
```

The plugin uses **Ktor** for the HTTP layer and **kotlinx.serialization** for JSON. The server runs at the **application level** — one instance for the whole IDE, with the active project tracked dynamically as projects are opened and closed. `DebugToolHandler` talks to IntelliJ's `XDebuggerManager`, `XBreakpointManager`, and `XDebugSession` APIs. `BuildToolHandler` uses `CompilerManager` for incremental builds. `TestToolHandler` uses `RunManager` and `ExecutionEnvironmentBuilder` to run JUnit/TestNG configs and `SMTRunnerEventsListener` to collect per-test results with stack traces.

---

## Troubleshooting

**"File not found" when setting a breakpoint**
→ Use the full absolute path to the file, e.g. `/home/user/project/src/main/java/com/example/Foo.java`

**`get_session_state` returns "No active debug session"**
→ Make sure `start_debug` was called and the run config name matches exactly (use `list_run_configs` to check)

**Breakpoints don't stop execution**
→ Verify `list_breakpoints` shows `type=JavaLineBreakpointType`. If it shows a different type, the file may not be recognized as a Java source file in the current project.

**Port 63820 is already in use**
→ Another IntelliJ instance is running with the plugin loaded. Only one instance can use the port at a time.

---

## Contributing

Pull requests are welcome. For major changes, open an issue first to discuss what you'd like to change.

```bash
git clone https://github.com/alexeyleping/intellij-debug-mcp.git
cd intellij-debug-mcp
./gradlew runIde   # launches a sandboxed IntelliJ with the plugin loaded
```

---

## License

[MIT](./LICENSE) © 2025 alexeyleping
