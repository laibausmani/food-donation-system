import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class FoodDonationServer {
    private static final int PORT = 8080;
    private static final String USERS_FILE = "users.txt";
    private static final String FOOD_FILE = "food.txt";
    private static final String REQUESTS_FILE = "requests.txt";
    
    // Thread-safe data structures with locks
    private static final ReentrantReadWriteLock userLock = new ReentrantReadWriteLock(true);
    private static final ReentrantReadWriteLock foodLock = new ReentrantReadWriteLock(true);
    private static final ReentrantReadWriteLock requestLock = new ReentrantReadWriteLock(true);
    
    // Thread pool for handling concurrent requests
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);
    
    // Semaphore to limit concurrent file operations
    private static final Semaphore fileSemaphore = new Semaphore(5, true);
    
    public static void main(String[] args) {
        try {
            initializeFiles();
            
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(executorService);
            
            // API endpoints
            server.createContext("/api/register", new RegisterHandler());
            server.createContext("/api/login", new LoginHandler());
            server.createContext("/api/food/add", new AddFoodHandler());
            server.createContext("/api/food/list", new ListFoodHandler());
            server.createContext("/api/food/claim", new ClaimFoodHandler());
            server.createContext("/api/request/add", new AddRequestHandler());
            server.createContext("/api/request/list", new ListRequestsHandler());
            server.createContext("/api/request/cancel", new CancelRequestHandler());
            server.createContext("/api/request/delete", new DeleteRequestHandler());
            server.createContext("/api/request/fulfill", new FulfillRequestHandler());
            server.createContext("/", new StaticFileHandler());
            
            server.start();
            System.out.println("Server started on port " + PORT);
            System.out.println("Open http://localhost:" + PORT + " in your browser");
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void initializeFiles() {
        try {
            for (String file : Arrays.asList(USERS_FILE, FOOD_FILE, REQUESTS_FILE)) {
                if (!Files.exists(Paths.get(file))) {
                    Files.createFile(Paths.get(file));
                }
            }
        } catch (IOException e) {
            System.err.println("Error initializing files: " + e.getMessage());
        }
    }
    
    // Helper method demonstrating proper lock ordering to prevent deadlocks
    private static void performMultiResourceOperation(Runnable operation) {
        // Always acquire locks in the same order: user -> food -> request
        // This prevents circular wait deadlock
        boolean userAcquired = false;
        boolean foodAcquired = false;
        try {
            userAcquired = userLock.writeLock().tryLock(5, TimeUnit.SECONDS);
            if (!userAcquired) throw new RuntimeException("Timeout acquiring user lock");
            
            foodAcquired = foodLock.writeLock().tryLock(5, TimeUnit.SECONDS);
            if (!foodAcquired) throw new RuntimeException("Timeout acquiring food lock");
            
            operation.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Operation interrupted");
        } finally {
            if (foodAcquired) foodLock.writeLock().unlock();
            if (userAcquired) userLock.writeLock().unlock();
        }
    }
    
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String username = params.get("username");
                String password = params.get("password");
                String type = params.get("type");
                
                if (username == null || password == null || type == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing parameters\"}");
                    return;
                }
                
                // Acquire write lock with timeout to prevent deadlock
                if (!userLock.writeLock().tryLock(5, TimeUnit.SECONDS)) {
                    sendResponse(exchange, 503, "{\"error\":\"Server busy, try again\"}");
                    return;
                }
                
                try {
                    // Acquire semaphore for file access
                    fileSemaphore.acquire();
                    
                    try {
                        List<String> users = Files.readAllLines(Paths.get(USERS_FILE));
                        
                        // Check if user exists
                        for (String user : users) {
                            String[] parts = user.split("\\|");
                            if (parts[0].equals(username)) {
                                sendResponse(exchange, 400, "{\"error\":\"Username already exists\"}");
                                return;
                            }
                        }
                        
                        // Add new user
                        String userRecord = username + "|" + password + "|" + type + "\n";
                        Files.write(Paths.get(USERS_FILE), userRecord.getBytes(), 
                                StandardOpenOption.APPEND);
                        
                        sendResponse(exchange, 200, "{\"success\":true,\"username\":\"" + username + "\"}");
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    userLock.writeLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String username = params.get("username");
                String password = params.get("password");
                
                // Use read lock for concurrent reads
                userLock.readLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        List<String> users = Files.readAllLines(Paths.get(USERS_FILE));
                        
                        for (String user : users) {
                            String[] parts = user.split("\\|");
                            if (parts[0].equals(username) && parts[1].equals(password)) {
                                String response = "{\"success\":true,\"username\":\"" + username + 
                                                "\",\"type\":\"" + parts[2] + "\"}";
                                sendResponse(exchange, 200, response);
                                return;
                            }
                        }
                        
                        sendResponse(exchange, 401, "{\"error\":\"Invalid credentials\"}");
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    userLock.readLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class AddFoodHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String donator = params.get("donator");
                String name = params.get("name");
                String quantity = params.get("quantity");
                String location = params.get("location");
                
                foodLock.writeLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        String id = UUID.randomUUID().toString().substring(0, 8);
                        String foodRecord = id + "|" + donator + "|" + name + "|" + 
                                        quantity + "|" + location + "|available\n";
                        
                        Files.write(Paths.get(FOOD_FILE), foodRecord.getBytes(), 
                                StandardOpenOption.APPEND);
                        
                        sendResponse(exchange, 200, "{\"success\":true,\"id\":\"" + id + "\"}");
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    foodLock.writeLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class ListFoodHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                foodLock.readLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        List<String> foods = Files.readAllLines(Paths.get(FOOD_FILE));
                        StringBuilder json = new StringBuilder("[");
                        
                        for (int i = 0; i < foods.size(); i++) {
                            String[] parts = foods.get(i).split("\\|");
                            if (parts.length >= 6 && "available".equals(parts[5])) {
                                json.append("{\"id\":\"").append(parts[0])
                                    .append("\",\"donator\":\"").append(parts[1])
                                    .append("\",\"name\":\"").append(parts[2])
                                    .append("\",\"quantity\":\"").append(parts[3])
                                    .append("\",\"location\":\"").append(parts[4])
                                    .append("\"}");
                                if (i < foods.size() - 1) json.append(",");
                            }
                        }
                        
                        json.append("]");
                        sendResponse(exchange, 200, json.toString().replace(",]", "]"));
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    foodLock.readLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class ClaimFoodHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String foodId = params.get("foodId");
                String receiver = params.get("receiver");
                
                foodLock.writeLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        List<String> foods = Files.readAllLines(Paths.get(FOOD_FILE));
                        List<String> updatedFoods = new ArrayList<>();
                        boolean found = false;
                        
                        for (String food : foods) {
                            String[] parts = food.split("\\|");
                            if (parts[0].equals(foodId) && "available".equals(parts[5])) {
                                // Mark as claimed
                                updatedFoods.add(parts[0] + "|" + parts[1] + "|" + parts[2] + 
                                            "|" + parts[3] + "|" + parts[4] + "|claimed|" + receiver);
                                found = true;
                            } else {
                                updatedFoods.add(food);
                            }
                        }
                        
                        if (found) {
                            Files.write(Paths.get(FOOD_FILE), 
                                    String.join("\n", updatedFoods).getBytes());
                            sendResponse(exchange, 200, "{\"success\":true}");
                        } else {
                            sendResponse(exchange, 404, "{\"error\":\"Food not available\"}");
                        }
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    foodLock.writeLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class AddRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String receiver = params.get("receiver");
                String foodType = params.get("foodType");
                String quantity = params.get("quantity");
                
                requestLock.writeLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        String id = UUID.randomUUID().toString().substring(0, 8);
                        String requestRecord = id + "|" + receiver + "|" + foodType + "|" + 
                                            quantity + "|pending\n";
                        
                        Files.write(Paths.get(REQUESTS_FILE), requestRecord.getBytes(), 
                                StandardOpenOption.APPEND);
                        
                        sendResponse(exchange, 200, "{\"success\":true,\"id\":\"" + id + "\"}");
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    requestLock.writeLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class ListRequestsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requestLock.readLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        List<String> requests = Files.readAllLines(Paths.get(REQUESTS_FILE));
                        StringBuilder json = new StringBuilder("[");
                        
                        for (int i = 0; i < requests.size(); i++) {
                            String[] parts = requests.get(i).split("\\|");
                            if (parts.length >= 5) {
                                json.append("{\"id\":\"").append(parts[0])
                                    .append("\",\"receiver\":\"").append(parts[1])
                                    .append("\",\"foodType\":\"").append(parts[2])
                                    .append("\",\"quantity\":\"").append(parts[3])
                                    .append("\",\"status\":\"").append(parts[4])
                                    .append("\"}");
                                if (i < requests.size() - 1) json.append(",");
                            }
                        }
                        
                        json.append("]");
                        sendResponse(exchange, 200, json.toString());
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    requestLock.readLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class CancelRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String requestId = params.get("requestId");
                String receiver = params.get("receiver");
                
                requestLock.writeLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        List<String> requests = Files.readAllLines(Paths.get(REQUESTS_FILE));
                        List<String> updatedRequests = new ArrayList<>();
                        boolean found = false;
                        
                        for (String request : requests) {
                            String[] parts = request.split("\\|");
                            if (parts[0].equals(requestId) && parts[1].equals(receiver) && 
                                "pending".equals(parts[4])) {
                                // Mark as cancelled
                                updatedRequests.add(parts[0] + "|" + parts[1] + "|" + parts[2] + 
                                                "|" + parts[3] + "|cancelled");
                                found = true;
                            } else {
                                updatedRequests.add(request);
                            }
                        }
                        
                        if (found) {
                            Files.write(Paths.get(REQUESTS_FILE), 
                                    (String.join("\n", updatedRequests) + "\n").getBytes());
                            sendResponse(exchange, 200, "{\"success\":true}");
                        } else {
                            sendResponse(exchange, 404, "{\"error\":\"Request not found or already processed\"}");
                        }
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    requestLock.writeLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class DeleteRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String requestId = params.get("requestId");
                String receiver = params.get("receiver");
                
                if (requestId == null || receiver == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing parameters\"}");
                    return;
                }
                
                requestLock.writeLock().lock();
                try {
                    fileSemaphore.acquire();
                    try {
                        List<String> requests = Files.readAllLines(Paths.get(REQUESTS_FILE));
                        List<String> updatedRequests = new ArrayList<>();
                        boolean found = false;
                        
                        for (String request : requests) {
                            String[] parts = request.split("\\|");
                            // Only delete if it matches ID and receiver, and is not already fulfilled
                            if (parts[0].equals(requestId) && parts[1].equals(receiver)) {
                                // Don't add this request to the updated list (effectively deleting it)
                                found = true;
                            } else {
                                updatedRequests.add(request);
                            }
                        }
                        
                        if (found) {
                            Files.write(Paths.get(REQUESTS_FILE), 
                                    (String.join("\n", updatedRequests) + 
                                    (updatedRequests.isEmpty() ? "" : "\n")).getBytes());
                            sendResponse(exchange, 200, "{\"success\":true}");
                        } else {
                            sendResponse(exchange, 404, "{\"error\":\"Request not found\"}");
                        }
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    requestLock.writeLock().unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class FulfillRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parseParams(body);
                
                String requestId = params.get("requestId");
                String donor = params.get("donor");
                
                if (requestId == null || donor == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing parameters\"}");
                    return;
                }
                
                // Need to acquire locks in proper order to prevent deadlock
                boolean requestAcquired = false;
                try {
                    requestAcquired = requestLock.writeLock().tryLock(5, TimeUnit.SECONDS);
                    if (!requestAcquired) {
                        sendResponse(exchange, 503, "{\"error\":\"Server busy, try again\"}");
                        return;
                    }
                    
                    fileSemaphore.acquire();
                    try {
                        List<String> requests = Files.readAllLines(Paths.get(REQUESTS_FILE));
                        List<String> updatedRequests = new ArrayList<>();
                        boolean found = false;
                        String foodType = "";
                        String quantity = "";
                        
                        for (String request : requests) {
                            String[] parts = request.split("\\|");
                            if (parts[0].equals(requestId) && "pending".equals(parts[4])) {
                                // Mark as fulfilled and add donor info
                                foodType = parts[2];
                                quantity = parts[3];
                                updatedRequests.add(parts[0] + "|" + parts[1] + "|" + parts[2] + 
                                                "|" + parts[3] + "|fulfilled|" + donor);
                                found = true;
                            } else {
                                updatedRequests.add(request);
                            }
                        }
                        
                        if (found) {
                            Files.write(Paths.get(REQUESTS_FILE), 
                                    (String.join("\n", updatedRequests) + "\n").getBytes());
                            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Request fulfilled successfully\"}");
                        } else {
                            sendResponse(exchange, 404, "{\"error\":\"Request not found or already processed\"}");
                        }
                    } finally {
                        fileSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendResponse(exchange, 503, "{\"error\":\"Operation interrupted\"}");
                } finally {
                    if (requestAcquired) {
                        requestLock.writeLock().unlock();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }
    
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";
            
            File file = new File("." + path);
            if (file.exists() && !file.isDirectory()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String contentType = getContentType(path);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                sendResponse(exchange, 404, "File not found");
            }
            exchange.getResponseBody().close();
        }
        
        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            return "text/plain";
        }
    }
    
    private static void sendResponse(HttpExchange exchange, int status, String response) 
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, response.getBytes().length);
        exchange.getResponseBody().write(response.getBytes());
        exchange.getResponseBody().close();
    }
    
    private static Map<String, String> parseParams(String body) {
        Map<String, String> params = new HashMap<>();
        if (body.startsWith("{")) {
            // Simple JSON parsing
            body = body.substring(1, body.length() - 1);
            for (String pair : body.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0].replaceAll("\"", "").trim();
                    String value = kv[1].replaceAll("\"", "").trim();
                    params.put(key, value);
                }
            }
        }
        return params;
    }
}