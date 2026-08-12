/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine;

import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.index.CriteriaBasedMergePolicy;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.VersionType;
import org.opensearch.index.mapper.ParsedDocument;
import org.opensearch.index.mapper.Uid;
import org.opensearch.index.store.Store;
import org.opensearch.test.IndexSettingsModule;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.opensearch.common.util.FeatureFlags.CONTEXT_AWARE_MIGRATION_EXPERIMENTAL_FLAG;
import static org.opensearch.index.codec.CriteriaBasedCodec.BUCKET_NAME;
import static org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO;

/**
 * Tests demonstrating that Context Aware Segments allow the same _id to coexist
 * across different context/tenants (grouping criteria) within the same index.
 *
 * <p>In a multi-tenant scenario, "tenant1" and "tenant2" should each be able to
 * have a document with _id="123" in the same index, isolated by their respective
 * context (grouping criteria). Each tenant's documents reside in separate segments
 * tagged with the tenant's bucket name.</p>
 */
public class ContextAwareSegmentMultiTenantTests extends EngineTestCase {

    /**
     * Test: Two tenants (tenant1, tenant2) each index a document with _id="123"
     * into the same context-aware index. After refresh, the index should contain
     * exactly 2 documents, both with _id="123" but in separate context-specific segments.
     */
    @LockFeatureFlag(CONTEXT_AWARE_MIGRATION_EXPERIMENTAL_FLAG)
    public void testSameIdDifferentContextsCoexist() throws Exception {
        final IndexSettings indexSettings = IndexSettingsModule.newIndexSettings(
            "test",
            Settings.builder()
                .put(defaultSettings.getSettings())
                .put(IndexSettings.INDEX_CONTEXT_AWARE_ENABLED_SETTING.getKey(), true)
                .build()
        );

        try (
            Store store = createStore();
            InternalEngine engine = createEngine(
                indexSettings, store, createTempDir(), new CriteriaBasedMergePolicy(NoMergePolicy.INSTANCE),
                null, null, null, null, null
            )
        ) {
            // --- Step 1: tenant1 writes doc _id="123" ---
            final ParsedDocument docTenant1 = testParsedDocument(
                "123",
                null,
                testContextSpecificDocument("tenant1"),
                new BytesArray("{ \"tenant\": \"tenant1\", \"data\": \"hello from tenant1\" }"),
                null
            );
            Engine.Index indexOpTenant1 = new Engine.Index(
                newUid(docTenant1),
                docTenant1,
                UNASSIGNED_SEQ_NO,
                primaryTerm.get(),
                1L,
                VersionType.EXTERNAL,
                Engine.Operation.Origin.PRIMARY,
                System.nanoTime(),
                -1,
                false,
                UNASSIGNED_SEQ_NO,
                0
            );
            Engine.IndexResult result1 = engine.index(indexOpTenant1);
            assertNotNull("tenant1 indexing should succeed", result1);
            assertNull("tenant1 indexing should not have failure", result1.getFailure());

            // --- Step 2: tenant2 writes doc _id="123" ---
            final ParsedDocument docTenant2 = testParsedDocument(
                "123",
                null,
                testContextSpecificDocument("tenant2"),
                new BytesArray("{ \"tenant\": \"tenant2\", \"data\": \"hello from tenant2\" }"),
                null
            );
            Engine.Index indexOpTenant2 = new Engine.Index(
                newUid(docTenant2),
                docTenant2,
                UNASSIGNED_SEQ_NO,
                primaryTerm.get(),
                2L,
                VersionType.EXTERNAL,
                Engine.Operation.Origin.PRIMARY,
                System.nanoTime(),
                -1,
                false,
                UNASSIGNED_SEQ_NO,
                0
            );
            Engine.IndexResult result2 = engine.index(indexOpTenant2);
            assertNotNull("tenant2 indexing should succeed", result2);
            assertNull("tenant2 indexing should not have failure", result2.getFailure());

            // --- Step 3: Refresh to make documents searchable ---
            engine.refresh("test");

            // --- Step 4: Verify both documents exist ---
            try (Engine.Searcher searcher = engine.acquireSearcher("test")) {
                // Total document count should be 2
                assertEquals(
                    "Index should contain exactly 2 documents (one per tenant with same _id)",
                    2,
                    searcher.getIndexReader().numDocs()
                );

                // Search by _id = "123" should return 2 hits
                TopDocs topDocs = searcher.search(
                    new TermQuery(new Term("_id", Uid.encodeId("123"))),
                    10
                );
                assertEquals(
                    "Searching by _id='123' should find 2 documents (one from each tenant)",
                    2,
                    topDocs.totalHits.value()
                );
            }

            // --- Step 5: Verify segments are separated by context ---
            List<Segment> segments = engine.segments(true);
            Set<String> bucketNames = segments.stream()
                .map(segment -> segment.getAttributes().get(BUCKET_NAME))
                .collect(Collectors.toSet());

            assertTrue(
                "Segments should include tenant1 bucket",
                bucketNames.contains("tenant1")
            );
            assertTrue(
                "Segments should include tenant2 bucket",
                bucketNames.contains("tenant2")
            );
        }
    }

