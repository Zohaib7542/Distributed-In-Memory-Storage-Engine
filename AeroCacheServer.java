import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class AeroCacheServer {
    private static final int CACHE_CAPACITY = 10000;
    private static AeroCache cache;

    public static void main(String[] args) {
        // Read the port from the terminal argument, or default to 8081
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        cache = new AeroCache(CACHE_CAPACITY);
        
        ExecutorService threadPool = Executors.newFixedThreadPool(10); 

        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("AeroCache Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(new ClientHandler(clientSocket, cache));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// ClientHandler remains exactly the same as before
class ClientHandler implements Runnable {
    private final Socket socket;
    private final AeroCache cache;

    public ClientHandler(Socket socket, AeroCache cache) {
        this.socket = socket;
        this.cache = cache;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                String[] parts = inputLine.trim().split("\\s+");
                if (parts.length == 0) continue;
                
                String command = parts[0].toUpperCase();

                if (command.equals("GET") && parts.length == 2) {
                    String value = cache.get(parts[1]);
                    out.println(value != null ? value : "(nil)");
                } 
                else if (command.equals("SET") && parts.length >= 3) {
                    String key = parts[1];
                    String value = parts[2];
                    long ttl = 0;
                    if (parts.length == 4) {
                        try { ttl = Long.parseLong(parts[3]); } 
                        catch (NumberFormatException e) {
                            out.println("ERROR: Invalid TTL");
                            continue;
                        }
                    }
                    cache.put(key, value, ttl);
                    out.println("OK");
                }
                else if (command.equals("DEL") && parts.length == 2) {
                    cache.remove(parts[1]);
                    out.println("OK");
                } 
                else if (command.equals("DUMP_ALL")) {
                    java.util.Map<String, String> allData = cache.getAll();
                    if (allData.isEmpty()) {
                        out.println("(empty)");
                    } else {
                        // Serialize the entire cache into a comma-separated string
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map.Entry<String, String> entry : allData.entrySet()) {
                            sb.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
                        }
                        out.println(sb.toString());
                    }
                }
                else {
                    out.println("ERROR: Invalid Command");
                }
            }
        } catch (IOException e) {
            System.out.println("Client disconnected.");
        }
    }
}