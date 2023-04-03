package org.jboss.eap.operator.demos.tx.recovery;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.transaction.Synchronization;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.Transactional;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class DemoBean {

    private static final String RELEASE_MARKER_NAME = "release";

    private volatile Path markerDir;

    @PersistenceContext(unitName = "demo")
    EntityManager em;

    volatile CountDownLatch hangTxLatch;

    @Inject
    DemoBean internalDelegate;

    @Resource
    private ManagedExecutorService executor;

    @Resource
    TransactionSynchronizationRegistry txSyncRegistry;

    @PostConstruct
    public void initialiseWatcher() {

        try {
            String value = System.getenv().get("TX_RELEASE_DIRECTORY");
            if (value != null) {
                System.out.println("TX_RELEASE_DIRECTORY variable specified. Using to determine directory.");
                markerDir = Paths.get(value);
            } else {
                System.out.println("TX_RELEASE_DIRECTORY variable not specified. Using a temporary directory");
                markerDir = Files.createTempDirectory("xxx");
            }
            markerDir = markerDir.toAbsolutePath().normalize();

            Files.createDirectories(markerDir);
            System.out.println("Using the directory " + markerDir + ". Initialising the watch service...");

            System.out.println("==========================================================================================");
            System.out.println("Once you have tried to add an entry, rsh into the pod and release the transaction by running 'touch " + markerDir + "'");
            System.out.println("==========================================================================================");


            WatchService watcher = FileSystems.getDefault().newWatchService();
            markerDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            executor.submit(new FileWatcher(watcher));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private class FileWatcher implements Runnable {

        private final WatchService watcher;

        public FileWatcher(WatchService watcher) {
            this.watcher = watcher;
        }

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
                        System.out.println("File name matches, looking for latch to release transaction...");

                        freeLatch();

                        try {
                            if (Files.exists(path)) {
                                Files.delete(path);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }
        }
    }

    Response addEntryToRunInTransactionInBackground(String value) {
        if (value == null) {
            System.err.println("Null value.");
            return Response.status(Response.Status.BAD_REQUEST.getStatusCode()).build();
        }

        synchronized (DemoBean.class) {
            if (hangTxLatch != null) {
                System.err.println("There is currently a long transaction in process. Please release the existing one.");
                return Response.status(Response.Status.CONFLICT.getStatusCode())
                        .build();
            }
            hangTxLatch = new CountDownLatch(1);
        }
        System.out.println("Submitting background task to store the entity in a long transaction");
        executor.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println("Calling Transactional method from background task");
                internalDelegate.addEntryInTxAndWait(value);
            }
        });
        return Response.accepted().entity(System.getenv().get("HOSTNAME")).build();
    }

    // Called internally by the transactionExecutor Runnable
    @Transactional
    void addEntryInTxAndWait(String value) {

        System.out.println("Transaction started. Registering Tx Synchronization");
        txSyncRegistry.registerInterposedSynchronization(new Callback());

        System.out.println("Starting Tx release poller");
        HttpReleasePoller poller = new HttpReleasePoller();
        executor.submit(poller);


        System.out.println("Persisting entity with value: " + value);
        DemoEntity entity = new DemoEntity();
        entity.setValue(value);
        em.persist(entity);

        try {
            System.out.println("Waiting for the latch to be released before committing the transaction....");
            hangTxLatch.await();
            System.out.println("Latch was released!");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Wait for latch was interrupted");
        } finally {
            poller.stop();
        }
    }

    @Transactional
    public List<String> getAllValues() {
        TypedQuery<DemoEntity> query = em.createQuery("SELECT d from DemoEntity d", DemoEntity.class);
        List<String> values = query.getResultList().stream().map(v -> v.getValue()).collect(Collectors.toList());
        return values;
    }

    private void freeLatch() {
        synchronized (DemoBean.class) {
            if (hangTxLatch != null) {
                System.out.println("Resetting latch");
                hangTxLatch.countDown();
                hangTxLatch = null;
            } else {
                System.out.println("Latch already reset. Nothing to do");
            }
        }
    }

    private class Callback implements Synchronization {

        @Override
        public void beforeCompletion() {

        }

        @Override
        public void afterCompletion(int status) {
            System.out.println("Attempting to clear latch in Tx Synchronization. Status: " + status);
            synchronized (DemoBean.class) {
                freeLatch();
            }
        }
    }

    private class HttpReleasePoller implements Runnable {
        private final URL url;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        public HttpReleasePoller() {
            String hostName = System.getenv().get("HOSTNAME");
            try {
                this.url = new URL("http://eap7-app-release-server/release/" + hostName);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            while (!stopped.get()) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    int status = 0;
                    try {
                        status = conn.getResponseCode();
                    } catch (ConnectException e) {
                        System.err.println("Error connecting: " + e.getMessage());
                    }
                    System.out.println("Polled release server. Status: " + status);
                    try (BufferedReader in = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String inputLine;
                        StringBuffer content = new StringBuffer();
                        while ((inputLine = in.readLine()) != null) {
                            content.append(inputLine);
                        }
                        System.out.println("----> read content '" + content.toString().trim() + "'");
                        if (content.toString().trim().equals("1")) {
                            System.out.println("Writing marker to release lock");
                            Path marker = markerDir.resolve(RELEASE_MARKER_NAME);
                            Files.write(marker, "1".getBytes(StandardCharsets.UTF_8));
                            return;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        public void stop() {
            stopped.set(true);
        }
    }
}
