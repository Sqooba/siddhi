/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.core.query.input;

import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEvent;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.Event;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventPool;
import io.siddhi.core.event.stream.converter.StreamEventConverter;
import io.siddhi.core.event.stream.converter.StreamEventConverterFactory;
import io.siddhi.core.query.output.ratelimit.OutputRateLimiter;
import io.siddhi.core.query.processor.Processor;
import io.siddhi.core.util.statistics.LatencyTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * {StreamJunction.Receiver} implementation to receive events to be fed into multi
 * stream processors which consume multiple streams.
 */
public class MultiProcessStreamReceiver extends ProcessStreamReceiver {

    private static ThreadLocal<ReturnEventHolder> multiProcessReturn = new ThreadLocal<>();
    protected Processor[] nextProcessors;
    protected int processCount;
    protected int[] eventSequence;
    protected OutputRateLimiter outputRateLimiter;
    private MetaStreamEvent[] metaStreamEvents;
    private StreamEventPool[] streamEventPools;
    private StreamEventConverter[] streamEventConverters;


    public MultiProcessStreamReceiver(String streamId, int processCount,
                                      SiddhiQueryContext siddhiQueryContext) {
        super(streamId, siddhiQueryContext);
        this.processCount = processCount;
        nextProcessors = new Processor[processCount];
        metaStreamEvents = new MetaStreamEvent[processCount];
        streamEventPools = new StreamEventPool[processCount];
        streamEventConverters = new StreamEventConverter[processCount];
        eventSequence = new int[processCount];
        for (int i = 0; i < eventSequence.length; i++) {
            eventSequence[i] = i;
        }
    }

    public static ThreadLocal<ReturnEventHolder> getMultiProcessReturn() {
        return multiProcessReturn;
    }

    public MultiProcessStreamReceiver clone(String key) {
        return new MultiProcessStreamReceiver(streamId + key, processCount,
                siddhiQueryContext);
    }

    private void process(int eventSequence, StreamEvent borrowedEvent) {
        if (lockWrapper != null) {
            lockWrapper.lock();
        }
        try {
            LatencyTracker latencyTracker = siddhiQueryContext.getLatencyTracker();
            if (latencyTracker != null) {
                try {
                    latencyTracker.markIn();
                    processAndClear(eventSequence, borrowedEvent);
                } finally {
                    latencyTracker.markOut();
                }
            } else {
                processAndClear(eventSequence, borrowedEvent);
            }
        } finally {
            if (lockWrapper != null) {
                lockWrapper.unlock();
            }
        }
    }

    @Override
    public void receive(ComplexEvent complexEvent) {
        ComplexEvent aComplexEvent = complexEvent;
        while (aComplexEvent != null) {
            if (outputRateLimiter == null) {
                synchronized (this) {
                    stabilizeStates();
                    for (int anEventSequence : eventSequence) {
                        StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                        StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                        StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                        aStreamEventConverter.convertComplexEvent(aComplexEvent, borrowedEvent);
                        process(anEventSequence, borrowedEvent);
                    }
                }
            } else {
                List<ReturnEventHolder> returnEventHolderList = new ArrayList<>(eventSequence.length);
                try {
                    multiProcessReturn.set(new ReturnEventHolder());
                    synchronized (this) {
                        stabilizeStates();
                        for (int anEventSequence : eventSequence) {
                            StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                            StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                            StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                            aStreamEventConverter.convertComplexEvent(aComplexEvent, borrowedEvent);
                            process(anEventSequence, borrowedEvent);
                            if (multiProcessReturn.get() != null &&
                                    multiProcessReturn.get().complexEventChunk != null) {
                                returnEventHolderList.add(multiProcessReturn.get());
                                multiProcessReturn.set(new ReturnEventHolder());
                            }
                        }
                    }
                } finally {
                    multiProcessReturn.set(null);
                }
                for (ReturnEventHolder returnEventHolder : returnEventHolderList) {
                    outputRateLimiter.sendToCallBacks(returnEventHolder.complexEventChunk);
                }
            }
            aComplexEvent = aComplexEvent.getNext();
        }
    }

