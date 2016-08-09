/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.examples.paging;

import com.datastax.driver.core.*;
import com.google.common.base.Charsets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * A stateless web UI that displays paginated results for a CQL query.
 * Navigation is bidirectional, and you can jump to a random page (by modifying the URL).
 * <p/>
 * Cassandra does not support offset queries (see https://issues.apache.org/jira/browse/CASSANDRA-6511), so we emulate
 * it by restarting from the beginning each time, and iterating through the results until we reach the requested page.
 * This is fundamentally inefficient (O(n) in the number of rows skipped), but the tradeoff might be acceptable for some
 * use cases; for example, if you show 10 results per page and you think users are unlikely to browse past page 10,
 * you only need to retrieve at most 100 rows.
 * <p/>
 * Preconditions:
 * - a Cassandra cluster is running and accessible through the contacts points identified by CONTACT_POINTS and
 * CASSANDRA_PORT;
 * - port HTTP_PORT is available.
 * <p/>
 * Side effects:
 * - creates a new keyspace "examples" in the cluster. If a keyspace with this name already exists, it will be reused;
 * - creates a table "examples.random_paging_web_ui". If it already exists, it will be reused;
 * - inserts data in the table;
 * - launches a web server listening on HTTP_PORT.
 */
public class RandomPagingWebUi {
    static final String[] CONTACT_POINTS = {"127.0.0.1"};
    static final int CASSANDRA_PORT = 9042;

    static final int HTTP_PORT = 8080;

    // How many results are displayed on each page in your UI
    static final int ITEMS_PER_PAGE = 10;
    // How many rows the driver will retrieve at a time.
    // This is set artificially low for the sake of this example. Unless your rows are very large, you can probably use
    // a much higher value (the driver's default is 5000).
    static final int FETCH_SIZE = 60;

    public static void main(String[] args) throws Exception {
        Cluster cluster = null;
        try {
            cluster = Cluster.builder()
                    .addContactPoints(CONTACT_POINTS).withPort(CASSANDRA_PORT)
                    .build();
            Session session = cluster.connect();

            createSchema(session);
            populateSchema(session);

            HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            server.createContext("/videos", new VideosHandler(session));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            server.setExecutor(executor);
            server.start();

            System.out.printf("Service started on http://localhost:%d/videos, press Enter key to stop%n", HTTP_PORT);
            System.in.read();
            System.out.println("Stopping");

            server.stop(0);
            executor.shutdownNow();
        } finally {
            if (cluster != null) cluster.close();
        }
    }

    private static void createSchema(Session session) {
        session.execute("CREATE KEYSPACE IF NOT EXISTS examples " +
                "WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        session.execute("CREATE TABLE IF NOT EXISTS examples.random_paging_web_ui(" +
                "user_id int, " +
                "added timestamp, " +
                "video_id int, title text, " +
                "PRIMARY KEY (user_id, added, video_id)" +
                ") WITH CLUSTERING ORDER BY (added DESC, video_id ASC)");
    }

    private static void populateSchema(Session session) throws Exception {
        long firstAdded = new SimpleDateFormat(DATE_FORMAT).parse("2016-06-01").getTime();
        for (int i = 0; i < 100; i++) {
            Date added = new Date(firstAdded + MILLISECONDS.convert(i, DAYS));
            session.execute("INSERT INTO examples.random_paging_web_ui (user_id, added, video_id, title) " +
                            "VALUES (0, ?, ?, ?)",
                    added, i, "video " + i);
        }
    }

    /**
     * Handles requests to /videos[?page=xxx]
     */
    static class VideosHandler implements HttpHandler {
        private final Pager pager;
        private final PreparedStatement selectVideos;

        VideosHandler(Session session) {
            this.pager = new Pager(session, ITEMS_PER_PAGE);
            this.selectVideos = session.prepare("SELECT added, video_id, title FROM examples.random_paging_web_ui WHERE user_id = 0");
        }

        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            try {
                StringBuilder response = new StringBuilder();

                Statement statement = selectVideos.bind().setFetchSize(FETCH_SIZE);
                int page = extractPageFromQueryString(httpExchange);
                ResultSet rs = pager.skipTo(statement, page);

                boolean empty = rs.isExhausted();
                if (empty) {
                    renderNoResults(response);
                } else {
                    renderTableHeader(response);
                    SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT);
                    int remaining = ITEMS_PER_PAGE;
                    for (Row row : rs) {
                        renderTr(response, row.getInt("video_id"), row.getString("title"), dateFormat.format(row.getTimestamp("added")));
                        if (--remaining == 0)
                            break;
                    }
                    renderTableFooter(response);
                }

                if (page > 1) {
                    renderPreviousPageLink(response, page - 1);
                }
                if (!empty) {
                    renderNextPageLink(response, page + 1);
                }

                reply(httpExchange, 200, response.toString());
            } catch (Throwable t) {
                // Server-side logs
                t.printStackTrace();

                // Include the error message in the response for demonstration purposes. In a production application,
                // you probably don't want to give a potential attacker any information.
                reply(httpExchange, 500, "Server error: " + t.getMessage());
            }
        }

        // For simplicity, we only handle the query string "?page=xxx"
        // Any other format (no query string, additional parameters...) will lead back to the first page
        private int extractPageFromQueryString(HttpExchange httpExchange) {
            String query = httpExchange.getRequestURI().getQuery();
            if (query == null) {
                return 1;
            }
            Matcher matcher = QUERY_PATTERN.matcher(query);
            return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
        }

        private static final Pattern QUERY_PATTERN = Pattern.compile("page=(\\d+)");

        private void renderNoResults(StringBuilder response) {
            response.append("<div>No more results</div>");
        }

        private void renderTableHeader(StringBuilder response) {
            response
                    .append("<style>")
                    .append("table { border-collapse: collapse }")
                    .append("table, th, td { border: 1px solid black }")
                    .append("tr:nth-child(even) { background-color: lightgray }")
                    .append("td { padding: 0 30px 0 30px }")
                    .append("</style>")
                    .append("<table><tr><th>Id</th><th>Title</th><th>Added</th></tr>");
        }

        private void renderTr(StringBuilder response, int id, String title, String added) {
            response.append("<tr><td>")
                    .append(id)
                    .append("</td><td>")
                    .append(title)
                    .append("</td><td>")
                    .append(added)
                    .append("</td></tr>");
        }

        private void renderTableFooter(StringBuilder response) {
            response.append("</table>");
        }

        private void renderPreviousPageLink(StringBuilder response, int previousPage) {
            response.append(String.format("<a href=\"/videos?page=%d\">Previous</a>&nbsp;&nbsp;&nbsp;", previousPage));
        }

        private void renderNextPageLink(StringBuilder response, int nextPage) {
            response.append(String.format("<a href=\"/videos?page=%d\">Next</a>", nextPage));
        }

        private static void reply(HttpExchange httpExchange, int status, String body) throws IOException {
            httpExchange.getResponseHeaders().add("Content-type", "text/html; charset=utf-8");
            httpExchange.sendResponseHeaders(status, body.length());
            OutputStream os = httpExchange.getResponseBody();
            os.write(body.getBytes(Charsets.UTF_8));
            os.close();
        }
    }

    /**
     * Helper class to emulate random paging.
     * <p/>
     * Note that it MUST be stateless, because it is cached as a field in our HTTP handler.
     */
    static class Pager {
        private final Session session;
        private final int pageSize;

        Pager(Session session, int pageSize) {
            this.session = session;
            this.pageSize = pageSize;
        }

        ResultSet skipTo(Statement statement, int displayPage) {
            // Absolute index of the first row we want to display on the web page. Our goal is that rs.next() returns
            // that row.
            int targetRow = (displayPage - 1) * pageSize;

            ResultSet rs = session.execute(statement);
            // Absolute index of the next row returned by rs (if it is not exhausted)
            int currentRow = 0;
            int fetchedSize = rs.getAvailableWithoutFetching();
            byte[] nextState = rs.getExecutionInfo().getPagingStateUnsafe();

            // Skip protocol pages until we reach the one that contains our target row.
            // For example, if the first query returned 60 rows and our target is row number 90, we know we can skip
            // those 60 rows directly without even iterating through them.
            // This part is optional, we could simply iterate through the rows with the for loop below, but that's
            // slightly less efficient because iterating each row involves a bit of internal decoding.
            while (fetchedSize > 0 && nextState != null && currentRow + fetchedSize < targetRow) {
                statement.setPagingStateUnsafe(nextState);
                rs = session.execute(statement);
                currentRow += fetchedSize;
                fetchedSize = rs.getAvailableWithoutFetching();
                nextState = rs.getExecutionInfo().getPagingStateUnsafe();
            }

            if (currentRow < targetRow) {
                for (Row row : rs) {
                    if (++currentRow == targetRow) break;
                }
            }
            // If targetRow is past the end, rs will be exhausted.
            // This means you can request a page past the end in the web UI (e.g. request page 12 while there are only
            // 10 pages), and it will show up as empty.
            // One improvement would be to detect that and take a different action, for example redirect to page 10 or
            // show an error message, this is left as an exercise for the reader.
            return rs;
        }
    }

    private static final String DATE_FORMAT = "yyyy-MM-dd";
}
