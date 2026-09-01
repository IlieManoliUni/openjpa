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
package org.apache.openjpa.jdbc.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.apache.openjpa.jdbc.conf.JDBCConfiguration;
import org.apache.openjpa.jdbc.conf.JDBCConfigurationImpl;
import org.apache.openjpa.kernel.exps.QueryExpressions;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

/**
 * Specification-based test suite for {@link SelectImpl}, derived with the
 * Category Partition method (Ostrand and Balcer).
 *
 * The oracle is the published contract of the class: the javadoc of the
 * {@link Select} and {@link SelectExecutor} interfaces it implements, plus
 * the javadoc of its own public helpers. The implementation was consulted
 * only to enumerate the reachable failure modes, never to derive an expected
 * value; every expected value was observed by executing the class first.
 *
 * The class is a SQL statement builder with 305 methods. Category Partition
 * works per functional unit, so the suite covers the five units whose
 * contract is written down and which are reachable without a database:
 * S1 distinctness, S2 result window, S3 select list, S4 clause builders,
 * S5 subselect structure. Units that need a {@code ClassMapping} or a live
 * connection (joins, eager selects, execution) are out of the fixture's
 * reach and are declared so rather than approximated.
 *
 * The fixture is the one the project's own {@code TestSelectImpl} uses: a
 * generic {@link DBDictionary} inside a {@link JDBCConfigurationImpl}, no
 * connection. Every test method name starts with the identifier of the test
 * frame it realises. Frames prefixed D pin divergences between the published
 * contract and the observed behaviour. The groups are static member classes
 * run through the JUnit 4 Enclosed runner.
 */
@RunWith(Enclosed.class)
public class SelectImplTest {

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    static JDBCConfiguration configuration(int joinSyntax) {
        DBDictionary dict = new DBDictionary();
        dict.joinSyntax = joinSyntax;
        dict.configureNamingRules();
        JDBCConfiguration conf = new JDBCConfigurationImpl();
        dict.setConfiguration(conf);
        conf.setDBDictionary(dict);
        return conf;
    }

    /** A fresh select over a generic dictionary using SQL92 join syntax. */
    static SelectImpl newSelect() {
        return newSelect(JoinSyntaxes.SYNTAX_SQL92);
    }

    static SelectImpl newSelect(int joinSyntax) {
        return new SelectImpl(configuration(joinSyntax));
    }

    static SQLBuffer buffer(SelectImpl select, String sql) {
        return new SQLBuffer(select.getConfiguration().getDBDictionaryInstance()).append(sql);
    }

    static String sql(SQLBuffer buffer) {
        return buffer.getSQL();
    }

    // ------------------------------------------------------------------
    // S1 - distinctness: setDistinct / isDistinct / setAutoDistinct /
    //      getAutoDistinct
    // ------------------------------------------------------------------

    /** S1 - distinctness. */
    public static class Distinctness {

        /** S1.1 - a fresh select is not distinct. */
        @Test
        public void S1_1_freshIsNotDistinct() {
            assertFalse(newSelect().isDistinct());
        }

        /** S1.2 - a fresh select is auto distinct. */
        @Test
        public void S1_2_freshIsAutoDistinct() {
            assertTrue(newSelect().getAutoDistinct());
        }

        /** S1.3 - setDistinct(true) makes the select distinct. */
        @Test
        public void S1_3_setDistinctTrue() {
            SelectImpl s = newSelect();
            s.setDistinct(true);
            assertTrue(s.isDistinct());
        }

        /** S1.4 - setDistinct(false) on a fresh select leaves it not distinct. */
        @Test
        public void S1_4_setDistinctFalse() {
            SelectImpl s = newSelect();
            s.setDistinct(false);
            assertFalse(s.isDistinct());
        }

        /** S1.5 - the last setDistinct call wins, true then false. */
        @Test
        public void S1_5_setDistinctTrueThenFalse() {
            SelectImpl s = newSelect();
            s.setDistinct(true);
            s.setDistinct(false);
            assertFalse(s.isDistinct());
        }

        /** S1.6 - the last setDistinct call wins, false then true. */
        @Test
        public void S1_6_setDistinctFalseThenTrue() {
            SelectImpl s = newSelect();
            s.setDistinct(false);
            s.setDistinct(true);
            assertTrue(s.isDistinct());
        }

        /** S1.7 - setAutoDistinct(false) is reported back. */
        @Test
        public void S1_7_setAutoDistinctFalse() {
            SelectImpl s = newSelect();
            s.setAutoDistinct(false);
            assertFalse(s.getAutoDistinct());
        }

        /** S1.8 - auto distinct can be switched back on. */
        @Test
        public void S1_8_setAutoDistinctFalseThenTrue() {
            SelectImpl s = newSelect();
            s.setAutoDistinct(false);
            s.setAutoDistinct(true);
            assertTrue(s.getAutoDistinct());
        }

