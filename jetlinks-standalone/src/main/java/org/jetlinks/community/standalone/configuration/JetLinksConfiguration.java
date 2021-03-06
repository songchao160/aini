package org.jetlinks.community.standalone.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.device.message.writer.TimeSeriesMessageWriterConnector;
import org.jetlinks.community.timeseries.TimeSeriesManager;
import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.cluster.ClusterManager;
import org.jetlinks.core.device.DeviceOperationBroker;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.device.StandaloneDeviceMessageBroker;
import org.jetlinks.core.message.DeviceOfflineMessage;
import org.jetlinks.core.message.DeviceOnlineMessage;
import org.jetlinks.core.server.MessageHandler;
import org.jetlinks.core.server.monitor.GatewayServerMetrics;
import org.jetlinks.core.server.monitor.GatewayServerMonitor;
import org.jetlinks.core.server.session.DeviceSessionManager;
import org.jetlinks.core.spi.ServiceContext;
import org.jetlinks.community.device.message.DeviceMessageConnector;
import org.jetlinks.community.gateway.MessageConnector;
import org.jetlinks.community.gateway.supports.DefaultMessageGateway;
import org.jetlinks.community.gateway.supports.LocalClientSessionManager;
import org.jetlinks.supports.cluster.ClusterDeviceRegistry;
import org.jetlinks.supports.cluster.redis.RedisClusterManager;
import org.jetlinks.supports.protocol.ServiceLoaderProtocolSupports;
import org.jetlinks.supports.protocol.management.ClusterProtocolSupportManager;
import org.jetlinks.supports.protocol.management.ProtocolSupportLoader;
import org.jetlinks.supports.protocol.management.ProtocolSupportManager;
import org.jetlinks.supports.protocol.management.jar.JarProtocolSupportLoader;
import org.jetlinks.supports.server.DefaultClientMessageHandler;
import org.jetlinks.supports.server.DefaultDecodedClientMessageHandler;
import org.jetlinks.supports.server.DefaultSendToDeviceMessageHandler;
import org.jetlinks.supports.server.monitor.MicrometerGatewayServerMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableConfigurationProperties(JetLinksProperties.class)
@Slf4j
public class JetLinksConfiguration {

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> webServerFactoryWebServerFactoryCustomizer() {
        //????????????????????????????????????
        return factory -> factory.addServerCustomizers(httpServer ->
            httpServer.httpRequestDecoder(spec -> {
                spec.maxInitialLineLength(10240);
                return spec;
            }));
    }

    @Bean
    @ConfigurationProperties(prefix = "vertx")
    public VertxOptions vertxOptions() {
        return new VertxOptions();
    }

    @Bean
    public Vertx vertx(VertxOptions vertxOptions) {
        return Vertx.vertx(vertxOptions);
    }

    @Bean
    public StandaloneDeviceMessageBroker standaloneDeviceMessageBroker() {
        return new StandaloneDeviceMessageBroker();
    }

    @Bean(initMethod = "startup")
    public RedisClusterManager clusterManager(JetLinksProperties properties, ReactiveRedisTemplate<Object, Object> template) {
        return new RedisClusterManager(properties.getClusterName(), properties.getServerId(), template);
    }

    @Bean
    public DeviceRegistry deviceRegistry(ProtocolSupports supports, ClusterManager manager, DeviceOperationBroker handler) {
        return new ClusterDeviceRegistry(supports, manager, handler);
    }

    @Bean(initMethod = "startup", destroyMethod = "shutdown")
    public DefaultMessageGateway defaultMessageGateway(@Autowired(required = false) List<MessageConnector> connectors) {
        DefaultMessageGateway gateway = new DefaultMessageGateway("default", "????????????", new LocalClientSessionManager());
        if (connectors != null) {
            for (MessageConnector connector : connectors) {
                gateway.registerMessageConnector(connector);
            }
        }
        return gateway;
    }

