package org.jetlinks.community.network.tcp.device;

import io.netty.buffer.ByteBufUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.logger.ReactiveLogger;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.ProtocolSupports;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.message.*;
import org.jetlinks.core.message.codec.DefaultTransport;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.FromDeviceMessageContext;
import org.jetlinks.core.message.codec.Transport;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.core.server.session.DeviceSessionManager;
import org.jetlinks.community.gateway.DeviceGateway;
import org.jetlinks.community.gateway.monitor.DeviceGatewayMonitor;
import org.jetlinks.community.gateway.monitor.GatewayMonitors;
import org.jetlinks.community.gateway.monitor.MonitorSupportDeviceGateway;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.community.network.tcp.server.TcpServer;
import org.jetlinks.core.server.session.KeepOnlineSession;
import org.jetlinks.supports.server.DecodedClientMessageHandler;
import reactor.core.Disposable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

@Slf4j
class TcpServerDeviceGateway implements DeviceGateway, MonitorSupportDeviceGateway {

    @Getter
    private String id;

    private TcpServer tcpServer;

    private String protocol;

    private ProtocolSupports supports;

    private DeviceRegistry registry;

    private DecodedClientMessageHandler clientMessageHandler;

    private DeviceSessionManager sessionManager;

    private DeviceGatewayMonitor gatewayMonitor;

    private LongAdder counter = new LongAdder();

    public TcpServerDeviceGateway(String id,
                                  String protocol,
                                  ProtocolSupports supports,
                                  DeviceRegistry deviceRegistry,
                                  DecodedClientMessageHandler clientMessageHandler,
                                  DeviceSessionManager sessionManager,
                                  TcpServer tcpServer) {
        this.gatewayMonitor = GatewayMonitors.getDeviceGatewayMonitor(id);
        this.id = id;
        this.protocol = protocol;
        this.registry = deviceRegistry;
        this.supports = supports;
        this.tcpServer = tcpServer;
        this.clientMessageHandler = clientMessageHandler;
        this.sessionManager = sessionManager;
    }


    private EmitterProcessor<Message> processor = EmitterProcessor.create(false);

    private FluxSink<Message> sink = processor.sink();

    private AtomicBoolean started = new AtomicBoolean();

    public Mono<ProtocolSupport> getProtocol() {
        return supports.getProtocol(protocol);
    }

    @Override
    public long totalConnection() {
        return counter.sum();
    }

    @Override
    public Transport getTransport() {
        return DefaultTransport.TCP;
    }

    @Override
    public NetworkType getNetworkType() {
        return DefaultNetworkType.TCP_SERVER;
    }

    private List<Disposable> disposable = new CopyOnWriteArrayList<>();

