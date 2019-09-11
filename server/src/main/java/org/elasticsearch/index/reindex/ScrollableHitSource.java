/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.mapper.SeqNoFieldMapper;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import static java.util.Objects.requireNonNull;
import static org.elasticsearch.common.util.CollectionUtils.isEmpty;

/**
 * A scrollable source of results. Pumps data out into the passed onResponse consumer. Same data may come out several times in case
 * of failures during searching. Once the onResponse consumer is done, it should call AsyncResponse.isDone(time) to receive more data
 * (only receives one response at a time).
 */
public abstract class ScrollableHitSource {
    private final AtomicReference<String> scrollId = new AtomicReference<>();

    protected final Logger logger;
    protected final BackoffPolicy backoffPolicy;
    protected final ThreadPool threadPool;
    private final Runnable countSearchRetry;
    private final Consumer<AsyncResponse> onResponse;
    private final Consumer<Exception> fail;
    private final ToLongFunction<Hit> restartFromValueFunction;
    private long restartFromValue = Long.MIN_VALUE; // need refinement if we support descending.

    public ScrollableHitSource(Logger logger, BackoffPolicy backoffPolicy, ThreadPool threadPool, Runnable countSearchRetry,
                               Consumer<AsyncResponse> onResponse, Consumer<Exception> fail,
                               String restartFromField, Checkpoint checkpoint) {
        this.logger = logger;
        this.backoffPolicy = backoffPolicy;
        this.threadPool = threadPool;
        this.countSearchRetry = countSearchRetry;
        this.onResponse = onResponse;
        this.fail = fail;
        if (restartFromField != null) {
            if (SeqNoFieldMapper.NAME.equals(restartFromField)) {
                restartFromValueFunction = Hit::getSeqNo;
                if (checkpoint != null) {
                    restartFromValue = checkpoint.restartFromValue;
                }
            } else {
                assert checkpoint == null;
                restartFromValueFunction = hit -> Long.MIN_VALUE;
                // todo: non-seqno field support.
                // need to extract field, either from source or by asking for it explicitly.
                // also we need to handle missing values.
                // hit -> ((Number) hit.field(restartFromField).getValue()).longValue();
            }
        } else {
            assert checkpoint == null;
            restartFromValueFunction = hit -> Long.MIN_VALUE;
        }
    }

    public final void start() {
        if (logger.isDebugEnabled()) {
            logger.debug("executing initial scroll against {}",
                isEmpty(indices()) ? "all indices" : indices());
        }

        // todo: we never restart the original request, since if this fails, we probably want fast feedback. But when we add
        // resume from seqNo, we should do retry on that original request, so this needs some care at that time.
        // So far, rejections (429) still lead to retries, since they always did.
        restartNoLogging(TimeValue.ZERO, createRetryListenerNoRestart(this::restart));

    }

    private void restart(RejectAwareActionListener<Response> searchListener) {
        restart(TimeValue.ZERO, searchListener);
    }

    private void restart(TimeValue extraKeepAlive, RejectAwareActionListener<Response> searchListener) {
        if (logger.isDebugEnabled()) {
            logger.debug("restarting search against {} from resume marker {}",
                isEmpty(indices()) ? "all indices" : indices(), restartFromValue);
        }
        restartNoLogging(extraKeepAlive, searchListener);
    }

    private void restartNoLogging(TimeValue extraKeepAlive, RejectAwareActionListener<Response> searchListener) {
        String scrollId = this.scrollId.get();
        if (scrollId != null) {
            // we do not bother waiting for the scroll to be cleared, yet at least. We could implement a policy to
            // not have more than x old scrolls outstanding and wait for their timeout before continuing (we know the timeout).
            // A flaky connection could in principle lead to many scrolls within the timeout window, so could be worth pursuing.
            clearScroll(scrollId, () -> {});
            this.scrollId.set(null);
        }
        if (restartFromValue == Long.MIN_VALUE) {
            doStart(extraKeepAlive, searchListener);
        } else {
            doRestart(extraKeepAlive, restartFromValue, searchListener);
        }
    }

