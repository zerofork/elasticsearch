/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.datastreams.mapper;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.MapperTestUtils;
import org.elasticsearch.index.mapper.DataStreamTimestampFieldMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.generateMapping;
import static org.elasticsearch.cluster.metadata.MetadataCreateDataStreamService.validateTimestampFieldMapping;
import static org.hamcrest.Matchers.equalTo;

public class MetadataCreateDataStreamServiceTests extends ESTestCase {

    public void testValidateTimestampFieldMapping() throws Exception {
        String mapping = generateMapping("@timestamp", "date");
        validateTimestampFieldMapping(createMappingLookup(mapping));
        mapping = generateMapping("@timestamp", "date_nanos");
        validateTimestampFieldMapping(createMappingLookup(mapping));
    }

    public void testValidateTimestampFieldMappingNoFieldMapping() {
        Exception e = expectThrows(IllegalStateException.class, () -> validateTimestampFieldMapping(createMappingLookup("{}")));
        assertThat(e.getMessage(), equalTo("[" + DataStreamTimestampFieldMapper.NAME + "] meta field has been disabled"));
        String mapping1 = "{\n"
            + "      \""
            + DataStreamTimestampFieldMapper.NAME
            + "\": {\n"
            + "        \"enabled\": false\n"
            + "      },"
            + "      \"properties\": {\n"
            + "        \"@timestamp\": {\n"
            + "          \"type\": \"date\"\n"
            + "        }\n"
            + "      }\n"
            + "    }";
        e = expectThrows(IllegalStateException.class, () -> validateTimestampFieldMapping(createMappingLookup(mapping1)));
        assertThat(e.getMessage(), equalTo("[" + DataStreamTimestampFieldMapper.NAME + "] meta field has been disabled"));

        String mapping2 = generateMapping("@timestamp2", "date");
        e = expectThrows(IllegalArgumentException.class, () -> validateTimestampFieldMapping(createMappingLookup(mapping2)));
        assertThat(e.getMessage(), equalTo("data stream timestamp field [@timestamp] does not exist"));
    }

    public void testValidateTimestampFieldMappingInvalidFieldType() {
        String mapping = generateMapping("@timestamp", "keyword");
        Exception e = expectThrows(IllegalArgumentException.class, () -> validateTimestampFieldMapping(createMappingLookup(mapping)));
        assertThat(
            e.getMessage(),
            equalTo("data stream timestamp field [@timestamp] is of type [keyword], " + "but [date,date_nanos] is expected")
        );
    }

    MappingLookup createMappingLookup(String mapping) throws IOException {
        String indexName = "test";
        IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            )
            .putMapping(mapping)
            .build();
        IndicesModule indicesModule = new IndicesModule(List.of());
        MapperService mapperService = MapperTestUtils.newMapperService(
            xContentRegistry(),
            createTempDir(),
            Settings.EMPTY,
            indicesModule,
            indexName
        );
        mapperService.merge(indexMetadata, MapperService.MergeReason.MAPPING_UPDATE);
        return mapperService.mappingLookup();
    }
}