    @Override
    public void receive(Event event) {
        if (outputRateLimiter == null) {
            synchronized (this) {
                stabilizeStates();
                for (int anEventSequence : eventSequence) {
                    StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                    StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                    StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                    aStreamEventConverter.convertEvent(event, borrowedEvent);
                    process(anEventSequence, borrowedEvent);
                }
            }
        } else {
            List<ReturnEventHolder> returnEventHolderList = new ArrayList<>(eventSequence.length);
            try {
                multiProcessReturn.set(new ReturnEventHolder());
                synchronized (this) {
                    stabilizeStates();
                    for (int anEventSequence : eventSequence) {
                        StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                        StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                        StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                        aStreamEventConverter.convertEvent(event, borrowedEvent);
                        process(anEventSequence, borrowedEvent);
                        if (multiProcessReturn.get() != null &&
                                multiProcessReturn.get().complexEventChunk != null) {
                            returnEventHolderList.add(multiProcessReturn.get());
                            multiProcessReturn.set(new ReturnEventHolder());
                        }
                    }
                }
            } finally {
                multiProcessReturn.set(null);
            }
            for (ReturnEventHolder returnEventHolder : returnEventHolderList) {
                outputRateLimiter.sendToCallBacks(returnEventHolder.complexEventChunk);
            }
        }
    }

    @Override
    public void receive(Event[] events) {
        for (Event event : events) {
            if (outputRateLimiter == null) {
                synchronized (this) {
                    stabilizeStates();
                    for (int anEventSequence : eventSequence) {
                        StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                        StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                        StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                        aStreamEventConverter.convertEvent(event, borrowedEvent);
                        process(anEventSequence, borrowedEvent);
                    }
                }
            } else {
                List<ReturnEventHolder> returnEventHolderList = new ArrayList<>(eventSequence.length);
                try {
                    multiProcessReturn.set(new ReturnEventHolder());
                    synchronized (this) {
                        stabilizeStates();
                        for (int anEventSequence : eventSequence) {
                            StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                            StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                            StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                            aStreamEventConverter.convertEvent(event, borrowedEvent);
                            process(anEventSequence, borrowedEvent);
                            if (multiProcessReturn.get() != null &&
                                    multiProcessReturn.get().complexEventChunk != null) {
                                returnEventHolderList.add(multiProcessReturn.get());
                                multiProcessReturn.set(new ReturnEventHolder());
                            }
                        }
                    }
                } finally {
                    multiProcessReturn.set(null);
                }
                for (ReturnEventHolder returnEventHolder : returnEventHolderList) {
                    outputRateLimiter.sendToCallBacks(returnEventHolder.complexEventChunk);
                }
            }
        }
    }

    @Override
    public void receive(List<Event> events) {
        for (Event event : events) {
            if (outputRateLimiter == null) {
                synchronized (this) {
                    stabilizeStates();
                    for (int anEventSequence : eventSequence) {
                        StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                        StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                        StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                        aStreamEventConverter.convertEvent(event, borrowedEvent);
                        process(anEventSequence, borrowedEvent);
                    }
                }
            } else {
                List<ReturnEventHolder> returnEventHolderList = new ArrayList<>(eventSequence.length);
                try {
                    multiProcessReturn.set(new ReturnEventHolder());
                    synchronized (this) {
                        stabilizeStates();
                        for (int anEventSequence : eventSequence) {
                            StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                            StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                            StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                            aStreamEventConverter.convertEvent(event, borrowedEvent);
                            process(anEventSequence, borrowedEvent);
                            if (multiProcessReturn.get() != null &&
                                    multiProcessReturn.get().complexEventChunk != null) {
                                returnEventHolderList.add(multiProcessReturn.get());
                                multiProcessReturn.set(new ReturnEventHolder());
                            }
                        }
                    }
                } finally {
                    multiProcessReturn.set(null);
                }
                for (ReturnEventHolder returnEventHolder : returnEventHolderList) {
                    outputRateLimiter.sendToCallBacks(returnEventHolder.complexEventChunk);
                }
            }
        }
    }

