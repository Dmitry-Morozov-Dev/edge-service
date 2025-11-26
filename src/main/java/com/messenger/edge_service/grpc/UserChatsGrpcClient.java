package com.messenger.edge_service.grpc;

import com.messenger.chat_service_new.grpc.GetUserChatsRequest;
import com.messenger.chat_service_new.grpc.GetUserChatsResponse;
import com.messenger.chat_service_new.grpc.UserChatsServiceGrpc;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Component
public class UserChatsGrpcClient {

    @GrpcClient("userChatsService")
    private UserChatsServiceGrpc.UserChatsServiceStub stub;

    public Mono<List<String>> getUserChats(String userId) {
        GetUserChatsRequest request = GetUserChatsRequest.newBuilder()
                .setUserId(userId)
                .build();

        return Mono.<List<String>>create(sink -> stub.getUserChats(request, new StreamObserver<GetUserChatsResponse>() {
            @Override
            public void onNext(GetUserChatsResponse value) {
                sink.success(value.getChatIdsList());
            }

            @Override
            public void onError(Throwable t) {
                sink.error(t);
            }

            @Override
            public void onCompleted() {
                // ничего не делаем, успех уже отправлен в onNext
            }
        }))
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(t -> t instanceof StatusRuntimeException)
        );
    }
}
