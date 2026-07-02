package dev.aparikh.moneytransfer.agent

import org.springframework.stereotype.Component

/** How a natural-language confirmation reply was understood. */
enum class Affirmation { AFFIRM, DENY, UNCLEAR }

/**
 * Interprets a free-form confirmation reply ("yes", "yeah go ahead", "nah cancel that") as
 * [Affirmation.AFFIRM] / [Affirmation.DENY] / [Affirmation.UNCLEAR] with pure pattern matching —
 * no LLM call, so it's deterministic, free, and adds no latency on the money path.
 *
 * A curated set of affirmative/negative phrases covers the common ways to say yes/no. Anything
 * that matches **both** or **neither** resolves to `UNCLEAR`, so the caller re-prompts and money
 * never moves on an ambiguous reply. The trade-off vs. an LLM classifier is the long tail:
 * genuinely unusual phrasings fall to `UNCLEAR` rather than being understood.
 */
@Component
class AffirmationInterpreter {

    fun interpret(answer: String): Affirmation {
        val text = answer.lowercase()
        val affirm = AFFIRM.containsMatchIn(text)
        val deny = DENY.containsMatchIn(text)
        return when {
            affirm && !deny -> Affirmation.AFFIRM
            deny && !affirm -> Affirmation.DENY
            else -> Affirmation.UNCLEAR // both (e.g. "no wait, yes") or neither → re-prompt, never sends
        }
    }

    private companion object {
        // \b word boundaries so "no" doesn't match "now"/"nothing"; multi-word phrases matched literally.
        private val AFFIRM = Regex(
            "\\b(yes|yeah|yep|yup|sure|ok|okay|confirm(ed)?|approved?|proceed|absolutely|" +
                "definitely|affirmative|go ahead|go for it|do it|send( it)?|sounds good|please do)\\b",
        )
        private val DENY = Regex(
            "\\b(no|nope|nah|cancel|stop|abort|decline|do ?n[o']t|negative|never ?mind|forget it|leave it)\\b",
        )
    }
}
