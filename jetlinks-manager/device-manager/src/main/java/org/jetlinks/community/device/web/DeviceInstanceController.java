package org.jetlinks.community.device.web;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import io.swagger.annotations.ApiOperation;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.ezorm.rdb.exception.DuplicateKeyException;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.community.device.entity.*;
import org.jetlinks.community.device.entity.excel.DeviceInstanceImportExportEntity;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.jetlinks.community.device.web.excel.DeviceExcelInfo;
import org.jetlinks.community.device.web.excel.DeviceWrapper;
import org.jetlinks.community.io.excel.ImportExportService;
import org.jetlinks.community.io.utils.FileUtils;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceProductOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.community.device.response.*;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.timeseries.TimeSeriesManager;
import org.jetlinks.community.timeseries.TimeSeriesMetric;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/device-instance", "/device/instance"})
@Authorize
@Resource(id = "device-instance", name = "????????????")
@Slf4j
public class DeviceInstanceController implements
    ReactiveServiceCrudController<DeviceInstanceEntity, String> {

    @Getter
    private final LocalDeviceInstanceService service;

    private final TimeSeriesManager timeSeriesManager;

    private final DeviceRegistry registry;

    private final LocalDeviceProductService productService;

    private final ImportExportService importExportService;

    private final ReactiveRepository<DeviceTagEntity, String> tagRepository;

    @SuppressWarnings("all")
    public DeviceInstanceController(LocalDeviceInstanceService service,
                                    TimeSeriesManager timeSeriesManager,
                                    DeviceRegistry registry,
                                    LocalDeviceProductService productService,
                                    ImportExportService importExportService,
                                    ReactiveRepository<DeviceTagEntity, String> tagRepository) {
        this.service = service;
        this.timeSeriesManager = timeSeriesManager;
        this.registry = registry;
        this.productService = productService;
        this.importExportService = importExportService;
        this.tagRepository = tagRepository;
    }


    //??????????????????
    @GetMapping("/{id:.+}/detail")
    @QueryAction
    public Mono<DeviceDetail> getDeviceDetailInfo(@PathVariable String id) {
        return service.getDeviceDetail(id);
    }

    //????????????????????????
    @GetMapping("/{id:.+}/state")
    @QueryAction
    public Mono<DeviceState> getDeviceState(@PathVariable String id) {
        return service.getDeviceState(id);
    }

    //????????? ?????????????????????
    @GetMapping("/info/{id:.+}")
    @QueryAction
    @Deprecated
    public Mono<DeviceInfo> getDeviceInfoById(@PathVariable String id) {
        return service.getDeviceInfoById(id);
    }

    //????????? ?????????????????????
    @GetMapping("/run-info/{id:.+}")
    @QueryAction
    @Deprecated
    public Mono<DeviceRunInfo> getRunDeviceInfoById(@PathVariable String id) {
        return service.getDeviceRunInfo(id);
    }


    @PostMapping({
        "/deploy/{deviceId:.+}",//todo ????????? ?????????????????????
        "/{deviceId:.+}/deploy"
    })
    @SaveAction
    public Mono<DeviceDeployResult> deviceDeploy(@PathVariable String deviceId) {
        return service.deploy(deviceId);
    }

    @PostMapping({
        "/cancelDeploy/{deviceId:.+}", //todo ????????? ?????????????????????
        "/{deviceId:.+}/undeploy"
    })
    @SaveAction
    public Mono<Integer> cancelDeploy(@PathVariable String deviceId) {
        return service.cancelDeploy(deviceId);
    }

    //????????????
    @PostMapping("/{deviceId:.+}/disconnect")
    @SaveAction
    public Mono<Boolean> disconnect(@PathVariable String deviceId) {
        return registry
            .getDevice(deviceId)
            .flatMapMany(DeviceOperator::disconnect)
            .singleOrEmpty();
    }

    //????????????
    @PostMapping
    public Mono<DeviceInstanceEntity> add(@RequestBody Mono<DeviceInstanceEntity> payload) {
        return payload.flatMap(entity -> service.insert(Mono.just(entity))
            .onErrorMap(DuplicateKeyException.class, err -> new BusinessException("??????ID?????????", err))
            .thenReturn(entity));
    }

    //????????????,????????????
    @GetMapping(value = "/deploy", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaveAction
    public Flux<DeviceDeployResult> deployAll(QueryParamEntity query) {
        query.setPaging(false);
        return service.query(query).as(service::deploy);
    }

    /**
     * ????????????????????????
     *
     * @param query ????????????
     * @return ??????????????????
     */
    @GetMapping(value = "/state/_sync", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaveAction
    public Flux<Integer> syncDeviceState(QueryParamEntity query) {
        query.setPaging(false);
        return service
            .query(query.includes("id"))
            .map(DeviceInstanceEntity::getId)
            .buffer(200)
            .publishOn(Schedulers.single())
            .concatMap(flux -> service.syncStateBatch(Flux.just(flux), true))
            .defaultIfEmpty(0);
    }

    //?????????
    @GetMapping("/{productId:.+}/{deviceId:.+}/properties")
    @Deprecated
    @QueryAction
    public Flux<DevicePropertiesEntity> getDeviceLatestProperties(@PathVariable String productId,
                                                                  @PathVariable String deviceId) {
        return service.getDeviceLatestProperties(deviceId);
    }

    //???????????????????????????
    @GetMapping("/{deviceId:.+}/properties/latest")
    @QueryAction
    public Flux<DevicePropertiesEntity> getDeviceLatestProperties(@PathVariable String deviceId) {
        return service.getDeviceLatestProperties(deviceId);
    }

    //?????????????????????????????????
    @GetMapping("/{deviceId:.+}/property/{property:.+}")
    @QueryAction
    public Mono<DevicePropertiesEntity> getDeviceLatestProperty(@PathVariable String deviceId, @PathVariable String property) {
        return service.getDeviceLatestProperty(deviceId, property);
    }

    //????????????????????????
    @GetMapping("/{deviceId:.+}/event/{eventId}")
    @QueryAction
    public Mono<PagerResult<Map<String, Object>>> queryPagerByDeviceEvent(QueryParamEntity queryParam,
                                                                          @RequestParam(defaultValue = "false") boolean format,
                                                                          @PathVariable String deviceId,
                                                                          @PathVariable String eventId) {
        return service.queryDeviceEvent(deviceId, eventId, queryParam, format);
    }

    @GetMapping("/{deviceId:.+}/properties/_query")
    @QueryAction
    public Mono<PagerResult<DevicePropertiesEntity>> queryDeviceProperties(@PathVariable String deviceId, QueryParamEntity entity) {
        return service.queryDeviceProperties(deviceId, entity);
    }

    @GetMapping("/{deviceId:.+}/logs")
    @QueryAction
    public Mono<PagerResult<DeviceOperationLogEntity>> queryDeviceLog(@PathVariable String deviceId,
                                                                      QueryParamEntity entity) {
        return service.queryDeviceLog(deviceId, entity);
    }

    //????????????
    @DeleteMapping("/{deviceId}/tag/{tagId:.+}")
    @SaveAction
    public Mono<Void> deleteDeviceTag(@PathVariable String deviceId,
                                      @PathVariable String tagId) {
        return tagRepository.createDelete()
            .where(DeviceTagEntity::getDeviceId, deviceId)
            .and(DeviceTagEntity::getId, tagId)
            .execute()
            .then();
    }

    /**
     * ????????????????????????
     * <pre>
     *     GET /device/instance/{deviceId}/tags
     *
     *     [
     *      {
     *          "id":"id",
     *          "key":"",
     *          "value":"",
     *          "name":""
     *      }
     *     ]
     * </pre>
     *
     * @param deviceId ??????ID
     * @return ??????????????????
     */
    @GetMapping("/{deviceId}/tags")
    @SaveAction
    public Flux<DeviceTagEntity> getDeviceTags(@PathVariable String deviceId) {
        return tagRepository.createQuery()
            .where(DeviceTagEntity::getDeviceId, deviceId)
            .fetch();
    }

    //??????????????????
    @PatchMapping("/{deviceId}/tag")
    @SaveAction
    public Flux<DeviceTagEntity> saveDeviceTag(@PathVariable String deviceId,
                                               @RequestBody Flux<DeviceTagEntity> tags) {
        return tags
            .doOnNext(tag -> {
                tag.setId(DeviceTagEntity.createTagId(deviceId, tag.getKey()));
                tag.setDeviceId(deviceId);
                tag.tryValidate();
            })
            .as(tagRepository::save)
            .thenMany(getDeviceTags(deviceId));
    }


    @GetMapping(value = "/import", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiOperation("??????????????????")
    @SaveAction
    public Flux<ImportDeviceInstanceResult> doBatchImport(@RequestParam String fileUrl) {

        return Authentication
            .currentReactive()
            .flatMapMany(auth -> productService
                .createQuery()
                .fetch()
                .collectList()
                .flatMapMany(productEntities -> {
                    Map<String, String> productNameMap = productEntities.stream()
                        .collect(Collectors.toMap(DeviceProductEntity::getName, DeviceProductEntity::getId, (_1, _2) -> _1));
                    return importExportService
                        .doImport(DeviceInstanceImportExportEntity.class, fileUrl)
                        .map(result -> {
                            try {
                                DeviceInstanceImportExportEntity importExportEntity = result.getResult();
                                DeviceInstanceEntity entity = FastBeanCopier.copy(importExportEntity, new DeviceInstanceEntity());
                                String productId = productNameMap.get(importExportEntity.getProductName());
                                if (StringUtils.isEmpty(productId)) {
                                    throw new BusinessException("?????????????????????");
                                }
                                if (StringUtils.isEmpty(entity.getId())) {
                                    throw new BusinessException("??????ID????????????");
                                }

                                entity.setProductId(productId);
                                entity.setState(DeviceState.notActive);
                                return entity;
                            } catch (Throwable e) {
                                throw new BusinessException("???" +
                                    (result.getRowIndex() + 2)
                                    + "???:" + e.getMessage());
                            }
                        });
                })
                .buffer(20)
                .publishOn(Schedulers.single())
                .concatMap(list -> service.save(Flux.fromIterable(list)))
                .map(ImportDeviceInstanceResult::success))
            .onErrorResume(err -> Mono.just(ImportDeviceInstanceResult.error(err)))
            ;
    }

    DataBufferFactory bufferFactory = new DefaultDataBufferFactory();


    //?????????????????????
    @GetMapping(value = "/{productId}/import", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaveAction
    public Flux<ImportDeviceInstanceResult> doBatchImportByProduct(@PathVariable String productId,
                                                                   @RequestParam String fileUrl) {
        return registry.getProduct(productId)
            .flatMap(DeviceProductOperator::getMetadata)
            .map(metadata -> new DeviceWrapper(metadata.getTags()))
            .defaultIfEmpty(DeviceWrapper.empty)
            .flatMapMany(wrapper -> importExportService
                .getInputStream(fileUrl)
                .flatMapMany(inputStream -> ReactorExcel.read(inputStream, FileUtils.getExtension(fileUrl), wrapper)))
            .map(info -> {
                DeviceInstanceEntity entity = FastBeanCopier.copy(info, new DeviceInstanceEntity());
                entity.setProductId(productId);
                if (StringUtils.isEmpty(entity.getId())) {
                    throw new BusinessException("???" + info.getRowNumber() + 1 + "???:??????ID????????????");
                }
                return Tuples.of(entity, info.getTags());
            })
            .buffer(100)//???100?????????????????????
            .publishOn(Schedulers.single())
            .concatMap(buffer ->
                Mono.zip(
                    service.save(Flux.fromIterable(buffer).map(Tuple2::getT1)),
                    tagRepository
                        .save(Flux.fromIterable(buffer).flatMapIterable(Tuple2::getT2))
                        .defaultIfEmpty(SaveResult.of(0, 0))
                ))
            .map(res -> ImportDeviceInstanceResult.success(res.getT1()))
            .onErrorResume(err -> Mono.just(ImportDeviceInstanceResult.error(err)));
    }

    //??????????????????
    @GetMapping("/{productId}/template.{format}")
    @QueryAction
    public Mono<Void> downloadExportTemplate(ServerHttpResponse response,
                                             QueryParamEntity parameter,
                                             @PathVariable String format,
                                             @PathVariable String productId) throws IOException {
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=".concat(URLEncoder.encode("??????????????????." + format, StandardCharsets.UTF_8.displayName())));
        parameter.setPaging(false);
        parameter.toNestQuery(q -> q.is(DeviceInstanceEntity::getProductId, productId));
        return registry.getProduct(productId)
            .flatMap(DeviceProductOperator::getMetadata)
            .map(meta -> DeviceExcelInfo.getTemplateHeaderMapping(meta.getTags()))
            .defaultIfEmpty(DeviceExcelInfo.getTemplateHeaderMapping(Collections.emptyList()))
            .flatMapMany(headers ->
                ReactorExcel.<DeviceExcelInfo>writer(format)
                    .headers(headers)
                    .converter(DeviceExcelInfo::toMap)
                    .writeBuffer(Flux.empty()))
            .doOnError(err -> log.error(err.getMessage(), err))
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    //????????????????????????.
    @GetMapping("/{productId}/export.{format}")
    @QueryAction
    public Mono<Void> export(ServerHttpResponse response,
                             QueryParamEntity parameter,
                             @PathVariable String format,
                             @PathVariable String productId) throws IOException {
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=".concat(URLEncoder.encode("????????????." + format, StandardCharsets.UTF_8.displayName())));
        parameter.setPaging(false);
        parameter.toNestQuery(q -> q.is(DeviceInstanceEntity::getProductId, productId));
        return registry.getProduct(productId)
            .flatMap(DeviceProductOperator::getMetadata)
            .map(meta -> DeviceExcelInfo.getExportHeaderMapping(meta.getTags()))
            .defaultIfEmpty(DeviceExcelInfo.getExportHeaderMapping(Collections.emptyList()))
            .flatMapMany(headers ->
                ReactorExcel.<DeviceExcelInfo>writer(format)
                    .headers(headers)
                    .converter(DeviceExcelInfo::toMap)
                    .writeBuffer(
                        service.query(parameter)
                            .map(entity -> FastBeanCopier.copy(entity, new DeviceExcelInfo()))
                            .buffer(200)
                            .flatMap(list -> {
                                Map<String, DeviceExcelInfo> importInfo = list
                                    .stream()
                                    .collect(Collectors.toMap(DeviceExcelInfo::getId, Function.identity()));
                                return tagRepository.createQuery()
                                    .where()
                                    .in(DeviceTagEntity::getDeviceId, importInfo.keySet())
                                    .fetch()
                                    .collect(Collectors.groupingBy(DeviceTagEntity::getDeviceId))
                                    .flatMapIterable(Map::entrySet)
                                    .doOnNext(entry -> importInfo.get(entry.getKey()).setTags(entry.getValue()))
                                    .thenMany(Flux.fromIterable(list));
                            })
                        , 512 * 1024))//??????512k
            .doOnError(err -> log.error(err.getMessage(), err))
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }


    //??????????????????,?????????????????????.
    @GetMapping("/export.{format}")
    @QueryAction
    public Mono<Void> export(ServerHttpResponse response,
                             QueryParamEntity parameter,
                             @PathVariable String format) throws IOException {
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=".concat(URLEncoder.encode("????????????." + format, StandardCharsets.UTF_8.displayName())));
        return ReactorExcel.<DeviceExcelInfo>writer(format)
            .headers(DeviceExcelInfo.getExportHeaderMapping(Collections.emptyList()))
            .converter(DeviceExcelInfo::toMap)
            .writeBuffer(
                service.query(parameter)
                    .map(entity -> FastBeanCopier.copy(entity, new DeviceExcelInfo()))
                , 512 * 1024)//??????512k
            .doOnError(err -> log.error(err.getMessage(), err))
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }

    @PostMapping("/export")
    @QueryAction
    @SneakyThrows
    public Mono<Void> export(ServerHttpResponse response, QueryParamEntity parameter) {
        response.getHeaders().set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=".concat(URLEncoder.encode("????????????.xlsx", StandardCharsets.UTF_8.displayName())));
        parameter.setPaging(false);

        return StreamUtils.buffer(
            512 * 1024,
            output -> {
                ExcelWriter excelWriter = EasyExcel.write(output, DeviceInstanceImportExportEntity.class).build();
                WriteSheet writeSheet = EasyExcel.writerSheet().build();
                return service.query(parameter)
                    .map(entity -> FastBeanCopier.copy(entity, new DeviceInstanceImportExportEntity()))
                    .buffer(100)
                    .doOnNext(list -> excelWriter.write(list, writeSheet))
                    .doOnComplete(excelWriter::finish)
                    .then();
            })
            .map(bufferFactory::wrap)
            .as(response::writeWith);
    }
}
