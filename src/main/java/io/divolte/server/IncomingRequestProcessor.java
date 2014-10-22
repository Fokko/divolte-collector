package io.divolte.server;

import static io.divolte.server.BaseEventHandler.*;
import static io.divolte.server.processing.ItemProcessor.ProcessingDirective.*;
import io.divolte.server.hdfs.HdfsFlusher;
import io.divolte.server.hdfs.HdfsFlushingPool;
import io.divolte.server.ip2geo.LookupService;
import io.divolte.server.kafka.KafkaFlusher;
import io.divolte.server.kafka.KafkaFlushingPool;
import io.divolte.server.processing.ItemProcessor;
import io.divolte.server.processing.ProcessingPool;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@ParametersAreNonnullByDefault
final class IncomingRequestProcessor implements ItemProcessor<HttpServerExchange> {
    private final static Logger logger = LoggerFactory.getLogger(IncomingRequestProcessor.class);

    public final static AttachmentKey<Boolean> DUPLICATE_EVENT_KEY = AttachmentKey.create(Boolean.class);

    @Nullable
    private final ProcessingPool<KafkaFlusher, AvroRecordBuffer> kafkaFlushingPool;
    @Nullable
    private final ProcessingPool<HdfsFlusher, AvroRecordBuffer> hdfsFlushingPool;

    private final IncomingRequestListener listener;

    private final RecordMapper mapper;

    private final ShortTermDuplicateMemory memory;
    private final boolean keepDuplicates;

    public IncomingRequestProcessor(final Config config,
                                    @Nullable final KafkaFlushingPool kafkaFlushingPool,
                                    @Nullable final HdfsFlushingPool hdfsFlushingPool,
                                    @Nullable final LookupService geoipLookupService,
                                    final Schema schema,
                                    final IncomingRequestListener listener) {

        this.kafkaFlushingPool = kafkaFlushingPool;
        this.hdfsFlushingPool = hdfsFlushingPool;
        this.listener = listener;

        memory = new ShortTermDuplicateMemory(config.getInt("divolte.incoming_request_processor.duplicate_memory_size"));
        keepDuplicates = !config.getBoolean("divolte.incoming_request_processor.discard_duplicates");

        final Config schemaMappingConfig = schemaMappingConfigFromConfig(Objects.requireNonNull(config));
        mapper = new RecordMapper(Objects.requireNonNull(schema),
                                  schemaMappingConfig, config,
                                  Optional.ofNullable(geoipLookupService));
    }

    private Config schemaMappingConfigFromConfig(final Config config) {
        final Config schemaMappingConfig;
        if (config.hasPath("divolte.tracking.schema_mapping")) {
            logger.info("Using schema mapping from configuration.");
            schemaMappingConfig = config;
        } else {
            logger.info("Using built in default schema mapping.");
            schemaMappingConfig = ConfigFactory.load("default-schema-mapping");
        }
        return schemaMappingConfig;
    }

    @Override
    public ProcessingDirective process(final HttpServerExchange exchange) {
        final GenericRecord avroRecord = mapper.newRecordFromExchange(exchange);
        final AvroRecordBuffer avroBuffer = AvroRecordBuffer.fromRecord(
                exchange.getAttachment(PARTY_COOKIE_KEY),
                exchange.getAttachment(SESSION_COOKIE_KEY),
                exchange.getAttachment(REQUEST_START_TIME_KEY),
                exchange.getAttachment(COOKIE_UTC_OFFSET_KEY),
                avroRecord);

        /*
         * Note: we cannot use the actual query string here,
         * as the incoming request processor is agnostic of
         * that sort of thing. The request may have come from
         * an endpoint that doesn't require a query string,
         * but rather generates these IDs on the server side.
         */
        final int requestHashCode = Objects.hash(
                exchange.getAttachment(PARTY_COOKIE_KEY),
                exchange.getAttachment(SESSION_COOKIE_KEY),
                exchange.getAttachment(PAGE_VIEW_ID_KEY),
                exchange.getAttachment(EVENT_ID_KEY)
                );
        final boolean duplicate = memory.observeAndReturnDuplicity(requestHashCode);

        if (duplicate) {
            if (logger.isDebugEnabled()) {
                final String queryString = exchange.getQueryString();
                final String requestUrl = exchange.getRequestURL();
                final String fullUrl = Strings.isNullOrEmpty(queryString)
                        ? requestUrl
                                : requestUrl + '?' + queryString;
                logger.debug("Received duplicate event from {}: {}", exchange.getSourceAddress(), fullUrl);
            }
            if (keepDuplicates) {
                exchange.putAttachment(DUPLICATE_EVENT_KEY, true);
                doProcess(exchange, avroRecord, avroBuffer);
            }
        } else {
            exchange.putAttachment(DUPLICATE_EVENT_KEY, false);
            doProcess(exchange, avroRecord, avroBuffer);
        }

        return CONTINUE;
    }

    private void doProcess(final HttpServerExchange exchange, final GenericRecord avroRecord, final AvroRecordBuffer avroBuffer) {
        listener.incomingRequest(exchange, avroBuffer, avroRecord);

        if (null != kafkaFlushingPool) {
            kafkaFlushingPool.enqueue(avroBuffer.getPartyId().value, avroBuffer);
        }
        if (null != hdfsFlushingPool) {
            hdfsFlushingPool.enqueue(avroBuffer.getPartyId().value, avroBuffer);
        }
    }
}
