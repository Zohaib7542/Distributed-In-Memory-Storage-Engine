import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 1. The Auto-Healing Hash Ring
class HashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodes;
    private final MessageDigest md;

    public HashRing(int virtualNodes) {
        this.virtualNodes = virtualNodes;
        try {
            this.md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not supported", e);
        }
    }
    // Add this inside the HashRing class
    public synchronized java.util.Set<String> getActiveNodes() {
        // Returns a unique list of all physical nodes currently in the ring
        return new java.util.HashSet<>(ring.values());
    }

    // Added synchronized to prevent thread collisions if multiple clients trigger a heal
    public synchronized void addNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = generateHash(node + "-VNODE-" + i);
            ring.put(hash, node);
        }
        System.out.println("Added node: " + node);
    }

    // NEW: Erase the node and all its virtual copies from the ring
    public synchronized void removeNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = generateHash(node + "-VNODE-" + i);
            ring.remove(hash);
        }
        System.out.println("ALERT: Removed dead node from ring: " + node);
    }

    public synchronized String getServer(String key) {
        if (ring.isEmpty()) return null;
        long hash = generateHash(key);
        SortedMap<Long, String> tailMap = ring.tailMap(hash);
        long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        return ring.get(nodeHash);
    }

    public synchronized boolean isEmpty() {
        return ring.isEmpty();
    }

    private long generateHash(String key) {
        md.reset();
        md.update(key.getBytes());
        byte[] digest = md.digest();
        long h = 0;
        for (int i = 0; i < 4; i++) {
            h <<= 8;
            h |= ((int) digest[i]) & 0xFF;
        }
        return h & 0xFFFFFFFFL;
    }
}

// 2. The Router Server
public class CacheRouter {
    private static final int ROUTER_PORT = 8080;
    private static final HashRing hashRing = new HashRing(3);

    public static void main(String[] args) {
        hashRing.addNode("localhost:8081");
        hashRing.addNode("localhost:8082");
        hashRing.addNode("localhost:8083");

        ExecutorService threadPool = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(ROUTER_PORT)) {
            System.out.println("Self-Healing Router started on port " + ROUTER_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                String[] parts = inputLine.trim().split("\\s+");
                if (parts.length == 0) continue;

                String command = parts[0].toUpperCase();

                
                if (command.equals("CLUSTER_ADD") && parts.length == 2) {
                    String newNode = parts[1];
                    
                    // 1. Get a snapshot of who is currently in the ring BEFORE adding the new node
                    java.util.Set<String> oldNodes = hashRing.getActiveNodes();
                    
                    // 2. Add the new node to the Hash Ring
                    hashRing.addNode(newNode);
                    
                    // 3. Trigger the background migration
                    rebalanceData(oldNodes, newNode);
                    
                    out.println("OK: Added " + newNode + " and rebalanced cluster data.");
                    continue;
                }
                else if (command.equals("CLUSTER_STATUS")) {
                    out.println("ACTIVE NODES: " + hashRing.getActiveNodes());
                    continue; 
                }
                if (parts.length < 2) continue;

                String key = parts[1];
                boolean success = false;

                // NEW: The Retry Loop
                while (!success && !hashRing.isEmpty()) {
                    String targetServer = hashRing.getServer(key);
                    if (targetServer == null) break;

                    try {
                        // Attempt to forward. If it crashes, it jumps to the catch block
                        String response = forwardCommand(targetServer, inputLine);
                        out.println(response);
                        success = true; 
                    } catch (IOException e) {
                        // The network request failed. Heal the ring and try again immediately.
                        hashRing.removeNode(targetServer);
                    }
                }

                if (!success) {
                    out.println("CRITICAL FAULT: All backend nodes are down.");
                }
            }
        } catch (IOException e) {
            System.out.println("Client disconnected.");
        }
    }

    // CHANGED: We no longer swallow the exception here. We throw it up to the Retry Loop.
    private static String forwardCommand(String server, String command) throws IOException {
        String[] hostPort = server.split(":");
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        try (Socket backendSocket = new Socket(host, port);
             PrintWriter backendOut = new PrintWriter(backendSocket.getOutputStream(), true);
             BufferedReader backendIn = new BufferedReader(new InputStreamReader(backendSocket.getInputStream()))) {
            
            backendOut.println(command);
            return backendIn.readLine();
        }
    }
    private static void rebalanceData(java.util.Set<String> oldNodes, String newNode) {
        System.out.println("Starting background data rebalance for new node: " + newNode);
        
        for (String oldNode : oldNodes) {
            try {
                // Ask the old node for all its data
                String dump = forwardCommand(oldNode, "DUMP_ALL");
                if (dump == null || dump.equals("(empty)") || dump.startsWith("ERROR")) continue;
                
                String[] pairs = dump.split(",");
                for (String pair : pairs) {
                    if (pair.isEmpty()) continue;
                    
                    String[] kv = pair.split(":");
                    if (kv.length != 2) continue;
                    String key = kv[0];
                    String val = kv[1];
                    
                    // Check the Hash Ring: Does this key belong to the new node now?
                    String targetNow = hashRing.getServer(key);
                    
                    if (targetNow.equals(newNode)) {
                        // It belongs to the new node! Migrate it.
                        forwardCommand(newNode, "SET " + key + " " + val); // Write to new
                        forwardCommand(oldNode, "DEL " + key);             // Delete from old
                        System.out.println("Migrated key '" + key + "' from " + oldNode + " to " + newNode);
                    }
                }
            } catch (IOException e) {
                System.out.println("Skipped rebalancing from dead node: " + oldNode);
            }
        }
        System.out.println("Rebalancing complete.");
    }
}