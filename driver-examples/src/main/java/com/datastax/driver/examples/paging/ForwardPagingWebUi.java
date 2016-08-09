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
 * Navigation is forward-only (use the back button of your browser to go backwards).
 * The implementation relies on the paging state returned by Cassandra, and encodes it in HTTP URLs.
 * <p/>
 * Preconditions:
 * - a Cassandra cluster is running and accessible through the contacts points identified by CONTACT_POINTS and
 * CASSANDRA_PORT;
 * - port HTTP_PORT is available.
 * <p/>
 * Side effects:
 * - creates a new keyspace "examples" in the cluster. If a keyspace with this name already exists, it will be reused;
 * - creates a table "examples.forward_paging_web_ui". If it already exists, it will be reused;
 * - inserts data in the table;
 * - launches a web server listening on HTTP_PORT.
 */
public class ForwardPagingWebUi {
    static final String[] CONTACT_POINTS = {"127.0.0.1"};
    static final int CASSANDRA_PORT = 9042;

    static final int HTTP_PORT = 8080;

    static final int ITEMS_PER_PAGE = 10;

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
        session.execute("CREATE TABLE IF NOT EXISTS examples.forward_paging_web_ui(" +
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
            session.execute("INSERT INTO examples.forward_paging_web_ui (user_id, added, video_id, title) " +
                            "VALUES (0, ?, ?, ?)",
                    added, i, "video " + i);
        }
    }

    /**
     * Handles requests to /videos[?page=xxx]
     */
    static class VideosHandler implements HttpHandler {
        private final Session session;
        private final PreparedStatement selectVideos;

        VideosHandler(Session session) {
            this.session = session;
            this.selectVideos = session.prepare("SELECT added, video_id, title FROM examples.forward_paging_web_ui WHERE user_id = 0");
        }

        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            try {
                StringBuilder response = new StringBuilder();

                Statement statement = selectVideos.bind().setFetchSize(ITEMS_PER_PAGE);
                String currentState = extractPagingStateFromQueryString(httpExchange);
                if (currentState != null) {
                    statement.setPagingState(PagingState.fromString(currentState));
                }
                ResultSet rs = session.execute(statement);
                PagingState nextState = rs.getExecutionInfo().getPagingState();

                int remaining = rs.getAvailableWithoutFetching();
                if (remaining == 0) {
                    // Note that this can happen not only if there are no results at all, but also on the last page if
                    // the total size is an exact multiple of the page size.
                    // See https://issues.apache.org/jira/browse/CASSANDRA-8871
                    renderNoResults(response);
                } else {
                    renderTableHeader(response);
                    SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT);
                    for (Row row : rs) {
                        renderTr(response, row.getInt("video_id"), row.getString("title"), dateFormat.format(row.getTimestamp("added")));
                        // Make sure we don't go past the current page (we don't want the driver to fetch the next one)
                        if (--remaining == 0)
                            break;
                    }
                    renderTableFooter(response);
                }

                if (nextState != null) {
                    renderNextPageLink(response, nextState.toString());
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
        private String extractPagingStateFromQueryString(HttpExchange httpExchange) {
            String query = httpExchange.getRequestURI().getQuery();
            if (query == null) {
                return null;
            }
            Matcher matcher = QUERY_PATTERN.matcher(query);
            return matcher.matches() ? matcher.group(1) : null;
        }

        private static final Pattern QUERY_PATTERN = Pattern.compile("page=(.*)");

        private void renderNoResults(StringBuilder response) {
            response.append("No more results");
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

        private void renderNextPageLink(StringBuilder response, String nextPage) {
            response.append(String.format("<a href=\"/videos?page=%s\">Next</a>", nextPage));
        }

        private static void reply(HttpExchange httpExchange, int status, String body) throws IOException {
            httpExchange.getResponseHeaders().add("Content-type", "text/html; charset=utf-8");
            httpExchange.sendResponseHeaders(status, body.length());
            OutputStream os = httpExchange.getResponseBody();
            os.write(body.getBytes(Charsets.UTF_8));
            os.close();
        }
    }

    private static final String DATE_FORMAT = "yyyy-MM-dd";
}
