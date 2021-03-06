package org.jetlinks.supports.cluster.redis;

import org.jetlinks.core.cluster.ClusterTopic;
import org.reactivestreams.Publisher;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import reactor.core.Disposable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

public class RedisClusterTopic<T> implements ClusterTopic<T> {

    private String topicName;

    private ReactiveRedisOperations<Object, T> operations;

    private FluxProcessor<TopicMessage<T>, TopicMessage<T>> processor;

    public RedisClusterTopic(String topic, ReactiveRedisOperations<Object, T> operations) {
        this.topicName = topic;
        this.operations = operations;
        processor = EmitterProcessor.create(false);
    }

    private Disposable disposable;

    private AtomicBoolean subscribed = new AtomicBoolean();

    private void doSubscribe() {
        if (subscribed.compareAndSet(false, true)) {
            disposable = operations
                    .listenToPattern(topicName)
                    .subscribe(data -> {
                        if (!processor.hasDownstreams()) {
                            disposable.dispose();
                            subscribed.compareAndSet(true, false);
                        } else {
                            processor.onNext(new TopicMessage<T>() {
                                @Override
                                public String getTopic() {
                                    return data.getChannel();
                                }

                                @Override
                                public T getMessage() {
                                    return data.getMessage();
                                }
                            });
                        }
                    });
        }
    }

    @Override
    public Flux<TopicMessage<T>> subscribePattern() {
        return processor
                .doOnSubscribe((r) -> doSubscribe());
    }

    @Override
    public Mono<Integer> publish(Publisher<? extends T> publisher) {
        return Flux.from(publisher)
                .flatMap(data -> operations.convertAndSend(topicName, data))
                .last(1L)
                .map(Number::intValue);
    }
}