    @Override
    public void receive(long timestamp, Object[] data) {
        if (outputRateLimiter == null) {
            synchronized (this) {
                stabilizeStates();
                for (int anEventSequence : eventSequence) {
                    StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                    StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                    StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                    aStreamEventConverter.convertData(timestamp, data, borrowedEvent);
                    process(anEventSequence, borrowedEvent);
                }
            }
        } else {
            List<ReturnEventHolder> returnEventHolderList = new ArrayList<>(eventSequence.length);
            try {
                multiProcessReturn.set(new ReturnEventHolder());
                synchronized (this) {
                    stabilizeStates();
                    for (int anEventSequence : eventSequence) {
                        StreamEventConverter aStreamEventConverter = streamEventConverters[anEventSequence];
                        StreamEventPool aStreamEventPool = streamEventPools[anEventSequence];
                        StreamEvent borrowedEvent = aStreamEventPool.borrowEvent();
                        aStreamEventConverter.convertData(timestamp, data, borrowedEvent);
                        process(anEventSequence, borrowedEvent);
                        if (multiProcessReturn.get() != null &&
                                multiProcessReturn.get().complexEventChunk != null) {
                            returnEventHolderList.add(multiProcessReturn.get());
                            multiProcessReturn.set(new ReturnEventHolder());
                        }
                    }
                }
            } finally {
                multiProcessReturn.set(null);
            }
            for (ReturnEventHolder returnEventHolder : returnEventHolderList) {
                outputRateLimiter.sendToCallBacks(returnEventHolder.complexEventChunk);
            }
        }
    }

    protected void processAndClear(int processIndex, StreamEvent streamEvent) {
        ComplexEventChunk<StreamEvent> currentStreamEventChunk = new ComplexEventChunk<StreamEvent>(
                streamEvent, streamEvent, batchProcessingAllowed);
        nextProcessors[processIndex].process(currentStreamEventChunk);
    }

    protected void stabilizeStates() {

    }

    public void setNext(Processor nextProcessor) {
        for (int i = 0, nextLength = nextProcessors.length; i < nextLength; i++) {
            Processor processor = nextProcessors[i];
            if (processor == null) {
                nextProcessors[i] = nextProcessor;
                break;
            }
        }
    }

    public void setMetaStreamEvent(MetaStreamEvent metaStreamEvent) {
        for (int i = 0, nextLength = metaStreamEvents.length; i < nextLength; i++) {
            MetaStreamEvent streamEvent = metaStreamEvents[i];
            if (streamEvent == null) {
                metaStreamEvents[i] = metaStreamEvent;
                break;
            }
        }
    }

    @Override
    public boolean toStream() {
        return metaStreamEvents[0].getEventType() == MetaStreamEvent.EventType.DEFAULT ||
                metaStreamEvents[0].getEventType() == MetaStreamEvent.EventType.WINDOW;
    }

    public void setStreamEventPool(StreamEventPool streamEventPool) {
        for (int i = 0, nextLength = streamEventPools.length; i < nextLength; i++) {
            StreamEventPool eventPool = streamEventPools[i];
            if (eventPool == null) {
                streamEventPools[i] = streamEventPool;
                break;
            }
        }
    }

    public void init() {

        for (int i = 0, nextLength = streamEventConverters.length; i < nextLength; i++) {
            StreamEventConverter streamEventConverter = streamEventConverters[i];
            if (streamEventConverter == null) {
                streamEventConverters[i] = StreamEventConverterFactory.constructEventConverter(metaStreamEvents[i]);
                break;
            }
        }
    }

    public void setOutputRateLimiter(OutputRateLimiter outputRateLimiter) {
        this.outputRateLimiter = outputRateLimiter;
    }

    /**
     * Class to hold the events which are differed publishing
     */
    public class ReturnEventHolder {
        ComplexEventChunk complexEventChunk;

        public void setReturnEvents(ComplexEventChunk complexEventChunk) {
            if (this.complexEventChunk == null) {
                this.complexEventChunk = new ComplexEventChunk(complexEventChunk.isBatch());
            }
            this.complexEventChunk.add(complexEventChunk.getFirst());
        }
    }
}
