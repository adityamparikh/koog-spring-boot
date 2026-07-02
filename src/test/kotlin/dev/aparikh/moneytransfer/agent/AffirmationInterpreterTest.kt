package dev.aparikh.moneytransfer.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests the pure, LLM-free natural-language yes/no interpreter. Anything not clearly
 * affirmative or negative resolves to `UNCLEAR`, so an ambiguous reply never moves money.
 */
class AffirmationInterpreterTest {

    private val interpreter = AffirmationInterpreter()

    @Test
    fun `affirmative phrasings resolve to AFFIRM`() {
        listOf("yes", "YEP", "sure", "ok", "confirmed", "approved", "go ahead", "yeah go for it", "sounds good, do it")
            .forEach { assertEquals(Affirmation.AFFIRM, interpreter.interpret(it), it) }
    }

    @Test
    fun `negative phrasings resolve to DENY`() {
        listOf("no", "nope", "nah", "cancel", "stop that", "abort", "actually, never mind")
            .forEach { assertEquals(Affirmation.DENY, interpreter.interpret(it), it) }
    }

    @Test
    fun `ambiguous or unrecognized replies resolve to UNCLEAR`() {
        listOf("hmm what were the details?", "maybe later", "no wait, yes", "")
            .forEach { assertEquals(Affirmation.UNCLEAR, interpreter.interpret(it), it) }
    }

    @Test
    fun `word boundaries prevent false matches`() {
        assertEquals(Affirmation.UNCLEAR, interpreter.interpret("now")) // "no" inside "now" must not deny
        assertEquals(Affirmation.AFFIRM, interpreter.interpret("do it now")) // "do it" still affirms
    }
}
