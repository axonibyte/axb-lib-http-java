/*
 * Copyright (c) 2020-2024 Axonibyte Innovations, LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.axonibyte.lib.http;

import static spark.Spark.before;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.staticFiles;
import static spark.Spark.stop;
import static spark.Spark.webSocket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.axonibyte.lib.http.rest.Endpoint;
import com.axonibyte.lib.http.rest.HTTPMethod;
import com.axonibyte.lib.http.ws.WSConnectionAuthenticator;
import com.axonibyte.lib.http.ws.WSHandler;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Request;
import spark.Response;
import spark.Route;

/**
 * API Driver; manages RESTful and WebSocket API jSONEndpoints.
 * 
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class APIDriver implements Runnable {

  private final Logger logger = LoggerFactory.getLogger(APIDriver.class);
  
  private final int port; // the port that the front end should run on
  private final Endpoint endpoints[]; // the pages that will be accessible
  private final String wsRoute; // the WebSocket route, if applicable
  private final String allowedMethods; // methods that can be accepted
  private final Set<String> allowedOrigins; // the allowed origins for CORS
  private final String allowedHeaders; // headers that can be accepted
  private final String exposedHeaders; // headers that should be exposed
  private final boolean anyOriginAllowed; // true iff "*" was configured
  
  private Thread thread = null; // the thread to run the frontend
  
  private APIDriver(
      int port,
      String allowedMethods,
      Collection<String> allowedOrigins,
      String allowedHeaders,
      String exposedHeaders,
      String publicFolder,
      Endpoint[] endpoints,
      String wsRoute
  ) {
    this.port = port;
    this.allowedMethods = allowedMethods;
    this.allowedOrigins = Set.copyOf(allowedOrigins);
    this.anyOriginAllowed = allowedOrigins.contains("*");
    this.allowedHeaders = allowedHeaders;
    this.exposedHeaders = exposedHeaders;
    this.endpoints = endpoints;
    if(null == wsRoute)
      this.wsRoute = null;
    else if(wsRoute.contains(" "))
      throw new RuntimeException("WebSocket endpoint may not contain whitsapces.");
    else {
      this.wsRoute = (wsRoute.charAt(0) == '/' ? "" : "/") + wsRoute;
      WSHandler.launchDispatcher();
    }
    
    staticFiles.location(publicFolder); // relative to the root of the classpath
  }

  /**
   * Runs the front end in a separate thread so that it can be halted externally.
   */
  @Override public void run() {
    if(null != wsRoute)
      webSocket(wsRoute, WSHandler.class);

    logger.info("Exposing API on port {}.", port);
    port(port);
    
    before((req, res) -> {
      applyCORSHeaders(req, res);
      res.header("Content-Type", "application/json");
    });

    options("*", (req, res)-> {
      // Advertise only what is actually configured. Reflecting the client's requested
      // headers and methods back at it makes the preflight response meaningless, since it
      // approves whatever was asked for.
      applyCORSHeaders(req, res);
      return "OK";
    });
    
    // iterate through initialized pages and determine the appropriate HTTP request types
    for(Endpoint endpoint : endpoints)
      for(HTTPMethod method : endpoint.getHTTPMethods())
        method.getSparkMethod().accept(endpoint.getRoute(), endpoint::onRequest);
    
    // this is a patch because the WebSocket route overrides Spark.notFound
    final Route route = (req, res) -> {
      if(null != wsRoute && !req.raw().getPathInfo().equals(wsRoute)) {
        logger.info(
            "User at {} attempted to hit nonexistent endpoint {} {}",
            req.ip(),
            req.requestMethod(),
            req.raw().getRequestURI());
        res.type("application/json");
        res.status(404);
        return new JSONObject()
        .put("status", "error")
        .put("info", "Resource not found.")
            .toString(2) + '\n';
      }

      return null;
    };
    
    for(var method : HTTPMethod.values())
      for(var version : APIVersion.values())
        method.getSparkMethod().accept(version.versionize("*"), route);
  }
  
  /**
   * Stops the web server.
   */
  public void halt() {
    stop();
  }

  /**
   * Applies the CORS response headers for a request.
   *
   * <p>{@code Access-Control-Allow-Origin} accepts a single origin or {@code *} --
   * never a list. Emitting a comma-joined list of configured origins produces a
   * header that every browser rejects, which silently blocks all of them. So the
   * request's {@code Origin} is matched against the allowlist and only the matching
   * value is echoed back.</p>
   *
   * @param req the HTTP request
   * @param res the HTTP response
   */
  private void applyCORSHeaders(Request req, Response res) {
    String origin = req.headers("Origin");
    String allow = resolveAllowOrigin(origin, allowedOrigins, anyOriginAllowed);

    // The response varies by Origin whenever the allowlist is consulted, so shared
    // caches must key on it.
    if(!anyOriginAllowed) res.header("Vary", "Origin");

    if(null == allow) {
      // No matching origin: emit no Access-Control-Allow-Origin at all, so the browser
      // blocks the cross-origin read. That is the desired outcome.
      if(null != origin)
        logger.debug("Rejected CORS origin {} (not in allowlist).", origin);
      return;
    }

    res.header("Access-Control-Allow-Origin", allow);
    res.header("Access-Control-Allow-Methods", allowedMethods);
    res.header("Access-Control-Allow-Headers", allowedHeaders);
    res.header("Access-Control-Expose-Headers", exposedHeaders);
  }

  /**
   * Determines the value of the {@code Access-Control-Allow-Origin} response header
   * for a given request origin.
   *
   * <p>Package-private and pure so that the matching rule can be tested directly,
   * without standing up a server.</p>
   *
   * @param requestOrigin the request's {@code Origin} header, possibly {@code null}
   * @param allowedOrigins the configured allowlist
   * @param anyOriginAllowed {@code true} iff the allowlist contained {@code *}
   * @return the header value to emit, or {@code null} if the header should be omitted
   */
  static String resolveAllowOrigin(
      String requestOrigin, Set<String> allowedOrigins, boolean anyOriginAllowed) {
    if(anyOriginAllowed) return "*";
    if(null == requestOrigin) return null;
    return allowedOrigins.contains(requestOrigin) ? requestOrigin : null;
  }

  /**
   * Builds the API driver and executes it in its own thread.
   *
   * @author Caleb L. Power <cpower@axonibyte.com>
   */
  public static class Builder {
    
    private static final String[] ALLOWED_METHODS_DEFAULT = {
      "DELETE",
      "POST",
      "GET",
      "PATCH",
      "PUT",
      "OPTIONS"
    };
    
    private static final String[] ALLOWED_ORIGINS_DEFAULT = {
      "*"
    };
    
    private static final String[] ALLOWED_HEADERS_DEFAULT = {
      "Content-Type",
      "Access-Control-Allow-Headers",
      "Access-Control-Allow-Origin",
      "Access-Control-Allow-Methods",
      "Authorization",
      "X-Requested-With"
    };
    
    private static final String[] EXPOSED_HEADERS_DEFAULT = {
      "Content-Type",
      "Content-Length"
    };
    
    private List<Endpoint> endpoints = new ArrayList<>();
    private List<String> allowedMethods = new ArrayList<>(
        Arrays.asList(ALLOWED_METHODS_DEFAULT));
    private List<String> allowedOrigins = new ArrayList<>(
        Arrays.asList(ALLOWED_ORIGINS_DEFAULT));
    private List<String> allowedHeaders = new ArrayList<>(
        Arrays.asList(ALLOWED_HEADERS_DEFAULT));
    private List<String> exposedHeaders = new ArrayList<>(
        Arrays.asList(EXPOSED_HEADERS_DEFAULT));
    private String publicFolder = ".";
    private String wsRoute = null;
    private int port = 0;
    private WSConnectionAuthenticator wsConnectionAuthenticator = null;

    /**
     * Sets the {@link WSConnectionAuthenticator} used to vet incoming WebSocket
     * connections on the route configured via {@link #setWSRoute(String)}.
     *
     * <p>If this is not set, every connection that reaches the WebSocket route is
     * accepted. Any deployment whose WebSocket route is reachable from an untrusted
     * network should set one.</p>
     *
     * @param authenticator the {@link WSConnectionAuthenticator} to install
     * @return this {@link Builder}
     */
    public Builder setWSConnectionAuthenticator(WSConnectionAuthenticator authenticator) {
      this.wsConnectionAuthenticator = authenticator;
      return this;
    }

    /**
     * Clears all entries previously destined to be values in the
     * {@code Access-Control-Allow-Methods} header.
     *
     * @return this {@link Builder}
     */
    public Builder clearAllowedMethods() {
      allowedMethods.clear();
      return this;
    }

    /**
     * Adds one or more methods to the list of values destined for use in the
     * {@code Access-Control-Allow-Methods} header.
     *
     * @param methods varargs array of valid methods
     * @return this {@link Builder}
     */
    public Builder addAllowedMethods(String... methods) {
      allowedMethods.addAll(
          Arrays.asList(methods));
      return this;
    }

    /**
     * Clears all entries previously destined to be values in the
     * {@code Access-Control-Allow-Origin} header.
     *
     * @return this {@link Builder}
     */
    public Builder clearAllowedOrigins() {
      allowedOrigins.clear();
      return this;
    }

    /**
     * Adds one or more methods to the list of values destined for use in the
     * {@code Access-Control-ALlow-Origin} header.
     *
     * @param origins varargs array of valid origins
     * @return this {@link Builder}
     */
    public Builder addAllowedOrigins(String... origins) {
      allowedOrigins.addAll(
          Arrays.asList(origins));
      return this;
    }

    /**
     * Clears all entries previously destined to be values in the
     * {@code Access-Control-Allow-Headers} header.
     *
     * @return this {@link Builder}
     */
    public Builder clearAllowedHeaders() {
      allowedHeaders.clear();
      return this;
    }

    /**
     * Adds one or more headers to the list of values destined for use in the
     * {@code Access-Control-Allow-Headers} header.
     *
     * @param headers varargs array of allowed headers
     * @return this {@link Builder}
     */
    public Builder addAllowedHeaders(String... headers) {
      allowedHeaders.addAll(
          Arrays.asList(headers));
      return this;
    }

    /**
     * Clears all entries previously destined to be values in the
     * {@code Access-Control-Expose-Headers} header.
     *
     * @return this {@link Builder}
     */
    public Builder clearExposedHeaders() {
      exposedHeaders.clear();
      return this;
    }

    /**
     * Adds one or more headers to the list of values destined for use in the
     * {@code Access-Control-Expose-Headers} header.
     *
     * @param headers varargs array of the exposed headers
     * @return this {@link Builder}
     */
    public Builder addExposedHeaders(String... headers) {
      exposedHeaders.addAll(
          Arrays.asList(headers));
      return this;
    }

    /**
     * Establishes the public folder that will be served to the public in the
     * event a route isn't available. Note that if this program is indeed going
     * to be packaged as a JAR, then {@code /} denotes the root of the resources
     * folder... that is, if assets are generally stored at
     * {@code src/main/resources/public} then the argument for this method should
     * be {@code /public}.
     *
     * @param the path to the public folder
     * @return this {@link Builder}
     */
    public Builder setPublicFolder(String publicFolder) {
      this.publicFolder = publicFolder;
      return this;
    }

    /**
     * Sets the WebSocket route, or disableds the WebSocket route entirely.
     *
     * @param wsRoute the path for the WebSocket endpoint, or {@code null} if
     *        WebSockets should be disabled altogether
     * @return this {@link Builder}
     */
    public Builder setWSRoute(String wsRoute) {
      this.wsRoute = wsRoute;
      return this;
    }

    /**
     * Adds one or more {@link Endpoint} objects to be accessible by any actor
     * that has the ability to hit the port.
     *
     * @param endpoints a varargs array of {@link Endpoint} objects
     * @return this {@link Builder}
     */
    public Builder addEndpoints(Endpoint... endpoints) {
      this.endpoints.addAll(
          Arrays.asList(endpoints));
      return this;
    }

    /**
     * Sets the port on which the API will be exposed.
     *
     * @param port the port number
     * @return this {@link Builder}
     */
    public Builder setPort(int port) {
      this.port = port;
      return this;
    }

    /**
     * Builds, launches, and returns an {@link APIDriver} based on specified
     * parameters.
     *
     * @return the new {@link APIDriver}
     */
    public APIDriver build() {
      // Installed before the driver thread starts so that no connection can be accepted
      // ahead of the authenticator being in place.
      if(null != wsConnectionAuthenticator)
        WSHandler.setConnectionAuthenticator(wsConnectionAuthenticator);

      APIDriver apiDriver = new APIDriver(
          port,
          String.join(", ", allowedMethods),
          allowedOrigins,
          String.join(", ", allowedHeaders),
          String.join(", ", exposedHeaders),
          publicFolder,
          endpoints.toArray(new Endpoint[endpoints.size()]),
          wsRoute);
      apiDriver.thread = new Thread(apiDriver);
      apiDriver.thread.setDaemon(false);
      apiDriver.thread.start();
      return apiDriver;
    }
  }
  
}
