package org.jboss.eap.operator.demos.tx.recovery;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.transaction.Transactional;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@ApplicationScoped
public class DemoBean {

    @PersistenceContext(unitName = "demo")
    EntityManager em;

    volatile CountDownLatch hangTxLatch;

    @Transactional
    Response addEntryAndWait(String value) {
        if (hangTxLatch != null) {
            synchronized (this) {
                if (hangTxLatch != null) {
                    throw new IllegalStateException("We are already saving an entry and waiting");
                }
                hangTxLatch = new CountDownLatch(1);
            }
        }
        try {
            hangTxLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Thread was interrupted");
            Response.status(Response.Status.REQUEST_TIMEOUT).build();
        }

        DemoEntity entity = new DemoEntity();
        entity.setValue(value);
        em.persist(entity);

        return Response.ok().build();
    }

    public void release() {
        synchronized (this) {
            if (hangTxLatch == null) {
                hangTxLatch.countDown();
                hangTxLatch = null;
            }
        }
    }

    @Transactional
    public List<String> getAllValues() {
        TypedQuery<DemoEntity> query = em.createQuery("SELECT d from DemoEntity d", DemoEntity.class);
        List<String> values = query.getResultList().stream().map(v -> v.getValue()).collect(Collectors.toList());
        return values;
    }
}