    private RetryListener createRetryListener(Consumer<RejectAwareActionListener<Response>> restartHandler,
                                              Consumer<RejectAwareActionListener<Response>> retryScrollHandler) {
        if (canRestart()) {
            return new RetryListener(logger, threadPool, backoffPolicy,
                countRetries(restartHandler), countRetries(retryScrollHandler),
                ActionListener.wrap(this::onResponse, fail));
        } else {
            return createRetryListenerNoRestart(retryScrollHandler);
        }
    }

    private RetryListener createRetryListenerNoRestart(Consumer<RejectAwareActionListener<Response>> retryScrollHandler) {
        return new RetryListener(logger, threadPool, backoffPolicy,
            x -> { throw new UnsupportedOperationException(); }, countRetries(retryScrollHandler),
            ActionListener.wrap(this::onResponse, fail)) {
            @Override
            public void onResponse(Response response) {
                ScrollableHitSource.this.onResponse(response);
            }

            @Override
            public void onFailure(Exception e) {
                fail.accept(e);
            }
        };
    }

    private Consumer<RejectAwareActionListener<Response>> countRetries(Consumer<RejectAwareActionListener<Response>> retryHandler) {
        return listener -> {
                countSearchRetry.run();
                retryHandler.accept(listener);
            };
    }

    // package private for tests.
    final void startNextScroll(TimeValue extraKeepAlive) {
        startNextScroll(extraKeepAlive, createRetryListener(listener -> restart(extraKeepAlive, listener),
            listener -> startNextScroll(extraKeepAlive, listener)
        ));
    }
    private void startNextScroll(TimeValue extraKeepAlive, RejectAwareActionListener<Response> searchListener) {
        assert scrollId.get() != null;
        doStartNextScroll(scrollId.get(), extraKeepAlive, searchListener);
    }

    private void onResponse(Response response) {
        logger.debug("scroll returned [{}] documents with a scroll id of [{}]", response.getHits().size(), response.getScrollId());
        setScroll(response.getScrollId());
        onResponse.accept(new AsyncResponse() {
            private final AtomicBoolean alreadyDone = new AtomicBoolean();
            private final Checkpoint checkpoint = new Checkpoint(extractRestartFromValue(response.getHits(), restartFromValue));
            @Override
            public Response response() {
                return response;
            }

            @Override
            public void done(TimeValue extraKeepAlive) {
                assert alreadyDone.compareAndSet(false, true);
                restartFromValue = checkpoint.restartFromValue;
                startNextScroll(extraKeepAlive);
            }

            @Override
            public Checkpoint getCheckpoint() {
                return checkpoint;
            }
        });
    }

    private long extractRestartFromValue(List<? extends Hit> hits, long defaultValue) {
        if (hits.size() != 0) {
            return restartFromValueFunction.applyAsLong(hits.get(hits.size() - 1));
        } else {
            return defaultValue;
        }
    }

    public final void close(Runnable onCompletion) {
        String scrollId = this.scrollId.get();
        if (Strings.hasLength(scrollId)) {
            clearScroll(scrollId, () -> cleanup(onCompletion));
        } else {
            cleanup(onCompletion);
        }
    }

    // following is the SPI to be implemented.
    protected abstract void doStart(TimeValue extraKeepAlive, RejectAwareActionListener<Response> searchListener);
    protected abstract void doRestart(TimeValue extraKeepAlive, long restartFromValue, RejectAwareActionListener<Response> searchListener);

    protected abstract void doStartNextScroll(String scrollId, TimeValue extraKeepAlive,
                                              RejectAwareActionListener<Response> searchListener);
    protected abstract boolean canRestart();
    protected abstract String[] indices();

    /**
     * Called to clear a scroll id.
     *
     * @param scrollId the id to clear
     * @param onCompletion implementers must call this after completing the clear whether they are
     *        successful or not
     */
    protected abstract void clearScroll(String scrollId, Runnable onCompletion);
    /**
     * Called after the process has been totally finished to clean up any resources the process
     * needed like remote connections.
     *
     * @param onCompletion implementers must call this after completing the cleanup whether they are
     *        successful or not
     */
    protected abstract void cleanup(Runnable onCompletion);

