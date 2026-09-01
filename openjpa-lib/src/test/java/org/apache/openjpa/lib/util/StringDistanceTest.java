/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openjpa.lib.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

/**
 * Specification-based test suite for {@link StringDistance}, derived with the
 * Category Partition method (Ostrand and Balcer).
 *
 * The oracle is the published contract of the class, that is the javadoc of
 * each method plus the definition of the Levenshtein distance the javadoc
 * links to. The implementation was consulted only to enumerate the reachable
 * failure modes, never to derive an expected value.
 *
 * Every test method name starts with the identifier of the test frame it
 * realises, so that the suite can be read against the partition tables in
 * the report. Frames prefixed L belong to the first functional unit
 * (getLevenshteinDistance), frames prefixed C to the second one
 * (getClosestLevenshteinDistance), frames prefixed F pin divergences
 * between the published contract and the observed behaviour. The five
 * groups are static member classes run through the JUnit 4 Enclosed runner.
 */
@RunWith(Enclosed.class)
public class StringDistanceTest {

    /** Query used throughout the second functional unit. */
    private static final String QUERY = "nam";

    /**
     * Candidate set used throughout the second functional unit.
     * Distances from QUERY are: name = 1, nome = 2, namez = 2, so the
     * minimum is unique and equal to 1.
     */
    private static final String[] CANDIDATES = { "name", "nome", "namez" };

    private static List<String> candidateList() {
        return new ArrayList<>(Arrays.asList(CANDIDATES));
    }

    // ------------------------------------------------------------------
    // FU1 - getLevenshteinDistance(String s, String t)
    // ------------------------------------------------------------------

    /** FU1 - getLevenshteinDistance(s, t). */
    public static class LevenshteinDistance {

        /** L1 - a null first string is rejected. */
        @Test
        public void L1_nullFirstStringThrows() {
            assertThrows(NullPointerException.class,
                    () -> StringDistance.getLevenshteinDistance(null, "abc"));
        }

        /** L2 - a null second string is rejected. */
        @Test
        public void L2_nullSecondStringThrows() {
            assertThrows(NullPointerException.class,
                    () -> StringDistance.getLevenshteinDistance("abc", null));
        }

        /** L3 - two empty strings are at distance zero. */
        @Test
        public void L3_bothEmpty() {
            assertEquals(0, StringDistance.getLevenshteinDistance("", ""));
        }

        /** L4 - from the empty string the distance is the other length. */
        @Test
        public void L4_emptyToNonEmpty() {
            assertEquals(3, StringDistance.getLevenshteinDistance("", "abc"));
        }

        /** L5 - to the empty string the distance is the other length. */
        @Test
        public void L5_nonEmptyToEmpty() {
            assertEquals(3, StringDistance.getLevenshteinDistance("abc", ""));
        }

        /** L6 - two equal single characters are at distance zero. */
        @Test
        public void L6_singleCharacterEqual() {
            assertEquals(0, StringDistance.getLevenshteinDistance("a", "a"));
        }

        /** L7 - two different single characters are at distance one. */
        @Test
        public void L7_singleCharacterDifferent() {
            assertEquals(1, StringDistance.getLevenshteinDistance("a", "b"));
        }

        /** L8 - identical multi character strings are at distance zero. */
        @Test
        public void L8_identicalStrings() {
            assertEquals(0, StringDistance.getLevenshteinDistance("abc", "abc"));
        }

        /** L9 - one substitution costs one. */
        @Test
        public void L9_singleSubstitution() {
            assertEquals(1, StringDistance.getLevenshteinDistance("kitten", "sitten"));
        }

        /** L10 - one insertion costs one. */
        @Test
        public void L10_singleInsertion() {
            assertEquals(1, StringDistance.getLevenshteinDistance("cat", "cart"));
        }

        /** L11 - one deletion costs one. */
        @Test
        public void L11_singleDeletion() {
            assertEquals(1, StringDistance.getLevenshteinDistance("cart", "cat"));
        }

        /** L12 - mixed edits are counted as a minimum, not as a sum. */
        @Test
        public void L12_mixedEdits() {
            // kitten -> sitten -> sittin -> sitting : two substitutions and one insertion
            assertEquals(3, StringDistance.getLevenshteinDistance("kitten", "sitting"));
        }

