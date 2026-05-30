package vip.mate.domain.wiki.dto;

/**
 * RFC-029: Individual signal contribution to a relation score.
 */
public record SignalScore(String signal, double weight, double score) {}