        /** S1.9 - auto distinct alone does not make a select without joins distinct. */
        @Test
        public void S1_9_autoDistinctWithoutJoinsIsNotDistinct() {
            SelectImpl s = newSelect();
            s.setAutoDistinct(true);
            assertFalse(s.isDistinct());
        }

        /** S1.10 - setDistinct does not disturb the auto distinct setting. */
        @Test
        public void S1_10_setDistinctLeavesAutoDistinct() {
            SelectImpl s = newSelect();
            s.setDistinct(true);
            assertTrue(s.getAutoDistinct());
        }

        /** S1.11 - setAutoDistinct does not disturb the explicit distinct setting. */
        @Test
        public void S1_11_setAutoDistinctLeavesDistinct() {
            SelectImpl s = newSelect();
            s.setAutoDistinct(false);
            assertFalse(s.isDistinct());
        }

        /** S1.12 - an explicit distinct wins over auto distinct being off. */
        @Test
        public void S1_12_explicitDistinctWinsOverAutoOff() {
            SelectImpl s = newSelect();
            s.setAutoDistinct(false);
            s.setDistinct(true);
            assertTrue(s.isDistinct());
        }
    }

    // ------------------------------------------------------------------
    // S2 - result window: setRange / getStartIndex / getEndIndex /
    //      setExpectedResultCount / getExpectedResultCount
    // ------------------------------------------------------------------

    /** S2 - result window. */
    public static class ResultWindow {

        /** S2.1 - a fresh select starts at index zero. */
        @Test
        public void S2_1_freshStartIndex() {
            assertEquals(0L, newSelect().getStartIndex());
        }

        /** S2.2 - a fresh select ends at the largest possible index. */
        @Test
        public void S2_2_freshEndIndex() {
            assertEquals(Long.MAX_VALUE, newSelect().getEndIndex());
        }

        /** S2.3 - a normal range is stored as given. */
        @Test
        public void S2_3_normalRange() {
            SelectImpl s = newSelect();
            s.setRange(10, 20);
            assertEquals(10L, s.getStartIndex());
            assertEquals(20L, s.getEndIndex());
        }

        /** S2.4 - an empty range, start equal to end, is stored as given. */
        @Test
        public void S2_4_emptyRange() {
            SelectImpl s = newSelect();
            s.setRange(5, 5);
            assertEquals(5L, s.getStartIndex());
            assertEquals(5L, s.getEndIndex());
        }

        /** S2.5 - an inverted range is stored without validation. */
        @Test
        public void S2_5_invertedRangeIsNotValidated() {
            SelectImpl s = newSelect();
            s.setRange(20, 10);
            assertEquals(20L, s.getStartIndex());
            assertEquals(10L, s.getEndIndex());
        }

        /** S2.6 - negative bounds are stored without validation. */
        @Test
        public void S2_6_negativeRangeIsNotValidated() {
            SelectImpl s = newSelect();
            s.setRange(-1, -1);
            assertEquals(-1L, s.getStartIndex());
            assertEquals(-1L, s.getEndIndex());
        }

        /** S2.7 - a fresh select expects no particular result count. */
        @Test
        public void S2_7_freshExpectedResultCount() {
            assertEquals(0, newSelect().getExpectedResultCount());
        }

        /** S2.8 - an unforced count is kept when there are no eager to-many joins. */
        @Test
        public void S2_8_unforcedCountKeptWithoutEagerJoins() {
            SelectImpl s = newSelect();
            assertFalse(s.hasEagerJoin(true));
            s.setExpectedResultCount(3, false);
            assertEquals(3, s.getExpectedResultCount());
        }

        /** S2.9 - a forced count is kept. */
        @Test
        public void S2_9_forcedCountKept() {
            SelectImpl s = newSelect();
            s.setExpectedResultCount(3, true);
            assertEquals(3, s.getExpectedResultCount());
        }

        /** S2.10 - the last count set wins. */
        @Test
        public void S2_10_lastCountWins() {
            SelectImpl s = newSelect();
            s.setExpectedResultCount(3, false);
            s.setExpectedResultCount(7, true);
            assertEquals(7, s.getExpectedResultCount());
        }

        /** S2.11 - a negative count is stored without validation. */
        @Test
        public void S2_11_negativeCountIsNotValidated() {
            SelectImpl s = newSelect();
            s.setExpectedResultCount(-1, true);
            assertEquals(-1, s.getExpectedResultCount());
        }

        /** S2.12 - a fresh select has no joins of either kind. */
        @Test
        public void S2_12_freshHasNoJoins() {
            SelectImpl s = newSelect();
            assertFalse(s.hasJoin(true));
            assertFalse(s.hasJoin(false));
            assertFalse(s.hasEagerJoin(true));
            assertFalse(s.hasEagerJoin(false));
        }

