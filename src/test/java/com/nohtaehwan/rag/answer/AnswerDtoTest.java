package com.nohtaehwan.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AnswerDtoTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void answerRequest_question이_있으면_검증을_통과한다() {
        Set<ConstraintViolation<AnswerRequest>> violations = VALIDATOR.validate(new AnswerRequest("질문"));

        assertThat(violations).isEmpty();
    }

    @Test
    void answerRequest_question이_null이거나_공백이면_검증에_실패한다() {
        assertThat(VALIDATOR.validate(new AnswerRequest(null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new AnswerRequest(""))).isNotEmpty();
        assertThat(VALIDATOR.validate(new AnswerRequest("   "))).isNotEmpty();
    }

    @Test
    void answerResponse_필드가_그대로_보존된다() {
        AnswerSource source = new AnswerSource(1L, "제목", "a.md", 0, 0.1);
        AnswerResponse response = new AnswerResponse("답변", List.of(source));

        assertThat(response.answer()).isEqualTo("답변");
        assertThat(response.sources()).containsExactly(source);
    }
}
