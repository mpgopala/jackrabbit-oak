/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.search.spi.query;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;

import com.google.common.collect.Lists;
import com.google.common.primitives.Chars;
import org.apache.jackrabbit.oak.api.PropertyValue;
import org.apache.jackrabbit.oak.api.Result.SizePrecision;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.commons.PerfLogger;
import org.apache.jackrabbit.oak.commons.json.JsopBuilder;
import org.apache.jackrabbit.oak.commons.json.JsopWriter;
import org.apache.jackrabbit.oak.plugins.index.Cursors;
import org.apache.jackrabbit.oak.plugins.index.Cursors.PathCursor;
import org.apache.jackrabbit.oak.plugins.index.search.IndexLookup;
import org.apache.jackrabbit.oak.plugins.index.search.IndexNode;
import org.apache.jackrabbit.oak.plugins.index.search.PropertyDefinition;
import org.apache.jackrabbit.oak.plugins.index.search.SizeEstimator;
import org.apache.jackrabbit.oak.plugins.index.search.spi.query.FulltextIndexPlanner.PlanResult;
import org.apache.jackrabbit.oak.plugins.memory.PropertyValues;
import org.apache.jackrabbit.oak.query.facet.FacetResult;
import org.apache.jackrabbit.oak.spi.query.Cursor;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.apache.jackrabbit.oak.spi.query.Filter.PropertyRestriction;
import org.apache.jackrabbit.oak.spi.query.IndexRow;
import org.apache.jackrabbit.oak.spi.query.QueryConstants;
import org.apache.jackrabbit.oak.spi.query.QueryIndex;
import org.apache.jackrabbit.oak.spi.query.QueryIndex.AdvanceFulltextQueryIndex;
import org.apache.jackrabbit.oak.spi.query.QueryLimits;
import org.apache.jackrabbit.oak.spi.query.fulltext.FullTextExpression;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;
import static org.apache.jackrabbit.oak.spi.query.QueryIndex.AdvancedQueryIndex;
import static org.apache.jackrabbit.oak.spi.query.QueryIndex.NativeQueryIndex;

/**
 * Provides an abstract QueryIndex that does lookups against a fulltext index
 *
 * @see QueryIndex
 *
 */
