package com.example.inventory.management.common.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void 조회한_행이_size보다_많으면_size개로_자르고_다음_페이지가_있다() {
        PageResponse<Integer> response = PageResponse.of(List.of(1, 2, 3), 0, 2);

        assertThat(response.content()).containsExactly(1, 2);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    void 조회한_행이_size와_같으면_다음_페이지가_없다() {
        PageResponse<Integer> response = PageResponse.of(List.of(1, 2), 1, 2);

        assertThat(response.content()).containsExactly(1, 2);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 조회한_행이_없으면_빈_content와_다음_페이지_없음을_반환한다() {
        PageResponse<Integer> response = PageResponse.of(List.of(), 5, 10);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(5);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void map은_content만_변환하고_페이지_정보는_유지한다() {
        PageResponse<String> response = PageResponse.of(List.of(1, 2, 3), 2, 2).map(String::valueOf);

        assertThat(response.content()).containsExactly("1", "2");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    void 조회_limit과_offset은_size_기준으로_계산된다() {
        assertThat(PageResponse.fetchLimit(10)).isEqualTo(11);
        assertThat(PageResponse.offset(3, 10)).isEqualTo(30L);
        assertThat(PageResponse.offset(Integer.MAX_VALUE, 100)).isEqualTo((long) Integer.MAX_VALUE * 100);
    }
}
