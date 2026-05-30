package vip.mate.domain.wiki.dto;

/**
 * RFC-032: Lightweight raw material ID-to-title projection for batch lookups.
 */
public record RawTitleRef(Long id, String title) {}
