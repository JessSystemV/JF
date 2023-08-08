package dev.jess.forum;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static List<Post> posts = new ArrayList<>();

    private static int RATE_LIMIT_INTERVAL = 20000; // 20 seconds in milliseconds

    private static final Map<String, Long> lastActionTimes = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        int port = 80;
        try {
            port = Integer.parseInt(args[0]);
            RATE_LIMIT_INTERVAL = Integer.parseInt(args[1]);
        }catch (NumberFormatException | ArrayIndexOutOfBoundsException e){}
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new MessageHandler());
        server.createContext("/teapot", new TeapotHandler());
        server.createContext("/posts/", new IndividualPostHandler()); // Add this line
        server.createContext("/addcomment/", new AddCommentHandler());
        server.setExecutor(null);
        posts.add(new Post(
                "Root",
                "This is the first post! " +
                        "(br)" +
                        "It has been here since the start. use ( br ) without the spaces for a line break"));
        load();
        System.out.println("Loaded ("+(posts.size()-1)+") posts");
        server.start();
        System.out.println("Server is listening on port "+port);
    }

    public static void save() {
        HashMap<String, String> postsMap = new HashMap<>();
        for(int i = 0; i < posts.size(); i++) {
            Post post = posts.get(i);
            HashMap<String, String> postMap = new HashMap<>();
            postMap.put("username", post.username.replace("\r\n", "\n").replace("\r", "\n"));
            postMap.put("content", post.content.replace("\r\n", "\n").replace("\r", "\n"));
            HashMap<String, String> commentsMap = new HashMap<>();
            List<Comment> comments = post.comments;
            for(int j = 0; j < comments.size(); j++) {
                Comment comment = comments.get(j);
                HashMap<String, String> commentMap = new HashMap<>();
                commentMap.put("username", comment.username.replace("\r\n", "\n").replace("\r", "\n"));
                commentMap.put("content", comment.content.replace("\r\n", "\n").replace("\r", "\n"));
                commentsMap.put(""+j, mapToString(commentMap));
            }
            postMap.put("comments", mapToString(commentsMap));
            postsMap.put(""+i, mapToString(postMap));
        }
        try {
            PrintStream printStream = new PrintStream("forum.jdb");
            printStream.print(mapToString(postsMap));
            printStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void load() throws IOException {

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream("forum.jdb")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String posts = "";
        String s;
        while((s = reader.readLine()) != null) posts += s + "\n";
        Map<String, String> postsMap = stringToMap(posts);
        if (postsMap != null) {
            System.out.println("not null");
            for (int i = 0; ; i++) {

                String postString = postsMap.get("" + i);

                if (postString == null) break;

                Map<String, String> postMap = stringToMap(postString);
                String username = postMap.get("username");
                String content = postMap.get("content");
                String commentsStr = postMap.get("comments");

                List<Comment> comments = new ArrayList<>();

                if(commentsStr != null) {
                    Map<String, String> commentsMap = stringToMap(commentsStr);
                    for (int j = 0; ; j++) {
                        String commentStr = commentsMap.get("" + j);
                        if (commentStr == null) {
                            break;
                        }
                        Map<String, String> commentMap = stringToMap(commentStr);
                        if (commentMap != null) {
                            String commentUsername = commentMap.get("username");
                            String commentContent = commentMap.get("content");
                            Comment comment = new Comment(commentUsername, commentContent);
                            comments.add(comment);
                        }
                    }
                }

                if(username.equalsIgnoreCase("root")){
                    continue;
                }
                Post post = new Post(username, content, comments);
                Main.posts.add(post);
            }
        }
    }

    static class Post {
        String username;
        String content;

        List<Comment> comments = new ArrayList<>();

        public Post(String username, String content) {
            this.username = username;
            this.content = content;
        }

        public Post(String username, String content, List<Comment> comments) {
            this.username = username;
            this.content = content;
            this.comments = comments;
        }
    }

    static class MessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestMethod = exchange.getRequestMethod();

            if(rateLimit(exchange)) {
                return;
            }

            if (requestMethod.equalsIgnoreCase("GET")) {
                handleGetRequest(exchange);
            } else if (requestMethod.equalsIgnoreCase("POST")) {
                handlePostRequest(exchange);
            }
        }

        private void handleGetRequest(HttpExchange exchange) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("<html><head><style>")
                    .append("body { font-family: Arial, sans-serif; background-color: #DDA0DD; color: #4A235A; }")
                    .append("a:link, a:visited, a:hover, a:active { color: rebeccapurple; background-color: transparent; text-decoration: none; }")
                    .append(".header-container { text-align: center; }") // Center-align the header
                    .append(".form-container { text-align: center; }")   // Center-align the form
                    .append(".post-container { text-align: left; }")     // Align posts to the left
                    .append(".post { border: 2px solid rebeccapurple; padding: 10px; margin: 10px; min-width: 80px; }")
                    .append("</style></head><body>")
                    .append("<div class=\"header-container\"><h2>Message Board</h2></div>")
                    .append("<form method=\"post\" action=\"/\">"
                            + "<input type=\"text\" name=\"username\" placeholder=\"Username\"><br>"
                            + "<textarea name=\"message\" rows=\"4\" cols=\"50\" placeholder=\"Comment\"></textarea><br>"
                            + "<input type=\"submit\" value=\"Submit Post\">")


                    .append("<div class=\"post-container\">");

            synchronized (posts) {
                for (int postId = posts.size(); postId >= 1; postId--) {
                    Post post = posts.get(postId - 1);
                    response.append("<div class=\"post\"><p><strong><a href=\"/posts/").append(postId).append("\">")
                            .append(post.username).append("</a></strong><br/>")
                            .append("&emsp;").append(markdown(post.content)).append("</p></div>");
                }
            }

            response.append("</div></body></html>");

            sendResponse(exchange, response.toString());
        }

        private void handlePostRequest(HttpExchange exchange) throws IOException {
            String requestBody = Utils.getRequestBody(exchange);
            String[] keyValuePairs = requestBody.split("&");
            String username = null;
            String message = null;

            for (String pair : keyValuePairs) {
                String[] keyAndValue = pair.split("=");
                if (keyAndValue.length == 2) {
                    String key = keyAndValue[0];
                    String value = keyAndValue[1].replace("+", " ");

                    if (key.equals("username")) {
                        username = sanitize(decodeUTF8(value));
                    } else if (key.equals("message")) {
                        message = sanitize(decodeUTF8(value));
                    }
                }
            }

            if (username != null && message != null) {
                synchronized (posts) {
                    posts.add(new Post(username, message));
                }
            }

            save();

            sendRedirect(exchange, "/");
        }

        private void sendResponse(HttpExchange exchange, String response) throws IOException {
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }

        private void sendRedirect(HttpExchange exchange, String location) throws IOException {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
        }
    }


    static class TeapotHandler implements HttpHandler {
        volatile int uses = 0;
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            uses++;

            Thread refill = new Thread(()-> {
                try {
                    wait(30000);
                    uses=0;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            if(uses>4) {
                if(!refill.isAlive()){
                    refill.start();
                    sendResponse(exchange, "I'm an empty teapot (please wait 30 seconds)", 503);
                }
            }else {
                sendResponse(exchange, "I'm a teapot", 418);
            }
        }

        private void sendResponse(HttpExchange exchange, String response, int rcode) throws IOException {
            exchange.sendResponseHeaders(rcode, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static class Comment {
        public String username;
        public String content;


        public Comment(String username, String content) {
            this.username = username;
            this.content = content;
        }
    }

    static class IndividualPostHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if(rateLimit(exchange)) {
                return;
            }
            String requestURI = exchange.getRequestURI().toString();
            String postIdStr = requestURI.substring(requestURI.lastIndexOf('/') + 1);

            try {
                int postId = Integer.parseInt(postIdStr);
                String postContent = getPostContent(postId);
                sendResponse(exchange, postContent);
            } catch (NumberFormatException e) {
                sendResponse(exchange, "403 Forbidden");
            }
        }

        private String getPostContent(int postId) {
            synchronized (posts) {
                if (postId > 0 && postId <= posts.size()) {
                    Post post = posts.get(postId - 1);

                    String postContent = "<strong>" + post.username + "</strong><br/>" +
                            "&emsp;" + post.content;

                    StringBuilder commentsHtml = new StringBuilder();
                    for (Comment comment : post.comments) {
                        commentsHtml.append("<div class=\"comment\">")
                                .append("<p><strong>").append(comment.username).append("</strong><br/>")
                                .append("&emsp;").append(markdown(comment.content)).append("</p>")
                                .append("</div>");
                    }

                    String response = "<html><head><style>"
                            + "body { font-family: Arial, sans-serif; background-color: #DDA0DD; color: #4A235A; }"
                            + ".post { border: 2px solid rebeccapurple; padding: 10px; margin: 10px; min-width: 80px; }"
                            + ".comment { border: 2px solid rebeccapurple; padding: 10px; margin: 10px; }"
                            + "</style></head><body>"
                            + "<div class=\"post\">"
                            + "<p>" + markdown(postContent) + "</p>"
                            + "</div>"
                            + "<div class=\"comments\">"
                            + "<h3>Comments</h3>"
                            + commentsHtml
                            + "</div>"
                            + "<form method=\"post\" action=\"/addcomment/" + postId + "\">"
                            + "<input type=\"text\" name=\"username\" placeholder=\"Username\"><br>"
                            + "<textarea name=\"comment\" rows=\"4\" cols=\"50\" placeholder=\"Comment\"></textarea><br>"
                            + "<input type=\"submit\" value=\"Submit Comment\">"
                            + "</form>"
                            + "</body></html>";

                    return response;
                }
            }
            return "404 Post Not Found";
        }

        private void sendResponse(HttpExchange exchange, String response) throws IOException {
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }


    static class AddCommentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestURI = exchange.getRequestURI().toString();
            String postIdStr = requestURI.substring(requestURI.lastIndexOf('/') + 1);

            if(rateLimit(exchange)) {
                return;
            }
            try {
                int postId = Integer.parseInt(postIdStr);

                String requestMethod = exchange.getRequestMethod();
                if ("POST".equals(requestMethod)) {
                    InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(isr);
                    String requestBody = br.readLine();

                    String username = extractFormParameter(requestBody, "username");
                    String comment = extractFormParameter(requestBody, "comment");

                    if (username != null && comment != null && !username.isEmpty() && !comment.isEmpty()) {
                        synchronized (posts) {
                            if (postId > 0 && postId <= posts.size()) {
                                Post post = posts.get(postId - 1);
                                post.comments.add(new Comment(username, comment));
                            }
                        }
                    }
                }
                save();

                // Redirect back to the post page
                exchange.getResponseHeaders().add("Location", "/posts/" + postId);
                exchange.sendResponseHeaders(302, -1); // Redirect
            } catch (NumberFormatException e) {
                sendResponse(exchange, "403 Forbidden");
            }
        }

        private String extractFormParameter(String requestBody, String paramName) {
            String[] bodyParams = requestBody.split("&");
            for (String param : bodyParams) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2 && keyValue[0].equals(paramName)) {
                    return URLDecoder.decode(keyValue[1]);
                }
            }
            return null;
        }



        private void sendResponse(HttpExchange exchange, String response) throws IOException {
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }



    public static String markdown(String markdown) {
        // Convert line breaks
        String html = markdown
                .replace("(br)", "<br/>&emsp;")
                .replace("\n","<br/>&emsp;"); //Line Break!
        return html;
    }

    public static String decodeUTF8(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%20"), String.valueOf(StandardCharsets.UTF_8));
        }
        catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Too old OS");
        }
    }

    public static String sanitize(String input) {
        // Replace dangerous HTML tags with empty string
        String dangerousTagsPattern = "<(script|style|iframe|object|embed|applet|meta|base|form)[^>]*>.*?</\\1>";
        Pattern htmlPattern = Pattern.compile(dangerousTagsPattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher htmlMatcher = htmlPattern.matcher(input);
        String sanitizedHtml = htmlMatcher.replaceAll("");
        // Remove dangerous JavaScript constructs including onclick, onload, and javascript:
        String dangerousJsPattern = "(?i)(\\s*(onclick|onload)\\s*=\\s*([\"'])?[^>]*\\3)|javascript:";
        Pattern jsPattern = Pattern.compile(dangerousJsPattern);
        Matcher jsMatcher = jsPattern.matcher(sanitizedHtml);
        String sanitizedJs = jsMatcher.replaceAll("");
        sanitizedJs = sanitizedJs.replace("{","");
        sanitizedJs = sanitizedJs.replace("}","");
        return sanitizedJs.replace("\r\n", "\n").replace("\r", "\n");
    }

    static class Utils {
        static String getRequestBody(HttpExchange exchange) throws IOException {
            StringBuilder requestBody = new StringBuilder();
            InputStream inputStream = exchange.getRequestBody();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String line;

            while ((line = bufferedReader.readLine()) != null) {
                requestBody.append(line);
            }

            return requestBody.toString();
        }
    }

    public static boolean rateLimit(HttpExchange exchange) throws IOException {
        String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();

        // Check the last time this IP performed an action
        long currentTime = System.currentTimeMillis();
        long lastActionTime = lastActionTimes.getOrDefault(clientIP, 0L);

        if (currentTime - lastActionTime < RATE_LIMIT_INTERVAL) {
            sendResponse(exchange, "429 Too Many Requests");
            return true;
        }

        // Update the last action time for this IP
        lastActionTimes.put(clientIP, currentTime);
        return false;
    }

    private static void sendResponse(HttpExchange exchange, String response) throws IOException {
        exchange.sendResponseHeaders(200, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }



    //Credit to TudbuT: https://github.com/TudbuT (for being the best gf ever and helping perpetuate my laziness)
    //Something like this but slightly less readable once saved can probably be found at
    //https://github.com/TudbuT/tuddylib

    public static Map<String, String> stringToMap(String mapStringParsable) {
        Map<String, String> map = new HashMap<>();

        String[] splitTiles = mapStringParsable.split("\n");
        for (int i = 0; i < splitTiles.length; i++) {
            String tile = splitTiles[i];
            String[] splitTile = tile.split(":");
            if (tile.contains(":")) {
                if (splitTile.length == 2)
                    map.put(
                            splitTile[0]
                                    .replaceAll("%I", ":")
                                    .replaceAll("%N", "\n")
                                    .replaceAll("%P", "%"),
                            splitTile[1].equals("%0")
                                    ? null
                                    : splitTile[1]
                                    .replaceAll("%I", ":")
                                    .replaceAll("%N", "\n")
                                    .replaceAll("%P", "%"));
                else
                    map.put(
                            splitTile[0]
                                    .replaceAll("%I", ":")
                                    .replaceAll("%N", "\n")
                                    .replaceAll("%P", "%"),
                            "");
            }
        }

        return map;
    }

    public static String mapToString(Map<String, String> map) {
        StringBuilder r = new StringBuilder();

        for (String key : map.keySet().toArray(new String[0])) {

            r.append(key.replaceAll("%", "%P").replaceAll("\n", "%N").replaceAll(":", "%I"))
                    .append(":")
                    .append(
                            map.get(key) == null
                                    ? "%0"
                                    : map.get(key)
                                    .replaceAll("%", "%P")
                                    .replaceAll("\n", "%N")
                                    .replaceAll(":", "%I"))
                    .append("\n");
        }

        return r.toString();
    }
}