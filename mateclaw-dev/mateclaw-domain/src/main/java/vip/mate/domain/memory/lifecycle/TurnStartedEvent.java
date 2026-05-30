package vip.mate.domain.memory.lifecycle;

/**
 * Published after prefetchAll completes, before the LLM call.
 *
 * @param context the turn context
 * @author MateClaw Team
 */
public record TurnStartedEvent(TurnContext context) {}
