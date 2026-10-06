package eu.wohlben.qits.ci.dto;

/**
 * One highlight of a report — "3 tests failed", "coverage −2.1%" — as its kind summarised it at
 * submit time (epic qits-754, Design §5). {@code severity} is {@code good | info | warn | bad} and
 * {@code text} at most 80 characters by the CLI's contract; this service stores and answers the
 * strip verbatim and judges neither. {@code metric}, {@code value} and {@code delta} are null when
 * the highlight measures nothing.
 */
public record CiReportHighlightDto(
    String severity, String text, String metric, Double value, Double delta) {}
