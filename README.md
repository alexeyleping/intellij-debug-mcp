# intellij-debug-mcp

> **Control the IntelliJ IDEA debugger, build system, test runner, file system, code structure, static analysis, and Git history with Claude Code (or any MCP client) via natural language.**

An IntelliJ IDEA plugin that exposes the built-in Java debugger, compiler, test runner, file/editor, PSI (Program Structure Interface), IDE inspections, and Git APIs as an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server. This lets AI coding assistants like **Claude Code** set breakpoints, step through code, inspect variables, evaluate expressions, build the project, run tests, read files, navigate the codebase, semantically understand code structure, surface IDE warnings, and trace Git history — all without you touching the IDE manually.

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
- Read any file in the project, search across the codebase, and open files in the editor
- Find classes, inspect their methods and fields, and trace all usages — without reading raw source files
- Get IDE static-analysis warnings (errors, warnings, weak warnings) for any file — just like hovering over red/yellow highlights
- Trace Git history: blame any line, browse commit log, diff any commit or file — all from Claude's terminal
- Create or update run configurations on the fly without opening the IDE UI

**When to use it:**
- You describe a bug to Claude Code and want it to investigate autonomously
- You want Claude to verify a hypothesis ("is `sum` really 60 here?") without context-switching to the IDE
- You're doing pair debugging — Claude watches the debugger while you read the code
- Automated debugging workflows where the AI runs, breaks, inspects, and patches in one go
- You want Claude to fix a compilation error end-to-end: build → read errors → patch → rebuild
- Full TDD loop: Claude writes a fix, runs the tests, reads failures, patches again — until green
- Claude needs to explore an unfamiliar codebase: find relevant files, read them, search for usages
- Claude needs to understand architecture: find a class, inspect its API, trace where it's used
- Claude needs to audit code quality: get IDE inspections, see all warnings, fix them automatically
- Claude needs to understand code evolution: git blame a suspicious line, read the commit that introduced it

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

