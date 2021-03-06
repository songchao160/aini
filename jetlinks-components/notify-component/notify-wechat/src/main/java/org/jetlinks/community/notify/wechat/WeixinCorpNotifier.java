package org.jetlinks.community.notify.wechat;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.Values;
import org.jetlinks.community.notify.AbstractNotifier;
import org.jetlinks.community.notify.DefaultNotifyType;
import org.jetlinks.community.notify.NotifyType;
import org.jetlinks.community.notify.Provider;
import org.jetlinks.community.notify.template.TemplateManager;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class WeixinCorpNotifier extends AbstractNotifier<WechatMessageTemplate> {

    private AtomicReference<String> accessToken = new AtomicReference<>();

    private long refreshTokenTime;

    private long tokenTimeOut = Duration.ofSeconds(7000).toMillis();

    private WebClient client;

    private static final String tokenApi = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";

    private static final String notify = "https://qyapi.weixin.qq.com/cgi-bin/message/send";

    private WechatCorpProperties properties;

    public WeixinCorpNotifier(WebClient client, WechatCorpProperties properties, TemplateManager templateManager) {
        super(templateManager);
        this.client = client;
        this.properties = properties;
    }

    @Nonnull
    @Override
    public NotifyType getType() {
        return DefaultNotifyType.weixin;
    }

    @Nonnull
    @Override
    public Provider getProvider() {
        return WechatProvider.corpMessage;
    }

    @Nonnull
    @Override
    public Mono<Void> send(@Nonnull WechatMessageTemplate template, @Nonnull Values context) {
        return getToken()
                .flatMap(token ->
                        client.post()
                                .uri(UriComponentsBuilder.fromUriString(notify).queryParam("access_token",token).toUriString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(BodyInserters.fromValue(template.createJsonRequest(context)))
                                .exchange()
                                .flatMap(clientResponse -> clientResponse.bodyToMono(HashMap.class))
                                .as(this::checkResult))
                .then();
    }

    private Mono<HashMap> checkResult(Mono<HashMap> msg) {
        return msg.doOnNext(map -> {
            String code = String.valueOf(map.get("errcode"));
            if ("0".equals(code)) {
                log.info("??????????????????????????????");
            } else {
                log.warn("??????????????????????????????:{}", map);
                throw new BusinessException("??????????????????????????????:" + map.get("errmsg"), code);
            }
        });
    }

    private Mono<String> getToken() {
        if (System.currentTimeMillis() - refreshTokenTime > tokenTimeOut || accessToken.get() == null) {
            return requestToken();
        }
        return Mono.just(accessToken.get());
    }

    private Mono<String> requestToken() {
        return client
                .get()
                .uri(UriComponentsBuilder.fromUriString(tokenApi)
                        .queryParam("corpid", properties.getCorpId())
                        .queryParam("corpsecret", properties.getCorpSecret())
                        .build().toUri())
                .exchange()
                .flatMap(resp -> resp.bodyToMono(HashMap.class))
                .map(map -> {
                    if (map.containsKey("access_token")) {
                        return map.get("access_token");
                    }
                    throw new BusinessException("??????Token??????:" + map.get("errmsg"), String.valueOf(map.get("errcode")));
                })
                .cast(String.class)
                .doOnNext((r) -> {
                    refreshTokenTime = System.currentTimeMillis();
                    accessToken.set(r);
                });
    }

    @Nonnull
    @Override
    public Mono<Void> close() {
        accessToken.set(null);
        refreshTokenTime = 0;
        return Mono.empty();
    }
}