    /**
     * Test: Within the same tenant/context, indexing a document with the same _id
     * should still behave as an update (version bump), not create a duplicate.
     */
    @LockFeatureFlag(CONTEXT_AWARE_MIGRATION_EXPERIMENTAL_FLAG)
    public void testSameIdSameContextIsUpdate() throws Exception {
        final IndexSettings indexSettings = IndexSettingsModule.newIndexSettings(
            "test",
            Settings.builder()
                .put(defaultSettings.getSettings())
                .put(IndexSettings.INDEX_CONTEXT_AWARE_ENABLED_SETTING.getKey(), true)
                .build()
        );

        try (
            Store store = createStore();
            InternalEngine engine = createEngine(
                indexSettings, store, createTempDir(), new CriteriaBasedMergePolicy(NoMergePolicy.INSTANCE),
                null, null, null, null, null
            )
        ) {
            // tenant1 writes _id="123" first time
            final ParsedDocument doc1 = testParsedDocument(
                "123",
                null,
                testContextSpecificDocument("tenant1"),
                new BytesArray("{ \"version\": 1 }"),
                null
            );
            engine.index(indexForDoc(doc1));

            // tenant1 writes _id="123" second time (same context = update)
            final ParsedDocument doc2 = testParsedDocument(
                "123",
                null,
                testContextSpecificDocument("tenant1"),
                new BytesArray("{ \"version\": 2 }"),
                null
            );
            engine.index(indexForDoc(doc2));

            engine.refresh("test");

            // Should have only 1 live document (the update replaces the old one)
            try (Engine.Searcher searcher = engine.acquireSearcher("test")) {
                assertEquals(
                    "Same _id within same context should result in update, not duplicate",
                    1,
                    searcher.getIndexReader().numDocs()
                );

                TopDocs topDocs = searcher.search(
                    new TermQuery(new Term("_id", Uid.encodeId("123"))),
                    10
                );
                assertEquals(1, topDocs.totalHits.value());
            }
        }
    }

    /**
     * Test: Multiple tenants, multiple docs with overlapping _ids.
     * Demonstrates full isolation — each context maintains its own _id namespace.
     */
    @LockFeatureFlag(CONTEXT_AWARE_MIGRATION_EXPERIMENTAL_FLAG)
    public void testMultipleTenantsWithOverlappingIds() throws Exception {
        final IndexSettings indexSettings = IndexSettingsModule.newIndexSettings(
            "test",
            Settings.builder()
                .put(defaultSettings.getSettings())
                .put(IndexSettings.INDEX_CONTEXT_AWARE_ENABLED_SETTING.getKey(), true)
                .build()
        );

        try (
            Store store = createStore();
            InternalEngine engine = createEngine(
                indexSettings, store, createTempDir(), new CriteriaBasedMergePolicy(NoMergePolicy.INSTANCE),
                null, null, null, null, null
            )
        ) {
            String[] tenants = { "tenant1", "tenant2" };
            String docId = "123";

            // Each tenant indexes a doc with _id="123"
            for (int t = 0; t < tenants.length; t++) {
                final ParsedDocument doc = testParsedDocument(
                    docId,
                    null,
                    testContextSpecificDocument(tenants[t]),
                    new BytesArray("{ \"tenant\": \"" + tenants[t] + "\", \"value\": " + t + " }"),
                    null
                );
                Engine.Index indexOp = new Engine.Index(
                    newUid(doc),
                    doc,
                    UNASSIGNED_SEQ_NO,
                    primaryTerm.get(),
                    (long) (t + 1),
                    VersionType.EXTERNAL,
                    Engine.Operation.Origin.PRIMARY,
                    System.nanoTime(),
                    -1,
                    false,
                    UNASSIGNED_SEQ_NO,
                    0
                );
                Engine.IndexResult result = engine.index(indexOp);
                assertNull("Indexing doc for " + tenants[t] + " should not fail", result.getFailure());
            }

            engine.refresh("test");

            // Verify: 2 total docs in the index
            try (Engine.Searcher searcher = engine.acquireSearcher("test")) {
                assertEquals(2, searcher.getIndexReader().numDocs());

                // Both should be retrievable by _id="123"
                TopDocs topDocs = searcher.search(
                    new TermQuery(new Term("_id", Uid.encodeId("123"))),
                    10
                );
                assertEquals(
                    "Both tenant1 and tenant2 docs with _id='123' should be found",
                    2,
                    topDocs.totalHits.value()
                );
            }

            // Verify segment-level isolation
            List<Segment> segments = engine.segments(true);
            Set<String> buckets = segments.stream()
                .map(seg -> seg.getAttributes().get(BUCKET_NAME))
                .collect(Collectors.toSet());
            assertTrue("Should have separate segments for tenant1 and tenant2",
                buckets.containsAll(Set.of("tenant1", "tenant2")));
        }
    }
}
