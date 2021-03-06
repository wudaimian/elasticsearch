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

package org.elasticsearch.action.search;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.TimestampParsingException;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.IndexShardClosedException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.InvalidIndexTemplateException;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

public class SearchPhaseExecutionExceptionTests extends ESTestCase {

    public void testToXContent() throws IOException {
        SearchPhaseExecutionException exception = new SearchPhaseExecutionException("test", "all shards failed",
                new ShardSearchFailure[]{
                        new ShardSearchFailure(new ParsingException(1, 2, "foobar", null),
                                new SearchShardTarget("node_1", new Index("foo", "_na_"), 0)),
                        new ShardSearchFailure(new IndexShardClosedException(new ShardId(new Index("foo", "_na_"), 1)),
                                new SearchShardTarget("node_2", new Index("foo", "_na_"), 1)),
                        new ShardSearchFailure(new ParsingException(5, 7, "foobar", null),
                                new SearchShardTarget("node_3", new Index("foo", "_na_"), 2)),
                });

        // Failures are grouped (by default)
        assertEquals("{" +
                "\"type\":\"search_phase_execution_exception\"," +
                "\"reason\":\"all shards failed\"," +
                "\"phase\":\"test\"," +
                "\"grouped\":true," +
                "\"failed_shards\":[" +
                        "{" +
                            "\"shard\":0," +
                            "\"index\":\"foo\"," +
                            "\"node\":\"node_1\"," +
                            "\"reason\":{" +
                                        "\"type\":\"parsing_exception\"," +
                                        "\"reason\":\"foobar\"," +
                                        "\"line\":1," +
                                        "\"col\":2" +
                            "}" +
                        "}," +
                        "{" +
                            "\"shard\":1," +
                            "\"index\":\"foo\"," +
                            "\"node\":\"node_2\"," +
                            "\"reason\":{" +
                                        "\"type\":\"index_shard_closed_exception\"," +
                                        "\"reason\":\"CurrentState[CLOSED] Closed\"," +
                                        "\"index_uuid\":\"_na_\"," +
                                        "\"shard\":\"1\"," +
                                        "\"index\":\"foo\"" +
                            "}" +
                        "}" +
                "]}", Strings.toString(exception));

        // Failures are NOT grouped
        ToXContent.MapParams params = new ToXContent.MapParams(singletonMap("group_shard_failures", "false"));
        try (XContentBuilder builder = jsonBuilder()) {
            builder.startObject();
            exception.toXContent(builder, params);
            builder.endObject();

            assertEquals("{" +
                    "\"type\":\"search_phase_execution_exception\"," +
                    "\"reason\":\"all shards failed\"," +
                    "\"phase\":\"test\"," +
                    "\"grouped\":false," +
                    "\"failed_shards\":[" +
                            "{" +
                                "\"shard\":0," +
                                "\"index\":\"foo\"," +
                                "\"node\":\"node_1\"," +
                                "\"reason\":{" +
                                            "\"type\":\"parsing_exception\"," +
                                            "\"reason\":\"foobar\"," +
                                            "\"line\":1," +
                                            "\"col\":2" +
                                "}" +
                            "}," +
                            "{" +
                                "\"shard\":1," +
                                "\"index\":\"foo\"," +
                                "\"node\":\"node_2\"," +
                                "\"reason\":{" +
                                            "\"type\":\"index_shard_closed_exception\"," +
                                            "\"reason\":\"CurrentState[CLOSED] Closed\"," +
                                            "\"index_uuid\":\"_na_\"," +
                                            "\"shard\":\"1\"," +
                                            "\"index\":\"foo\"" +
                                "}" +
                            "}," +
                            "{" +
                                "\"shard\":2," +
                                "\"index\":\"foo\"," +
                                "\"node\":\"node_3\"," +
                                "\"reason\":{" +
                                            "\"type\":\"parsing_exception\"," +
                                            "\"reason\":\"foobar\"," +
                                            "\"line\":5," +
                                            "\"col\":7" +
                                "}" +
                            "}" +
                    "]}", builder.string());
        }
    }

    public void testToAndFromXContent() throws IOException {
        final XContent xContent = randomFrom(XContentType.values()).xContent();

        ShardSearchFailure[] shardSearchFailures = new ShardSearchFailure[randomIntBetween(1, 5)];
        for (int i = 0; i < shardSearchFailures.length; i++) {
            Exception cause = randomFrom(
                    new ParsingException(1, 2, "foobar", null),
                    new InvalidIndexTemplateException("foo", "bar"),
                    new TimestampParsingException("foo", null),
                    new NullPointerException()
            );
            shardSearchFailures[i] = new  ShardSearchFailure(cause, new SearchShardTarget("node_" + i, new Index("test", "_na_"), i));
        }

        final String phase = randomFrom("query", "search", "other");
        SearchPhaseExecutionException actual = new SearchPhaseExecutionException(phase, "unexpected failures", shardSearchFailures);

        BytesReference exceptionBytes = toShuffledXContent(actual, xContent.type(), ToXContent.EMPTY_PARAMS, randomBoolean());

        ElasticsearchException parsedException;
        try (XContentParser parser = createParser(xContent, exceptionBytes)) {
            assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken());
            parsedException = ElasticsearchException.fromXContent(parser);
            assertEquals(XContentParser.Token.END_OBJECT, parser.currentToken());
            assertNull(parser.nextToken());
        }

        assertNotNull(parsedException);
        assertThat(parsedException.getHeaderKeys(), hasSize(0));
        assertThat(parsedException.getMetadataKeys(), hasSize(1));
        assertThat(parsedException.getMetadata("es.phase"), hasItem(phase));
        // SearchPhaseExecutionException has no cause field
        assertNull(parsedException.getCause());
    }
}