        /** L13 - strings over disjoint alphabets cost one substitution each. */
        @Test
        public void L13_disjointAlphabets() {
            assertEquals(3, StringDistance.getLevenshteinDistance("abc", "xyz"));
        }

        /** L14 - the comparison is case sensitive. */
        @Test
        public void L14_caseIsSignificant() {
            assertEquals(3, StringDistance.getLevenshteinDistance("abc", "ABC"));
        }

        /** L15 - leading whitespace is significant. */
        @Test
        public void L15_whitespaceIsSignificant() {
            assertEquals(2, StringDistance.getLevenshteinDistance("  a", "a"));
        }

        /** L16 - the distance is symmetric. */
        @Test
        public void L16_symmetry() {
            assertEquals(
                    StringDistance.getLevenshteinDistance("kitten", "sitting"),
                    StringDistance.getLevenshteinDistance("sitting", "kitten"));
            assertEquals(
                    StringDistance.getLevenshteinDistance("flaw", "lawn"),
                    StringDistance.getLevenshteinDistance("lawn", "flaw"));
            assertEquals(
                    StringDistance.getLevenshteinDistance("", "abc"),
                    StringDistance.getLevenshteinDistance("abc", ""));
        }

        /** L17 - a string is at distance zero only from itself. */
        @Test
        public void L17_identityOfIndiscernibles() {
            assertEquals(0, StringDistance.getLevenshteinDistance("kitten", "kitten"));
            assertTrue(StringDistance.getLevenshteinDistance("kitten", "sitting") > 0);
        }

        /** L18 - the distance satisfies the triangle inequality. */
        @Test
        public void L18_triangleInequality() {
            int ab = StringDistance.getLevenshteinDistance("kitten", "sitten");
            int bc = StringDistance.getLevenshteinDistance("sitten", "sitting");
            int ac = StringDistance.getLevenshteinDistance("kitten", "sitting");
            assertTrue("d(a,c)=" + ac + " must not exceed d(a,b)+d(b,c)=" + (ab + bc),
                    ac <= ab + bc);
        }

        /** L19 - the distance never exceeds the length of the longer string. */
        @Test
        public void L19_upperBound() {
            String s = "kitten";
            String t = "sitting";
            assertTrue(StringDistance.getLevenshteinDistance(s, t)
                    <= Math.max(s.length(), t.length()));
        }
    }

    // ------------------------------------------------------------------
    // FU2a - getClosestLevenshteinDistance without a threshold
    // ------------------------------------------------------------------

    /** FU2a - getClosestLevenshteinDistance without a threshold. */
    public static class ClosestWithoutThreshold {

