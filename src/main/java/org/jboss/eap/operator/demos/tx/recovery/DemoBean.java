package org.jboss.eap.operator.demos.tx.recovery;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class DemoBean {

    @PersistenceContext(unitName = "demo")
    EntityManager em;

    volatile CountDownLatch hangTxLatch;

    @Inject
    DemoBean internalDelegate;

    // It seems the max number of active tasks here is 2
    @Resource
    private ManagedExecutorService executor;

    @Resource
    TransactionSynchronizationRegistry txSyncRegistry;
    
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
                freeLatch();
        }
    }

    private class HttpReleasePoller implements Runnable {
        private final URL url;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        public HttpReleasePoller() {
            String hostName = System.getenv().get("HOSTNAME");
            try {
                this.url = new URL("http://eap7-app-release-server:8080/release/" + hostName);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            System.out.println("Release poller: starting");
            try {
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
                        System.out.println("Release poller: Polled release server. Status: " + status);
                        try (BufferedReader in = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()))) {
                            String inputLine;
                            StringBuffer content = new StringBuffer();
                            while ((inputLine = in.readLine()) != null) {
                                System.out.println("Read line: " + inputLine);
                                content.append(inputLine);
                            }
                            System.out.println("----> read content '" + content.toString().trim() + "'");
                            if (content.toString().trim().equals("1")) {
                                System.out.println("Freeing latch");
                                freeLatch();
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
            } finally {
                System.out.println("Release poller: ending");
            }
        }

        public void stop() {
            stopped.set(true);
        }
    }
}