    @Bean
    public DeviceMessageConnector deviceMessageConnector(DeviceRegistry registry) {
        return new DeviceMessageConnector(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "device.message.writer.time-series", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TimeSeriesMessageWriterConnector timeSeriesMessageWriterConnector(TimeSeriesManager timeSeriesManager, DeviceRegistry registry) {
        return new TimeSeriesMessageWriterConnector(timeSeriesManager, registry);
    }

    @Bean(destroyMethod = "shutdown")
    public DefaultDecodedClientMessageHandler defaultDecodedClientMessageHandler(MessageHandler handler,
                                                                                 DeviceMessageConnector messageConnector,
                                                                                 DeviceSessionManager deviceSessionManager,
                                                                                 ApplicationEventPublisher eventPublisher) {
        DefaultDecodedClientMessageHandler clientMessageHandler = new DefaultDecodedClientMessageHandler(handler, deviceSessionManager,
            EmitterProcessor.create(false)
        );
        // TODO: 2019/12/31 ?????????????????????????????????
        clientMessageHandler
            .subscribe()
            .parallel()
            .runOn(Schedulers.parallel())
            .flatMap(msg -> messageConnector.onMessage(msg).onErrorContinue((err, r) -> log.error(err.getMessage(), err)))
            .subscribe();

        return clientMessageHandler;
    }

    @Bean(initMethod = "startup")
    public DefaultSendToDeviceMessageHandler defaultSendToDeviceMessageHandler(JetLinksProperties properties,
                                                                               DeviceSessionManager sessionManager,
                                                                               DeviceRegistry registry,
                                                                               MessageHandler messageHandler) {
        return new DefaultSendToDeviceMessageHandler(properties.getServerId(), sessionManager, messageHandler, registry);
    }

    @Bean
    public DefaultClientMessageHandler defaultClientMessageHandler(DefaultDecodedClientMessageHandler handler) {
        return new DefaultClientMessageHandler(handler);
    }


    @Bean
    public GatewayServerMonitor gatewayServerMonitor(JetLinksProperties properties, MeterRegistry registry) {
        GatewayServerMetrics metrics = new MicrometerGatewayServerMetrics(properties.getServerId(), registry);

        return new GatewayServerMonitor() {
            @Override
            public String getCurrentServerId() {
                return properties.getServerId();
            }

            @Override
            public GatewayServerMetrics metrics() {
                return metrics;
            }
        };
    }


    @Bean(initMethod = "init", destroyMethod = "shutdown")
    public DefaultDeviceSessionManager deviceSessionManager(JetLinksProperties properties,
                                                            GatewayServerMonitor monitor,
                                                            DeviceMessageConnector messageConnector,
                                                            DeviceRegistry registry,
                                                            ScheduledExecutorService executorService,
                                                            ApplicationEventPublisher eventPublisher) {
        DefaultDeviceSessionManager sessionManager = new DefaultDeviceSessionManager();
        sessionManager.setExecutorService(executorService);
        sessionManager.setGatewayServerMonitor(monitor);
        sessionManager.setRegistry(registry);
        Optional.ofNullable(properties.getTransportLimit()).ifPresent(sessionManager::setTransportLimits);

        sessionManager.onRegister()
            .flatMap(session -> {
                DeviceOnlineMessage message = new DeviceOnlineMessage();
                message.setDeviceId(session.getDeviceId());
                message.setTimestamp(session.connectTime());
                return messageConnector.onMessage(message);
            })
            .onErrorContinue((err, r) -> log.error(err.getMessage(), err))
            .subscribe();

        sessionManager.onUnRegister()
            .flatMap(session -> {
                DeviceOfflineMessage message = new DeviceOfflineMessage();
                message.setDeviceId(session.getDeviceId());
                message.setTimestamp(System.currentTimeMillis());
                return messageConnector.onMessage(message);
            })
            .onErrorContinue((err, r) -> log.error(err.getMessage(), err))
            .subscribe();

        return sessionManager;
    }

    @Bean(initMethod = "init")
    public ServiceLoaderProtocolSupports serviceLoaderProtocolSupports(ServiceContext serviceContext) {
        ServiceLoaderProtocolSupports supports = new ServiceLoaderProtocolSupports();
        supports.setServiceContext(serviceContext);
        return supports;
    }

    @Bean
    public ProtocolSupportManager protocolSupportManager(ClusterManager clusterManager) {
        return new ClusterProtocolSupportManager(clusterManager);
    }

    @Bean
    public JarProtocolSupportLoader jarProtocolSupportLoader(ServiceContext serviceContext) {
        JarProtocolSupportLoader loader = new JarProtocolSupportLoader();
        loader.setServiceContext(serviceContext);
        return loader;
    }


    @Bean
    public LazyInitManagementProtocolSupports managementProtocolSupports(ProtocolSupportManager supportManager,
                                                                         ProtocolSupportLoader loader,
                                                                         ClusterManager clusterManager) {
        LazyInitManagementProtocolSupports supports = new LazyInitManagementProtocolSupports();
        supports.setClusterManager(clusterManager);
        supports.setManager(supportManager);
        supports.setLoader(loader);
        return supports;
    }


}