    /**
     * Set the id of the last scroll. Used for debugging.
     */
    public final void setScroll(String scrollId) {
        this.scrollId.set(scrollId);
    }

    public interface AsyncResponse {
        /**
         * The response data made available.
         */
        Response response();

        /**
         * The checkpoint to use when the response data have been safely handled.
         */
        Checkpoint getCheckpoint();

        /**
         * Called when done processing response to signal more data is needed.
         * @param extraKeepAlive extra time to keep underlying scroll open.
         */
        void done(TimeValue extraKeepAlive);
    }

    /**
     * Response from each scroll batch.
     */
    public static class Response {
        private final boolean timedOut;
        private final List<SearchFailure> failures;
        private final long totalHits;
        private final List<? extends Hit> hits;
        private final String scrollId;

        public Response(boolean timedOut, List<SearchFailure> failures, long totalHits, List<? extends Hit> hits, String scrollId) {
            this.timedOut = timedOut;
            this.failures = failures;
            this.totalHits = totalHits;
            this.hits = hits;
            this.scrollId = scrollId;
        }

        /**
         * Did this batch time out?
         */
        public boolean isTimedOut() {
            return timedOut;
        }

        /**
         * Where there any search failures?
         */
        public final List<SearchFailure> getFailures() {
            return failures;
        }

        /**
         * What were the total number of documents matching the search?
         */
        public long getTotalHits() {
            return totalHits;
        }

        /**
         * The documents returned in this batch.
         */
        public List<? extends Hit> getHits() {
            return hits;
        }

        /**
         * The scroll id used to fetch the next set of documents.
         */
        public String getScrollId() {
            return scrollId;
        }
    }

    /**
     * A document returned as part of the response. Think of it like {@link SearchHit} but with all the things reindex needs in convenient
     * methods.
     */
    public interface Hit {
        /**
         * The index in which the hit is stored.
         */
        String getIndex();
        /**
         * The type that the hit has.
         */
        String getType();
        /**
         * The document id of the hit.
         */
        String getId();
        /**
         * The version of the match or {@code -1} if the version wasn't requested. The {@code -1} keeps it inline with Elasticsearch's
         * internal APIs.
         */
        long getVersion();

        /**
         * The sequence number of the match or {@link SequenceNumbers#UNASSIGNED_SEQ_NO} if sequence numbers weren't requested.
         */
        long getSeqNo();

        /**
         * The primary term of the match or {@link SequenceNumbers#UNASSIGNED_PRIMARY_TERM} if sequence numbers weren't requested.
         */
        long getPrimaryTerm();

        /**
         * The source of the hit. Returns null if the source didn't come back from the search, usually because it source wasn't stored at
         * all.
         */
        @Nullable BytesReference getSource();
        /**
         * The content type of the hit source. Returns null if the source didn't come back from the search.
         */
        @Nullable XContentType getXContentType();
        /**
         * The routing on the hit if there is any or null if there isn't.
         */
        @Nullable String getRouting();
    }

    /**
     * An implementation of {@linkplain Hit} that uses getters and setters.
     */
    public static class BasicHit implements Hit {
        private final String index;
        private final String type;
        private final String id;
        private final long version;

        private BytesReference source;
        private XContentType xContentType;
        private String routing;
        private long seqNo;
        private long primaryTerm;

        public BasicHit(String index, String type, String id, long version) {
            this.index = index;
            this.type = type;
            this.id = id;
            this.version = version;
        }

