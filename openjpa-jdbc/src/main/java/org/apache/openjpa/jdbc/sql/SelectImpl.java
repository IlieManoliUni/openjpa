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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.Stack;
import java.util.TreeMap;

import org.apache.openjpa.jdbc.conf.JDBCConfiguration;
import org.apache.openjpa.jdbc.kernel.EagerFetchModes;
import org.apache.openjpa.jdbc.kernel.JDBCFetchConfiguration;
import org.apache.openjpa.jdbc.kernel.JDBCLockManager;
import org.apache.openjpa.jdbc.kernel.JDBCStore;
import org.apache.openjpa.jdbc.kernel.JDBCStoreManager;
import org.apache.openjpa.jdbc.meta.ClassMapping;
import org.apache.openjpa.jdbc.meta.FieldMapping;
import org.apache.openjpa.jdbc.meta.Joinable;
import org.apache.openjpa.jdbc.meta.ValueMapping;
import org.apache.openjpa.jdbc.meta.strats.RelationStrategies;
import org.apache.openjpa.jdbc.schema.Column;
import org.apache.openjpa.jdbc.schema.ForeignKey;
import org.apache.openjpa.jdbc.schema.Table;
import org.apache.openjpa.kernel.StoreContext;
import org.apache.openjpa.kernel.exps.Context;
import org.apache.openjpa.kernel.exps.QueryExpressions;
import org.apache.openjpa.kernel.exps.Value;
import org.apache.openjpa.lib.log.Log;
import org.apache.openjpa.lib.util.Localizer;
import org.apache.openjpa.lib.util.StringUtil;
import org.apache.openjpa.meta.ClassMetaData;
import org.apache.openjpa.util.ApplicationIds;
import org.apache.openjpa.util.Id;
import org.apache.openjpa.jdbc.identifier.DBIdentifier;
import org.apache.openjpa.util.InternalException;

import static java.util.Collections.emptyIterator;

/**
 * Standard {@link Select} implementation. Usage note: though this class
 * implements {@link Joins}, it should not be used for joining directly.
 * Instead, use the return value of {@link #newJoins}.
 *
 * @author Abe White
 */
