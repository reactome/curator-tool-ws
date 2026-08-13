package org.reactome.curation.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The frontend sends operands as the labels shown in its dropdown ("Not Equal", "Regex",
 * "IS NOT NULL"), not as enum names. An unmapped label reaches listInstances as a null
 * operand, where it either drops the condition from the query or throws, so every label the
 * dropdown offers has to round-trip through map().
 */
public class ListOperandTest {

    @Test
    public void mapsTheLabelsUsedByTheFrontendDropdown() {
        assertThat(ListOperand.map("Equal")).isEqualTo(ListOperand.EQUAL);
        assertThat(ListOperand.map("Not Equal")).isEqualTo(ListOperand.NOT_EQUAL);
        assertThat(ListOperand.map("Contains")).isEqualTo(ListOperand.CONTAINS);
        assertThat(ListOperand.map("Regex")).isEqualTo(ListOperand.REGEX);
        assertThat(ListOperand.map("IS NULL")).isEqualTo(ListOperand.IS_NULL);
        assertThat(ListOperand.map("IS NOT NULL")).isEqualTo(ListOperand.IS_NOT_NULL);
    }

    @Test
    public void alsoMapsTheUnderscoredEnumStyleNames() {
        assertThat(ListOperand.map("not_equal")).isEqualTo(ListOperand.NOT_EQUAL);
        assertThat(ListOperand.map("is_null")).isEqualTo(ListOperand.IS_NULL);
        assertThat(ListOperand.map("is_not_null")).isEqualTo(ListOperand.IS_NOT_NULL);
    }

    @Test
    public void returnsNullForAnUnknownOperand() {
        assertThat(ListOperand.map("startsWith")).isNull();
    }
}
