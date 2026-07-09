/*
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package nl.mpi.tla.flat.deposit.action;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ValidateTest {

    private static final String DATE_ERROR = "cvc-pattern-valid: Value '20267' is not facet-valid "
            + "with respect to pattern '[0-9]{4}(-(0[1-9]|1[012])(-([0-2][0-9]|3[01]))?)?"
            + "(/[0-9]{4}(-(0[1-9]|1[012])(-([0-2][0-9]|3[01]))?)?)?|Unknown|Unspecified' "
            + "for type 'simpletype-Date-1---'.";

    @Test
    public void datePatternErrorIsExplainedWithoutSchemaSyntax() {
        assertEquals(
                "Use YYYY, YYYY-MM, or YYYY-MM-DD; for a range use two such dates separated by '/', "
                        + "or enter Unknown or Unspecified.",
                Validate.userText(DATE_ERROR));
        assertEquals("Date", Validate.fieldFromMessage(DATE_ERROR));
    }

    @Test
    public void genericPatternAndEnumerationErrorsArePlainLanguage() {
        assertEquals("Use the format required for this field.", Validate.userText(
                "cvc-pattern-valid: Value 'abc' is not facet-valid with respect to pattern '[A-Z]+' "
                        + "for type 'simpletype-Code-1---'."));
        assertEquals("Choose one of these allowed values: speaker, interviewer.", Validate.userText(
                "cvc-enumeration-valid: Value 'other' is not facet-valid with respect to enumeration "
                        + "'[speaker, interviewer]'. It must be a value from the enumeration."));
    }

    @Test
    public void derivativeElementErrorCanBeSuppressedForTheSameField() {
        String error = "cvc-complex-type.2.2: Element 'cmd:Date' must have no element [children], "
                + "and the value must be valid.";

        assertTrue(Validate.isCascadingValueError(error));
        assertEquals("Date", Validate.fieldFromMessage(error));
        assertEquals("Enter a valid value for this field.", Validate.userText(error));
    }

    @Test
    public void unknownSchemaDiagnosticDoesNotLeakValidatorJargon() {
        assertEquals("The value does not satisfy the requirements for this field.",
                Validate.userText("cvc-something-new: Internal schema terminology."));
    }
}
