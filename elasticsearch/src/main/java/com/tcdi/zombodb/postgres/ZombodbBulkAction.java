package com.tcdi.zombodb.postgres;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.replication.ReplicationType;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestBuilderListener;

import static org.elasticsearch.index.query.QueryBuilders.constantScoreQuery;
import static org.elasticsearch.index.query.QueryBuilders.idsQuery;
import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestStatus.OK;

public class ZombodbBulkAction extends BaseRestHandler {

    @Inject
    public ZombodbBulkAction(Settings settings, RestController controller, Client client) {
        super(settings, controller, client);

        controller.registerHandler(POST, "/{index}/{type}/_zdbbulk", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) throws Exception {
        BulkRequest bulkRequest = Requests.bulkRequest();
        bulkRequest.listenerThreaded(false);
        String defaultIndex = request.param("index");
        String defaultType = request.param("type");
        String defaultRouting = request.param("routing");

        String replicationType = request.param("replication");
        if (replicationType != null) {
            bulkRequest.replicationType(ReplicationType.fromString(replicationType));
        }
        String consistencyLevel = request.param("consistency");
        if (consistencyLevel != null) {
            bulkRequest.consistencyLevel(WriteConsistencyLevel.fromString(consistencyLevel));
        }
        bulkRequest.timeout(request.paramAsTime("timeout", BulkShardRequest.DEFAULT_TIMEOUT));
        bulkRequest.refresh(request.paramAsBoolean("refresh", bulkRequest.refresh()));
        bulkRequest.add(request.content(), defaultIndex, defaultType, defaultRouting, null, true);

        for (ActionRequest ar : bulkRequest.requests()) {
            if (ar instanceof IndexRequest) {
                IndexRequest doc = (IndexRequest) ar;
                String id = doc.id();
                String routing = doc.routing();

                assert routing != null;

                if (id.equals(routing))
                    // document is the root document, nothing to do
                    continue;

                SearchResponse result = client.search(
                        new SearchRequestBuilder(client)
                                .setIndices(defaultIndex)
                                .setTypes(defaultType)
                                .setSize(1)
                                .setFetchSource(false)
                                .addField("_routing")
                                .setQuery(constantScoreQuery(idsQuery(defaultType).addIds(routing)))
                                .setTerminateAfter(1)
                                .request()
                ).actionGet();

                doc.routing((String) result.getHits().getAt(0).field("_routing").getValue());
            }
        }

        client.bulk(bulkRequest, new RestBuilderListener<BulkResponse>(channel) {
            @Override
            public RestResponse buildResponse(BulkResponse response, XContentBuilder builder) throws Exception {
                builder.startObject();
                builder.field(Fields.TOOK, response.getTookInMillis());
                builder.field(Fields.ERRORS, response.hasFailures());
                builder.startArray(Fields.ITEMS);
                for (BulkItemResponse itemResponse : response) {
                    builder.startObject();
                    builder.startObject(itemResponse.getOpType());
                    builder.field(Fields._INDEX, itemResponse.getIndex());
                    builder.field(Fields._TYPE, itemResponse.getType());
                    builder.field(Fields._ID, itemResponse.getId());
                    long version = itemResponse.getVersion();
                    if (version != -1) {
                        builder.field(Fields._VERSION, itemResponse.getVersion());
                    }
                    if (itemResponse.isFailed()) {
                        builder.field(Fields.STATUS, itemResponse.getFailure().getStatus().getStatus());
                        builder.field(Fields.ERROR, itemResponse.getFailure().getMessage());
                    } else {
                        if (itemResponse.getResponse() instanceof DeleteResponse) {
                            DeleteResponse deleteResponse = itemResponse.getResponse();
                            if (deleteResponse.isFound()) {
                                builder.field(Fields.STATUS, RestStatus.OK.getStatus());
                            } else {
                                builder.field(Fields.STATUS, RestStatus.NOT_FOUND.getStatus());
                            }
                            builder.field(Fields.FOUND, deleteResponse.isFound());
                        } else if (itemResponse.getResponse() instanceof IndexResponse) {
                            IndexResponse indexResponse = itemResponse.getResponse();
                            if (indexResponse.isCreated()) {
                                builder.field(Fields.STATUS, RestStatus.CREATED.getStatus());
                            } else {
                                builder.field(Fields.STATUS, RestStatus.OK.getStatus());
                            }
                        } else if (itemResponse.getResponse() instanceof UpdateResponse) {
                            UpdateResponse updateResponse = itemResponse.getResponse();
                            if (updateResponse.isCreated()) {
                                builder.field(Fields.STATUS, RestStatus.CREATED.getStatus());
                            } else {
                                builder.field(Fields.STATUS, RestStatus.OK.getStatus());
                            }
                        }
                    }
                    builder.endObject();
                    builder.endObject();
                }
                builder.endArray();

                builder.endObject();
                return new BytesRestResponse(OK, builder);
            }
        });
    }

    private static final class Fields {
        static final XContentBuilderString ITEMS = new XContentBuilderString("items");
        static final XContentBuilderString ERRORS = new XContentBuilderString("errors");
        static final XContentBuilderString _INDEX = new XContentBuilderString("_index");
        static final XContentBuilderString _TYPE = new XContentBuilderString("_type");
        static final XContentBuilderString _ID = new XContentBuilderString("_id");
        static final XContentBuilderString STATUS = new XContentBuilderString("status");
        static final XContentBuilderString ERROR = new XContentBuilderString("error");
        static final XContentBuilderString TOOK = new XContentBuilderString("took");
        static final XContentBuilderString _VERSION = new XContentBuilderString("_version");
        static final XContentBuilderString FOUND = new XContentBuilderString("found");
    }

}