package com.github.k0kubun.github_ranking;

import com.github.k0kubun.github_ranking.config.Config;
import com.github.k0kubun.github_ranking.server.ApiApplication;
import com.github.k0kubun.github_ranking.server.ApiServer;
import com.github.k0kubun.github_ranking.worker.OrganizationRankingWorker;
import com.github.k0kubun.github_ranking.worker.UpdateUserWorker;
import com.github.k0kubun.github_ranking.worker.UserRankingWorker;
import com.github.k0kubun.github_ranking.worker.Worker;
import com.github.k0kubun.github_ranking.worker.WorkerManager;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main
{
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final int NUM_UPDATE_USER_WORKERS = 3;

    private static final Config config = new Config(System.getenv());

    public static void main(String[] args)
    {
        ScheduledExecutorService scheduler = buildAndRunScheduler();

        WorkerManager workers = buildWorkers(config);
        workers.start();

        ApiServer server = new ApiServer(ApiApplication.class, config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownAndAwaitTermination(scheduler);
            server.stop();
            workers.stop();
        }));
    }

    private static ScheduledExecutorService buildAndRunScheduler()
    {
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("scheduler-%d")
            .setUncaughtExceptionHandler((t, e) -> {
                LOG.error("Uncaught exception at scheduler: " + e.getMessage());
                e.printStackTrace();
            })
            .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);

        // Schedule at most every 6 hours
        scheduler.scheduleWithFixedDelay(() -> {
            scheduleIfEmpty(config.getQueueConfig().getUserRankingQueue());
            scheduleIfEmpty(config.getQueueConfig().getOrgRankingQueue());
            scheduleIfEmpty(config.getQueueConfig().getRepoRankingQueue());
        }, 6, 6, TimeUnit.HOURS);
        return scheduler;
    }

    private static void scheduleIfEmpty(BlockingQueue<Boolean> queue)
    {
        if (queue.size() == 0) {
            try {
                queue.put(true);
            } catch (InterruptedException e) {
                LOG.error("Scheduling interrupted: " + e.getMessage());
            }
        }
    }

    private static WorkerManager buildWorkers(Config config)
    {
        DataSource dataSource = config.getDatabaseConfig().getDataSource();

        WorkerManager workers = new WorkerManager();
        for (int i = 0; i < NUM_UPDATE_USER_WORKERS; i++) {
            workers.add(new UpdateUserWorker(dataSource));
        }
        workers.add(new UserRankingWorker(config));
        workers.add(new OrganizationRankingWorker(config));
        return workers;
    }

    // https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html
    private static void shutdownAndAwaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    LOG.error("Failed to shutdown scheduler");
                }
            }
        } catch (InterruptedException e) {
            LOG.error("Scheduler shutdown interrupted: " + e.getMessage());
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
