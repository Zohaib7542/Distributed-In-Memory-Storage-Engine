import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

// 1. The Doubly Linked List Node
class Node {
    String key;
    String value;
    Node prev;
    Node next;

    public Node(String key, String value) {
        this.key = key;
        this.value = value;
    }
}

// 2. The Expiry Object for the Min-Heap
class ExpiryItem implements Comparable<ExpiryItem> {
    String key;
    long expiryTime;

    public ExpiryItem(String key, long expiryTime) {
        this.key = key;
        this.expiryTime = expiryTime;
    }

    @Override
    public int compareTo(ExpiryItem other) {
        return Long.compare(this.expiryTime, other.expiryTime);
    }
}

// 3. The Core Thread-Safe Engine
public class AeroCache {
    private final Map<String, Node> cache;
    private final int capacity;
    
    // Dummy head and tail for the Doubly Linked List
    private final Node head;
    private final Node tail;
    
    // Min-Heap to track the soonest expiring keys
    private final PriorityQueue<ExpiryItem> ttlHeap;

    public AeroCache(int capacity) {
        this.capacity = capacity;
        this.cache = new HashMap<>();
        this.ttlHeap = new PriorityQueue<>();
        
        this.head = new Node("HEAD", "HEAD");
        this.tail = new Node("TAIL", "TAIL");
        head.next = tail;
        tail.prev = head;

        startCleanupThread();
    }

    // --- Core Cache Operations (Synchronized for Thread Safety) ---

    public synchronized String get(String key) {
        if (!cache.containsKey(key)) return null;

        Node node = cache.get(key);
        moveToHead(node);
        return node.value;
    }

    public synchronized void put(String key, String value, long ttlMilliseconds) {
        if (cache.containsKey(key)) {
            Node node = cache.get(key);
            node.value = value;
            moveToHead(node);
        } else {
            if (cache.size() >= capacity) {
                evictLRU();
            }
            Node newNode = new Node(key, value);
            cache.put(key, newNode);
            addToHead(newNode);
        }

        // Add to Min-Heap if a TTL is provided (greater than 0)
        if (ttlMilliseconds > 0) {
            long expiryTime = System.currentTimeMillis() + ttlMilliseconds;
            ttlHeap.offer(new ExpiryItem(key, expiryTime));
        }
    }

    // --- Linked List Helpers ---

    private void addToHead(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        addToHead(node);
    }

    private void evictLRU() {
        Node leastRecentlyUsed = tail.prev;
        removeNode(leastRecentlyUsed);
        cache.remove(leastRecentlyUsed.key);
    }

    // --- The Background Cleanup Thread ---

    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    synchronized (this) {
                        long now = System.currentTimeMillis();
                        // Check the top of the heap. If the time has passed, evict it.
                        while (!ttlHeap.isEmpty() && ttlHeap.peek().expiryTime <= now) {
                            ExpiryItem expiredItem = ttlHeap.poll();
                            if (cache.containsKey(expiredItem.key)) {
                                Node nodeToRemove = cache.get(expiredItem.key);
                                removeNode(nodeToRemove);
                                cache.remove(expiredItem.key);
                                System.out.println("Evicted expired key: " + expiredItem.key);
                            }
                        }
                    }
                    // Sleep for 100ms so we don't fry the CPU with an infinite loop
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        // Setting as Daemon ensures this thread dies automatically when the main server stops
        cleanupThread.setDaemon(true); 
        cleanupThread.start();
    }
    // Add inside AeroCache.java
    public synchronized java.util.Map<String, String> getAll() {
        java.util.Map<String, String> snapshot = new java.util.HashMap<>();
        // Iterate over the HashMap to get all current valid keys
        for (java.util.Map.Entry<String, Node> entry : cache.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().value);
        }
        return snapshot;
    }

    public synchronized void remove(String key) {
        if (cache.containsKey(key)) {
            Node nodeToRemove = cache.get(key);
            removeNode(nodeToRemove); // Detach from Doubly Linked List
            cache.remove(key);        // Remove from HashMap
        }
    }
}