public class SelectImpl
    implements Select, PathJoins {

    private static final int NONAUTO_DISTINCT = 2 << 0;
    private static final int DISTINCT = 2 << 1;
    private static final int NOT_DISTINCT = 2 << 2;
    private static final int IMPLICIT_DISTINCT = 2 << 3;
    private static final int TO_MANY = 2 << 4;
    private static final int AGGREGATE = 2 << 5;
    private static final int LOB = 2 << 6;
    private static final int OUTER = 2 << 7;
    private static final int LRS = 2 << 8;
    private static final int EAGER_TO_ONE = 2 << 9;
    private static final int EAGER_TO_MANY = 2 << 10;
    private static final int RECORD_ORDERED = 2 << 11;
    private static final int GROUPING = 2 << 12;
    private static final int FORCE_COUNT = 2 << 13;
    private static final String AND = " AND ";

    private static final String[] TABLE_ALIASES = new String[16];
    private static final String[] ORDER_ALIASES = new String[16];
    private static final Object[] NULL_IDS = new Object[16];
    private static final Object[] PLACEHOLDERS = new Object[50];

    private static final Localizer _loc = Localizer.forPackage(Select.class);

    static {
        for (int i = 0; i < TABLE_ALIASES.length; i++)
            TABLE_ALIASES[i] = "t" + i;
        for (int i = 0; i < ORDER_ALIASES.length; i++)
            ORDER_ALIASES[i] = "o" + i;
        for (int i = 0; i < NULL_IDS.length; i++)
            NULL_IDS[i] = new NullId();
        for (int i = 0; i < PLACEHOLDERS.length; i++)
            PLACEHOLDERS[i] = new Placeholder();
    }

    private final JDBCConfiguration configuration;
    private final DBDictionary databaseDictionary;

    // map of variable + relation path + table keys to the correct alias index:
    // each relation path/table combination should have a unique alias because
    // it represents a separate object; for example, if a Person class has a
    // 'parent' field representing another Person and also has an 'address'
    // field of type Address:
    // 'address.street' should map to a different table alias than
    // 'parent.address.street' for the purposes of comparisons
    private Map aliasMappings = null;

    // map of indexes to table aliases like 'TABLENAME t0'
    private SortedMap tables = null;

    // combined list of selected ids and map of each id to its alias
    protected final Selects selects = newSelects();
    private List ordered = null;
    private List grouped = null;

    // flags
    private int flags = 0;
    private int joinSyntaxType = 0;
    private long startIdx = 0;
    private long endIdx = Long.MAX_VALUE;
    private int nullIds = 0;
    private int orders = 0;
    private int placeholders = 0;
    private int expectedResultSize = 0;

    // query clauses
    private SQLBuffer ordering = null;
    private SQLBuffer setOperatorBuf = null;
    private SQLBuffer where = null;
    private SQLBuffer grouping = null;
    private SQLBuffer having = null;
    private SQLBuffer full = null;

    // joins to add to the end of our where clause, and joins to prepend to
    // all selects (see select(classmapping) method)
    private SelectJoins selectJoins = null;
    private Stack preJoins = null;

    // map of joins+keys to eager selects and global set of eager keys; the
    // same key can't be used more than once
    private Map eagerSelectMap = null;
    private Set eagerKeys = null;

    // subselect support
    private List<SelectImpl> subsels = null;
    private SelectImpl parentSelect = null;
    private String subPath = null;
    private boolean containsSubselect = false;

    // from select if this select selects from a tmp table created by another
    private SelectImpl from = null;
    protected SelectImpl outerSelect = null;

    // JPQL Query context this select is associated with
    private Context queryContext = null;

    // A path navigation is begin with this schema alias
    private String rootSchemaAlias = null;
    private ClassMapping tpcMeta = null;
    private List joinedTables = null;
    private List exJoinedTables = null;

    @Override
    public ClassMapping getTablePerClassMeta() {
        return tpcMeta;
    }
    @Override
    public void setTablePerClassMeta(ClassMapping meta) {
        tpcMeta = meta;
    }

    @Override
    public void setJoinedTableClassMeta(List meta) {
        joinedTables = meta;
    }

    @Override
    public List getJoinedTableClassMeta() {
        return joinedTables;
    }

    @Override
    public void setExcludedJoinedTableClassMeta(List meta) {
        exJoinedTables = meta;
    }

    @Override
    public List getExcludedJoinedTableClassMeta() {
        return exJoinedTables;
    }


    /**
     * Helper method to return the proper table alias for the given alias index.
     */
    static String toAlias(int index) {
        if (index == -1)
            return null;
        if (index < TABLE_ALIASES.length)
            return TABLE_ALIASES[index];
        return "t" + index;
    }

    /**
     * Helper method to return the proper order alias for the given order
     * column index.
     */
    public static String toOrderAlias(int index) {
        if (index == -1)
            return null;
        if (index < ORDER_ALIASES.length)
            return ORDER_ALIASES[index];
        return "o" + index;
    }

    /**
     * Constructor. Supply configuration.
     */
    public SelectImpl(JDBCConfiguration conf) {
        configuration = conf;
        databaseDictionary = configuration.getDBDictionaryInstance();
        joinSyntaxType = databaseDictionary.joinSyntax;
        selects.databaseDictionary = databaseDictionary;
    }

    @Override
    public void setContext(Context context) {
        if (queryContext == null) {
            queryContext = context;
            queryContext.setSelect(this);
        }
    }

    @Override
    public Context ctx() {
        return queryContext;
    }

    @Override
    public void setSchemaAlias(String schemaAlias) {
        rootSchemaAlias = schemaAlias;
    }

    //
    // SelectExecutor implementation
    //

    @Override
    public JDBCConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public SQLBuffer toSelect(boolean forUpdate, JDBCFetchConfiguration fetch) {
        full = databaseDictionary.toSelect(this, forUpdate, fetch);
        return full;
    }

    @Override
    public SQLBuffer getSQL() {
        return full;
    }

    @Override
    public SQLBuffer toSelectCount() {
        return databaseDictionary.toSelectCount(this);
    }

    @Override
    public boolean getAutoDistinct() {
        return (flags & NONAUTO_DISTINCT) == 0;
    }

    @Override
    public void setAutoDistinct(boolean val) {
        if (val)
            flags &= ~NONAUTO_DISTINCT;
        else
            flags |= NONAUTO_DISTINCT;
    }

    @Override
    public boolean isDistinct() {
        return (flags & NOT_DISTINCT) == 0 && ((flags & DISTINCT) != 0
            || ((flags & NONAUTO_DISTINCT) == 0
            && (flags & IMPLICIT_DISTINCT) != 0));
    }

    @Override
    public void setDistinct(boolean distinct) {
        // need two flags in case set not_distinct, then a to-many join happens
        // and distinct flag gets set automatically
        if (distinct) {
            flags |= DISTINCT;
            flags &= ~NOT_DISTINCT;
        } else {
            flags |= NOT_DISTINCT;
            flags &= ~DISTINCT;
        }
    }

    @Override
    public boolean isLRS() {
        return (flags & LRS) != 0;
    }

    @Override
    public void setLRS(boolean lrs) {
        if (lrs)
            flags |= LRS;
        else
            flags &= ~LRS;
    }

    @Override
    public int getExpectedResultCount() {
        // if the count isn't forced and we have to-many eager joins that could
        // throw the count off, don't pay attention to it
        if ((flags & FORCE_COUNT) == 0 && hasEagerJoin(true))
            return 0;
        return expectedResultSize;
    }

    @Override
    public void setExpectedResultCount(int expectedResultCount, boolean force) {
        expectedResultSize = expectedResultCount;
        if (force)
            flags |= FORCE_COUNT;
        else
            flags &= ~FORCE_COUNT;
    }

    @Override
    public int getJoinSyntax() {
        return joinSyntaxType;
    }

    @Override
    public void setJoinSyntax(int joinSyntax) {
        joinSyntaxType = joinSyntax;
    }

    @Override
    public boolean supportsRandomAccess(boolean forUpdate) {
        return databaseDictionary.supportsRandomAccessResultSet(this, forUpdate);
    }

    @Override
    public boolean supportsLocking() {
        return databaseDictionary.supportsLocking(this);
    }

    @Override
    public boolean hasMultipleSelects() {
        if (eagerSelectMap == null)
            return false;
        Map.Entry entry;
        for (Object o : eagerSelectMap.entrySet()) {
            entry = (Map.Entry) o;
            if (entry.getValue() != this)
                return true;
        }
        return false;
    }

    @Override
    public int getCount(JDBCStore store)
        throws SQLException {
        Connection conn = null;
        PreparedStatement stmnt = null;
        ResultSet rs = null;
        try {
            SQLBuffer sql = toSelectCount();
            conn = store.getNewConnection();
            stmnt = prepareStatement(conn, sql, null,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, false);
            databaseDictionary.setQueryTimeout(stmnt,
                    store.getFetchConfiguration().getQueryTimeout());
            rs = executeQuery(conn, stmnt, sql, false, store);
            int count =  getCount(rs);

            return databaseDictionary.applyRange(this, count);
        } finally {
            if (rs != null)
                try { rs.close(); } catch (SQLException se) { /* Preserve the original exception while closing resources. */ }
            if (stmnt != null)
                try { stmnt.close(); } catch (SQLException se) { /* Preserve the original exception while closing resources. */ }
            if (conn != null)
                try { conn.close(); } catch (SQLException se) { /* Preserve the original exception while closing resources. */ }
        }
    }

    @Override
    public Result execute(JDBCStore store, JDBCFetchConfiguration fetch)
        throws SQLException {
        if (fetch == null)
            fetch = store.getFetchConfiguration();
        return execute(store.getContext(), store, fetch,
            fetch.getReadLockLevel());
    }

    @Override
    public Result execute(JDBCStore store, JDBCFetchConfiguration fetch,
        int lockLevel)
        throws SQLException {
        if (fetch == null)
            fetch = store.getFetchConfiguration();
        return execute(store.getContext(), store, fetch, lockLevel);
    }

    /**
     * Execute this select in the context of the given store manager. The
     * context is passed in separately for profiling purposes.
     */
    protected Result execute(StoreContext ctx, JDBCStore store,
        JDBCFetchConfiguration fetch, int lockLevel)
        throws SQLException {
        boolean forUpdate = false;
        if (!isAggregate() && grouping == null) {
            JDBCLockManager lm = store.getLockManager();
            if (lm != null)
                forUpdate = lm.selectForUpdate(this, lockLevel);
        }

        logEagerRelations();
        SQLBuffer sql = toSelect(forUpdate, fetch);
        boolean isLRS = isLRS();
        int rsType = (isLRS && supportsRandomAccess(forUpdate))
            ? -1 : ResultSet.TYPE_FORWARD_ONLY;
        Connection conn = store.getConnection();
        PreparedStatement stmnt = null;
        ResultSet rs = null;
        try {
            stmnt = prepareExecutionStatement(conn, sql, fetch, rsType, isLRS);

            databaseDictionary.setTimeouts(stmnt, fetch, forUpdate);

            rs = executeQuery(conn, stmnt, sql, isLRS, store);
        } catch (SQLException se) {
            // clean up statement
            if (stmnt != null)
                try { stmnt.close(); } catch (SQLException se2) { /* Preserve the original exception while closing resources. */ }
            try { conn.close(); } catch (SQLException se2) { /* Preserve the original exception while closing resources. */ }
            throw se;
        }
        return getEagerResult(conn, stmnt, rs, store, fetch, forUpdate, sql);
    }

    private PreparedStatement prepareExecutionStatement(Connection conn,
        SQLBuffer sql, JDBCFetchConfiguration fetch, int rsType,
        boolean isLRS) throws SQLException {
        if (isLRS)
            return prepareStatement(conn, sql, fetch, rsType, -1, true);
        else
            return prepareStatement(conn, sql, null, rsType, -1, false);
    }

    /**
     * Execute our eager selects, adding the results under the same keys
     * to the given result.
     */
    private static void addEagerResults(SelectResult res, SelectImpl sel,
        JDBCStore store, JDBCFetchConfiguration fetch)
        throws SQLException {
        if (sel.eagerSelectMap == null)
            return;

        // execute eager selects
        Map.Entry entry;
        Result eres;
        Map eager;
        for (Object o : sel.eagerSelectMap.entrySet()) {
            entry = (Map.Entry) o;

            // simulated batched selects for inner/outer joins; for separate
            // selects, don't pass on lock level, because they're probably
            // for relations and therefore should use default level
            if (entry.getValue() == sel)
                eres = res;
            else
                eres = ((SelectExecutor) entry.getValue()).execute(store,
                        fetch);

            eager = res.getEagerMap(false);
            if (eager == null) {
                eager = new HashMap();
                res.setEagerMap(eager);
            }
            eager.put(entry.getKey(), eres);
        }
    }


    /**
     * This method is to provide override for non-JDBC or JDBC-like
     * implementation of preparing statement.
     */
    protected PreparedStatement prepareStatement(Connection conn,
        SQLBuffer sql, JDBCFetchConfiguration fetch, int rsType,
        int rsConcur, boolean isLRS) throws SQLException {
        if (fetch == null)
            return sql.prepareStatement(conn, rsType, rsConcur);
        else
            return sql.prepareStatement(conn, fetch, rsType, -1);
    }

    /**
     * This method is to provide override for non-JDBC or JDBC-like
     * implementation of preparing statement.
     */
    public PreparedStatement prepareStatement(Connection conn,
        String sql) throws SQLException {
        return conn.prepareStatement(sql);
    }

    /**
     * This method is to provide override for non-JDBC or JDBC-like
     * implementation of executing query.
     */
    protected ResultSet executeQuery(Connection conn, PreparedStatement stmnt,
        SQLBuffer sql, boolean isLRS, JDBCStore store) throws SQLException {
        return stmnt.executeQuery();
    }

    /**
     * This method is to provide override for non-JDBC or JDBC-like
     * implementation of executing query.
     */
    public ResultSet executeQuery(Connection conn, PreparedStatement stmnt,
        String sql, JDBCStore store, Object[] params, Column[] cols)
        throws SQLException {
        return stmnt.executeQuery();
    }

    /**
     * This method is to provide override for non-JDBC or JDBC-like
     * implementation of getting count from the result set.
     */
    protected int getCount(ResultSet rs) throws SQLException {
        rs.next();
        return rs.getInt(1);
    }

    /**
     * This method is to provide override for non-JDBC or JDBC-like
     * implementation of executing eager selects.
     */
    public Result getEagerResult(Connection conn,
        PreparedStatement stmnt, ResultSet rs, JDBCStore store,
        JDBCFetchConfiguration fetch, boolean forUpdate, SQLBuffer sql)
        throws SQLException {
        SelectResult res = new SelectResult(conn, stmnt, rs, databaseDictionary);
        res.setSelect(this);
        res.setStore(store);
        res.setLocking(forUpdate);
        try {
            addEagerResults(res, this, store, fetch);
        } catch (SQLException se) {
            res.close();
            throw se;
        }
        return res;
    }

    //
    // Select implementation
    //

    @Override
    public int indexOf() {
        return 0;
    }

    @Override
    public List getSubselects() {
        return (subsels == null) ? Collections.emptyList() : subsels;
    }

    @Override
    public Select getParent() {
        return parentSelect;
    }

    @Override
    public String getSubselectPath() {
        return subPath;
    }

    @Override
    public void setParent(Select parent, String path) {
        subPath = path;

        if (parent == parentSelect)
            return;
        if (parentSelect != null)
            parentSelect.subsels.remove(this);

        //### right now we can't use sql92 joins with subselects, cause
        //### I can't figure out what to do when the subselect has a join
        //### with an alias also present in the outer select... you don't want
        //### the join to appear in the FROM clause of the subselect cause
        //### then it re-aliases both tables in the scope of the subselect
        //### and the correlation with the outer select is lost
        parentSelect = (SelectImpl) parent;
        if (parentSelect != null) {
            if (parentSelect.subsels == null)
                parentSelect.subsels = new ArrayList(2);
            parentSelect.subsels.add(this);
            if (parentSelect.joinSyntaxType == JoinSyntaxes.SYNTAX_SQL92)
                joinSyntaxType = JoinSyntaxes.SYNTAX_TRADITIONAL;
            else
                joinSyntaxType = parentSelect.joinSyntaxType;
        }
    }

    @Override
    public void setHasSubselect(boolean hasSub) {
        containsSubselect = hasSub;
    }

    @Override
    public boolean getHasSubselect() {
        return containsSubselect;
    }

    public Map getAliases() {
        return aliasMappings;
    }

    public void removeAlias(Object key) {
        aliasMappings.remove(key);
    }

    public Map getTables() {
        return tables;
    }

    public void removeTable(Object key) {
        tables.remove(key);
    }

    @Override
    public Select getFromSelect() {
        return from;
    }

    @Override
    public void setFromSelect(Select sel) {
        from = (SelectImpl) sel;
        if (from != null)
            from.outerSelect = this;
    }

    @Override
    public boolean hasEagerJoin(boolean toMany) {
        if (toMany)
            return (flags & EAGER_TO_MANY) != 0;
        return (flags & EAGER_TO_ONE) != 0;
    }

    @Override
    public boolean hasJoin(boolean toMany) {
        if (toMany)
            return (flags & TO_MANY) != 0;
        return tables != null && tables.size() > 1;
    }

    @Override
    public boolean isSelected(Table table) {
        PathJoins pj = getJoins(null, false);
        if (from != null)
            return from.getTableIndex(table, pj, false) != -1;
        return getTableIndex(table, pj, false) != -1;
    }

    @Override
    public Collection getTableAliases() {
        return (tables == null) ? Collections.emptySet() : tables.values();
    }

    @Override
    public List getSelects() {
        return Collections.unmodifiableList(selects);
    }

    @Override
    public List getSelectAliases() {
        return selects.getAliases(false, outerSelect != null);
    }

    @Override
    public List getIdentifierAliases() {
        return selects.getAliases(true, outerSelect != null);
    }

    @Override
    public SQLBuffer getOrdering() {
        return ordering;
    }

    @Override
    public SQLBuffer getGrouping() {
        return grouping;
    }

    @Override
    public SQLBuffer getWhere() {
        return where;
    }

    @Override
    public SQLBuffer getHaving() {
        return having;
    }

    @Override
    public void addJoinClassConditions() {
        if (selectJoins == null || selectJoins.joins() == null)
            return;

        // join set iterator allows concurrent modification
        Join j;
        for (Iterator itr = selectJoins.joins().iterator(); itr.hasNext();) {
            j = (Join) itr.next();
            if (j.getRelationTarget() != null) {
                j.getRelationTarget().getDiscriminator().addClassConditions
                    (this, j.getSubclasses() == SUBS_JOINABLE,
                    j.getRelationJoins());
                j.setRelation(null, 0, null);
            }
        }
    }

    @Override
    public Joins getJoins() {
        return selectJoins;
    }

    @Override
    public Iterator getJoinIterator() {
        if (selectJoins == null || selectJoins.isEmpty())
            return emptyIterator();
        return selectJoins.joins().joinIterator();
    }

    @Override
    public long getStartIndex() {
        return startIdx;
    }

    @Override
    public long getEndIndex() {
        return endIdx;
    }

    @Override
    public void setRange(long start, long end) {
        startIdx = start;
        endIdx = end;
    }

    @Override
    public String getColumnAlias(Column col) {
        return getColumnAlias(col, (Joins) null);
    }

    @Override
    public String getColumnAlias(Column col, Joins joins) {
        return getColumnAlias(col, getJoins(joins, false));
    }

    /**
     * Return the alias for the given column.
     */
    private String getColumnAlias(Column col, PathJoins pj) {
        return getColumnAlias(col.getIdentifier().getName(), col.getTable(), pj);
    }

    @Override
    public String getColumnAlias(String col, Table table) {
        return getColumnAlias(col, table, (Joins) null);
    }

    @Override
    public String getColumnAlias(String col, Table table, Joins joins) {
        return getColumnAlias(col, table, getJoins(joins, false));
    }

    /**
     * Return the alias for the give column
     */
    @Override
    public String getColumnAlias(Column col, Object path) {
        Table table = col.getTable();
        String tableAlias = null;
        Iterator itr = getJoinIterator();
        while (itr.hasNext()) {
            Join join = (Join) itr.next();
            if (join != null) {
                if (join.getTable1() == table)
                    tableAlias = join.getAlias1();
                else if (join.getTable2() == table)
                    tableAlias = join.getAlias2();
                if (tableAlias != null)
                    return tableAlias + "." +
                            databaseDictionary.toDBName(col.getIdentifier());
            }
        }
        throw new InternalException("Can not resolve alias for field: " +
            path.toString() + " mapped to column: " + col.getIdentifier().getName() +
            " table: "+table.getIdentifier().getName());
    }

    /**
     * Return the alias for the given column.
     */
    private String getColumnAlias(String col, Table table, PathJoins pj) {
        return getTableAlias(table, pj).append(databaseDictionary.toDBName(
            DBIdentifier.newColumn(col))).toString();
    }

    private StringBuilder getTableAlias(Table table, PathJoins pj) {
        StringBuilder buf = new StringBuilder();
        if (from != null) {
            String alias = toAlias(from.getTableIndex(table, pj, true));
            if (databaseDictionary.requiresAliasForSubselect)
                return buf.append(FROM_SELECT_ALIAS).append(".").append(alias).
                    append("_");
            return buf.append(alias).append("_");
        }
        return buf.append(toAlias(getTableIndex(table, pj, true))).append(".");
    }

    @Override
    public boolean isAggregate() {
        return (flags & AGGREGATE) != 0;
    }

    @Override
    public void setAggregate(boolean agg) {
        if (agg)
            flags |= AGGREGATE;
        else
            flags &= ~AGGREGATE;
    }

    @Override
    public boolean isLob() {
        return (flags & LOB) != 0;
    }

    @Override
    public void setLob(boolean lob) {
        if (lob)
            flags |= LOB;
        else
            flags &= ~LOB;
    }

    @Override
    public void clearSelects() {
        selects.clear();
    }

    @Override
    public boolean select(SQLBuffer sql, Object id) {
        return select(sql, id, null);
    }

    @Override
    public boolean select(SQLBuffer sql, Object id, Joins joins) {
        if (!isGrouping())
            return select((Object) sql, id, joins);
        groupBy(sql, joins);
        return false;
    }

    /**
     * Record the select of the given SQL buffer or string.
     */
    private boolean select(Object sql, Object id, Joins joins) {
        getJoins(joins, true);
        boolean contains;
        if (id == null) {
            int idx = selects.indexOfAlias(sql);
            contains = idx != -1;
            if (contains)
                id = selects.get(idx);
            else
                id = nullId();
        } else
            contains = selects.contains(id);

        if (contains)
            return false;
        selects.setAlias(id, sql, false);
        return true;
    }

    /**
     * Returns a unique id for a SQL string whose given id is null.
     */
    private Object nullId() {
        if (nullIds >= NULL_IDS.length)
            return new NullId();
        return NULL_IDS[nullIds++];
    }

    @Override
    public boolean select(String sql, Object id) {
        return select(sql, id, null);
    }

    @Override
    public boolean select(String sql, Object id, Joins joins) {
        if (!isGrouping())
            return select((Object) sql, id, joins);
        groupBy(sql, joins);
        return true;
    }

    @Override
    public void selectPlaceholder(String sql) {
        Object holder = (placeholders >= PLACEHOLDERS.length)
            ? new Placeholder() : PLACEHOLDERS[placeholders++];
        select(sql, holder);
    }

    /**
     * Insert a placeholder at the given index; use a negative index
     * to count from the back of the select list.
     */
    public void insertPlaceholder(String sql, int pos) {
        Object holder = (placeholders >= PLACEHOLDERS.length)
            ? new Placeholder() : PLACEHOLDERS[placeholders++];
        selects.insertAlias(pos, holder, sql);
    }

    /**
     * Clear selected placeholders, and return removed select indexes.
     */
    public void clearPlaceholderSelects() {
        selects.clearPlaceholders();
    }

    @Override
    public boolean select(Column col) {
        return select(col, null);
    }

    @Override
    public boolean select(Column col, Joins joins) {
        if (!isGrouping())
            return select(col, getJoins(joins, true), false);
        groupBy(col, joins);
        return false;
    }

    @Override
    public int select(Column[] cols) {
        return select(cols, null);
    }

    @Override
    public int select(Column[] cols, Joins joins) {
        if (cols == null || cols.length == 0)
            return 0;
        if (isGrouping()) {
            groupBy(cols, joins);
            return 0;
        }
        PathJoins pj = getJoins(joins, true);
        int seld = 0;
        for (int i = 0; i < cols.length; i++)
            if (select(cols[i], pj, false))
                seld |= 2 << i;
        return seld;
    }

    /**
     * Select the given column after making the given joins.
     */
    private boolean select(Column col, PathJoins pj, boolean ident) {
        // we cache on column object if there are no joins so that when
        // looking up columns in the result we don't have to create a string
        // buffer for the table + column alias; if there are joins, then
        // we key on the alias
        String alias = getColumnAlias(col, pj);
        Object id;
        if (pj == null || pj.path() == null)
            id = col;
        else
            id = alias;
        if (selects.contains(id))
            return false;

        if (col.getType() == Types.BLOB || col.getType() == Types.CLOB)
            setLob(true);
        selects.setAlias(id, alias, ident);
        return true;
    }

    @Override
    public void select(ClassMapping mapping, int subclasses,
        JDBCStore store, JDBCFetchConfiguration fetch, int eager) {
        select(mapping, subclasses, store, fetch, eager, null);
    }

    @Override
    public void select(ClassMapping mapping, int subclasses,
        JDBCStore store, JDBCFetchConfiguration fetch, int eager,
        Joins joins) {
        select(this, mapping, subclasses, store, fetch, eager, joins, false);
    }

    /**
     * Select the given mapping.
     */
    void select(Select wrapper, ClassMapping mapping, int subclasses,
        JDBCStore store, JDBCFetchConfiguration fetch, int eager,
        Joins joins, boolean ident) {
        // note that this is one case where we don't want to use the result
        // of getJoins(); just use the given joins, which will either be clean
        // or the result of previous pre-joins. this way we don't push extra
        // stack stuff when no actual new joins have been made, and we don't
        // think the user wants outer joins when actually only the previous
        // joins were outer.  we do invoke getJoins(), though, to add these
        // joins (if any) to our top-level joins; otherwise it'd be possible
        // for the user to immediately do another join and select something,
        // and if we're in outer mode all these joins will get switched to outer
        // joins.  caching them as their original join type prevents that
        getJoins(joins, true);

        PathJoins pj = (PathJoins) joins;
        boolean hasJoins = pj != null && pj.isDirty();
        if (hasJoins) {
            if (preJoins == null)
                preJoins = new Stack();
            preJoins.push(pj);
        }

        // if they are selecting this mapping with outer joins, then all joins
        // from this mapping should also be outer
        boolean wasOuter = (flags & OUTER) != 0;
        if (hasJoins && !wasOuter && pj.isOuter())
            flags |= OUTER;

        // delegate to store manager to select in same order it loads result
        ((JDBCStoreManager) store).select(wrapper, mapping, subclasses, null,
            null, fetch, eager, ident, (flags & OUTER) != 0);

        // reset
        if (hasJoins)
            preJoins.pop();
        if (!wasOuter && (flags & OUTER) != 0)
            flags &= ~OUTER;
    }

    @Override
    public boolean selectIdentifier(Column col) {
        return selectIdentifier(col, null);
    }

    @Override
    public boolean selectIdentifier(Column col, Joins joins) {
        if (!isGrouping())
            return select(col, getJoins(joins, true), true);
        groupBy(col, joins);
        return false;
    }

    @Override
    public int selectIdentifier(Column[] cols) {
        return selectIdentifier(cols, null);
    }

    @Override
    public int selectIdentifier(Column[] cols, Joins joins) {
        if (cols == null || cols.length == 0)
            return 0;
        if (isGrouping()) {
            groupBy(cols, joins);
            return 0;
        }
        PathJoins pj = getJoins(joins, true);
        int seld = 0;
        for (int i = 0; i < cols.length; i++)
            if (select(cols[i], pj, true))
                seld |= 2 << i;
        return seld;
    }

    @Override
    public void selectIdentifier(ClassMapping mapping, int subclasses,
        JDBCStore store, JDBCFetchConfiguration fetch, int eager) {
        selectIdentifier(mapping, subclasses, store, fetch, eager, null);
    }

    @Override
    public void selectIdentifier(ClassMapping mapping, int subclasses,
        JDBCStore store, JDBCFetchConfiguration fetch, int eager,
        Joins joins) {
        select(this, mapping, subclasses, store, fetch, eager, joins, true);
    }

    @Override
    public int selectPrimaryKey(ClassMapping mapping) {
        return selectPrimaryKey(mapping, null);
    }

    @Override
    public int selectPrimaryKey(ClassMapping mapping, Joins joins) {
        return primaryKeyOperation(mapping, true, null, joins, false);
    }

    /**
     * Operate on primary key data. Return a bit mask of selected columns.
     */
    private int primaryKeyOperation(ClassMapping mapping, boolean sel,
        Boolean asc, Joins joins, boolean aliasOrder) {
        if (!sel && asc == null)
            return 0;

        // if this mapping can't select the full pk values, then join to
        // super and recurse
        if (!mapping.isPrimaryKeyObjectId(true))
            return primaryKeyOperationOnSuperclass(mapping, sel, asc, joins,
                aliasOrder);

        Column[] cols = mapping.getPrimaryKeyColumns();
        if (isGrouping()) {
            groupBy(cols, joins);
            return 0;
        }

        PathJoins pj = getJoins(joins, false);
        int seld = 0;
        for (int i = 0; i < cols.length; i++) {
            if (columnOperation(cols[i], sel, asc, pj, aliasOrder))
                seld |= 2 << i;
        }

        PathJoins joinedPj = ensureSuperclassJoinForPrimaryKey(mapping, pj);
        if (joinedPj != null)
            where(joinedPj);

        return seld;
    }

    private int primaryKeyOperationOnSuperclass(ClassMapping mapping,
        boolean sel, Boolean asc, Joins joins, boolean aliasOrder) {
        ClassMapping sup = mapping.getJoinablePCSuperclassMapping();
        if (joins == null)
            joins = newJoins();
        joins = mapping.joinSuperclass(joins, false);
        return primaryKeyOperation(sup, sel, asc, joins, aliasOrder);
    }

    private PathJoins ensureSuperclassJoinForPrimaryKey(ClassMapping mapping,
        PathJoins pj) {
        boolean joined = false;
        ClassMapping sup;

        for (sup = mapping.getJoinablePCSuperclassMapping(); sup != null;
            mapping = sup, sup = mapping.getJoinablePCSuperclassMapping()) {
            if (mapping.getTable() != sup.getTable()) {
                if (getTableIndex(mapping.getTable(), pj, false) == -1
                    && getTableIndex(sup.getTable(), pj, false) != -1) {
                    if (pj == null)
                        pj = (PathJoins) newJoins();
                    pj = (PathJoins) mapping.joinSuperclass(pj, false);
                    joined = true;
                } else {
                    break;
                }
            }
        }
        return joined ? pj : null;
    }

    /**
     * Perform an operation on a column.
     */
    private boolean columnOperation(Column col, boolean sel, Boolean asc,
        PathJoins pj, boolean aliasOrder) {
        String as = null;

        if (asc != null && (aliasOrder || (flags & RECORD_ORDERED) != 0))
            as = prepareColumnOrderingState(col, pj, aliasOrder);

        boolean seld = sel && select(col, pj, false);

        if (asc != null) {
            String alias = (as != null) ? as : getColumnAlias(col, pj);
            appendOrdering(alias, asc);
        }
        return seld;
    }

    private String prepareColumnOrderingState(Column col, PathJoins pj,
        boolean aliasOrder) {
        Object id;
        if (pj == null || pj.path() == null)
            id = col;
        else
            id = getColumnAlias(col, pj);

        recordOrderedColumn(id);

        if (!aliasOrder)
            return null;

        String as = toOrderAlias(orders++);
        selects.setSelectAs(id, as);
        return as;
    }

    private void recordOrderedColumn(Object id) {
        if ((flags & RECORD_ORDERED) == 0)
            return;

        if (ordered == null)
            ordered = new ArrayList(5);

        ordered.add(id);
    }

    /**
     * Append ordering information to our internal buffer.
     */
    private void appendOrdering(Object orderBy, boolean asc) {
        if (ordering == null)
            ordering = new SQLBuffer(databaseDictionary);
        else
            ordering.append(", ");

        if (orderBy instanceof SQLBuffer sqlbuffer)
            ordering.append(sqlbuffer);
        else
            ordering.append((String) orderBy);
        if (asc)
            ordering.append(" ASC");
        else
            ordering.append(" DESC");
    }

    public void appendNullsPrecedence(int nullPrecedence) {
        if (ordering == null)
            return;
        databaseDictionary.appendNullsPrecedence(ordering, nullPrecedence);
    }

    @Override
    public void addSetOperatorSQL(int setOpType, SQLBuffer sql) {
        if (setOperatorBuf == null)
            setOperatorBuf = new SQLBuffer(databaseDictionary);
        String keyword;
        switch (setOpType) {
            case QueryExpressions.SET_OP_UNION:
                keyword = " UNION ";
                break;
            case QueryExpressions.SET_OP_UNION_ALL:
                keyword = " UNION ALL ";
                break;
            case QueryExpressions.SET_OP_INTERSECT:
                keyword = " INTERSECT ";
                break;
            case QueryExpressions.SET_OP_INTERSECT_ALL:
                keyword = " INTERSECT ALL ";
                break;
            case QueryExpressions.SET_OP_EXCEPT:
                keyword = " " + databaseDictionary.exceptFunction + " ";
                break;
            case QueryExpressions.SET_OP_EXCEPT_ALL:
                keyword = " " + databaseDictionary.exceptFunction + " ALL ";
                break;
            default:
                return;
        }
        setOperatorBuf.append(keyword);
        setOperatorBuf.append(sql);
    }

    @Override
    public SQLBuffer getSetOperatorBuffer() {
        return setOperatorBuf;
    }

    @Override
    public int orderByPrimaryKey(ClassMapping mapping, boolean asc,
        boolean sel) {
        return orderByPrimaryKey(mapping, asc, null, sel);
    }

    @Override
    public int orderByPrimaryKey(ClassMapping mapping, boolean asc,
        Joins joins, boolean sel) {
        return orderByPrimaryKey(mapping, asc, joins, sel, false);
    }

    /**
     * Allow unions to set aliases on order columns.
     */
    public int orderByPrimaryKey(ClassMapping mapping, boolean asc,
        Joins joins, boolean sel, boolean aliasOrder) {
        return primaryKeyOperation(mapping, sel,
            (asc) ? Boolean.TRUE : Boolean.FALSE, joins, aliasOrder);
    }

    @Override
    public boolean orderBy(Column col, boolean asc, boolean sel) {
        return orderBy(col, asc, null, sel);
    }

    @Override
    public boolean orderBy(Column col, boolean asc, Joins joins, boolean sel) {
        return orderBy(col, asc, joins, sel, false);
    }

    /**
     * Allow unions to set aliases on order columns.
     */
    boolean orderBy(Column col, boolean asc, Joins joins, boolean sel,
        boolean aliasOrder) {
        return columnOperation(col, sel, (asc) ? Boolean.TRUE : Boolean.FALSE,
            getJoins(joins, true), aliasOrder);
    }

    @Override
    public int orderBy(Column[] cols, boolean asc, boolean sel) {
        return orderBy(cols, asc, null, sel);
    }

    @Override
    public int orderBy(Column[] cols, boolean asc, Joins joins, boolean sel) {
        return orderBy(cols, asc, joins, sel, false);
    }

    /**
     * Allow unions to set aliases on order columns.
     */
    int orderBy(Column[] cols, boolean asc, Joins joins, boolean sel,
        boolean aliasOrder) {
        PathJoins pj = getJoins(joins, true);
        int seld = 0;
        for (int i = 0; i < cols.length; i++)
            if (columnOperation(cols[i], sel,
                (asc) ? Boolean.TRUE : Boolean.FALSE, pj, aliasOrder))
                seld |= 2 << i;
        return seld;
    }

    @Override
    public boolean orderBy(SQLBuffer sql, boolean asc, boolean sel, Value selAs)
    {
        return orderBy(sql, asc, null, sel, selAs);
    }

    @Override
    public boolean orderBy(SQLBuffer sql, boolean asc, Joins joins,
        boolean sel, Value selAs) {
        return orderBy(sql, asc, joins, sel, false, selAs);
    }

    /**
     * Allow unions to set aliases on order columns.
     */
    boolean orderBy(SQLBuffer sql, boolean asc, Joins joins, boolean sel,
        boolean aliasOrder, Value selAs) {
        return orderBy((Object) sql, asc, joins, sel, aliasOrder, selAs);
    }

    /**
     * Order on a SQL buffer or string.
     */
    private boolean orderBy(Object sql, boolean asc, Joins joins, boolean sel,
        boolean aliasOrder, Value selAs) {
        Object order = sql;
        if (aliasOrder) {
            order = toOrderAlias(orders++);
            selects.setSelectAs(sql, (String) order);
        }
        if ((flags & RECORD_ORDERED) != 0) {
            if (ordered == null)
                ordered = new ArrayList(5);
            ordered.add(selAs == null ? sql : selAs);
        }

        getJoins(joins, true);
        appendOrdering(selAs != null ? selAs.getAlias() : order, asc);
        if (sel) {
            int idx = selects.indexOfAlias(sql);
            if (idx == -1) {
                selects.setAlias(nullId(), sql, false);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean orderBy(String sql, boolean asc, boolean sel) {
        return orderBy(sql, asc, null, sel);
    }

    @Override
    public boolean orderBy(String sql, boolean asc, Joins joins, boolean sel) {
        return orderBy(sql, asc, joins, sel, false);
    }

    /**
     * Allow unions to set aliases on order columns.
     */
    boolean orderBy(String sql, boolean asc, Joins joins, boolean sel,
        boolean aliasOrder) {
        return orderBy(sql, asc, joins, sel, aliasOrder, null);
    }

    @Override
    public void clearOrdering() {
        ordering = null;
        orders = 0;
    }

    /**
     * Allow unions to record the select list indexes of items we order by.
     */
    void setRecordOrderedIndexes(boolean orderedIndexesRecording) {
        if (orderedIndexesRecording)
            flags |= RECORD_ORDERED;
        else {
            ordered = null;
            flags &= ~RECORD_ORDERED;
        }
    }

    /**
     * Return the indexes in the select list of all items we're ordering
     * by, or null if none. For use with unions.
     */
    List getOrderedIndexes() {
        if (ordered == null)
            return null;
        List idxs = new ArrayList(ordered.size());
        for (Object o : ordered) {
            idxs.add(selects.indexOf(o));
        }
        return idxs;
    }

    @Override
    public void wherePrimaryKey(Object oid, ClassMapping mapping,
        JDBCStore store) {
        wherePrimaryKey(oid, mapping, null, store);
    }

    /**
     * Add where conditions setting the mapping's primary key to the given
     * oid values. If the given mapping does not use oid values for its
     * primary key, we will recursively join to its superclass until we find
     * an ancestor that does.
     */
    private void wherePrimaryKey(Object oid, ClassMapping mapping, Joins joins,
        JDBCStore store) {
        // if this mapping's identifiers include something other than
        // the pk values, join to super and recurse
        if (!mapping.isPrimaryKeyObjectId(false)) {
            ClassMapping sup = mapping.getJoinablePCSuperclassMapping();
            if (joins == null)
                joins = newJoins();
            joins = mapping.joinSuperclass(joins, false);
            wherePrimaryKey(oid, sup, joins, store);
            return;
        }

        Column[] cols = mapping.getPrimaryKeyColumns();
        where(oid, mapping, cols, cols, null, null, getJoins(joins, true),
            store);
    }

    @Override
    public void whereForeignKey(ForeignKey fk, Object oid,
        ClassMapping mapping, JDBCStore store) {
        whereForeignKey(fk, oid, mapping, null, store);
    }

    /**
     * Add where conditions setting the given foreign key to the given
     * oid values.
     *
     * @see #wherePrimaryKey
     */
    private void whereForeignKey(ForeignKey fk, Object oid,
        ClassMapping mapping, Joins joins, JDBCStore store) {
        // if this mapping's identifiers include something other than
        // the pk values, or if this foreign key doesn't link to only
        // identifiers, join to table and do a getPrimaryKey
        if (!mapping.isPrimaryKeyObjectId(false) || !containsAll
            (mapping.getPrimaryKeyColumns(), fk.getPrimaryKeyColumns())) {
            if (joins == null)
                joins = newJoins();
            // traverse to foreign key target mapping
            while (mapping.getTable() != fk.getPrimaryKeyTable()) {
                if (joins == null)
                    joins = newJoins();
                joins = mapping.joinSuperclass(joins, false);
                mapping = mapping.getJoinablePCSuperclassMapping();
                if (mapping == null)
                    throw new InternalException();
            }
            joins = joins.join(fk, false, false);
            wherePrimaryKey(oid, mapping, joins, store);
            return;
        }

        Column[] fromCols = fk.getColumns();
        Column[] toCols = fk.getPrimaryKeyColumns();
        Column[] constCols = fk.getConstantColumns();
        Object[] consts = fk.getConstants();
        where(oid, mapping, toCols, fromCols, consts, constCols,
            getJoins(joins, true), store);
    }

    /**
     * Internal method to flush the oid values as where conditions to the
     * given columns.
     */
    private void where(Object oid, ClassMapping mapping, Column[] toCols,
        Column[] fromCols, Object[] vals, Column[] constCols, PathJoins pj,
        JDBCStore store) {
        ValueMapping embed = mapping.getEmbeddingMapping();
        if (embed != null) {
            where(oid, embed.getFieldMapping().getDefiningMapping(),
                toCols, fromCols, vals, constCols, pj, store);
            return;
        }

        // only bother to pack pk values into array if app id
        Object[] pks = null;
        boolean relationId = RelationStrategies.isRelationId(fromCols);
        if (!relationId && mapping.getIdentityType() == ClassMetaData.ID_APPLICATION)
            pks = ApplicationIds.toPKValues(oid, mapping);

        SQLBuffer buf = new SQLBuffer(databaseDictionary);
        int count = appendOidColumnConditions(buf, oid, mapping, toCols,
            fromCols, pj, store, pks, relationId);
        appendConstantColumnConditions(buf, vals, constCols, pj, count);

        appendWhere(buf);
    }

    private int appendOidColumnConditions(SQLBuffer buf, Object oid,
        ClassMapping mapping, Column[] toCols, Column[] fromCols, PathJoins pj,
        JDBCStore store, Object[] pks, boolean relationId) {
        int count = 0;
        for (int i = 0; i < toCols.length; i++, count++) {
            Object val = resolveWhereJoinValue(oid, mapping, toCols[i], store,
                pks, relationId);

            if (count > 0)
                buf.append(AND);
            buf.append(getColumnAlias(fromCols[i], pj));
            if (val == null)
                buf.append(" IS ");
            else
                buf.append(" = ");
            buf.appendValue(val, fromCols[i]);
        }
        return count;
    }

    private Object resolveWhereJoinValue(Object oid, ClassMapping mapping,
        Column toCol, JDBCStore store, Object[] pks, boolean relationId) {
        if (pks == null) {
            if (oid == null)
                return null;
            if (relationId)
                return oid;
            return ((Id) oid).getId();
        }

        // must be app identity; use pk index to get correct pk value
        Joinable join = mapping.assertJoinable(toCol);
        Object val = pks[mapping.getField(join.getFieldIndex()).
            getPrimaryKeyIndex()];
        return join.getJoinValue(val, toCol, store);
    }

    private void appendConstantColumnConditions(SQLBuffer buf, Object[] vals,
        Column[] constCols, PathJoins pj, int count) {
        if (constCols == null || constCols.length == 0)
            return;

        for (int i = 0; i < constCols.length; i++, count++) {
            if (count > 0)
                buf.append(AND);
            buf.append(getColumnAlias(constCols[i], pj));

            if (vals[i] == null)
                buf.append(" IS ");
            else
                buf.append(" = ");
            buf.appendValue(vals[i], constCols[i]);
        }
    }

    /**
     * Test to see if the given set of columns contains all the
     * columns in the given potential subset.
     */
    private static boolean containsAll(Column[] set, Column[] sub) {
        if (sub.length > set.length)
            return false;

        // this is obviously n^2, but the number of columns should be in
        // the 1-2 range, so no biggie
        boolean found = true;
        for (int i = 0; i < sub.length && found; i++) {
            found = false;
            for (int j = 0; j < set.length && !found; j++)
                found = sub[i] == set[j];
        }
        return found;
    }

    @Override
    public void where(Joins joins) {
        if (joins != null)
            where((String) null, joins);
    }

    @Override
    public void where(SQLBuffer sql) {
        where(sql, (Joins) null);
    }

    @Override
    public void where(SQLBuffer sql, Joins joins) {
        getJoins(joins, true);
        appendWhere(sql);
    }

    /**
     * Add the given condition to the WHERE clause.
     */
    private void appendWhere(SQLBuffer sql) {
        // no need to use joins...
        if (sql == null || sql.isEmpty())
            return;

        if (where == null)
            where = new SQLBuffer(databaseDictionary);
        else if (!where.isEmpty())
            where.append(AND);
        where.append(sql);
    }

    @Override
    public void where(String sql) {
        where(sql, (Joins) null);
    }

    @Override
    public void where(String sql, Joins joins) {
        getJoins(joins, true);
        appendWhere(sql);
    }

    /**
     * Add the given condition to the WHERE clause.
     */
    private void appendWhere(String sql) {
        // no need to use joins...
        if (StringUtil.isEmpty(sql))
            return;

        if (where == null)
            where = new SQLBuffer(databaseDictionary);
        else if (!where.isEmpty())
            where.append(AND);
        where.append(sql);
    }

    @Override
    public void having(SQLBuffer sql) {
        having(sql, (Joins) null);
    }

    @Override
    public void having(SQLBuffer sql, Joins joins) {
        getJoins(joins, true);
        appendHaving(sql);
    }

    /**
     * Add the given condition to the HAVING clause.
     */
    private void appendHaving(SQLBuffer sql) {
        // no need to use joins...
        if (sql == null || sql.isEmpty())
            return;

        if (having == null)
            having = new SQLBuffer(databaseDictionary);
        else if (!having.isEmpty())
            having.append(AND);
        having.append(sql);
    }

    @Override
    public void having(String sql) {
        having(sql, (Joins) null);
    }

    @Override
    public void having(String sql, Joins joins) {
        getJoins(joins, true);
        appendHaving(sql);
    }

    /**
     * Add the given condition to the HAVING clause.
     */
    private void appendHaving(String sql) {
        // no need to use joins...
        if (StringUtil.isEmpty(sql))
            return;

        if (having == null)
            having = new SQLBuffer(databaseDictionary);
        else if (!having.isEmpty())
            having.append(AND);
        having.append(sql);
    }

    @Override
    public void groupBy(SQLBuffer sql) {
        groupBy(sql, null);
    }

    @Override
    public void groupBy(SQLBuffer sql, Joins joins) {
        getJoins(joins, true);
        groupByAppend(sql.getSQL());
    }

    @Override
    public void groupBy(String sql) {
        groupBy(sql, null);
    }

    @Override
    public void groupBy(String sql, Joins joins) {
        getJoins(joins, true);
        groupByAppend(sql);
    }

    @Override
    public void groupBy(Column col) {
        groupBy(col, null);
    }

    @Override
    public void groupBy(Column col, Joins joins) {
        PathJoins pj = getJoins(joins, true);
        groupByAppend(getColumnAlias(col, pj));
    }

    @Override
    public void groupBy(Column[] cols) {
        groupBy(cols, null);
    }

    @Override
    public void groupBy(Column[] cols, Joins joins) {
        PathJoins pj = getJoins(joins, true);
        for (Column col : cols) {
            groupByAppend(getColumnAlias(col, pj));
        }
    }

    private void groupByAppend(String sql) {
        if (grouped == null || !grouped.contains(sql)) {
            if (grouping == null) {
                grouping = new SQLBuffer(databaseDictionary);
                grouped = new ArrayList();
            } else
                grouping.append(", ");

            grouping.append(sql);
            grouped.add(sql);
        }
    }

    @Override
    public void groupBy(ClassMapping mapping, int subclasses, JDBCStore store,
        JDBCFetchConfiguration fetch) {
        groupBy(mapping, subclasses, store, fetch, null);
    }

    @Override
    public void groupBy(ClassMapping mapping, int subclasses, JDBCStore store,
        JDBCFetchConfiguration fetch, Joins joins) {
        // we implement this by putting ourselves into grouping mode, where
        // all select invocations are re-routed to group-by invocations instead.
        // this allows us to utilize the same select APIs of the store manager
        // and all the mapping strategies, rather than having to create
        // equivalent APIs and duplicate logic for grouping
        boolean wasGrouping = isGrouping();
        flags |= GROUPING;
        try {
            select(mapping, subclasses, store, fetch,
                EagerFetchModes.EAGER_NONE, joins);
        } finally {
            if (!wasGrouping)
                flags &= ~GROUPING;
        }
    }

    /**
     * Whether we're in group mode, where any select is changed to a group-by
     * call.
     */
    private boolean isGrouping() {
        return (flags & GROUPING) != 0;
    }

    /**
     * Return the joins to use for column aliases, etc.
     *
     * @param joins joins given by the user
     * @return the joins to use for aliases, etc
     */
    private PathJoins getJoins(Joins joins, boolean recordJoins) {
        PathJoins pj = (PathJoins) joins;
        boolean pre = (pj == null || !pj.isDirty())
            && preJoins != null && !preJoins.isEmpty();
        if (pre)
            pj = (PathJoins) preJoins.peek();

        if (pj == null || !pj.isDirty())
            pj = selectJoins;
        else if (!pre) {
            if ((flags & OUTER) != 0)
                pj = (PathJoins) outer(pj);
            if (recordJoins && !pj.isEmpty())
                recordSelectedJoins(pj);
        }
        return pj;
    }

    private void recordSelectedJoins(PathJoins pj) {
        if (selectJoins == null)
            selectJoins = new SelectJoins(this);
        if (selectJoins.joins() == null)
            selectJoins.setJoins(new JoinSet(pj.joins()));
        else
            selectJoins.joins().addAll(pj.joins());
    }

    @Override
    public SelectExecutor whereClone(int sels) {
        if (sels < 1)
            sels = 1;

        Select[] clones = null;
        for (int i = 0; i < sels; i++) {
            SelectImpl sel = createWhereCloneSelect();

            if (sels == 1)
                return sel;
            if (clones == null)
                clones = new Select[sels];
            clones[i] = sel;
        }
        return configuration.getSQLFactoryInstance().newUnion(clones);
    }

    private SelectImpl createWhereCloneSelect() {
        SelectImpl sel = (SelectImpl) configuration.getSQLFactoryInstance().newSelect();
        copyWhereCloneState(sel);
        cloneWhereCloneFromSelect(sel);
        cloneWhereCloneSubselects(sel);
        return sel;
    }

    private void copyWhereCloneState(SelectImpl sel) {
        sel.flags = flags;
        sel.flags &= ~AGGREGATE;
        sel.flags &= ~OUTER;
        sel.flags &= ~LRS;
        sel.flags &= ~EAGER_TO_ONE;
        sel.flags &= ~EAGER_TO_MANY;
        sel.flags &= ~FORCE_COUNT;
        sel.joinSyntaxType = joinSyntaxType;
        sel.rootSchemaAlias = rootSchemaAlias;

        if (aliasMappings != null)
            sel.aliasMappings = new HashMap(aliasMappings);
        if (tables != null)
            sel.tables = new TreeMap(tables);
        if (selectJoins != null)
            sel.selectJoins = selectJoins.clone(sel);
        if (where != null)
            sel.where = new SQLBuffer(where);
    }

    private void cloneWhereCloneFromSelect(SelectImpl sel) {
        if (from != null) {
            sel.from = (SelectImpl) from.whereClone(1);
            sel.from.outerSelect = sel;
        }
    }

    private void cloneWhereCloneSubselects(SelectImpl sel) {
        if (subsels == null)
            return;

        sel.subsels = new ArrayList(subsels.size());
        for (int j = 0; j < subsels.size(); j++) {
            SelectImpl sub = subsels.get(j);
            SelectImpl selSub = (SelectImpl) sub.fullClone(1);
            selSub.parentSelect = sel;
            selSub.subPath = sub.subPath;
            sel.subsels.add(selSub);
            if (sel.where != null)
                sel.where.replace(sub, selSub);
        }
    }

    @Override
    public SelectExecutor fullClone(int sels) {
        if (sels < 1)
            sels = 1;

        Select[] clones = null;
        SelectImpl sel;
        for (int i = 0; i < sels; i++) {
            sel = (SelectImpl) whereClone(1);
            sel.flags = flags;
            sel.expectedResultSize = expectedResultSize;
            sel.selects.addAll(selects);
            if (ordering != null)
                sel.ordering = new SQLBuffer(ordering);
            sel.orders = orders;
            if (grouping != null)
                sel.grouping = new SQLBuffer(grouping);
            if (having != null)
                sel.having = new SQLBuffer(having);
            if (from != null) {
                sel.from = (SelectImpl) from.fullClone(1);
                sel.from.outerSelect = sel;
            }

            if (sels == 1)
                return sel;
            if (clones == null)
                clones = new Select[sels];
            clones[i] = sel;
        }
        return configuration.getSQLFactoryInstance().newUnion(clones);
    }

    @Override
    public SelectExecutor eagerClone(FieldMapping key, int eagerType,
        boolean toMany, int sels) {
        if (eagerType == EAGER_OUTER
            && joinSyntaxType == JoinSyntaxes.SYNTAX_TRADITIONAL)
            return null;
        if (eagerKeys != null && eagerKeys.contains(key))
            return null;

        // global set of eager keys
        if (eagerKeys == null)
            eagerKeys = new HashSet();
        eagerKeys.add(key);

        SelectExecutor sel;
        if (eagerType != EAGER_PARALLEL) {
            if (toMany)
                flags |= EAGER_TO_MANY;
            else
                flags |= EAGER_TO_ONE;
            sel = this;
        } else if (sels < 2)
            sel = parallelClone();
        else {
            Select[] clones = new Select[sels];
            for (int i = 0; i < clones.length; i++)
                clones[i] = parallelClone();
            sel = configuration.getSQLFactoryInstance().newUnion(clones);
        }

        if (eagerSelectMap == null)
            eagerSelectMap = new HashMap();
        eagerSelectMap.put(toEagerKey(key, getJoins(null, false)), sel);
        return sel;
    }

    /**
     * Return a clone of this select for use in eager parallel selects.
     */
    private SelectImpl parallelClone() {
        SelectImpl sel = (SelectImpl) whereClone(1);
        sel.flags &= ~NONAUTO_DISTINCT;
        sel.eagerKeys = eagerKeys;
        if (preJoins != null && !preJoins.isEmpty()) {
            sel.preJoins = new Stack();
            sel.preJoins.push(((SelectJoins) preJoins.peek()).
                clone(sel));
        }
        return sel;
    }

    /**
     * Return view of eager selects. May be null.
     */
    public Map getEagerMap() {
        return eagerSelectMap;
    }

    @Override
    public void logEagerRelations() {
        if (eagerKeys != null) {
            configuration.getLog(JDBCConfiguration.LOG_DIAG).trace(
                "Eager relations: "+eagerKeys);
        }
    }

    @Override
    public SelectExecutor getEager(FieldMapping key) {
        if (eagerSelectMap == null || !eagerKeys.contains(key))
            return null;
        return (SelectExecutor) eagerSelectMap.get(toEagerKey(key, getJoins(null,
            false)));
    }

    /**
     * Return the eager key to use for the user-given key.
     */
    private static Object toEagerKey(FieldMapping key, PathJoins pj) {
        if (pj == null || pj.path() == null)
            return key;
        return new Key(pj.path().toString(), key);
    }

    @Override
    public Joins newJoins() {
        if (preJoins != null && !preJoins.isEmpty()) {
            SelectJoins sj = (SelectJoins) preJoins.peek();
            return sj.clone(this);
        }
        // return this for efficiency in case no joins end up being made
        return this;
    }

    @Override
    public Joins newOuterJoins() {
        return ((PathJoins) newJoins()).setOuter(true);
    }

    @Override
    public void append(SQLBuffer buf, Joins joins) {
        if (joins == null || joins.isEmpty())
            return;
        if (joinSyntaxType == JoinSyntaxes.SYNTAX_SQL92)
            return;

        if (!buf.isEmpty())
            buf.append(AND);
        Join join = null;
        for (Iterator itr = ((PathJoins) joins).joins().joinIterator();
            itr.hasNext();) {
            join = (Join) itr.next();
            switch (joinSyntaxType) {
                case JoinSyntaxes.SYNTAX_TRADITIONAL:
                    buf.append(databaseDictionary.toTraditionalJoin(join));
                    break;
                case JoinSyntaxes.SYNTAX_DATABASE:
                    buf.append(databaseDictionary.toNativeJoin(join));
                    break;
                default:
                    throw new InternalException();
            }

            if (itr.hasNext())
                buf.append(AND);
        }
    }

    @Override
    public Joins and(Joins joins1, Joins joins2) {
        return and((PathJoins) joins1, (PathJoins) joins2, true);
    }

    @Override
    public Select getSelect() {
        return null;
    }

    /**
     * Combine the given joins.
     */
    private SelectJoins and(PathJoins j1, PathJoins j2, boolean nullJoins) {
        if (areBothJoinSetsEmptyForAnd(j1, j2))
            return null;

        SelectJoins sj = new SelectJoins(this);
        if (j1 == null || j1.isEmpty())
            mergeSecondJoinSetForAnd(sj, j2, nullJoins);
        else
            mergeFirstJoinSetForAnd(sj, j1, j2, nullJoins);

        // null previous joins; all are combined into this one
        clearCombinedJoinSetsForAnd(j1, j2, nullJoins);

        return sj;
    }

    private boolean areBothJoinSetsEmptyForAnd(PathJoins j1, PathJoins j2) {
        return (j1 == null || j1.isEmpty())
            && (j2 == null || j2.isEmpty());
    }

    private void mergeSecondJoinSetForAnd(SelectJoins sj, PathJoins j2,
        boolean nullJoins) {
        if (j2.getSelect() != this)
            return;

        if (nullJoins)
            sj.setJoins(j2.joins());
        else
            sj.setJoins(new JoinSet(j2.joins()));
    }

    private void mergeFirstJoinSetForAnd(SelectJoins sj, PathJoins j1,
        PathJoins j2, boolean nullJoins) {
        if (j1.getSelect() != this)
            return;

        JoinSet set = nullJoins ? j1.joins() : new JoinSet(j1.joins());

        if (j2 != null && !j2.isEmpty() && j2.getSelect() == this)
            set.addAll(j2.joins());

        sj.setJoins(set);
    }

    private void clearCombinedJoinSetsForAnd(PathJoins j1, PathJoins j2,
        boolean nullJoins) {
        if (nullJoins && j1 != null)
            j1.nullJoins();
        if (nullJoins && j2 != null)
            j2.nullJoins();
    }

    @Override
    public Joins or(Joins joins1, Joins joins2) {
        PathJoins j1 = (PathJoins) joins1;
        PathJoins j2 = (PathJoins) joins2;

        // if no common joins, return null; if one side of the or clause has
        // different joins than the other, then we need to use distinct
        boolean j1Empty = j1 == null || j1.isEmpty();
        boolean j2Empty = j2 == null || j2.isEmpty();
        if (j1Empty || j2Empty) {
            processOrWithEmptySide(j1, j2, j1Empty, j2Empty);
            return null;
        }

        // if all common joins, move all joins to returned instance
        SelectJoins sj = new SelectJoins(this);
        if (j1.joins().equals(j2.joins())) {
            moveEquivalentOrJoins(sj, j1, j2);
        } else {
            splitCommonOrJoins(sj, j1, j2);
        }
        return sj;
    }

    private void processOrWithEmptySide(PathJoins j1, PathJoins j2,
        boolean j1Empty, boolean j2Empty) {
        if (j1Empty && !j2Empty) {
            collectOuterJoins(j2);
            if (!j2.isEmpty())
                flags |= IMPLICIT_DISTINCT;
            return;
        }

        if (!j1Empty) {
            collectOuterJoins(j1);
            if (!j1.isEmpty())
                flags |= IMPLICIT_DISTINCT;
        }
    }

    private void moveEquivalentOrJoins(SelectJoins sj, PathJoins j1,
        PathJoins j2) {
        sj.setJoins(j1.joins());
        j1.nullJoins();
        j2.nullJoins();
    }

    private void splitCommonOrJoins(SelectJoins sj, PathJoins j1,
        PathJoins j2) {
        JoinSet commonJoins = new JoinSet(j1.joins());
        commonJoins.retainAll(j2.joins());
        if (!commonJoins.isEmpty()) {
            // put common joins in returned instance; remove them from
            // each given instance
            sj.setJoins(commonJoins);
            j1.joins().removeAll(commonJoins);
            j2.joins().removeAll(commonJoins);
        }
        collectOuterJoins(j1);
        collectOuterJoins(j2);

        // if one side of the or clause has different joins than the other,
        // then we need to use distinct
        if (!j1.isEmpty() || !j2.isEmpty())
            flags |= IMPLICIT_DISTINCT;
    }

    @Override
    public Joins outer(Joins joins) {
        if (joinSyntaxType == JoinSyntaxes.SYNTAX_TRADITIONAL || joins == null)
            return joins;

        // record that this is an outer join set, even if it's empty
        PathJoins pj = ((PathJoins) joins).setOuter(true);
        if (pj.isEmpty())
            return pj;

        convertEligibleJoinsToOuter(pj);
        return joins;
    }

    private void convertEligibleJoinsToOuter(PathJoins pj) {
        Join join;
        Join rec;
        boolean hasJoins = selectJoins != null && selectJoins.joins() != null;
        for (Iterator itr = pj.joins().iterator(); itr.hasNext();) {
            join = (Join) itr.next();
            if (join.getType() == Join.TYPE_INNER) {
                if (!hasJoins)
                    join.setType(Join.TYPE_OUTER);
                else {
                    rec = selectJoins.joins().getRecordedJoin(join);
                    if (rec == null || rec.getType() == Join.TYPE_OUTER)
                        join.setType(Join.TYPE_OUTER);
                }
            }
        }
    }

    /**
     * Moves the joins from the given instance into our outer joins set.
     */
    private void collectOuterJoins(PathJoins pj) {
        if (joinSyntaxType == JoinSyntaxes.SYNTAX_TRADITIONAL || pj == null
            || pj.isEmpty())
            return;

        if (selectJoins == null)
            selectJoins = new SelectJoins(this);

        boolean add = prepareOuterJoinCollection(pj);

        for (Iterator itr = pj.joins().iterator(); itr.hasNext();) {
            Join join = (Join) itr.next();
            adjustJoinForOuterCollection(join);
            if (add)
                selectJoins.joins().add(join);
        }
        pj.nullJoins();
    }

    private boolean prepareOuterJoinCollection(PathJoins pj) {
        if (selectJoins.joins() != null)
            return true;

        selectJoins.setJoins(pj.joins());
        return false;
    }

    private void adjustJoinForOuterCollection(Join join) {
        if (join.getType() != Join.TYPE_INNER)
            return;

        if (join.getForeignKey() != null
            && !databaseDictionary.canOuterJoin(joinSyntaxType,
                join.getForeignKey())) {
            Log log = configuration.getLog(JDBCConfiguration.LOG_JDBC);
            if (log.isWarnEnabled())
                log.warn(_loc.get("cant-outer-fk",
                    join.getForeignKey()));
            return;
        }

        join.setType(Join.TYPE_OUTER);
    }

    /**
     * Return the alias for the given table under the given joins.
     * NOTE: WE RELY ON THESE INDEXES BEING MONOTONICALLY INCREASING FROM 0
     */
    int getTableIndex(Table table, PathJoins pj, boolean create) {
        // if we have a from select, then there are no table aliases
        if (from != null)
            return -1;

        Integer i = null;
        Object key = table.getFullIdentifier().getName();
        if (pj != null && pj.path() != null)
            key = new Key(pj.getPathStr(), key);

        if (queryContext != null && (parentSelect != null || subsels != null || containsSubselect)) {
            i = findAliasForQuery(table, pj, key, create);
        }

        if (i != null)
            return i;

        // check out existing aliases
        i = findAlias(table, key);

        if (i != null)
            return i;
        if (!create)
            return -1;

        // not found; create alias
        i = aliasSize(false, null);
        recordTableAlias(table, key, i);
        return i;
    }

    private Integer findAliasForQuery(Table table, PathJoins pj, Object key,
        boolean create) {
        Integer i = null;
        SelectImpl sel = this;
        String alias = rootSchemaAlias;
        if (isPathInThisContext(pj) || table.isAssociation())
            alias = null;

        // find the context where this alias is defined
        Context ctx = (alias != null) ?
            queryContext.findContext(alias) : null;
        if (ctx != null)
            sel = (SelectImpl) ctx.getSelect();

        if (!create)
            i = sel.findAlias(table, key);  // find in parent and in myself
        else
            i = sel.getAlias(key); // find in myself
        if (i != null)
            return i;

        if (create) { // create here
            i = sel.createAlias(table, key);
        } else if (ctx != null && ctx != ctx()) { // create in other select
            i = ((SelectImpl)ctx.getSelect()).createAlias(table, key);
        }

        return i;
    }

    private boolean isPathInThisContext(PathJoins pj) {
        // currCtx is set from Action, it is reset to null after the PCPath initialization
        Context currCtx = pj == null ? null : ((PathJoinsImpl)pj).context;

        // lastCtx is set to currCtx after the SelectJoins.join. pj.lastCtx and pj.path string are
        // the last snapshot of pj. They will be used together for later table alias resolution in
        // the getColumnAlias().
        Context lastCtx = pj == null ? null : ((PathJoinsImpl)pj).lastContext;
        Context thisCtx = currCtx == null ? lastCtx : currCtx;
        String corrVar = pj == null ? null : pj.getCorrelatedVariable();

        return (pj != null && pj.path() != null &&
            (corrVar == null || (thisCtx != null && ctx() == thisCtx)));
    }

    private Integer getAlias(Object key) {
        Integer alias = null;
        if (aliasMappings != null)
            alias = (Integer) aliasMappings.get(key);
        return alias;
    }

    private int createAlias(Table table, Object key) {
        Integer i = ctx().nextAlias();
        recordTableAlias(table, key, i);
        return i;
    }

    private Integer findAlias(Table table, Object key) {
        Integer alias = null;
        if (aliasMappings != null) {
            alias = (Integer) aliasMappings.get(key);
            if (alias != null) {
                return alias;
            }
        }
        if (parentSelect != null) {
            alias = parentSelect.findAlias(table, key);
            if (alias != null) {
                return alias;
            }
        }
        return alias;
    }

    /**
     * Record the mapping of the given key to the given alias.
     */
    private void recordTableAlias(Table table, Object key, Integer alias) {
        if (aliasMappings == null)
            aliasMappings = new HashMap();
        aliasMappings.put(key, alias);

        String tableString = databaseDictionary.getFullName(table, false) + " "
            + toAlias(alias);
        if (tables == null)
            tables = new TreeMap();
        tables.put(alias, tableString);
    }


    /**
     * Calculate total number of aliases.
     *
     * From 1.2.x
     */
    private int aliasSize(boolean fromParent, SelectImpl fromSub) {
        int aliases = (fromParent || parentSelect == null) ? 0 : parentSelect.aliasSize(false, this);
        aliases += (aliasMappings == null) ? 0 : aliasMappings.size();
        if (subsels != null) {
            for (SelectImpl sub : subsels) {
                if (sub != fromSub)
                    aliases += sub.aliasSize(true, null);
            }
        }
        return aliases;
    }

    @Override
    public String toString() {
        return toSelect(false, null).getSQL();
    }

    //
    // PathJoins implementation
    //

    @Override
    public boolean isOuter() {
        return false;
    }

    @Override
    public PathJoins setOuter(boolean outer) {
        return new SelectJoins(this).setOuter(true);
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public StringBuilder path() {
        return null;
    }

    @Override
    public String getPathStr() {
        return null;
    }

    @Override
    public JoinSet joins() {
        return null;
    }

    @Override
    public int joinCount() {
        return 0;
    }

    @Override
    public void nullJoins() {
        // This SelectImpl instance has no join state to clear.
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Joins crossJoin(Table localTable, Table foreignTable) {
        return new SelectJoins(this).crossJoin(localTable, foreignTable);
    }

    @Override
    public Joins join(ForeignKey fk, boolean inverse, boolean toMany) {
        return new SelectJoins(this).join(fk, inverse, toMany);
    }

    @Override
    public Joins outerJoin(ForeignKey fk, boolean inverse, boolean toMany) {
        return new SelectJoins(this).outerJoin(fk, inverse, toMany);
    }

    @Override
    public Joins joinRelation(String name, ForeignKey fk, ClassMapping target,
        int subs, boolean inverse, boolean toMany) {
        return new SelectJoins(this).joinRelation(name, fk, target, subs,
            inverse, toMany);
    }

    @Override
    public Joins outerJoinRelation(String name, ForeignKey fk,
        ClassMapping target, int subs, boolean inverse, boolean toMany) {
        return new SelectJoins(this).outerJoinRelation(name, fk, target, subs,
            inverse, toMany);
    }

    @Override
    public Joins setVariable(String variable) {
        if (variable == null)
            return this;
        return new SelectJoins(this).setVariable(variable);
    }

    @Override
    public Joins setSubselect(String alias) {
        if (alias == null)
            return this;
        return new SelectJoins(this).setSubselect(alias);
    }

    /**
     * Represents a SQL string selected with null id.
     */
    private static class NullId {
    }

    /**
     * Represents a placeholder SQL string.
     */
    private static class Placeholder {
    }

    public SelectImpl clone(Context ctx) {
        SelectImpl sel = (SelectImpl) configuration.getSQLFactoryInstance().newSelect();
        sel.queryContext = ctx;
        if (parentSelect != null && parentSelect.ctx() != null)
            sel.parentSelect = (SelectImpl)parentSelect.ctx().getSelect();
        sel.rootSchemaAlias = rootSchemaAlias;
        sel.flags = flags;
        return sel;
    }

    /**
         * Key type used for aliases.
         */
        private record Key(String _path, Object _key) {

        @Override
            public int hashCode() {
                return ((_path == null) ? 0 : _path.hashCode()) ^ ((_key == null) ? 0 : _key.hashCode());
            }

            @Override
            public boolean equals(Object other) {
                if (other == null)
                    return false;
                if (other == this)
                    return true;
                if (other.getClass() != getClass())
                    return false;
                Key k = (Key) other;
                if (k._key == null || k._path == null || _key == null || _path == null)
                    return false;
                return k._path.equals(_path) && k._key.equals(_key);
            }

            @Override
            public String toString() {
                return _path + "|" + _key;
            }

            Object getKey() {
                return _key;
            }
        }

    /**
     * A {@link Result} implementation wrapped around this select.
     */
    public static class SelectResult
        extends ResultSetResult
        implements PathJoins {

        private SelectImpl owningSelect = null;
        private Map<CachedColumnAliasKey, Object> cachedColumnAlias = null;

        // position in selected columns list where we expect the next load
        private int columnPosition = 0;
        private Stack preJoins = null;

        /**
         * Constructor.
         */
        public SelectResult(Connection conn, Statement stmnt, ResultSet rs,
            DBDictionary dict) {
            super(conn, stmnt, rs, dict);
        }

        /**
         * Select for this result.
         */
        @Override
        public SelectImpl getSelect() {
            return owningSelect;
        }

        /**
         * Select for this result.
         */
        public void setSelect(SelectImpl sel) {
            owningSelect = sel;
        }

        @Override
        public Object getEager(FieldMapping key) {
            // don't bother creating key if we know we don't have any
            // eager results
            if (owningSelect.eagerSelectMap == null || !owningSelect.eagerKeys.contains(key))
                return null;
            Map map = SelectResult.this.getEagerMap(true);
            if (map == null)
                return null;
            return map.get(SelectImpl.toEagerKey(key, getJoins(null)));
        }

        @Override
        public void putEager(FieldMapping key, Object res) {
            Map map = SelectResult.this.getEagerMap(true);
            if (map == null) {
                map = new HashMap();
                setEagerMap(map);
            }
            map.put(SelectImpl.toEagerKey(key, getJoins(null)), res);
        }

        @Override
        public Object load(ClassMapping mapping, JDBCStore store,
            JDBCFetchConfiguration fetch, Joins joins)
            throws SQLException {
            boolean hasJoins = joins != null
                && ((PathJoins) joins).path() != null;
            if (hasJoins) {
                if (preJoins == null)
                    preJoins = new Stack();
                preJoins.push(joins);
            }

            Object obj = super.load(mapping, store, fetch, joins);

            // reset
            if (hasJoins)
                preJoins.pop();
            return obj;
        }

        @Override
        public Joins newJoins() {
            PathJoins pre = getPreJoins();
            if (pre == null || pre.path() == null)
                return this;

            PathJoinsImpl pj = new PathJoinsImpl();
            pj.path = new StringBuilder(pre.path().toString());
            return pj;
        }

        @Override
        protected boolean containsInternal(Object obj, Joins joins) {
            // we key directly on objs and join-less cols, or on the alias
            // for cols with joins
            PathJoins pj = getJoins(joins);
            if (pj != null && pj.path() != null) {
                Object columnAlias = getColumnAlias((Column) obj, pj);
                if (joins == null) {
                    if (cachedColumnAlias == null) {
                        cachedColumnAlias = new HashMap<>();
                    }
                    cachedColumnAlias.put(new CachedColumnAliasKey((Column) obj, pj), columnAlias);
                }
                return columnAlias != null && owningSelect.selects.contains(columnAlias);
            }
            return obj != null && owningSelect.selects.contains(obj);
        }

        @Override
        protected boolean containsAllInternal(Object[] objs, Joins joins)
            throws SQLException {
            PathJoins pj = getJoins(joins);
            Object obj;
            for (Object o : objs) {
                if (pj != null && pj.path() != null)
                    obj = getColumnAlias((Column) o, pj);
                else
                    obj = o;
                if (obj == null || !owningSelect.selects.contains(obj))
                    return false;
            }
            return true;
        }

        @Override
        public void pushBack()
            throws SQLException {
            columnPosition = 0;
            super.pushBack();
        }

        @Override
        protected boolean absoluteInternal(int row)
            throws SQLException {
            columnPosition = 0;
            return super.absoluteInternal(row);
        }

        @Override
        protected boolean nextInternal()
            throws SQLException {
            columnPosition = 0;
            return super.nextInternal();
        }

        @Override
        protected int findObject(Object obj, Joins joins)
            throws SQLException {
            if (columnPosition == owningSelect.selects.size())
                columnPosition = 0;

            // we key directly on objs and join-less cols, or on the alias
            // for cols with joins
            PathJoins pj = getJoins(joins);
            Boolean pk = null;
            if (pj != null && pj.path() != null) {
                Column col = (Column) obj;
                pk = (col.isPrimaryKey()) ? Boolean.TRUE : Boolean.FALSE;
                obj = resolveFindObjectAlias(col, pj, joins);
                if (obj == null)
                    throw new SQLException(col.getTable() + ": "
                        + pj.path() + " (" + owningSelect.aliasMappings + ")");
            }

            // we load in the same order we select, more or less...
            if (owningSelect.selects.get(columnPosition).equals(obj))
                return ++columnPosition;

            // if we're looking for a primary key, try back a couple places,
            // since pks might be selected in a slightly different order than
            // they are loaded back; don't change the marker position
            if (pk == null)
                pk = identifyUnjoinedPrimaryKey(obj);

            return locateFindObjectPosition(obj, pk);
        }

        private Object resolveFindObjectAlias(Column col, PathJoins pj, Joins joins) {
            if (joins == null && cachedColumnAlias != null) {
                Object alias = cachedColumnAlias.get(new CachedColumnAliasKey(col, pj));
                if (alias == null)
                    alias = getColumnAlias(col, pj);
                return alias;
            }
            return getColumnAlias(col, pj);
        }

        private Boolean identifyUnjoinedPrimaryKey(Object obj) {
            return (obj instanceof Column column && column.isPrimaryKey())
                ? Boolean.TRUE : Boolean.FALSE;
        }

        private int locateFindObjectPosition(Object obj, Boolean pk)
            throws SQLException {
            if (Boolean.TRUE.equals(pk)) {
                for (int i = columnPosition - 1;
                    i >= 0 && i >= columnPosition - 3; i--) {
                    if (owningSelect.selects.get(i).equals(obj))
                        return i + 1;
                }
            }

            // search forward on the assumption that we might be skipping
            // selects for sibling classes; advance the position if we find
            // something forward
            for (int i = columnPosition + 1; i < owningSelect.selects.size(); i++) {
                if (owningSelect.selects.get(i).equals(obj)) {
                    columnPosition = i;
                    return ++columnPosition;
                }
            }

            // maybe the column was selected by 2 different mappings, so it's
            // somewhere prior to the current position; in this case leave the
            // position marker at its current place cause subsequent loads will
            // still probably start from there
            for (int i = 0; i < columnPosition; i++) {
                if (owningSelect.selects.get(i).equals(obj))
                    return i + 1;
            }

            // somethings's wrong...
            throw new SQLException(obj.toString());
        }

        /**
         * Return the joins to use to find column data.
         */
        private PathJoins getJoins(Joins joins) {
            PathJoins pj = (PathJoins) joins;
            if (pj != null && pj.path() != null)
                return pj;
            return getPreJoins();
        }

        /**
         * Return the pre joins for the result, or null if none. Note that
         * we have to take the Select's pre joins into account too, since
         * batched selects can have additional pre joins on the stack even
         * on execution.
         */
        private PathJoins getPreJoins() {
            if (preJoins != null && !preJoins.isEmpty())
                return (PathJoins) preJoins.peek();
            if (owningSelect.preJoins != null && !owningSelect.preJoins.isEmpty())
                return (PathJoins) owningSelect.preJoins.peek();
            return null;
        }

        /**
         * Return the alias used to key on the column data, considering the
         * given joins.
         */
        String getColumnAlias(Column col, PathJoins pj) {
            String alias;
            if (owningSelect.from != null) {
                alias = SelectImpl.toAlias(owningSelect.from.getTableIndex
                    (col.getTable(), pj, false));
                if (alias == null)
                    return null;
                if (owningSelect.databaseDictionary.requiresAliasForSubselect)
                    return FROM_SELECT_ALIAS + "." + alias + "_" + col;
                return alias + "_" + col;
            }
            alias = SelectImpl.toAlias(owningSelect.getTableIndex(col.getTable(), pj, false));
            return (alias == null) ? null : alias + "." + owningSelect.databaseDictionary.toDBName(DBIdentifier.newColumn(col.toString()));
        }

        //
        // PathJoins implementation
        //

        @Override
        public boolean isOuter() {
            return false;
        }

        @Override
        public PathJoins setOuter(boolean outer) {
            return this;
        }

        @Override
        public boolean isDirty() {
            return false;
        }

        @Override
        public StringBuilder path() {
            return null;
        }

        @Override
        public String getPathStr() {
            return null;
        }

        @Override
        public JoinSet joins() {
            return null;
        }

        @Override
        public int joinCount() {
            return 0;
        }

        @Override
        public void nullJoins() {
            // This result-level PathJoins implementation has no join state to clear.
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public Joins crossJoin(Table localTable, Table foreignTable) {
            return this;
        }

        @Override
        public Joins join(ForeignKey fk, boolean inverse, boolean toMany) {
            return this;
        }

        @Override
        public Joins outerJoin(ForeignKey fk, boolean inverse, boolean toMany) {
            return this;
        }

        @Override
        public Joins joinRelation(String name, ForeignKey fk,
            ClassMapping target, int subs, boolean inverse, boolean toMany) {
            return new PathJoinsImpl().joinRelation(name, fk, target, subs,
                inverse, toMany);
        }

        @Override
        public Joins outerJoinRelation(String name, ForeignKey fk,
            ClassMapping target, int subs, boolean inverse, boolean toMany) {
            return new PathJoinsImpl().outerJoinRelation(name, fk, target, subs,
                inverse, toMany);
        }

        @Override
        public Joins setVariable(String variable) {
            if (variable == null)
                return this;
            return new PathJoinsImpl().setVariable(variable);
        }

        @Override
        public Joins setSubselect(String alias) {
            if (alias == null)
                return this;
            return new PathJoinsImpl().setSubselect(alias);
        }

        @Override
        public Joins setCorrelatedVariable(String variable) {
            return this;
        }

        @Override
        public Joins setJoinContext(Context ctx) {
            return this;
        }

        @Override
        public String getCorrelatedVariable() {
            return null;
        }

        @Override
        public void moveJoinsToParent() {
            // This result-level PathJoins implementation has no joins to move.
        }

        private record CachedColumnAliasKey(Column col, PathJoins pjs) {

            @Override
                    public boolean equals(Object obj) {
                        if (this == obj)
                            return true;
                        if (obj == null)
                            return false;
                        if (getClass() != obj.getClass())
                            return false;
                        CachedColumnAliasKey other = (CachedColumnAliasKey) obj;
                        if (col == null) {
                            if (other.col != null)
                                return false;
                        } else if (!col.equals(other.col))
                            return false;
                        if (pjs == null) {
                            return other.pjs == null;
                        } else return pjs.equals(other.pjs);
                    }

                }
    }

    /**
     * Base joins implementation.
     */
    private static class PathJoinsImpl
        implements PathJoins {

        protected StringBuilder path = null;
        protected String variable = null;
        protected String correlatedVar = null;
        protected Context context = null;
        protected Context lastContext = null;
        protected String pathStr = null;

        @Override
        public Select getSelect() {
            return null;
        }

        @Override
        public boolean isOuter() {
            return false;
        }

        @Override
        public PathJoins setOuter(boolean outer) {
            return this;
        }

        @Override
        public boolean isDirty() {
            return variable != null || path != null;
        }

        @Override
        public StringBuilder path() {
            return path;
        }

        @Override
        public JoinSet joins() {
            return null;
        }

        @Override
        public int joinCount() {
            return 0;
        }

        @Override
        public void nullJoins() {
            // Path-only joins do not maintain a JoinSet to clear.
        }

        @Override
        public Joins setVariable(String variable) {
            this.variable = variable;
            return this;
        }

        public String getVariable() {
            return variable;
        }

        @Override
        public Joins setCorrelatedVariable(String variable) {
            this.correlatedVar = variable;
            return this;
        }

        @Override
        public String getCorrelatedVariable() {
            return correlatedVar;
        }

        @Override
        public Joins setJoinContext(Context context) {
            this.context = context;
            return this;
         }

        @Override
        public Joins setSubselect(String alias) {
            append(alias);
            return this;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public Joins crossJoin(Table localTable, Table foreignTable) {
            append(variable);
            variable = null;
            return this;
        }

        @Override
        public Joins join(ForeignKey fk, boolean inverse, boolean toMany) {
            return join();
        }

        @Override
        public Joins outerJoin(ForeignKey fk, boolean inverse, boolean toMany) {
            return join();
        }

        private Joins join() {
            append(variable);
            variable = null;
            return this;
        }

        @Override
        public Joins joinRelation(String name, ForeignKey fk,
            ClassMapping target, int subs, boolean inverse, boolean toMany) {
            return joinRelation(name);
        }

        @Override
        public Joins outerJoinRelation(String name, ForeignKey fk,
            ClassMapping target, int subs, boolean inverse, boolean toMany) {
            return joinRelation(name);
        }

        private Joins joinRelation(String name) {
            append(name);
            append(variable);
            variable = null;
            return this;
        }

        protected void append(String str) {
            if (str != null) {
                if (path == null)
                    path = new StringBuilder(str);
                else
                    path.append('.').append(str);
                pathStr = null;
            }
        }

        @Override
        public String getPathStr() {
            if (pathStr == null) {
                pathStr = path.toString();
            }
            return pathStr;
        }

        @Override
        public String toString() {
            return "PathJoinsImpl<" + hashCode() + ">: "
                + path;
        }

    @Override
    public void moveJoinsToParent() {
        // The base path-only implementation has no joins to move.
    }
}

/**
     * Joins implementation.
     */
    private static class SelectJoins
        extends PathJoinsImpl {

        private final SelectImpl owningSelect;
        private JoinSet joinSet = null;
        private boolean outerJoins = false;
        private int joinCountValue = 0;

        public SelectJoins(SelectImpl sel) {
            owningSelect = sel;
        }

        @Override
        public Select getSelect() {
            return owningSelect;
        }

        @Override
        public boolean isOuter() {
            return outerJoins;
        }

        @Override
        public PathJoins setOuter(boolean outer) {
            outerJoins = outer;
            return this;
        }

        @Override
        public boolean isDirty() {
            return super.isDirty() || !isEmpty();
        }

        @Override
        public JoinSet joins() {
            return joinSet;
        }

        public void setJoins(JoinSet joins) {
            joinSet = joins;
            outerJoins = joins != null && joins.last() != null
                && joins.last().getType() == Join.TYPE_OUTER;
        }

        @Override
        public int joinCount() {
            if (joinSet == null)
                return joinCountValue;
            return Math.max(joinCountValue, joinSet.size());
        }

        @Override
        public void nullJoins() {
            if (joinSet != null)
                joinCountValue = Math.max(joinCountValue, joinSet.size());
            joinSet = null;
        }

        @Override
        public boolean isEmpty() {
            return joinSet == null || joinSet.isEmpty();
        }

        @Override
        public Joins crossJoin(Table localTable, Table foreignTable) {
            // cross joins are for unbound variables; unfortunately we have
            // to always go DISTINCT for unbound vars because there are certain
            // cases that require it, and we can't differentiate them from the
            // cases that don't
            owningSelect.flags |= IMPLICIT_DISTINCT;

            if (owningSelect.getJoinSyntax() != JoinSyntaxes.SYNTAX_SQL92
                || owningSelect.from != null) {
                return finishCrossJoinWithoutJoin();
            }

            // don't let the get alias methods see that a variable has been set
            // until we get past the local table
            String variable = this.variable;
            this.variable = null;
            Context ctx = context;
            context = null;

            int alias1 = owningSelect.getTableIndex(localTable, this, true);
            this.append(variable);
            this.append(correlatedVar);
            context = ctx;

            int alias2 = owningSelect.getTableIndex(foreignTable, this, true);
            Join j = new Join(localTable, alias1, foreignTable, alias2,
                null, false);
            j.setType(Join.TYPE_CROSS);

            if (joinSet == null)
                joinSet = new JoinSet();
            joinSet.add(j);
            setCorrelated(j);
            outerJoins = false;
            lastContext = context;
            context = null;
            return this;
        }

        private Joins finishCrossJoinWithoutJoin() {
            // don't make any joins, but update the path if a variable
            // has been set
            if (this.variable != null) {
                this.append(this.variable);
            } else if (this.path == null
                && this.correlatedVar != null
                && owningSelect.databaseDictionary.isImplicitJoin()) {
                this.append(resolveCorrelatedCrossJoinPath());
            }
            this.variable = null;
            outerJoins = false;
            return this;
        }

        private String resolveCorrelatedCrossJoinPath() {
            String str = this.variable;
            boolean resolved = false;
            for (Object o : owningSelect.parentSelect.aliasMappings.keySet()) {
                if (!resolved && o instanceof Key k) {
                    if (this.correlatedVar.equals(k._path)) {
                        str = this.correlatedVar;
                        resolved = true;
                    }
                } else if (!resolved && o.equals(this.correlatedVar)) {
                    str = this.correlatedVar;
                    resolved = true;
                }
            }
            return str;
        }

        @Override
        public Joins join(ForeignKey fk, boolean inverse, boolean toMany) {
            return join(null, fk, null, -1, inverse, toMany, false);
        }

        @Override
        public Joins outerJoin(ForeignKey fk, boolean inverse, boolean toMany) {
            return join(null, fk, null, -1, inverse, toMany, true);
        }

        @Override
        public Joins joinRelation(String name, ForeignKey fk,
            ClassMapping target, int subs, boolean inverse, boolean toMany) {
            return join(name, fk, target, subs, inverse, toMany, false);
        }

        @Override
        public Joins outerJoinRelation(String name, ForeignKey fk,
            ClassMapping target, int subs, boolean inverse, boolean toMany) {
            return join(name, fk, target, subs, inverse, toMany, true);
        }

        private Joins join(String name, ForeignKey fk, ClassMapping target,
            int subs, boolean inverse, boolean toMany, boolean outer) {
            // don't let the get alias methods see that a variable has been set
            // until we get past the local table
            String variable = this.variable;
            this.variable = null;
            Context ctx = context;
            context = null;

            // get first table alias before updating path; if there is a from
            // select then we shouldn't actually create a join object, since
            // the joins will all be done in the from select
            boolean createJoin = owningSelect.from == null;
            Table table1 = null;
            int alias1 = -1;
            if (createJoin) {
                boolean createIndex = true;
                table1 = inverse ? fk.getPrimaryKeyTable() : fk.getTable();
                if (correlatedVar != null)
                    createIndex = false;  // not to create here
                alias1 = owningSelect.getTableIndex(table1, this, createIndex);
            }

            appendJoinPath(name, variable, ctx);
            recordToManyJoin(toMany);
            outerJoins = outer;

            if (createJoin)
                addResolvedJoin(fk, target, subs, inverse, outer, table1, alias1);

            lastContext = context;
            context = null;
            return this;
        }

        private void appendJoinPath(String name, String variable, Context ctx) {
            // update the path with the relation name before getting pk alias
            this.append(name);
            this.append(variable);
            if (variable == null)
                this.append(correlatedVar);
            context = ctx;
        }

        private void recordToManyJoin(boolean toMany) {
            if (toMany) {
                owningSelect.flags |= IMPLICIT_DISTINCT;
                owningSelect.flags |= TO_MANY;
            }
        }

        private void addResolvedJoin(ForeignKey fk, ClassMapping target,
            int subs, boolean inverse, boolean outer, Table table1, int alias1) {
            Table table2 = inverse ? fk.getTable() : fk.getPrimaryKeyTable();
            int alias2 = resolveForeignAlias(table2);
            Join j = new Join(table1, alias1, table2, alias2, fk, inverse);
            j.setType(outer ? Join.TYPE_OUTER : Join.TYPE_INNER);

            if (joinSet == null)
                joinSet = new JoinSet();
            if (joinSet.add(j) && (subs == Select.SUBS_JOINABLE
                || subs == Select.SUBS_NONE))
                j.setRelation(target, subs, clone(owningSelect));

            setCorrelated(j);
        }

        private int resolveForeignAlias(Table table) {
            if (table.isAssociation()) {
                int alias = owningSelect.getTableIndex(table, this, false);
                if (alias != -1)
                    return alias;
                return owningSelect.getTableIndex(table, this, true);
            }

            boolean createIndex = true;
            if (context == owningSelect.ctx())
                createIndex = true;
            else if (correlatedVar != null)
                createIndex = false;
            return owningSelect.getTableIndex(table, this, createIndex);
        }

        private void setCorrelated(Join j) {
            if (owningSelect.parentSelect == null)
                return;

            if (owningSelect.aliasMappings == null) {
                j.setIsNotMyJoin();
               return;
            }

            Object[] aliases = owningSelect.aliasMappings.values().toArray();
            boolean found1 = false;
            boolean found2 = false;

            for (Object o : aliases) {
                int alias = (Integer) o;
                if (alias == j.getIndex1())
                    found1 = true;
                if (alias == j.getIndex2())
                    found2 = true;
            }

            if (found1 && found2) {
                // Both aliases belong to this select, so the join remains unchanged.
            }
            else if (!found1 && !found2) {
                j.setIsNotMyJoin();
            }
            else {
                j.setCorrelated();
            }
        }

        @Override
        public void moveJoinsToParent() {
            if (joinSet == null)
                return;
           Join j = null;
           List<Join> removed = new ArrayList<>(5);
           for (Iterator itr = joinSet.iterator(); itr.hasNext();) {
               j = (Join) itr.next();
               if (j.isNotMyJoin()) {
                   addJoinsToParent(owningSelect.parentSelect, j);
                   removed.add(j);
               }
           }
           for (Join join : removed) {
               joinSet.remove(join);
           }
        }

        private void addJoinsToParent(SelectImpl parent, Join join) {
            if (parent.aliasMappings == null)
                return;
            Object[] aliases = parent.aliasMappings.values().toArray();
            boolean found1 = false;
            boolean found2 = false;

            for (Object o : aliases) {
                int alias = (Integer) o;
                if (alias == join.getIndex1())
                    found1 = true;
                if (alias == join.getIndex2())
                    found2 = true;
            }

            if (found1 && found2) {
                // this is my join, add join
                if (parent.selectJoins == null)
                    parent.selectJoins = new SelectJoins(parent);
                SelectJoins p = parent.selectJoins;
                if (p.joins() == null)
                    p.setJoins(new JoinSet());
                p.joins().add(join);
            }
            else if (parent.parentSelect != null)
                addJoinsToParent(parent.parentSelect, join);
        }

        public SelectJoins clone(SelectImpl sel) {
            SelectJoins sj = new SelectJoins(sel);
            sj.variable = variable;
            if (path != null)
                sj.path = new StringBuilder(path.toString());
            if (joinSet != null && !joinSet.isEmpty())
                sj.joinSet = new JoinSet(joinSet);
            sj.outerJoins = outerJoins;
            return sj;
        }

        @Override
        public String toString() {
            return super.toString() + " (" + outerJoins + "): " + joinSet;
        }
    }

    protected Selects newSelects() {
        return new Selects();
    }

    @Override
    public DBDictionary getDictionary() {
        return databaseDictionary;
    }

    /**
     * Helper class to track selected columns, with fast contains method.
     * Acts as a list of select ids, with additional methods to manipulate
     * the alias of each selected id.
     */
    protected static class Selects
        extends AbstractList {

        protected List ids = null;
        protected List idents = null;
        protected Map aliasMappings = null;
        protected Map selectAs = null;
        protected DBDictionary databaseDictionary = null;

        /**
         * Add all aliases from another instance.
         */
        public void addAll(Selects sels) {
            if (ids == null && sels.ids != null)
                ids = new ArrayList(sels.ids);
            else if (sels.ids != null)
                ids.addAll(sels.ids);

            if (idents == null && sels.idents != null)
                idents = new ArrayList(sels.idents);
            else if (sels.idents != null)
                idents.addAll(sels.idents);

            if (aliasMappings == null && sels.aliasMappings != null)
                aliasMappings = new HashMap(sels.aliasMappings);
            else if (sels.aliasMappings != null)
                aliasMappings.putAll(sels.aliasMappings);

            if (selectAs == null && sels.selectAs != null)
                selectAs = new HashMap(sels.selectAs);
            else if (sels.selectAs != null)
                selectAs.putAll(sels.selectAs);
        }

        /**
         * Returns the alias of a given id.
         */
        public Object getAlias(Object id) {
            return (aliasMappings == null) ? null : aliasMappings.get(id);
        }

        /**
         * Set an alias for a given id.
         */
        public int setAlias(Object id, Object alias, boolean ident) {
            if (ids == null) {
                ids = new ArrayList();
                aliasMappings = new HashMap();
            }

            int idx;
            if (aliasMappings.put(id, alias) != null)
                idx = ids.indexOf(id);
            else {
                ids.add(id);
                idx = ids.size() - 1;

                if (ident) {
                    if (idents == null)
                        idents = new ArrayList(3);
                    idents.add(id);
                }
            }
            return idx;
        }

        /**
         * Set an alias for a given index.
         */
        public void setAlias(int idx, Object alias) {
            Object id = ids.get(idx);
            aliasMappings.put(id, alias);
        }

        /**
         * Insert an alias before the given index, using negative indexes
         * to count backwards.
         */
        public void insertAlias(int idx, Object id, Object alias) {
            aliasMappings.put(id, alias);
            if (idx >= 0)
                ids.add(idx, id);
            else
                ids.add(ids.size() + idx, id);
        }

        /**
         * Return the index of the given alias.
         */
        public int indexOfAlias(Object alias) {
            if (aliasMappings == null)
                return -1;
            for (int i = 0; i < ids.size(); i++)
                if (alias.equals(aliasMappings.get(ids.get(i))))
                    return i;
            return -1;
        }

        /**
         * A list representation of the aliases, in select order, with
         * AS aliases present.
         */
        public List getAliases(final boolean ident, final boolean inner) {
            if (ids == null)
                return Collections.emptyList();

            return new AbstractList() {
                @Override
                public int size() {
                    return (ident && idents != null) ? idents.size()
                        : ids.size();
                }

                @Override
                public Object get(int i) {
                    Object id = getAliasLookupId(i, ident);
                    Object alias = getAliasEntryWithXmlSuffix(id);
                    String as = resolveAliasDisplayName(id, alias, inner);

                    if (as != null)
                        return applyAliasDisplay(alias, as, ident);

                    return alias;
                }
            };
        }

        private Object getAliasLookupId(int index, boolean ident) {
            return (ident && idents != null) ? idents.get(index)
                : ids.get(index);
        }

        private Object getAliasEntryWithXmlSuffix(Object id) {
            Object alias = aliasMappings.get(id);
            if (id instanceof Column column && column.isXML())
                alias = alias + databaseDictionary.getStringVal;
            return alias;
        }

        private String resolveAliasDisplayName(Object id, Object alias,
            boolean inner) {
            if (inner) {
                if (alias instanceof String string)
                    return string.replace('.', '_');
                return null;
            }

            if (selectAs != null)
                return (String) selectAs.get(id);

            if (id instanceof Value value)
                return value.getAlias();

            return null;
        }

        private Object applyAliasDisplay(Object alias, String as,
            boolean ident) {
            if (ident && idents != null)
                return as;

            if (alias instanceof SQLBuffer sqlbuffer)
                return new SQLBuffer(sqlbuffer).append(" AS ").append(as);

            return alias + " AS " + as;
        }

        /**
         * Set that a given id's alias has an AS value.
         */
        public void setSelectAs(Object id, String as) {
            if (selectAs == null)
                selectAs = new HashMap((int) (5 * 1.33 + 1));
            selectAs.put(id, as);
        }

        /**
         * Clear all placeholders and select AS clauses.
         */
        public void clearPlaceholders() {
            if (ids == null)
                return;

            Object id;
            for (Iterator itr = ids.iterator(); itr.hasNext();) {
                id = itr.next();
                if (id instanceof Placeholder) {
                    itr.remove();
                    aliasMappings.remove(id);
                }
            }
        }

        @Override
        public boolean contains(Object id) {
            return aliasMappings != null && aliasMappings.containsKey(id);
        }

        @Override
        public Object get(int i) {
            if (ids == null)
                throw new ArrayIndexOutOfBoundsException();
            return ids.get(i);
        }

        @Override
        public int size() {
            return (ids == null) ? 0 : ids.size();
        }

        @Override
        public void clear() {
            ids = null;
            aliasMappings = null;
            selectAs = null;
            idents = null;
        }
    }

    @Override
    public Joins setCorrelatedVariable(String variable) {
        if (variable == null)
            return this;
        return new SelectJoins(this).setCorrelatedVariable(variable);
    }

    @Override
    public Joins setJoinContext(Context ctx) {
        if (ctx == null)
            return this;
        return new SelectJoins(this).setJoinContext(ctx);
    }

    @Override
    public String getCorrelatedVariable() {
        return null;
    }

    @Override
    public void moveJoinsToParent() {
        // This root SelectJoins instance has no parent join set to receive its joins.
    }
}

/**
 * Common joins interface used internally. Cannot be made an inner class
 * because the outer class (Select) has to implement it.
 */
interface PathJoins
    extends Joins {

    /**
     * Mark this as an outer joins set.
     */
    PathJoins setOuter(boolean outer);

    /**
     * Return true if this instance has a path, any joins, or a variable.
     */
    boolean isDirty();

    /**
     * Return the relation path traversed by these joins, or null if none.
     */
    StringBuilder path();

    /**
     * Return the set of {@link Join} elements, or null if none.
     */
    JoinSet joins();

    /**
     * Return the maximum number of joins contained in this instance at any
     * time.
     */
    int joinCount();

    /**
     * Null the set of {@link Join} elements.
     */
    void nullJoins();

    /**
     * The select owner of this join
     */
    Select getSelect();

    String getPathStr();
}