Search for **"IntelliJ Debug MCP"** in **Settings → Plugins → Marketplace**, or install directly from the
[JetBrains Plugin Marketplace](https://plugins.jetbrains.com/plugin/TODO).

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
`select_frame`, `resume`, `pause`, `list_run_configs`, `create_run_config`, `update_run_config`,
`build_project`, `get_build_errors`,
`run_tests`, `get_test_results`,
`read_file`, `list_files`, `find_files`, `search_in_files`,
`get_open_files`, `open_file`,
`find_class`, `find_usages`, `get_file_structure`,
`get_inspections`,
`git_blame`, `git_log`, `git_diff`

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

### PSI (Code Structure)

| Tool | Description | Parameters |
|------|-------------|------------|
| `find_class` | Find a class by short name or fully-qualified name. Returns file, line, fields, and methods with signatures. | `name` (string, required) |
| `find_usages` | Find all usages of a class, method, or field in the project. Use `className` to search for a specific class member. | `name` (string, required), `className` (string, optional) |
| `get_file_structure` | Get the full structure of a file: all classes with their fields and methods, signatures, and line numbers. | `path` (string, required) |

### File / Editor

| Tool | Description | Parameters |
|------|-------------|------------|
| `read_file` | Read file contents (absolute or relative to project root). Supports pagination. | `path` (string, required), `offset` (int, optional), `limit` (int, optional — default 500) |
| `list_files` | List files and directories at a path. Defaults to project root. | `path` (string, optional) |
| `find_files` | Find files matching a glob pattern (e.g. `*.kt`, `src/**/*.java`). | `pattern` (string, required) |
| `search_in_files` | Full-text search across project files. Optionally filter by file pattern. | `query` (string, required), `filePattern` (string, optional) |
| `get_open_files` | List all files currently open in the editor. | — |
| `open_file` | Open a file in the editor, optionally at a specific line. | `path` (string, required), `line` (int, optional) |

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

### Git

| Tool | Description | Parameters |
|------|-------------|------------|
| `git_blame` | Show who last modified each line of a file. Optionally narrow to a line range. | `path` (string, required), `startLine` (int, optional), `endLine` (int, optional) |
| `git_log` | Show commit history. Optionally filter by file path. Default 20 commits, max 100. | `path` (string, optional), `maxCount` (int, optional) |
| `git_diff` | Show diff. Optionally filter by file path and/or commit/range (e.g. `HEAD~1`, `abc123..HEAD`). | `path` (string, optional), `commit` (string, optional) |

### Inspections (Static Analysis)

| Tool | Description | Parameters |
|------|-------------|------------|
| `get_inspections` | Return static analysis problems from the IDE daemon for open files or a specific file. Open the file in the editor before calling. | `path` (string, optional), `severity` (string, optional — `ERROR`, `WARNING`, `WEAK_WARNING`) |

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
| `create_run_config` | Create a new run configuration. `type`: `application` (default) or `junit`. | `name` (string, required), `type` (string, optional), `mainClass` (string, optional), `testClass` (string, optional), `vmOptions` (string, optional), `programArgs` (string, optional), `workingDir` (string, optional) |
| `update_run_config` | Update an existing run configuration by name. Only provided fields are changed. | `name` (string, required), `mainClass` / `testClass` / `vmOptions` / `programArgs` / `workingDir` (all optional) |
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

### Codebase exploration

```
You: "Find all places where the payment service is called."

Claude:
1. search_in_files "PaymentService"  → 6 matches in 4 files:
                                        src/checkout/CheckoutService.java:42
                                        src/orders/OrderProcessor.java:87
                                        ...
2. read_file "src/checkout/CheckoutService.java" offset=38 limit=10
                                     → lines 38–48: context around the call
3. open_file "src/checkout/CheckoutService.java" line=42
                                     → file opened in editor at line 42

"PaymentService is called in 4 places. The main entry point is
 CheckoutService.java:42 — here's what the call looks like: ..."
```

### Code structure exploration (PSI)

```
You: "Where is PaymentService used and what methods does it have?"

Claude:
1. find_class "PaymentService"
   → Class: com.example.payments.PaymentService
     File: src/main/java/com/example/payments/PaymentService.java:12
     Fields (1):
       PaymentGateway gateway
     Methods (3):
       void processPayment(Order order, BigDecimal amount)
       boolean refund(String transactionId)
       List<Transaction> getHistory(String customerId)

2. find_usages "PaymentService"
   → Found 5 usage(s) of 'PaymentService':
       src/checkout/CheckoutService.java:34: PaymentService paymentService
       src/checkout/CheckoutService.java:89: paymentService.processPayment(order, total)
       src/orders/OrderProcessor.java:61: new PaymentService(gateway)
       ...

"PaymentService has 3 public methods. It's injected in CheckoutService
 and instantiated in OrderProcessor. The main call is processPayment
 at CheckoutService.java:89."
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

### Git history exploration

```
You: "Who introduced the bug in the average() method and when?"

Claude:
1. git_blame "src/main/java/com/example/Calculator.java" startLine=10 endLine=20
   → 14: a3f92c1d | 2025-03-12 | bob | fix: correct average calculation
     15: a3f92c1d |  |  |

2. git_log "src/main/java/com/example/Calculator.java" maxCount=5
   → a3f92c1d | 2025-03-12 | bob | fix: correct average calculation
     7e84b02f | 2025-03-10 | alice | refactor: extract Calculator class

3. git_diff commit="a3f92c1d"
   → -return (double) sum / numbers.size();
     +return (double) sum / (numbers.size() - 1);

"Bob introduced the off-by-one on 2025-03-12 in commit a3f92c1d —
 changed size() to (size()-1) by mistake."
```

### IDE inspections

```
You: "Are there any code quality issues in Calculator.java?"

Claude:
1. open_file "src/main/java/com/example/Calculator.java"
2. get_inspections "src/main/java/com/example/Calculator.java" severity="WARNING"
   → WARNING  line 14: Division by zero is possible
     WARNING  line 28: Variable 'result' is never used
     ERROR    line 35: Cannot resolve symbol 'Mathh'

"Three issues found: potential division-by-zero on line 14,
 unused variable on line 28, and a typo 'Mathh' on line 35."
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
        ├──▶ TestToolHandler        ← RunManager, ExecutionEnvironmentBuilder
        │         │
        │         ▼
        │    JUnit / TestNG runner  ← SMTestProxy (SM Test Framework)
        │
        ├──▶ FileToolHandler        ← LocalFileSystem, FileEditorManager
        │         │
        │         ▼
        │    VirtualFile / OpenFileDescriptor  ← VFS + Editor navigation
        │
        ├──▶ PsiToolHandler         ← PsiManager, JavaPsiFacade, PsiShortNamesCache
        │         │
        │         ▼
        │    PSI tree / ReferencesSearch  ← semantic code model
        │
        ├──▶ InspectionsToolHandler ← DaemonCodeAnalyzer, HighlightInfo
        │         │
        │         ▼
        │    IDE Inspections daemon  ← static analysis (errors/warnings)
        │
        ├──▶ GitToolHandler         ← GitRepositoryManager, git CLI
        │         │
        │         ▼
        │    Git4Idea / git blame/log/diff
        │
        └──▶ RunConfigToolHandler   ← RunManager
                  │
                  ▼
             Run Configuration API  ← create / update application & junit configs
```

The plugin uses **Ktor** for the HTTP layer and **kotlinx.serialization** for JSON. The server runs at the **application level** — one instance for the whole IDE, with the active project tracked dynamically as projects are opened and closed. `DebugToolHandler` talks to IntelliJ's `XDebuggerManager`, `XBreakpointManager`, and `XDebugSession` APIs. `BuildToolHandler` uses `CompilerManager` for incremental builds. `TestToolHandler` uses `RunManager` and `ExecutionEnvironmentBuilder` to run JUnit/TestNG configs and `SMTRunnerEventsListener` to collect per-test results with stack traces. `FileToolHandler` uses IntelliJ's `LocalFileSystem` VFS to read files and walk directory trees, and `FileEditorManager` / `OpenFileDescriptor` for editor navigation. `PsiToolHandler` uses `JavaPsiFacade` and `PsiShortNamesCache` to resolve classes by name (supporting both Java and Kotlin), `ReferencesSearch` for find-usages, and `PsiRecursiveElementVisitor` to extract file structure. `InspectionsToolHandler` reads `HighlightInfo` entries from the IDE's `DaemonCodeAnalyzer` to surface static analysis results. `GitToolHandler` uses IntelliJ's Git4Idea integration to run blame, log, and diff operations. `RunConfigToolHandler` uses `RunManager` to create and update application and JUnit run configurations programmatically.

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
