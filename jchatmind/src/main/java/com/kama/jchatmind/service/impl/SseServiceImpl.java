package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@AllArgsConstructor
@Slf4j
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(chatSessionId, emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            // 客户端可能在建立连接后立刻断开，这里不应该中断整个流程
            log.warn("SSE init send failed, chatSessionId={}", chatSessionId, e);
            clients.remove(chatSessionId);
            emitter.complete();
        }

        emitter.onCompletion(() -> {
            clients.remove(chatSessionId);
        });
        emitter.onTimeout(() -> clients.remove(chatSessionId));
        emitter.onError((error) -> clients.remove(chatSessionId));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter == null) {
            // 用户刷新页面或离开会话时属于正常现象，静默跳过
            log.debug("No SSE client found, skip sending. chatSessionId={}", chatSessionId);
            return;
        }

        try {
            // 将消息转换为字符串
            String sseMessageStr = objectMapper.writeValueAsString(message);
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(sseMessageStr)
            );
        } catch (IOException e) {
            // 连接中断时移除客户端，避免抛出异常污染正常请求日志
            log.warn("SSE send failed, remove client. chatSessionId={}", chatSessionId, e);
            clients.remove(chatSessionId);
            emitter.completeWithError(e);
        }
    }
}
