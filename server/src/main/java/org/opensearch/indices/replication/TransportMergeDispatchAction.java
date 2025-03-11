/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.indices.IndicesService;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

public class TransportMergeDispatchAction extends HandledTransportAction<PublishMergedCheckpointRequest, PublishMergedCheckpointResponse> {
    private final MergeDispatchHandler dispatchHandler;

    @Inject
    public TransportMergeDispatchAction(
        ActionFilters actionFilters,
        ClusterService clusterService,
        IndicesService indicesService,
        TransportService transportService
    ) {
        super(MergeDispatchAction.NAME, transportService, actionFilters, PublishMergedCheckpointRequest::new, ThreadPool.Names.SAME);
        this.dispatchHandler = new MergeDispatchHandler(clusterService, indicesService, transportService);
    }

    @Override
    protected void doExecute(Task task, PublishMergedCheckpointRequest request, ActionListener<PublishMergedCheckpointResponse> listener) {
        this.dispatchHandler.messageReceived(request, listener);
    }
}