        /** S2.13 - the large result set flag is off by default and settable. */
        @Test
        public void S2_13_largeResultSetFlag() {
            SelectImpl s = newSelect();
            assertFalse(s.isLRS());
            s.setLRS(true);
            assertTrue(s.isLRS());
            s.setLRS(false);
            assertFalse(s.isLRS());
        }
    }

    // ------------------------------------------------------------------
    // S3 - select list: select(sql, id) / selectPlaceholder /
    //      insertPlaceholder / clearPlaceholderSelects / clearSelects /
    //      getSelects / getSelectAliases
    // ------------------------------------------------------------------

    /** S3 - select list. */
    public static class SelectList {

        /** S3.1 - a fresh select selects nothing. */
        @Test
        public void S3_1_freshIsEmpty() {
            SelectImpl s = newSelect();
            assertEquals(0, s.getSelects().size());
            assertEquals(0, s.getSelectAliases().size());
            assertEquals(0, s.getIdentifierAliases().size());
        }

        /** S3.2 - selecting SQL under an id records the id and the SQL as its alias. */
        @Test
        public void S3_2_selectRecordsIdAndAlias() {
            SelectImpl s = newSelect();
            assertTrue(s.select("a", "idA"));
            assertEquals(Arrays.asList("idA"), s.getSelects());
            assertEquals(Arrays.asList("a"), s.getSelectAliases());
        }

        /** S3.3 - selecting the same id twice is refused the second time. */
        @Test
        public void S3_3_sameIdTwiceIsRefused() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            assertFalse(s.select("a", "idA"));
            assertEquals(1, s.getSelects().size());
        }

