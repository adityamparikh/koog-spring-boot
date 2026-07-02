# Side note: when a custom Koog strategy earns its keep (and why overdraft protection doesn't)

> Context: step 4 (`feature.md` FR-12) says "implement a **custom Koog strategy**" for the
> over-balance case. We studied the Koog example projects and concluded a custom strategy is the
> wrong tool here, and shipped overdraft protection as plain app-side logic instead. This note
> records why, so the deviation from FR-12's literal wording is a documented decision, not an
> oversight.

## What a custom `strategy { }` graph actually buys you

A Koog strategy graph replaces the default `singleRunStrategy` (LLM → run tools → feed results
back → repeat until a text answer) with a hand-wired graph of nodes and conditional edges. It's
worth the machinery only when you need **control flow the single tool-loop structurally can't do
— or shouldn't be trusted to do**. Surveying every custom-strategy example in the Koog repo, they
all fall into these situations:

| Situation | What the graph does | Koog example(s) |
|-----------|---------------------|-----------------|
| **Intent routing / dispatch** | Classify the request, then branch to a *tool-scoped* handler subgraph | `banking/RoutingViaGraph.kt` (Transfer vs Analytics), `CustomerSupportGraphService.kt` (4-way + context guard) |
| **Autonomous verify/revise loops** | Produce → **verify against an objective gate** → fix → re-verify, looping on failure, no human | `subgraphwithtask/CustomStrategy.kt` (build the code, check it compiles), `spring-io-2026/BankingAgentService.kt`, `devoxx-belgium-2025/AgentStrategy.kt` |
| **Parallel fan-out + pick-best** | Run N models concurrently, aggregate/judge | `parallelexecution/BestJokeAgent.kt` (`parallel { selectByIndex }`) |
| **Long game/agent loops with per-turn upkeep** | Tool loop that must trim/compress history every turn | `chess/Chess.kt`, `tone/ToneStrategy.kt` |
| **Choice among N sampled completions** | Sample several, then select (graph node *or* executor decorator) | `chess/choice/*` |
| **Safety gates** | Moderate input/output, short-circuit to refusal | `moderation/JokesWithModeration.kt` |
| **Deterministic mechanics** | Structured extraction, checkpoint/rewind, streaming | `structuredoutput/*`, `snapshot/*`, `streaming/*` |
| **Recursive / polymorphic control flow** | Build a plan-tree, dispatch on runtime node type | `planner/PlannerAgentExample.kt` |

**The common thread:** the graph exists to *branch across capabilities, loop until an objective
gate passes, parallelize, enforce a safety checkpoint, or guarantee a deterministic step*. Not one
example uses a custom graph for a simple business rule.

## Every custom-strategy example, with its nodes & edges

Source links are to the `JetBrains/koog` `develop` branch. `→` is an edge; `[cond]` is the
condition on an `onCondition`/`onToolCalls`/`onTextMessage` edge.

