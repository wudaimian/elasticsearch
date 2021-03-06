/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.bulk.byscroll;

import org.elasticsearch.action.bulk.byscroll.BulkByScrollTask;
import org.elasticsearch.action.bulk.byscroll.WorkingBulkByScrollTask;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.Before;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.common.unit.TimeValue.timeValueNanos;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class WorkingBulkByScrollTaskTests extends ESTestCase {
    private WorkingBulkByScrollTask task;

    @Before
    public void createTask() {
        task = new WorkingBulkByScrollTask(1, "test_type", "test_action", "test", TaskId.EMPTY_TASK_ID, null, Float.POSITIVE_INFINITY);
    }

    public void testBasicData() {
        assertEquals(1, task.getId());
        assertEquals("test_type", task.getType());
        assertEquals("test_action", task.getAction());
        assertEquals("test", task.getDescription());
    }

    public void testProgress() {
        long created = 0;
        long updated = 0;
        long deleted = 0;
        long versionConflicts = 0;
        long noops = 0;
        int batch = 0;
        BulkByScrollTask.Status status = task.getStatus();
        assertEquals(0, status.getTotal());
        assertEquals(created, status.getCreated());
        assertEquals(updated, status.getUpdated());
        assertEquals(deleted, status.getDeleted());
        assertEquals(versionConflicts, status.getVersionConflicts());
        assertEquals(batch, status.getBatches());
        assertEquals(noops, status.getNoops());

        long totalHits = randomIntBetween(10, 1000);
        task.setTotal(totalHits);
        for (long p = 0; p < totalHits; p++) {
            status = task.getStatus();
            assertEquals(totalHits, status.getTotal());
            assertEquals(created, status.getCreated());
            assertEquals(updated, status.getUpdated());
            assertEquals(deleted, status.getDeleted());
            assertEquals(versionConflicts, status.getVersionConflicts());
            assertEquals(batch, status.getBatches());
            assertEquals(noops, status.getNoops());

            if (randomBoolean()) {
                created++;
                task.countCreated();
            } else if (randomBoolean()) {
                updated++;
                task.countUpdated();
            } else {
                deleted++;
                task.countDeleted();
            }

            if (rarely()) {
                versionConflicts++;
                task.countVersionConflict();
            }

            if (rarely()) {
                batch++;
                task.countBatch();
            }

            if (rarely()) {
                noops++;
                task.countNoop();
            }
        }
        status = task.getStatus();
        assertEquals(totalHits, status.getTotal());
        assertEquals(created, status.getCreated());
        assertEquals(updated, status.getUpdated());
        assertEquals(deleted, status.getDeleted());
        assertEquals(versionConflicts, status.getVersionConflicts());
        assertEquals(batch, status.getBatches());
        assertEquals(noops, status.getNoops());
    }

    /**
     * Furiously rethrottles a delayed request to make sure that we never run it twice.
     */
    public void testDelayAndRethrottle() throws IOException, InterruptedException {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        int threads = between(1, 10);
        CyclicBarrier waitForShutdown = new CyclicBarrier(threads);

        /*
         * We never end up waiting this long because the test rethrottles over and over again, ratcheting down the delay a random amount
         * each time.
         */
        float originalRequestsPerSecond = (float) randomDoubleBetween(1, 10000, true);
        task.rethrottle(originalRequestsPerSecond);
        TimeValue maxDelay = timeValueSeconds(between(1, 5));
        assertThat(maxDelay.nanos(), greaterThanOrEqualTo(0L));
        int batchSizeForMaxDelay = (int) (maxDelay.seconds() * originalRequestsPerSecond);
        ThreadPool threadPool = new TestThreadPool(getTestName()) {
            @Override
            public ScheduledFuture<?> schedule(TimeValue delay, String name, Runnable command) {
                assertThat(delay.nanos(), both(greaterThanOrEqualTo(0L)).and(lessThanOrEqualTo(maxDelay.nanos())));
                return super.schedule(delay, name, command);
            }
        };
        try {
            task.delayPrepareBulkRequest(threadPool, timeValueNanos(System.nanoTime()), batchSizeForMaxDelay, new AbstractRunnable() {
                @Override
                protected void doRun() throws Exception {
                    boolean oldValue = done.getAndSet(true);
                    if (oldValue) {
                        throw new RuntimeException("Ran twice oh no!");
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    errors.add(e);
                }
            });

            // Rethrottle on a random number of threads, one of which is this thread.
            Runnable test = () -> {
                try {
                    int rethrottles = 0;
                    while (false == done.get()) {
                        float requestsPerSecond = (float) randomDoubleBetween(0, originalRequestsPerSecond * 2, true);
                        task.rethrottle(requestsPerSecond);
                        rethrottles += 1;
                    }
                    logger.info("Rethrottled [{}] times", rethrottles);
                    waitForShutdown.await();
                } catch (Exception e) {
                    errors.add(e);
                }
            };
            for (int i = 1; i < threads; i++) {
                threadPool.generic().execute(test);
            }
            test.run();
        } finally {
            // Other threads should finish up quickly as they are checking the same AtomicBoolean.
            threadPool.shutdown();
            threadPool.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(errors, empty());
    }

    public void testDelayNeverNegative() throws IOException {
        // Thread pool that returns a ScheduledFuture that claims to have a negative delay
        ThreadPool threadPool = new TestThreadPool("test") {
            public ScheduledFuture<?> schedule(TimeValue delay, String name, Runnable command) {
                return new ScheduledFuture<Void>() {
                    @Override
                    public long getDelay(TimeUnit unit) {
                        return -1;
                    }

                    @Override
                    public int compareTo(Delayed o) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean isCancelled() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean isDone() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Void get() throws InterruptedException, ExecutionException {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
        try {
            // Have the task use the thread pool to delay a task that does nothing
            task.delayPrepareBulkRequest(threadPool, timeValueSeconds(0), 1, new AbstractRunnable() {
                @Override
                protected void doRun() throws Exception {
                }
                @Override
                public void onFailure(Exception e) {
                    throw new UnsupportedOperationException();
                }
            });
            // Even though the future returns a negative delay we just return 0 because the time is up.
            assertEquals(timeValueSeconds(0), task.getStatus().getThrottledUntil());
        } finally {
            threadPool.shutdown();
        }
    }

    public void testPerfectlyThrottledBatchTime() {
        task.rethrottle(Float.POSITIVE_INFINITY);
        assertThat((double) task.perfectlyThrottledBatchTime(randomInt()), closeTo(0f, 0f));

        int total = between(0, 1000000);
        task.rethrottle(1);
        assertThat((double) task.perfectlyThrottledBatchTime(total),
                closeTo(TimeUnit.SECONDS.toNanos(total), TimeUnit.SECONDS.toNanos(1)));
    }
}
