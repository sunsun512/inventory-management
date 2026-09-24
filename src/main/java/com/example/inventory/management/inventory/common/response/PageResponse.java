package com.example.inventory.management.inventory.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

/**
 * Offset page without a total count: the query fetches {@link #fetchLimit(int) size + 1} rows and
 * {@link #of} trims the extra row, which only signals that a next page exists. This avoids a
 * separate count query on every request.
 */
@Schema(description = "페이지 응답. 전체 건수 없이 다음 페이지 존재 여부만 제공한다")
public record PageResponse<T>(
        @Schema(description = "현재 페이지 항목") List<T> content,
        @Schema(description = "현재 페이지 번호 (0부터 시작)", example = "0") int page,
        @Schema(description = "페이지 크기 (최대 100으로 제한된 실제 적용 값)", example = "10") int size,
        @Schema(description = "다음 페이지 존재 여부", example = "true") boolean hasNext
) {

    /** Largest page size served; larger requests are silently clamped to it. */
    public static final int MAX_SIZE = 100;

    public PageResponse {
        content = List.copyOf(content);
    }

    /**
     * @param fetched rows read with {@code offset(page, size)} and {@code fetchLimit(size)}, i.e. at
     *                most {@code size + 1}; a row beyond {@code size} means there is a next page
     */
    public static <T> PageResponse<T> of(List<T> fetched, int page, int size) {
        boolean hasNext = fetched.size() > size;
        List<T> content = hasNext ? fetched.subList(0, size) : fetched;
        return new PageResponse<>(content, page, size, hasNext);
    }

    /** Rows to fetch for a page: one extra row to detect whether a next page exists. */
    public static int fetchLimit(int size) {
        return size + 1;
    }

    /** Row offset of a page, computed in long so a huge page number cannot overflow. */
    public static long offset(int page, int size) {
        return (long) page * size;
    }

    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = content.stream().<R>map(mapper).toList();
        return new PageResponse<>(mapped, page, size, hasNext);
    }
}