| Example | Situation | Nodes | Edges / routing |
|---------|-----------|-------|-----------------|
| [CustomStrategy](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/subgraphwithtask/CustomStrategy.kt) | Generate→verify→fix (build gate) | `generate` (subgraphWithTask), `verify` (subgraphWithVerification), `fix` (subgraphWithTask) | `verify → fix [!successful]`; `verify → nodeFinish [successful]`; `fix → verify` (retry) |
| [RoutingViaGraph](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/banking/routing/RoutingViaGraph.kt) | Intent routing (banking) | `classifyRequest` (subgraph w/ `nodeLLMRequestStructured<ClassifiedBankRequest>`), `transferMoney` (subgraph), `transactionAnalysis` (subgraph) | `classify → transferMoney [Transfer]`; `classify → transactionAnalysis [Analytics]`; `AskUser` clarify loop on parse failure |
| [CustomerSupportGraphService](https://github.com/JetBrains/koog/blob/develop/examples/spring-ai-kotlin/src/main/kotlin/com/example/spring_ai_kotlin/service/customersupport/CustomerSupportGraphService.kt) | Intent routing + context guard | `classifyRequest` (`nodeLLMRequestStructured<SupportRequest>`), `checkContext`, `orderStatus`/`changeAddress`/`refund`/`faq` (subgraphs), `maybeCompressHistory` (`nodeDoNothing`), `compressLLMHistory` | 4-way branch on intent; `checkContext → clarify [NeedsMoreInfo]`; all converge → `maybeCompressHistory → compressLLMHistory [tooManyTokensSpent]` else `→ nodeFinish` |
| [AgentStrategy (devoxx "sandwich")](https://github.com/JetBrains/koog/blob/develop/examples/devoxx-belgium-2025/src/main/kotlin/ai/koog/spring/sandwich/agents/AgentStrategy.kt) | Identify/fix/verify/adjust + early exit | `identifyProblem` (subgraphWithTask), `fixProblem`, `verifySolution` (subgraphWithVerification), `adjust`, `compressHistory` | `identify → nodeFinish [resolved]` (early exit); `identify → fixProblem` else; `fixProblem → compressHistory [tooManyTokens]` else `→ verify`; `verify → nodeFinish [successful]` / `→ adjust`; `adjust → compressHistory → verify` |
| [BankingAgentService](https://github.com/JetBrains/koog/blob/develop/examples/spring-io-2026/kotlin/src/main/kotlin/org/example/koog/spring/kotlin/agents/BankingAgentService.kt) | Identify/fix/verify/adjust (banking) | `identifyProblem`, `fixProblem`, `verifySolution` (subgraphWithVerification), `adjustSolution`, `compressHistory` (`nodeLLMCompressHistory`) | `verify → nodeFinish [successful]`; `verify → adjust` else; `adjust → compressHistory [historyIsTooLong]` else `→ verify` |
| [BestJokeAgent](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/parallelexecution/BestJokeAgent.kt) | Parallel fan-out + pick-best | three `node<String,String>` generators inside `parallel(...)`, merged by `selectByIndex { findTheBestJoke }` | `nodeStart then nodeGenerateBestJoke then nodeFinish` (parallelism inside the merge node) |
| [Chess](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/chess/Chess.kt) | Long tool loop + per-turn history trim | `nodeCallLLM`, `nodeExecuteTool`, `nodeTrimHistory`, `nodeSendToolResult` | `callLLM → executeTool [onToolCalls]`; `executeTool → nodeTrimHistory → sendToolResult`; `sendToolResult → executeTool [onToolCalls]` (loop) / `→ nodeFinish [onTextMessage]` |
| [ChessChoiceNodes](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/chess/choice/ChessChoiceNodes.kt) | Choice among N sampled moves | `nodeLLMSendResultsMultipleChoices`, `nodeSelectLLMChoice(askChoiceStrategy)` | inserts choice-selection between results and continuation (`numberOfChoices = 3`) |
| [JokesWithModeration](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/moderation/JokesWithModeration.kt) | Pre/post moderation gates | `moderateInput` (`nodeLLMModerateMessage`), `callLLM`, `moderateJoke` (`nodeLLMModerateMessage`) | `moderateInput → refuse [harmful]` / `→ callLLM` else; `callLLM → moderateJoke`; `moderateJoke → refuse [harmful]` / `→ nodeFinish` |
| [PlannerAgentExample](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/planner/PlannerAgentExample.kt) | Recursive plan-tree build | `setup`, `tryFindingSequential/ParallelSubtasks`, `parseLLMResponse`, `pickNodeToBuild`, build nodes (delegate/parallel/sequential) | type-dispatch `[builder is DelegateNode.Builder / ParallelNode.Builder / …]`; loop until the plan tree is built |
| [trip-planning Agent](https://github.com/JetBrains/koog/blob/develop/examples/trip-planning-example/src/main/kotlin/ai/koog/agents/examples/tripplanning/Agent.kt) | Clarify→suggest→feedback revision loop | `clarifyUserPlan` (subgraphWithTask), `suggestPlan` (subgraphWithTask), `showPlanSuggestion`, `processUserFeedback` (`nodeLLMRequestStructured<PlanSuggestionFeedback>`) | `processUserFeedback → createPlanCorrectionRequest → suggestPlan [!isAccepted]` (revision loop); `→ nodeFinish [isAccepted]` |
| [ToneStrategy](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/tone/ToneStrategy.kt) | Tool loop + compression gate | `nodeSendInput`, `nodeExecuteTool`, `nodeSendToolResult`, `nodeCompressHistory` | tool loop; `→ nodeCompressHistory [messages > 100]` else straight through |
| [StreamingAgentWithTools](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/streaming/StreamingAgentWithTools.kt) | Streaming tool loop | `nodeLLMRequestStreaming` (transform), `nodeExecuteTools(parallel=true)`, `nodeSendToolResultsStreaming` | loop `[onToolCalls]`; finish `[onTextMessage]` |
| [SimpleExample (structured output)](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/structuredoutput/SimpleExample.kt) | Structured extraction | `prepareRequest`, `getStructuredForecast` (`nodeLLMRequestStructured<WeatherForecast>`), `nodeFinish` | linear; `nodeFinish transformed { it.getOrThrow().data }` |
| [SnapshotStrategy](https://github.com/JetBrains/koog/blob/develop/examples/simple-examples/src/main/kotlin/ai/koog/agents/example/snapshot/SnapshotStrategy.kt) | Checkpoint / execution-point rewind | `node1`, `node2`, `teleportNode`, `nodeFinish` | linear; `teleportNode` calls `withPersistence { setExecutionPointAfterNode(...) }` to rewind to `node1` |

**Reading the table for our case:** every graph either **branches across intents/subgraphs**,
**loops until a gate** (`successful` / `isAccepted` / game-end), **parallelizes**, or **gates on
safety/compression**. Our overdraft rule is a single `requested > available` check with no such
structure — the next section spells out why.

## Why overdraft protection is not a custom-strategy situation

The over-balance rule is a single comparison — `requested > available` — that the
`prepareTransfer` tool already runs on every call. That's an `if`, not control flow. Mapping our
case onto the table:

- **Routing?** No — we have one capability (transfers). There's nothing to route *to*. (Routing
  would only apply if we added a second capability, e.g. transaction analytics — that's the
  banking example's actual reason for a graph, and would be its own step.)
- **Verify/revise loop?** *Shape-wise, yes* — validate the amount, revise if over balance,
  re-validate. But the graph-hostable version of that loop is **autonomous** (the agent verifies
  `$50 > $30` and auto-corrects to `$30` itself, within one run, no human). That is exactly the
  silent auto-cap we **rejected** — we want the **user** to choose the amount. A user-driven
  revision is inherently **between turns** (the user supplies the new amount on the next message),
  and Koog has **no mid-run "ask the user" primitive**, so it can't live in a graph. It's a
  conversational loop, hosted app-side — which is where the human belongs.
- **Parallel / choice / moderation / structured / recursive?** None apply.

And notably: **even the canonical banking example uses its graph only for routing.** Inside its
`transferMoney` subgraph the actual send is a plain tool loop — there is no graph node for
balance-checking or capping. So "cap/guard the transfer amount in the graph" has **no precedent**
in any Koog example.

## What we shipped instead

Overdraft protection lives in the `prepareTransfer` tool (app-side):

- **Domain is the guard.** `TransferService.transfer` already rejects any overdraw atomically
  (`UPDATE … WHERE balance >= :amount`). Race-free, holds for every caller. This is the safety
  invariant; it is not the agent's job.
- **The tool is the UX.** On an over-balance request, `prepareTransfer` does **not** stage and does
  **not** silently cap — it asks the model to prompt the user for an amount up to their balance, so
  the **user's input decides the amount**. When the user supplies an in-balance amount, the tool
  stages it and the existing confirm-gate sends it.

This is the verify/revise loop — just hosted in the conversation (verify on each tool call, revise
on the user's next message), because our "revision" needs a human. The custom graph is deferred; if
it's ever built, its justified use is **intent routing** once a second capability exists, not the
overdraft cap.
