package vip.mate.domain.memory.event;

import vip.mate.domain.memory.service.DreamReport;

/**
 * Published when a dream consolidation completes successfully.
 *
 * @param report the structured dream report
 * @author MateClaw Team
 */
public record DreamCompletedEvent(DreamReport report) {}
