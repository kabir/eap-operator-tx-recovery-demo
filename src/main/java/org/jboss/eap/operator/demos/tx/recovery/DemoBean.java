package org.jboss.eap.operator.demos.tx.recovery;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.transaction.Transactional;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@ApplicationScoped
public class DemoBean {

    private static final String RELEASE_MARKER_NAME = "release";

    @PersistenceContext(unitName = "demo")
    EntityManager em;

    volatile CountDownLatch hangTxLatch;

    @Inject
    DemoBean internalDelegate;

    private final ExecutorService transactionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor();
    private volatile Path releaseMarker;



    @PostConstruct
    public void initialiseWatcher() {

        try {
            String value = System.getenv().get("TX_RELEASE_DIRECTORY");
            Path path = null;
            if (value != null) {
                System.out.println("TX_RELEASE_DIRECTORY variable specified. Using to determine directory.");
                path = Paths.get(value);
            } else {
                System.out.println("TX_RELEASE_DIRECTORY variable not specified. Using a temporary directory");
                path = Files.createTempDirectory("xxx");
            }
            path = path.toAbsolutePath().normalize();

            if (Files.exists(path)) {
                throw new IllegalStateException("The directory " + path + " exists. Please specify an empty directory");
            }
            Files.createDirectories(path);
            System.out.println("Using the directory " + path + ". Initialising the watch service...");

            releaseMarker = path.resolve(RELEASE_MARKER_NAME);

            System.out.println("==========================================================================================");
            System.out.println("Once you have tried to add an entry, rsh into the pod and release the transaction by running '`'touch " + path + "'");
            System.out.println("==========================================================================================");


            WatchService watcher = FileSystems.getDefault().newWatchService();
            WatchKey key = path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);

            watcherExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        WatchKey key;
                        try {
                            key = watcher.take();
                        } catch (InterruptedException e) {
                            return;
                        }
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();

                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                // OVERFLOW can happen even if not the event we said we were interested in
                                continue;
                            }

                            // File name is context of event
                            WatchEvent<Path> we = (WatchEvent<Path>) event;
                            Path path = we.context();
                            System.out.println("Watcher found file created at: " + path);
                            if (path.endsWith(RELEASE_MARKER_NAME)) {
                                System.out.println("File name matches, looking for latch to release.");
                                if (hangTxLatch != null) {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                    hangTxLatch.countDown();
                                }
                            }
                        }
                    }
                }
            });

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PreDestroy
    public void stop() {
        watcherExecutor.shutdown();
        transactionExecutor.shutdown();
    }

    Response addEntryToRunInTransactionInBackground(String value) {
        if (value == null) {
            return Response.status(Response.Status.BAD_REQUEST.getStatusCode(), "Null value").build();
        }

        synchronized (RELEASE_MARKER_NAME) {
            if (hangTxLatch != null) {
                return Response.status(
                                Response.Status.CONFLICT.getStatusCode(),
                                "There is currently a long transaction in process. Please release the existing one.")
                        .build();
            }
            hangTxLatch = new CountDownLatch(1);
            transactionExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    internalDelegate.addEntryInTxAndWait(value);
                }
            });
        }
        return Response.accepted().build();
    }

    // Called internally by the transactionExecutor Runnable
    @Transactional
    Response addEntryInTxAndWait(String value) {
        try {
            System.out.println("Waiting for the latch to be released....");
            hangTxLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Thread was interrupted");
            Response.status(Response.Status.REQUEST_TIMEOUT).build();
        }

        System.out.println("Persisting entity with value: " + value);
        DemoEntity entity = new DemoEntity();
        entity.setValue(value);
        em.persist(entity);

        System.out.println("Persisted");

        // Not totally correct but as this is a low traffic demo it should be ok.
        synchronized (DemoBean.class) {
            hangTxLatch = null;
        }
        return Response.ok().build();
    }

    @Transactional
    public List<String> getAllValues() {
        TypedQuery<DemoEntity> query = em.createQuery("SELECT d from DemoEntity d", DemoEntity.class);
        List<String> values = query.getResultList().stream().map(v -> v.getValue()).collect(Collectors.toList());
        return values;
    }
}