        @Override
        public String getIndex() {
            return index;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public long getVersion() {
            return version;
        }

        @Override
        public long getSeqNo() {
            return seqNo;
        }

        @Override
        public long getPrimaryTerm() {
            return primaryTerm;
        }

        @Override
        public BytesReference getSource() {
            return source;
        }

        @Override
        public XContentType getXContentType() {
            return xContentType;
        }

        public BasicHit setSource(BytesReference source, XContentType xContentType) {
            this.source = source;
            this.xContentType = xContentType;
            return this;
        }

        @Override
        public String getRouting() {
            return routing;
        }

        public BasicHit setRouting(String routing) {
            this.routing = routing;
            return this;
        }

        public void setSeqNo(long seqNo) {
            this.seqNo = seqNo;
        }

        public void setPrimaryTerm(long primaryTerm) {
            this.primaryTerm = primaryTerm;
        }
    }

    /**
     * A failure during search. Like {@link ShardSearchFailure} but useful for reindex from remote as well.
     */
    public static class SearchFailure implements Writeable, ToXContentObject {
        private final Throwable reason;
        private final RestStatus status;
        @Nullable
        private final String index;
        @Nullable
        private final Integer shardId;
        @Nullable
        private final String nodeId;

        public static final String INDEX_FIELD = "index";
        public static final String SHARD_FIELD = "shard";
        public static final String NODE_FIELD = "node";
        public static final String REASON_FIELD = "reason";
        public static final String STATUS_FIELD = BulkItemResponse.Failure.STATUS_FIELD;

        public SearchFailure(Throwable reason, @Nullable String index, @Nullable Integer shardId, @Nullable String nodeId) {
            this(reason, index, shardId, nodeId, ExceptionsHelper.status(reason));
        }

        public SearchFailure(Throwable reason, @Nullable String index, @Nullable Integer shardId, @Nullable String nodeId,
                             RestStatus status) {
            this.index = index;
            this.shardId = shardId;
            this.reason = requireNonNull(reason, "reason cannot be null");
            this.nodeId = nodeId;
            this.status = status;
        }

        /**
         * Build a search failure that doesn't have shard information available.
         */
        public SearchFailure(Throwable reason) {
            this(reason, null, null, null);
        }

        /**
         * Read from a stream.
         */
        public SearchFailure(StreamInput in) throws IOException {
            reason = in.readException();
            index = in.readOptionalString();
            shardId = in.readOptionalVInt();
            nodeId = in.readOptionalString();
            status = ExceptionsHelper.status(reason);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeException(reason);
            out.writeOptionalString(index);
            out.writeOptionalVInt(shardId);
            out.writeOptionalString(nodeId);
        }

        public String getIndex() {
            return index;
        }

        public Integer getShardId() {
            return shardId;
        }

        public RestStatus getStatus() {
            return this.status;
        }

        public Throwable getReason() {
            return reason;
        }

        @Nullable
        public String getNodeId() {
            return nodeId;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (index != null) {
                builder.field(INDEX_FIELD, index);
            }
            if (shardId != null) {
                builder.field(SHARD_FIELD, shardId);
            }
            if (nodeId != null) {
                builder.field(NODE_FIELD, nodeId);
            }
            builder.field(STATUS_FIELD, status.getStatus());
            builder.field(REASON_FIELD);
            {
                builder.startObject();
                ElasticsearchException.generateThrowableXContent(builder, params, reason);
                builder.endObject();
            }
            builder.endObject();
            return builder;
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }
    }

    /**
     * An opaque object representing the checkpoint state to resume from.
     */
    public static class Checkpoint implements ToXContentObject {
        private static final String RESTART_FROM_VALUE = "restartFromValue";

        private static final ConstructingObjectParser<Checkpoint, Void> PARSER =
            new ConstructingObjectParser<>("reindex/checkpoint", a -> new Checkpoint((long) a[0]));

        static {
            PARSER.declareLong(ConstructingObjectParser.constructorArg(), new ParseField(RESTART_FROM_VALUE));
        }

        // todo: slice handling could complicate this
        private final long restartFromValue;

        protected Checkpoint(long restartFromValue) {
            this.restartFromValue = restartFromValue;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(RESTART_FROM_VALUE, restartFromValue);
            return builder.endObject();
        }

        long getRestartFromValue() {
            return restartFromValue;
        }

        public static Checkpoint fromXContent(XContentParser parser) {
            return PARSER.apply(parser, null);
        }
    }
}