        /** S3.4 - identity is by id: a second SQL under a known id is dropped. */
        @Test
        public void S3_4_secondSqlUnderKnownIdIsDropped() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            assertFalse(s.select("b", "idA"));
            assertEquals(Arrays.asList("a"), s.getSelectAliases());
        }

        /** S3.5 - the same SQL under two ids is selected twice. */
        @Test
        public void S3_5_sameSqlUnderTwoIdsIsSelectedTwice() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            assertTrue(s.select("a", "idB"));
            assertEquals(Arrays.asList("a", "a"), s.getSelectAliases());
        }

        /** S3.6 - a null id is replaced by an internal identifier. */
        @Test
        public void S3_6_nullIdIsAccepted() {
            SelectImpl s = newSelect();
            assertTrue(s.select("a", null));
            assertEquals(1, s.getSelects().size());
            assertNotNull(s.getSelects().get(0));
        }

        /** S3.7 - with null ids, identity falls back to the SQL itself. */
        @Test
        public void S3_7_nullIdDeduplicatesBySql() {
            SelectImpl s = newSelect();
            s.select("a", null);
            assertFalse(s.select("a", null));
            assertEquals(1, s.getSelects().size());
        }

        /** S3.8 - distinct SQL under null ids is kept in order. */
        @Test
        public void S3_8_nullIdsKeepDistinctSqlInOrder() {
            SelectImpl s = newSelect();
            s.select("a", null);
            s.select("b", null);
            assertEquals(Arrays.asList("a", "b"), s.getSelectAliases());
        }

        /** S3.9 - a SQLBuffer is selected and returned as the alias itself. */
        @Test
        public void S3_9_sqlBufferIsSelectedAsAlias() {
            SelectImpl s = newSelect();
            SQLBuffer x = buffer(s, "x");
            assertTrue(s.select(x, "idX"));
            assertSame(x, s.getSelectAliases().get(0));
        }

        /** S3.10 - a placeholder is a selectable entry with its SQL as alias. */
        @Test
        public void S3_10_placeholderIsSelected() {
            SelectImpl s = newSelect();
            s.selectPlaceholder("1");
            assertEquals(Arrays.asList("1"), s.getSelectAliases());
            assertEquals(1, s.getSelects().size());
        }

        /** S3.11 - placeholders keep their position among ordinary selects. */
        @Test
        public void S3_11_placeholderKeepsPosition() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.selectPlaceholder("1");
            s.select("b", "idB");
            assertEquals(Arrays.asList("a", "1", "b"), s.getSelectAliases());
        }

        /** S3.12 - clearing placeholders removes only the placeholders. */
        @Test
        public void S3_12_clearPlaceholdersRemovesOnlyPlaceholders() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.selectPlaceholder("1");
            s.select("b", "idB");
            s.clearPlaceholderSelects();
            assertEquals(Arrays.asList("a", "b"), s.getSelectAliases());
        }

        /** S3.13 - clearing placeholders on a fresh select is harmless. */
        @Test
        public void S3_13_clearPlaceholdersOnFresh() {
            SelectImpl s = newSelect();
            s.clearPlaceholderSelects();
            assertEquals(0, s.getSelects().size());
        }

        /** S3.14 - a placeholder inserted at zero goes first. */
        @Test
        public void S3_14_insertPlaceholderAtFront() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            s.insertPlaceholder("p", 0);
            assertEquals(Arrays.asList("p", "a", "b"), s.getSelectAliases());
        }

        /** S3.15 - a placeholder inserted in the middle goes before that index. */
        @Test
        public void S3_15_insertPlaceholderInMiddle() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            s.insertPlaceholder("p", 1);
            assertEquals(Arrays.asList("a", "p", "b"), s.getSelectAliases());
        }

        /** S3.16 - a placeholder inserted at the size is appended. */
        @Test
        public void S3_16_insertPlaceholderAtEnd() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            s.insertPlaceholder("p", 2);
            assertEquals(Arrays.asList("a", "b", "p"), s.getSelectAliases());
        }

        /** S3.17 - a negative index counts from the back: -1 goes before the last. */
        @Test
        public void S3_17_insertPlaceholderMinusOne() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            s.insertPlaceholder("p", -1);
            assertEquals(Arrays.asList("a", "p", "b"), s.getSelectAliases());
        }

        /** S3.18 - a negative index counts from the back: -size goes first. */
        @Test
        public void S3_18_insertPlaceholderMinusSize() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            s.insertPlaceholder("p", -2);
            assertEquals(Arrays.asList("p", "a", "b"), s.getSelectAliases());
        }

        /** S3.19 - an index beyond the size is rejected. */
        @Test
        public void S3_19_insertPlaceholderBeyondSize() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            assertThrows(IndexOutOfBoundsException.class, () -> s.insertPlaceholder("p", 3));
        }

        /** S3.20 - a negative index beyond the size is rejected. */
        @Test
        public void S3_20_insertPlaceholderBeyondNegativeSize() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            assertThrows(IndexOutOfBoundsException.class, () -> s.insertPlaceholder("p", -3));
        }

        /** S3.21 - clearing the selects empties the list. */
        @Test
        public void S3_21_clearSelectsEmptiesList() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.select("b", "idB");
            s.clearSelects();
            assertEquals(0, s.getSelects().size());
            assertEquals(0, s.getSelectAliases().size());
        }

        /** S3.22 - after clearing, a previously selected id can be selected again. */
        @Test
        public void S3_22_selectAgainAfterClear() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.clearSelects();
            assertTrue(s.select("a", "idA"));
        }

        /** S3.23 - the selects view is unmodifiable. */
        @Test
        @SuppressWarnings("unchecked")
        public void S3_23_selectsViewIsUnmodifiable() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            List selects = s.getSelects();
            assertThrows(UnsupportedOperationException.class, () -> selects.add("x"));
        }

        /** S3.24 - reading past the end of an empty selects view fails. */
        @Test
        public void S3_24_readPastEndOfEmptySelects() {
            List selects = newSelect().getSelects();
            assertThrows(IndexOutOfBoundsException.class, () -> selects.get(0));
        }
    }

    // ------------------------------------------------------------------
    // S4 - clause builders: where / having / groupBy / orderBy /
    //      clearOrdering / toOrderAlias / addSetOperatorSQL
    // ------------------------------------------------------------------

    /** S4 - clause builders. */
    public static class Clauses {

        /** S4.1 - a fresh select has no clauses. */
        @Test
        public void S4_1_freshHasNoClauses() {
            SelectImpl s = newSelect();
            assertNull(s.getWhere());
            assertNull(s.getHaving());
            assertNull(s.getGrouping());
            assertNull(s.getOrdering());
            assertNull(s.getSetOperatorBuffer());
        }

        /** S4.2 - a single where condition is stored verbatim. */
        @Test
        public void S4_2_singleWhere() {
            SelectImpl s = newSelect();
            s.where("a = 1");
            assertEquals("a = 1", sql(s.getWhere()));
        }

        /** S4.3 - where conditions accumulate with AND. */
        @Test
        public void S4_3_whereAccumulatesWithAnd() {
            SelectImpl s = newSelect();
            s.where("a = 1");
            s.where("b = 2");
            assertEquals("a = 1 AND b = 2", sql(s.getWhere()));
        }

        /** S4.4 - an empty or null where condition is ignored, in both overloads. */
        @Test
        public void S4_4_emptyWhereIsIgnored() {
            SelectImpl s = newSelect();
            s.where("");
            s.where((String) null);
            s.where((SQLBuffer) null);
            s.where(new SQLBuffer(s.getConfiguration().getDBDictionaryInstance()));
            assertNull(s.getWhere());
        }

        /** S4.5 - an empty where condition after a real one leaves it untouched. */
        @Test
        public void S4_5_emptyWhereAfterRealOne() {
            SelectImpl s = newSelect();
            s.where("a = 1");
            s.where("");
            assertEquals("a = 1", sql(s.getWhere()));
        }

        /** S4.6 - the SQLBuffer and String overloads of where accumulate together. */
        @Test
        public void S4_6_whereOverloadsAccumulateTogether() {
            SelectImpl s = newSelect();
            s.where("a = 1");
            s.where(buffer(s, "c = 3"));
            assertEquals("a = 1 AND c = 3", sql(s.getWhere()));
        }

        /** S4.7 - where does not touch having. */
        @Test
        public void S4_7_whereDoesNotTouchHaving() {
            SelectImpl s = newSelect();
            s.where("a = 1");
            assertNull(s.getHaving());
        }

        /** S4.8 - having conditions accumulate with AND. */
        @Test
        public void S4_8_havingAccumulatesWithAnd() {
            SelectImpl s = newSelect();
            s.having("h > 1");
            s.having("k < 2");
            assertEquals("h > 1 AND k < 2", sql(s.getHaving()));
        }

        /** S4.9 - an empty or null having condition is ignored, in both overloads. */
        @Test
        public void S4_9_emptyHavingIsIgnored() {
            SelectImpl s = newSelect();
            s.having("");
            s.having((String) null);
            s.having((SQLBuffer) null);
            assertNull(s.getHaving());
        }

        /** S4.10 - a single group by term is stored verbatim. */
        @Test
        public void S4_10_singleGroupBy() {
            SelectImpl s = newSelect();
            s.groupBy("x");
            assertEquals("x", sql(s.getGrouping()));
        }

        /** S4.11 - group by terms accumulate with a comma. */
        @Test
        public void S4_11_groupByAccumulatesWithComma() {
            SelectImpl s = newSelect();
            s.groupBy("x");
            s.groupBy("y");
            assertEquals("x, y", sql(s.getGrouping()));
        }

        /** S4.12 - a repeated group by term is added once. */
        @Test
        public void S4_12_repeatedGroupByIsDeduplicated() {
            SelectImpl s = newSelect();
            s.groupBy("x");
            s.groupBy("x");
            assertEquals("x", sql(s.getGrouping()));
        }

        /** S4.13 - the SQLBuffer and String overloads of groupBy accumulate together. */
        @Test
        public void S4_13_groupByOverloadsAccumulateTogether() {
            SelectImpl s = newSelect();
            s.groupBy("x");
            s.groupBy(buffer(s, "z"));
            assertEquals("x, z", sql(s.getGrouping()));
        }

        /** S4.14 - ordering without selecting appends the term and reports nothing selected. */
        @Test
        public void S4_14_orderByWithoutSelect() {
            SelectImpl s = newSelect();
            assertFalse(s.orderBy("c", true, false));
            assertEquals("c ASC", sql(s.getOrdering()));
            assertEquals(0, s.getSelects().size());
        }

        /** S4.15 - ordering terms accumulate with a comma and keep their direction. */
        @Test
        public void S4_15_orderByAccumulatesWithDirection() {
            SelectImpl s = newSelect();
            s.orderBy("c", true, false);
            s.orderBy("d", false, false);
            assertEquals("c ASC, d DESC", sql(s.getOrdering()));
        }

        /** S4.16 - ordering with selection adds the term to the select list. */
        @Test
        public void S4_16_orderByWithSelect() {
            SelectImpl s = newSelect();
            assertTrue(s.orderBy("e", true, true));
            assertEquals("e ASC", sql(s.getOrdering()));
            assertEquals(Arrays.asList("e"), s.getSelectAliases());
        }

        /** S4.17 - ordering with selection does not re-select an already selected term. */
        @Test
        public void S4_17_orderByDoesNotReselect() {
            SelectImpl s = newSelect();
            s.select("e", "idE");
            assertFalse(s.orderBy("e", true, true));
            assertEquals(Arrays.asList("e"), s.getSelectAliases());
        }

        /** S4.18 - clearing the ordering removes the clause. */
        @Test
        public void S4_18_clearOrdering() {
            SelectImpl s = newSelect();
            s.orderBy("c", true, false);
            s.clearOrdering();
            assertNull(s.getOrdering());
        }

        /** S4.19 - clearing the ordering keeps a term that ordering had selected. */
        @Test
        public void S4_19_clearOrderingKeepsSelectedTerm() {
            SelectImpl s = newSelect();
            s.orderBy("c", true, true);
            s.clearOrdering();
            assertEquals(Arrays.asList("c"), s.getSelectAliases());
        }

        /** S4.20 - nulls precedence on a select without ordering is a no-op. */
        @Test
        public void S4_20_nullsPrecedenceWithoutOrdering() {
            SelectImpl s = newSelect();
            s.appendNullsPrecedence(1);
            assertNull(s.getOrdering());
        }

        /** S4.21 - the sentinel index -1 has no order alias. */
        @Test
        public void S4_21_orderAliasOfSentinel() {
            assertNull(SelectImpl.toOrderAlias(-1));
        }

        /** S4.22 - order aliases are the letter o followed by the index. */
        @Test
        public void S4_22_orderAliasFormat() {
            assertEquals("o0", SelectImpl.toOrderAlias(0));
            assertEquals("o15", SelectImpl.toOrderAlias(15));
        }

        /** S4.23 - the format is the same beyond the cached range. */
        @Test
        public void S4_23_orderAliasBeyondCache() {
            assertEquals("o16", SelectImpl.toOrderAlias(16));
            assertEquals("o100", SelectImpl.toOrderAlias(100));
        }

        /** S4.24 - each set operator has its keyword. */
        @Test
        public void S4_24_setOperatorKeywords() {
            assertEquals(" UNION SELECT 1", setOp(QueryExpressions.SET_OP_UNION));
            assertEquals(" UNION ALL SELECT 1", setOp(QueryExpressions.SET_OP_UNION_ALL));
            assertEquals(" INTERSECT SELECT 1", setOp(QueryExpressions.SET_OP_INTERSECT));
            assertEquals(" INTERSECT ALL SELECT 1", setOp(QueryExpressions.SET_OP_INTERSECT_ALL));
            assertEquals(" EXCEPT SELECT 1", setOp(QueryExpressions.SET_OP_EXCEPT));
            assertEquals(" EXCEPT ALL SELECT 1", setOp(QueryExpressions.SET_OP_EXCEPT_ALL));
        }

        private static String setOp(int type) {
            SelectImpl s = newSelect();
            s.addSetOperatorSQL(type, buffer(s, "SELECT 1"));
            return sql(s.getSetOperatorBuffer());
        }

        /** S4.25 - set operators accumulate in call order. */
        @Test
        public void S4_25_setOperatorsAccumulate() {
            SelectImpl s = newSelect();
            s.addSetOperatorSQL(QueryExpressions.SET_OP_UNION, buffer(s, "SELECT 1"));
            s.addSetOperatorSQL(QueryExpressions.SET_OP_INTERSECT, buffer(s, "SELECT 2"));
            assertEquals(" UNION SELECT 1 INTERSECT SELECT 2", sql(s.getSetOperatorBuffer()));
        }

        /** S4.26 - a set operator without SQL is rejected. */
        @Test
        public void S4_26_setOperatorWithoutSql() {
            SelectImpl s = newSelect();
            assertThrows(NullPointerException.class,
                () -> s.addSetOperatorSQL(QueryExpressions.SET_OP_UNION, null));
        }
    }

    // ------------------------------------------------------------------
    // S5 - subselect structure: setParent / getParent / getSubselectPath /
    //      getSubselects / indexOf / join syntax inheritance
    // ------------------------------------------------------------------

    /** S5 - subselect structure. */
    public static class SubselectStructure {

        /** S5.1 - a fresh select is a top-level select. */
        @Test
        public void S5_1_freshIsTopLevel() {
            SelectImpl s = newSelect();
            assertNull(s.getParent());
            assertNull(s.getSubselectPath());
            assertEquals(0, s.getSubselects().size());
            assertEquals(0, s.indexOf());
        }

        /** S5.2 - attaching records the parent and the path on the child. */
        @Test
        public void S5_2_attachRecordsParentAndPath() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "p");
            assertSame(parent, child.getParent());
            assertEquals("p", child.getSubselectPath());
        }

        /** S5.3 - attaching registers the child on the parent only. */
        @Test
        public void S5_3_attachRegistersChildOnParent() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "p");
            assertEquals(1, parent.getSubselects().size());
            assertTrue(parent.getSubselects().contains(child));
            assertEquals(0, child.getSubselects().size());
        }

        /** S5.4 - a child of a SQL92 parent falls back to traditional join syntax. */
        @Test
        public void S5_4_sql92ParentGivesTraditionalChild() {
            SelectImpl parent = newSelect(JoinSyntaxes.SYNTAX_SQL92);
            SelectImpl child = newSelect(JoinSyntaxes.SYNTAX_SQL92);
            child.setParent(parent, "p");
            assertEquals(JoinSyntaxes.SYNTAX_SQL92, parent.getJoinSyntax());
            assertEquals(JoinSyntaxes.SYNTAX_TRADITIONAL, child.getJoinSyntax());
        }

        /** S5.5 - a child of a traditional parent inherits traditional join syntax. */
        @Test
        public void S5_5_traditionalParentIsInherited() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            parent.setJoinSyntax(JoinSyntaxes.SYNTAX_TRADITIONAL);
            child.setParent(parent, "p");
            assertEquals(JoinSyntaxes.SYNTAX_TRADITIONAL, child.getJoinSyntax());
        }

        /** S5.6 - a child of a database-syntax parent inherits database join syntax. */
        @Test
        public void S5_6_databaseParentIsInherited() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            parent.setJoinSyntax(JoinSyntaxes.SYNTAX_DATABASE);
            child.setParent(parent, "p");
            assertEquals(JoinSyntaxes.SYNTAX_DATABASE, child.getJoinSyntax());
        }

        /** S5.7 - re-parenting moves the child from the old parent to the new one. */
        @Test
        public void S5_7_reparentMovesChild() {
            SelectImpl first = newSelect();
            SelectImpl second = newSelect();
            SelectImpl child = newSelect();
            child.setParent(first, "a");
            child.setParent(second, "b");
            assertEquals(0, first.getSubselects().size());
            assertEquals(1, second.getSubselects().size());
            assertSame(second, child.getParent());
            assertEquals("b", child.getSubselectPath());
        }

        /** S5.8 - re-attaching to the same parent updates the path without duplicating the child. */
        @Test
        public void S5_8_sameParentUpdatesPathOnly() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "a");
            child.setParent(parent, "b");
            assertEquals("b", child.getSubselectPath());
            assertEquals(1, parent.getSubselects().size());
        }

        /** S5.9 - detaching removes the child from the parent. */
        @Test
        public void S5_9_detachRemovesChildFromParent() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "a");
            child.setParent(null, null);
            assertEquals(0, parent.getSubselects().size());
            assertNull(child.getParent());
        }

        /** S5.10 - detaching a select that was never attached is harmless. */
        @Test
        public void S5_10_detachFreshIsHarmless() {
            SelectImpl s = newSelect();
            s.setParent(null, null);
            assertNull(s.getParent());
            assertNull(s.getSubselectPath());
        }

        /** S5.11 - children are listed in attachment order. */
        @Test
        public void S5_11_childrenKeepAttachmentOrder() {
            SelectImpl parent = newSelect();
            SelectImpl first = newSelect();
            SelectImpl second = newSelect();
            first.setParent(parent, "1");
            second.setParent(parent, "2");
            assertEquals(2, parent.getSubselects().size());
            assertSame(first, parent.getSubselects().get(0));
            assertSame(second, parent.getSubselects().get(1));
        }

        /** S5.12 - nesting is one level at a time: a grandchild is not listed on the grandparent. */
        @Test
        public void S5_12_grandchildIsNotListedOnGrandparent() {
            SelectImpl grandparent = newSelect();
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            parent.setParent(grandparent, "p");
            child.setParent(parent, "c");
            assertEquals(1, grandparent.getSubselects().size());
            assertEquals(1, parent.getSubselects().size());
            assertSame(parent, grandparent.getSubselects().get(0));
            assertSame(child, parent.getSubselects().get(0));
        }

        /** S5.13 - a subselect is still reported at index zero of its own union. */
        @Test
        public void S5_13_childIndexIsZero() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "p");
            assertEquals(0, child.indexOf());
        }
    }

    // ------------------------------------------------------------------
    // Divergences between the published contract and the observed behaviour
    // ------------------------------------------------------------------

    /**
     * Divergences between the javadoc and the observed behaviour.
     *
     * D8, not executable in this fixture: {@code select(SQLBuffer, id)} and
     * {@code select(String, id)} carry the same javadoc, "return true if
     * selected", but in grouping mode the first returns false and the second
     * returns true for the identical situation (SelectImpl lines 890 and 930).
     * Grouping mode is entered only through {@code groupBy(ClassMapping, ...)},
     * which needs a mapped class, so the frame is recorded from the source and
     * left without a test rather than approximated.
     */
    public static class SpecificationDivergences {

        /**
         * D1. getSetOperatorBuffer is documented to return "any set operator
         * SQL appended to this select, or null". An unknown operator is
         * silently ignored, but the buffer is allocated before the operator is
         * checked, so afterwards the method returns an empty buffer instead of
         * null. DBDictionary.toSelect then appends that empty buffer to the
         * statement.
         */
        @Test
        public void D1_unknownSetOperatorLeavesEmptyBufferNotNull() {
            SelectImpl s = newSelect();
            s.addSetOperatorSQL(99, buffer(s, "SELECT 1"));
            assertNotNull(s.getSetOperatorBuffer());
            assertTrue(s.getSetOperatorBuffer().isEmpty());

            SelectImpl t = newSelect();
            t.addSetOperatorSQL(QueryExpressions.SET_OP_NONE, buffer(t, "SELECT 1"));
            assertNotNull(t.getSetOperatorBuffer());
            assertEquals("", sql(t.getSetOperatorBuffer()));
        }

        /**
         * D2. where and having ignore empty and null input in both overloads;
         * groupBy does not. An empty string becomes an empty grouping term, a
         * null string becomes the literal text "null", and a null SQLBuffer
         * fails. Three overloads documented with the same sentence, "Add a
         * GROUP BY clause", answer three different ways to the same input.
         */
        @Test
        public void D2_groupByDoesNotGuardEmptyOrNull() {
            SelectImpl empty = newSelect();
            empty.groupBy("");
            assertNotNull(empty.getGrouping());
            assertEquals("", sql(empty.getGrouping()));

            SelectImpl nullString = newSelect();
            nullString.groupBy((String) null);
            assertEquals("null", sql(nullString.getGrouping()));

            SelectImpl nullBuffer = newSelect();
            assertThrows(NullPointerException.class, () -> nullBuffer.groupBy((SQLBuffer) null));
        }

        /**
         * D3. orderBy is documented to "optionally select ordering data if not
         * already selected". The selection is indeed de-duplicated, but the
         * ordering clause is not: ordering by the same term twice yields the
         * term twice in ORDER BY.
         */
        @Test
        public void D3_repeatedOrderByDuplicatesOrderingButNotSelection() {
            SelectImpl s = newSelect();
            s.orderBy("e", true, true);
            assertFalse(s.orderBy("e", true, true));
            assertEquals(Arrays.asList("e"), s.getSelectAliases());
            assertEquals("e ASC, e ASC", sql(s.getOrdering()));
        }

        /**
         * D4. orderBy, unlike where and having, does not guard null or empty
         * SQL: both are rendered into the ORDER BY clause as text.
         */
        @Test
        public void D4_orderByDoesNotGuardEmptyOrNull() {
            SelectImpl nullSql = newSelect();
            nullSql.orderBy((String) null, true, false);
            assertEquals("null ASC", sql(nullSql.getOrdering()));

            SelectImpl emptySql = newSelect();
            emptySql.orderBy("", true, false);
            assertEquals(" ASC", sql(emptySql.getOrdering()));
        }

        /**
         * D5. Detaching a subselect with setParent(null, path) removes it from
         * its parent but keeps the path, so a select with no parent still
         * reports a subselect path.
         */
        @Test
        public void D5_detachedSelectKeepsSubselectPath() {
            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "a");
            child.setParent(null, "x");
            assertNull(child.getParent());
            assertEquals("x", child.getSubselectPath());
        }

        /**
         * D6. getSubselects is documented to return "this select's subselects,
         * or empty collection if none". With no children it returns an
         * immutable empty list; with children it returns the live internal
         * list, which callers can modify.
         */
        @Test
        @SuppressWarnings("unchecked")
        public void D6_subselectsListIsImmutableWhenEmptyAndLiveOtherwise() {
            List none = newSelect().getSubselects();
            assertThrows(UnsupportedOperationException.class, () -> none.add("x"));

            SelectImpl parent = newSelect();
            SelectImpl child = newSelect();
            child.setParent(parent, "p");
            parent.getSubselects().add("x");
            assertEquals(2, parent.getSubselects().size());
        }

        /**
         * D7. toOrderAlias treats -1 as "no alias" but does not guard any
         * other negative index, which reaches the alias cache directly.
         */
        @Test
        public void D7_orderAliasGuardsOnlyMinusOne() {
            assertNull(SelectImpl.toOrderAlias(-1));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> SelectImpl.toOrderAlias(-2));
        }

        /**
         * D9. selectPlaceholder works on an empty select list; insertPlaceholder
         * on the same empty list fails, because it writes to internal maps
         * that only the first ordinary select creates.
         */
        @Test
        public void D9_insertPlaceholderOnEmptyListFails() {
            SelectImpl appended = newSelect();
            appended.selectPlaceholder("p");
            assertEquals(1, appended.getSelects().size());

            SelectImpl inserted = newSelect();
            assertThrows(NullPointerException.class, () -> inserted.insertPlaceholder("p", 0));
        }

        /**
         * D10. select accepts null and empty SQL under a non-null id and
         * reports them as selected, so the select list can carry a null or
         * empty alias; where and having refuse the same input.
         */
        @Test
        public void D10_selectDoesNotGuardEmptyOrNullSql() {
            SelectImpl nullSql = newSelect();
            assertTrue(nullSql.select((String) null, "id"));
            assertEquals(1, nullSql.getSelectAliases().size());
            assertNull(nullSql.getSelectAliases().get(0));

            SelectImpl emptySql = newSelect();
            assertTrue(emptySql.select("", "id"));
            assertEquals(Arrays.asList(""), emptySql.getSelectAliases());
        }
    }

    // ------------------------------------------------------------------
    // Rendering through the generic dictionary
    // ------------------------------------------------------------------

    /** End-to-end rendering of the clauses through the generic dictionary. */
    public static class Rendering {

        /** R1 - no statement exists before the select is rendered. */
        @Test
        public void R1_noSqlBeforeRendering() {
            assertNull(newSelect().getSQL());
        }

        /** R2 - rendering composes the select list and the where clause. */
        @Test
        public void R2_renderSelectAndWhere() {
            SelectImpl s = newSelect();
            s.select("a", "idA");
            s.where("a = 1");
            SQLBuffer rendered = s.toSelect(false, null);
            assertEquals("SELECT a FROM  WHERE a = 1", sql(rendered));
            assertSame(rendered, s.getSQL());
        }

        /** R3 - the count statement is available without rendering the select. */
        @Test
        public void R3_renderCount() {
            assertEquals("SELECT COUNT(*) FROM ", sql(newSelect().toSelectCount()));
        }
    }
}