    private void doStart() {
        if (started.getAndSet(true) || !disposable.isEmpty()) {
            return;
        }

        disposable.add(tcpServer
            .handleConnection()
            .subscribe(client -> {
                InetSocketAddress clientAddr = client.getRemoteAddress();
                counter.increment();
                gatewayMonitor.totalConnection(counter.intValue());
                client.onDisconnect(() -> {
                    counter.decrement();
                    gatewayMonitor.disconnected();
                    gatewayMonitor.totalConnection(counter.sum());
                });
                AtomicReference<Duration> keepaliveTimeout = new AtomicReference<>();
                AtomicReference<DeviceSession> sessionRef = new AtomicReference<>(sessionManager.getSession(client.getId()));
                client.subscribe()
                    .filter(r -> started.get())
                    .takeWhile(r -> disposable != null)
                    .doOnNext(r -> {
                        log.debug("??????TCP??????:\n{}", r);
                        gatewayMonitor.receivedMessage();
                    })
                    .flatMap(tcpMessage -> getProtocol()
                        .flatMap(pt -> pt.getMessageCodec(getTransport()))
                        .flatMapMany(codec -> codec.decode(new FromDeviceMessageContext() {
                            @Override
                            @Nonnull
                            public EncodedMessage getMessage() {
                                return tcpMessage;
                            }

                            @Override
                            public DeviceSession getSession() {
                                //session????????????
                                if (sessionRef.get() == null) {
                                    return new UnknownTcpDeviceSession(client.getId(), client, getTransport()) {
                                        @Override
                                        public Mono<Boolean> send(EncodedMessage encodedMessage) {
                                            return super.send(encodedMessage).doOnSuccess(r -> gatewayMonitor.sentMessage());
                                        }

                                        @Override
                                        public void setKeepAliveTimeout(Duration timeout) {
                                            keepaliveTimeout.set(timeout);
                                        }
                                    };
                                }
                                return sessionRef.get();
                            }

                            @Override
                            public DeviceOperator getDevice() {
                                return getSession().getOperator();
                            }
                        }))
                        .switchIfEmpty(Mono.fromRunnable(() ->
                            log.warn("???????????????TCP?????????[{}]??????:\n{}",
                                clientAddr,
                                tcpMessage
                            )))
                        .cast(DeviceMessage.class)
                        .flatMap(message -> registry
                            .getDevice(message.getDeviceId())
                            .switchIfEmpty(Mono.fromRunnable(() -> {
                                log.warn("??????[{}]?????????,TCP[{}]??????:[{}],????????????:{}",
                                    message.getDeviceId(),
                                    clientAddr,
                                    ByteBufUtil.hexDump(tcpMessage.getPayload()),
                                    message
                                );
                            }))
                            .flatMap(device -> {
                                DeviceSession fSession = sessionRef.get() == null ?
                                    sessionManager.getSession(device.getDeviceId()) :
                                    sessionRef.get();
                                //????????????????????????
                                if (message instanceof DeviceOnlineMessage) {
                                    if (fSession == null) {
                                        fSession = new TcpDeviceSession(client.getId(), device, client, getTransport()) {
                                            @Override
                                            public Mono<Boolean> send(EncodedMessage encodedMessage) {
                                                return super.send(encodedMessage).doOnSuccess(r -> gatewayMonitor.sentMessage());
                                            }
                                        };
                                        //????????????????????????.???????????????????????????????????????.???????????????????????????????????????
                                        if (message.getHeader(Headers.keepOnline).orElse(false)) {
                                            fSession = new KeepOnlineSession(fSession, Duration.ofMillis(-1));
                                        } else {
                                            client.onDisconnect(() -> sessionManager.unregister(device.getDeviceId()));
                                        }
                                        sessionRef.set(fSession);
                                        sessionManager.register(fSession);
                                    }
                                    fSession.keepAlive();
                                    if (keepaliveTimeout.get() != null) {
                                        fSession.setKeepAliveTimeout(keepaliveTimeout.get());
                                    }
                                    return Mono.empty();
                                }
                                if (fSession != null) {
                                    fSession.keepAlive();
                                }
                                //????????????
                                if (message instanceof DeviceOfflineMessage) {
                                    sessionManager.unregister(device.getDeviceId());
                                    return Mono.empty();
                                }
                                message.addHeaderIfAbsent(Headers.clientAddress, String.valueOf(clientAddr));

                                if (processor.hasDownstreams()) {
                                    sink.next(message);
                                }
                                return clientMessageHandler.handleMessage(device, message);
                            }))
                        .doOnEach(ReactiveLogger.onError(err ->
                            log.error("??????TCP[{}]????????????:\n{}",
                                clientAddr,
                                tcpMessage
                                , err))))
                    .onErrorResume((err) -> Mono.empty())
                    .subscriberContext(ReactiveLogger.start("network", tcpServer.getId()))
                    .subscribe();
            }));
    }

    @Override
    public Flux<Message> onMessage() {
        return processor.map(Function.identity());
    }

    @Override
    public Mono<Void> pause() {
        return Mono.fromRunnable(() -> started.set(false));
    }

    @Override
    public Mono<Void> startup() {
        return Mono.fromRunnable(this::doStart);
    }

    @Override
    public Mono<Void> shutdown() {
        return Mono.fromRunnable(() -> {
            started.set(false);

            disposable.forEach(Disposable::dispose);

            disposable.clear();
        });
    }

    @Override
    public boolean isAlive() {
        return started.get();
    }
}
