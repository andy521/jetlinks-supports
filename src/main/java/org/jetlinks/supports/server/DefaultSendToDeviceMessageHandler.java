package org.jetlinks.supports.server;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceState;
import org.jetlinks.core.device.DeviceStateInfo;
import org.jetlinks.core.enums.ErrorCode;
import org.jetlinks.core.message.*;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.ToDeviceMessageContext;
import org.jetlinks.core.server.MessageHandler;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.core.server.session.DeviceSessionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@AllArgsConstructor
public class DefaultSendToDeviceMessageHandler {

    private String serverId;

    private DeviceSessionManager sessionManager;

    private MessageHandler handler;

    public void startup() {

        //处理发往设备的消息
        handler
                .handleSendToDeviceMessage(serverId)
                .subscribe(message -> {
                    if (message instanceof DeviceMessage) {
                        handleDeviceMessage(((DeviceMessage) message));
                    }
                    if (message instanceof BroadcastMessage) {
                        // TODO: 2019-10-20
                    }
                });

        //处理设备状态检查
        handler.handleGetDeviceState(serverId, deviceId -> Flux.from(deviceId).map(id -> new DeviceStateInfo(id, sessionManager.getSession(id) != null ? DeviceState.online : DeviceState.offline)));

    }

    protected void handleDeviceMessage(DeviceMessage message) {
        String deviceId = message.getDeviceId();
        DeviceSession session = sessionManager.getSession(deviceId);
        //在当前服务
        if (session != null) {
            doSend(message, session);
        } else {
            log.warn("device[{}] not connected,send message fail", message.getDeviceId());
            doReply(createReply(deviceId, message)
                    .error(ErrorCode.CLIENT_OFFLINE))
                    .subscribe();
        }
    }

    protected DeviceMessageReply createReply(String deviceId, DeviceMessage message) {
        DeviceMessageReply reply;
        if (message instanceof RepayableDeviceMessage) {
            reply = ((RepayableDeviceMessage) message).newReply();
        } else {
            reply = new CommonDeviceMessageReply();
        }
        reply.messageId(message.getMessageId()).deviceId(deviceId);
        return reply;
    }

    protected void doSend(DeviceMessage message, DeviceSession session) {
        String deviceId = message.getDeviceId();

        DeviceMessageReply reply = createReply(deviceId, message);
        if (message instanceof DisconnectDeviceMessage) {
            sessionManager.unregister(session.getDeviceId());
            doReply(reply.success()).subscribe();
        } else {
            session.getOperator()
                    .getProtocol()
                    .flatMap(protocolSupport -> protocolSupport.getMessageCodec(session.getTransport()))
                    .flatMap(codec -> codec.encode(new ToDeviceMessageContext() {
                        @Override
                        public Mono<Boolean> sendToDevice(EncodedMessage message) {
                            return session.send(message);
                        }

                        @Override
                        public Mono<Void> disconnect() {
                            session.close();
                            return Mono.empty();
                        }

                        @Override
                        public Message getMessage() {
                            return message;
                        }

                        @Override
                        public DeviceOperator getDeviceOperator() {
                            return session.getOperator();
                        }
                    }))
                    .flatMap(session::send)
                    .flatMap(success -> {
                        if (message.getHeader(Headers.async).orElse(false)) {
                            return doReply(reply.message(ErrorCode.REQUEST_HANDLING.getText())
                                    .code(ErrorCode.REQUEST_HANDLING.name())
                                    .success());
                        }
                        return Mono.just(true);
                    })
                    .doOnError(error -> {
                        log.error(error.getMessage(), error);
                        doReply(reply.error(error)).subscribe();
                    })
                    .subscribe();
        }
    }


    private Mono<Boolean> doReply(DeviceMessageReply reply) {
        return handler
                .reply(reply)
                .as(mo -> {
                    if (log.isDebugEnabled()) {
                        return mo.doFinally(s -> log.debug("reply message {} ,[{}]", s, reply));
                    }
                    return mo;
                })
                .doOnError((error) -> log.error("reply message error", error));
    }

}