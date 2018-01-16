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

package org.elasticsearch.action.admin.indices.create;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.UnavailableShardsException;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_WAIT_FOR_ACTIVE_SHARDS;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertBlocked;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.IsNull.notNullValue;

@ClusterScope(scope = Scope.TEST)
public class CreateIndexIT extends ESIntegTestCase {

    public void testCreationDateGivenFails() {
        try {
            prepareCreate("test").setSettings(Settings.builder().put(IndexMetaData.SETTING_CREATION_DATE, 4L)).get();
            fail();
        } catch (IllegalArgumentException ex) {
            assertEquals("unknown setting [index.creation_date] please check that any required plugins are installed, or check the " +
                "breaking changes documentation for removed settings", ex.getMessage());
        }
    }

    public void testCreationDateGenerated() {
        long timeBeforeRequest = System.currentTimeMillis();
        prepareCreate("test").get();
        long timeAfterRequest = System.currentTimeMillis();
        ClusterStateResponse response = client().admin().cluster().prepareState().get();
        ClusterState state = response.getState();
        assertThat(state, notNullValue());
        MetaData metadata = state.getMetaData();
        assertThat(metadata, notNullValue());
        ImmutableOpenMap<String, IndexMetaData> indices = metadata.getIndices();
        assertThat(indices, notNullValue());
        assertThat(indices.size(), equalTo(1));
        IndexMetaData index = indices.get("test");
        assertThat(index, notNullValue());
        assertThat(index.getCreationDate(), allOf(lessThanOrEqualTo(timeAfterRequest), greaterThanOrEqualTo(timeBeforeRequest)));
    }

    public void testDoubleAddMapping() throws Exception {
        try {
            prepareCreate("test")
                    .addMapping("type1", "date", "type=date")
                    .addMapping("type1", "num", "type=integer");
            fail("did not hit expected exception");
        } catch (IllegalStateException ise) {
            // expected
        }
        try {
            prepareCreate("test")
                    .addMapping("type1", new HashMap<String,Object>())
                    .addMapping("type1", new HashMap<String,Object>());
            fail("did not hit expected exception");
        } catch (IllegalStateException ise) {
            // expected
        }
        try {
            prepareCreate("test")
                    .addMapping("type1", jsonBuilder())
                    .addMapping("type1", jsonBuilder());
            fail("did not hit expected exception");
        } catch (IllegalStateException ise) {
            // expected
        }
    }

