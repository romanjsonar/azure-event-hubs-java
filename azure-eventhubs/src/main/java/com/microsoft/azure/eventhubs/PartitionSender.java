/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.eventhubs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This sender class is a logical representation of sending events to a specific EventHub partition. Do not use this class
 * if you do not care about sending events to specific partitions. Instead, use {@link EventHubClient#send} method.
 *
 * @see EventHubClient#createPartitionSender(String)
 * @see EventHubClient#createFromConnectionString(String)
 */
public final class PartitionSender extends ClientEntity {
    private final String partitionId;
    private final String eventHubName;
    private final MessagingFactory factory;

    private MessageSender internalSender;

    private PartitionSender(MessagingFactory factory, String eventHubName, String partitionId) {
        super(null, null);

        this.partitionId = partitionId;
        this.eventHubName = eventHubName;
        this.factory = factory;
    }

    /**
     * Internal-Only: factory pattern to Create EventHubSender
     */
    static CompletableFuture<PartitionSender> Create(MessagingFactory factory, String eventHubName, String partitionId) throws EventHubException {
        final PartitionSender sender = new PartitionSender(factory, eventHubName, partitionId);
        return sender.createInternalSender()
                .thenApply(new Function<Void, PartitionSender>() {
                    public PartitionSender apply(Void a) {
                        return sender;
                    }
                });
    }

    private CompletableFuture<Void> createInternalSender() throws EventHubException {
        return MessageSender.create(this.factory, StringUtil.getRandomString(),
                String.format("%s/Partitions/%s", this.eventHubName, this.partitionId))
                .thenAccept(new Consumer<MessageSender>() {
                    public void accept(MessageSender a) {
                        PartitionSender.this.internalSender = a;
                    }
                });
    }

    /**
     * Creates an Empty Collection of {@link EventData}.
     * @return the empty {@link EventDataBatch}, after negotiating maximum message size with EventHubs service
     */
    public EventDataBatch createBatch(BatchOptions options) {
        if (!StringUtil.isNullOrEmpty(options.partitionKey)) {
            throw new IllegalArgumentException("A partition key cannot be set when using PartitionSender. If you'd like to " +
                    "continue using PartitionSender with EventDataBatches, then please do not set a partition key in your BatchOptions.");
        }

        int maxSize = this.internalSender.getMaxMessageSize();

        if (options.maxMessageSize == null) {
            return new EventDataBatch(maxSize, null);
        }

        if (options.maxMessageSize > maxSize) {
            throw new IllegalArgumentException("The maxMessageSize set in BatchOptions is too large. You set a maxMessageSize of " +
                    options.maxMessageSize + ". The maximum allowed size is " + maxSize + ".");
        }

        return new EventDataBatch(options.maxMessageSize, null);
    }

    /**
     * Synchronous version of {@link #send(EventData)} Api.
     *
     * @param data the {@link EventData} to be sent.
     * @throws PayloadSizeExceededException if the total size of the {@link EventData} exceeds a pre-defined limit set by the service. Default is 256k bytes.
     * @throws EventHubException          if Service Bus service encountered problems during the operation.
     */
    public final void sendSync(final EventData data)
            throws EventHubException {
        try {
            this.send(data).get();
        } catch (InterruptedException | ExecutionException exception) {
            if (exception instanceof InterruptedException) {
                // Re-assert the thread's interrupted status
                Thread.currentThread().interrupt();
            }

            Throwable throwable = exception.getCause();
            if (throwable instanceof EventHubException) {
                throw (EventHubException) throwable;
            } else if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            } else {
                throw new RuntimeException(exception);
            }
        }
    }

    /**
     * Send {@link EventData} to a specific EventHub partition. The target partition is pre-determined when this PartitionSender was created.
     * This send pattern emphasize data correlation over general availability and latency.
     * <p>
     * There are 3 ways to send to EventHubs, each exposed as a method (along with its sendBatch overload):
     * <pre>
     * i.   {@link EventHubClient#send(EventData)}, {@link EventHubClient#send(Iterable)}, {@link EventHubClient#send(EventDataBatch)}
     * ii.  {@link EventHubClient#send(EventData, String)} or {@link EventHubClient#send(Iterable, String)}
     * iii. {@link PartitionSender#send(EventData)}, {@link PartitionSender#send(Iterable)}, or {@link PartitionSender(EventDataBatch)}
     * </pre>
     * <p>
     * Use this type of Send, if:
     * <pre>
     * i. The client wants to take direct control of distribution of data across partitions. In this case client is responsible for making sure there is at least one sender per event hub partition.
     * ii. User cannot use partition key as a mean to direct events to specific partition, yet there is a need for data correlation with partitioning scheme.
     * </pre>
     *
     * @param data the {@link EventData} to be sent.
     * @return a CompletableFuture that can be completed when the send operations is done..
     */
    public final CompletableFuture<Void> send(EventData data) {
        return this.internalSender.send(data.toAmqpMessage());
    }

    /**
     * Synchronous version of {@link #send(Iterable)} .
     *
     * @param eventDatas batch of events to send to EventHub
     * @throws EventHubException if Service Bus service encountered problems during the operation.
     */
    public final void sendSync(final Iterable<EventData> eventDatas)
            throws EventHubException {
        try {
            this.send(eventDatas).get();
        } catch (InterruptedException | ExecutionException exception) {
            if (exception instanceof InterruptedException) {
                // Re-assert the thread's interrupted status
                Thread.currentThread().interrupt();
            }

            Throwable throwable = exception.getCause();
            if (throwable instanceof EventHubException) {
                throw (EventHubException) throwable;
            } else if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            } else {
                throw new RuntimeException(exception);
            }
        }
    }

    /**
     * Send {@link EventData} to a specific EventHub partition. The targeted partition is pre-determined when this PartitionSender was created.
     * <p>
     * There are 3 ways to send to EventHubs, to understand this particular type of Send refer to the overload {@link #send(EventData)}, which is the same type of Send and is used to send single {@link EventData}.
     * <p>
     * Sending a batch of {@link EventData}'s is useful in the following cases:
     * <pre>
     * i.	Efficient send - sending a batch of {@link EventData} maximizes the overall throughput by optimally using the number of sessions created to EventHubs' service.
     * ii.	Send multiple {@link EventData}'s in a Transaction. To achieve ACID properties, the Gateway Service will forward all {@link EventData}'s in the batch to a single EventHubs' partition.
     * </pre>
     * <p>
     * Sample code (sample uses sync version of the api but concept are identical):
     * <pre>
     * Gson gson = new GsonBuilder().create();
     * EventHubClient client = EventHubClient.createFromConnectionStringSync("__connection__");
     * PartitionSender senderToPartitionOne = client.createPartitionSenderSync("1");
     *
     * while (true)
     * {
     *     LinkedList{@literal<}EventData{@literal>} events = new LinkedList{@literal<}EventData{@literal>}();
     *     for (int count = 1; count {@literal<} 11; count++)
     *     {
     *         PayloadEvent payload = new PayloadEvent(count);
     *         byte[] payloadBytes = gson.toJson(payload).getBytes(Charset.defaultCharset());
     *         EventData sendEvent = new EventData(payloadBytes);
     *         Map{@literal<}String, String{@literal>} applicationProperties = new HashMap{@literal<}String, String{@literal>}();
     *         applicationProperties.put("from", "javaClient");
     *         sendEvent.setProperties(applicationProperties);
     *         events.add(sendEvent);
     *     }
     *
     *     senderToPartitionOne.sendSync(events);
     *     System.out.println(String.format("Sent Batch... Size: %s", events.size()));
     * }
     * </pre>
     *
     * @param eventDatas batch of events to send to EventHub
     * @return a CompletableFuture that can be completed when the send operations is done..
     * @throws PayloadSizeExceededException if the total size of the {@link EventData} exceeds a pre-defined limit set by the service. Default is 256k bytes.
     * @throws EventHubException          if Service Bus service encountered problems during the operation.
     */
    public final CompletableFuture<Void> send(Iterable<EventData> eventDatas)
            throws EventHubException {
        if (eventDatas == null || IteratorUtil.sizeEquals(eventDatas, 0)) {
            throw new IllegalArgumentException("EventData batch cannot be empty.");
        }

        return this.internalSender.send(EventDataUtil.toAmqpMessages(eventDatas));
    }

    /**
     * Synchronous version of {@link #send(EventDataBatch)}
     *
     * @param eventDatas EventDataBatch to send to EventHub
     * @throws EventHubException if Service Bus service encountered problems during the operation.
     */
    public final void sendSync(final EventDataBatch eventDatas) throws EventHubException {
        try {
            this.send(eventDatas).get();
        } catch (InterruptedException | ExecutionException exception) {
            if (exception instanceof InterruptedException) {
                // Re-assert the thread's interrupted status
                Thread.currentThread().interrupt();
            }

            Throwable throwable = exception.getCause();
            if (throwable instanceof EventHubException) {
                throw (EventHubException) throwable;
            } else if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            } else {
                throw new RuntimeException(exception);
            }
        }
    }

    /**
     * Send {@link EventDataBatch} to a specific EventHub partition. The targeted partition is pre-determined when this PartitionSender was created.
     * A partitionKey cannot be set when using EventDataBatch with a PartitionSender.
     * <p>
     * There are 3 ways to send to EventHubs, to understand this particular type of Send refer to the overload {@link #send(EventData)}, which is the same type of Send and is used to send single {@link EventData}.
     * <p>
     * Sending a batch of {@link EventData}'s is useful in the following cases:
     * <pre>
     * i.	Efficient send - sending a batch of {@link EventData} maximizes the overall throughput by optimally using the number of sessions created to EventHubs' service.
     * ii.	Send multiple {@link EventData}'s in a Transaction. To achieve ACID properties, the Gateway Service will forward all {@link EventData}'s in the batch to a single EventHubs' partition.
     * </pre>
     *
     * @param eventDatas EventDataBatch to send to EventHub
     * @return a CompletableFuture that can be completed when the send operation is done..
     * @throws EventHubException if Service Bus service encountered problems during the operation.
     * @see #send(Iterable)
     * @see EventDataBatch
     */
    public final CompletableFuture<Void> send(EventDataBatch eventDatas)
            throws EventHubException {
        if (eventDatas == null || Integer.compare(eventDatas.getSize(), 0) == 0) {
            throw new IllegalArgumentException("EventDataBatch cannot be empty.");
        }

        if (!StringUtil.isNullOrEmpty(eventDatas.getPartitionKey())) {
            throw new IllegalArgumentException("A partition key cannot be set when using PartitionSender. If you'd like to " +
            "continue using PartitionSender with EventDataBatches, then please do not set a partition key in your BatchOptions");
        }

        return this.internalSender.send(EventDataUtil.toAmqpMessages(eventDatas.getInternalIterable()));
    }

    @Override
    public CompletableFuture<Void> onClose() {
        if (this.internalSender == null) {
            return CompletableFuture.completedFuture(null);
        } else {
            return this.internalSender.close();
        }
    }
}
