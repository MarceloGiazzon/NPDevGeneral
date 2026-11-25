package com.npdev.dsl.v1.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Locale;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Diagnostics feed an English-language authoring loop. On a machine whose JVM locale is pt-BR, the schema
 * validator behind {@code npdev pack update} answered in Portuguese (and, through the Windows console, with the
 * accents corrupted), so the feedback the pack workshop built for the authoring AI ended in "- }" and named no
 * error. ModelValidatorMain and four others already pinned {@link Locale#ENGLISH}; these seven (the pack and
 * canonicalize commands) did not. Each entry point must pin it itself, because {@code PackUpdateMain} calls
 * {@code PackAddMain.run} directly and never passes through {@code PackAddMain.main}.
 *
 * <p>Every command is called with NO arguments, which each of them answers with its usage text and exit code 64
 * before reading or writing anything; the assertion is only about the locale left behind.
 */
class CliEnglishLocaleTest {

    private Locale original;
    private PrintStream originalErr;

    @BeforeEach
    void useAPortugueseMachine() {
        original = Locale.getDefault();
        originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        Locale.setDefault(Locale.forLanguageTag("pt-BR"));
    }

    @AfterEach
    void restore() {
        System.setErr(originalErr);
        Locale.setDefault(original);
    }

    private void assertPinsEnglish(String command, ToIntFunction<String[]> run) {
        Locale.setDefault(Locale.forLanguageTag("pt-BR"));
        int exit = run.applyAsInt(new String[0]);
        assertEquals(64, exit, command + " with no arguments is a usage error");
        assertEquals(Locale.ENGLISH, Locale.getDefault(), command + " must pin the English locale before it validates anything");
    }

    @Test
    void thePackCommandsPinEnglish() {
        assertPinsEnglish("PackUpdateMain", PackUpdateMain::run);
        assertPinsEnglish("PackAddMain", PackAddMain::run);
        assertPinsEnglish("PackSignMain", PackSignMain::run);
        assertPinsEnglish("PackWhyMain", PackWhyMain::run);
        assertPinsEnglish("PackListMain", PackListMain::run);
    }

    @Test
    void theSealAndCanonicalizeCommandsPinEnglish() {
        assertPinsEnglish("PackSealMain", PackSealMain::run);
        assertPinsEnglish("ModelCanonicalizeMain", ModelCanonicalizeMain::run);
    }
}