    public void testInvalidShardCountSettings() throws Exception {
        int value = randomIntBetween(-10, 0);
        try {
            prepareCreate("test").setSettings(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, value)
                    .build())
            .get();
            fail("should have thrown an exception about the primary shard count");
        } catch (IllegalArgumentException e) {
            assertEquals("Failed to parse value [" + value + "] for setting [index.number_of_shards] must be >= 1", e.getMessage());
        }
        value = randomIntBetween(-10, -1);
        try {
            prepareCreate("test").setSettings(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, value)
                    .build())
                    .get();
            fail("should have thrown an exception about the replica shard count");
        } catch (IllegalArgumentException e) {
            assertEquals("Failed to parse value [" + value + "] for setting [index.number_of_replicas] must be >= 0", e.getMessage());
        }

    }

    public void testCreateIndexWithBlocks() {
        try {
            setClusterReadOnly(true);
            assertBlocked(prepareCreate("test"));
        } finally {
            setClusterReadOnly(false);
        }
    }

    public void testCreateIndexWithMetadataBlocks() {
        assertAcked(prepareCreate("test").setSettings(Settings.builder().put(IndexMetaData.SETTING_BLOCKS_METADATA, true)));
        assertBlocked(client().admin().indices().prepareGetSettings("test"), IndexMetaData.INDEX_METADATA_BLOCK);
        disableIndexBlock("test", IndexMetaData.SETTING_BLOCKS_METADATA);
    }

    public void testUnknownSettingFails() {
        try {
            prepareCreate("test").setSettings(Settings.builder()
                .put("index.unknown.value", "this must fail")
                .build())
                .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertEquals("unknown setting [index.unknown.value] please check that any required plugins are installed, or check the" +
                " breaking changes documentation for removed settings", e.getMessage());
        }
    }

    public void testInvalidShardCountSettingsWithoutPrefix() throws Exception {
        int value = randomIntBetween(-10, 0);
        try {
            prepareCreate("test").setSettings(Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS.substring(IndexMetaData.INDEX_SETTING_PREFIX.length()), value)
                .build())
                .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertEquals("Failed to parse value [" + value + "] for setting [index.number_of_shards] must be >= 1", e.getMessage());
        }
        value = randomIntBetween(-10, -1);
        try {
            prepareCreate("test").setSettings(Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS.substring(IndexMetaData.INDEX_SETTING_PREFIX.length()), value)
                .build())
                .get();
            fail("should have thrown an exception about the shard count");
        } catch (IllegalArgumentException e) {
            assertEquals("Failed to parse value [" + value + "] for setting [index.number_of_replicas] must be >= 0", e.getMessage());
        }
    }

    public void testCreateAndDeleteIndexConcurrently() throws InterruptedException {
        createIndex("test");
        final AtomicInteger indexVersion = new AtomicInteger(0);
        final Object indexVersionLock = new Object();
        final CountDownLatch latch = new CountDownLatch(1);
        int numDocs = randomIntBetween(1, 10);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex("test", "test").setSource("index_version", indexVersion.get()).get();
        }
        synchronized (indexVersionLock) { // not necessarily needed here but for completeness we lock here too
            indexVersion.incrementAndGet();
        }
        client().admin().indices().prepareDelete("test").execute(new ActionListener<DeleteIndexResponse>() { // this happens async!!!
                @Override
                public void onResponse(DeleteIndexResponse deleteIndexResponse) {
                    Thread thread = new Thread() {
                     @Override
                    public void run() {
                         try {
                             // recreate that index
                             client().prepareIndex("test", "test").setSource("index_version", indexVersion.get()).get();
                             synchronized (indexVersionLock) {
                                 // we sync here since we have to ensure that all indexing operations below for a given ID are done before
                                 // we increment the index version otherwise a doc that is in-flight could make it into an index that it
                                 // was supposed to be deleted for and our assertion fail...
                                 indexVersion.incrementAndGet();
                             }
                             // from here on all docs with index_version == 0|1 must be gone!!!! only 2 are ok;
                             assertAcked(client().admin().indices().prepareDelete("test").get());
                         } finally {
                             latch.countDown();
                         }
                     }
                    };
                    thread.start();
                }

                @Override
                public void onFailure(Exception e) {
                    throw new RuntimeException(e);
                }
            }
        );
        numDocs = randomIntBetween(100, 200);
        for (int i = 0; i < numDocs; i++) {
            try {
                synchronized (indexVersionLock) {
                    client().prepareIndex("test", "test").setSource("index_version", indexVersion.get())
                        .setTimeout(TimeValue.timeValueSeconds(10)).get();
                }
            } catch (IndexNotFoundException inf) {
                // fine
            } catch (UnavailableShardsException ex) {
                assertEquals(ex.getCause().getClass(), IndexNotFoundException.class);
                // fine we run into a delete index while retrying
            }
        }
        latch.await();
        refresh();

        // we only really assert that we never reuse segments of old indices or anything like this here and that nothing fails with
        // crazy exceptions
        SearchResponse expected = client().prepareSearch("test").setIndicesOptions(IndicesOptions.lenientExpandOpen())
            .setQuery(new RangeQueryBuilder("index_version").from(indexVersion.get(), true)).get();
        SearchResponse all = client().prepareSearch("test").setIndicesOptions(IndicesOptions.lenientExpandOpen()).get();
        assertEquals(expected + " vs. " + all, expected.getHits().getTotalHits(), all.getHits().getTotalHits());
        logger.info("total: {}", expected.getHits().getTotalHits());
    }

    /**
     * Asserts that the root cause of mapping conflicts is readable.
     */
    public void testMappingConflictRootCause() throws Exception {
        CreateIndexRequestBuilder b = prepareCreate("test");
        b.addMapping("type1", jsonBuilder().startObject().startObject("properties")
                .startObject("text")
                    .field("type", "text")
                    .field("analyzer", "standard")
                    .field("search_analyzer", "whitespace")
                .endObject().endObject().endObject());
        b.addMapping("type2", jsonBuilder().humanReadable(true).startObject().startObject("properties")
                .startObject("text")
                    .field("type", "text")
                .endObject().endObject().endObject());
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> b.get());
        assertThat(e.getMessage(), containsString("mapper [text] is used by multiple types"));
    }

    public void testRestartIndexCreationAfterFullClusterRestart() throws Exception {
        client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder().put("cluster.routing.allocation.enable",
            "none")).get();
        client().admin().indices().prepareCreate("test").setWaitForActiveShards(ActiveShardCount.NONE).setSettings(indexSettings()).get();
        internalCluster().fullRestart();
        ensureGreen("test");
    }

    /**
     * This test ensures that index creation adheres to the {@link IndexMetaData#SETTING_WAIT_FOR_ACTIVE_SHARDS}.
     */
    public void testDefaultWaitForActiveShardsUsesIndexSetting() throws Exception {
        final int numReplicas = internalCluster().numDataNodes();
        Settings settings = Settings.builder()
                                .put(SETTING_WAIT_FOR_ACTIVE_SHARDS.getKey(), Integer.toString(numReplicas))
                                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), numReplicas)
                                .build();
        assertAcked(client().admin().indices().prepareCreate("test-idx-1").setSettings(settings).get());

        // all should fail
        settings = Settings.builder()
                       .put(settings)
                       .put(SETTING_WAIT_FOR_ACTIVE_SHARDS.getKey(), "all")
                       .build();
        assertFalse(client().admin().indices().prepareCreate("test-idx-2").setSettings(settings).setTimeout("100ms").get().isShardsAcked());

        // the numeric equivalent of all should also fail
        settings = Settings.builder()
                       .put(settings)
                       .put(SETTING_WAIT_FOR_ACTIVE_SHARDS.getKey(), Integer.toString(numReplicas + 1))
                       .build();
        assertFalse(client().admin().indices().prepareCreate("test-idx-3").setSettings(settings).setTimeout("100ms").get().isShardsAcked());
    }

    public void testInvalidPartitionSize() {
        BiFunction<Integer, Integer, Boolean> createPartitionedIndex = (shards, partitionSize) -> {
            CreateIndexResponse response;

            try {
                response = prepareCreate("test_" + shards + "_" + partitionSize)
                    .setSettings(Settings.builder()
                        .put("index.number_of_shards", shards)
                        .put("index.routing_partition_size", partitionSize))
                    .execute().actionGet();
            } catch (IllegalStateException | IllegalArgumentException e) {
                return false;
            }

            return response.isAcknowledged();
        };

        assertFalse(createPartitionedIndex.apply(3, 6));
        assertFalse(createPartitionedIndex.apply(3, 0));
        assertFalse(createPartitionedIndex.apply(3, 3));

        assertTrue(createPartitionedIndex.apply(3, 1));
        assertTrue(createPartitionedIndex.apply(3, 2));

        assertTrue(createPartitionedIndex.apply(1, 1));
    }

    public void testIndexNameInResponse() {
        CreateIndexResponse response = prepareCreate("foo")
            .setSettings(Settings.builder().build())
            .get();

        assertEquals("Should have index name in response", "foo", response.index());
    }

    /**
     * This test method is used to generate the Put Mapping Java Indices API documentation
     * at "docs/java-api/admin/indices/put-mapping.asciidoc" so the documentation gets tested
     * so that it compiles and runs without throwing errors at runtime.
     */
     public void testPutMappingDocumentation() throws Exception {
         Client client = client();
         // tag::addMapping-create-index-request
         client.admin().indices().prepareCreate("twitter")  // <1>
         .addMapping("tweet", "{\n" +                       // <2>
                 "    \"tweet\": {\n" +
                 "      \"properties\": {\n" +
                 "        \"message\": {\n" +
                 "          \"type\": \"text\"\n" +
                 "        }\n" +
                 "      }\n" +
                 "    }\n" +
                 "  }", XContentType.JSON)
         .get();
         // end::addMapping-create-index-request

         // we need to delete in order to create a fresh new index with another type
         client.admin().indices().prepareDelete("twitter").get();
         client.admin().indices().prepareCreate("twitter").get();

         // tag::putMapping-request-source
         client.admin().indices().preparePutMapping("twitter")   // <1>
         .setType("user")                                        // <2>
         .setSource("{\n" +                                      // <3>
                 "  \"properties\": {\n" +
                 "    \"name\": {\n" +
                 "      \"type\": \"text\"\n" +
                 "    }\n" +
                 "  }\n" +
                 "}", XContentType.JSON)
         .get();

         // You can also provide the type in the source document
         client.admin().indices().preparePutMapping("twitter")
         .setType("user")
         .setSource("{\n" +
                 "    \"user\":{\n" +                            // <4>
                 "        \"properties\": {\n" +
                 "            \"name\": {\n" +
                 "                \"type\": \"text\"\n" +
                 "            }\n" +
                 "        }\n" +
                 "    }\n" +
                 "}", XContentType.JSON)
         .get();
         // end::putMapping-request-source

         // tag::putMapping-request-source-append
         client.admin().indices().preparePutMapping("twitter")   // <1>
         .setType("user")                                        // <2>
         .setSource("{\n" +                                      // <3>
                 "  \"properties\": {\n" +
                 "    \"user_name\": {\n" +
                 "      \"type\": \"text\"\n" +
                 "    }\n" +
                 "  }\n" +
                 "}", XContentType.JSON)
         .get();
         // end::putMapping-request-source-append
     }
}
