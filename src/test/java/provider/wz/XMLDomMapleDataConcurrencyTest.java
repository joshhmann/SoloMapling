package provider.wz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.Data;
import provider.DataEntity;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// The WZ reader is the one piece of the item pipeline every bot thread touches. It used to hand out a
// fresh wrapper around each Xerces node, so the synchronized modifiers locked the wrapper instance -
// never shared between two readers of one file - and concurrent readers built the lazy NodeListCache
// together, which threw "Cannot assign field fChildIndex because this.fNodeListCache is null" on the
// booting bot fleet. These tests pin the two properties the fix rests on (one lock per parsed
// document, inherited by every wrapper it builds) and the traversal results, which must not change.
class XMLDomMapleDataConcurrencyTest {
    private static final int THREADS = 16;
    private static final int ROUNDS = 2000;
    private static final int DIRS = 40;

    @TempDir
    Path tempDir;

    @Test
    void everyWrapperOfOneDocumentSharesItsLock() throws Exception {
        XMLDomMapleData root = root("0400.img.xml");

        Object rootLock = lockOf(root);
        assertNotNull(rootLock, "the root wrapper must hold a document lock");
        assertSame(rootLock, lockOf(root.getChildByPath("04000007")),
                "a child wrapper must inherit the document lock instead of looking it up again");
        assertSame(rootLock, lockOf(root.getChildByPath("04000007/info/price")),
                "a grandchild wrapper must inherit the document lock");
        assertSame(rootLock, lockOf(root.getChildren().get(0)),
                "wrappers built by getChildren must inherit the document lock");
    }

    @Test
    void wrappersOfDifferentDocumentsDoNotShareTheLock() throws Exception {
        XMLDomMapleData first = root("0400.img.xml");
        XMLDomMapleData second = root("0401.img.xml");

        assertNotSame(lockOf(first), lockOf(second),
                "two parsed files are independent and must not serialize against each other");
    }

    @Test
    void parentNavigationStillWalksTheTree() throws Exception {
        XMLDomMapleData root = root("0400.img.xml");
        Data price = root.getChildByPath("04000003/info/price");
        assertEquals("price", price.getName());

        Data namesake = price.getChildByPath("../name");
        assertEquals("name", namesake.getName());
        assertEquals("item-3", namesake.getData());

        Data twiceUp = price.getChildByPath("../../info/name");
        assertEquals("name", twiceUp.getName());
        assertEquals("item-3", twiceUp.getData());

        DataEntity info = price.getParent();
        assertEquals("info", info.getName());
        assertSame(lockOf(root), lockOf(info), "a parent wrapper must stay on the document lock");
        assertEquals("04000003", info.getParent().getName());
    }

    @Test
    void concurrentTraversalsSeeTheSameTreeASingleReader() throws Exception {
        XMLDomMapleData root = root("0400.img.xml");
        assertEquals("item-39", root.getChildByPath("04000039/info/name").getData());

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> readers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        int index = round % DIRS;
                        String dir = "0400" + String.format("%04d", index);

                        Data dirNode = root.getChildByPath(dir);
                        if (dirNode == null) {
                            throw new AssertionError("missing " + dir);
                        }
                        if (dirNode.getChildren().size() != 1) {
                            throw new AssertionError(dir + " listed " + dirNode.getChildren().size() + " children");
                        }
                        Data nameNode = root.getChildByPath(dir + "/info/name");
                        if (nameNode == null || !("item-" + index).equals(nameNode.getData())) {
                            throw new AssertionError(dir + " read a wrong name: " + nameNode);
                        }
                        if (!Integer.valueOf(index).equals(root.getChildByPath(dir + "/info/price").getData())) {
                            throw new AssertionError(dir + " read a wrong price");
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "wz-reader-" + t);
            reader.start();
            readers.add(reader);
        }

        start.countDown();
        for (Thread reader : readers) {
            reader.join(30_000);
            assertFalse(reader.isAlive(), reader.getName() + " did not finish");
        }

        Throwable thrown = failure.get();
        assertNull(thrown, () -> "a concurrent reader failed: " + thrown);
    }

    private XMLDomMapleData root(String fileName) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, fixture(fileName.substring(0, fileName.indexOf('.'))));
        return new XMLDomMapleData(new FileInputStream(file.toFile()), tempDir);
    }

    private static Object lockOf(Object data) throws Exception {
        Field field = XMLDomMapleData.class.getDeclaredField("domLock");
        field.setAccessible(true);
        return field.get(data);
    }

    private static String fixture(String imageName) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<imgdir name=\"").append(imageName).append("\">\n");
        for (int i = 0; i < DIRS; i++) {
            xml.append("  <imgdir name=\"0400").append(String.format("%04d", i)).append("\">\n")
                    .append("    <imgdir name=\"info\">\n")
                    .append("      <int name=\"price\" value=\"").append(i).append("\"/>\n")
                    .append("      <string name=\"name\" value=\"item-").append(i).append("\"/>\n")
                    .append("    </imgdir>\n")
                    .append("  </imgdir>\n");
        }
        return xml.append("</imgdir>\n").toString();
    }
}