        /** C1 - a null candidate array yields no match. */
        @Test
        public void C1_nullArray() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, (String[]) null));
        }

        /** C2 - a null candidate collection yields no match. */
        @Test
        public void C2_nullCollection() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, (Collection) null));
        }

        /** C3 - an empty candidate array yields no match. */
        @Test
        public void C3_emptyArray() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, new String[0]));
        }

        /** C4 - an empty candidate collection yields no match. */
        @Test
        public void C4_emptyCollection() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, new ArrayList<String>()));
        }

        /** C5 - a singleton candidate is always returned when no threshold is given. */
        @Test
        public void C5_singletonAlwaysMatches() {
            assertEquals("zzzzzzzz",
                    StringDistance.getClosestLevenshteinDistance(QUERY, new String[] { "zzzzzzzz" }));
        }

        /** C6 - the nearest candidate is returned from an array. */
        @Test
        public void C6_nearestFromArray() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES));
        }

        /** C7 - the nearest candidate is returned from a collection. */
        @Test
        public void C7_nearestFromCollection() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance(QUERY, candidateList()));
        }

        /** C8 - an exact match is returned. */
        @Test
        public void C8_exactMatch() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance("name", CANDIDATES));
        }

        /** C9 - without a threshold even a very distant candidate is returned. */
        @Test
        public void C9_unboundedThresholdAcceptsAnyDistance() {
            assertNotNull(StringDistance.getClosestLevenshteinDistance("zzzz", CANDIDATES));
        }

        /** C10 - a null element in the candidates is rejected. */
        @Test
        public void C10_nullCandidateElement() {
            assertThrows(NullPointerException.class,
                    () -> StringDistance.getClosestLevenshteinDistance("name",
                            new String[] { "name", null }));
        }

        /** C11 - a non String element in a raw collection is rejected. */
        @Test
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public void C11_nonStringCandidateElement() {
            Collection raw = new ArrayList();
            raw.add("name");
            raw.add(Integer.valueOf(7));
            assertThrows(ClassCastException.class,
                    () -> StringDistance.getClosestLevenshteinDistance("name", raw));
        }
    }

    // ------------------------------------------------------------------
    // FU2b - getClosestLevenshteinDistance with an absolute threshold
    // ------------------------------------------------------------------

    /** FU2b - getClosestLevenshteinDistance with an int threshold. */
    public static class ClosestWithIntThreshold {

        /** C12 - a null candidate array yields no match whatever the threshold. */
        @Test
        public void C12_nullArray() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, (String[]) null, 3));
        }

        /** C13 - an empty candidate array yields no match. */
        @Test
        public void C13_emptyArray() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, new String[0], 3));
        }

        /** C14 - a threshold below the best distance rejects the match. */
        @Test
        public void C14_thresholdBelowBestDistance() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 0));
        }

        /** C15 - a threshold equal to the best distance accepts the match. */
        @Test
        public void C15_thresholdEqualToBestDistance() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 1));
        }

        /** C16 - a threshold above the best distance accepts the match. */
        @Test
        public void C16_thresholdAboveBestDistance() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 2));
        }

        /** C17 - a zero threshold still accepts an exact match. */
        @Test
        public void C17_zeroThresholdAcceptsExactMatch() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance("name", CANDIDATES, 0));
        }

        /** C18 - a negative threshold rejects every candidate. */
        @Test
        public void C18_negativeThresholdRejectsEverything() {
            assertNull(StringDistance.getClosestLevenshteinDistance("name", CANDIDATES, -1));
            assertNull(StringDistance.getClosestLevenshteinDistance("name", CANDIDATES,
                    Integer.MIN_VALUE));
        }

        /** C19 - the maximum threshold accepts an arbitrarily distant candidate. */
        @Test
        public void C19_maximumThresholdAcceptsEverything() {
            assertEquals("name", StringDistance.getClosestLevenshteinDistance("zzzz", CANDIDATES,
                    Integer.MAX_VALUE));
        }

        /** C20 - candidates beyond the threshold yield no match. */
        @Test
        public void C20_allCandidatesTooFar() {
            assertNull(StringDistance.getClosestLevenshteinDistance("zzzz", CANDIDATES, 2));
        }

        /** C21 - the array and the collection overload agree. */
        @Test
        public void C21_arrayAndCollectionAgree() {
            assertEquals(
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 1),
                    StringDistance.getClosestLevenshteinDistance(QUERY, candidateList(), 1));
        }

        /** C22 - a null query is rejected when the candidate list is not empty. */
        @Test
        public void C22_nullQueryWithCandidates() {
            assertThrows(NullPointerException.class,
                    () -> StringDistance.getClosestLevenshteinDistance(null, CANDIDATES, 3));
        }

        /** C23 - a null query with no candidates yields no match. */
        @Test
        public void C23_nullQueryWithoutCandidates() {
            assertNull(StringDistance.getClosestLevenshteinDistance(null, new String[0], 3));
        }
    }

    // ------------------------------------------------------------------
    // FU2c - getClosestLevenshteinDistance with a percentage threshold
    // ------------------------------------------------------------------

    /** FU2c - getClosestLevenshteinDistance with a float percentage. */
    public static class ClosestWithFloatThreshold {

        /** C24 - a null candidate array yields no match. */
        @Test
        public void C24_nullArray() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, (String[]) null, 0.5f));
        }

        /** C25 - a null candidate collection yields no match. */
        @Test
        public void C25_nullCollection() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, (Collection) null, 0.5f));
        }

        /** C26 - an empty candidate array yields no match. */
        @Test
        public void C26_emptyArray() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, new String[0], 0.5f));
        }

        /** C27 - a zero percentage admits only an exact match. */
        @Test
        public void C27_zeroPercentage() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 0.0f));
            assertEquals("name",
                    StringDistance.getClosestLevenshteinDistance("name", CANDIDATES, 0.0f));
        }

        /** C28 - a percentage that truncates below one rejects a distance of one. */
        @Test
        public void C28_percentageBelowTheRoundingStep() {
            // 3 * 0.33 = 0.99, truncated to an absolute threshold of 0
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 0.33f));
        }

        /** C29 - a percentage that truncates to one accepts a distance of one. */
        @Test
        public void C29_percentageAtTheRoundingStep() {
            // 3 * 0.34 = 1.02, truncated to an absolute threshold of 1
            assertEquals("name",
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 0.34f));
        }

        /** C30 - a full percentage accepts the nearest candidate. */
        @Test
        public void C30_fullPercentage() {
            assertEquals("name",
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 1.0f));
        }

        /** C31 - a percentage below zero is clamped to zero. */
        @Test
        public void C31_percentageClampedFromBelow() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, -5.0f));
            assertEquals(
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 0.0f),
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, -5.0f));
        }

        /** C32 - a percentage above one is clamped to one. */
        @Test
        public void C32_percentageClampedFromAbove() {
            assertEquals(
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 1.0f),
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 99.0f));
        }

        /** C33 - a null query yields no match instead of failing. */
        @Test
        public void C33_nullQueryReturnsNull() {
            assertNull(StringDistance.getClosestLevenshteinDistance(null, CANDIDATES, 0.5f));
        }

        /** C34 - an empty query can never reach a non empty candidate. */
        @Test
        public void C34_emptyQueryNeverMatches() {
            // the threshold is a percentage of a length of zero, hence always zero
            assertNull(StringDistance.getClosestLevenshteinDistance("", new String[] { "ab" }, 1.0f));
            assertEquals("",
                    StringDistance.getClosestLevenshteinDistance("", new String[] { "" }, 1.0f));
        }
    }

    // ------------------------------------------------------------------
    // Divergences between the published contract and the observed behaviour
    // ------------------------------------------------------------------

    /** Divergences between the javadoc and the observed behaviour. */
    public static class SpecificationDivergences {

        /**
         * F1. The javadoc of both float overloads states that the threshold is
         * "the specified percentage of the length of the candidate string".
         * The observed behaviour uses the length of the query instead. The two
         * readings are distinguishable, and on this pair they disagree in both
         * directions, so the divergence cannot be explained away as rounding.
         */
        @Test
        public void F1_percentageIsTakenOverTheQuery() {
            String shortOne = "ab";
            String longOne = "abcdefghij";
            assertEquals(8, StringDistance.getLevenshteinDistance(shortOne, longOne));

            // candidate length 10, a full percentage would admit a distance of 8
            assertNull(StringDistance.getClosestLevenshteinDistance(shortOne,
                    new String[] { longOne }, 1.0f));

            // candidate length 2, a full percentage would reject a distance of 8
            assertEquals(shortOne, StringDistance.getClosestLevenshteinDistance(longOne,
                    new String[] { shortOne }, 1.0f));
        }

        /** F1 expected - the percentage should be taken over the candidate. */
        @Test
        @Ignore("F1 - enable once the javadoc and the implementation are reconciled")
        public void F1_percentageShouldBeTakenOverTheCandidate() {
            String shortOne = "ab";
            String longOne = "abcdefghij";
            assertEquals(longOne, StringDistance.getClosestLevenshteinDistance(shortOne,
                    new String[] { longOne }, 1.0f));
            assertNull(StringDistance.getClosestLevenshteinDistance(longOne,
                    new String[] { shortOne }, 1.0f));
        }

        /**
         * F2. A null query is a normal input for the percentage overloads,
         * which answer null, and a fatal input for every other overload of the
         * same family, which raise a NullPointerException. Nothing in the
         * javadoc announces the difference.
         */
        @Test
        public void F2_nullQueryIsHandledInconsistently() {
            assertNull(StringDistance.getClosestLevenshteinDistance(null, CANDIDATES, 0.5f));
            assertThrows(NullPointerException.class,
                    () -> StringDistance.getClosestLevenshteinDistance(null, CANDIDATES, 3));
            assertThrows(NullPointerException.class,
                    () -> StringDistance.getClosestLevenshteinDistance(null, CANDIDATES));
        }

        /** F2 expected - every overload should treat a null query the same way. */
        @Test
        @Ignore("F2 - enable once the overload family agrees on a null query")
        public void F2_nullQueryShouldBeHandledUniformly() {
            assertNull(StringDistance.getClosestLevenshteinDistance(null, CANDIDATES, 3));
            assertNull(StringDistance.getClosestLevenshteinDistance(null, CANDIDATES));
        }

        /**
         * F3. When two candidates are equally near, the one that wins is the
         * one the iteration reaches first. The javadoc promises "the candidate
         * string with the closest Levenshtein distance" as though it were
         * unique. For a collection whose iteration order is not specified the
         * answer is therefore not determined by the arguments alone.
         */
        @Test
        public void F3_tieIsResolvedByIterationOrder() {
            assertEquals(1, StringDistance.getLevenshteinDistance("hat", "bat"));
            assertEquals(1, StringDistance.getLevenshteinDistance("hat", "cat"));

            assertEquals("bat", StringDistance.getClosestLevenshteinDistance("hat",
                    new String[] { "bat", "cat" }, 1));
            assertEquals("cat", StringDistance.getClosestLevenshteinDistance("hat",
                    new String[] { "cat", "bat" }, 1));

            Collection<String> insertionOrdered = new LinkedHashSet<>(Arrays.asList("cat", "bat"));
            assertEquals("cat",
                    StringDistance.getClosestLevenshteinDistance("hat", insertionOrdered, 1));

            Collection<String> sorted = new TreeSet<>(Arrays.asList("cat", "bat"));
            assertEquals("bat", StringDistance.getClosestLevenshteinDistance("hat", sorted, 1));
        }

        /**
         * F4. A not a number percentage is neither rejected nor clamped. Both
         * Math.min and Math.max propagate it, the cast to int yields zero, and
         * the call silently degrades to an exact match search.
         */
        @Test
        public void F4_notANumberPercentageDegradesSilently() {
            assertNull(StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, Float.NaN));
            assertEquals("name",
                    StringDistance.getClosestLevenshteinDistance("name", CANDIDATES, Float.NaN));
            // a well formed percentage of the same magnitude would have matched
            assertEquals("name",
                    StringDistance.getClosestLevenshteinDistance(QUERY, CANDIDATES, 1.0f));
        }

        /**
         * F5. The distance is counted in UTF-16 code units rather than in
         * characters, so a single emoji costs two edits. The javadoc defines
         * the distance as "the minimum number of changes", which a reader
         * would apply to characters.
         */
        @Test
        public void F5_supplementaryCharacterCostsTwoEdits() {
            String grinning = "\uD83D\uDE00";  // U+1F600, a single code point
            assertEquals(1, grinning.codePointCount(0, grinning.length()));
            assertEquals(2, StringDistance.getLevenshteinDistance("a", grinning));
        }

        /** F5 expected - a supplementary character should cost one edit. */
        @Test
        @Ignore("F5 - enable once the distance is counted in code points")
        public void F5_supplementaryCharacterShouldCostOneEdit() {
            assertEquals(1, StringDistance.getLevenshteinDistance("a", "\uD83D\uDE00"));
        }

        /**
         * F6. SonarCloud rule java:S1118 on line 29 of the original class: a
         * class exposing nothing but static members still exposed the default
         * public constructor. The automated refactoring of milestone 4 added a
         * private constructor. Before that step this assertion failed and its
         * negation held; the pair was the acceptance oracle of the refactoring.
         */
        @Test
        public void F6_utilityClassIsNotInstantiable() {
            Constructor<?>[] constructors = StringDistance.class.getDeclaredConstructors();
            assertEquals(1, constructors.length);
            assertTrue("a utility class should declare a private constructor",
                    Modifier.isPrivate(constructors[0].getModifiers()));
        }
    }
}
