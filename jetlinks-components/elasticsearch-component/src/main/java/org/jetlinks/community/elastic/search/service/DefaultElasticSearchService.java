package org.jetlinks.community.elastic.search.service;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.common.xcontent.XContentType;
import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.utils.time.DateFormatter;
import org.hswebframework.utils.time.DefaultDateFormatter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.core.utils.FluxUtils;
import org.jetlinks.community.elastic.search.ElasticRestClient;
import org.jetlinks.community.elastic.search.index.ElasticSearchIndexManager;
import org.jetlinks.community.elastic.search.utils.ElasticSearchConverter;
import org.jetlinks.community.elastic.search.utils.ReactorActionListener;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author zhouhao
 * @since 1.0
 **/
@Service
@Slf4j
public class DefaultElasticSearchService implements ElasticSearchService {

    private final ElasticRestClient restClient;

    private final ElasticSearchIndexManager indexManager;

    FluxSink<Buffer> sink;

    static {
        DateFormatter.supportFormatter.add(new DefaultDateFormatter(Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}.+"), "yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
    }

    public DefaultElasticSearchService(ElasticRestClient restClient,
                                       ElasticSearchIndexManager indexManager) {
        this.restClient = restClient;
        init();
        this.indexManager = indexManager;
    }


    public <T> Flux<T> query(String index, QueryParam queryParam, Function<Map<String, Object>, T> mapper) {
        return doSearch(createSearchRequest(queryParam, index))
            .flatMapIterable(response -> translate(mapper, response))
            .onErrorResume(err -> {
                log.error("query elastic error", err);
                return Mono.empty();
            });
    }

    @Override
    public <T> Mono<PagerResult<T>> queryPager(String index, QueryParam queryParam, Function<Map<String, Object>, T> mapper) {
        return doSearch(createSearchRequest(queryParam, index))
            .map(response -> translatePageResult(mapper, queryParam, response))
            .switchIfEmpty(Mono.just(PagerResult.empty()))
            .onErrorReturn(err -> {
                log.error("query elastic error", err);
                return true;
            }, PagerResult.empty());
    }

    @Override
    public Mono<Long> count(String index, QueryParam queryParam) {
        QueryParam param = queryParam.clone();
        param.setPaging(false);
        param.setSorts(Collections.emptyList());
        return doCount(createCountRequest(param, index))
            .map(CountResponse::getCount)
            .defaultIfEmpty(0L)
            .onErrorReturn(err -> {
                log.error("query elastic error", err);
                return true;
            }, 0L);
    }

    @Override
    public <T> Mono<Void> commit(String index, T payload) {
        return Mono.fromRunnable(() -> {
            sink.next(new Buffer(index, payload));
        });
    }

    @Override
    public <T> Mono<Void> commit(String index, Collection<T> payload) {
        return Mono.fromRunnable(() -> {
            for (T t : payload) {
                sink.next(new Buffer(index, t));
            }
        });
    }

    @Override
    public <T> Mono<Void> commit(String index, Publisher<T> data) {
        return Flux.from(data)
            .flatMap(d -> commit(index, d))
            .then();
    }

    @PreDestroy
    public void shutdown() {
        sink.complete();
    }

    //@PostConstruct
    public void init() {
        //????????????????????????????????????,?????????slf4j???????????????????????????.
        FluxUtils.bufferRate(
            Flux.<Buffer>create(sink -> this.sink = sink),
            1000,
            2000,
            Duration.ofSeconds(3))
            .onBackpressureBuffer(512,
                drop -> System.err.println("??????????????????????????????!"),
                BufferOverflowStrategy.DROP_OLDEST)
            .flatMap(this::doSave)
            .doOnNext((len) -> {
                if (log.isDebugEnabled() && len > 0) {
                    log.debug("??????ElasticSearch????????????,??????:{}", len);
                }
            })
            .onErrorContinue((err, obj) -> System.err.println("??????ElasticSearch????????????:\n" + org.hswebframework.utils.StringUtils.throwable2String(err)))
            .subscribe();
    }

    @AllArgsConstructor
    @Getter
    static class Buffer {
        String index;
        Object payload;
    }


    private Mono<String> getIndexForSave(String index) {
        return indexManager
            .getIndexStrategy(index)
            .map(strategy -> strategy.getIndexForSave(index));

    }

    private Mono<String> getIndexForSearch(String index) {
        return indexManager
            .getIndexStrategy(index)
            .map(strategy -> strategy.getIndexForSearch(index));

    }

    protected Mono<Integer> doSave(Collection<Buffer> buffers) {
        return Flux.fromIterable(buffers)
            .groupBy(Buffer::getIndex)
            .flatMap(group -> {
                String index = group.key();
                return this.getIndexForSave(index)
                    .zipWith(indexManager.getIndexMetadata(index))
                    .flatMapMany(tp2 ->
                        group.map(buffer -> {
                            IndexRequest request = new IndexRequest(tp2.getT1(), "_doc");
                            Object o = JSON.toJSON(buffer.getPayload());
                            if (o instanceof Map) {
                                request.source(tp2.getT2().convertToElastic((Map<String, Object>) o));
                            } else {
                                request.source(o.toString(), XContentType.JSON);
                            }
                            return request;
                        }));
            })
            .collectList()
            .filter(CollectionUtils::isNotEmpty)
            .flatMap(lst -> {
                BulkRequest request = new BulkRequest();
                lst.forEach(request::add);
                return ReactorActionListener.<BulkResponse>mono(listener ->
                    restClient.getWriteClient().bulkAsync(request, RequestOptions.DEFAULT, listener)
                );
            }).thenReturn(buffers.size());
    }

    private <T> PagerResult<T> translatePageResult(Function<Map<String, Object>, T> mapper, QueryParam param, SearchResponse response) {
        long total = response.getHits().getTotalHits();
        return PagerResult.of((int) total, translate(mapper, response), param);
    }

    private <T> List<T> translate(Function<Map<String, Object>, T> mapper, SearchResponse response) {
        return Arrays.stream(response.getHits().getHits())
            .map(hit -> {
                Map<String, Object> hitMap = hit.getSourceAsMap();
                hitMap.put("id", hit.getId());
                return mapper.apply(hitMap);
            })
            .collect(Collectors.toList());
    }

    private Mono<SearchResponse> doSearch(Mono<SearchRequest> requestMono) {
        return requestMono.flatMap((request) ->
            ReactorActionListener
                .<SearchResponse>mono(listener ->
                    restClient
                        .getQueryClient()
                        .searchAsync(request, RequestOptions.DEFAULT, listener)))
            .onErrorResume(err -> {
                log.error("query elastic error", err);
                return Mono.empty();
            });
    }

    private Mono<CountResponse> doCount(Mono<CountRequest> requestMono) {
        return requestMono.flatMap((request) ->
            ReactorActionListener
                .<CountResponse>mono(listener ->
                    restClient
                        .getQueryClient()
                        .countAsync(request, RequestOptions.DEFAULT, listener)))
            .onErrorResume(err -> {
                log.error("query elastic error", err);
                return Mono.empty();
            });
    }

    private Mono<SearchRequest> createSearchRequest(QueryParam queryParam, String index) {
        return indexManager
            .getIndexMetadata(index)
            .map(metadata -> ElasticSearchConverter.convertSearchSourceBuilder(queryParam, metadata))
            .switchIfEmpty(Mono.fromSupplier(() -> ElasticSearchConverter.convertSearchSourceBuilder(queryParam, null)))
            .flatMap(builder -> this.getIndexForSearch(index)
                .map(idx -> new SearchRequest(idx).source(builder).types("_doc")));
    }

    private Mono<CountRequest> createCountRequest(QueryParam queryParam, String index) {
        QueryParam tempQueryParam = queryParam.clone();
        tempQueryParam.setPaging(false);
        tempQueryParam.setSorts(Collections.emptyList());
        return indexManager
            .getIndexMetadata(index)
            .map(metadata -> ElasticSearchConverter.convertSearchSourceBuilder(queryParam, metadata))
            .switchIfEmpty(Mono.fromSupplier(() -> ElasticSearchConverter.convertSearchSourceBuilder(queryParam, null)))
            .flatMap(builder -> this.getIndexForSearch(index)
                .map(idx -> new CountRequest(idx).source(builder)));
    }
}
