package com.kll.deliverydispatch.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.mapper.DispatchOutboxMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchOutboxPublisherTest {

    @Mock
    private DispatchOutboxMapper dispatchOutboxMapper;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private DispatchOutboxPublisher dispatchOutboxPublisher;

    @BeforeEach
    void setUp() {
        dispatchOutboxPublisher = new DispatchOutboxPublisher(
                dispatchOutboxMapper, rocketMQTemplate, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void shouldMarkOutboxPublishedAfterRocketMqAcceptsEvent() {
        DispatchOutboxMessage message = pendingMessage();
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        sendResult.setMsgId("MSG-1");
        when(dispatchOutboxMapper.selectPendingForUpdate(20)).thenReturn(List.of(message));
        when(rocketMQTemplate.syncSend(eq(DispatchRocketMqConstants.DESTINATION), startsWith("{")))
                .thenReturn(sendResult);
        when(dispatchOutboxMapper.markAsPublished(7L)).thenReturn(1);

        dispatchOutboxPublisher.publishPendingMessages();

        verify(dispatchOutboxMapper).markAsPublished(7L);
    }

    @Test
    void shouldKeepOutboxPendingWhenRocketMqWriteFails() {
        DispatchOutboxMessage message = pendingMessage();
        when(dispatchOutboxMapper.selectPendingForUpdate(20)).thenReturn(List.of(message));
        when(rocketMQTemplate.syncSend(eq(DispatchRocketMqConstants.DESTINATION), startsWith("{")))
                .thenThrow(new IllegalStateException("RocketMQ unavailable"));

        dispatchOutboxPublisher.publishPendingMessages();

        verify(dispatchOutboxMapper, never()).markAsPublished(7L);
    }

    private DispatchOutboxMessage pendingMessage() {
        DispatchOutboxMessage message = new DispatchOutboxMessage();
        message.setId(7L);
        message.setOrderId(42L);
        message.setEventType(DispatchEventTypes.DISPATCH_REQUEST);
        message.setStatus(DispatchOutboxMessage.PENDING);
        return message;
    }
}