public abstract class FulltextIndex implements AdvancedQueryIndex, QueryIndex, NativeQueryIndex,
        AdvanceFulltextQueryIndex {

    private final Logger LOG = LoggerFactory
            .getLogger(getClass());
    private final PerfLogger PERF_LOGGER =
            new PerfLogger(LoggerFactory.getLogger(getClass() + ".perf"));

    static final String ATTR_PLAN_RESULT = "oak.fulltext.planResult";

    protected abstract FulltextIndexTracker getIndexTracker();

    protected abstract String getType();

    protected abstract SizeEstimator getSizeEstimator(IndexPlan plan);

    protected abstract String getFulltextRequestString(IndexPlan plan, IndexNode indexNode);

    @Override
    public List<IndexPlan> getPlans(Filter filter, List<OrderEntry> sortOrder, NodeState rootState) {
        Collection<String> indexPaths = new IndexLookup(rootState).collectIndexNodePaths(filter, getType());
        List<IndexPlan> plans = Lists.newArrayListWithCapacity(indexPaths.size());
        for (String path : indexPaths) {
            IndexNode indexNode = null;
            try {
                indexNode = getIndexTracker().acquireIndexNode(path, getType());

                if (indexNode != null) {
                    IndexPlan plan = new FulltextIndexPlanner(indexNode, path, filter, sortOrder).getPlan();
                    if (plan != null) {
                        plans.add(plan);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error getting plan for {}", path);
                LOG.error("Exception:", e);
            } finally {
                if (indexNode != null) {
                    indexNode.release();
                }
            }
        }
        return plans;
    }

    @Override
    public double getCost(Filter filter, NodeState root) {
        throw new UnsupportedOperationException("Not supported as implementing AdvancedQueryIndex");
    }

    @Override
    public String getPlan(Filter filter, NodeState root) {
        throw new UnsupportedOperationException("Not supported as implementing AdvancedQueryIndex");
    }

    @Override
    public String getPlanDescription(IndexPlan plan, NodeState root) {
        Filter filter = plan.getFilter();
        IndexNode index = getIndexTracker().acquireIndexNode(getPlanResult(plan).indexPath, getType());
        checkState(index != null, "The Fulltext of type " + getType() + "  index is not available");
        try {
            FullTextExpression ft = filter.getFullTextConstraint();
            StringBuilder sb = new StringBuilder(getType()).append(":");
            String path = getPlanResult(plan).indexPath;
            sb.append(getIndexName(plan))
                    .append("(")
                    .append(path)
                    .append(") ");
            sb.append(getFulltextRequestString(plan, index));
            if (plan.getSortOrder() != null && !plan.getSortOrder().isEmpty()) {
                sb.append(" ordering:").append(plan.getSortOrder());
            }
            if (ft != null) {
                sb.append(" ft:(").append(ft).append(")");
            }
            addSyncIndexPlan(plan, sb);
            return sb.toString();
        } finally {
            index.release();
        }
    }

    private static void addSyncIndexPlan(IndexPlan plan, StringBuilder sb) {
        FulltextIndexPlanner.PlanResult pr = getPlanResult(plan);
        if (pr.hasPropertyIndexResult()) {
            FulltextIndexPlanner.PropertyIndexResult pres = pr.getPropertyIndexResult();
            sb.append(" sync:(")
              .append(pres.propertyName);

            if (!pres.propertyName.equals(pres.pr.propertyName)) {
               sb.append("[").append(pres.pr.propertyName).append("]");
            }

            sb.append(" ").append(pres.pr);
            sb.append(")");
        }

        if (pr.evaluateSyncNodeTypeRestriction()) {
            sb.append(" sync:(nodeType");
            sb.append(" primaryTypes : ").append(plan.getFilter().getPrimaryTypes());
            sb.append(" mixinTypes : ").append(plan.getFilter().getMixinTypes());
            sb.append(")");
        }
    }

    @Override
    public Cursor query(final Filter filter, final NodeState root) {
        throw new UnsupportedOperationException("Not supported as implementing AdvancedQueryIndex");
    }

    private static boolean shouldInclude(String docPath, IndexPlan plan) {
        String path = getPathRestriction(plan);

        boolean include = true;

        Filter filter = plan.getFilter();
        switch (filter.getPathRestriction()) {
            case EXACT:
                include = path.equals(docPath);
                break;
            case DIRECT_CHILDREN:
                include = PathUtils.getParentPath(docPath).equals(path);
                break;
            case ALL_CHILDREN:
                include = PathUtils.isAncestor(path, docPath);
                break;
        }

        return include;
    }

    @Override
    public NodeAggregator getNodeAggregator() {
        return null;
    }

    /**
     * In a fulltext term for jcr:contains(foo, 'bar') 'foo'
     * is the property name. While in jcr:contains(foo/*, 'bar')
     * 'foo' is node name
     *
     * @return true if the term is related to node
     */
    public static boolean isNodePath(String fulltextTermPath) {
        return fulltextTermPath.endsWith("/*");
    }

    protected IndexNode acquireIndexNode(IndexPlan plan) {
        return getIndexTracker().acquireIndexNode(getPlanResult(plan).indexPath, getType());
    }

    protected static String getIndexName(IndexPlan plan) {
        return PathUtils.getName(getPlanResult(plan).indexPath);
    }

    protected static int determinePropertyType(PropertyDefinition defn, PropertyRestriction pr) {
        int typeFromRestriction = pr.propertyType;
        if (typeFromRestriction == PropertyType.UNDEFINED) {
            //If no explicit type defined then determine the type from restriction
            //value
            if (pr.first != null && pr.first.getType() != Type.UNDEFINED) {
                typeFromRestriction = pr.first.getType().tag();
            } else if (pr.last != null && pr.last.getType() != Type.UNDEFINED) {
                typeFromRestriction = pr.last.getType().tag();
            } else if (pr.list != null && !pr.list.isEmpty()) {
                typeFromRestriction = pr.list.get(0).getType().tag();
            }
        }
        return getPropertyType(defn, pr.propertyName, typeFromRestriction);
    }

    private static int getPropertyType(PropertyDefinition defn, String name, int defaultVal) {
        if (defn.isTypeDefined()) {
            return defn.getType();
        }
        return defaultVal;
    }

    protected static PlanResult getPlanResult(IndexPlan plan) {
        return (PlanResult) plan.getAttribute(ATTR_PLAN_RESULT);
    }

    /**
     * Following chars are used as operators in Lucene Query and should be escaped
     */
    private static final char[] QUERY_OPERATORS = {':' , '/', '!', '&', '|', '='};

    /**
     * Following logic is taken from org.apache.jackrabbit.core.query.lucene.JackrabbitQueryParser#parse(java.lang.String)
     */
    static String rewriteQueryText(String textsearch) {
        // replace escaped ' with just '
        StringBuilder rewritten = new StringBuilder();
        // most query parsers recognize 'AND' and 'NOT' as
        // keywords.
        textsearch = textsearch.replaceAll("AND", "and");
        textsearch = textsearch.replaceAll("NOT", "not");
        boolean escaped = false;
        for (int i = 0; i < textsearch.length(); i++) {
            char c = textsearch.charAt(i);
            if (c == '\\') {
                if (escaped) {
                    rewritten.append("\\\\");
                    escaped = false;
                } else {
                    escaped = true;
                }
            } else if (c == '\'') {
                if (escaped) {
                    escaped = false;
                }
                rewritten.append(c);
            } else if (Chars.contains(QUERY_OPERATORS, c)) {
                rewritten.append('\\').append(c);
            } else {
                if (escaped) {
                    rewritten.append('\\');
                    escaped = false;
                }
                rewritten.append(c);
            }
        }
        return rewritten.toString();
    }

    protected static String getPathRestriction(IndexPlan plan) {
        Filter f = plan.getFilter();
        String pathPrefix = plan.getPathPrefix();
        if (pathPrefix.isEmpty()) {
            return f.getPath();
        }
        String relativePath = PathUtils.relativize(pathPrefix, f.getPath());
        return "/" + relativePath;
    }

    static class FulltextResultRow {
        final String path;
        final double score;
        final String suggestion;
        final boolean isVirutal;
        final Map<String, String> excerpts;
        final String explanation;
        final List<FacetResult.Facet> facets;

        FulltextResultRow(String path, double score, Map<String, String> excerpts, List<FacetResult.Facet> facets, String explanation) {
            this.explanation = explanation;
            this.excerpts = excerpts;
            this.facets = facets;
            this.isVirutal = false;
            this.path = path;
            this.score = score;
            this.suggestion = null;
        }

        FulltextResultRow(String suggestion, long weight) {
            this.isVirutal = true;
            this.path = "/";
            this.score = weight;
            this.suggestion = suggestion;
            this.excerpts = null;
            this.facets = null;
            this.explanation = null;
        }

        FulltextResultRow(String suggestion) {
            this(suggestion, 1);
        }

        @Override
        public String toString() {
            return String.format("%s (%1.2f)", path, score);
        }
    }

    /**
     * A cursor over Fulltext results. The result includes the path,
     * and the jcr:score pseudo-property as returned by Lucene.
     */
    static class FulltextPathCursor implements Cursor {

        private final Logger log = LoggerFactory.getLogger(getClass());
        private static final int TRAVERSING_WARNING = Integer.getInteger("oak.traversing.warning", 10000);

        private final Cursor pathCursor;
        private final String pathPrefix;
        FulltextResultRow currentRow;
        private final SizeEstimator sizeEstimator;
        private long estimatedSize;
        private int numberOfFacets;

        FulltextPathCursor(final Iterator<FulltextResultRow> it, final IndexPlan plan, QueryLimits settings, SizeEstimator sizeEstimator) {
            pathPrefix = plan.getPathPrefix();
            this.sizeEstimator = sizeEstimator;
            Iterator<String> pathIterator = new Iterator<String>() {

                private int readCount;

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public String next() {
                    currentRow = it.next();
                    readCount++;
                    if (readCount % TRAVERSING_WARNING == 0) {
                        Cursors.checkReadLimit(readCount, settings);
                        log.warn("Index-Traversed {} nodes with filter {}", readCount, plan.getFilter());
                    }
                    return currentRow.path;
                }

                @Override
                public void remove() {
                    it.remove();
                }

            };

            PlanResult planResult = getPlanResult(plan);
            pathCursor = new PathCursor(pathIterator, planResult.isUniquePathsRequired(), settings);
            numberOfFacets = planResult.indexDefinition.getNumberOfTopFacets();
        }


        @Override
        public boolean hasNext() {
            return pathCursor.hasNext();
        }

        @Override
        public void remove() {
            pathCursor.remove();
        }

        @Override
        public IndexRow next() {
            final IndexRow pathRow = pathCursor.next();
            return new IndexRow() {

                @Override
                public boolean isVirtualRow() {
                    return currentRow.isVirutal;
                }

                @Override
                public String getPath() {
                    String sub = pathRow.getPath();
                    if (isVirtualRow()) {
                        return sub;
                    } else if (!"".equals(pathPrefix) && PathUtils.denotesRoot(sub)) {
                        return pathPrefix;
                    } else if (PathUtils.isAbsolute(sub)) {
                        return pathPrefix + sub;
                    } else {
                        return PathUtils.concat(pathPrefix, sub);
                    }
                }

                @Override
                public PropertyValue getValue(String columnName) {
                    // overlay the score
                    if (QueryConstants.JCR_SCORE.equals(columnName)) {
                        return PropertyValues.newDouble(currentRow.score);
                    }
                    if (QueryConstants.REP_SPELLCHECK.equals(columnName) || QueryConstants.REP_SUGGEST.equals(columnName)) {
                        return PropertyValues.newString(currentRow.suggestion);
                    }
                    if (QueryConstants.OAK_SCORE_EXPLANATION.equals(columnName)) {
                        return PropertyValues.newString(currentRow.explanation);
                    }
                    if (columnName.startsWith(QueryConstants.REP_EXCERPT)) {
                        String excerpt = currentRow.excerpts.get(columnName);
                        if (excerpt != null) {
                            return PropertyValues.newString(excerpt);
                        }
                    }
                    if (columnName.startsWith(QueryConstants.REP_FACET)) {
                        List<FacetResult.Facet> facets = currentRow.facets;
                        try {
                            if (facets != null) {
                                JsopWriter writer = new JsopBuilder();
                                writer.object();
                                for (FacetResult.Facet f : facets) {
                                    writer.key(f.getLabel()).value(f.getCount());
                                }
                                writer.endObject();
                                return PropertyValues.newString(writer.toString());
                            } else {
                                return null;
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return pathRow.getValue(columnName);
                }

            };
        }

        @Override
        public long getSize(SizePrecision precision, long max) {
            if (estimatedSize != 0) {
                return estimatedSize;
            }
            return estimatedSize = sizeEstimator.getSize();
        }
    }

    static String parseFacetField(String columnName) {
        return columnName.substring(QueryConstants.REP_FACET.length() + 1, columnName.length() - 1);
    }
}
