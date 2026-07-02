# Side note: `runBlocking` vs `runTest` in coroutine tests

> Context: the step-2 agent tests (`AgentServiceTest`) call a `suspend fun` (`AgentService.chat`)
> and use `runBlocking { ... }`. This note records *why* — and when `runTest` would be the
> better tool instead. Read at leisure.

## The one question that decides it

**Does the code under test have coroutine behavior I want to control — `delay`s to skip, or
coroutines I launched (`launch`/`async`) that I want to order deterministically — running on
the test's own dispatcher?**

- **Yes →** use `runTest`.
- **No →** `runBlocking` is the simpler equal.

`runTest` is *not* "the test-flavored `runBlocking`." It's specifically a tool for controlling
**virtual time** and **coroutine scheduling**. It has exactly two superpowers over
`runBlocking`:

1. **A virtual clock** — `delay(...)` completes instantly instead of really waiting.
2. **A test scheduler** — it deterministically orders coroutines launched *on it*, and fails
   if one never completes (leak detection).

Both only fire when the code under test uses `delay`/`launch`/`async` **on `runTest`'s own
dispatcher**. If there's nothing like that, `runTest` degrades to "`runBlocking` plus an extra
dependency (`org.jetbrains.kotlinx:kotlinx-coroutines-test`)."

## Where `runTest` earns its keep

```kotlin
// code under test — has real waits
suspend fun pollUntilReady(): Status {
    repeat(10) {
        val s = check()
        if (s.ready) return s
        delay(30.seconds)     // ← real 30-second waits
    }
    error("gave up")
}

@Test fun readyEventually() = runTest {   // virtual clock fast-forwards every delay
    val s = pollUntilReady()               // completes in millis, not 5 real minutes
    assertTrue(s.ready)
}
```

With `runBlocking`, that test would burn *minutes* of wall-clock time.

## Why our step-2 tests use `runBlocking`

```kotlin
suspend fun chat(...): AgentChatResult {
    // no delay(), no launch{}, no async{} — just awaits execute(), and Koog runs its
    // coroutines on Dispatchers.Default/IO (NOT the test scheduler)
    val reply = multiLLMPromptExecutor.execute(prompt, model).textContent()
    ...
}

@Test fun happyPath() = runBlocking { assertEquals("Hello", chat(...).reply) }
```

- No `delay` → the virtual clock has nothing to skip.
- No coroutines launched on the test scheduler → nothing for it to order; Koog's internal
  coroutines run on **real** dispatchers `runTest` can't see or control.

So swapping to `runTest` here would behave identically, while adding a dependency. Neither
superpower fires because the `suspend` is *plumbing* (Koog's API happens to be suspending),
not behavior we're asserting.

## Caveat (don't over-index on this)

The official kotlinx-coroutines guidance leans toward `runTest` as the *default* for suspend
tests, and standardizing on `runTest` everywhere for consistency is perfectly defensible. The
claim here is narrow: *for these specific step-2 tests* it buys nothing functional.

This flips at **step 4+**: strategy-graph tests that use Koog's `agents-test`
(`withTesting()`, controlled tool timings / injected dispatchers) will genuinely want
`runTest`'s virtual time and scheduler. Expect to add `kotlinx-coroutines-test` then.

## Related: why `chat` is `suspend` (no `runBlocking` in production)

`PromptExecutor.execute` / `AIAgent.run` are `suspend`, and Spring MVC invokes suspending
controller methods natively (via `kotlinx-coroutines-reactor`, already on the classpath), so
`AgentService.chat` and `AgentController.chat` are `suspend` end-to-end — no `runBlocking`
bridge in production code. `runBlocking` survives only in tests, to drive the suspend call to
completion. Note the cost this imposes on MockMvc: a suspend handler completes via async
dispatch, so controller tests must `asyncDispatch(result)` before asserting (see
`AgentControllerTest`).
